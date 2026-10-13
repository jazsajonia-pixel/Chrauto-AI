package com.chrauto.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot
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

    /** Retries briefly: during app switches the active window is often momentarily unavailable. */
    override suspend fun observe(): UiObservation? {
        repeat(8) {
            val obs = service?.snapshot()
            if (obs != null && obs.nodes.isNotEmpty()) return obs
            kotlinx.coroutines.delay(250)
        }
        return service?.snapshot()
    }
    override suspend fun execute(action: AgentAction) = service?.executeAction(action) ?: ActionResult(false, "Accessibility Service unavailable")
    override suspend fun settle(maxMs: Long) { service?.settle(maxMs) ?: kotlinx.coroutines.delay(minOf(maxMs, 600)) }
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
        com.chrauto.ai.voice.ChronEngine.attach(this)
        s.launch { com.chrauto.ai.voice.ChronEngine.state.collect { overlay?.renderChron(it) } }
        s.launch { com.chrauto.ai.voice.ChronEngine.level.collect { overlay?.renderLevel(it) } }
        if (SettingsStore(this).chronEnabled && !com.chrauto.ai.voice.ChronService.running) com.chrauto.ai.voice.ChronService.start(this)
    }

    fun setAssistantVisible(visible: Boolean) {
        if (visible) {
            val o = overlay ?: runCatching { AssistantOverlay(this) }.getOrNull()?.also { overlay = it }
            o?.show()
            o?.render(AgentController.state.value)
            o?.renderChron(com.chrauto.ai.voice.ChronEngine.state.value)
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
    @Volatile private var lastEventAt = 0L
    @Volatile private var lastSnapshot: Map<String, UiNodeSnapshot> = emptyMap()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (event.packageName?.toString() != packageName || overlay == null) lastEventAt = SystemClock.uptimeMillis()
            else -> Unit
        }
    }

    /** Waits until the UI has been quiet for a moment (or [maxMs]) instead of sleeping a fixed time. */
    suspend fun settle(maxMs: Long, quietMs: Long = 280) {
        val start = SystemClock.uptimeMillis()
        delay(120)
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - lastEventAt >= quietMs) return
            delay(40)
        }
    }

    private fun activeRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        val ws = runCatching { windows }.getOrNull().orEmpty()
        return (ws.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: ws.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION })?.root
    }

    fun snapshot(): UiObservation? {
        val root = activeRoot() ?: return null
        val nodes = mutableListOf<UiNodeSnapshot>()
        collect(root, "0", nodes)
        lastSnapshot = nodes.associateBy { it.id }
        val metrics = resources.displayMetrics
        return UiObservation(root.packageName?.toString(), root.className?.toString(), nodes,
            screenWidth = metrics.widthPixels, screenHeight = metrics.heightPixels)
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
            node.className?.toString(), node.isClickable || node.hasAction(AccessibilityNodeInfo.ACTION_CLICK), node.isEditable,
            node.isScrollable, node.isEnabled, bounds,
            node.actionList.map { it.id.toString() },
            checked = if (node.isCheckable) node.isChecked else null,
            hint = if (password) null else node.hintText?.toString()?.take(200),
            packageName = node.packageName?.toString()
        )
        for (index in 0 until node.childCount) node.getChild(index)?.let { child -> collect(child, "$path.$index", out) }
    }

    suspend fun executeAction(action: AgentAction): ActionResult = withContext(Dispatchers.Main) {
        val executor = AccessibilityActionExecutor(
            findNode = { id -> resolve(id)?.let(::AccessibilityNodeInfoTarget) },
            performGlobalAction = ::performGlobalAction,
            findFocusedInput = { (activeRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))?.let(::AccessibilityNodeInfoTarget) }
        )
        when (action) {
            is AgentAction.OpenApp -> runCatching {
                startActivity(packageManager.getLaunchIntentForPackage(action.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) ?: error("App not installed"))
                ActionResult(true, "Opened ${action.packageName}")
            }.getOrElse { ActionResult(false, it.message ?: "Could not open app") }
            is AgentAction.Tap -> tap(action, executor)
            is AgentAction.TypeText -> {
                val typed = executor.execute(action)
                if (!typed.ok || !action.submit) typed else {
                    delay(120)
                    val enter = executor.execute(AgentAction.PressEnter(action.nodeId))
                    if (enter.ok) ActionResult(true, "Typed and submitted") else ActionResult(true, "Typed (Enter not available: ${enter.message})")
                }
            }
            is AgentAction.TapAt -> gestureTap(action.x, action.y).let { ActionResult(it, if (it) "Tapped on screen" else "Tap gesture was cancelled") }
            is AgentAction.GesturePath -> dispatchPath(action).let { ActionResult(it, if (it) "Gesture completed" else "Gesture was cancelled or unsupported") }
            is AgentAction.Scroll, AgentAction.PressBack, is AgentAction.PressEnter -> executor.execute(action)
            is AgentAction.Wait -> { delay(action.milliseconds); ActionResult(true, "Wait completed") }
            is AgentAction.AskUser -> ActionResult(false, "User confirmation required")
            is AgentAction.Finish -> ActionResult(true, "Finish is verified by orchestrator")
            is AgentAction.ReportError -> ActionResult(false, action.message)
        }
    }

    /**
     * Taps with a real touch at the centre of the exact element the AI picked (what a finger would do),
     * falling back to an accessibility click on the element or its clickable parent.
     */
    private suspend fun tap(action: AgentAction.Tap, executor: AccessibilityActionExecutor): ActionResult {
        val node = resolve(action.nodeId)
        val bounds = node?.let { n -> Rect().also { n.getBoundsInScreen(it) } } ?: lastSnapshot[action.nodeId]?.bounds
        val point = bounds?.let(::tapPoint)
        // Under the floating bubble a gesture races the window-flag change, so prefer a direct accessibility click there.
        if (point != null && overlay?.covers(point.first, point.second) == true) {
            var t = node
            while (t != null && !(t.isClickable || t.hasAction(AccessibilityNodeInfo.ACTION_CLICK))) t = t.parent
            if (t != null && t.isEnabled && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ActionResult(true, "Clicked (under assistant)")
        }
        if (action.viaGesture && point != null && (node == null || node.isVisibleToUser)) {
            val ok = overlay?.withTouchPassThrough(point.first, point.second) { gestureTap(point.first, point.second) } ?: gestureTap(point.first, point.second)
            if (ok) return ActionResult(true, "Tapped at ${point.first.toInt()},${point.second.toInt()}")
        }
        var target = node
        while (target != null && !(target.isClickable || target.hasAction(AccessibilityNodeInfo.ACTION_CLICK))) target = target.parent
        if (target != null && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ActionResult(true, "Clicked")
        return if (node == null) ActionResult(false, "Tap target is no longer on screen") else executor.execute(AgentAction.Tap(action.nodeId))
    }

    /** Centre of the element, clipped to the visible screen. */
    private fun tapPoint(b: Rect): Pair<Float, Float>? {
        val m = resources.displayMetrics
        val r = Rect(b)
        if (!r.intersect(0, 0, m.widthPixels, m.heightPixels) || r.width() < 2 || r.height() < 2) return null
        return r.exactCenterX() to r.exactCenterY()
    }

    /** Dispatches one validated continuous pointer path; arbitrary simultaneous independent fingers are not supported here. */
    private suspend fun dispatchPath(action: AgentAction.GesturePath): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(action.points.first().x, action.points.first().y)
            action.points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, action.durationMs))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }
        if (!dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
    }

    private suspend fun gestureTap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 55)).build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }
        if (!dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
    }

    /**
     * Node ids are tree paths, which go stale when the screen changes between "look" and "act"
     * (lists loading, suggestions appearing). Verify the node is still the same element; otherwise
     * re-find it by text/description/class nearest to where it was.
     */
    private fun resolve(id: String): AccessibilityNodeInfo? {
        val expected = lastSnapshot[id]
        val byPath = find(id)
        if (expected == null) return byPath
        if (byPath != null && sameElement(byPath, expected)) return byPath
        val root = activeRoot() ?: return byPath
        val ex = expected.bounds.exactCenterX(); val ey = expected.bounds.exactCenterY()
        var best: AccessibilityNodeInfo? = null
        var bestDist = Float.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited < 2_000) {
            val n = stack.removeLast(); visited++
            if (n.className?.toString() == expected.className) {
                val textMatch = !expected.text.isNullOrBlank() && n.text?.toString() == expected.text
                val descMatch = !expected.contentDescription.isNullOrBlank() && n.contentDescription?.toString() == expected.contentDescription
                val r = Rect().also { n.getBoundsInScreen(it) }
                val d = hypot(r.exactCenterX() - ex, r.exactCenterY() - ey)
                val labelless = expected.text.isNullOrBlank() && expected.contentDescription.isNullOrBlank()
                if (((textMatch || descMatch) && d < bestDist) || (labelless && d < 40f && d < bestDist)) { best = n; bestDist = d }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
        }
        return best ?: byPath?.takeIf { expected.text.isNullOrBlank() && expected.contentDescription.isNullOrBlank() }
    }

    private fun sameElement(n: AccessibilityNodeInfo, s: UiNodeSnapshot): Boolean {
        if (n.className?.toString() != s.className) return false
        val text = if (n.isPassword) null else n.text?.toString()?.take(MAX_TEXT_LENGTH)
        if (!n.isEditable && (text ?: "") != (s.text ?: "")) return false
        if ((n.contentDescription?.toString()?.take(300) ?: "") != (s.contentDescription ?: "")) return false
        val r = Rect().also { n.getBoundsInScreen(it) }
        return hypot(r.exactCenterX() - s.bounds.exactCenterX(), r.exactCenterY() - s.bounds.exactCenterY()) < 60f
    }

    private fun AccessibilityNodeInfo.hasAction(id: Int) = actionList.any { it.id == id }

    private fun find(id: String): AccessibilityNodeInfo? {
        val root = activeRoot() ?: return null
        var current: AccessibilityNodeInfo? = root
        for (part in id.split('.').drop(1)) {
            val index = part.toIntOrNull() ?: return null
            current = current?.getChild(index)
        }
        return current
    }
}
