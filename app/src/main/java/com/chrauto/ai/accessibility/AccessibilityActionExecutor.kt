package com.chrauto.ai.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.chrauto.ai.agent.*

interface AccessibilityNodeTarget {
    val isEnabled: Boolean
    val isEditable: Boolean
    fun performAction(action: Int, arguments: Bundle? = null): Boolean
}

class AccessibilityNodeInfoTarget(private val node: AccessibilityNodeInfo) : AccessibilityNodeTarget {
    override val isEnabled get() = node.isEnabled
    override val isEditable get() = node.isEditable
    override fun performAction(action: Int, arguments: Bundle?): Boolean =
        if (arguments == null) node.performAction(action) else node.performAction(action, arguments)
}

/** Executes only the semantic actions authorized by the agent protocol. */
class AccessibilityActionExecutor(
    private val findNode: (String) -> AccessibilityNodeTarget?,
    private val performGlobalAction: (Int) -> Boolean
) {
    fun execute(action: AgentAction): ActionResult = when (action) {
        is AgentAction.Tap -> findNode(action.nodeId)?.let {
            ActionResult(it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Tap dispatched")
        } ?: ActionResult(false, "Tap target missing")
        is AgentAction.TypeText -> findNode(action.nodeId)?.let { node ->
            if (!node.isEnabled || !node.isEditable) return@let ActionResult(false, "Text target is not editable")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
            }
            ActionResult(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "Text input dispatched")
        } ?: ActionResult(false, "Text target missing")
        is AgentAction.Scroll -> findNode(action.nodeId ?: "0")?.let { node ->
            val scrollAction = when (action.direction) {
                // DOWN/RIGHT reveal content further along the list (forward); UP/LEFT go back.
                Direction.DOWN, Direction.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                Direction.UP, Direction.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            ActionResult(node.performAction(scrollAction), "Scroll dispatched")
        } ?: ActionResult(false, "Scroll target missing")
        AgentAction.PressBack -> ActionResult(
            performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),
            "Back dispatched"
        )
        else -> ActionResult(false, "Action is not an accessibility node action")
    }
}
