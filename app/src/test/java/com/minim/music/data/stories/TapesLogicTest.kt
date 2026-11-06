package com.minim.music.data.stories

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.minim.music.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 5.7.0 u47: tapes logic: filters, New to You, the dot, played ticks, speed, sleep, channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TapesLogicTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun up(id: String, ch: String, name: String = ch, ageDays: Long = 1, dur: Long = 600, views: Long = -1) =
        StoryUpload(id, "title $id", ch, name, dur, now - ageDays * day, views)

    private fun song(id: String) = Track(id = id, title = "s$id", artist = "A", durationSec = 200, thumbnailUrl = "", sourceUrl = "")

    private fun done() = StoryPlayback(100_000, 100_000, true, now)
    private fun half() = StoryPlayback(50_000, 100_000, false, now)

    @Test
    fun libraryFilterSplitsSongsAndTapes() {
        val tape = StoryTracks.track("v1", "t", "Kaushik", 900)
        val all = listOf(song("a"), tape, song("b"))
        assertEquals(listOf("a", "b"), LibraryFilter.split(all, LibraryKind.SONGS).map { it.id })
        assertEquals(listOf("v1"), LibraryFilter.split(all, LibraryKind.TAPES).map { it.id })
        assertEquals(2, LibraryFilter.songsOnly(all).size)
    }

    @Test
    fun tapeKeepsItsChannelIdAndOldTapesStillWork() {
        val t = StoryTracks.track("vid12345678", "t", "Kaushik", 900, "UCabcdefghijklmnop")
        assertTrue(StoryTracks.isStory(t))
        assertEquals("UCabcdefghijklmnop", StoryTracks.channelIdOf(t))
        val old = StoryTracks.track("vid12345678", "t", "Kaushik", 900)
        assertTrue(StoryTracks.isStory(old))
        assertNull(StoryTracks.channelIdOf(old))
        assertNull(StoryTracks.channelIdOf(song("x")))
    }

    @Test
    fun channelForUsesIdThenName() {
        val followed = listOf(StoryChannel("UCkaushik000001", "Kaushik STORIES", "u"))
        val withId = StoryTracks.track("v", "t", "Someone", 100, "UCother00000001")
        assertEquals("UCother00000001", ChannelVideos.channelFor(withId, followed)!!.id)
        val old = StoryTracks.track("v", "t", "kaushik stories", 100)
        assertEquals("UCkaushik000001", ChannelVideos.channelFor(old, followed)!!.id)
        assertNull(ChannelVideos.channelFor(StoryTracks.track("v", "t", "Nobody", 100), followed))
    }

    @Test
    fun newToYouLeavesOutFollowedAndPlayedAndCapsPerChannel() {
        val followed = listOf(StoryChannel("UCmine", "Mine", "u"))
        val related = listOf(
            listOf(up("a1", "UCx"), up("a2", "UCx"), up("a3", "UCx"), up("a4", "UCx"), up("m1", "UCmine"), up("p1", "UCy")),
            listOf(up("b1", "UCz"), up("a1", "UCx"), up("n1", "UCq", "mine"))
        )
        val out = NewToYou.build(related, followed, mapOf("p1" to done()), now)
        val ids = out.map { it.videoId }
        assertEquals("a1", ids.first()) // two of your tapes point at it
        assertFalse("m1" in ids)
        assertFalse("n1" in ids) // same name as a followed channel
        assertFalse("p1" in ids)
        assertEquals(3, ids.count { it.startsWith("a") })
        assertTrue("b1" in ids)
    }

    @Test
    fun newToYouDropsWhatYouFinishLaterAndChannelsYouFollowLater() {
        val items = listOf(up("a", "UCx"), up("b", "UCy"), up("c", "UCz"))
        val v = NewToYou.visible(items, listOf(StoryChannel("UCz", "Z", "u")), mapOf("a" to done(), "b" to half()))
        assertEquals(listOf("b"), v.map { it.videoId })
    }

    @Test
    fun newToYouSeedsPreferRecentPlaysThenEachChannel() {
        val feed = listOf(up("x1", "UCx", ageDays = 1), up("x2", "UCx", ageDays = 2), up("y1", "UCy", ageDays = 3))
        val prog = mapOf("x2" to StoryPlayback(1, 2, false, now))
        val seeds = NewToYou.seeds(feed, prog, max = 3)
        assertEquals("x2", seeds.first())
        assertTrue("y1" in seeds)
        assertEquals(3, seeds.size)
    }

    @Test
    fun ringOnlyForUnplayedUploadsFromThisWeek() {
        val uploads = listOf(up("a", "UCa", ageDays = 2), up("b", "UCb", ageDays = 2), up("c", "UCc", ageDays = 10))
        val ring = NewUploadRing.channelsWithNew(uploads, mapOf("b" to half()), now)
        assertEquals(setOf("UCa"), ring)
    }

    @Test
    fun playedMarkIsTheDoneFlag() {
        assertTrue(PlayedMark.isPlayed(done()))
        assertFalse(PlayedMark.isPlayed(half()))
        assertFalse(PlayedMark.isPlayed(null))
        assertEquals(setOf("a"), PlayedMark.playedIds(mapOf("a" to done(), "b" to half())))
    }

    @Test
    fun popularSortsByViewsLatestByDate() {
        val list = listOf(up("a", "UC", ageDays = 1, views = 10), up("b", "UC", ageDays = 5, views = 900), up("c", "UC", ageDays = 3, views = 50))
        assertEquals(listOf("b", "c", "a"), ChannelVideos.sort(list, ChannelSort.POPULAR).map { it.videoId })
        assertEquals(listOf("a", "c", "b"), ChannelVideos.sort(list, ChannelSort.LATEST).map { it.videoId })
    }

    @Test
    fun followCheck() {
        val f = listOf(StoryChannel("UC1", "One", "u"))
        assertTrue(ChannelVideos.isFollowed("UC1", "x", f))
        assertFalse(ChannelVideos.isFollowed("UC2", "One", f))
    }

    @Test
    fun speedIsPerChannelAndSongsStayAt1x() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = TapeSpeedStore(ctx.getSharedPreferences("speed_test", Context.MODE_PRIVATE))
        val tape = StoryTracks.track("v", "t", "Kaushik STORIES", 900)
        assertEquals(1f, store.speedFor(tape))
        store.set("Kaushik STORIES", 1.5f)
        assertEquals(1.5f, store.speedFor(tape))
        assertEquals(1.5f, store.speedFor(StoryTracks.track("v2", "t", "kaushik stories", 900)))
        assertEquals(1f, store.speedFor(StoryTracks.track("v3", "t", "Other", 900)))
        assertEquals(1f, store.speedFor(song("s").copy(artist = "Kaushik STORIES")))
        // survives a restart
        val again = TapeSpeedStore(ctx.getSharedPreferences("speed_test", Context.MODE_PRIVATE))
        assertEquals(1.5f, again.speedFor(tape))
        again.set("Kaushik STORIES", 1f)
        assertEquals(1f, again.speedFor(tape))
        assertEquals(listOf("1x", "1.25x", "1.5x", "2x"), TapeSpeed.OPTIONS.map { TapeSpeed.label(it) })
        assertEquals(emptyMap<String, Float>(), TapeSpeedStore.decode("{\"a\":3.0}"))
    }

    @Test
    fun sleepCustomAndCountdown() {
        assertEquals(25, SleepOptions.parseCustom(" 25 "))
        assertNull(SleepOptions.parseCustom("0"))
        assertNull(SleepOptions.parseCustom("721"))
        assertNull(SleepOptions.parseCustom("abc"))
        assertEquals("15:00", SleepOptions.countdown(15 * 60_000L))
        assertEquals("0:01", SleepOptions.countdown(1))
        assertEquals("1:02:05", SleepOptions.countdown((3600 + 125) * 1000L))
        assertEquals(30_000L, SleepOptions.remainingAtSpeed(100_000, 40_000, 2f))
        assertEquals(listOf(15, 30, 45, 60), SleepOptions.PRESET_MINUTES)
    }

    @Test
    fun newToYouCacheSurvivesInStore() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = StoriesStore(ctx)
        store.saveNewToYou(listOf(up("a", "UCx")), now)
        assertEquals(listOf("a"), StoriesStore(ctx).newToYou.map { it.videoId })
        assertEquals(now, StoriesStore(ctx).newToYouAt)
    }
}
