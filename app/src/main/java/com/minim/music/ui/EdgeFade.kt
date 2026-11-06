package com.minim.music.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.minim.music.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 5.7.0 u49: lists no longer get sliced off by an invisible line under the header. Their content
 * fades out into whatever is behind it (the app colour from the cover, Dark mode, a festival
 * wallpaper) as it scrolls up to the header, and melts away behind the floating mini player at
 * the bottom, so the pill really floats.
 *
 * It uses Android's own fading edges, which erase the list's content to see-through instead of
 * painting a colour, so the fade always matches the background without being recoloured. The
 * fades only grow in as the list scrolls away from its first or last item, so at rest at either
 * end nothing sits under a fade.
 */
object EdgeFadeMath {
    /** From solid background at the header's bottom edge to fully see-through content. */
    const val TOP_DP = 28f
    /** The bottom edge when no floating mini player is showing. */
    const val EDGE_BOTTOM_DP = 28f
    /** How far above the floating pill the content starts to fade. */
    const val ABOVE_PILL_DP = 28f

    /**
     * The bottom fade: [lengthPx] tall, ending [clearPx] above the list's bottom edge. Below that
     * the content is fully faded out (it is behind or under the pill).
     */
    data class Bottom(val lengthPx: Int, val clearPx: Int)

    /**
     * The bottom fade for a list [listHeight] tall. [pillTopFromBottom] and [pillBottomFromBottom]
     * are the floating pill's edges measured up from the list's bottom edge, or null when no pill
     * floats over it. With a pill the content is gone by the pill's middle and starts fading
     * [ABOVE_PILL_DP] above its top.
     */
    fun bottom(listHeight: Int, pillTopFromBottom: Int?, pillBottomFromBottom: Int?, density: Float): Bottom {
        val edge = Bottom((EDGE_BOTTOM_DP * density).roundToInt(), 0)
        if (pillTopFromBottom == null || pillBottomFromBottom == null) return edge
        if (pillBottomFromBottom < 0 || pillTopFromBottom <= pillBottomFromBottom) return edge
        // A pill that isn't in the lower part of the list isn't floating over its bottom
        if (listHeight <= 0 || pillTopFromBottom > listHeight / 2) return edge
        val clear = (pillTopFromBottom + pillBottomFromBottom) / 2
        val length = pillTopFromBottom - clear + (ABOVE_PILL_DP * density).roundToInt()
        return Bottom(length, clear)
    }

    /**
     * The strength Android's fading edge wants, when the view's fading edge length is [maxLengthPx]
     * and this edge should be [lengthPx] long. [distancePx] is how far the content runs past this
     * edge: 0 at rest at the first (or last) item, so no fade, growing to the full fade over
     * [lengthPx] of scrolling. A negative distance means far away (the end isn't laid out).
     */
    fun strength(distancePx: Int, lengthPx: Int, maxLengthPx: Int): Float {
        if (maxLengthPx <= 0 || lengthPx <= 0) return 0f
        val d = if (distancePx < 0) lengthPx else min(distancePx, lengthPx)
        return (d.toFloat() / maxLengthPx).coerceIn(0f, 1f)
    }
}

/** The shared state of one fading list: lengths, the pill it watches, and its listener. */
class EdgeFade(private val view: View, attrs: AttributeSet?) {
    private val density = view.resources.displayMetrics.density
    private val aroundPill: Boolean
    val topLengthPx = (EdgeFadeMath.TOP_DP * density).roundToInt()
    var bottom = EdgeFadeMath.bottom(0, null, null, density)
        private set
    private var pill: View? = null

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refresh() }

    init {
        val a = view.context.obtainStyledAttributes(attrs, R.styleable.EdgeFadeList)
        aroundPill = a.getBoolean(R.styleable.EdgeFadeList_fadeAroundPill, true)
        a.recycle()
        view.isVerticalFadingEdgeEnabled = true
        applyLength()
    }

    val maxLengthPx: Int get() = max(topLengthPx, bottom.lengthPx)

    fun topStrength(distancePx: Int) = EdgeFadeMath.strength(distancePx, topLengthPx, maxLengthPx)
    fun bottomStrength(distancePx: Int) = EdgeFadeMath.strength(distancePx, bottom.lengthPx, maxLengthPx)

    fun onAttached() {
        if (aroundPill && pill == null) pill = view.rootView.findViewById(R.id.mini_player)
        view.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        refresh()
    }

    fun onDetached() {
        view.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }

    /** Works out the bottom fade again after a layout (the pill came or went, the list resized). */
    fun refresh() {
        val p = pill?.takeIf { aroundPill && it.isShown && it.height > 0 }
        var pillTop: Int? = null
        var pillBottom: Int? = null
        if (p != null && view.height > 0) {
            // Layout positions, so a pill popping in (scale, alpha) or a screen sliding doesn't count
            val listBottom = layoutTop(view) + view.height
            val top = layoutTop(p)
            pillTop = listBottom - top
            pillBottom = listBottom - (top + p.height)
        }
        val next = EdgeFadeMath.bottom(view.height, pillTop, pillBottom, density)
        if (next != bottom) {
            bottom = next
            applyLength()
            view.invalidate()
        }
    }

    private fun applyLength() {
        view.setFadingEdgeLength(maxLengthPx)
    }

    /** Draws [body] with everything under the bottom fade's end cut away (behind the pill). */
    inline fun drawClipped(canvas: Canvas, bottomFading: Boolean, body: () -> Unit) {
        val clear = bottom.clearPx
        if (!bottomFading || clear <= 0) {
            body()
            return
        }
        val save = canvas.save()
        canvas.clipRect(
            viewScrollX(), viewScrollY(),
            viewScrollX() + viewWidth(), viewScrollY() + viewHeight() - clear
        )
        body()
        canvas.restoreToCount(save)
    }

    fun viewScrollX() = view.scrollX
    fun viewScrollY() = view.scrollY
    fun viewWidth() = view.width
    fun viewHeight() = view.height

    private fun layoutTop(v: View): Int {
        var y = 0
        var cur: View? = v
        while (cur != null) {
            y += cur.top
            val parent = cur.parent as? View ?: break
            y -= parent.scrollY
            cur = parent
        }
        return y
    }
}

