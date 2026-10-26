package com.chrauto.ai.viber

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.chrauto.ai.tasks.ViberMessage
import java.io.File
import java.util.UUID

/** Android side of the Viber flow: image storage and the share intent. */
object ViberShare {
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    const val MAX_IMAGES_PER_MESSAGE = 10

    fun isInstalled(context: Context) = runCatching { context.packageManager.getPackageInfo(VIBER_PACKAGE, 0); true }.getOrDefault(false)

    private fun dir(context: Context) = File(context.filesDir, "viber").apply { mkdirs() }

    /**
     * Copies a picked image into private storage so the batch survives restarts and doesn't depend on
     * a temporary picker permission. Runs on a background thread (called from Dispatchers.IO).
     */
    fun importImage(context: Context, uri: Uri): Result<String> = runCatching {
        val type = context.contentResolver.getType(uri).orEmpty()
        require(type.startsWith("image/")) { "Only images can be attached" }
        val ext = when (type) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg" }
        val out = File(dir(context), "${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't read the image" }
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    if (total > MAX_IMAGE_BYTES) { o.close(); out.delete(); error("Image is larger than 20 MB") }
                    o.write(buf, 0, n)
                }
            }
        }
        out.absolutePath
    }

    /** Deletes stored images that no task refers to any more. */
    fun cleanup(context: Context, keep: Set<String>) {
        dir(context).listFiles()?.forEach { if (it.absolutePath !in keep) it.delete() }
    }

    private fun uriFor(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))

    /** Builds Viber's own share intent (text + images); the group is then picked on Viber's screen. */
    fun launcher(context: Context) = ShareLauncher { message ->
        if (!isInstalled(context)) return@ShareLauncher "Viber is not installed"
        val files = message.images.filter { File(it).exists() }
        if (files.size != message.images.size) return@ShareLauncher "An attached image is missing from storage"
        val uris = runCatching { files.map { uriFor(context, it) } }.getOrElse { return@ShareLauncher "Couldn't attach images: ${it.message}" }
        val intent = when {
            uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            uris.size == 1 -> Intent(Intent.ACTION_SEND).apply { type = "image/*"; putExtra(Intent.EXTRA_STREAM, uris.single()) }
            else -> Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        }.apply {
            if (message.text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, message.text)
            setPackage(VIBER_PACKAGE)
            if (uris.isNotEmpty()) {
                clipData = ClipData.newRawUri("", uris.first()).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        runCatching { context.startActivity(intent); null }.getOrElse { "Viber couldn't open the share screen: ${it.message}" }
    }
}
