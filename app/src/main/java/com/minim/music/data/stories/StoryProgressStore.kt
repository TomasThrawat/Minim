package com.minim.music.data.stories

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 5.7.0 u46: how far you got into each stories upload, in its own small prefs file (updates keep
 * it). Kept in memory and written in the background.
 */
class StoryProgressStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val map = LinkedHashMap<String, StoryPlayback>(decode(prefs.getString(KEY, null)))

    @Synchronized
    fun get(videoId: String): StoryPlayback? = map[videoId]

    @Synchronized
    fun all(): Map<String, StoryPlayback> = HashMap(map)

    /**
     * Saves how far [videoId] got. Returns true when that changed what the tab shows (it became
     * resumable, heard, or stopped being resumable), so the tab only redraws then.
     */
    @Synchronized
    fun record(videoId: String, positionMs: Long, durationMs: Long, ended: Boolean, now: Long): Boolean {
        if (videoId.isBlank()) return false
        val old = map[videoId]
        val new = StoryProgress.update(old, positionMs, durationMs, ended, now)
        map[videoId] = new
        val trimmed = StoryProgress.trim(map)
        if (trimmed.size != map.size) {
            map.clear()
            map.putAll(trimmed)
        }
        prefs.edit().putString(KEY, encode(map)).apply()
        return old == null && StoryProgress.isInProgress(new) ||
            old != null && (old.done != new.done || StoryProgress.isInProgress(old) != StoryProgress.isInProgress(new))
    }

    companion object {
        const val FILE = "minim_stories_progress"
        private const val KEY = "progress"

        fun encode(map: Map<String, StoryPlayback>): String {
            val o = JSONObject()
            for ((id, p) in map) {
                o.put(id, JSONObject().put("p", p.positionMs).put("d", p.durationMs).put("x", p.done).put("a", p.at))
            }
            return o.toString()
        }

        fun decode(raw: String?): Map<String, StoryPlayback> {
            if (raw.isNullOrBlank()) return emptyMap()
            return try {
                val o = JSONObject(raw)
                val out = LinkedHashMap<String, StoryPlayback>()
                for (id in o.keys()) {
                    val e = o.optJSONObject(id) ?: continue
                    out[id] = StoryPlayback(e.optLong("p"), e.optLong("d"), e.optBoolean("x"), e.optLong("a"))
                }
                out
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
}
