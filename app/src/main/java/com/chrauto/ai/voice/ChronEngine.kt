package com.chrauto.ai.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
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
import com.chrauto.ai.agent.AgentState
import com.chrauto.ai.agent.RunStatus
import com.chrauto.ai.data.SettingsStore
import com.chrauto.ai.provider.ProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * "Chron" — live, hands-free voice assistant.
 *
 *  PASSIVE  – quietly listens for "Chron …".
 *  ACTIVE   – listening to you (after the wake word, the mic button, or during a live conversation).
 *  THINKING – deciding what to do / waiting for the first words from the AI.
 *  SPEAKING – talking. With "Interrupt anytime" on, the mic stays open (duplex) so you can talk
 *             over Chron: it stops mid-sentence and listens to you.
 *
 * Live conversation: after every reply Chron keeps listening, so you can go back and forth without
 * saying its name, until you say "bye" or stay quiet for a while.
 *
 * Faster: answers stream from the AI and Chron starts speaking after the first sentence; the
 * recogniser is reused instead of re-created; the AI connection is pre-warmed as soon as you start
 * talking; common requests (time, battery, repeat, stop…) are answered on the phone instantly.
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
        val conversation: List<Pair<String, String>> = emptyList(),
        /** In a live conversation: Chron listens for follow-ups without its name. */
        val live: Boolean = false,
        /** The mic is open while Chron talks, so you can interrupt. */
        val duplex: Boolean = false
    )

    private enum class Listen { PASSIVE, ACTIVE, DUPLEX }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    /** Smoothed microphone level 0..1 while listening (drives the bubble's voice ring). */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ctx: Context? = null
    private var recognizer: SpeechRecognizer? = null
    private var token = 0
    private var listening: Listen? = null
    private var wakeOn = false
    private var screenOn = true
    private var speaking = false
    private var interrupting = false
    private var conversationUntil = 0L
    private var errorStreak = 0
    private var onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private var turnJob: Job? = null
    private var lastWarm = 0L
    private var lastReply = ""
    private var voiceTask = false
    private var agentWatch: Job? = null
    private val conversation = ArrayDeque<Pair<String, String>>()
    private val restart = Runnable { if (canListenPassively()) listen(Listen.PASSIVE) }
    /** What Chron was saying when the user interrupted; stripped from the start of their transcript. */
    private var echoContext = ""

    private fun set(transform: (State) -> State) { _state.value = transform(_state.value) }
    private fun settings() = ctx?.let(::SettingsStore)
    private fun wakeName() = settings()?.wakeName ?: "Chron"
    private fun inConversation() = System.currentTimeMillis() < conversationUntil

    // -------------------------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------------------------

    fun attach(context: Context) {
        val first = ctx == null
        if (first) ctx = context.applicationContext
        Speaker.init(context)
        applyVoiceSettings()
        if (first) {
            settings()?.chatHistory()?.let { saved -> conversation.clear(); conversation.addAll(saved.takeLast(10)); set { it.copy(conversation = conversation.toList()) } }
            agentWatch = scope.launch {
                var prev = AgentController.state.value
                AgentController.state.collect { s -> onAgent(prev, s); prev = s }
            }
        }
    }

    /** Re-reads speaking speed and language from settings. */
    fun applyVoiceSettings() {
        val s = settings() ?: return
        Speaker.rate = s.speechRate
        Speaker.setLanguage(s.voiceLanguage)
    }

    fun enableWake(context: Context) {
        attach(context)
        wakeOn = true
        set { it.copy(wakeOn = true, error = null) }
        if (listening == null && !speaking && _state.value.mode != Mode.THINKING) listen(Listen.PASSIVE)
    }

    fun disableWake() {
        wakeOn = false
        set { it.copy(wakeOn = false) }
        if (listening == Listen.PASSIVE || listening == null) { destroyRecognizer(); if (!speaking && _state.value.mode != Mode.THINKING) setMode(Mode.OFF) }
    }

    /** Mic button / notification "Talk": listen right now (also interrupts Chron if it's talking). */
    fun listenNow(context: Context) {
        attach(context)
        if (listening == Listen.ACTIVE && !speaking) { endConversation(); return } // second tap cancels
        haptic()
        if (speaking) interruptSpeech()
        turnJob?.cancel()
        startConversation()
        listen(Listen.ACTIVE)
    }

    /** Stops Chron talking (and any pending answer) without starting to listen. */
    fun hush() {
        turnJob?.cancel()
        conversationUntil = 0
        if (speaking || Speaker.isSpeaking) interruptSpeech()
        finishTurn()
    }

    /** Forgets the conversation (also on "start over"). */
    fun clearConversation() {
        conversation.clear(); lastReply = ""
        settings()?.saveChatHistory(emptyList())
        set { it.copy(conversation = emptyList(), heard = "", reply = "") }
    }

    /** Speaks a status message (e.g. "Okay, stopped.") and records it in the chat. */
    fun announce(text: String, followUp: Boolean = false) {
        if (settings()?.voiceReplies == false) { record("", text); return }
        if (listening == Listen.ACTIVE) { record("", text); return } // don't talk over the user
        reply("", text, followUp)
    }

    fun setScreenOn(on: Boolean) {
        screenOn = on
        if (!on && settings()?.pauseWhenScreenOff != false && listening == Listen.PASSIVE) { stopListening(); setMode(Mode.OFF) }
        else if (on) scheduleRestart(400)
    }

    fun recentlySpoke(ms: Long = 2_500) = System.currentTimeMillis() - Speaker.lastSpokeAt < ms

    /** Debug/screenshot helper: shows a scripted state without using the microphone. */
    fun preview(state: State, level: Float = 0f) { _state.value = state; _level.value = level }

    // -------------------------------------------------------------------------------------------
    // Speech output callbacks (from Speaker)
    // -------------------------------------------------------------------------------------------

    fun onSpeaking(isSpeaking: Boolean) {
        if (speaking == isSpeaking) return
        speaking = isSpeaking
        if (isSpeaking) {
            if (listening == Listen.PASSIVE) stopListening() // don't wake up on our own voice
            setMode(Mode.SPEAKING)
            // Keep listening while talking so the user can interrupt (barge-in).
            if (settings()?.bargeIn != false && hasMic()) main.postDelayed({ if (speaking && listening != Listen.ACTIVE) listen(Listen.DUPLEX) }, 220)
            else stopListening()
            return
        }
        if (interrupting) return
        when {
            listening == Listen.ACTIVE -> Unit // the user already took over
            listening == Listen.DUPLEX && inConversation() -> {
                echoContext = Speaker.spokenText
                listening = Listen.ACTIVE; set { it.copy(duplex = false) }; setMode(Mode.ACTIVE)
            }
            inConversation() -> { stopListening(); main.postDelayed({ if (!speaking && listening == null && _state.value.mode != Mode.THINKING) listen(Listen.ACTIVE) }, 90) }
            else -> finishTurn()
        }
    }

    // -------------------------------------------------------------------------------------------
    // Listening
    // -------------------------------------------------------------------------------------------

    private fun canListenPassively() = wakeOn && !speaking && listening == null && !inConversation() &&
        (screenOn || settings()?.pauseWhenScreenOff == false) && _state.value.mode != Mode.THINKING

    private fun setMode(mode: Mode) = set {
        it.copy(mode = mode, partial = if (mode == Mode.ACTIVE || mode == Mode.PASSIVE) "" else it.partial, live = inConversation(),
            duplex = if (mode == Mode.SPEAKING) it.duplex else false)
    }

    private fun hasMic(c: Context? = ctx) = c != null && ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun listen(kind: Listen) {
        main.removeCallbacks(restart)
        echoContext = ""
        val c = ctx ?: return
        if (!hasMic(c)) { set { it.copy(error = "Microphone permission needed", mode = Mode.OFF) }; return }
        val r = recognizer ?: create(c)?.also { recognizer = it }
            ?: run { set { it.copy(error = "Speech recognition isn't available on this phone", mode = Mode.OFF) }; return }
        runCatching { r.cancel() }
        val id = ++token
        listening = kind
        r.setRecognitionListener(Listener(id))
        when (kind) {
            Listen.PASSIVE -> setMode(Mode.PASSIVE)
            Listen.ACTIVE -> setMode(Mode.ACTIVE)
            Listen.DUPLEX -> set { it.copy(duplex = true) }
        }
        set { it.copy(error = null) }
        runCatching { r.startListening(intent(c, kind)) }.onFailure {
            errorStreak++; destroyRecognizer()
            if (kind == Listen.ACTIVE) main.postDelayed({ listen(Listen.ACTIVE) }, 250) else scheduleRestart(backoff())
        }
    }

    private fun create(c: Context): SpeechRecognizer? = runCatching {
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(c))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
        else if (SpeechRecognizer.isRecognitionAvailable(c)) SpeechRecognizer.createSpeechRecognizer(c)
        else null
    }.getOrNull()

    private fun intent(c: Context, kind: Listen) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)
        settings()?.voiceLanguage?.takeIf { it.isNotBlank() }?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it); putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it) }
        // Short end-of-speech pauses so Chron answers quickly.
        val active = kind != Listen.PASSIVE
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (active) 750L else 600L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (active) 550L else 450L)
        if (kind == Listen.ACTIVE) putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_200L)
    }

    private fun stopListening() {
        token++
        listening = null
        recognizer?.let { runCatching { it.cancel() } }
        _level.value = 0f
        if (_state.value.duplex) set { it.copy(duplex = false) }
    }

    private fun destroyRecognizer() {
        stopListening()
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private fun startConversation() {
        conversationUntil = System.currentTimeMillis() + (settings()?.let { if (it.liveConversation) 12_000L else 9_000L } ?: 9_000L)
        set { it.copy(live = true) }
    }

    private fun endConversation() {
        conversationUntil = 0
        set { it.copy(live = false) }
        finishTurn()
    }

    /** Back to idle: passive wake-word listening if enabled, otherwise off. */
    private fun finishTurn() {
        if (inConversation() && !speaking) { listen(Listen.ACTIVE); return }
        stopListening()
        conversationUntil = 0
        set { it.copy(live = false) }
        if (canListenPassively()) scheduleRestart(150)
        else if (!speaking && _state.value.mode != Mode.THINKING) setMode(Mode.OFF)
    }

    private fun scheduleRestart(ms: Long) { main.removeCallbacks(restart); main.postDelayed(restart, ms) }
    private fun backoff() = minOf(5_000L, 300L shl minOf(errorStreak, 4))

    private fun interruptSpeech() {
        interrupting = true
        try { Speaker.stop() } finally { interrupting = false }
        speaking = false
    }

    /** The user talked over Chron: stop speaking and keep this recognition session as their turn. */
    private fun bargeIn(partial: String) {
        turnJob?.cancel()
        listening = Listen.ACTIVE
        val spoken = Speaker.spokenText
        echoContext = spoken
        interruptSpeech()
        haptic()
        startConversation()
        set { it.copy(mode = Mode.ACTIVE, duplex = false, partial = EchoGuard.stripEcho(partial, spoken, wakeName()), live = true) }
        warmUp()
    }

    private class Listener(private val id: Int) : RecognitionListener {
        private var woke = false
        private val stale get() = id != token
        private var smooth = 0f

        override fun onReadyForSpeech(params: Bundle?) { errorStreak = 0 }
        override fun onBeginningOfSpeech() { if (!stale && listening == Listen.ACTIVE) warmUp() }
        override fun onRmsChanged(rmsdB: Float) {
            if (stale || listening == Listen.PASSIVE) return
            val v = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            smooth = if (v > smooth) smooth * 0.4f + v * 0.6f else smooth * 0.85f + v * 0.15f
            if (kotlin.math.abs(smooth - _level.value) > 0.03f) _level.value = smooth
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (!stale) _level.value = 0f }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (stale) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) return
            when (listening) {
                Listen.DUPLEX -> if (speaking && EchoGuard.isUser(text, Speaker.spokenText, Speaker.headsetConnected(), wakeName())) bargeIn(text)
                Listen.ACTIVE -> {
                    val shown = if (echoContext.isNotBlank()) EchoGuard.stripEcho(text, echoContext, wakeName()) else WakeWord.find(text, wakeName())?.command ?: text
                    set { it.copy(partial = shown) }
                    // "Stop" while a task runs acts instantly, without waiting for the end of the sentence.
                    if (AgentController.isRunning && CommandRouter.quickRoute(shown) is Route.Stop) { stopListening(); handle(shown) }
                }
                Listen.PASSIVE -> {
                    val match = WakeWord.find(text, wakeName()) ?: return
                    if (!woke) { woke = true; haptic(); startConversation(); setMode(Mode.ACTIVE); warmUp() }
                    set { it.copy(partial = match.command) }
                }
                null -> Unit
            }
        }

        override fun onResults(results: Bundle?) {
            if (stale) return
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val kind = listening
            stopListening()
            val name = wakeName()
            when (kind) {
                Listen.ACTIVE -> {
                    val first = list.firstOrNull().orEmpty()
                    val echo = echoContext; echoContext = ""
                    val text = (if (echo.isNotBlank()) EchoGuard.stripEcho(first, echo, name) else WakeWord.find(first, name)?.command ?: first).trim()
                    if (text.isBlank()) endConversation() else handle(text)
                }
                Listen.DUPLEX -> {
                    val first = list.firstOrNull().orEmpty()
                    if (speaking && EchoGuard.isUser(first, Speaker.spokenText, Speaker.headsetConnected(), name)) {
                        val text = EchoGuard.stripEcho(first, Speaker.spokenText, name)
                        interruptSpeech(); haptic(); startConversation()
                        if (text.isBlank()) listen(Listen.ACTIVE) else handle(text)
                    } else if (speaking) main.postDelayed({ if (speaking && listening == null) listen(Listen.DUPLEX) }, 60)
                    else if (inConversation()) listen(Listen.ACTIVE) else finishTurn()
                }
                Listen.PASSIVE -> {
                    val match = WakeWord.findAny(list, name)
                    when {
                        match == null -> scheduleRestart(60)
                        match.wakeOnly -> { chime(); startConversation(); main.postDelayed({ listen(Listen.ACTIVE) }, 150) }
                        else -> { startConversation(); handle(match.command) }
                    }
                }
                null -> Unit
            }
        }

        override fun onError(error: Int) {
            if (stale) return
            val kind = listening
            stopListening()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    errorStreak = 0
                    when {
                        kind == Listen.DUPLEX && speaking -> main.postDelayed({ if (speaking && listening == null) listen(Listen.DUPLEX) }, 60)
                        kind == Listen.PASSIVE -> scheduleRestart(60)
                        kind == Listen.ACTIVE -> endConversation() // you went quiet: end the live conversation
                        else -> finishTurn()
                    }
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    // On-device model missing: ask the system to download it and use the regular recogniser meanwhile.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ctx?.let { c ->
                        runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(c).apply { triggerModelDownload(intent(c, Listen.PASSIVE)); destroy() } }
                    }
                    onDevice = false; destroyRecognizer(); retry(kind, 120)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> set { it.copy(error = "Microphone permission needed", mode = Mode.OFF) }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { destroyRecognizer(); retry(kind, 400) }
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> retry(kind, 4_000)
                SpeechRecognizer.ERROR_CLIENT -> { destroyRecognizer(); retry(kind, 200) }
                else -> {
                    errorStreak++
                    if (errorStreak >= 4 && onDevice) { onDevice = false; destroyRecognizer() }
                    if (kind == Listen.ACTIVE) set { it.copy(error = "Voice error ($error)") }
                    retry(kind, backoff())
                }
            }
        }

        private fun retry(kind: Listen?, delay: Long) {
            when (kind) {
                Listen.DUPLEX -> main.postDelayed({ if (speaking && listening == null) listen(Listen.DUPLEX) }, delay)
                Listen.ACTIVE -> if (errorStreak < 3) main.postDelayed({ if (listening == null && !speaking) listen(Listen.ACTIVE) }, delay) else finishTurn()
                else -> if (wakeOn) scheduleRestart(delay) else setMode(Mode.OFF)
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // Understanding & replying
    // -------------------------------------------------------------------------------------------

    private fun warmUp() {
        val now = System.currentTimeMillis()
        if (now - lastWarm < 45_000) return
        lastWarm = now
        val config = settings()?.config()?.takeIf { it.isUsable } ?: return
        scope.launch(Dispatchers.IO) { runCatching { ProviderFactory.create(config).warmUp() } }
    }

    private fun handle(text: String) {
        stopListening()
        turnJob?.cancel()
        startConversation()
        set { it.copy(mode = Mode.THINKING, heard = text, partial = "", reply = "", live = true) }
        turnJob = scope.launch {
            val agent = AgentController.state.value
            LocalAnswers.match(text, awaitingApproval = agent.status == RunStatus.NEEDS_ASSISTANCE)?.let { doLocal(it, text); return@launch }
            CommandRouter.quickRoute(text)?.let { act(it, text); return@launch }
            val s = settings()
            val config = s?.config()?.takeIf { it.isUsable } ?: run { act(Route.Task(text), text); return@launch }
            val running = agent.task.takeIf { agent.status == RunStatus.RUNNING }
            val rctx = CommandRouter.Context(running, conversation.toList(), s.voiceLanguage)
            streamReply(text, config, rctx)
        }
    }

    /** Streams the AI's reply and speaks it sentence by sentence as it arrives. */
    private suspend fun streamReply(heard: String, config: com.chrauto.ai.provider.AiConfig, rctx: CommandRouter.Context) {
        val provider = ProviderFactory.create(config)
        val stream = ReplyStream()
        val chunker = SentenceChunker()
        val deltas = Channel<String>(Channel.UNLIMITED)
        var result: Result<String>? = null
        val producer = scope.launch(Dispatchers.IO) {
            result = provider.chatStream(CommandRouter.systemPrompt(rctx), CommandRouter.userPrompt(heard, rctx)) { deltas.trySend(it) }
            deltas.close()
        }
        var started = false
        try {
            for (d in deltas) {
                val fresh = stream.push(d)
                if (stream.kind == ReplyStream.Kind.ANSWER && fresh.isNotEmpty()) {
                    set { it.copy(reply = stream.answer) }
                    val pieces = chunker.push(fresh)
                    if (pieces.isNotEmpty()) {
                        if (!started) { started = true; Speaker.begin() }
                        pieces.forEach(Speaker::append)
                    }
                }
            }
        } finally {
            if (!producer.isCompleted) producer.cancel()
        }
        val r = result
        if (r == null || r.isFailure) {
            if (started) Speaker.stop()
            val msg = r?.exceptionOrNull()?.message?.take(140) ?: "no reply"
            reply(heard, "Sorry, I couldn't reach ${config.provider.label}. $msg", followUp = true)
            return
        }
        val route = stream.finish()
        if (route is Route.Answer) {
            val tail = chunker.push(stream.remaining()) + chunker.flush()
            set { it.copy(reply = route.reply) }
            if (started) {
                tail.forEach(Speaker::append)
                record(heard, route.reply)
                openConversationWindow()
                if (!Speaker.end()) onSpeechSkipped(true)
            } else reply(heard, route.reply, followUp = true)
        } else {
            if (started) Speaker.stop()
            act(route, heard)
        }
    }

    private fun act(route: Route, heard: String) {
        when (route) {
            is Route.Stop -> {
                AgentController.stop()
                reply(heard, route.reply, followUp = false)
            }
            is Route.Task -> {
                val problem = AgentController.startWithSavedSettings(route.task)
                if (problem != null) reply(heard, problem, followUp = false)
                else {
                    voiceTask = true
                    reply(heard, route.ack ?: listOf("On it!", "Okay!", "Sure thing!", "You got it!").random(), followUp = settings()?.liveConversation == true, optional = true)
                }
            }
            is Route.Answer -> reply(heard, route.reply, followUp = true)
        }
    }

    private fun doLocal(local: Local, heard: String) {
        when (local) {
            Local.Time -> reply(heard, "It's ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}.", followUp = true)
            Local.Date -> reply(heard, "Today is ${DateFormat.getDateInstance(DateFormat.FULL).format(Date())}.", followUp = true)
            Local.Battery -> {
                val bm = ctx?.getSystemService(BatteryManager::class.java)
                val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val charging = bm?.isCharging == true
                reply(heard, if (pct in 0..100) "Your battery is at $pct percent${if (charging) " and charging" else ""}." else "I can't read the battery right now.", followUp = true)
            }
            Local.Repeat -> reply(heard, lastReply.ifBlank { "I haven't said anything yet." }, followUp = true, remember = false)
            is Local.Rate -> {
                val s = settings()
                val newRate = ((s?.speechRate ?: 1.1f) + if (local.faster) 0.2f else -0.2f).coerceIn(0.7f, 1.9f)
                s?.speechRate = newRate; Speaker.rate = newRate
                reply(heard, if (local.faster) "Okay, I'll talk faster." else "Okay, I'll slow down.", followUp = true)
            }
            Local.Hush -> { record(heard, "(quiet)"); hush() }
            Local.Status -> {
                val a = AgentController.state.value
                val text = when (a.status) {
                    RunStatus.RUNNING -> "I'm working on: ${a.task.take(80)}. ${if (a.step > 0) "Step ${a.step}, " else ""}${a.detail.take(80)}"
                    RunStatus.SUCCEEDED -> "I finished: ${a.detail.take(120)}"
                    RunStatus.FAILED -> "The last task got stuck: ${a.detail.take(120)}"
                    RunStatus.NEEDS_ASSISTANCE -> "I'm waiting for your okay to continue. Say yes to go ahead."
                    else -> "Nothing running right now. What should I do?"
                }
                reply(heard, text, followUp = true)
            }
            Local.Approve -> {
                val problem = AgentController.approveAndContinue()
                if (problem == null) voiceTask = true
                reply(heard, problem ?: "Okay, continuing.", followUp = false)
            }
            Local.Forget -> { clearConversation(); reply(heard, "Okay, fresh start.", followUp = true, remember = false) }
            Local.Goodbye -> { conversationUntil = 0; reply(heard, listOf("Talk soon!", "Bye for now!", "Anytime!").random(), followUp = false) }
            Local.Help -> reply(heard, LocalAnswers.HELP, followUp = true)
            Local.WhoAreYou -> reply(heard, "I'm ${wakeName()}, your phone helper. Ask me anything or tell me what to do.", followUp = true)
        }
    }

    private fun record(heard: String, text: String) {
        if (text.isNotBlank() && text != "(quiet)") lastReply = text
        conversation.addLast(heard to text); while (conversation.size > 10) conversation.removeFirst()
        settings()?.saveChatHistory(conversation.toList())
        set { it.copy(conversation = conversation.toList()) }
    }

    private fun openConversationWindow() {
        val live = settings()?.liveConversation != false
        conversationUntil = System.currentTimeMillis() + if (live) 12_000L else 9_000L
        set { it.copy(live = true) }
    }

    private fun reply(heard: String, text: String, followUp: Boolean, optional: Boolean = false, remember: Boolean = true) {
        if (remember) record(heard, text) else lastReply = lastReply.ifBlank { text }
        set { it.copy(reply = text) }
        if (followUp) openConversationWindow() else conversationUntil = 0
        if (optional && settings()?.voiceReplies == false) { onSpeechSkipped(followUp); return }
        setMode(Mode.SPEAKING)
        if (!Speaker.say(text)) onSpeechSkipped(followUp)
    }

    /** Nothing was spoken (voice off/unavailable): continue as if speech ended. */
    private fun onSpeechSkipped(followUp: Boolean) {
        speaking = false
        if (followUp && inConversation()) listen(Listen.ACTIVE) else finishTurn()
    }

    // -------------------------------------------------------------------------------------------
    // Task narration: tell the user how a task went, then keep the conversation open.
    // -------------------------------------------------------------------------------------------

    private fun onAgent(prev: AgentState, s: AgentState) {
        if (prev.status == s.status) return
        val s0 = settings() ?: return
        if (s.status == RunStatus.RUNNING) {
            if (!recentlySpoke() && s0.voiceReplies && _state.value.mode != Mode.SPEAKING && _state.value.mode != Mode.THINKING) Speaker.say("On it!")
            return
        }
        if (prev.status != RunStatus.RUNNING) return
        val fromVoice = voiceTask; voiceTask = false
        val follow = fromVoice && s0.liveConversation
        val detail = s.detail.take(160)
        when (s.status) {
            RunStatus.SUCCEEDED -> announce("All done! $detail", follow)
            RunStatus.FAILED -> announce("Sorry, I got stuck. $detail", follow)
            RunStatus.NEEDS_ASSISTANCE -> announce("I need your okay before I continue. Say yes to go ahead.", followUp = true)
            else -> Unit
        }
    }

    // -------------------------------------------------------------------------------------------

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
