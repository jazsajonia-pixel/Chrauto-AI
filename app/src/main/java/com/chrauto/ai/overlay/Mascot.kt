package com.chrauto.ai.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class Mood { IDLE, HAPPY, THINKING, WORKING, LISTENING, SAD }

object MascotColors {
    const val VIOLET = 0xFF7C5CFF.toInt()
    const val PINK = 0xFFFF7EB6.toInt()
    const val INK = 0xFF2B2160.toInt()
    const val CHEEK = 0xFFFF9EC4.toInt()
    const val GOLD = 0xFFFFD166.toInt()
    const val MINT = 0xFF3DDC97.toInt()
    const val CORAL = 0xFFFF6B6B.toInt()
}

/** Draws the Chrauto buddy: a soft gradient blob with blinking eyes, cheeks, and mood-specific details. */
object MascotPainter {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val eye = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pupil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.INK }
    private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.CHEEK; alpha = 170 }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.INK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.GOLD }
    private val oval = RectF()
    private val path = Path()
    // Shaders are cached per radius and drawn around (0,0) via canvas.translate, so no per-frame allocations.
    private class Shaders(r: Float) {
        val body: Shader = LinearGradient(-r, -r, r, r, MascotColors.VIOLET, MascotColors.PINK, Shader.TileMode.CLAMP)
        val ring: Shader = SweepGradient(0f, 0f, intArrayOf(0x00FFFFFF, MascotColors.GOLD, MascotColors.PINK, 0x00FFFFFF), null)
        val shadow: Shader = RadialGradient(0f, r * 0.95f, r * 0.9f, 0x33000000, 0x00000000, Shader.TileMode.CLAMP)
    }
    private val cache = HashMap<Int, Shaders>()
    private var bodyShader: Shader? = null
    private var ringShader: Shader? = null
    private var shadowShader: Shader? = null
    private fun shaders(r: Float) {
        val s = cache.getOrPut((r * 4).toInt()) { if (cache.size > 12) cache.clear(); Shaders(r) }
        bodyShader = s.body; ringShader = s.ring; shadowShader = s.shadow
    }

    /** [t] is a free-running animation clock in seconds. [blink] 0 = open, 1 = closed. */
    fun draw(canvas: Canvas, ox: Float, oy: Float, r: Float, mood: Mood, t: Float, blink: Float, lookX: Float = 0f, lookY: Float = 0f) {
        shaders(r)
        canvas.save()
        canvas.translate(ox, oy)
        val cx = 0f; val cy = 0f
        // Glow / activity ring
        when (mood) {
            Mood.THINKING, Mood.WORKING -> {
                ring.strokeWidth = r * 0.14f
                ring.shader = ringShader
                canvas.save(); canvas.rotate((t * 360f * (if (mood == Mood.THINKING) 0.9f else 1.6f)) % 360f, cx, cy)
                oval.set(cx - r * 1.22f, cy - r * 1.22f, cx + r * 1.22f, cy + r * 1.22f)
                canvas.drawArc(oval, 0f, 280f, false, ring); canvas.restore()
                ring.shader = null
            }
            Mood.LISTENING -> {
                for (i in 0..1) {
                    val p = ((t * 0.9f) + i * 0.5f) % 1f
                    ring.strokeWidth = r * 0.08f
                    ring.color = MascotColors.PINK; ring.alpha = ((1f - p) * 200).toInt()
                    canvas.drawCircle(cx, cy, r * (1.05f + p * 0.55f), ring)
                }
                ring.alpha = 255
            }
            else -> Unit
        }
        // Soft drop shadow
        glow.shader = shadowShader
        oval.set(cx - r * 0.85f, cy + r * 0.72f, cx + r * 0.85f, cy + r * 1.12f)
        canvas.drawOval(oval, glow)

        // Body (slightly squishy)
        val squish = 1f + 0.03f * sin(t * 2f * PI.toFloat())
        body.shader = bodyShader
        oval.set(cx - r * squish, cy - r / squish, cx + r * squish, cy + r / squish)
        canvas.drawOval(oval, body)
        oval.set(cx - r * 0.62f, cy - r * 0.82f, cx - r * 0.05f, cy - r * 0.42f)
        canvas.drawOval(oval, shine)

        // Antenna sparkle
        val sy = cy - r * 1.18f + r * 0.06f * sin(t * 3f)
        drawSparkle(canvas, cx + r * 0.05f, sy, r * 0.2f, t)

        // Eyes
        val ex = r * 0.36f; val ey = cy - r * 0.08f
        val ew = r * 0.21f; val eh = r * 0.27f * (1f - blink.coerceIn(0f, 1f) * 0.92f)
        line.strokeWidth = r * 0.1f
        if (mood == Mood.HAPPY) {
            for (s in listOf(-1f, 1f)) {
                path.reset(); path.moveTo(cx + s * ex - ew, ey + r * 0.05f)
                path.quadTo(cx + s * ex, ey - r * 0.2f, cx + s * ex + ew, ey + r * 0.05f)
                canvas.drawPath(path, line)
            }
        } else {
            for (s in listOf(-1f, 1f)) {
                oval.set(cx + s * ex - ew, ey - eh, cx + s * ex + ew, ey + eh)
                canvas.drawOval(oval, eye)
                if (eh > r * 0.06f) {
                    val px = cx + s * ex + lookX.coerceIn(-1f, 1f) * ew * 0.45f
                    val py = ey + lookY.coerceIn(-1f, 1f) * eh * 0.4f
                    canvas.drawCircle(px, py, ew * 0.58f, pupil)
                    canvas.drawCircle(px - ew * 0.2f, py - ew * 0.22f, ew * 0.18f, eye)
                }
            }
        }
        // Cheeks
        canvas.drawCircle(cx - r * 0.62f, cy + r * 0.24f, r * 0.13f, cheek)
        canvas.drawCircle(cx + r * 0.62f, cy + r * 0.24f, r * 0.13f, cheek)
        // Mouth
        line.strokeWidth = r * 0.08f
        path.reset()
        val my = cy + r * 0.3f
        when (mood) {
            Mood.SAD -> { path.moveTo(cx - r * 0.16f, my + r * 0.08f); path.quadTo(cx, my - r * 0.06f, cx + r * 0.16f, my + r * 0.08f); canvas.drawPath(path, line) }
            Mood.LISTENING, Mood.THINKING -> { line.style = Paint.Style.FILL; canvas.drawCircle(cx, my + r * 0.02f, r * 0.07f, line); line.style = Paint.Style.STROKE }
            Mood.HAPPY -> { path.moveTo(cx - r * 0.2f, my - r * 0.02f); path.quadTo(cx, my + r * 0.24f, cx + r * 0.2f, my - r * 0.02f); canvas.drawPath(path, line) }
            else -> { path.moveTo(cx - r * 0.15f, my); path.quadTo(cx, my + r * 0.15f, cx + r * 0.15f, my); canvas.drawPath(path, line) }
        }
        canvas.restore()
    }

    private fun drawSparkle(canvas: Canvas, x: Float, y: Float, s: Float, t: Float) {
        val k = 0.8f + 0.2f * sin(t * 4f)
        path.reset()
        for (i in 0 until 8) {
            val a = (i * PI / 4).toFloat() - PI.toFloat() / 2
            val rr = if (i % 2 == 0) s * k else s * 0.35f
            val px = x + cos(a) * rr; val py = y + sin(a) * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); canvas.drawPath(path, spark)
    }
}

