package com.chrauto.ai.agent

import android.graphics.Rect

const val MAX_TEXT_LENGTH = 2_000
const val MAX_ACTIONS_PER_RESPONSE = 1

sealed interface AgentAction {
    data class OpenApp(val packageName: String) : AgentAction
    /** [viaGesture] = real touch at the node's centre (most reliable); false = accessibility click. */
    data class Tap(val nodeId: String, val viaGesture: Boolean = true) : AgentAction
    /** Submits the focused/given text field (keyboard Enter / Search). */
    data class PressEnter(val nodeId: String? = null) : AgentAction
    data class TypeText(val nodeId: String, val text: String, val replace: Boolean = true, val submit: Boolean = false) : AgentAction
    data class Scroll(val nodeId: String?, val direction: Direction) : AgentAction
    data object PressBack : AgentAction
    data class Wait(val milliseconds: Long) : AgentAction
    data class AskUser(val reason: String) : AgentAction
    data class Finish(val summary: String, val evidence: String) : AgentAction
    data class ReportError(val message: String) : AgentAction
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

data class ActionPlan(val action: AgentAction, val thought: String? = null)
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
    val actions: List<String>,
    val hint: String? = null,
    val packageName: String? = null,
    val checked: Boolean? = null
)
data class UiObservation(
    val packageName: String?,
    val activityName: String?,
    val nodes: List<UiNodeSnapshot>,
    val capturedAtMillis: Long = System.currentTimeMillis()
) {
    fun compactText(): String = buildString {
        append("package=").append(packageName ?: "unknown").append('\n')
        nodes.filter { it.isInteresting() }.take(160).forEach { node ->
            append("id=").append(node.id)
            node.text?.takeIf { it.isNotBlank() }?.let { append("; text=").append(it.take(200)) }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let { append("; desc=").append(it.take(160)) }
            node.hint?.takeIf { it.isNotBlank() }?.let { append("; hint=").append(it.take(120)) }
            append("; class=").append(node.className?.substringAfterLast('.') ?: "")
            if (node.clickable) append("; clickable=true")
            if (node.editable) append("; editable=true")
            if (node.scrollable) append("; scrollable=true")
            if (!node.enabled) append("; enabled=false")
            node.checked?.let { append("; checked=").append(it) }
            if (node.clickable || node.editable || node.scrollable) node.bounds.let { b -> append("; bounds=[").append(b.left).append(',').append(b.top).append(',').append(b.right).append(',').append(b.bottom).append(']') }
            append('\n')
        }
    }

    fun node(id: String): UiNodeSnapshot? = nodes.firstOrNull { it.id == id }

    /** Returns the node itself if clickable, otherwise its nearest clickable ancestor (ids are tree paths). */
    fun clickableTarget(id: String): UiNodeSnapshot? {
        var current: String? = id
        while (current != null) {
            node(current)?.takeIf { it.clickable && it.enabled }?.let { return it }
            current = current.substringBeforeLast('.', "").ifEmpty { null }
        }
        return null
    }
}

private fun UiNodeSnapshot.isInteresting() =
    !text.isNullOrBlank() || !contentDescription.isNullOrBlank() || !hint.isNullOrBlank() || clickable || editable || scrollable

/** Screen-space box used to animate the on-screen assistant cursor. */
data class TargetBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

object ActionValidator {
    fun validate(action: AgentAction, observation: UiObservation?): String? = when (action) {
        is AgentAction.OpenApp -> if (!Regex("^[a-zA-Z][a-zA-Z0-9_.]{2,}$").matches(action.packageName)) "Invalid package name" else null
        is AgentAction.Tap -> when {
            node(observation, action.nodeId) == null -> "Target node is stale or missing"
            observation?.clickableTarget(action.nodeId) == null -> "Target is not enabled and clickable"
            else -> null
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
        is AgentAction.PressEnter -> if (action.nodeId != null && node(observation, action.nodeId) == null) "Enter target is stale or missing" else null
        is AgentAction.Wait -> if (action.milliseconds in 100..10_000) null else "Wait must be between 100 and 10000 ms"
        is AgentAction.AskUser -> if (action.reason.isBlank()) "Confirmation reason is required" else null
        is AgentAction.Finish -> if (action.summary.isBlank() || action.evidence.isBlank()) "Finish requires summary and evidence copied from the screen" else null
        is AgentAction.ReportError -> if (action.message.isBlank()) "Error message is required" else null
    }

    private fun node(observation: UiObservation?, id: String): UiNodeSnapshot? = observation?.nodes?.firstOrNull { it.id == id }
}

object SafetyPolicy {
    private val consequentialWords = setOf("send", "publish", "purchase", "buy", "delete", "security", "password", "submit")
    fun requiresConfirmation(task: String, action: AgentAction, approved: Boolean = false): Boolean {
        if (action is AgentAction.AskUser) return true
        if (approved) return false
        return task.lowercase().split(Regex("\\W+")).any(consequentialWords::contains)
    }
}
