package com.chrauto.ai.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.chrauto.ai.agent.AgentController
import com.chrauto.ai.agent.RunStatus
import com.chrauto.ai.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Chron" hands-free voice assistant.
 *
 *  PASSIVE  – quietly listens for "Chron …" (restarting the recogniser after every sentence).
 *  ACTIVE   – listening to a command (after the wake word, the mic button, or as a follow-up).
 *  THINKING – deciding what to do / asking the AI.
 *  SPEAKING – replying out loud; the mic is paused so Chron doesn't hear itself.
 *
 * After Chron answers a question it keeps listening for a few seconds without needing the
 * wake word again, so you can have a back-and-forth conversation.
 */
object ChronEngine {
    enum class Mode { OFF, PASSIVE, ACTIVE, THINKING, SPEAKING }
    data class State(
        val mode: Mode = Mode.OFF,
        val partial: String = "",
        val heard: String = "",
        val reply: String = "",
        val wakeOn: Boolean = false,
        val error: String? = null,
        val conversation: List<Pair<String, String>> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ctx: Context? = null
    private var recognizer: SpeechRecognizer? = null
    private var sessionActive = false
    private var wakeOn = false
    private var screenOn = true
    private var speaking = false
    private var followUpUntil = 0L
    private var errorStreak = 0
    private var onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val conversation = ArrayDeque<Pair<String, String>>()
    private val restart = Runnable { if (canListenPassively()) startSession(false) }

    private fun set(transform: (State) -> State) { _state.value = transform(_state.value) }
    private fun settings() = ctx?.let(::SettingsStore)

    fun attach(context: Context) {
        if (ctx == null) ctx = context.applicationContext
        Speaker.init(context)
    }

    fun enableWake(context: Context) {
        attach(context)
        wakeOn = true
        set { it.copy(wakeOn = true, error = null) }
        if (!sessionActive && !speaking && _state.value.mode != Mode.THINKING) startSession(false)
    }

    fun disableWake() {
        wakeOn = false
        set { it.copy(wakeOn = false) }
        if (!sessionActive) { stopRecognizer(); setMode(Mode.OFF) }
    }

    /** Mic button / notification "Talk": listen for one command right now. */
    fun listenNow(context: Context) {
        attach(context)
        if (sessionActive) { finishSession(); return } // second tap cancels
        if (speaking) Speaker.stop()
        haptic()
        startSession(true)
    }

    fun setScreenOn(on: Boolean) {
        screenOn = on
        if (!on && settings()?.pauseWhenScreenOff != false && !sessionActive) { stopRecognizer(); setMode(if (wakeOn) Mode.OFF else Mode.OFF) }
        else if (on) scheduleRestart(400)
    }

    fun onSpeaking(isSpeaking: Boolean) {
        if (speaking == isSpeaking) return
        speaking = isSpeaking
        if (isSpeaking) { stopRecognizer(); sessionActive = false; setMode(Mode.SPEAKING) }
        else if (System.currentTimeMillis() < followUpUntil) main.postDelayed({ if (!speaking) startSession(true) }, 150)
        else finishSession()
    }

    fun recentlySpoke(ms: Long = 2_500) = System.currentTimeMillis() - Speaker.lastSpokeAt < ms

    private fun canListenPassively() = wakeOn && !speaking && !sessionActive && (screenOn || settings()?.pauseWhenScreenOff == false) &&
        _state.value.mode != Mode.THINKING

    private fun setMode(mode: Mode) = set { it.copy(mode = mode, partial = if (mode == Mode.ACTIVE || mode == Mode.PASSIVE) "" else it.partial) }

    private fun hasMic(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startSession(active: Boolean) {
        main.removeCallbacks(restart)
        stopRecognizer()
        val c = ctx ?: return
        if (!hasMic(c)) { set { it.copy(error = "Microphone permission needed", mode = Mode.OFF) }; return }
        val r = create(c) ?: run { set { it.copy(error = "Speech recognition isn't available on this phone", mode = Mode.OFF) }; return }
        recognizer = r
        sessionActive = active
        r.setRecognitionListener(Listener(r, active))
        setMode(if (active) Mode.ACTIVE else Mode.PASSIVE)
        set { it.copy(error = null) }
        runCatching { r.startListening(intent(c, active)) }.onFailure { errorStreak++; scheduleRestart(backoff()) }
    }

    private fun create(c: Context): SpeechRecognizer? = runCatching {
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(c))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
        else if (SpeechRecognizer.isRecognitionAvailable(c)) SpeechRecognizer.createSpeechRecognizer(c)
        else null
    }.getOrNull()

    private fun intent(c: Context, active: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)
        // Commands end after a short pause so Chron reacts quickly.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (active) 900L else 700L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (active) 700L else 500L)
        if (active) putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500L)
    }

    private fun stopRecognizer() {
        recognizer?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        recognizer = null
    }

    private fun finishSession() {
        stopRecognizer()
        sessionActive = false
        followUpUntil = 0
        if (canListenPassively()) scheduleRestart(150) else if (!speaking && _state.value.mode != Mode.THINKING) setMode(Mode.OFF)
    }

    private fun scheduleRestart(ms: Long) { main.removeCallbacks(restart); main.postDelayed(restart, ms) }
    private fun backoff() = minOf(5_000L, 300L shl minOf(errorStreak, 4))

    private class Listener(private val r: SpeechRecognizer, private val active: Boolean) : RecognitionListener {
        private var woke = false
        private fun stale() = r !== recognizer

        override fun onReadyForSpeech(params: Bundle?) { errorStreak = 0 }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (stale()) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) return
            if (active) { set { it.copy(partial = text) }; return }
            val match = WakeWord.find(text, settings()?.wakeName ?: "Chron")
            if (match != null) {
                if (!woke) { woke = true; haptic(); setMode(Mode.ACTIVE) }
                set { it.copy(partial = match.command) }
            }
        }

        override fun onResults(results: Bundle?) {
            if (stale()) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            stopRecognizer()
            val name = settings()?.wakeName ?: "Chron"
            if (active) {
                val text = list.firstOrNull().orEmpty().let { WakeWord.find(it, name)?.command ?: it }.trim()
                if (text.isBlank()) finishSession() else handle(text)
                return
            }
            val match = WakeWord.findAny(list, name)
            when {
                match == null -> { sessionActive = false; scheduleRestart(80) }
                match.wakeOnly -> { chime(); sessionActive = false; main.postDelayed({ startSession(true) }, 180) }
                else -> handle(match.command)
            }
        }

        override fun onError(error: Int) {
            if (stale()) return
            stopRecognizer()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    errorStreak = 0
                    if (active) finishSession() else { sessionActive = false; scheduleRestart(80) }
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    // On-device model missing: ask the system to download it, use the regular recogniser meanwhile.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ctx?.let { c -> runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(c).apply { triggerModelDownload(intent(c, false)); destroy() } } }
                    onDevice = false; sessionActive = false; scheduleRestart(120)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { sessionActive = false; set { it.copy(error = "Microphone permission needed", mode = Mode.OFF) } }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { sessionActive = false; scheduleRestart(700) }
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> { sessionActive = false; scheduleRestart(4_000) }
                else -> {
                    errorStreak++
                    if (errorStreak >= 4 && onDevice) onDevice = false
                    sessionActive = false
                    if (active) set { it.copy(error = "Voice error ($error)") }
                    if (wakeOn) scheduleRestart(backoff()) else setMode(Mode.OFF)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun handle(text: String) {
        stopRecognizer()
        sessionActive = false
        set { it.copy(mode = Mode.THINKING, heard = text, partial = "", reply = "") }
        scope.launch {
            val s = settings()
            val running = AgentController.state.value.takeIf { it.status == RunStatus.RUNNING }?.task
            val route = CommandRouter.route(text, s?.config()?.takeIf { it.isUsable }, CommandRouter.Context(running, conversation.toList()))
            when (route) {
                is Route.Stop -> { AgentController.stop(); reply(text, route.reply, followUp = false) }
                is Route.Task -> {
                    val problem = AgentController.startWithSavedSettings(route.task)
                    if (problem != null) reply(text, problem, followUp = false)
                    else reply(text, route.ack ?: listOf("On it!", "Okay!", "Sure thing!", "You got it!").random(), followUp = false, optional = true)
                }
                is Route.Answer -> reply(text, route.reply, followUp = true)
            }
        }
    }

    private fun reply(heard: String, text: String, followUp: Boolean, optional: Boolean = false) {
        conversation.addLast(heard to text); while (conversation.size > 8) conversation.removeFirst()
        set { it.copy(reply = text, conversation = conversation.toList()) }
        followUpUntil = if (followUp) System.currentTimeMillis() + 9_000 else 0
        if (optional && settings()?.voiceReplies == false) { setMode(Mode.OFF); finishSession(); return }
        setMode(Mode.SPEAKING)
        if (!Speaker.say(text)) { if (followUp) startSession(true) else finishSession() }
    }

    /** Debug/screenshot helper: shows a scripted state without using the microphone. */
    fun preview(state: State) { _state.value = state }

    private fun haptic() {
        val c = ctx ?: return
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (c.getSystemService(VibratorManager::class.java)).defaultVibrator
            else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun chime() {
        haptic()
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            main.postDelayed({ tone.release() }, 400)
        }
    }
}
