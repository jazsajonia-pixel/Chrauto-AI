package com.chrauto.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * One shared text-to-speech voice for Chron.
 *
 * Supports streaming: [begin] a reply, [append] sentences as they arrive from the AI (so Chron starts
 * talking after the first sentence), then [end]. Music and videos are ducked while Chron talks, and
 * [ChronEngine] is told when speech starts/stops so it can keep the mic open for interruptions.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var app: Context? = null
    @Volatile private var ready = false
    @Volatile var lastSpokeAt = 0L
        private set
    private val main = Handler(Looper.getMainLooper())

    // Current reply ("session") bookkeeping — main thread only.
    private var session = 0
    private val pending = mutableSetOf<String>()
    private var ended = true
    private var active = false
    private var pendingDone: (() -> Unit)? = null
    private val spoken = StringBuilder()
    private var focus: AudioFocusRequest? = null
    private var queuedBeforeReady = mutableListOf<String>()

    /** Everything Chron has said (or queued) in the current reply; used to recognise its own echo. */
    val spokenText: String get() = spoken.toString()
    val isSpeaking: Boolean get() = active

    var rate = 1.1f
        set(value) { field = value.coerceIn(0.6f, 2f); tts?.setSpeechRate(field) }

    private var languageTag: String = ""

    fun init(context: Context) {
        if (tts != null) return
        app = context.applicationContext
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) return@TextToSpeech
            applyLanguage()
            tts?.setSpeechRate(rate)
            tts?.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { main.post { started(utteranceId) } }
                override fun onDone(utteranceId: String?) { main.post { done(utteranceId) } }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { main.post { done(utteranceId) } }
                override fun onError(utteranceId: String?, errorCode: Int) { main.post { done(utteranceId) } }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { done(utteranceId) } }
            })
            main.post {
                val early = queuedBeforeReady.toList(); queuedBeforeReady.clear()
                early.forEach { enqueue(it) }
            }
        }
    }

    /** "" = phone language, otherwise a BCP-47 tag like "en-PH" or "fil-PH". */
    fun setLanguage(tag: String) {
        if (tag == languageTag) return
        languageTag = tag
        if (ready) applyLanguage()
    }

    private fun applyLanguage() {
        val t = tts ?: return
        val locale = if (languageTag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(languageTag)
        val res = runCatching { t.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) runCatching { t.setLanguage(Locale.getDefault()) }
    }

    val isAvailable get() = ready

    /** Starts a new reply, cutting off anything still being said. */
    fun begin(onDone: (() -> Unit)? = null) {
        val wasActive = active
        session++
        pending.clear()
        spoken.clear()
        queuedBeforeReady.clear()
        ended = false
        pendingDone = onDone
        if (wasActive) { runCatching { tts?.stop() } }
    }

    /** Queues one more piece of the current reply. */
    fun append(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        if (spoken.isNotEmpty()) spoken.append(' ')
        spoken.append(t)
        if (!ready) { queuedBeforeReady += t; return }
        enqueue(t)
    }

    private fun enqueue(t: String) {
        val id = "chron-$session-${System.nanoTime()}"
        pending += id
        lastSpokeAt = System.currentTimeMillis()
        if (!active) requestFocus()
        tts?.speak(t.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_ADD, null, id)
    }

    /** No more pieces are coming; finishes once the queue has been spoken. Returns false if nothing will be said. */
    fun end(): Boolean {
        ended = true
        val anything = spoken.isNotEmpty()
        if (pending.isEmpty() && queuedBeforeReady.isEmpty()) finish()
        return anything && ready
    }

    /** Speaks one complete message. Returns false (and runs [onDone]) if the voice isn't available. */
    fun say(text: String, onDone: (() -> Unit)? = null): Boolean {
        if (!ready || text.isBlank()) { onDone?.invoke(); return false }
        begin(onDone); append(text.trim().take(600)); return end()
    }

    /** Stops talking immediately (barge-in, Stop button). */
    fun stop() {
        session++
        pending.clear(); queuedBeforeReady.clear()
        ended = true
        runCatching { tts?.stop() }
        finish()
    }

    private fun started(id: String?) {
        if (id == null || !id.startsWith("chron-$session-")) return
        lastSpokeAt = System.currentTimeMillis()
        if (!active) { active = true; ChronEngine.onSpeaking(true) }
    }

    private fun done(id: String?) {
        if (id == null || !pending.remove(id)) return
        lastSpokeAt = System.currentTimeMillis()
        if (ended && pending.isEmpty()) finish()
    }

    private fun finish() {
        val wasActive = active
        active = false
        abandonFocus()
        if (wasActive) ChronEngine.onSpeaking(false)
        pendingDone?.let { pendingDone = null; it() }
    }

    // ---- Audio focus: duck music/videos while Chron talks ----
    private fun requestFocus() {
        val am = app?.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(false)
                .build().also { focus = it }
            runCatching { am.requestAudioFocus(req) }
        }
    }

    private fun abandonFocus() {
        val am = app?.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
    }

    /** True when sound goes to headphones/earbuds, so Chron can be interrupted with a single word. */
    fun headsetConnected(): Boolean {
        val am = app?.getSystemService(AudioManager::class.java) ?: return false
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }.getOrDefault(false)
    }
}