/** Self-animating mascot view (bubble). */
open class MascotView(context: Context) : View(context) {
    var mood: Mood = Mood.IDLE
        set(value) { field = value; invalidate() }
    var lookX = 0f
    var lookY = 0f
    /** Small mint "ear" shown while Chron is listening for its name. */
    var earOn = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Microphone level 0..1: draws a soft ring that pulses with your voice while Chron listens. */
    var level = 0f
    private var shownLevel = 0f
    private val voiceRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = MascotColors.PINK }
    private val ear = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.MINT }
    private val earRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var lastFrame = 0L
    protected var clock = 0f
    private var blink = 0f
    private var nextBlink = 2.5f
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        var last = System.nanoTime()
        addUpdateListener {
            val now = System.nanoTime(); val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f); clock += dt; last = now
            nextBlink -= dt
            blink = if (nextBlink < 0f) { val b = (-nextBlink) / 0.16f; if (b >= 2f) { nextBlink = 2f + (Math.random() * 3).toFloat(); 0f } else if (b < 1f) b else 2f - b } else 0f
            // ~40 fps is plenty for a floating buddy and saves battery; skip work when not visible.
            if (isShown && now - lastFrame >= 24_000_000L) { lastFrame = now; invalidate() }
        }
    }

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) * 0.36f
        val bob = r * 0.06f * sin(clock * 2.4f)
        shownLevel += (level - shownLevel) * 0.35f
        if (shownLevel > 0.02f) {
            val rr = r * (1.08f + 0.28f * shownLevel)
            voiceRing.strokeWidth = r * (0.06f + 0.08f * shownLevel)
            voiceRing.alpha = (90 + 140 * shownLevel).toInt().coerceAtMost(230)
            canvas.drawCircle(width / 2f, height / 2f + r * 0.08f + bob, rr, voiceRing)
        }
        MascotPainter.draw(canvas, width / 2f, height / 2f + r * 0.08f + bob, r, mood, clock, blink, lookX, lookY)
        if (earOn) {
            val ex = width / 2f + r * 0.78f; val ey = height / 2f - r * 0.7f + bob
            val pulse = 1f + 0.18f * sin(clock * 4f)
            canvas.drawCircle(ex, ey, r * 0.2f * pulse, earRing)
            canvas.drawCircle(ex, ey, r * 0.14f * pulse, ear)
        }
    }
}

