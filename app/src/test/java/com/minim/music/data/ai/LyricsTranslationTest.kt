package com.minim.music.data.ai

import com.minim.music.data.ai.LyricLanguage.Kind
import com.minim.music.data.greetings.MiniJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 5.6.0: AI lyrics translation: English skip, prompt, parsing, line matching, the saved copy, one call per song. */
class LyricsTranslationTest {

    // ---------------------------------------------------------------- English skip

    @Test
    fun english_lines_are_english() {
        for (l in listOf(
            "Hello from the other side",
            "I must have called a thousand times",
            "Baby, I love you so much",
            "We don't talk anymore",
            "Tonight I'm gonna dance like nobody's watching",
            "I'm in love with the shape of you",
        )) assertEquals(l, Kind.ENGLISH, LyricLanguage.kind(l))
    }

    @Test
    fun hindi_hinglish_spanish_korean_lines_need_translation() {
        for (l in listOf(
            "Tum hi ho",
            "Main tujhse pyaar karta hoon",
            "Kesariya tera ishq hai piya",
            "Tujhe dekha to ye jaana sanam",
            "Despacito, quiero respirar tu cuello despacito",
            "तुम ही हो",
            "사랑해요",
            "আমি তোমাকে ভালোবাসি",
            "Saranghae neoreul",
        )) assertEquals(l, Kind.OTHER, LyricLanguage.kind(l))
    }

    @Test
    fun notes_markers_sounds_and_blank_lines_are_skipped() {
        for (l in listOf("", "   ", "♪", "♪ ♪", "(Instrumental)", "[Chorus]", "[Verse 2: Arijit Singh]", "Oh oh oh", "La la la", "...", "Na na na na")) {
            assertEquals("'$l'", Kind.SKIP, LyricLanguage.kind(l))
        }
    }

    @Test
    fun the_button_shows_only_for_songs_that_arent_english() {
        val english = List(20) { "I will always love you, baby" } + "♪"
        assertFalse(LyricLanguage.plan(english).showButton)
        // one stray word in a long English song doesn't count
        assertFalse(LyricLanguage.plan(english + "Despacito").showButton)
        val hinglish = listOf("Baby I love you so much", "Tum hi ho", "Ab tum hi ho", "♪", "I need you tonight")
        val plan = LyricLanguage.plan(hinglish)
        assertTrue(plan.showButton)
        assertEquals(listOf(Kind.ENGLISH, Kind.OTHER, Kind.OTHER, Kind.SKIP, Kind.ENGLISH), plan.kinds)
        assertEquals(listOf(0, 1, 2, 4), plan.sendIndices)
        // a short song with one non-English line
        assertTrue(LyricLanguage.plan(listOf("Despacito", "Hello there")).showButton)
        assertFalse(LyricLanguage.plan(emptyList()).showButton)
    }

    // ---------------------------------------------------------------- prompt

    @Test
    fun one_request_holds_every_line_with_its_number_the_prompt_and_the_json_schema() {
        val lines = listOf("Tum hi ho", "♪", "I need you")
        val body = LyricsTranslationPrompt.requestBody(lines, listOf(0, 2), "Tum Hi Ho", "Arijit Singh")
        val root = MiniJson.parse(body, 1_000_000) as Map<*, *>
        val sys = ((root["systemInstruction"] as Map<*, *>)["parts"] as List<*>).first() as Map<*, *>
        val prompt = sys["text"] as String
        for (must in listOf("natural, fluent English", "Never translate word for word", "idioms", "ishq", "empty string", "exactly one item for every input line", "short enough")) {
            assertTrue(must, prompt.contains(must))
        }
        val user = ((((root["contents"] as List<*>).first() as Map<*, *>)["parts"] as List<*>).first() as Map<*, *>)["text"] as String
        assertTrue(user.contains("\"Tum Hi Ho\" by Arijit Singh"))
        assertTrue(user.contains("Return exactly 2 items"))
        assertTrue(user.contains("{\"id\":0,\"text\":\"Tum hi ho\"}"))
        assertTrue(user.contains("{\"id\":2,\"text\":\"I need you\"}"))
        assertFalse(user.contains("♪"))
        val gen = root["generationConfig"] as Map<*, *>
        assertEquals("application/json", gen["responseMimeType"])
        val items = (((gen["responseSchema"] as Map<*, *>)["properties"] as Map<*, *>)["lines"] as Map<*, *>)["items"] as Map<*, *>
        assertEquals(listOf("id", "en"), items["required"])
    }

