package com.minim.music.ui

import android.widget.TextView
import androidx.core.content.ContextCompat
import com.minim.music.R

/**
 * 5.7.0 u47: a small tick in front of a tape's second line once it's played (to the end, or past
 * 90%). Drawn in the line's own colour, so it follows the palette and the dimming.
 */
object PlayedTick {
    fun apply(tv: TextView, played: Boolean) {
        if (!played) {
            val d = tv.compoundDrawablesRelative
            if (d[0] != null) tv.setCompoundDrawablesRelative(null, d[1], d[2], d[3])
            return
        }
        val icon = ContextCompat.getDrawable(tv.context, R.drawable.ic_check)?.mutate() ?: return
        val size = (tv.textSize * 1.05f).toInt().coerceAtLeast(1)
        icon.setBounds(0, 0, size, size)
        icon.setTint(tv.currentTextColor)
        tv.compoundDrawablePadding = (3 * tv.resources.displayMetrics.density).toInt()
        val d = tv.compoundDrawablesRelative
        tv.setCompoundDrawablesRelative(icon, d[1], d[2], d[3])
    }
}
