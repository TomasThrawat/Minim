package com.minim.music.data.ai

import com.minim.music.data.greetings.MiniJson
import com.minim.music.data.local.LibraryDatabase.TrackStats
import com.minim.music.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 5.7.0: the Gemini shelf: prompt, parsing, the title Gemini writes, and the once-a-day maker. */
class GeminiShelfTest {

    private fun track(id: String, title: String = "Song $id", artist: String = "Artist $id") =
        Track(id = id, title = title, artist = artist, durationSec = 200, thumbnailUrl = "", sourceUrl = "")

    private fun stats(t: Track, plays: Int, last: Long, liked: Boolean = false, likedAt: Long = 0L) =
        TrackStats(t, plays, last, liked, likedAt)

    @Test
    fun `parses the title and songs Gemini sends`() {
        val a = GeminiShelfParser.parse(
            """{"title":"Late Night Arijit Detour","songs":[{"title":"Channa Mereya","artist":"Arijit Singh"},{"title":"Agar Tum Saath Ho","artist":"Alka Yagnik"}]}"""
        )!!
        assertEquals("Late Night Arijit Detour", a.title)
        assertEquals(listOf(GeminiPick("Channa Mereya", "Arijit Singh"), GeminiPick("Agar Tum Saath Ho", "Alka Yagnik")), a.picks)
    }

    @Test
    fun `code fences, repeats and broken songs are handled`() {
        val a = GeminiShelfParser.parse(
            "```json\n{\"title\":\"Rainy Day Ghazals\",\"songs\":[{\"title\":\"Tum Hi Ho\",\"artist\":\"Arijit Singh\"}," +
                "{\"title\":\"tum hi ho\",\"artist\":\"ARIJIT SINGH\"},{\"artist\":\"No Title\"},{\"title\":\"  \"}," +
                "{\"title\":\"Kesariya\",\"artist\":\"\"}]}\n```"
        )!!
        assertEquals("Rainy Day Ghazals", a.title)
        assertEquals(listOf(GeminiPick("Tum Hi Ho", "Arijit Singh"), GeminiPick("Kesariya", "")), a.picks)
    }

    @Test
    fun `not JSON or no songs is null`() {
        assertNull(GeminiShelfParser.parse("Sure! Here are some songs"))
        assertNull(GeminiShelfParser.parse("""{"title":"Hi","songs":[]}"""))
        assertNull(GeminiShelfParser.parse("""[1,2,3]"""))
    }

    @Test
    fun `the title is cleaned to one short plain line`() {
        assertEquals("Midnight Drive Melodies", GeminiShelfParser.cleanTitle("\"Midnight Drive Melodies.\""))
        assertEquals("Desi Monsoon Feels", GeminiShelfParser.cleanTitle("Desi Monsoon Feels \uD83C\uDF27\uFE0F"))
        assertEquals("Bollywood Heartbreak Hour", GeminiShelfParser.cleanTitle("\u201cBollywood\nHeartbreak Hour\u201d"))
        assertEquals("LateNight", GeminiShelfParser.cleanTitle("#LateNight"))
        val long = GeminiShelfParser.cleanTitle("Songs For When The City Lights Go Soft And Slow")
        assertTrue(long, long.length <= GeminiShelfPrompt.MAX_TITLE)
        assertFalse(long.endsWith(" "))
        assertTrue("Songs For When The City Lights Go Soft".startsWith(long))
    }

    @Test
    fun `empty or generic titles fall back`() {
        val f = GeminiShelfPrompt.FALLBACK_TITLE
        assertEquals(f, GeminiShelfParser.cleanTitle(null))
        assertEquals(f, GeminiShelfParser.cleanTitle("   "))
        assertEquals(f, GeminiShelfParser.cleanTitle("Recommended"))
        assertEquals(f, GeminiShelfParser.cleanTitle("For You!"))
        assertEquals(f, GeminiShelfParser.cleanTitle("\uD83D\uDD25\uD83D\uDD25"))
    }

