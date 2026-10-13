package com.chrauto.ai.agent

import com.chrauto.ai.provider.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

interface DeviceBridge {
    fun isReady(): Boolean
    suspend fun observe(): UiObservation?
    suspend fun execute(action: AgentAction): ActionResult
    fun stopScreenCapture() {}
    fun isScreenCaptureActive(): Boolean = false
    /** Waits until the screen stops changing (up to [maxMs]). */
    suspend fun settle(maxMs: Long) { delay(minOf(maxMs, 600)) }
}

enum class RunStatus { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED, NEEDS_ASSISTANCE }

/** Fine-grained activity used by the floating assistant to animate. */
enum class AgentPhase { IDLE, OBSERVING, THINKING, MOVING, ACTING, DONE }

data class AgentLog(val message: String, val isError: Boolean = false, val atMillis: Long = System.currentTimeMillis())
data class AgentState(
    val status: RunStatus = RunStatus.IDLE,
    val logs: List<AgentLog> = emptyList(),
    val task: String = "",
    val phase: AgentPhase = AgentPhase.IDLE,
    val detail: String = "",
    val target: TargetBox? = null,
    val step: Int = 0,
    val phaseSince: Long = System.currentTimeMillis(),
    val screenAware: Boolean = false
)

class AgentOrchestrator(
    private val provider: AiProvider,
    private val device: DeviceBridge,
    private val maxActions: Int = 25,
    private val maxRequests: Int = 30,
    private val maxDurationMs: Long = 240_000,
    private val maxConsecutiveFailures: Int = 3,
    private val previewMs: Long = 320,
    private val approved: Boolean = false,
    /** Launchable apps (label → package) for the instant "open X" shortcut. */
    private val apps: Map<String, String> = emptyMap(),
    private val screenAware: Boolean = false,
    private val onState: (AgentState) -> Unit
) {
    suspend fun run(task: String) {
        val started = System.currentTimeMillis()
        var state = AgentState(RunStatus.RUNNING, listOf(AgentLog("Started: ${task.take(160)}")), task, AgentPhase.OBSERVING, if (screenAware) "Capturing game screen for Gemini" else "Looking at the screen", screenAware = screenAware)
        onState(state)
        fun update(transform: (AgentState) -> AgentState) {
            val next = transform(state)
            state = if (next.phase != state.phase) next.copy(phaseSince = System.currentTimeMillis()) else next
            onState(state)
        }
        fun log(message: String, error: Boolean = false) = update { it.copy(logs = (it.logs + AgentLog(message, error)).takeLast(100)) }

        try {
            if (screenAware && !provider.supportsVision) throw IllegalStateException("The selected AI provider cannot accept screenshots. Choose Gemini or turn off screen-aware mode.")
            if (!device.isReady()) throw IllegalStateException("Accessibility Service is disabled. Enable Chrauto AI in Android Settings → Accessibility first.")
            // Open the network connection while we read the screen.
            var observation = device.observe() ?: throw IllegalStateException("No accessible active window is available.")
            if (screenAware && observation.visionFrame == null) throw IllegalStateException("Screen capture is not active. Grant screen-capture permission and start the task again.")
            var actions = 0
            var requests = 0
            var consecutiveFailures = 0
            val history = mutableListOf<String>()
            // Instant path: "open X (and …)" launches the app locally without waiting for the AI.
            Shortcuts.openApp(task, apps)?.let { shortcut ->
                val label = "Open ${shortcut.label}"
                update { it.copy(phase = AgentPhase.ACTING, detail = label, step = 1) }
                log("Step 1: $label (instant)")
                val result = device.execute(AgentAction.OpenApp(shortcut.packageName))
                if (result.ok) {
                    actions++
                    history += "$label → ok (already done for you; continue with the rest of the task)"
                    device.settle(2_500)
                    observation = device.observe() ?: observation
                    if (shortcut.rest.isBlank() && observation.packageName == shortcut.packageName) {
                        log("Done: Opened ${shortcut.label}")
                        update { it.copy(status = RunStatus.SUCCEEDED, phase = AgentPhase.DONE, detail = "Opened ${shortcut.label}", target = null) }
                        return
                    }
                }
            }
            if (approved) history += "NOTE: the user explicitly approved this task (including any send/submit step). Proceed without asking again."
            val fingerprints = mutableMapOf<String, Int>()

            fun reject(reason: String) {
                consecutiveFailures++
                history += "REJECTED: $reason"
                log("Retrying: $reason", true)
                if (consecutiveFailures >= maxConsecutiveFailures) throw IllegalStateException("Stopped after repeated problems: $reason")
            }

            while (actions < maxActions && requests < maxRequests && System.currentTimeMillis() - started < maxDurationMs) {
                coroutineContext.ensureActive()
                if (screenAware && observation.visionFrame == null) throw IllegalStateException("Fresh screenshot unavailable; stopped before sending another model request.")
                log("Observed ${observation.nodes.size} accessible nodes in ${observation.packageName ?: "unknown app"}.")
                update { it.copy(phase = AgentPhase.THINKING, detail = "Thinking…", target = null) }
                val providerResult = withTimeoutOrNull(40_000) { provider.plan(task, observation, history) }
                    ?: throw IllegalStateException("AI request timed out after 40 seconds")
                val resolvedPlan = providerResult.getOrElse { throw it }
                if (screenAware && (!device.isScreenCaptureActive() || observation.visionFrame == null))
                    throw IllegalStateException("Screen capture stopped while Gemini was thinking; no action was dispatched.")
                requests++
                resolvedPlan.thought?.let { log(it) }

                if (!screenAware && (resolvedPlan.action is AgentAction.TapAt || resolvedPlan.action is AgentAction.GesturePath)) {
                    throw IllegalStateException("Visual coordinate actions require the explicit screen-aware opt-in.")
                }
                val plan = resolvedPlan
                if (SafetyPolicy.requiresConfirmation(task, plan.action, approved)) {
                    val reason = (plan.action as? AgentAction.AskUser)?.reason ?: "This task may send, buy, delete, or submit something."
                    log("Paused: $reason")
                    update { it.copy(status = RunStatus.NEEDS_ASSISTANCE, phase = AgentPhase.DONE, detail = reason, target = null) }
                    return
                }
                when (val action = plan.action) {
                    is AgentAction.Finish -> {
                        val ok = action.evidence.isNotBlank() && observation.compactText().contains(action.evidence, ignoreCase = true)
                        if (ok) {
                            log("Done: ${action.summary}")
                            update { it.copy(status = RunStatus.SUCCEEDED, phase = AgentPhase.DONE, detail = action.summary, target = null) }
                            return
                        }
                        reject("finish evidence \"${action.evidence.take(80)}\" is not visible on the current screen")
                        continue
                    }
                    is AgentAction.ReportError -> throw IllegalStateException(action.message)
                    else -> Unit
                }

                val problem = ActionValidator.validate(plan.action, observation)
                if (problem != null) { reject("${describe(plan.action, observation)} — $problem"); continue }
                val resolved = resolve(plan.action, observation)

                val label = describe(resolved, observation)
                val box = targetFor(resolved, observation)
                update { it.copy(phase = AgentPhase.MOVING, detail = label, target = box, step = actions + 1) }
                if (box != null) delay(previewMs)
                update { it.copy(phase = AgentPhase.ACTING) }
                log("Step ${actions + 1}: $label")
                val before = observation.compactText().hashCode()
                var result = withTimeoutOrNull(15_000) { device.execute(resolved) }
                    ?: ActionResult(false, "Action timed out after 15 seconds")
                actions++
                if (!result.ok) { reject("$label failed: ${result.message}"); device.settle(800); observation = device.observe() ?: observation; continue }
                consecutiveFailures = 0

                update { it.copy(phase = AgentPhase.OBSERVING, detail = "Checking the result") }
                device.settle(when (resolved) { is AgentAction.OpenApp -> 3_000; is AgentAction.Wait -> 300; else -> 1_600 })
                observation = device.observe() ?: throw IllegalStateException("The screen could not be observed after the action.")
                if (screenAware && observation.visionFrame == null) throw IllegalStateException("A fresh screenshot could not be captured after the action.")

                // A touch that changed nothing may have missed: try once more with an accessibility click.
                if (resolved is AgentAction.Tap && resolved.viaGesture && observation.compactText().hashCode() == before) {
                    log("Nothing changed — trying a direct click")
                    result = device.execute(resolved.copy(viaGesture = false))
                    if (result.ok) { device.settle(1_200); observation = device.observe() ?: observation }
                }
                history += "$label → ${if (observation.compactText().hashCode() == before) "ok, but the screen did not change" else "ok"}"

                val fingerprint = observation.compactText().hashCode().toString() + ":" + label
                val seen = (fingerprints[fingerprint] ?: 0) + 1
                fingerprints[fingerprint] = seen
                if (seen >= 3) throw IllegalStateException("Repeated the same action without progress; stopped safely.")
                if (seen == 2) history += "NOTE: the screen did not change after \"$label\"; try something else."
            }
            throw IllegalStateException("Execution limit reached before verified completion.")
        } catch (cancelled: CancellationException) {
            update { it.copy(status = RunStatus.CANCELLED, phase = AgentPhase.DONE, detail = "Stopped", target = null) }
            throw cancelled
        } catch (error: Exception) {
            log(error.message ?: "Unknown failure", true)
            update { it.copy(status = RunStatus.FAILED, phase = AgentPhase.DONE, detail = error.message ?: "Failed", target = null) }
        } finally {
            if (screenAware) device.stopScreenCapture()
        }
    }

    /** Fill in a scroll container when omitted. Taps keep the exact node the AI chose (its centre is touched). */
    private fun resolve(action: AgentAction, observation: UiObservation): AgentAction = when (action) {
        is AgentAction.TapAt -> observation.visionFrame?.let { frame -> action.copy(
            x = action.x * observation.screenWidth / frame.width,
            y = action.y * observation.screenHeight / frame.height
        ) } ?: action
        is AgentAction.GesturePath -> observation.visionFrame?.let { frame -> action.copy(points = action.points.map { point ->
            ScreenPoint(point.x * observation.screenWidth / frame.width, point.y * observation.screenHeight / frame.height)
        }) } ?: action
        is AgentAction.Scroll -> if (action.nodeId == null || observation.node(action.nodeId)?.scrollable != true)
            observation.nodes.firstOrNull { it.scrollable && it.enabled }?.let { AgentAction.Scroll(it.id, action.direction) } ?: action
        else action
        else -> action
    }

    private fun targetFor(action: AgentAction, observation: UiObservation): TargetBox? {
        val id = when (action) {
            is AgentAction.Tap -> action.nodeId
            is AgentAction.TapAt, is AgentAction.GesturePath -> null
            is AgentAction.TypeText -> action.nodeId
            is AgentAction.PressEnter -> action.nodeId
            is AgentAction.Scroll -> action.nodeId
            else -> null
        } ?: return null
        val b = observation.node(id)?.bounds ?: return null
        if (b.right <= b.left || b.bottom <= b.top) return null
        return TargetBox(b.left, b.top, b.right, b.bottom)
    }

    private fun describe(action: AgentAction, observation: UiObservation): String {
        fun name(id: String?) = id?.let { observation.node(it) }?.let { n ->
            (n.text?.takeIf { it.isNotBlank() } ?: n.contentDescription?.takeIf { it.isNotBlank() } ?: n.hint ?: n.className?.substringAfterLast('.'))?.take(40)
        } ?: id ?: "screen"
        return when (action) {
            is AgentAction.OpenApp -> "Open ${action.packageName}"
            is AgentAction.Tap -> "Tap \"${name(action.nodeId)}\""
            is AgentAction.TapAt -> "Tap screen target"
            is AgentAction.GesturePath -> "Perform screen gesture"
            is AgentAction.TypeText -> "Type \"${action.text.take(40)}\""
            is AgentAction.Scroll -> "Scroll ${action.direction.name.lowercase()}"
            AgentAction.PressBack -> "Go back"
            is AgentAction.PressEnter -> "Press Enter"
            is AgentAction.Wait -> "Wait ${action.milliseconds} ms"
            is AgentAction.AskUser -> "Ask user"
            is AgentAction.Finish -> "Finish"
            is AgentAction.ReportError -> "Report error"
        }
    }
}
