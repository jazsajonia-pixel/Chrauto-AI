package com.chrauto.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.chrauto.ai.agent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

object AccessibilityBridge : com.chrauto.ai.agent.DeviceBridge {
    @Volatile var service: AccessibilityAgentService? = null
    override fun isReady() = service != null
    override suspend fun observe() = service?.snapshot()
    override suspend fun execute(action: AgentAction) = service?.executeAction(action) ?: ActionResult(false, "Accessibility Service unavailable")
}

class AccessibilityAgentService : AccessibilityService() {
    private val connected = AtomicBoolean(false)
    override fun onServiceConnected() { connected.set(true); AccessibilityBridge.service = this }
    override fun onDestroy() { AccessibilityBridge.service = null; connected.set(false); super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    fun snapshot(): UiObservation? {
        val root = rootInActiveWindow ?: return null
        val nodes = mutableListOf<UiNodeSnapshot>()
        collect(root, "0", nodes)
        return UiObservation(root.packageName?.toString(), root.className?.toString(), nodes)
    }

    private fun collect(node: AccessibilityNodeInfo, path: String, out: MutableList<UiNodeSnapshot>) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val password = node.isPassword
        out += UiNodeSnapshot(path, if (password) null else node.text?.toString()?.take(MAX_TEXT_LENGTH), if (password) null else node.contentDescription?.toString()?.take(300), node.className?.toString(), node.isClickable, node.isEditable, node.isScrollable, node.isEnabled, bounds, node.actionList.map { it.id.toString() })
        for (index in 0 until node.childCount) node.getChild(index)?.let { child -> collect(child, "$path.$index", out) }
    }

    suspend fun executeAction(action: AgentAction): ActionResult = withContext(Dispatchers.Main) {
        when (action) {
            is AgentAction.OpenApp -> runCatching { startActivity(packageManager.getLaunchIntentForPackage(action.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: error("App not installed")); ActionResult(true, "Opened ${action.packageName}") }.getOrElse { ActionResult(false, it.message ?: "Could not open app") }
            is AgentAction.Tap, is AgentAction.TypeText, is AgentAction.Scroll, AgentAction.PressBack ->
                AccessibilityActionExecutor({ id -> find(id)?.let(::AccessibilityNodeInfoTarget) }, ::performGlobalAction).execute(action)
            is AgentAction.Wait -> {
                kotlinx.coroutines.delay(action.milliseconds)
                ActionResult(true, "Wait completed")
            }
            is AgentAction.AskUser -> ActionResult(false, "User confirmation required")
            is AgentAction.Finish -> ActionResult(true, "Finish is verified by orchestrator")
            is AgentAction.ReportError -> ActionResult(false, action.message)
        }
    }

    private fun find(id: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var current: AccessibilityNodeInfo? = root
        for (part in id.split('.').drop(1)) {
            val index = part.toIntOrNull() ?: return null
            current = current?.getChild(index)
        }
        return current
    }
}
