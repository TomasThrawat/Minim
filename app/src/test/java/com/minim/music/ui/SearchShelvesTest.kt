package com.minim.music.ui

import com.minim.music.data.discover.DiscoverEngine
import com.minim.music.data.discover.Shelf
import com.minim.music.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 5.7.0: the Search page's shelves and the top artists row. */
class SearchShelvesTest {

    private fun t(id: String) = Track(id = id, title = id, artist = "A", durationSec = 1, thumbnailUrl = "", sourceUrl = "")
    private fun shelf(key: String, n: Int = 5) = Shelf(key, key, (1..n).map { t("$key$it") })

    @Test
    fun `top artists, then Gemini, then Discover`() {
        val gemini = Shelf("gemini", "Slow Burn Sufi Nights", (1..6).map { t("g$it") })
        val out = buildSearchShelves(listOf(shelf("heavy"), shelf("mix:1")), listOf("Arijit Singh", "Anirudh", "Pritam"), gemini, "Your top artists")
        assertEquals(listOf(TOP_ARTISTS_SHELF, "gemini", "heavy", "mix:1"), out.map { it.key })
        assertEquals(listOf("Arijit Singh", "Anirudh", "Pritam"), out[0].artists)
        assertEquals("Your top artists", out[0].title)
        assertEquals("Slow Burn Sufi Nights", out[1].title)
    }

    @Test
    fun `extras only show next to Discover's shelves`() {
        val gemini = Shelf("gemini", "X", (1..6).map { t("g$it") })
        assertTrue(buildSearchShelves(emptyList(), listOf("a", "b", "c"), gemini, "T").isEmpty())
    }

    @Test
    fun `short artist rows and short Gemini shelves are left out`() {
        val tiny = Shelf("gemini", "X", listOf(t("g1"), t("g2")))
        val out = buildSearchShelves(listOf(shelf("heavy")), listOf("a", "b"), tiny, "T")
        assertEquals(listOf("heavy"), out.map { it.key })
        val many = buildSearchShelves(listOf(shelf("heavy")), (1..30).map { "artist $it" }, null, "T")
        assertEquals(DiscoverEngine.ARTIST_ROW, many[0].artists.size)
    }

    @Test
    fun `top artists add up plays by first artist`() {
        val out = DiscoverEngine.rankTopArtists(
            listOf(
                "Arijit Singh" to 5,
                "Arijit Singh, Pritam" to 4,
                "Anirudh Ravichander - Topic" to 6,
                "anirudh ravichander" to 1,
                "Pritam" to 2,
                "Old Song Artist" to 0,
                "Unknown artist" to 50,
            ),
            limit = 10
        )
        assertEquals(listOf("Arijit Singh", "Anirudh Ravichander", "Pritam", "Old Song Artist"), out)
        assertEquals(listOf("Arijit Singh", "Anirudh Ravichander"), DiscoverEngine.rankTopArtists(listOf("Arijit Singh" to 9, "Anirudh Ravichander" to 7, "Pritam" to 1), 2))
    }

    @Test
    fun `artist names lose YouTube's suffixes`() {
        assertEquals("Anirudh Ravichander", DiscoverEngine.displayArtist("Anirudh Ravichander - Topic"))
        assertEquals("Arijit Singh", DiscoverEngine.displayArtist("Arijit Singh, Pritam"))
        assertEquals("TaylorSwift", DiscoverEngine.displayArtist("TaylorSwiftVEVO"))
        assertEquals("Topic", DiscoverEngine.displayArtist("Topic"))
    }

    private val main = File("src/main")
    private fun read(path: String) = File(main, path).readText()

    @Test
    fun `swipe up on Home and every mention of it are gone`() {
        val activity = read("java/com/minim/music/ui/MainActivity.kt")
        assertTrue("no screen gesture detector", !activity.contains("setupScreenGestures") && !activity.contains("screenGestures"))
        assertTrue(!File(main, "java/com/minim/music/ui/DiscoverSwipeGate.kt").exists())
        val texts = read("res/values/strings.xml") + read("java/com/minim/music/ui/Tips.kt") +
            File(main, "assets").walkTopDown().filter { it.isFile && it.extension in setOf("json", "txt") }.joinToString("") { it.readText() }
        assertTrue("no text says swipe up opens Discover", !Regex("(?i)swipe up to open discover|swipe up.{0,20}discover").containsMatchIn(texts))
    }

    @Test
    fun `the Lab tab became stories, without the music search`() {
        // 5.7.0 u44: Lab ("coming soon") is now the stories tab
        val strings = read("res/values/strings.xml")
        assertTrue(strings.contains("<string name=\"tab_stories\">tapes</string>")) // u47: renamed to tapes
        assertTrue(!strings.contains("name=\"tab_discover\"") && !strings.contains("name=\"tab_lab\""))
        val top = read("res/layout/view_top_tabs.xml")
        assertTrue(top.contains("@string/tab_stories") && top.contains("@+id/top_stories_new"))
        // 5.7.0 u45: + Add Channel left the top row for the first tile of the channel row
        assertTrue(!top.contains("top_add_channel"))
        assertTrue(read("res/layout/view_stories.xml").contains("@+id/stories_list"))
        assertTrue(read("res/layout/activity_main.xml").contains("@layout/view_stories"))
        val activity = read("java/com/minim/music/ui/MainActivity.kt")
        assertTrue(activity.contains("rowTab == Tab.HOME || rowTab == Tab.STORIES) View.GONE"))
        assertTrue(activity.contains("prefs.storiesTabSeen = true"))
    }

    @Test
    fun `the Search page holds the shelves and hides them while typing`() {
        val search = read("res/layout/view_search.xml")
        assertTrue(search.contains("@+id/search_discover") && search.contains("@+id/discover_shelves") && search.contains("@+id/discover_refresh"))
        val activity = read("java/com/minim/music/ui/MainActivity.kt")
        assertTrue(activity.contains("setSearchTyping(true)") && activity.contains("setSearchTyping(false)"))
    }

    @Test
    fun `full screen lyrics uses the Now Playing bar`() {
        val fs = read("java/com/minim/music/ui/FullscreenLyrics.kt")
        assertTrue(fs.contains("bar.expressive = true"))
        assertTrue(fs.contains("bar.onScrub") && fs.contains("bar.onEdge"))
        assertTrue(read("res/layout/view_fullscreen_lyrics.xml").contains("@+id/fs_seek_pill"))
        assertTrue(read("java/com/minim/music/ui/MainActivity.kt").contains("fsLyrics.setPlayback("))
    }
}
