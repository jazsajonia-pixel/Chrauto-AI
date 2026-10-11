package com.chrauto.ai.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.chrauto.ai.MainActivity
import com.chrauto.ai.R
import com.chrauto.ai.agent.AgentController
import com.chrauto.ai.data.SettingsStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the microphone available for "Chron" while the app is in the
 * background. Android shows its mic indicator and this notification while it runs.
 */
class ChronService : Service() {
    companion object {
        private const val CHANNEL = "chron"
        private const val ID = 7
        const val ACTION_TALK = "com.chrauto.ai.chron.TALK"
        const val ACTION_STOP_TASK = "com.chrauto.ai.chron.STOP_TASK"
        const val ACTION_TURN_OFF = "com.chrauto.ai.chron.OFF"
        @Volatile var running = false
            private set

        fun start(context: Context): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            return runCatching { ContextCompat.startForegroundService(context, Intent(context, ChronService::class.java)); true }.getOrDefault(false)
        }
        fun stop(context: Context) { context.stopService(Intent(context, ChronService::class.java)) }
    }

    private val scope = MainScope()
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ChronEngine.setScreenOn(intent.action != Intent.ACTION_SCREEN_OFF)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chron voice assistant", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Chron is listening for its name"; setShowBadge(false)
        })
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, notification(ChronEngine.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, notification(ChronEngine.state.value))
        }.isSuccess
        if (!ok) { stopSelf(); return }
        running = true
        ChronEngine.enableWake(this)
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            ChronEngine.state.map { it.mode to it.error }.distinctUntilChanged().collect { nm.notify(ID, notification(ChronEngine.state.value)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TALK -> ChronEngine.listenNow(this)
            ACTION_STOP_TASK -> AgentController.stop()
            ACTION_TURN_OFF -> { SettingsStore(this).chronEnabled = false; stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ChronEngine.disableWake()
        runCatching { unregisterReceiver(screen) }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(s: ChronEngine.State): Notification {
        val name = SettingsStore(this).wakeName
        val text = when (s.mode) {
            ChronEngine.Mode.ACTIVE -> "Listening…"
            ChronEngine.Mode.THINKING -> "Thinking…"
            ChronEngine.Mode.SPEAKING -> "Talking"
            ChronEngine.Mode.PASSIVE -> "Say “$name, …” anytime"
            ChronEngine.Mode.OFF -> s.error ?: "Paused while the screen is off"
        }
        fun action(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, ChronService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("$name is here")
            .setContentText(text)
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Talk", action(ACTION_TALK, 1))
            .addAction(0, "Stop task", action(ACTION_STOP_TASK, 2))
            .addAction(0, "Turn off", action(ACTION_TURN_OFF, 3))
            .build()
    }
}