    @Test
    fun `the request asks for JSON with a title and songs`() {
        val taste = GeminiTaste(listOf("Tum Hi Ho by Arijit Singh"), listOf("Arijit Singh"), emptyList(), emptyList(), listOf("Tum Hi Ho by Arijit Singh"))
        val body = MiniJson.parse(GeminiShelfPrompt.requestBody(taste)) as Map<*, *>
        val cfg = body["generationConfig"] as Map<*, *>
        assertEquals("application/json", cfg["responseMimeType"])
        val props = (cfg["responseSchema"] as Map<*, *>)["properties"] as Map<*, *>
        assertTrue(props.containsKey("title") && props.containsKey("songs"))
        val system = ((body["systemInstruction"] as Map<*, *>)["parts"] as List<*>).first() as Map<*, *>
        assertTrue((system["text"] as String).contains("shelf title"))
        val user = GeminiShelfPrompt.userText(taste)
        assertTrue(user.contains("Most played songs:\n- Tum Hi Ho by Arijit Singh"))
        assertTrue(user.contains("don't pick these"))
        assertFalse("empty sections are left out", user.contains("Liked recently"))
    }

    @Test
    fun `the taste summary leaves out Not interested songs and orders by plays`() {
        val a = track("a"); val b = track("b"); val c = track("c"); val d = track("d")
        val t = GeminiShelfPrompt.taste(
            listOf(stats(a, 3, 100), stats(b, 9, 50), stats(c, 20, 200), stats(d, 0, 0, liked = true, likedAt = 5)),
            listOf("Artist b"),
            blocked = setOf("c")
        )
        assertEquals(listOf("Song b by Artist b", "Song a by Artist a"), t.topSongs)
        assertEquals(listOf("Song a by Artist a", "Song b by Artist b"), t.recent)
        assertEquals(listOf("Song d by Artist d"), t.liked)
        assertFalse(t.known.any { it.contains("Song c") })
    }

    @Test
    fun `found songs keep order without misses, repeats or skipped songs`() {
        val a = track("a"); val b = track("b"); val c = track("c")
        val out = GeminiShelfMaker.pickTracks(listOf(a, null, b, a, c), skip = { it.id == "b" })
        assertEquals(listOf(a, c), out)
        val many = (1..30).map { track("t$it") }
        assertEquals(GeminiShelfMaker.MAX_TRACKS, GeminiShelfMaker.pickTracks(many) { false }.size)
    }

    private fun sse(text: String): String {
        val chunk = """{"candidates":[{"content":{"parts":[{"text":${JsonOut.write(text)}}]},"finishReason":"STOP"}]}"""
        return "data: $chunk\n\n"
    }

    private val answer = """{"title":"Slow Burn Sufi Nights","songs":[""" +
        (1..8).joinToString(",") { """{"title":"Song $it","artist":"Artist $it"}""" } + "]}"

