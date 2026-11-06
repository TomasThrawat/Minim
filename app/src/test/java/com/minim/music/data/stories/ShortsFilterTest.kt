package com.minim.music.data.stories

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortsFilterTest {

    private val watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test
    fun shortFlagFromNewPipeIsAShortEvenWhenLong() {
        assertTrue(ShortsFilter.isShort(watch, 170, shortFlag = true))
        assertFalse(ShortsFilter.keep(watch, 170, shortFlag = true, isLive = false))
    }

    @Test
    fun shortsLinkIsAShort() {
        assertTrue(ShortsFilter.isShort("https://www.youtube.com/shorts/abcdefghijk", 90, false))
        assertTrue(ShortsFilter.isShort("https://m.youtube.com/SHORTS/abcdefghijk", 0, false))
    }

    @Test
    fun sixtySecondsOrLessIsAShortAsAFallback() {
        assertTrue(ShortsFilter.isShort(watch, 60, false))
        assertTrue(ShortsFilter.isShort(watch, 1, false))
        assertFalse(ShortsFilter.isShort(watch, 61, false))
        assertTrue(ShortsFilter.keep(watch, 61, false, false))
    }

    @Test
    fun normalUploadIsKept() {
        assertFalse(ShortsFilter.isShort(watch, 24 * 60, false))
        assertTrue(ShortsFilter.keep(watch, 24 * 60, false, false))
        assertTrue(ShortsFilter.keep(null, 3 * 3600, false, false))
    }

    @Test
    fun liveAndUnknownLengthStayOut() {
        assertFalse(ShortsFilter.keep(watch, 3600, false, isLive = true))
        assertFalse(ShortsFilter.keep(watch, 0, false, false))
        assertFalse(ShortsFilter.keep(watch, -1, false, false))
    }
}
