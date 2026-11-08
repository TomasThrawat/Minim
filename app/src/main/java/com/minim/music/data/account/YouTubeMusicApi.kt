package com.minim.music.data.account

import com.minim.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Small WEB_REMIX client for the signed-in YouTube Music library endpoints. */
class YouTubeMusicApi(private val client: OkHttpClient) {
    suspend fun setLiked(session: YouTubeMusicAccount.Session, videoId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank())
        post(session, if (liked) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    }

    suspend fun fetchLikedSongs(session: YouTubeMusicAccount.Session): List<Track> = withContext(Dispatchers.IO) {
        val tracks = LinkedHashMap<String, Track>()
        var continuation: String? = null
        var sawLikedShelf = false
        repeat(MAX_PAGES) { page ->
            val payload = JSONObject()
            if (continuation.isNullOrBlank()) payload.put("browseId", LIKED_SONGS_BROWSE_ID) else payload.put("continuation", continuation)
            val response = post(session, "browse", payload)
            if (containsKey(response, "musicShelfRenderer") || containsKey(response, "musicPlaylistShelfRenderer")) sawLikedShelf = true
            collectTracks(response, tracks)
            continuation = findContinuation(response)
            if (continuation.isNullOrBlank()) {
                if (!sawLikedShelf) throw IOException("YouTube Music returned an unrecognized liked-songs response")
                return@withContext tracks.values.toList()
            }
            if (page == MAX_PAGES - 1) throw IOException("YouTube Music liked songs are still paginated")
        }
        throw IOException("YouTube Music liked songs could not be fully loaded")
    }

    private fun post(session: YouTubeMusicAccount.Session, endpoint: String, extra: JSONObject): JSONObject {
        val context = JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", session.clientVersion)
                .put("hl", Locale.getDefault().language.ifBlank { "en" })
                .put("gl", Locale.getDefault().country.ifBlank { "US" })
                .put("visitorData", session.visitorData)
        ).put("user", JSONObject().put("lockedSafetyMode", false))
        val body = JSONObject().put("context", context)
        val keys = extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            body.put(key, extra.get(key))
        }

        val url = "$BASE/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("key", session.apiKey)
            .addQueryParameter("prettyPrint", "false")
            .build()
        val timestamp = System.currentTimeMillis() / 1000L
        val sapisid = cookieValue(session.cookie, "SAPISID")
            ?: cookieValue(session.cookie, "__Secure-3PAPISID")
            ?: throw IOException("YouTube Music sign-in expired; sign in again")
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $ORIGIN".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("Cookie", session.cookie)
            .header("Authorization", "SAPISIDHASH ${timestamp}_$digest")
            .header("X-Goog-AuthUser", session.authUser.ifBlank { "0" })
            .header("X-Goog-Visitor-Id", session.visitorData)
            .apply { if (session.dataSyncId.isNotBlank()) header("X-Goog-PageId", session.dataSyncId) }
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", session.clientVersion)
            .header("User-Agent", session.userAgent)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = when (response.code) {
                    401, 403 -> "YouTube Music rejected the session; sign in again"
                    429 -> "YouTube Music is receiving too many requests; try again later"
                    else -> "YouTube Music request failed (${response.code})"
                }
                throw IOException(reason)
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun collectTracks(root: JSONObject, out: MutableMap<String, Track>) {
        walk(root) { key, value ->
            if (key == "musicResponsiveListItemRenderer" && value is JSONObject) {
                parseTrack(value)?.let { out.putIfAbsent(it.id, it) }
            }
        }
    }

    private fun parseTrack(renderer: JSONObject): Track? {
        val columns = renderer.optJSONArray("flexColumns") ?: return null
        val title = runText(columns.optJSONObject(0)).ifBlank { return null }
        val id = renderer.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: findVideoId(columns.optJSONObject(0))
            ?: findVideoId(renderer.optJSONObject("overlay"))
            ?: return null
        val artistRuns = columns.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")
        val artist = artistRuns?.let { runs ->
            (0 until runs.length()).mapNotNull { runs.optJSONObject(it)?.optString("text") }
                .filter { it.isNotBlank() && it != "•" && it != "·" }
                .distinct().joinToString(", ")
        }.orEmpty().ifBlank { "Unknown Artist" }
        val fixed = renderer.optJSONArray("fixedColumns")?.optJSONObject(0)
        val duration = parseDuration(runText(fixed))
        val thumbs = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbnail = thumbs?.let { arr -> arr.optJSONObject(arr.length() - 1)?.optString("url") }.orEmpty()
        return Track(
            id = id,
            title = title,
            artist = artist,
            durationSec = duration,
            thumbnailUrl = thumbnail,
            sourceUrl = "https://www.youtube.com/watch?v=$id",
        )
    }

    private fun runText(column: JSONObject?): String = column
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)
        ?.optString("text").orEmpty()

    private fun findVideoId(root: JSONObject?): String? {
        var found: String? = null
        walk(root) { key, value ->
            if (key == "videoId" && value is String && value.isNotBlank() && found == null) found = value
        }
        return found
    }

    private fun findContinuation(root: JSONObject): String? {
        var token: String? = null
        walk(root) { key, value ->
            if (token == null && (key == "nextContinuationData" || key == "continuationCommand") && value is JSONObject) {
                token = value.optString("continuation").takeIf { it.isNotBlank() }
                    ?: value.optString("token").takeIf { it.isNotBlank() }
            }
        }
        return token
    }

    private fun containsKey(root: JSONObject, wanted: String): Boolean {
        var found = false
        walk(root) { key, _ -> if (key == wanted) found = true }
        return found
    }

    private fun walk(root: Any?, visit: (String, Any?) -> Unit) {
        when (root) {
            is JSONObject -> {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = root.opt(key)
                    visit(key, value)
                    walk(value, visit)
                }
            }
            is JSONArray -> for (i in 0 until root.length()) walk(root.opt(i), visit)
        }
    }

    private fun cookieValue(cookie: String, key: String): String? = cookie.split(';').asSequence()
        .map { it.trim() }.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }

    private fun parseDuration(value: String): Long {
        if (value.isBlank()) return 0L
        val parts = value.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.size !in 2..3) return 0L
        return parts.fold(0L) { total, part -> total * 60L + part }
    }

    companion object {
        private const val ORIGIN = "https://music.youtube.com"
        private const val BASE = "$ORIGIN/youtubei/v1"
        private const val LIKED_SONGS_BROWSE_ID = "FEmusic_liked_videos"
        private const val MAX_PAGES = 100
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
