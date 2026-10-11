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
import com.chrauto.ai.data.SettingsStore
import com.chrauto.ai.overlay.AssistantOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

object AccessibilityBridge : com.chrauto.ai.agent.DeviceBridge {
    @Volatile var service: AccessibilityAgentService? = null
    override fun isReady() = service != null
    /** Show/hide the floating assistant from the app. */
    fun setAssistantVisible(visible: Boolean) { service?.setAssistantVisible(visible) }
    override suspend fun observe() = service?.snapshot()
    override suspend fun execute(action: AgentAction) = service?.executeAction(action) ?: ActionResult(false, "Accessibility Service unavailable")
}

private const val MAX_NODES = 800

class AccessibilityAgentService : AccessibilityService() {
    private val connected = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var overlay: AssistantOverlay? = null

    override fun onServiceConnected() {
        connected.set(true)
        AccessibilityBridge.service = this
        AgentController.init(this)
        val s = MainScope().also { scope = it }
        if (SettingsStore(this).floatingAssistant) setAssistantVisible(true)
        s.launch { AgentController.state.collect { state ->
            if (state.status == RunStatus.RUNNING && overlay == null) setAssistantVisible(true)
            overlay?.render(state)
        } }
    }

    fun setAssistantVisible(visible: Boolean) {
        if (visible) {
            val o = overlay ?: runCatching { AssistantOverlay(this) }.getOrNull()?.also { overlay = it }
            o?.show()
            o?.render(AgentController.state.value)
        } else {
            overlay?.detach(); overlay = null
        }
    }

    override fun onUnbind(intent: Intent?): Boolean { teardown(); return super.onUnbind(intent) }
    override fun onDestroy() { teardown(); super.onDestroy() }
    private fun teardown() {
        if (AccessibilityBridge.service === this) AccessibilityBridge.service = null
        connected.set(false)
        overlay?.detach(); overlay = null
        scope?.cancel(); scope = null
        AgentController.stop()
    }
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
        if (out.size >= MAX_NODES) return
        if (!node.isVisibleToUser && node.childCount == 0) return
        out += UiNodeSnapshot(
            path,
            if (password) null else node.text?.toString()?.take(MAX_TEXT_LENGTH),
            if (password) null else node.contentDescription?.toString()?.take(300),
            node.className?.toString(), node.isClickable, node.isEditable, node.isScrollable, node.isEnabled, bounds,
            node.actionList.map { it.id.toString() },
            hint = if (password) null else node.hintText?.toString()?.take(200),
            packageName = node.packageName?.toString()
        )
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
