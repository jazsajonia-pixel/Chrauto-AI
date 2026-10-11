package com.chrauto.ai.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** One shared text-to-speech voice. Tells [ChronEngine] when it talks so the mic doesn't hear itself. */
object Speaker {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile var lastSpokeAt = 0L
        private set
    private val main = Handler(Looper.getMainLooper())
    private var pendingDone: (() -> Unit)? = null

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { tts?.language = Locale.getDefault() }
                tts?.setSpeechRate(1.08f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { main.post { ChronEngine.onSpeaking(true) } }
                    override fun onDone(utteranceId: String?) { main.post { finished() } }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { main.post { finished() } }
                    override fun onError(utteranceId: String?, errorCode: Int) { main.post { finished() } }
                })
            }
        }
    }

    private fun finished() {
        ChronEngine.onSpeaking(false)
        pendingDone?.let { pendingDone = null; it() }
    }

    /** Returns false (and runs [onDone] immediately) if the voice isn't available. */
    fun say(text: String, onDone: (() -> Unit)? = null): Boolean {
        val t = text.trim().take(400)
        if (!ready || t.isBlank()) { onDone?.invoke(); return false }
        lastSpokeAt = System.currentTimeMillis()
        pendingDone = onDone
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "chron-${System.nanoTime()}")
        return true
    }

    fun stop() { runCatching { tts?.stop() }; finished() }
}
