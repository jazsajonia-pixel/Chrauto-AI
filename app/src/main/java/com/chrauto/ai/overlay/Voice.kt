package com.chrauto.ai.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import java.util.Locale

/** Wraps Android's SpeechRecognizer for one-shot voice commands. Must be used on the main thread. */
class VoiceCommander(
    private val context: Context,
    private val onListening: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    var listening = false
        private set

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    fun toggle() = if (listening) cancel() else start()

    fun start() {
        if (!hasPermission()) { onError("Microphone permission needed"); return }
        if (!isAvailable()) { onError("Speech recognition isn't available on this phone"); return }
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; onListening(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                finish()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) onError("I didn't catch that") else onResult(text)
            }
            override fun onError(error: Int) {
                finish()
                onError(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs a network connection"
                    else -> "Voice error ($error)"
                })
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching { r.startListening(intent) }.onFailure { finish(); onError("Couldn't start listening") }
    }

    fun cancel() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        if (listening) { listening = false; onListening(false) }
    }

    private fun finish() {
        listening = false
        onListening(false)
        recognizer?.let { r -> runCatching { r.destroy() } }
        recognizer = null
    }
}

/** Short spoken replies from the assistant. */
class Speaker(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) runCatching { tts.language = Locale.getDefault() }
    }

    fun say(text: String) {
        if (ready && text.isNotBlank()) tts.speak(text.take(200), TextToSpeech.QUEUE_FLUSH, null, "chrauto")
    }

    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}
