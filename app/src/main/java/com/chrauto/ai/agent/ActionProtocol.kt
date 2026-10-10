package com.chrauto.ai.agent

import android.graphics.Rect

const val MAX_TEXT_LENGTH = 2_000
const val MAX_ACTIONS_PER_RESPONSE = 1

sealed interface AgentAction {
    data class OpenApp(val packageName: String) : AgentAction
    data class Tap(val nodeId: String) : AgentAction
    data class TypeText(val nodeId: String, val text: String, val replace: Boolean = true) : AgentAction
    data class Scroll(val nodeId: String?, val direction: Direction) : AgentAction
    data object PressBack : AgentAction
    data class Wait(val milliseconds: Long) : AgentAction
    data class AskUser(val reason: String) : AgentAction
    data class Finish(val summary: String, val evidence: String) : AgentAction
    data class ReportError(val message: String) : AgentAction
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

data class ActionPlan(val action: AgentAction)
data class ActionResult(val ok: Boolean, val message: String, val changed: Boolean = false)
data class UiNodeSnapshot(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val bounds: Rect,
    val actions: List<String>
)
data class UiObservation(
    val packageName: String?,
    val activityName: String?,
    val nodes: List<UiNodeSnapshot>,
    val capturedAtMillis: Long = System.currentTimeMillis()
) {
    fun compactText(): String = buildString {
        append("package=").append(packageName ?: "unknown").append('\n')
        nodes.filter { it.text != null || it.contentDescription != null }.take(120).forEach { node ->
            append("id=").append(node.id).append("; text=").append(node.text ?: "")
            append("; desc=").append(node.contentDescription ?: "")
            append("; class=").append(node.className ?: "")
            append("; clickable=").append(node.clickable).append("; editable=").append(node.editable)
            append("; scrollable=").append(node.scrollable).append("; enabled=").append(node.enabled).append('\n')
        }
    }
}

object ActionValidator {
    fun validate(action: AgentAction, observation: UiObservation?): String? = when (action) {
        is AgentAction.OpenApp -> if (!Regex("^[a-zA-Z][a-zA-Z0-9_.]{2,}$").matches(action.packageName)) "Invalid package name" else null
        is AgentAction.Tap -> when (val target = node(observation, action.nodeId)) {
            null -> "Target node is stale or missing"
            else -> if (!target.enabled || !target.clickable) "Target is not enabled and clickable" else null
        }
        is AgentAction.TypeText -> when {
            action.text.length > MAX_TEXT_LENGTH -> "Text exceeds $MAX_TEXT_LENGTH characters"
            action.text.contains('\u0000') -> "Text contains an invalid character"
            node(observation, action.nodeId)?.let { !it.enabled || !it.editable } == true -> "Target is not an enabled editable field"
            node(observation, action.nodeId) == null -> "Target node is stale or missing"
            else -> null
        }
        is AgentAction.Scroll -> if (action.nodeId == null || node(observation, action.nodeId)?.scrollable == true) null else "Scroll target is stale or not scrollable"
        is AgentAction.PressBack -> null
        is AgentAction.Wait -> if (action.milliseconds in 100..10_000) null else "Wait must be between 100 and 10000 ms"
        is AgentAction.AskUser -> if (action.reason.isBlank()) "Confirmation reason is required" else null
        is AgentAction.Finish -> if (action.summary.isBlank() || action.evidence.isBlank()) "Finish requires summary and evidence" else null
        is AgentAction.ReportError -> if (action.message.isBlank()) "Error message is required" else null
    }

    private fun node(observation: UiObservation?, id: String): UiNodeSnapshot? = observation?.nodes?.firstOrNull { it.id == id }
}

object SafetyPolicy {
    private val consequentialWords = setOf("send", "publish", "purchase", "buy", "delete", "security", "password", "submit")
    fun requiresConfirmation(task: String, action: AgentAction): Boolean {
        if (action is AgentAction.AskUser) return true
        return task.lowercase().split(Regex("\\W+")).any(consequentialWords::contains)
    }
}