    // ---------------------------------------------------------------- parsing + line matching

    @Test
    fun parses_the_json_even_inside_code_fences() {
        val a = LyricsTranslationParser.parseItems("""{"lines":[{"id":0,"en":"Only you"},{"id":2,"en":""}]}""")!!
        assertEquals(listOf(LyricsTranslationParser.Item(0, "Only you"), LyricsTranslationParser.Item(2, "")), a)
        val b = LyricsTranslationParser.parseItems("```json\n{\"lines\":[{\"id\":\"3\",\"en\":\"Hi\"}]}\n```")!!
        assertEquals(listOf(LyricsTranslationParser.Item(3, "Hi")), b)
        assertEquals(listOf(LyricsTranslationParser.Item(null, "x")), LyricsTranslationParser.parseItems("[\"x\"]"))
        assertNull(LyricsTranslationParser.parseItems("Sorry, I can't"))
        assertNull(LyricsTranslationParser.parseItems(""))
        assertNull(LyricsTranslationParser.parseItems("{\"other\":1}"))
    }

    @Test
    fun translations_stay_on_their_own_lines_even_when_gemini_skips_or_reorders() {
        val ids = listOf(1, 3, 5, 7)
        val items = listOf(
            LyricsTranslationParser.Item(5, "five"),
            LyricsTranslationParser.Item(1, "one"),
            LyricsTranslationParser.Item(9, "not sent"),
            LyricsTranslationParser.Item(1, "duplicate"),
            LyricsTranslationParser.Item(7, "  \"seven\" "),
        )
        // line 3 was skipped: it just gets nothing, the rest don't shift
        assertEquals(mapOf(5 to "five", 1 to "one", 7 to "seven"), LyricsTranslationParser.align(ids, items))
    }

    @Test
    fun an_answer_that_cant_be_matched_is_refused() {
        val ids = listOf(0, 1, 2, 3)
        // under half the lines came back
        assertNull(LyricsTranslationParser.align(ids, listOf(LyricsTranslationParser.Item(0, "a"))))
        // no numbers and the wrong count: no way to know which line is which
        assertNull(LyricsTranslationParser.align(ids, listOf("a", "b", "c").map { LyricsTranslationParser.Item(null, it) }))
        // some numbered, some not
        assertNull(LyricsTranslationParser.align(ids, listOf(LyricsTranslationParser.Item(0, "a"), LyricsTranslationParser.Item(null, "b"))))
        // no numbers but exactly one per line: by order
        assertEquals(
            mapOf(0 to "a", 1 to "b", 3 to "d"),
            LyricsTranslationParser.align(ids, listOf("a", "b", "", "d").map { LyricsTranslationParser.Item(null, it) })
        )
    }

    @Test
    fun finish_leaves_english_skipped_and_copied_lines_empty() {
        val lines = listOf("Tum hi ho", "♪", "Hello from the other side", "Despacito", "Ishq")
        val kinds = LyricLanguage.plan(lines).kinds
        val aligned = mapOf(0 to "Only you", 1 to "music", 2 to "Hello from the other side!", 3 to "Slowly", 4 to "ishq")
        assertEquals(listOf("Only you", null, null, "Slowly", null), LyricsTranslationParser.finish(lines, kinds, aligned))
    }

    @Test
    fun clean_trims_quotes_and_cuts_runaway_lines() {
        assertEquals("Only you", LyricsTranslationParser.clean("“Only you”"))
        assertNull(LyricsTranslationParser.clean("  "))
        assertNull(LyricsTranslationParser.clean("—"))
        val long = LyricsTranslationParser.clean("word ".repeat(60))!!
        assertTrue(long.length <= LyricsTranslationPrompt.MAX_CHARS + 1)
        assertTrue(long.endsWith("…"))
    }

    // ---------------------------------------------------------------- saved copy

    private fun tempDir(): File = Files.createTempDirectory("minim-tr").toFile()

