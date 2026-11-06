package com.minim.music.data.artists

import android.content.Context
import com.minim.music.data.model.ArtistCountKind
import com.minim.music.data.model.ArtistRef
import com.minim.music.data.source.ArtistAudience
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * 5.7.0 u48: liked artists.
 *
 * Kept on its own (not inside Liked Songs or the tapes store) so a future Artist tab can read the
 * same list as it is: [LikedArtistsStore.artists] is a StateFlow of every liked artist, newest
 * first, with everything a row or a tile needs (name, photo, the count line) and the ArtistRef
 * that opens the Artist page. The rules live in [LikedArtistList], with no Android in them.
 */

/** One liked artist: enough to draw a row or tile without going online, and to open the page. */
data class LikedArtist(
    val key: String,
    val url: String,
    val name: String,
    val thumbnailUrl: String,
    val count: Long,
    val countKind: ArtistCountKind,
    val likedAt: Long
) {
    /** What the Artist page opens with. */
    fun toRef(): ArtistRef = ArtistRef(url = url, name = name, count = count, thumbnailUrl = thumbnailUrl, countKind = countKind)

    companion object {
        fun from(ref: ArtistRef, likedAt: Long): LikedArtist = LikedArtist(
            key = LikedArtistList.keyOf(ref.url, ref.name),
            url = ref.url,
            name = ref.name,
            thumbnailUrl = ref.thumbnailUrl,
            count = ref.count,
            countKind = ref.countKind,
            likedAt = likedAt
        )
    }
}

/** The pure rules: one entry per artist, newest like first. */
object LikedArtistList {

    /**
     * The same artist always gets the same key, whichever link it was opened from: the channel id
     * (UC...) when the link has one, else the link without its host, query or trailing slash,
     * else the name.
     */
    fun keyOf(url: String?, name: String?): String {
        ArtistAudience.channelId(url)?.let { return "ch:$it" }
        val u = url?.trim().orEmpty()
        if (u.isNotEmpty()) {
            val noScheme = u.substringAfter("://")
            val path = noScheme.substringAfter('/', "").substringBefore('?').substringBefore('#').trimEnd('/')
            if (path.isNotEmpty()) return "url:" + path.lowercase(Locale.ROOT)
        }
        return "name:" + name?.trim()?.lowercase(Locale.ROOT).orEmpty()
    }

    fun contains(list: List<LikedArtist>, key: String): Boolean = list.any { it.key == key }

    /** Likes [artist] (it goes to the top), or unlikes it when it's already there. */
    fun toggle(list: List<LikedArtist>, artist: LikedArtist): List<LikedArtist> =
        if (contains(list, artist.key)) list.filterNot { it.key == artist.key }
        else listOf(artist) + list

    /**
     * A liked artist's page loaded again: keep the fresher photo and count, but not the place in
     * the list or when it was liked. Unchanged when the artist isn't liked.
     */
    fun refresh(list: List<LikedArtist>, ref: ArtistRef): List<LikedArtist> {
        val key = keyOf(ref.url, ref.name)
        return list.map { a ->
            if (a.key != key) a
            else a.copy(
                name = ref.name.ifBlank { a.name },
                thumbnailUrl = ref.thumbnailUrl.ifBlank { a.thumbnailUrl },
                count = if (ref.count >= 0) ref.count else a.count,
                countKind = if (ref.count >= 0) ref.countKind else a.countKind
            )
        }
    }

    fun encode(list: List<LikedArtist>): String {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("k", a.key)
                    .put("u", a.url)
                    .put("n", a.name)
                    .put("t", a.thumbnailUrl)
                    .put("c", a.count)
                    .put("ck", a.countKind.name)
                    .put("at", a.likedAt)
            )
        }
        return arr.toString()
    }

    /** Never throws: a broken entry is skipped, a broken file is an empty list. */
    fun decode(json: String?): List<LikedArtist> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<LikedArtist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("u")
            val name = o.optString("n")
            if (url.isBlank() || name.isBlank()) continue
            val kind = try { ArtistCountKind.valueOf(o.optString("ck")) } catch (e: Exception) { ArtistCountKind.UNKNOWN }
            out.add(
                LikedArtist(
                    key = o.optString("k").ifBlank { keyOf(url, name) },
                    url = url,
                    name = name,
                    thumbnailUrl = o.optString("t"),
                    count = o.optLong("c", -1L),
                    countKind = kind,
                    likedAt = o.optLong("at", 0L)
                )
            )
        }
        return out.distinctBy { it.key }
    }
}

/** Liked artists, saved on the phone (survives restarts and updates). */
class LikedArtistsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(LikedArtistList.decode(prefs.getString(KEY_LIST, null)))

    /** Every liked artist, newest first. */
    val artists: StateFlow<List<LikedArtist>> = state.asStateFlow()

    fun isLiked(ref: ArtistRef): Boolean = LikedArtistList.contains(state.value, LikedArtistList.keyOf(ref.url, ref.name))

    /** Likes or unlikes the artist; returns whether it's liked now. */
    @Synchronized
    fun toggle(ref: ArtistRef, now: Long = System.currentTimeMillis()): Boolean {
        val next = LikedArtistList.toggle(state.value, LikedArtist.from(ref, now))
        save(next)
        return LikedArtistList.contains(next, LikedArtistList.keyOf(ref.url, ref.name))
    }

    /** Fresher photo and count for an artist that's already liked; nothing otherwise. */
    @Synchronized
    fun refresh(ref: ArtistRef) {
        if (!isLiked(ref)) return
        val next = LikedArtistList.refresh(state.value, ref)
        if (next != state.value) save(next)
    }

    private fun save(list: List<LikedArtist>) {
        state.value = list
        prefs.edit().putString(KEY_LIST, LikedArtistList.encode(list)).apply()
    }

    companion object {
        const val PREFS = "minim_liked_artists"
        private const val KEY_LIST = "artists_v1"
    }
}
