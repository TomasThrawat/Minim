package com.minim.music.data.account

import com.minim.music.data.model.Track
import com.minim.music.util.SafeRegex
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
import java.util.Locale

internal data class YouTubeMusicWebConfig(
    val apiKey: String,
    val clientVersion: String,
)

internal object YouTubeMusicWebConfigParser {
    fun parse(html: String): YouTubeMusicWebConfig? {
        fun value(name: String): String? = SafeRegex.of("""["]$name["]\s*:\s*["]([^"]+)["]""")
            .find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

        // Visitor/auth-user data belongs to the signed-in WebView session. It is not
        // guaranteed to be repeated in the standalone homepage configuration response.
        val apiKey = value("INNERTUBE_API_KEY") ?: return null
        val version = value("INNERTUBE_CLIENT_VERSION")
            ?: value("INNERTUBE_CONTEXT_CLIENT_VERSION")
            ?: return null
        return YouTubeMusicWebConfig(apiKey = apiKey, clientVersion = version)
    }
}

/** YouTube Music WEB_REMIX library client. Its library endpoints are not a public API. */
class YouTubeMusicApi(private val client: OkHttpClient) {
    @Volatile private var cachedConfig: YouTubeMusicWebConfig? = null
    @Volatile private var configLoadedAt = 0L

    /** Validates a cookie session against the signed-in account exposed by YouTube Music. */
    suspend fun validateSession(session: YouTubeMusicAccount.Session): String = withContext(Dispatchers.IO) {
        val response = post(session, webConfig(session), "account/account_menu", JSONObject())
        val header = findObject(response, "activeAccountHeaderRenderer")
            ?: throw IOException("YouTube Music did not confirm the signed-in account. Sign in again.")
        val accountName = header.optJSONObject("accountName")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
        val email = header.optJSONObject("email")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
        val name = accountName.ifBlank { email }
        if (name.isBlank()) {
            throw IOException("YouTube Music returned an empty account profile. Sign in again.")
        }
        name
    }


    suspend fun setLiked(session: YouTubeMusicAccount.Session, videoId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank())
        val config = webConfig(session)
        post(session, config, if (liked) "like/like" else "like/removelike", JSONObject().put("target", JSONObject().put("videoId", videoId)))
    }

    suspend fun fetchLikedSongs(session: YouTubeMusicAccount.Session): List<Track> = withContext(Dispatchers.IO) {
        val config = webConfig(session)
        val tracks = LinkedHashMap<String, Track>()
        var continuation: String? = null
        var sawLikedShelf = false
        repeat(MAX_PAGES) { page ->
            val payload = JSONObject()
            if (continuation.isNullOrBlank()) payload.put("browseId", LIKED_SONGS_BROWSE_ID) else payload.put("continuation", continuation)
            val response = post(session, config, "browse", payload)
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

    private fun webConfig(session: YouTubeMusicAccount.Session): YouTubeMusicWebConfig {
        if (session.apiKey.isNotBlank() && session.clientVersion.isNotBlank()) {
            return YouTubeMusicWebConfig(
                apiKey = session.apiKey,
                clientVersion = session.clientVersion,
            )
        }
        // Backward compatibility for encrypted sessions created before config capture
        // was moved into the signed-in WebView.
        return webConfig()
    }

    private fun webConfig(): YouTubeMusicWebConfig {
        val now = System.currentTimeMillis()
        cachedConfig?.let { if (now - configLoadedAt < CONFIG_TTL_MS) return it }
        val request = Request.Builder()
            .url(MUSIC_ORIGIN)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", System.getProperty("http.agent") ?: "Minim Android")
            .build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube Music config request failed (${response.code})")
            response.body?.string().orEmpty()
        }
        val parsed = YouTubeMusicWebConfigParser.parse(html)
            ?: throw IOException("YouTube Music homepage did not expose the WEB_REMIX API key and client version")
        cachedConfig = parsed
        configLoadedAt = now
        return parsed
    }

    private fun post(
        session: YouTubeMusicAccount.Session,
        config: YouTubeMusicWebConfig,
        endpoint: String,
        extra: JSONObject,
    ): JSONObject {
        val userContext = JSONObject().put("lockedSafetyMode", false)
        if (session.dataSyncId.isNotBlank()) {
            userContext.put("onBehalfOfUser", session.dataSyncId)
        }
        val context = JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", config.clientVersion)
                .put("hl", Locale.getDefault().language.ifBlank { "en" })
                .put("gl", Locale.getDefault().country.ifBlank { "US" })
                .put("visitorData", session.visitorData)
        ).put("user", userContext)
        val body = JSONObject().put("context", context)
        val keys = extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            body.put(key, extra.get(key))
        }
        val url = "$BASE/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("key", config.apiKey)
            .addQueryParameter("prettyPrint", "false")
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("Origin", MUSIC_ORIGIN)
            .header("X-Origin", MUSIC_ORIGIN)
            .header("Referer", "$MUSIC_ORIGIN/")
            .header(
                "Authorization",
                YouTubeMusicSessionAuth.authorizationHeader(session.cookie)
                    ?: throw IOException("The YouTube Music session is missing its authentication cookie. Sign in again."),
            )
            .header("Cookie", session.cookie)
            .header("X-Goog-AuthUser", session.authUser)
            .header("X-Goog-Visitor-Id", session.visitorData)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", config.clientVersion)
            .header("User-Agent", System.getProperty("http.agent") ?: "Minim Android")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = when (response.code) {
                    401, 403 -> "YouTube Music rejected the web session; reconnect your account"
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

    private fun findObject(root: JSONObject, wanted: String): JSONObject? {
        var found: JSONObject? = null
        walk(root) { key, value ->
            if (found == null && key == wanted && value is JSONObject) found = value
        }
        return found
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

    private fun parseDuration(value: String): Long {
        if (value.isBlank()) return 0L
        val parts = value.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.size !in 2..3) return 0L
        return parts.fold(0L) { total, part -> total * 60L + part }
    }

    companion object {
        private const val MUSIC_ORIGIN = "https://music.youtube.com"
        private const val BASE = "$MUSIC_ORIGIN/youtubei/v1"
        private const val LIKED_SONGS_BROWSE_ID = "FEmusic_liked_videos"
        private const val MAX_PAGES = 100
        private const val CONFIG_TTL_MS = 30L * 60L * 1000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
