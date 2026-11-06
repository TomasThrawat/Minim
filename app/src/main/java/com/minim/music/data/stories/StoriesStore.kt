package com.minim.music.data.stories

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 5.7.0 u44: what stories keeps on the phone, in its own small prefs file: the added channels,
 * the last uploads fetched (so the tab fills at once) and when they were fetched. Updates keep it
 * (same app, same files).
 */
class StoriesStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    var channels: List<StoryChannel>
        get() = decodeChannels(prefs.getString(KEY_CHANNELS, null))
        private set(value) = prefs.edit().putString(KEY_CHANNELS, encodeChannels(value)).apply()

    /** Adds [channel] (first in the row). False when it was already there. */
    fun add(channel: StoryChannel): Boolean {
        val old = channels
        if (StoryChannelList.contains(old, channel)) return false
        channels = StoryChannelList.add(old, channel)
        return true
    }

    /** Removes the channel and its saved uploads. */
    fun remove(id: String) {
        channels = StoryChannelList.remove(channels, id)
        val left = uploads.filterNot { it.channelId == id }
        prefs.edit().putString(KEY_UPLOADS, encodeUploads(left)).apply()
    }

    fun contains(channel: StoryChannel) = StoryChannelList.contains(channels, channel)

    /** A fresher photo for a channel (search results can lack one). */
    fun updateAvatar(id: String, avatarUrl: String) {
        if (avatarUrl.isBlank()) return
        val list = channels
        if (list.none { it.id == id && it.avatarUrl != avatarUrl }) return
        channels = list.map { if (it.id == id) it.copy(avatarUrl = avatarUrl) else it }
    }

    val uploads: List<StoryUpload> get() = decodeUploads(prefs.getString(KEY_UPLOADS, null))

    val fetchedAt: Long get() = prefs.getLong(KEY_FETCHED_AT, 0L)

    fun saveUploads(list: List<StoryUpload>, at: Long) {
        prefs.edit().putString(KEY_UPLOADS, encodeUploads(list)).putLong(KEY_FETCHED_AT, at).apply()
    }

    /** u47: the last New to You list, so the chip shows something at once. */
    val newToYou: List<StoryUpload> get() = decodeUploads(prefs.getString(KEY_NEW_TO_YOU, null))
    val newToYouAt: Long get() = prefs.getLong(KEY_NEW_TO_YOU_AT, 0L)

    fun saveNewToYou(list: List<StoryUpload>, at: Long) {
        prefs.edit().putString(KEY_NEW_TO_YOU, encodeUploads(list)).putLong(KEY_NEW_TO_YOU_AT, at).apply()
    }

    companion object {
        const val FILE = "minim_stories"
        private const val KEY_NEW_TO_YOU = "new_to_you"
        private const val KEY_NEW_TO_YOU_AT = "new_to_you_at"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_UPLOADS = "uploads"
        private const val KEY_FETCHED_AT = "fetched_at"

        fun encodeChannels(list: List<StoryChannel>): String {
            val arr = JSONArray()
            for (c in list) {
                arr.put(JSONObject().put("id", c.id).put("name", c.name).put("url", c.url).put("avatar", c.avatarUrl))
            }
            return arr.toString()
        }

        fun decodeChannels(raw: String?): List<StoryChannel> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id")
                    if (id.isBlank()) null
                    else StoryChannel(id, o.optString("name"), o.optString("url"), o.optString("avatar"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun encodeUploads(list: List<StoryUpload>): String {
            val arr = JSONArray()
            for (u in list) {
                arr.put(
                    JSONObject().put("v", u.videoId).put("t", u.title).put("c", u.channelId)
                        .put("n", u.channelName).put("d", u.durationSec).put("p", u.publishedAt)
                )
            }
            return arr.toString()
        }

        fun decodeUploads(raw: String?): List<StoryUpload> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val v = o.optString("v")
                    if (v.isBlank()) null
                    else StoryUpload(v, o.optString("t"), o.optString("c"), o.optString("n"), o.optLong("d"), o.optLong("p"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
