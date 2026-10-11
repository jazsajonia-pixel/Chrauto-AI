package com.chrauto.ai.agent

import android.content.Context
import android.content.Intent
import com.chrauto.ai.accessibility.AccessibilityBridge
import com.chrauto.ai.data.SettingsStore
import com.chrauto.ai.provider.GeminiProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of the running task so the main screen and the floating assistant
 * (buttons and voice) share one run, one state, and one Stop.
 */
object AgentController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state.asStateFlow()
    private var job: Job? = null
    private var appContext: Context? = null

    fun init(context: Context) { if (appContext == null) appContext = context.applicationContext }

    val isRunning get() = job?.isActive == true

    /** Starts a run with saved settings (used by voice and the floating panel). */
    fun startWithSavedSettings(task: String): String? {
        val ctx = appContext ?: return "App not initialised"
        val settings = SettingsStore(ctx)
        if (settings.apiKey.isBlank()) return "Add your Gemini API key in the Chrauto app first."
        start(task, settings.apiKey, settings.model)
        return null
    }

    fun start(task: String, apiKey: String, model: String): Job {
        job?.cancel()
        appContext?.let { SettingsStore(it).lastTask = task }
        val apps = appContext?.let(::launchableApps).orEmpty()
        val newJob = scope.launch {
            AgentOrchestrator(GeminiProvider(apiKey.trim(), model.trim(), apps), AccessibilityBridge) { _state.value = it }.run(task)
        }
        job = newJob
        return newJob
    }

    /** Suspends until the run ends, mirroring states to [onState]; cancelling the caller stops the run. */
    suspend fun runAndAwait(task: String, apiKey: String, model: String, onState: (AgentState) -> Unit) {
        val run = start(task, apiKey, model)
        try {
            coroutineScope {
                val mirror = launch { state.collect { onState(it) } }
                run.join()
                mirror.cancel()
            }
            onState(state.value)
        } finally {
            if (run.isActive) run.cancel()
        }
    }

    fun stop() {
        val active = job?.isActive == true
        job?.cancel()
        if (active || _state.value.status == RunStatus.RUNNING) {
            _state.value = _state.value.copy(
                status = RunStatus.CANCELLED, phase = AgentPhase.DONE, detail = "Stopped", target = null,
                logs = _state.value.logs + AgentLog("Cancelled by user.")
            )
        }
    }

    /** Plays a scripted run (no AI calls, no device actions) so the floating assistant can be previewed. */
    fun demo() {
        job?.cancel()
        job = scope.launch {
            val m = appContext?.resources?.displayMetrics
            val w = m?.widthPixels ?: 1080; val h = m?.heightPixels ?: 2400
            fun box(fx: Float, fy: Float) = TargetBox((w * fx).toInt() - 120, (h * fy).toInt() - 60, (w * fx).toInt() + 120, (h * fy).toInt() + 60)
            var s = AgentState(RunStatus.RUNNING, listOf(AgentLog("Demo started")), "Demo", AgentPhase.THINKING, "Thinking…")
            _state.value = s; delay(2_500)
            s = s.copy(phase = AgentPhase.MOVING, detail = "Tap \"Search\"", target = box(0.5f, 0.16f), step = 1); _state.value = s; delay(1_200)
            s = s.copy(phase = AgentPhase.ACTING); _state.value = s; delay(600)
            s = s.copy(phase = AgentPhase.THINKING, detail = "Thinking…", target = null); _state.value = s; delay(1_800)
            s = s.copy(phase = AgentPhase.MOVING, detail = "Type \"Kotlin tutorials\"", target = box(0.3f, 0.42f), step = 2); _state.value = s; delay(1_200)
            s = s.copy(phase = AgentPhase.ACTING); _state.value = s; delay(800)
            _state.value = s.copy(status = RunStatus.SUCCEEDED, phase = AgentPhase.DONE, detail = "Demo finished", target = null)
        }
    }

    fun dismiss() { if (!isRunning) _state.value = _state.value.copy(phase = AgentPhase.IDLE, target = null) }

    private fun launchableApps(context: Context): String = runCatching {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != context.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
            .take(120)
            .joinToString("\n") { "${it.first} => ${it.second}" }
    }.getOrDefault("")
}
