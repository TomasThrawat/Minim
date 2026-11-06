package com.minim.music.ui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.floor

/*
 * 5.7.0 u48: the mini player's "fill sweep". The pill itself slowly fills from the left with a
 * slightly lighter shade of its own colour as the song (or tape) plays. No bar, no extra views:
 * it is the pill's background.
 *
 * Battery: nothing ticks on its own. Between the player's once-a-second position updates the fill
 * is worked out from the clock, and the next redraw is booked for the moment the fill reaches the
 * next whole pixel (about 4 a second for a 3-minute song on a phone-wide pill, one every few
 * seconds for an hour-long tape). Redraws only happen while the pill is actually drawn: a hidden
 * pill books nothing.
 */

/** The pure maths, unit tested. */
object PillSweepMath {

    /** A new track, or a jump (seek, repeat), glides to the new place over this long. */
    const val SLIDE_MS = 420L

    /** A correction of more than this many pixels glides; smaller ones (clock drift) are invisible. */
    const val JUMP_PX = 3f

    /** Never redraw faster than a frame, nor wait longer than this while playing. */
    const val MIN_STEP_MS = 16L
    const val MAX_STEP_MS = 4_000L

    /** How much the sweep's lightness differs from the pill's (HSL lightness, 0..1). */
    const val LIGHTER = 0.085f
    const val DEEPER = 0.07f

    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * Where the fill is at [nowMs]: the last reported position, moved on by the time since (at the
     * playback speed) while playing. Stops at the end; never moves while paused or loading.
     */
    fun live(basePosMs: Long, baseAtMs: Long, nowMs: Long, durationMs: Long, playing: Boolean, speed: Float): Float {
        if (durationMs <= 0L) return 0f
        val moved = if (playing) ((nowMs - baseAtMs).coerceAtLeast(0L) * speed.coerceIn(0.25f, 4f)).toLong() else 0L
        return fraction(basePosMs + moved, durationMs)
    }

    /** Slows into place (decelerate), so a reset to zero slides back instead of snapping. */
    fun ease(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        val inv = 1f - c
        return 1f - inv * inv * inv
    }

    /** Glide when the track changed, or the fill would jump by more than [JUMP_PX]. */
    fun shouldSlide(sameTrack: Boolean, shown: Float, target: Float, widthPx: Float): Boolean =
        !sameTrack || abs(target - shown) * widthPx > JUMP_PX

    /**
     * Milliseconds until the fill edge reaches the next whole pixel at this speed, kept between a
     * frame and [MAX_STEP_MS]. -1 when it isn't moving (paused, unknown length, at the end).
     */
    fun nextStepMs(fraction: Float, widthPx: Float, durationMs: Long, playing: Boolean, speed: Float): Long {
        if (!playing || durationMs <= 0L || widthPx <= 0f || fraction >= 1f) return -1L
        val px = fraction * widthPx
        val toNext = (floor(px) + 1f - px).coerceAtLeast(0.01f)
        val msPerPx = durationMs / widthPx / speed.coerceIn(0.25f, 4f)
        return (toNext * msPerPx).toLong().coerceIn(MIN_STEP_MS, MAX_STEP_MS)
    }

    /**
     * The sweep's colour from the pill's: the same hue, a touch lighter on a dark pill. A pale pill
     * (dark mode) can't get noticeably lighter, so there it goes a touch deeper instead. Small on
     * purpose: the pill's text keeps nearly all of its contrast over both parts.
     */
    fun sweepColor(base: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(base, hsl)
        val dark = ColorUtils.calculateLuminance(base) < 0.4
        hsl[2] = if (dark) (hsl[2] + LIGHTER).coerceAtMost(1f) else (hsl[2] - DEEPER).coerceAtLeast(0f)
        return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), Color.alpha(base))
    }
}

/**
 * The mini player's background: the pill in its palette colour (it takes the view's
 * backgroundTint, so the Painter, theme fades and the Now Playing morph colour it as before) with
 * the sweep drawn inside the same rounded shape.
 */
