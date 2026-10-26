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

/** Chrauto's restrained product palette (shared by the in-app mark and the floating assistant). */
object MascotColors {
    const val ACCENT = 0xFF4F7CFF.toInt()      // cobalt: primary accent
    const val ACCENT_SOFT = 0xFF9DB6FF.toInt()
    const val HEAD_TOP = 0xFF222C42.toInt()
    const val HEAD_BOTTOM = 0xFF111827.toInt()
    const val VISOR = 0xFF070B14.toInt()
    const val INK = 0xFF0F172A.toInt()
    const val PANEL = 0xF2141B2D.toInt()
    const val PANEL_TEXT = 0xFFE6EAF2.toInt()
    const val SUCCESS = 0xFF2BB673.toInt()
    const val WARN = 0xFFE5A23A.toInt()
    const val DANGER = 0xFFE5484D.toInt()
    const val NEUTRAL = 0xFF8A93A6.toInt()
}

/**
 * Draws the Chrauto agent mark: a compact rounded-square head with a single visor. State is shown
 * only through the visor light (scan, sweep, solid bar, voice bars) and a thin activity arc, so it
 * reads as a tool status indicator rather than a cartoon face.
 */
object MascotPainter {
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val visor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.VISOR }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val node = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    // Shaders are cached per radius and drawn around (0,0) via canvas.translate: no per-frame allocations.
    private class Shaders(r: Float) {
        val head: Shader = LinearGradient(0f, -r, 0f, r, MascotColors.HEAD_TOP, MascotColors.HEAD_BOTTOM, Shader.TileMode.CLAMP)
    }
    private val cache = HashMap<Int, Shaders>()

    fun colorFor(mood: Mood) = when (mood) {
        Mood.HAPPY -> MascotColors.SUCCESS
        Mood.SAD -> MascotColors.WARN
        Mood.LISTENING -> MascotColors.ACCENT_SOFT
        else -> MascotColors.ACCENT
    }

    /** [t] is a free-running clock in seconds; [blink] 0..1 dims the idle light briefly. [r] is half the mark's width. */
    fun draw(canvas: Canvas, ox: Float, oy: Float, r: Float, mood: Mood, t: Float, blink: Float, lookX: Float = 0f, lookY: Float = 0f) {
        val sh = cache.getOrPut((r * 4).toInt()) { if (cache.size > 12) cache.clear(); Shaders(r) }
        val c = colorFor(mood)
        canvas.save()
        canvas.translate(ox, oy)

        // Thin activity arc around the head while the agent is busy.
        if (mood == Mood.THINKING || mood == Mood.WORKING) {
            arc.color = c; arc.alpha = 200; arc.strokeWidth = r * 0.07f
            val speed = if (mood == Mood.THINKING) 220f else 360f
            canvas.save(); canvas.rotate((t * speed) % 360f)
            rect.set(-r * 1.16f, -r * 1.16f, r * 1.16f, r * 1.16f)
            canvas.drawArc(rect, 0f, 70f, false, arc)
            canvas.restore()
        }

        // Head: rounded square with a hairline accent rim.
        val w = r * 0.92f; val h = r * 0.84f
        rect.set(-w, -h, w, h)
        head.shader = sh.head
        canvas.drawRoundRect(rect, r * 0.34f, r * 0.34f, head)
        rim.color = c; rim.alpha = 150; rim.strokeWidth = r * 0.05f
        canvas.drawRoundRect(rect, r * 0.34f, r * 0.34f, rim)

        // Status node on top.
        node.color = c
        canvas.drawCircle(0f, -h - r * 0.16f, r * 0.08f, node)
        rect.set(-r * 0.025f, -h - r * 0.1f, r * 0.025f, -h)
        canvas.drawRect(rect, node)

        // Visor.
        val vw = r * 0.64f; val vh = r * 0.17f; val vy = -r * 0.06f + lookY.coerceIn(-1f, 1f) * r * 0.03f
        rect.set(-vw, vy - vh, vw, vy + vh)
        canvas.drawRoundRect(rect, vh, vh, visor)
        light.color = c
        val inner = vw - vh * 0.55f
        when (mood) {
            Mood.IDLE -> {
                // Slow scanning light; dims briefly on "blink".
                val x = (sin(t * 0.9f) * 0.55f + lookX.coerceIn(-1f, 1f) * 0.3f).coerceIn(-0.7f, 0.7f) * inner
                light.alpha = (255 * (1f - 0.7f * blink.coerceIn(0f, 1f))).toInt()
                pill(canvas, x - r * 0.16f, vy, x + r * 0.16f, vh * 0.5f)
            }
            Mood.THINKING -> {
                val x = sin(t * 3.2f) * inner * 0.72f
                light.alpha = 255
                pill(canvas, x - r * 0.2f, vy, x + r * 0.2f, vh * 0.5f)
            }
            Mood.WORKING, Mood.HAPPY -> { light.alpha = 255; pill(canvas, -inner, vy, inner, vh * 0.5f) }
            Mood.SAD -> { light.alpha = 255; pill(canvas, -inner * 0.45f, vy, inner * 0.45f, vh * 0.5f) }
            Mood.LISTENING -> {
                light.alpha = 255
                for (i in 0 until 5) {
                    val x = (i - 2) * inner * 0.38f
                    val amp = 0.35f + 0.65f * ((sin(t * 7f + i * 1.3f) + 1f) / 2f)
                    rect.set(x - r * 0.045f, vy - vh * 0.75f * amp, x + r * 0.045f, vy + vh * 0.75f * amp)
                    canvas.drawRoundRect(rect, r * 0.045f, r * 0.045f, light)
                }
            }
        }
        // Soft glow under the visor light.
        halo.color = c; halo.alpha = 34
        rect.set(-vw, vy - vh * 1.6f, vw, vy + vh * 1.6f)
        canvas.drawRoundRect(rect, vh * 1.6f, vh * 1.6f, halo)
        canvas.restore()
    }

    private fun pill(canvas: Canvas, left: Float, cy: Float, right: Float, half: Float) {
        rect.set(left, cy - half, right, cy + half)
        canvas.drawRoundRect(rect, half, half, light)
    }
}

