package com.chrauto.ai.accessibility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.chrauto.ai.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/** Owns one consented projection session. Frames are transient and never written to disk. */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var outWidth = 0
    private var outHeight = 0
    private val stopped = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("screen-frame-capture").also { it.start(); handler = Handler(it.looper) }
        createChannel()
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val stopPending = android.app.PendingIntent.getService(this, 4018, stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_eye).setContentTitle("Screen-aware task is running")
            .setContentText("Screenshots are sent to Gemini for this task. Capture is not saved.")
            .addAction(R.drawable.ic_stop, "Stop capture", stopPending)
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        ScreenCaptureBridge.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { com.chrauto.ai.agent.AgentController.stop(); stopProjection(); stopSelf(); return START_NOT_STICKY }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (resultCode == 0 || data == null) { stopProjection(); stopSelf(); return START_NOT_STICKY }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = if (Build.VERSION.SDK_INT >= 30) {
                val b = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
                DisplayMetrics().also { it.widthPixels = b.width(); it.heightPixels = b.height(); it.densityDpi = resources.displayMetrics.densityDpi }
            } else resources.displayMetrics
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopProjection(); stopSelf() }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width > 0 && height > 0) configureReader(width, height, resources.displayMetrics.densityDpi, createDisplay = false)
                }
            }, handler)
            configureReader(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, createDisplay = true)
        } catch (_: Exception) {
            stopProjection(); stopSelf()
        }
        return START_NOT_STICKY
    }

    @Synchronized private fun configureReader(width: Int, height: Int, density: Int, createDisplay: Boolean) {
        sourceWidth = width; sourceHeight = height
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(width, height))
        outWidth = max(1, (width * scale).roundToInt()); outHeight = max(1, (height * scale).roundToInt())
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = ImageReader.newInstance(outWidth, outHeight, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ }, handler)
        }
        if (createDisplay) {
            display = projection?.createVirtualDisplay("ChrautoScreenCapture", outWidth, outHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
        } else {
            display?.resize(outWidth, outHeight, density)
            display?.setSurface(reader?.surface)
        }
    }

    suspend fun captureFrame(): com.chrauto.ai.agent.VisionFrame? = withContext(Dispatchers.IO) {
        val deadline = android.os.SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS
        while (!stopped.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
            val image = runCatching { reader?.acquireLatestImage() }.getOrNull()
            if (image != null) return@withContext image.toJpegFrame()
            delay(50)
        }
        null
    }

    private fun Image.toJpegFrame(): com.chrauto.ai.agent.VisionFrame? {
        try {
            val plane = planes.firstOrNull() ?: return null
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bitmap = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            return try {
                ByteArrayOutputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
                    com.chrauto.ai.agent.VisionFrame(out.toByteArray(), width, height)
                }
            } finally { bitmap.recycle() }
        } catch (_: Exception) { return null } finally { close() }
    }

    fun stopProjection() {
        if (!stopped.compareAndSet(false, true)) return
        ScreenCaptureBridge.detach(this)
        reader?.setOnImageAvailableListener(null, null)
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        val p = projection; projection = null
        runCatching { p?.stop() }
        worker?.quitSafely(); worker = null; handler = null
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() { stopProjection(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen-aware tasks", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val ACTION_START = "com.chrauto.ai.screen.START"
        const val ACTION_STOP = "com.chrauto.ai.screen.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL = "screen_capture"
        private const val NOTIFICATION_ID = 4017
        private const val MAX_SIDE = 1280
        private const val JPEG_QUALITY = 70
        private const val CAPTURE_TIMEOUT_MS = 3_000L
    }
}

object ScreenCaptureBridge {
    @Volatile private var service: ScreenCaptureService? = null
    @Volatile private var appContext: Context? = null
    @Volatile var requested: Boolean = false
        private set
    val active: Boolean get() = requested
    internal fun attach(value: ScreenCaptureService) { service = value }
    internal fun detach(value: ScreenCaptureService) { if (service === value) service = null; requested = false }

    fun start(context: Context, resultCode: Int, data: Intent) {
        appContext = context.applicationContext
        requested = true
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    suspend fun captureFrame(): com.chrauto.ai.agent.VisionFrame? {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        while (service == null && requested && android.os.SystemClock.elapsedRealtime() < deadline) delay(50)
        return service?.captureFrame()
    }

    fun stop(context: Context? = appContext) {
        requested = false
        context?.applicationContext?.let { ctx -> runCatching { ctx.stopService(Intent(ctx, ScreenCaptureService::class.java)) } }
        appContext = null
    }
}
