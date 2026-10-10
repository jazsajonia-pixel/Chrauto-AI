package com.chrauto.ai.agent

import com.chrauto.ai.provider.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

interface DeviceBridge {
    fun isReady(): Boolean
    suspend fun observe(): UiObservation?
    suspend fun execute(action: AgentAction): ActionResult
}

enum class RunStatus { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED, NEEDS_ASSISTANCE }
data class AgentLog(val message: String, val isError: Boolean = false)
data class AgentState(val status: RunStatus = RunStatus.IDLE, val logs: List<AgentLog> = emptyList(), val task: String = "")

class AgentOrchestrator(
    private val provider: AiProvider,
    private val device: DeviceBridge,
    private val maxActions: Int = 20,
    private val maxRequests: Int = 20,
    private val maxDurationMs: Long = 180_000,
    private val onState: (AgentState) -> Unit
) {
    suspend fun run(task: String) {
        val started = System.currentTimeMillis()
        var state = AgentState(RunStatus.RUNNING, listOf(AgentLog("Started: ${task.take(160)}")), task)
        onState(state)
        fun log(message: String, error: Boolean = false) {
            state = state.copy(logs = (state.logs + AgentLog(message, error)).takeLast(100))
            onState(state)
        }
        try {
            if (!device.isReady()) throw IllegalStateException("Accessibility Service is disabled. Enable it in Android Settings first.")
            var observation = device.observe() ?: throw IllegalStateException("No accessible active window is available.")
            var actions = 0
            var requests = 0
            val fingerprints = mutableMapOf<String, Int>()
            while (actions < maxActions && requests < maxRequests && System.currentTimeMillis() - started < maxDurationMs) {
                coroutineContext.ensureActive()
                log("Observed ${observation.nodes.size} accessible nodes in ${observation.packageName ?: "unknown app"}.")
                val plan = provider.plan(task, observation).getOrElse { throw it }
                requests++
                if (SafetyPolicy.requiresConfirmation(task, plan.action)) {
                    log("Paused: confirmation required before a consequential action.")
                    state = state.copy(status = RunStatus.NEEDS_ASSISTANCE); onState(state); return
                }
                if (plan.action is AgentAction.Finish) {
                    val finish = plan.action as AgentAction.Finish
                    val evidencePresent = observation.compactText().contains(finish.evidence, ignoreCase = true)
                    if (!evidencePresent) { log("Model requested success, but evidence was not present in the current observation.", true); throw IllegalStateException("Completion could not be verified") }
                    state = state.copy(status = RunStatus.SUCCEEDED); onState(state); return
                }
                ActionValidator.validate(plan.action, observation)?.let { throw IllegalArgumentException(it) }
                log("Executing ${plan.action::class.simpleName}.")
                val result = device.execute(plan.action)
                actions++
                if (!result.ok) throw IllegalStateException(result.message)
                delay(if (plan.action is AgentAction.Wait) 100 else 350)
                observation = device.observe() ?: throw IllegalStateException("The screen could not be observed after the action.")
                val fingerprint = observation.compactText().hashCode().toString() + ":" + plan.action::class.simpleName
                fingerprints[fingerprint] = (fingerprints[fingerprint] ?: 0) + 1
                if (fingerprints[fingerprint]!! >= 3) throw IllegalStateException("Repeated action produced no verified progress; stopped safely.")
            }
            throw IllegalStateException("Execution limit reached before verified completion.")
        } catch (cancelled: CancellationException) {
            state = state.copy(status = RunStatus.CANCELLED); onState(state); throw cancelled
        } catch (error: Exception) {
            log(error.message ?: "Unknown failure", true)
            state = state.copy(status = RunStatus.FAILED); onState(state)
        }
    }
}
