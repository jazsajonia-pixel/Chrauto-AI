package com.chrauto.ai.agent

import android.content.Context
import android.content.Intent
import com.chrauto.ai.accessibility.AccessibilityBridge
import com.chrauto.ai.data.SettingsStore
import com.chrauto.ai.provider.AiConfig
import com.chrauto.ai.provider.ProviderFactory
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
    fun startWithSavedSettings(task: String, approved: Boolean = false): String? {
        val ctx = appContext ?: return "App not initialised"
        val config = SettingsStore(ctx).config()
        if (!config.isUsable) return "Add your ${config.provider.label} API key in the Chrauto app first."
        start(task, config, approved)
        return null
    }

    /** Re-runs the paused task after the user approved it. */
    fun approveAndContinue(): String? {
        val task = _state.value.task.ifBlank { appContext?.let { SettingsStore(it).lastTask }.orEmpty() }
        if (task.isBlank()) return "Nothing to continue"
        return startWithSavedSettings(task, approved = true)
    }

    fun start(task: String, config: AiConfig, approved: Boolean = false, screenAware: Boolean = false): Job {
        job?.cancel()
        appContext?.let { SettingsStore(it).apply { lastTask = task; addRecent(task) } }
        val apps = appContext?.let(::launchableApps).orEmpty()
        val appText = apps.joinToString("\n") { "${it.first} => ${it.second}" }
        val appMap = apps.toMap()
        val newJob = scope.launch {
            AgentOrchestrator(ProviderFactory.create(config, appText), AccessibilityBridge, approved = approved, apps = appMap, screenAware = screenAware) { _state.value = it }.run(task)
        }
        if (screenAware) newJob.invokeOnCompletion { AccessibilityBridge.stopScreenCapture() }
        job = newJob
        return newJob
    }

    /** Suspends until the run ends, mirroring states to [onState]; cancelling the caller stops the run. */
    suspend fun runAndAwait(task: String, config: AiConfig, onState: (AgentState) -> Unit, approved: Boolean = false) {
        val run = start(task, config, approved)
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

    @Volatile private var appCache: Pair<Long, List<Pair<String, String>>>? = null

    /** Launchable apps (label, package), cached for a minute so starting a run stays instant. */
    private fun launchableApps(context: Context): List<Pair<String, String>> {
        appCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 60_000) return list }
        val list = runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .filter { it.second != context.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
                .take(150)
        }.getOrDefault(emptyList())
        appCache = System.currentTimeMillis() to list
        return list
    }
}