/**
 * The on-screen "cursor buddy". Its hotspot is the view centre: a target reticle is drawn there
 * and the little mascot hovers just above-right of it, with a loader while thinking.
 */
class CursorBuddyView(context: Context) : MascotView(context) {
    var showReticle = false
    private var tapPulse = -1f
    private val reticle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = MascotColors.PINK; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.VIOLET }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bubbleDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.VIOLET }

    fun pulse() {
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 520; addUpdateListener { tapPulse = it.animatedValue as Float; invalidate() }; doOnEndCompat { tapPulse = -1f }; start() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val unit = min(width, height) / 2f
        if (showReticle) {
            reticle.strokeWidth = unit * 0.06f
            reticle.alpha = 220
            val rr = unit * (0.24f + 0.03f * sin(clock * 6f))
            canvas.drawCircle(cx, cy, rr, reticle)
            canvas.drawCircle(cx, cy, unit * 0.07f, dot)
        }
        if (tapPulse >= 0f) {
            reticle.strokeWidth = unit * 0.08f * (1f - tapPulse)
            reticle.alpha = ((1f - tapPulse) * 255).toInt()
            canvas.drawCircle(cx, cy, unit * (0.2f + tapPulse * 0.75f), reticle)
            reticle.alpha = 255
        }
        val r = unit * 0.34f
        val mx = cx + unit * 0.42f
        val my = cy - unit * 0.42f + r * 0.08f * sin(clock * 3f)
        MascotPainter.draw(canvas, mx, my, r, mood, clock, 0f, lookX - 0.6f, lookY + 0.6f)
        if (mood == Mood.THINKING) {
            // "…" thought bubble
            val bx = mx - r * 1.5f; val by = my - r * 1.25f
            canvas.drawRoundRect(bx - r * 0.7f, by - r * 0.32f, bx + r * 0.7f, by + r * 0.32f, r * 0.32f, r * 0.32f, bubble)
            for (i in 0..2) {
                val a = 0.4f + 0.6f * ((sin(clock * 6f - i * 0.9f) + 1f) / 2f)
                bubbleDot.alpha = (a * 255).toInt()
                canvas.drawCircle(bx + (i - 1) * r * 0.4f, by, r * 0.11f, bubbleDot)
            }
        }
    }
}

internal fun ValueAnimator.doOnEndCompat(block: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })
}
