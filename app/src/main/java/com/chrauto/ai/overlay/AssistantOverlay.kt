package com.chrauto.ai.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.chrauto.ai.MainActivity
import com.chrauto.ai.R
import com.chrauto.ai.agent.AgentController
import com.chrauto.ai.agent.AgentPhase
import com.chrauto.ai.agent.AgentState
import com.chrauto.ai.agent.RunStatus
import com.chrauto.ai.data.SettingsStore
import com.chrauto.ai.voice.ChronEngine
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Floating assistant drawn as accessibility overlays (no extra "draw over apps" permission).
 *
 *  - Dock: a draggable mascot bubble at the bottom. Tapping it pops open a compact panel
 *    (one status line + one row of buttons) so it never covers more than a thin strip of the screen.
 *  - Cursor buddy: a non-touchable mini mascot that flies to whatever the agent is about to
 *    tap/type/scroll and shows a loader while thinking.
 *
 * The agent acts through accessibility node actions, not screen-coordinate taps, so it can't
 * "hit" these overlays by accident; the cursor window is also FLAG_NOT_TOUCHABLE.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class AssistantOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val settings = SettingsStore(context)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Number) = (v.toFloat() * density).toInt()

    private var chron = ChronEngine.State()
    private val listening get() = chron.mode == ChronEngine.Mode.ACTIVE
    private var expandedByVoice = false
    private var micPromptShown = false
    private var expanded = false
    private var hidden = false
    private var attached = false
    private var lastState = AgentState()
    private var statusOverride: String? = null
    private var moodOverride: Mood? = null

    // ---------- Dock ----------
    private val bubble = MascotView(context).apply { contentDescription = "Chrauto assistant" }
    private val statusText = TextView(context).apply {
        textSize = 12.5f; setTextColor(MascotColors.INK); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(4), 0, dp(4), dp(6))
    }
    private val micBtn = roundButton(R.drawable.ic_mic, "Talk to Chron", MascotColors.VIOLET) { ChronEngine.listenNow(context) }
    private val stopBtn = roundButton(R.drawable.ic_stop, "Stop", MascotColors.CORAL) {
        if (AgentController.isRunning) { AgentController.stop(); ChronEngine.announce("Okay, stopped.") } else ChronEngine.hush()
    }
    private val retryBtn = roundButton(R.drawable.ic_replay, "Repeat last task", 0xFF4C9AFF.toInt()) { retryLast() }
    private val appBtn = roundButton(R.drawable.ic_home, "Open Chrauto", 0xFF8E7CC3.toInt()) { openApp() }
    private val hideBtn = roundButton(R.drawable.ic_close, "Hide assistant", 0xFF9AA0B4.toInt()) { hide() }
    private val buttons = listOf(micBtn, stopBtn, retryBtn, appBtn, hideBtn)

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat(); setColor(0xF7FFFFFF.toInt()); setStroke(dp(1.5f), 0x337C5CFF)
        }
        elevation = dp(8).toFloat()
        setPadding(dp(8), dp(8), dp(8), dp(8))
        visibility = View.GONE
        addView(statusText, LinearLayout.LayoutParams(dp(208), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { i, b ->
                addView(b, LinearLayout.LayoutParams(dp(48), dp(48)).apply { if (i > 0) marginStart = dp(4) })
            }
        })
    }
    private val dock = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM or Gravity.END
        clipChildren = false; clipToPadding = false
        setPadding(dp(6), dp(6), dp(6), dp(6))
        addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        addView(bubble, LinearLayout.LayoutParams(dp(48), dp(48)))
    }
    private val dockParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(10); y = dp(28) }

    // ---------- Cursor buddy ----------
    private val cursorSize = dp(112)
    private val cursor = CursorBuddyView(context)
    private var cursorShown = false
    private var cursorAnim: ValueAnimator? = null
    private var lastTargetKey: String? = null
    /** Difference between requested window position and where the system actually placed it (cutouts, bars). */
    private var calibX = 0
    private var calibY = 0
    /** Full-screen, untouchable layer; the buddy moves inside it with cheap GPU translations. */
    private val cursorLayer = FrameLayout(context).apply {
        clipChildren = false
        addView(cursor, FrameLayout.LayoutParams(cursorSize, cursorSize))
    }
    private val cursorParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    init { setupBubbleTouch() }

    fun attach() {
        if (attached) return
        runCatching { wm.addView(dock, dockParams) }.onSuccess { attached = true }
        hidden = false
        refresh()
    }

    fun detach() {
        hideCursor(immediate = true)
        if (attached) runCatching { wm.removeView(dock) }
        attached = false
        handler.removeCallbacksAndMessages(null)
    }

    /** True if screen point (x, y) falls on the floating dock. */
    fun covers(x: Float, y: Float): Boolean {
        if (!attached || dock.visibility != View.VISIBLE || dock.width == 0) return false
        val loc = IntArray(2); dock.getLocationOnScreen(loc)
        val pad = dp(8)
        return x >= loc[0] - pad && x <= loc[0] + dock.width + pad && y >= loc[1] - pad && y <= loc[1] + dock.height + pad
    }

    /**
     * Lets an injected tap at (x, y) reach the app underneath: if the point is on the dock, the dock
     * fades out and stops taking touches until the tap is done. Window flag changes apply
     * asynchronously, so we wait a couple of frames before tapping.
     */
    suspend fun <T> withTouchPassThrough(x: Float, y: Float, block: suspend () -> T): T {
        if (!covers(x, y)) return block()
        dockParams.flags = dockParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(dock, dockParams) }
        dock.animate().cancel(); dock.alpha = 0.15f
        kotlinx.coroutines.delay(200)
        try { return block() } finally {
            kotlinx.coroutines.delay(60)
            if (attached) {
                dockParams.flags = dockParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(dock, dockParams) }
                dock.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    fun show() { hidden = false; if (!attached) attach() else dock.visibility = View.VISIBLE; refresh() }

    fun hide() {
        collapse()
        hidden = true
        dock.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(180).withEndAction {
            if (hidden) dock.visibility = View.GONE
            dock.alpha = 1f; dock.scaleX = 1f; dock.scaleY = 1f
        }.start()
    }

    /** Called for every agent state change. */
    fun render(state: AgentState) {
        val previous = lastState
        lastState = state
        if (state.status == RunStatus.RUNNING && previous.status != RunStatus.RUNNING) {
            statusOverride = null
            if (hidden) show()
        }
        if (previous.status == RunStatus.RUNNING && state.status != RunStatus.RUNNING) {
            // Keep the footprint small: tuck the panel away a few seconds after a run ends.
            handler.removeCallbacks(autoCollapse); handler.postDelayed(autoCollapse, 4_500)
            // Chron narrates the result out loud (see ChronEngine); the bubble just reacts.
            when (state.status) {
                RunStatus.SUCCEEDED -> flashMood(Mood.HAPPY)
                RunStatus.FAILED, RunStatus.NEEDS_ASSISTANCE -> flashMood(Mood.SAD)
                else -> Unit
            }
        }
        updateCursor(state, previous)
        refresh()
        handler.removeCallbacks(ticker)
        if (state.status == RunStatus.RUNNING) handler.postDelayed(ticker, 1_000)
    }
    private val ticker = object : Runnable {
        override fun run() { if (lastState.status == RunStatus.RUNNING) { refresh(); handler.postDelayed(this, 1_000) } }
    }

    // ---------- Rendering ----------
    private fun refresh() {
        val s = lastState
        val running = s.status == RunStatus.RUNNING
        bubble.mood = moodOverride ?: when {
            listening -> Mood.LISTENING
            chron.mode == ChronEngine.Mode.THINKING -> Mood.THINKING
            chron.mode == ChronEngine.Mode.SPEAKING -> Mood.HAPPY
            running && (s.phase == AgentPhase.MOVING || s.phase == AgentPhase.ACTING) -> Mood.WORKING
            running -> Mood.THINKING
            else -> Mood.IDLE
        }
        cursor.mood = when {
            !running -> moodOverride ?: Mood.IDLE
            s.phase == AgentPhase.THINKING || s.phase == AgentPhase.OBSERVING -> Mood.THINKING
            else -> Mood.WORKING
        }
        val live = if (chron.live) "● " else ""
        setStatus(statusOverride ?: when {
            listening -> live + (chron.partial.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: if (chron.live) "Listening… go ahead" else "Listening… tell me what to do")
            chron.mode == ChronEngine.Mode.THINKING -> live + (chron.reply.takeIf { it.isNotBlank() } ?: "“${chron.heard}” · thinking…")
            chron.mode == ChronEngine.Mode.SPEAKING -> live + chron.reply.ifBlank { "…" }
            running && s.phase == AgentPhase.THINKING -> {
                val secs = (System.currentTimeMillis() - s.phaseSince) / 1000
                if (secs >= 3) "Thinking… ${secs}s" else "Thinking…"
            }
            running -> (if (s.step > 0 && (s.phase == AgentPhase.MOVING || s.phase == AgentPhase.ACTING)) "Step ${s.step} · " else "") + s.detail.ifBlank { "Working…" }
            s.status == RunStatus.SUCCEEDED -> "Done: ${s.detail}"
            s.status == RunStatus.FAILED -> "Stuck: ${s.detail}"
            s.status == RunStatus.NEEDS_ASSISTANCE -> "Needs you: ${s.detail} · tap ↻ to approve"
            s.status == RunStatus.CANCELLED -> "Stopped. Tap the mic for a new task."
            chron.mode == ChronEngine.Mode.PASSIVE -> "Say “${settings.wakeName}, …” or tap the mic"
            else -> "Hi! Tap the mic and tell me what to do."
        }, override = false)
        micBtn.tint(if (listening) MascotColors.PINK else MascotColors.VIOLET)
        micBtn.contentDescription = if (chron.mode == ChronEngine.Mode.SPEAKING) "Interrupt and talk" else "Talk to Chron"
        val canStop = running || chron.mode == ChronEngine.Mode.SPEAKING
        stopBtn.isEnabled = canStop; stopBtn.alpha = if (canStop) 1f else 0.35f
        retryBtn.isEnabled = !running && settings.lastTask.isNotBlank(); retryBtn.alpha = if (retryBtn.isEnabled) 1f else 0.35f
    }

    private fun setStatus(text: String, override: Boolean = true) {
        if (override) {
            statusOverride = text
            handler.removeCallbacks(clearOverride); handler.postDelayed(clearOverride, 4_000)
        }
        if (statusText.text != text) statusText.text = text
    }
    private val autoCollapse = Runnable { if (lastState.status != RunStatus.RUNNING && chron.mode != ChronEngine.Mode.ACTIVE && chron.mode != ChronEngine.Mode.SPEAKING) collapse() }
    private val clearOverride = Runnable { statusOverride = null; refresh() }

    private fun flashMood(mood: Mood) {
        moodOverride = mood; refresh()
        handler.removeCallbacks(clearMood); handler.postDelayed(clearMood, 2_600)
    }
    private val clearMood = Runnable { moodOverride = null; refresh() }

    // ---------- Expand / collapse ----------
    private fun toggle() = if (expanded) collapse() else expand()

    private fun expand() {
        if (expanded) return
        expanded = true
        boing()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f; panel.scaleX = 0.4f; panel.scaleY = 0.7f
        panel.post {
            panel.pivotX = panel.width.toFloat(); panel.pivotY = panel.height.toFloat()
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(1.4f)).start()
        }
        buttons.forEachIndexed { i, b ->
            b.scaleX = 0f; b.scaleY = 0f
            b.animate().scaleX(1f).scaleY(1f).setStartDelay(90L + i * 45L).setDuration(240).setInterpolator(OvershootInterpolator(2.2f)).start()
        }
        refresh()
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        boing()
        panel.animate().alpha(0f).scaleX(0.4f).scaleY(0.7f).setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!expanded) panel.visibility = View.GONE }.start()
    }

    private fun boing() {
        bubble.animate().cancel()
        bubble.scaleX = 1.18f; bubble.scaleY = 0.84f
        bubble.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3f)).start()
    }

    private fun setupBubbleTouch() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false; var held = false
        // Hold the bubble to talk to Chron right away.
        val holdToTalk = Runnable { if (!dragging) { held = true; boing(); if (!expanded) { expand(); expandedByVoice = true }; ChronEngine.listenNow(context) } }
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = dockParams.x; startY = dockParams.y; dragging = false; held = false
                    handler.postDelayed(holdToTalk, ViewConfiguration.getLongPressTimeout().toLong())
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) { dragging = true; handler.removeCallbacks(holdToTalk) }
                    if (dragging) {
                        val metrics = context.resources.displayMetrics
                        dockParams.x = (startX - dx.toInt()).coerceIn(0, metrics.widthPixels - dp(70))
                        dockParams.y = (startY - dy.toInt()).coerceIn(0, metrics.heightPixels - dp(120))
                        bubble.lookX = (-dx / 200f).coerceIn(-1f, 1f) * -1f; bubble.lookY = (dy / 200f).coerceIn(-1f, 1f)
                        if (attached) runCatching { wm.updateViewLayout(dock, dockParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdToTalk)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    bubble.lookX = 0f; bubble.lookY = 0f
                    if (!dragging && !held && e.actionMasked == MotionEvent.ACTION_UP && abs(e.rawX - downX) < slop) { v.performClick(); toggle() }
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Cursor buddy ----------
    private fun updateCursor(state: AgentState, previous: AgentState) {
        if (state.screenAware) { hideCursor(immediate = true); lastTargetKey = null; return }
        val running = state.status == RunStatus.RUNNING
        if (!running) {
            lastTargetKey = null
            if (cursorShown) { cursor.showReticle = false; handler.postDelayed({ if (lastState.status != RunStatus.RUNNING) hideCursor() }, 1_400) }
            return
        }
        if (!cursorShown) showCursor()
        val target = state.target
        cursor.showReticle = target != null && (state.phase == AgentPhase.MOVING || state.phase == AgentPhase.ACTING)
        if (target != null) {
            val key = "${target.centerX},${target.centerY},${state.step}"
            if (key != lastTargetKey) { lastTargetKey = key; moveCursorTo(target.centerX, target.centerY) }
        }
        if (state.phase == AgentPhase.ACTING && previous.phase != AgentPhase.ACTING && target != null) cursor.pulse()
    }

    private fun showCursor() {
        val m = context.resources.displayMetrics
        cursorParams.x = 0; cursorParams.y = 0
        cursor.translationX = m.widthPixels / 2f - cursorSize / 2f
        cursor.translationY = m.heightPixels * 0.45f - cursorSize / 2f
        cursor.alpha = 0f; cursor.scaleX = 0.3f; cursor.scaleY = 0.3f
        runCatching { wm.addView(cursorLayer, cursorParams) }.onSuccess {
            cursorShown = true
            cursorLayer.post {
                val loc = IntArray(2); cursorLayer.getLocationOnScreen(loc)
                calibX = loc[0]; calibY = loc[1]
            }
            cursor.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.8f)).start()
        }
    }

    private fun hideCursor(immediate: Boolean = false) {
        cursorAnim?.cancel()
        if (!cursorShown) return
        val remove = { if (cursorShown) { runCatching { wm.removeView(cursorLayer) }; cursorShown = false } }
        if (immediate) remove() else cursor.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(200).withEndAction(remove).start()
    }

    private fun moveCursorTo(cx: Int, cy: Int) {
        if (!cursorShown) return
        cursorAnim?.cancel()
        val fromX = cursor.translationX; val fromY = cursor.translationY
        val toX = cx - cursorSize / 2f - calibX; val toY = cy - cursorSize / 2f - calibY
        val dx = toX - fromX; val dy = toY - fromY
        val dist = hypot(dx, dy)
        if (dist < 2f) return
        cursor.lookX = dx / dist; cursor.lookY = dy / dist
        cursor.rotation = (dx / (dist + 1f)) * 12f
        cursorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (170 + dist / density * 0.5f).toLong().coerceIn(170, 300)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
                // Slight arc so it floats rather than slides.
                cursor.translationX = fromX + dx * f
                cursor.translationY = fromY + dy * f - dist * 0.12f * (4f * f * (1f - f))
            }
            doOnEndCompat { cursor.animate().rotation(0f).setDuration(180).start(); cursor.lookX = 0f; cursor.lookY = 0f }
            start()
        }
    }

    // ---------- Actions ----------
    private fun retryLast() {
        if (lastState.status == RunStatus.NEEDS_ASSISTANCE) {
            AgentController.approveAndContinue()?.let { setStatus(it); flashMood(Mood.SAD) } ?: ChronEngine.announce("Okay, continuing.")
            return
        }
        val task = settings.lastTask
        if (task.isBlank()) { setStatus("No previous task yet"); return }
        AgentController.startWithSavedSettings(task)?.let { setStatus(it); flashMood(Mood.SAD) }
    }

    private fun openApp(requestMic: Boolean = false) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_REQUEST_MIC, requestMic)
        runCatching { context.startActivity(intent) }
        collapse()
    }

    /** Mic level from Chron (0..1) for the bubble's voice ring. */
    fun renderLevel(level: Float) { bubble.level = level }

    /** Called for every Chron voice state change. */
    fun renderChron(state: ChronEngine.State) {
        val prev = chron
        chron = state
        bubble.earOn = state.mode == ChronEngine.Mode.PASSIVE || state.duplex
        if (state.mode == ChronEngine.Mode.ACTIVE && prev.mode != ChronEngine.Mode.ACTIVE) {
            if (hidden) show()
            if (!expanded) { expand(); expandedByVoice = true }
        }
        if ((state.mode == ChronEngine.Mode.PASSIVE || state.mode == ChronEngine.Mode.OFF) &&
            (prev.mode == ChronEngine.Mode.SPEAKING || prev.mode == ChronEngine.Mode.ACTIVE) && expandedByVoice) {
            handler.removeCallbacks(autoCollapse); handler.postDelayed(autoCollapse, 3_500); expandedByVoice = false
        }
        if (state.error?.startsWith("Microphone") == true && !micPromptShown) { micPromptShown = true; openApp(requestMic = true) }
        refresh()
    }

    private fun roundButton(icon: Int, label: String, color: Int, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(icon)
        contentDescription = label
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(9), dp(9), dp(9), dp(9))
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        elevation = dp(2).toFloat()
        setOnClickListener {
            it.animate().scaleX(0.82f).scaleY(0.82f).setDuration(70).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
            onClick()
        }
    }

    private fun ImageView.tint(color: Int) { (background as? GradientDrawable)?.setColor(color) }
}
