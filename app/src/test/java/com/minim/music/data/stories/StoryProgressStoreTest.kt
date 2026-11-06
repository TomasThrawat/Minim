package com.minim.music.data.stories

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.7.0 u46: resume positions are saved on the phone and survive a restart (and updates). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoryProgressStoreTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun savedPosition_survivesARestart_andResumesFromIt() {
        val store = StoryProgressStore(ctx)
        assertNull(store.get("vid1"))
        // Becoming half-finished changes the tab (Continue listening); a later tick doesn't
        assertTrue(store.record("vid1", 10 * 60_000L, 40 * 60_000L, ended = false, now = 1L))
        assertFalse(store.record("vid1", 11 * 60_000L, 40 * 60_000L, ended = false, now = 2L))
        val reopened = StoryProgressStore(ctx)
        assertEquals(11 * 60_000L, reopened.get("vid1")!!.positionMs)
        assertEquals(11 * 60_000L - StoryProgress.REWIND_MS, StoryProgress.resumeMs(reopened.get("vid1")))
        // Played to the end: heard (dims, leaves Continue listening), still after a restart
        assertTrue(reopened.record("vid1", 40 * 60_000L, 40 * 60_000L, ended = true, now = 3L))
        assertTrue(StoryProgressStore(ctx).get("vid1")!!.done)
    }

    @Test
    fun encodeDecode_roundTrips_andBadDataIsEmpty() {
        val m = mapOf(
            "a" to StoryPlayback(61_000L, 600_000L, false, 3L),
            "b" to StoryPlayback(600_000L, 600_000L, true, 5L)
        )
        assertEquals(m, StoryProgressStore.decode(StoryProgressStore.encode(m)))
        assertTrue(StoryProgressStore.decode("not json").isEmpty())
        assertTrue(StoryProgressStore.decode(null).isEmpty())
    }
}
