package com.minim.music.data.ai

import com.minim.music.data.greetings.MiniJson
import com.minim.music.data.local.LibraryDatabase.TrackStats
import com.minim.music.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 5.7.0: the Gemini shelf on the Search page.
 *
 * Once a day, with the same Gemini setup as the chat and lyrics translation (the user's own key
 * from Settings, else the built-in one), Gemini reads a short summary of what they play (top songs and artists, recent plays, recent likes) and picks
 * songs for one shelf, and writes the shelf's title itself. Each pick is then found on YouTube
 * Music with the playlist import's matcher; picks that can't be found surely are dropped.
 */

/** What Gemini is told about the user's listening. All lists are "Title by Artist" lines. */
data class GeminiTaste(
    val topSongs: List<String>,
    val topArtists: List<String>,
    val recent: List<String>,
    val liked: List<String>,
    /** Songs already played a lot; Gemini is asked not to pick these. */
    val known: List<String>,
)

/** One pick as Gemini sent it. */
data class GeminiPick(val title: String, val artist: String)

/** Gemini's answer, cleaned: the shelf title it wrote and its picks in order. */
data class GeminiShelfAnswer(val title: String, val picks: List<GeminiPick>)

object GeminiShelfPrompt {

    /** Bump when the prompt changes enough that today's saved shelf should be redone. */
    const val VERSION = 1

    /** Songs asked for (more than the shelf shows, since some won't be found). */
    const val ASK_FOR = 18

    /** Longest shelf title kept; Gemini is asked for far less. */
    const val MAX_TITLE = 32

    /** Used when Gemini's title is missing or unusable. */
    const val FALLBACK_TITLE = "Picked by Gemini"

    val SYSTEM_PROMPT: String = """
You pick songs for one shelf in Minim, a minimal music player that plays songs from YouTube Music. You get a short summary of what the listener plays, and you return one shelf: a title and a list of songs.

How to pick:
- Pick songs this listener will love, based on the languages, moods, eras, genres and artists in their listening. Lean towards songs they have not played yet: deeper cuts from artists they love, and songs from artists close to them.
- Never pick a song from the "Already played a lot" list.
- Only pick real, released songs that are on YouTube Music. Give the song's real title and its main artist exactly as YouTube Music writes them. Never invent songs.
- At most 3 songs by the same artist. No remixes, covers, lofi, slowed or reverb versions unless the listener clearly plays those.
- Order the songs so the shelf flows well, best picks first.

The shelf title:
- Write a short, catchy title for this shelf that says what ties these songs together, in the listener's vibe. 2 to 5 words, at most 28 characters.
- Make it feel personal and specific to these songs, not generic. Never write "Recommended", "For you", "Songs you may like" or "Gemini picks".
- Plain text: no emojis, no quotation marks, no hashtags, no full stop at the end.

Output rules:
- Answer only with JSON that follows the schema.
- Return exactly ${ASK_FOR} songs.
""".trim()

    /** `{"title": "...", "songs": [{"title": "...", "artist": "..."}, ...]}` */
    val responseSchema: Map<String, Any?> = linkedMapOf(
        "type" to "OBJECT",
        "properties" to linkedMapOf(
            "title" to mapOf("type" to "STRING"),
            "songs" to linkedMapOf(
                "type" to "ARRAY",
                "items" to linkedMapOf(
                    "type" to "OBJECT",
                    "properties" to linkedMapOf(
                        "title" to mapOf("type" to "STRING"),
                        "artist" to mapOf("type" to "STRING"),
                    ),
                    "required" to listOf("title", "artist"),
                    "propertyOrdering" to listOf("title", "artist"),
                ),
            ),
        ),
        "required" to listOf("title", "songs"),
        "propertyOrdering" to listOf("title", "songs"),
    )

    fun userText(t: GeminiTaste): String {
        val sb = StringBuilder()
        fun section(name: String, lines: List<String>) {
            if (lines.isEmpty()) return
            sb.append(name).append(":\n")
            lines.forEach { sb.append("- ").append(it).append('\n') }
            sb.append('\n')
        }
        section("Most played songs", t.topSongs)
        section("Most played artists", t.topArtists)
        section("Played recently", t.recent)
        section("Liked recently", t.liked)
        section("Already played a lot (don't pick these)", t.known)
        sb.append("Pick ").append(ASK_FOR).append(" songs for today's shelf and give it a title.")
        return sb.toString()
    }

