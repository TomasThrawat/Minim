package com.minim.music.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 5.6.0: one row of the full screen lyrics: the original line and, under it, its English
 * translation ([TranslationLineView]). With translations off the second view has no height, so the
 * row looks and measures exactly like the line on its own did before.
 */
@SuppressLint("ViewConstructor")
class LyricRow(context: Context, val line: LyricLineView, val translation: TranslationLineView) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(translation, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}

/**
 * 5.6.0: the English translation under a lyric line, in smaller, dimmer Poppins.
 *
 * Its height is what opens the space between lines: [reveal] (0 to 1, the same for every row)
 * grows it from nothing, so turning translations on or off slides the lines apart or together.
 * While Gemini works it shows a soft shimmer bar ([shimmer]); when the translation arrives,
 * [fade] (0 to 1, per row, started top to bottom) crossfades the bar into the text and eases the
 * height from the bar's to the text's. A line with no translation fades its bar away and closes.
 */
@SuppressLint("ViewConstructor")
class TranslationLineView(context: Context, typeface: Typeface?) : View(context) {

    private val dm = resources.displayMetrics
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, dm)
        this.typeface = typeface
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private var layout: StaticLayout? = null
    private var layoutWidth = -1

    /** The translation, or null for none. */
    var text: String? = null
        private set
    /** A shimmer bar shows (or, once [fade] runs, fades out) while Gemini works on this line. */
    var shimmer = false
        private set
    /** 0 = bar (or nothing), 1 = translation (or nothing). */
    var fade = 1f
        private set
    /** How far the space under the line is open, 0 to 1. */
    var reveal = 0f
        private set
    /** Text colour. The row's brightness comes in as this view's own alpha, like the line's. */
    var ink: Int = 0xFFFFFFFF.toInt()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    /** Bar width as a share of the space, so the bars don't all look the same. */
    var barShare = 0.6f

    fun bind(text: String?, shimmer: Boolean, fade: Float, reveal: Float) {
        val newText = text?.takeIf { it.isNotBlank() }
        val relayout = newText != this.text || shimmer != this.shimmer || fade != this.fade || reveal != this.reveal
        if (newText != this.text) layout = null
        this.text = newText
        this.shimmer = shimmer
        this.fade = fade.coerceIn(0f, 1f)
        this.reveal = reveal.coerceIn(0f, 1f)
        if (relayout) {
            requestLayout()
            invalidate()
        }
    }

    fun setReveal(r: Float) {
        val v = r.coerceIn(0f, 1f)
        if (v == reveal) return
        reveal = v
        requestLayout()
        invalidate()
    }

    fun setFade(f: Float) {
        val v = f.coerceIn(0f, 1f)
        if (v == fade) return
        fade = v
        requestLayout()
        invalidate()
    }

    private fun dp(v: Float) = v * dm.density

    private fun textLayout(width: Int): StaticLayout? {
        val t = text ?: return null
        if (width <= 0) return null
        layout?.takeIf { layoutWidth == width }?.let { return it }
        val b = StaticLayout.Builder.obtain(t, 0, t.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        // Even line lengths when a translation wraps under a centred line
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            b.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED)
        }
        val l = b.build()
        layout = l
        layoutWidth = width
        return l
    }

    /** One line of translation text: the shimmer bar takes this much room. */
    private fun oneLineHeight(): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) * LINE_SPACING
    }

    private fun block(h: Float): Float = if (h > 0f) h + dp(GAP_DP) else 0f

    /** The height before [fade] is applied. */
    private fun fromHeight(): Float = if (shimmer) block(oneLineHeight()) else 0f

    private fun toHeight(width: Int): Float = textLayout(width)?.let { block(it.height.toFloat()) } ?: 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (reveal <= 0f) 0f else {
            val from = fromHeight()
            val to = toHeight(w)
            reveal * (from + (to - from) * fade)
        }
        setMeasuredDimension(w, h.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (reveal <= 0f || height <= 0) return
        val baseAlpha = (ink ushr 24) / 255f
        canvas.save()
        canvas.clipRect(0f, -dp(PULL_UP_DP), width.toFloat(), height.toFloat())
        // Pulled a little closer to its line than to the next one
        canvas.translate(0f, -dp(PULL_UP_DP) * reveal)
        if (shimmer && fade < 1f) drawShimmer(canvas, baseAlpha * (1f - fade) * reveal)
        val l = textLayout(width)
        if (l != null && fade > 0f) {
            paint.color = ink
            paint.alpha = (255 * baseAlpha * DIM * fade * reveal).roundToInt().coerceIn(0, 255)
            l.draw(canvas)
        }
        canvas.restore()
    }

    private fun drawShimmer(canvas: Canvas, a: Float) {
        if (a <= 0f) return
        val lineH = oneLineHeight()
        val barH = dp(BAR_DP)
        val w = width * barShare.coerceIn(0.3f, 0.9f)
        val left = (width - w) / 2f
        val top = (lineH - barH) / 2f
        bar.set(left, top, left + w, top + barH)
        val now = SystemClock.uptimeMillis()
        val phase = (now % SHIMMER_PERIOD_MS) / SHIMMER_PERIOD_MS.toFloat()
        val sweep = width * 0.6f
        val x = -sweep + (width + 2 * sweep) * phase
        val base = withAlpha(ink, BAR_ALPHA * a)
        val glow = withAlpha(ink, BAR_GLOW_ALPHA * a)
        barPaint.shader = LinearGradient(x - sweep / 2f, 0f, x + sweep / 2f, 0f, intArrayOf(base, glow, base), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(bar, barH / 2f, barH / 2f, barPaint)
        postInvalidateOnAnimation()
    }

    private fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((255 * a).roundToInt().coerceIn(0, 255) shl 24)

    companion object {
        /** The line above is 27 sp in Poppins SemiBold. */
        const val TEXT_SP = 17f
        const val LINE_SPACING = 1.1f
        /** Translation text is this bright compared to its line. */
        const val DIM = 0.7f
        const val GAP_DP = 10f
        const val PULL_UP_DP = 4f
        const val BAR_DP = 9f
        const val BAR_ALPHA = 0.16f
        const val BAR_GLOW_ALPHA = 0.42f
        const val SHIMMER_PERIOD_MS = 1300L

        /** A steady, different-looking bar width per line (between 42% and 78%). */
        fun barShareFor(position: Int, textLength: Int): Float {
            val byText = (0.3f + textLength / 60f).coerceIn(0.42f, 0.78f)
            val wobble = ((position * 37) % 11 - 5) / 100f
            return max(0.42f, (byText + wobble).coerceAtMost(0.78f))
        }
    }
}
