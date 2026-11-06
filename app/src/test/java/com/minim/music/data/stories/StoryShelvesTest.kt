package com.minim.music.data.stories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.7.0 u46: the stories shelves, the length chips, dimming heard uploads and resume. */
class StoryShelvesTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun up(id: String, ch: String, daysAgo: Double, min: Long) = StoryUpload(
        videoId = id, title = "Story $id", channelId = ch, channelName = "Name $ch",
        durationSec = min * 60, publishedAt = now - (daysAgo * day).toLong()
    )

    private val channels = listOf(StoryChannel("A", "Kaushik STORIES", ""), StoryChannel("B", "SR PAY STORIES", ""))

    private val feed = listOf(
        up("a1", "A", 0.5, 25),
        up("b1", "B", 2.0, 12),
        up("a2", "A", 6.5, 45),
        up("b2", "B", 8.0, 35),
        up("a3", "A", 20.0, 18)
    )

    private fun half(id: String, at: Long) = id to StoryPlayback(10 * 60_000L, 40 * 60_000L, false, at)

    @Test
    fun shelves_comeInOrder_continueThenNewThenOnePerChannel() {
        val progress = mapOf(half("b2", now - 1000))
        val shelves = StoryShelves.build(feed, channels, progress, StoryLength.ALL, now)
        assertEquals(listOf(StoryShelf.Kind.CONTINUE, StoryShelf.Kind.NEW, StoryShelf.Kind.CHANNEL, StoryShelf.Kind.CHANNEL), shelves.map { it.kind })
        assertEquals(listOf("b2"), shelves[0].uploads.map { it.videoId })
        assertEquals("Kaushik STORIES", shelves[2].title)
        assertEquals("A", shelves[2].channelId)
        assertEquals(listOf("a1", "a2", "a3"), shelves[2].uploads.map { it.videoId })
        assertEquals("SR PAY STORIES", shelves[3].title)
    }

    @Test
    fun newThisWeek_isOnlyTheLastSevenDays_newestFirst() {
        val new = StoryShelves.build(feed, channels, emptyMap(), StoryLength.ALL, now).first { it.kind == StoryShelf.Kind.NEW }
        assertEquals(listOf("a1", "b1", "a2"), new.uploads.map { it.videoId })
    }

    @Test
    fun continueListening_lastPlayedFirst_andSkipsUnstartedAndHeard() {
        val progress = mapOf(
            half("a2", now - 5000),
            half("b2", now - 1000),
            "a1" to StoryPlayback(10_000L, 25 * 60_000L, false, now), // barely started
            "b1" to StoryPlayback(12 * 60_000L, 12 * 60_000L, true, now) // heard
        )
        val cont = StoryShelves.build(feed, channels, progress, StoryLength.ALL, now).first()
        assertEquals(StoryShelf.Kind.CONTINUE, cont.kind)
        assertEquals(listOf("b2", "a2"), cont.uploads.map { it.videoId })
    }

    @Test
    fun emptyShelves_areHidden() {
        // Nothing half-finished and nothing from this week: only the channel rows
        val old = listOf(up("x", "A", 30.0, 20), up("y", "A", 40.0, 22))
        val shelves = StoryShelves.build(old, channels, emptyMap(), StoryLength.ALL, now)
        assertEquals(1, shelves.size)
        assertEquals(StoryShelf.Kind.CHANNEL, shelves[0].kind)
        assertEquals("A", shelves[0].channelId)
        // No uploads at all: no shelves
        assertTrue(StoryShelves.build(emptyList(), channels, emptyMap(), StoryLength.ALL, now).isEmpty())
    }

    @Test
    fun removedChannel_leavesNoShelf() {
        val shelves = StoryShelves.build(feed, channels.take(1), emptyMap(), StoryLength.ALL, now)
        assertTrue(shelves.all { s -> s.uploads.all { it.channelId == "A" } })
        assertEquals(1, shelves.count { it.kind == StoryShelf.Kind.CHANNEL })
    }

    @Test
    fun lengthChips_filterEveryShelf_andHideOnesLeftEmpty() {
        val short = StoryShelves.build(feed, channels, emptyMap(), StoryLength.SHORT, now)
        assertTrue(short.flatMap { it.uploads }.all { it.durationSec < 20 * 60 })
        assertEquals(setOf("b1", "a3"), short.flatMap { it.uploads }.map { it.videoId }.toSet())
        val long = StoryShelves.build(feed, channels, emptyMap(), StoryLength.LONG, now)
        assertTrue(long.flatMap { it.uploads }.all { it.durationSec >= 30 * 60 })
        assertEquals(setOf("a2", "b2"), long.flatMap { it.uploads }.map { it.videoId }.toSet())
        assertEquals(listOf("a2", "b2"), StoryShelves.channelList(feed, "A", StoryLength.LONG).map { it.videoId } +
            StoryShelves.channelList(feed, "B", StoryLength.LONG).map { it.videoId })
    }

    @Test
    fun lengthChips_edges_andTapAgainClears() {
        assertTrue(StoryLength.SHORT.matches(19 * 60 + 59))
        assertFalse(StoryLength.SHORT.matches(20 * 60))
        assertFalse(StoryLength.SHORT.matches(0)) // unknown length
        assertFalse(StoryLength.LONG.matches(29 * 60 + 59))
        assertTrue(StoryLength.LONG.matches(30 * 60))
        assertTrue(StoryLength.ALL.matches(0))
        assertEquals(StoryLength.SHORT, StoryLength.ALL.tap(StoryLength.SHORT))
        assertEquals(StoryLength.ALL, StoryLength.SHORT.tap(StoryLength.SHORT))
        assertEquals(StoryLength.LONG, StoryLength.SHORT.tap(StoryLength.LONG))
    }

    @Test
    fun heardUploads_goDim_atNinetyPercentOrTheEnd() {
        val dur = 40 * 60_000L
        val p89 = StoryProgress.update(null, (dur * 0.89).toLong(), dur, ended = false, now = now)
        assertFalse(StoryShelves.isDim(p89))
        val p90 = StoryProgress.update(null, (dur * 0.90).toLong(), dur, ended = false, now = now)
        assertTrue(StoryShelves.isDim(p90))
        val ended = StoryProgress.update(null, 1000L, dur, ended = true, now = now)
        assertTrue(StoryShelves.isDim(ended))
        assertFalse(StoryShelves.isDim(null))
        // Playing a heard one again from the start keeps it heard (it doesn't come back to Continue)
        val again = StoryProgress.update(p90, 60_000L, dur, ended = false, now = now)
        assertTrue(again.done)
        assertFalse(StoryProgress.isInProgress(again))
    }

    @Test
    fun resume_startsWhereYouStopped_lessAFewSeconds() {
        val dur = 40 * 60_000L
        assertEquals(0L, StoryProgress.resumeMs(null))
        // Under 30 seconds in: from the start
        assertEquals(0L, StoryProgress.resumeMs(StoryProgress.update(null, 20_000L, dur, false, now)))
        val p = StoryProgress.update(null, 12 * 60_000L, dur, false, now)
        assertEquals(12 * 60_000L - StoryProgress.REWIND_MS, StoryProgress.resumeMs(p))
        assertTrue(StoryProgress.isInProgress(p))
        assertEquals(0.3f, StoryProgress.fraction(p), 0.001f)
        assertEquals(28, StoryProgress.minutesLeft(p, 40 * 60))
        // Heard: starts over
        assertEquals(0L, StoryProgress.resumeMs(StoryProgress.update(null, dur, dur, true, now)))
        // Unknown length still resumes
        assertEquals(5 * 60_000L - StoryProgress.REWIND_MS, StoryProgress.resumeMs(StoryProgress.update(null, 5 * 60_000L, 0L, false, now)))
    }

    @Test
    fun progress_keepsTheNewestEntries() {
        val many = (1..10).associate { "v$it" to StoryPlayback(1L, 2L, false, it.toLong()) }
        assertEquals((6..10).map { "v$it" }.toSet(), StoryProgress.trim(many, 5).keys)
    }
}