    fun requestBody(t: GeminiTaste): String = JsonOut.write(
        linkedMapOf(
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to SYSTEM_PROMPT))),
            "contents" to listOf(
                mapOf("role" to "user", "parts" to listOf(mapOf("text" to userText(t))))
            ),
            "generationConfig" to linkedMapOf(
                "responseMimeType" to "application/json",
                "responseSchema" to responseSchema,
                "temperature" to 1.0,
                "maxOutputTokens" to 8192,
            ),
        )
    )

    /** "Title by Artist" */
    fun line(track: Track): String = "${track.title.trim()} by ${track.artist.trim()}"

    /**
     * The summary Gemini gets, from the song stats: up to 15 top songs, 8 top artists (worked
     * out by the caller), 15 recent plays, 15 recent likes and 40 songs not to pick. Songs in
     * [blocked] ("Not interested") never go in.
     */
    fun taste(stats: List<TrackStats>, topArtists: List<String>, blocked: Set<String>): GeminiTaste {
        val usable = stats.filter { it.track.id !in blocked && it.track.title.isNotBlank() }
        val played = usable.filter { it.playCount > 0 || it.lastPlayedAt > 0 }
        val byPlays = played.sortedWith(compareByDescending<TrackStats> { it.playCount }.thenByDescending { it.lastPlayedAt })
        val top = byPlays.take(15).map { line(it.track) }
        val recent = played.sortedByDescending { it.lastPlayedAt }.take(15).map { line(it.track) }
        val liked = usable.filter { it.isLiked }.sortedByDescending { it.likedAt }.take(15).map { line(it.track) }
        val known = byPlays.take(40).map { line(it.track) }
        return GeminiTaste(top, topArtists.take(8), recent, liked, known)
    }
}

object GeminiShelfParser {

    /** Gemini's JSON -> title and picks; null when it isn't the JSON asked for or has no songs. */
    fun parse(raw: String): GeminiShelfAnswer? {
        val text = LyricsTranslationParser.stripFences(raw)
        val root = try {
            MiniJson.parse(text)
        } catch (_: Exception) {
            return null
        } as? Map<*, *> ?: return null
        val songs = root["songs"] as? List<*> ?: return null
        val seen = HashSet<String>()
        val picks = ArrayList<GeminiPick>()
        for (s in songs) {
            val m = s as? Map<*, *> ?: continue
            val title = cleanText(m["title"] as? String ?: continue) ?: continue
            val artist = cleanText(m["artist"] as? String ?: "") ?: ""
            val key = norm(title) + "|" + norm(artist)
            if (!seen.add(key)) continue
            picks += GeminiPick(title, artist)
            if (picks.size >= GeminiShelfPrompt.ASK_FOR + 6) break
        }
        if (picks.isEmpty()) return null
        return GeminiShelfAnswer(cleanTitle(root["title"] as? String), picks)
    }

    /**
     * The shelf title as shown: one line, no wrapping quotes, emojis or full stop, cut at a word
     * when too long. Generic or empty titles become [GeminiShelfPrompt.FALLBACK_TITLE].
     */
    fun cleanTitle(raw: String?): String {
        var t = raw?.replace('\n', ' ')?.replace('\r', ' ')?.trim() ?: return GeminiShelfPrompt.FALLBACK_TITLE
        // Emojis and other pictographs (anything outside the Basic Multilingual Plane, plus the
        // common symbol blocks)
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val drop = Character.charCount(cp) > 1 || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D || cp == '#'.code
            if (!drop) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        t = sb.toString()
        while (t.contains("  ")) t = t.replace("  ", " ")
        t = t.trim()
        val pairs = listOf('"' to '"', '“' to '”', '\'' to '\'', '‘' to '’', '*' to '*')
        for ((a, b) in pairs) {
            if (t.length >= 2 && t.first() == a && t.last() == b) t = t.substring(1, t.length - 1).trim()
        }
        t = t.trimEnd('.', ' ')
        if (t.length > GeminiShelfPrompt.MAX_TITLE) {
            val cut = t.substring(0, GeminiShelfPrompt.MAX_TITLE)
            val space = cut.lastIndexOf(' ')
            t = (if (space >= 8) cut.substring(0, space) else cut).trimEnd(',', ';', ':', '-', ' ')
        }
        if (t.count { Character.isLetter(it) } < 2) return GeminiShelfPrompt.FALLBACK_TITLE
        if (norm(t) in GENERIC) return GeminiShelfPrompt.FALLBACK_TITLE
        return t
    }

