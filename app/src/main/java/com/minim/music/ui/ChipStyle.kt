package com.minim.music.ui

import android.graphics.Color
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** 5.7.0 u47: the tapes chips' look, shared with Liked/Offline's filter and the channel toggle. */
object ChipStyle {
    fun apply(chip: TextView, on: Boolean, pal: Pal) {
        val d = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 999f
            val stroke = (1.5f * chip.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            if (on) setColor(pal.fg) else {
                setColor(Color.TRANSPARENT)
                setStroke(stroke, ColorUtils.setAlphaComponent(pal.fg, 0x66))
            }
        }
        chip.background = d
        chip.setTextColor(if (on) pal.bg else pal.fg)
        chip.isSelected = on
    }
}