class PillSweepDrawable(
    private val animationsOn: () -> Boolean = { true },
    private val clock: () -> Long = { SystemClock.uptimeMillis() }
) : Drawable(), Runnable {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var tint: ColorStateList? = null
    private var baseColor = Color.BLACK

    private var trackId: String? = null
    private var hasTrack = false
    private var basePos = 0L
    private var baseAt = 0L
    private var durationMs = 0L
    private var playing = false
    private var speed = 1f

    private var slideFrom = 0f
    private var slideStart = -1L

    init {
        applyColor(Color.BLACK)
    }

    /** Feeds the player's state; cheap to call on every update. */
    fun setPlayback(id: String?, positionMs: Long, durationMs: Long, isPlaying: Boolean, playbackSpeed: Float) {
        val now = clock()
        val width = bounds.width().toFloat()
        val shown = shownAt(now)
        val midSlide = slideStart >= 0L && now - slideStart < PillSweepMath.SLIDE_MS
        val sameTrack = !hasTrack || id == trackId
        val wasFirst = !hasTrack
        trackId = id
        hasTrack = true
        basePos = positionMs.coerceAtLeast(0L)
        baseAt = now
        this.durationMs = durationMs
        playing = isPlaying
        speed = playbackSpeed
        val target = shownTarget(now)
        if (midSlide && sameTrack && animationsOn()) {
            // A glide already under way just heads for the new place: restarting it on every
            // update would keep it from ever settling
        } else if (!wasFirst && isVisible && width > 0f && animationsOn() &&
            PillSweepMath.shouldSlide(sameTrack, shown, target, width)
        ) {
            slideFrom = shown
            slideStart = now
        } else if (!sameTrack || !animationsOn()) {
            slideStart = -1L
        }
        invalidateSelf()
    }

    /** Nothing playing: the pill is plain again. */
    fun clear() {
        hasTrack = false
        trackId = null
        basePos = 0L
        durationMs = 0L
        playing = false
        slideStart = -1L
        unscheduleSelf(this)
        invalidateSelf()
    }

    /** What the fill shows now (0..1), for tests and the morph. */
    fun shownFraction(): Float = shownAt(clock())

    private fun shownTarget(now: Long): Float =
        PillSweepMath.live(basePos, baseAt, now, durationMs, playing, speed)

    private fun shownAt(now: Long): Float {
        val target = shownTarget(now)
        if (slideStart < 0L) return target
        val t = (now - slideStart).toFloat() / PillSweepMath.SLIDE_MS
        if (t >= 1f) return target
        return slideFrom + (target - slideFrom) * PillSweepMath.ease(t)
    }

    private val sliding: Boolean get() = slideStart >= 0L && clock() - slideStart < PillSweepMath.SLIDE_MS

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val r = b.height() / 2f
        canvas.drawRoundRect(rect, r, r, basePaint)
        val now = clock()
        val f = shownAt(now)
        if (slideStart >= 0L && now - slideStart >= PillSweepMath.SLIDE_MS) slideStart = -1L
        val edge = b.left + f * b.width()
        if (edge - b.left >= 0.5f) {
            val save = canvas.save()
            canvas.clipRect(b.left.toFloat(), b.top.toFloat(), edge, b.bottom.toFloat())
            canvas.drawRoundRect(rect, r, r, sweepPaint)
            canvas.restoreToCount(save)
        }
        // Book the next redraw only from a real draw: a hidden pill books nothing
        unscheduleSelf(this)
        val next = if (sliding) PillSweepMath.MIN_STEP_MS
        else PillSweepMath.nextStepMs(f, b.width().toFloat(), durationMs, playing, speed)
        if (next > 0L && isVisible) scheduleSelf(this, SystemClock.uptimeMillis() + next)
    }

    override fun run() = invalidateSelf()

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) unscheduleSelf(this) else if (changed) invalidateSelf()
        return changed
    }

    private fun applyColor(c: Int) {
        baseColor = c
        basePaint.color = c
        sweepPaint.color = PillSweepMath.sweepColor(c)
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        updateTint()
    }

    override fun setTint(tintColor: Int) = setTintList(ColorStateList.valueOf(tintColor))

    override fun isStateful(): Boolean = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean = updateTint()

    private fun updateTint(): Boolean {
        val c = tint?.getColorForState(state, tint!!.defaultColor) ?: Color.WHITE
        if (c == baseColor) return false
        applyColor(c)
        invalidateSelf()
        return true
    }

    /** The pill colour and the sweep colour now (tests). */
    fun colors(): Pair<Int, Int> = basePaint.color to sweepPaint.color

    override fun getOutline(outline: Outline) {
        val b = bounds
        outline.setRoundRect(b, b.height() / 2f)
        outline.alpha = Color.alpha(baseColor) / 255f
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        sweepPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        sweepPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