    private val GENERIC = setOf(
        "recommended", "recommendations", "foryou", "songsforyou", "songsyoumaylike",
        "geminipicks", "pickedbygemini", "shelf", "title", "playlist", "mix"
    )

    private fun cleanText(raw: String): String? {
        var t = raw.replace('\n', ' ').trim()
        while (t.contains("  ")) t = t.replace("  ", " ")
        if (t.isEmpty() || t.length > 120) return null
        return t
    }

    internal fun norm(s: String): String = s.lowercase().filter { Character.isLetterOrDigit(it) }
}

/** Today's Gemini shelf as saved on the phone. */
data class GeminiShelfDay(
    val day: Long,
    val title: String,
    val tracks: List<Track>,
    /** A hash of the key and model it was made with: a new key makes a fresh shelf. */
    val keyHash: Int,
)

/**
 * Makes and keeps the Gemini shelf: one Gemini call a day at most (a failed day is tried again
 * after [RETRY_MS]), saved in [file] so it shows at once after a restart.
 */
class GeminiShelfMaker(
    private val client: GeminiClient,
    private val file: File,
    private val scope: CoroutineScope,
) {

    @Volatile private var lastFailAt = 0L
    @Volatile private var lastFailDay = -1L
    @Volatile private var memory: GeminiShelfDay? = null
    private var running: kotlinx.coroutines.Deferred<GeminiShelfDay?>? = null

    /** The saved shelf, whatever day it's from (null when there's none). */
    suspend fun saved(): GeminiShelfDay? = memory ?: withContext(Dispatchers.IO) { read() }?.also { memory = it }

    /** True when [saved] needs redoing: another day, another key, or none yet. */
    fun isStale(s: GeminiShelfDay?, today: Long, keyHash: Int): Boolean =
        s == null || s.day != today || s.keyHash != keyHash

    /**
     * Today's shelf: the saved one when it's from today with this key, else a new one from
     * Gemini. Null when Gemini fails (tried again after [RETRY_MS]) or too few picks were found.
     */
    suspend fun today(
        today: Long,
        keyHash: Int,
        taste: suspend () -> GeminiTaste,
        endpoint: suspend () -> GeminiEndpoint,
        find: suspend (GeminiPick) -> Track?,
        skip: (Track) -> Boolean,
        now: Long = System.currentTimeMillis(),
    ): GeminiShelfDay? {
        val saved = saved()
        if (!isStale(saved, today, keyHash)) return saved
        if (lastFailDay == today && now - lastFailAt < RETRY_MS) return null
        val job = synchronized(this) {
            running?.takeIf { it.isActive } ?: scope.async {
                try {
                    val made = withTimeout(TIMEOUT_MS) { make(today, keyHash, taste, endpoint, find, skip) }
                    if (made == null) {
                        lastFailDay = today; lastFailAt = System.currentTimeMillis()
                    } else {
                        memory = made
                        withContext(Dispatchers.IO) { write(made) }
                    }
                    made
                } catch (e: kotlinx.coroutines.CancellationException) {
                    lastFailDay = today; lastFailAt = System.currentTimeMillis()
                    if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
                } catch (e: Exception) {
                    lastFailDay = today; lastFailAt = System.currentTimeMillis()
                    null
                }
            }.also { running = it }
        }
        return job.await()
    }

    private suspend fun make(
        today: Long,
        keyHash: Int,
        taste: suspend () -> GeminiTaste,
        endpoint: suspend () -> GeminiEndpoint,
        find: suspend (GeminiPick) -> Track?,
        skip: (Track) -> Boolean,
    ): GeminiShelfDay? {
        val t = taste()
        if (t.topSongs.isEmpty() && t.liked.isEmpty()) return null
        val ep = withContext(Dispatchers.IO) { endpoint() }
        val body = GeminiShelfPrompt.requestBody(t)
        var answer: GeminiShelfAnswer? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val turn = scope.async { client.stream(ep, body) {} }.await()
            if (turn.blocked && turn.text.isBlank()) return null
            answer = GeminiShelfParser.parse(turn.text)
            if (answer != null) break
        }
        val a = answer ?: return null
        val tracks = resolve(a.picks, find, skip)
        if (tracks.size < MIN_TRACKS) return null
        return GeminiShelfDay(today, a.title, tracks, keyHash)
    }

    /** Finds the picks a few at a time, keeping Gemini's order; repeats and skipped songs go. */
    private suspend fun resolve(
        picks: List<GeminiPick>,
        find: suspend (GeminiPick) -> Track?,
        skip: (Track) -> Boolean,
    ): List<Track> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        val found = picks.map { p ->
            async { gate.withPermit { try { find(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } } }
        }.awaitAll()
        pickTracks(found, skip)
    }

    // Saved with the app's own small JSON writer and reader (no org.json), like the translations
    private fun write(s: GeminiShelfDay) {
        try {
            val obj = linkedMapOf(
                "v" to GeminiShelfPrompt.VERSION, "day" to s.day, "title" to s.title, "key" to s.keyHash,
                "tracks" to s.tracks.map { t ->
                    linkedMapOf(
                        "id" to t.id, "title" to t.title, "artist" to t.artist,
                        "d" to t.durationSec, "th" to t.thumbnailUrl, "src" to t.sourceUrl
                    )
                }
            )
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JsonOut.write(obj))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
        }
    }

    private fun read(): GeminiShelfDay? = try {
        if (!file.exists()) null else {
            val o = MiniJson.parse(file.readText()) as Map<*, *>
            if ((o["v"] as? Number)?.toInt() != GeminiShelfPrompt.VERSION) null else {
                val tracks = (o["tracks"] as List<*>).mapNotNull { x ->
                    val t = x as? Map<*, *> ?: return@mapNotNull null
                    val id = t["id"] as? String ?: return@mapNotNull null
                    Track(
                        id = id, title = t["title"] as? String ?: "", artist = t["artist"] as? String ?: "",
                        durationSec = (t["d"] as? Number)?.toLong() ?: 0L,
                        thumbnailUrl = t["th"] as? String ?: "", sourceUrl = t["src"] as? String ?: ""
                    )
                }
                val title = (o["title"] as? String).orEmpty().ifBlank { GeminiShelfPrompt.FALLBACK_TITLE }
                GeminiShelfDay((o["day"] as Number).toLong(), title, tracks, (o["key"] as? Number)?.toInt() ?: 0)
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Forgets the saved shelf (the key was removed). */
    fun clear() {
        memory = null
        lastFailDay = -1L
        try { file.delete() } catch (_: Exception) {}
    }

    companion object {
        const val TIMEOUT_MS = 90_000L
        const val RETRY_MS = 60 * 60_000L
        const val MAX_ATTEMPTS = 2
        const val PARALLEL = 4
        /** Fewer found songs than this and there's no shelf today. */
        const val MIN_TRACKS = 4
        /** The shelf shows at most this many. */
        const val MAX_TRACKS = 15

        /** Found tracks in order: no misses, repeats or skipped songs, at most [MAX_TRACKS]. */
        fun pickTracks(found: List<Track?>, skip: (Track) -> Boolean): List<Track> {
            val ids = HashSet<String>()
            val out = ArrayList<Track>()
            for (t in found) {
                if (t == null || skip(t) || !ids.add(t.id)) continue
                out += t
                if (out.size >= MAX_TRACKS) break
            }
            return out
        }

        /** Identifies the key and model a shelf was made with, without saving the key itself. */
        fun keyHash(apiKey: String, model: String): Int = (apiKey.takeLast(8) + "|" + model).hashCode()
    }
}
