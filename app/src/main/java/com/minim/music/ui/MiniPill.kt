package com.minim.music.ui

import kotlin.math.roundToInt

/**
 * 5.7.0 u45: how wide the mini player pills may get. Both the Home pill and the floating one (every
 * other screen, music and stories alike) keep [SIDE_MARGIN_DP] of clear space from each screen edge,
 * so a long title scrolls inside the pill instead of stretching it to the edges.
 * The floating pill gets the same space as layout margins in activity_main.xml.
 */
object MiniPill {

    /** Clear space between a mini player pill and each side of the screen. */
    const val SIDE_MARGIN_DP = 40f

    /** The widest a pill may be on a screen [screenPx] wide. */
    fun maxWidth(screenPx: Int, density: Float): Int =
        (screenPx - 2 * SIDE_MARGIN_DP * density).roundToInt().coerceAtLeast(0)

    /**
     * The Home pill's width for content [contentPx] wide: at least [minDp] (so short titles don't look
     * tiny, unless the screen can't fit that), at most [maxWidth].
     */
    fun homeWidth(contentPx: Float, screenPx: Int, density: Float, minDp: Float): Int {
        val maxW = maxWidth(screenPx, density).toFloat()
        val minW = minOf(minDp * density, maxW)
        return contentPx.coerceIn(minW, maxW).roundToInt()
    }
}
