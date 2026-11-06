package com.minim.music.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.7.0 u48: the mini player's fill sweep. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PillSweepTest {

    @Test
    fun liveFillMovesWithTheClockOnlyWhilePlaying() {
        // 3-minute song, last told 60 s, 1.5 s ago
        val playing = PillSweepMath.live(60_000, 1_000, 2_500, 180_000, playing = true, speed = 1f)
        assertEquals(61_500f / 180_000f, playing, 1e-5f)
        val paused = PillSweepMath.live(60_000, 1_000, 2_500, 180_000, playing = false, speed = 1f)
        assertEquals(60_000f / 180_000f, paused, 1e-5f)
        // a tape at 2x moves twice as fast
        val fast = PillSweepMath.live(60_000, 1_000, 2_000, 180_000, playing = true, speed = 2f)
        assertEquals(62_000f / 180_000f, fast, 1e-5f)
        // never past the end, nothing for an unknown length
        assertEquals(1f, PillSweepMath.live(179_900, 0, 10_000, 180_000, true, 1f), 0f)
        assertEquals(0f, PillSweepMath.live(5_000, 0, 1_000, 0, true, 1f), 0f)
    }

    @Test
    fun redrawsAreBookedPerPixelAndStopWhenPaused() {
        // 3-minute song on a 300 px pill: 600 ms a pixel, at most 4 s, never faster than a frame
        val step = PillSweepMath.nextStepMs(0.5f, 300f, 180_000, playing = true, speed = 1f)
        assertTrue("step $step", step in 16L..600L)
        // an hour-long tape: one redraw every few seconds (capped at 4 s)
        assertEquals(4_000L, PillSweepMath.nextStepMs(0.1f, 300f, 3_600_000, true, 1f))
        // paused, unknown length or finished: nothing booked
        assertEquals(-1L, PillSweepMath.nextStepMs(0.5f, 300f, 180_000, false, 1f))
        assertEquals(-1L, PillSweepMath.nextStepMs(0.5f, 300f, 0, true, 1f))
        assertEquals(-1L, PillSweepMath.nextStepMs(1f, 300f, 180_000, true, 1f))
    }

    @Test
    fun slidesOnNewTrackOrJumpButNotOnDrift() {
        assertTrue(PillSweepMath.shouldSlide(sameTrack = false, shown = 0.6f, target = 0f, widthPx = 300f))
        assertTrue(PillSweepMath.shouldSlide(true, 0.2f, 0.7f, 300f))
        assertTrue(!PillSweepMath.shouldSlide(true, 0.500f, 0.505f, 300f))
        assertEquals(0f, PillSweepMath.ease(0f), 0f)
        assertEquals(1f, PillSweepMath.ease(1f), 0f)
        assertTrue(PillSweepMath.ease(0.5f) > 0.5f)
    }

    @Test
    fun sweepIsASubtleShadeOfThePillAndKeepsTextReadable() {
        // a dark pill (light mode) gets a touch lighter; a pale pill (dark mode) a touch deeper
        val darkPill = Color.rgb(0x22, 0x3A, 0x2E)
        val lightPill = Color.rgb(0xEC, 0xF2, 0xEE)
        val d = PillSweepMath.sweepColor(darkPill)
        val l = PillSweepMath.sweepColor(lightPill)
        assertTrue(ColorUtils.calculateLuminance(d) > ColorUtils.calculateLuminance(darkPill))
        assertTrue(ColorUtils.calculateLuminance(l) < ColorUtils.calculateLuminance(lightPill))
        // the pill's text (the page colour) stays readable over both parts
        val textOnDark = lightPill
        val textOnLight = darkPill
        assertTrue(ColorUtils.calculateContrast(textOnDark, darkPill) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(textOnDark, d) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(textOnLight, lightPill) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(textOnLight, l) >= 4.5)
        // same hue family, small step
        assertNotEquals(darkPill, d)
    }

    @Test
    fun drawableTakesThePaintersTintAndGlidesBackToZeroOnSkip() {
        var now = 10_000L
        val dr = PillSweepDrawable(animationsOn = { true }, clock = { now })
        dr.setBounds(0, 0, 300, 54)
        dr.setVisible(true, false)
        dr.setTintList(ColorStateList.valueOf(Color.rgb(0x20, 0x30, 0x40)))
        assertEquals(Color.rgb(0x20, 0x30, 0x40), dr.colors().first)
        assertNotEquals(dr.colors().first, dr.colors().second)

        dr.setPlayback("a", 90_000, 180_000, isPlaying = true, playbackSpeed = 1f)
        assertEquals(0.5f, dr.shownFraction(), 1e-4f)
        now += 1_800
        assertEquals(91_800f / 180_000f, dr.shownFraction(), 1e-4f)

        // skip: it starts from where it was and slides back to the new song's start
        val before = dr.shownFraction()
        dr.setPlayback("b", 0, 200_000, isPlaying = true, playbackSpeed = 1f)
        assertEquals(before, dr.shownFraction(), 1e-4f)
        now += PillSweepMath.SLIDE_MS / 2
        val mid = dr.shownFraction()
        assertTrue("mid $mid", mid > 0f && mid < before)
        now += PillSweepMath.SLIDE_MS
        assertEquals(PillSweepMath.live(0, now - PillSweepMath.SLIDE_MS * 3 / 2, now, 200_000, true, 1f), dr.shownFraction(), 1e-3f)

        // draws without trouble, and the sweep's colour is on the left only
        val bmp = Bitmap.createBitmap(300, 54, Bitmap.Config.ARGB_8888)
        dr.draw(Canvas(bmp))
        dr.clear()
        assertEquals(0f, dr.shownFraction(), 0f)
    }

    @Test
    fun noGlideWithAnimationsOff() {
        var now = 0L
        val dr = PillSweepDrawable(animationsOn = { false }, clock = { now })
        dr.setBounds(0, 0, 300, 54)
        dr.setVisible(true, false)
        dr.setPlayback("a", 90_000, 180_000, isPlaying = false, playbackSpeed = 1f)
        dr.setPlayback("b", 0, 180_000, isPlaying = false, playbackSpeed = 1f)
        assertEquals(0f, dr.shownFraction(), 0f)
    }
}