/** A RecyclerView whose top and bottom fade out instead of being sliced off. */
open class FadingRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    val edgeFade = EdgeFade(this, attrs)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        edgeFade.onAttached()
    }

    override fun onDetachedFromWindow() {
        edgeFade.onDetached()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        edgeFade.refresh()
    }

    // The fades cover the whole view, padding included (the lists draw into it), not just the
    // inside of the padding as Android would by default.
    override fun isPaddingOffsetRequired() = true
    override fun getTopPaddingOffset() = -paddingTop
    override fun getLeftPaddingOffset() = -paddingLeft
    override fun getRightPaddingOffset() = paddingRight
    override fun getBottomPaddingOffset() = paddingBottom - edgeFade.bottom.clearPx

    override fun getTopFadingEdgeStrength(): Float = edgeFade.topStrength(topDistance())
    override fun getBottomFadingEdgeStrength(): Float = edgeFade.bottomStrength(bottomDistance())

    override fun draw(c: Canvas) {
        edgeFade.drawClipped(c, canScrollVertically(1)) { super.draw(c) }
    }

    /** How far the content has scrolled up past the top padding, or -1 when the first item is far away. */
    internal fun topDistance(): Int {
        if (!canScrollVertically(-1)) return 0
        val lm = layoutManager ?: return -1
        var hasFirst = false
        var minTop = Int.MAX_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (getChildAdapterPosition(child) == 0) hasFirst = true
            val margin = (child.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            minTop = min(minTop, lm.getDecoratedTop(child) - margin)
        }
        if (!hasFirst || minTop == Int.MAX_VALUE) return -1
        return max(0, paddingTop - minTop)
    }

    /** How far the content still runs below the bottom padding, or -1 when the last item is far away. */
    internal fun bottomDistance(): Int {
        if (!canScrollVertically(1)) return 0
        val lm = layoutManager ?: return -1
        val last = (adapter?.itemCount ?: 0) - 1
        if (last < 0) return 0
        var hasLast = false
        var maxBottom = Int.MIN_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (getChildAdapterPosition(child) == last) hasLast = true
            val margin = (child.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            maxBottom = max(maxBottom, lm.getDecoratedBottom(child) + margin)
        }
        if (!hasLast || maxBottom == Int.MIN_VALUE) return -1
        return max(0, maxBottom - (height - paddingBottom))
    }
}

/** The Stats tab's scroll view, with the same fades as the lists. */
open class FadingNestedScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : NestedScrollView(context, attrs, defStyle) {

    val edgeFade = EdgeFade(this, attrs)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        edgeFade.onAttached()
    }

    override fun onDetachedFromWindow() {
        edgeFade.onDetached()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        edgeFade.refresh()
    }

    override fun isPaddingOffsetRequired() = true
    override fun getTopPaddingOffset() = -paddingTop
    override fun getLeftPaddingOffset() = -paddingLeft
    override fun getRightPaddingOffset() = paddingRight
    override fun getBottomPaddingOffset() = paddingBottom - edgeFade.bottom.clearPx

    override fun getTopFadingEdgeStrength(): Float = edgeFade.topStrength(scrollY)
    override fun getBottomFadingEdgeStrength(): Float = edgeFade.bottomStrength(bottomDistance())

    override fun draw(canvas: Canvas) {
        edgeFade.drawClipped(canvas, bottomDistance() > 0) { super.draw(canvas) }
    }

    internal fun bottomDistance(): Int {
        val child = getChildAt(0) ?: return 0
        val margin = (child.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        return max(0, child.bottom + margin - scrollY - (height - paddingBottom))
    }
}