/** Self-animating mascot view (bubble). */
open class MascotView(context: Context) : View(context) {
    var mood: Mood = Mood.IDLE
        set(value) { field = value; invalidate() }
    var lookX = 0f
    var lookY = 0f
    /** Small status dot shown while Chron is listening for its name. */
    var earOn = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Microphone level 0..1: draws a soft ring that pulses with your voice while Chron listens. */
    var level = 0f
    private var shownLevel = 0f
    private val voiceRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = MascotColors.ACCENT_SOFT }
    private val ear = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.SUCCESS }
    private val earRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.HEAD_BOTTOM }
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
            // ~40 fps is plenty for a status mark and saves battery; skip work when not visible.
            if (isShown && now - lastFrame >= 24_000_000L) { lastFrame = now; invalidate() }
        }
    }

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) * 0.36f
        val bob = 0f
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
            val pulse = 1f
            canvas.drawCircle(ex, ey, r * 0.17f * pulse, earRing)
            canvas.drawCircle(ex, ey, r * 0.12f * pulse, ear)
        }
    }
}

/**
 * The on-screen agent cursor. Its hotspot is the view centre: a thin target reticle is drawn there
 * and a small agent mark sits just above-right of it (its arc shows when the agent is thinking).
 */
class CursorBuddyView(context: Context) : MascotView(context) {
    var showReticle = false
    private var tapPulse = -1f
    private val reticle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = MascotColors.ACCENT; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MascotColors.ACCENT }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }

    fun pulse() {
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 520; addUpdateListener { tapPulse = it.animatedValue as Float; invalidate() }; doOnEndCompat { tapPulse = -1f }; start() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val unit = min(width, height) / 2f
        if (showReticle) {
            val rr = unit * 0.22f
            ring.strokeWidth = unit * 0.07f; ring.alpha = 140
            canvas.drawCircle(cx, cy, rr, ring)
            reticle.strokeWidth = unit * 0.04f
            reticle.alpha = 230
            canvas.drawCircle(cx, cy, rr, reticle)
            canvas.drawCircle(cx, cy, unit * 0.07f, dot)
        }
        if (tapPulse >= 0f) {
            reticle.strokeWidth = unit * 0.08f * (1f - tapPulse)
            reticle.alpha = ((1f - tapPulse) * 255).toInt()
            canvas.drawCircle(cx, cy, unit * (0.2f + tapPulse * 0.75f), reticle)
            reticle.alpha = 255
        }
        val r = unit * 0.26f
        val mx = cx + unit * 0.5f
        val my = cy - unit * 0.5f
        MascotPainter.draw(canvas, mx, my, r, mood, clock, 0f, lookX - 0.6f, lookY + 0.6f)
    }
}

internal fun ValueAnimator.doOnEndCompat(block: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })
}