    @Test
    fun `makes one shelf a day with Gemini's title and saves it`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(sse(answer)).setHeader("Content-Type", "text/event-stream"))
        server.start()
        val dir = Files.createTempDirectory("gshelf").toFile()
        val file = File(dir, "gemini_shelf.json")
        try {
            val maker = GeminiShelfMaker(GeminiClient(OkHttpClient()), file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
            val ep = GeminiEndpoint(server.url("/stream").toString(), emptyMap(), ownKey = false, model = "test")
            var finds = 0
            val taste = GeminiTaste(listOf("X by Y"), listOf("Y"), emptyList(), emptyList(), emptyList())
            val made = maker.today(
                today = 100L, keyHash = 7, taste = { taste }, endpoint = { ep },
                find = { p -> finds++; if (p.title == "Song 3") null else track(p.title.replace(' ', '_'), p.title, p.artist) },
                skip = { it.id == "Song_5" }
            )!!
            assertEquals("Slow Burn Sufi Nights", made.title)
            assertEquals(listOf("Song 1", "Song 2", "Song 4", "Song 6", "Song 7", "Song 8"), made.tracks.map { it.title })
            assertEquals(8, finds)
            assertEquals(1, server.requestCount)
            assertTrue(file.exists())

            // Same day, same key: the saved shelf, no second call, even from a fresh maker
            val again = GeminiShelfMaker(GeminiClient(OkHttpClient()), file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
            val same = again.today(100L, 7, { taste }, { ep }, { null }, { false })
            assertEquals(made, same)
            assertEquals(1, server.requestCount)
            assertFalse(again.isStale(again.saved(), 100L, 7))
            assertTrue(again.isStale(again.saved(), 101L, 7))
            assertTrue(again.isStale(again.saved(), 100L, 8))
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a failed day isn't asked again straight away`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(sse("not json")).setHeader("Content-Type", "text/event-stream"))
        server.enqueue(MockResponse().setBody(sse("still not json")).setHeader("Content-Type", "text/event-stream"))
        server.start()
        val dir = Files.createTempDirectory("gshelf").toFile()
        try {
            val maker = GeminiShelfMaker(GeminiClient(OkHttpClient()), File(dir, "g.json"), CoroutineScope(SupervisorJob() + Dispatchers.IO))
            val ep = GeminiEndpoint(server.url("/stream").toString(), emptyMap(), ownKey = false, model = "test")
            val taste = GeminiTaste(listOf("X by Y"), emptyList(), emptyList(), emptyList(), emptyList())
            assertNull(maker.today(5L, 1, { taste }, { ep }, { track(it.title) }, { false }))
            assertEquals("asks once more when the answer isn't JSON", 2, server.requestCount)
            assertNull(maker.today(5L, 1, { taste }, { ep }, { track(it.title) }, { false }))
            assertEquals(2, server.requestCount)
            assertNull(maker.saved())
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `too few found songs is no shelf`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(sse(answer)).setHeader("Content-Type", "text/event-stream"))
        server.start()
        val dir = Files.createTempDirectory("gshelf").toFile()
        try {
            val maker = GeminiShelfMaker(GeminiClient(OkHttpClient()), File(dir, "g.json"), CoroutineScope(SupervisorJob() + Dispatchers.IO))
            val ep = GeminiEndpoint(server.url("/stream").toString(), emptyMap(), ownKey = false, model = "test")
            val taste = GeminiTaste(listOf("X by Y"), emptyList(), emptyList(), emptyList(), emptyList())
            val made = maker.today(9L, 1, { taste }, { ep }, { p -> if (p.title == "Song 1") track("one") else null }, { false })
            assertNull(made)
        } finally {
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `no listening yet is no call`() = runBlocking {
        val dir = Files.createTempDirectory("gshelf").toFile()
        try {
            val maker = GeminiShelfMaker(GeminiClient(OkHttpClient()), File(dir, "g.json"), CoroutineScope(SupervisorJob() + Dispatchers.IO))
            var asked = false
            val made = maker.today(1L, 1, { GeminiTaste(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()) },
                { asked = true; GeminiEndpoint("http://127.0.0.1:1/", emptyMap(), ownKey = false, model = "test") }, { null }, { false })
            assertNull(made)
            assertFalse(asked)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `key hash changes with the key and the model`() {
        val a = GeminiShelfMaker.keyHash("AIzaSyABCDEFGH12345678", "gemini-2.5-flash")
        assertEquals(a, GeminiShelfMaker.keyHash("AIzaSyABCDEFGH12345678", "gemini-2.5-flash"))
        assertTrue(a != GeminiShelfMaker.keyHash("AIzaSyABCDEFGH87654321", "gemini-2.5-flash"))
        assertTrue(a != GeminiShelfMaker.keyHash("AIzaSyABCDEFGH12345678", "gemini-2.5-pro"))
        assertNotNull(a)
    }
}
