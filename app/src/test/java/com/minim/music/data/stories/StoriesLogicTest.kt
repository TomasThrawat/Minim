package com.minim.music.data.stories

import com.minim.music.R
import com.minim.music.data.model.Track
import com.minim.music.ui.SongMenuLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoriesLogicTest {

    private fun ch(id: String, name: String = id) = StoryChannel(id, name, "https://www.youtube.com/channel/$id")
    private fun up(v: String, c: String, at: Long) = StoryUpload(v, "t$v", c, "n$c", 300, at)

    // ---- label switch ----

    @Test
    fun storiesUploadSaysListeningSongsSayVibing() {
        val story = StoryTracks.track("abcdefghijk", "Episode 12", "Some Channel", 1800)
        val song = Track("abcdefghijk", "Tum Hi Ho", "Arijit Singh", 262, "https://lh3.googleusercontent.com/x", "https://music.youtube.com/watch?v=abcdefghijk")
        assertEquals(R.string.youre_listening_to, NowPlayingLabel.forTrack(story))
        assertEquals(R.string.youre_vibing_to, NowPlayingLabel.forTrack(song))
        assertEquals(R.string.youre_vibing_to, NowPlayingLabel.forTrack(null))
    }

    @Test
    fun storyMarkSurvivesAsAPlainTrack() {
        val story = StoryTracks.track("abcdefghijk", "Ep", "Chan", 100)
        // Copied the way the queue and the library copy tracks
        val copy = Track(story.id, story.title, story.artist, story.durationSec, story.thumbnailUrl, story.sourceUrl)
        assertTrue(StoryTracks.isStory(copy))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", story.thumbnailUrl)
        assertFalse(StoryTracks.isStory(Track("x", "a", "b", 1, "", "https://www.youtube.com/watch?v=x")))
    }

    @Test
    fun storyShareLinkIsTheVideo() {
        val story = StoryTracks.track("abcdefghijk", "Ep", "Chan", 100)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", SongMenuLogic.shareUrl(story.id, story.sourceUrl))
    }

    // ---- channel list ----

    @Test
    fun addPutsNewestFirstAndSkipsDoubles() {
        var list = StoryChannelList.add(emptyList(), ch("UCa"))
        list = StoryChannelList.add(list, ch("UCb"))
        assertEquals(listOf("UCb", "UCa"), list.map { it.id })
        assertEquals(list, StoryChannelList.add(list, ch("UCa", "Renamed")))
        // Same link, other id form, is the same channel
        val byUrl = StoryChannel("https://www.youtube.com/channel/UCa", "A", "https://www.youtube.com/channel/UCa/")
        assertEquals(2, StoryChannelList.add(list, byUrl).size)
    }

    @Test
    fun removeTakesOnlyThatChannel() {
        val list = listOf(ch("UCa"), ch("UCb"), ch("UCc"))
        assertEquals(listOf("UCa", "UCc"), StoryChannelList.remove(list, "UCb").map { it.id })
        assertEquals(list, StoryChannelList.remove(list, "UCzzz"))
    }

    // ---- links ----

    @Test
    fun channelLinksAreRecognised() {
        assertEquals("https://www.youtube.com/@mkbhd", ChannelLinks.normalise("https://www.youtube.com/@mkbhd/videos"))
        assertEquals("https://www.youtube.com/@mkbhd", ChannelLinks.normalise("@mkbhd"))
        assertEquals("https://www.youtube.com/@mkbhd", ChannelLinks.normalise("m.youtube.com/@mkbhd?si=x"))
        assertEquals(
            "https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ",
            ChannelLinks.normalise("https://youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ/featured")
        )
        assertEquals("UCBJycsmduvYEL83R_U4JriQ", ChannelLinks.channelId("https://www.youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ"))
        assertNull(ChannelLinks.normalise("Marques Brownlee"))
        assertNull(ChannelLinks.normalise("https://www.youtube.com/watch?v=abcdefghijk"))
    }

    // ---- feed ----

    @Test
    fun feedMixesChannelsNewestFirstAndDropsRemovedOnes() {
        val uploads = listOf(up("1", "UCa", 100), up("2", "UCb", 300), up("3", "UCa", 200), up("4", "UCgone", 999), up("2", "UCb", 300))
        val feed = StoryFeed.merge(uploads, listOf(ch("UCa"), ch("UCb")))
        assertEquals(listOf("2", "3", "1"), feed.map { it.videoId })
    }

    @Test
    fun ageBuckets() {
        val day = 86_400_000L
        val now = 1000 * day
        assertEquals(StoryFeed.Age.TODAY to 0, StoryFeed.age(now - 3_600_000L, now))
        assertEquals(StoryFeed.Age.YESTERDAY to 1, StoryFeed.age(now - day - 1, now))
        assertEquals(StoryFeed.Age.DAYS to 3, StoryFeed.age(now - 3 * day, now))
        assertEquals(StoryFeed.Age.WEEKS to 2, StoryFeed.age(now - 15 * day, now))
        assertEquals(StoryFeed.Age.MONTHS to 2, StoryFeed.age(now - 70 * day, now))
        assertEquals(StoryFeed.Age.YEARS to 1, StoryFeed.age(now - 400 * day, now))
        assertEquals(StoryFeed.Age.UNKNOWN to 0, StoryFeed.age(0, now))
    }
}