    @Test
    fun the_saved_copy_survives_a_restart_and_is_keyed_by_song_and_lyrics() {
        val dir = tempDir()
        val lines = listOf("Tum hi ho", "♪")
        TranslationCache(dir).put("abc123", lines, listOf("Only you", null))
        // a fresh cache, as after the app restarts
        val again = TranslationCache(dir)
        assertEquals(listOf("Only you", null), again.get("abc123", lines))
        assertNull(again.get("other", lines))
        assertNull(again.get("abc123", listOf("Tum hi ho", "♪", "new line")))
        assertNotEquals(again.key("abc123", lines), again.key("abc123", listOf("Tum hi ho ")))
        assertTrue(again.key("abc123", lines).startsWith("abc123_"))
    }

    @Test
    fun a_damaged_file_is_ignored_and_old_files_are_trimmed() {
        val dir = tempDir()
        val cache = TranslationCache(dir, maxFiles = 3)
        val lines = listOf("Tum hi ho")
        File(dir, cache.key("x", lines) + ".json").apply { dir.mkdirs(); writeText("{broken") }
        assertNull(cache.get("x", lines))
        for (i in 0 until 6) {
            cache.put("song$i", lines, listOf("t$i"))
            File(dir, cache.key("song$i", lines) + ".json").setLastModified(1_000_000L + i * 10_000L)
        }
        cache.put("song6", lines, listOf("t6"))
        assertTrue((dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0) <= 3)
        assertEquals(listOf("t6"), TranslationCache(dir).get("song6", lines))
    }

    // ---------------------------------------------------------------- one call per song

    private fun sse(json: String): MockResponse {
        val chunk = MiniJson.parse(json, 1_000_000)
        val event = JsonOut.write(
            mapOf("candidates" to listOf(mapOf("content" to mapOf("role" to "model", "parts" to listOf(mapOf("text" to JsonOut.write(chunk)))))))
        )
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\n")
    }

    private fun translator(dir: File, timeout: Long = 10_000L) = LyricsTranslator(
        GeminiClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()),
        TranslationCache(dir),
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        timeout,
    )

    private fun ep(server: MockWebServer) = GeminiEndpoint(server.url("/gen").toString(), emptyMap(), ownKey = false, model = "test")

    @Test
    fun one_gemini_call_then_the_phone_copy_is_used() = runBlocking {
        val server = MockWebServer()
        server.enqueue(sse("""{"lines":[{"id":0,"en":"Only you"},{"id":1,"en":"Now, only you"},{"id":3,"en":""}]}"""))
        server.start()
        val dir = tempDir()
        val lines = listOf("Tum hi ho", "Ab tum hi ho", "♪", "I need you tonight")
        val t = translator(dir)
        val first = t.translate("song1", lines, "Tum Hi Ho", "Arijit Singh") { ep(server) }
        assertEquals(listOf("Only you", "Now, only you", null, null), first)
        assertEquals(1, server.requestCount)
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent.contains("responseSchema"))
        // again, and after a restart: no new call
        assertEquals(first, t.translate("song1", lines, null, null) { ep(server) })
        assertEquals(first, translator(dir).cached("song1", lines))
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun a_mismatched_answer_is_asked_for_once_more_then_fails() = runBlocking {
        val server = MockWebServer()
        server.enqueue(sse("""{"lines":[{"id":0,"en":"a"}]}"""))
        server.enqueue(sse("""{"lines":[{"id":0,"en":"a"}]}"""))
        server.start()
        val lines = listOf("Tum hi ho", "Ab tum hi ho", "Dil mera", "Kya hua")
        try {
            translator(tempDir()).translate("s", lines, null, null) { ep(server) }
            fail("should fail")
        } catch (e: GeminiException) {
            assertNotNull(e.problem)
        }
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test
    fun server_errors_and_timeouts_fail_instead_of_waiting_forever() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        server.enqueue(MockResponse().setHeadersDelay(3, TimeUnit.SECONDS).setBody("data: {}\n\n"))
        server.start()
        val dir = tempDir()
        val lines = listOf("Tum hi ho", "Ab tum hi ho")
        try {
            translator(dir).translate("s", lines, null, null) { ep(server) }
            fail("500 should fail")
        } catch (e: GeminiException) { /* expected */ }
        val started = System.currentTimeMillis()
        try {
            translator(dir, timeout = 500L).translate("s", lines, null, null) { ep(server) }
            fail("timeout should fail")
        } catch (e: GeminiException) {
            assertEquals(GeminiProblem.Busy, e.problem)
        }
        assertTrue(System.currentTimeMillis() - started < 2_900L)
        assertNull(TranslationCache(dir).get("s", lines))
        server.shutdown()
    }
}
