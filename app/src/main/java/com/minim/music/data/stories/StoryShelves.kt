package com.minim.music.data.stories

/**
 * 5.7.0 u46: the stories tab as shelves instead of one long list, how far you got into each
 * upload (resume, "Continue listening", played ones going dim) and the length chips. Plain logic
 * (no Android), so it is unit tested.
 */

/** How far you got into one upload. [at] is when it was last saved (epoch ms). */
data class StoryPlayback(
    val positionMs: Long,
    val durationMs: Long,
    /** Heard to the end or past [StoryProgress.DONE_FRACTION]; stays set once set. */
    val done: Boolean,
    val at: Long
)

object StoryProgress {
    /** Played this far (or to the end), an upload counts as heard: it goes dim and starts over. */
    const val DONE_FRACTION = 0.9
    /** Less than this in, playing it again simply starts at the beginning. */
    const val RESUME_MIN_MS = 30_000L
    /** Resuming goes back a few seconds, so you catch the sentence you stopped in. */
    const val REWIND_MS = 3_000L
    /** How many uploads the phone remembers (the oldest are dropped). */
    const val MAX_ENTRIES = 500

    /** True when [positionMs] of [durationMs] is far enough to count as heard. */
    fun reachedEnd(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= (durationMs * DONE_FRACTION).toLong()

    /**
     * The new saved state after hearing up to [positionMs]. [ended] when the player moved on by
     * itself (the upload finished). Being done is sticky: playing a heard upload again doesn't
     * bring it back to Continue listening.
     */
    fun update(old: StoryPlayback?, positionMs: Long, durationMs: Long, ended: Boolean, now: Long): StoryPlayback {
        val dur = if (durationMs > 0) durationMs else old?.durationMs ?: 0L
        val pos = positionMs.coerceAtLeast(0L)
        val done = (old?.done == true) || ended || reachedEnd(pos, dur)
        return StoryPlayback(pos, dur, done, now)
    }

    /** Where to start playing an upload: where you stopped (a little before), or the beginning. */
    fun resumeMs(p: StoryPlayback?): Long {
        if (p == null || p.done || p.positionMs < RESUME_MIN_MS) return 0L
        if (p.durationMs > 0 && reachedEnd(p.positionMs, p.durationMs)) return 0L
        return (p.positionMs - REWIND_MS).coerceAtLeast(0L)
    }

    /** Half-finished: started (past [RESUME_MIN_MS]) and not heard to the end. */
    fun isInProgress(p: StoryPlayback?): Boolean = resumeMs(p) > 0L

    /** How much of it is heard, 0..1 (the thin line under a Continue listening card). */
    fun fraction(p: StoryPlayback?): Float {
        if (p == null || p.durationMs <= 0) return 0f
        if (p.done) return 1f
        return (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
    }

    /** Minutes left (at least 1), for "12 min left". */
    fun minutesLeft(p: StoryPlayback?, durationSec: Long): Int {
        val dur = if (p != null && p.durationMs > 0) p.durationMs else durationSec * 1000L
        val left = (dur - (p?.positionMs ?: 0L)).coerceAtLeast(0L)
        return ((left + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }

    /** Keeps the newest [max] entries. */
    fun trim(map: Map<String, StoryPlayback>, max: Int = MAX_ENTRIES): Map<String, StoryPlayback> {
        if (map.size <= max) return map
        return map.entries.sortedByDescending { it.value.at }.take(max).associate { it.key to it.value }
    }
}

/** The length chips: none, "Under 20 min" or "30+ min". Tapping the one that's on clears it. */
enum class StoryLength {
    ALL, SHORT, LONG;

    fun matches(durationSec: Long): Boolean = when (this) {
        ALL -> true
        SHORT -> durationSec in 1 until SHORT_MAX_SEC
        LONG -> durationSec >= LONG_MIN_SEC
    }

    /** The chip after tapping [chip] while this one is on. */
    fun tap(chip: StoryLength): StoryLength = if (chip == this) ALL else chip

    companion object {
        const val SHORT_MAX_SEC = 20 * 60L
        const val LONG_MIN_SEC = 30 * 60L
    }
}

/** One shelf: Continue listening, New this week, or one channel's uploads. */
data class StoryShelf(
    val kind: Kind,
    /** The channel for [Kind.CHANNEL]; blank otherwise. */
    val channelId: String,
    /** The channel name for [Kind.CHANNEL]; the UI names the other two. */
    val title: String,
    val uploads: List<StoryUpload>
) {
    enum class Kind { CONTINUE, NEW, CHANNEL }

    /** Stable key for the row (its scroll position is kept by this). */
    val key: String get() = if (kind == Kind.CHANNEL) "ch:$channelId" else kind.name
}

object StoryShelves {
    const val WEEK_MS = 7 * 86_400_000L
    /** Cards in one sideways row; the row title opens everything from that channel. */
    const val MAX_PER_SHELF = 20
    const val MAX_CONTINUE = 15

    /**
     * The shelves, top to bottom: Continue listening (half-finished, last played first), New this
     * week (posted in the last 7 days, newest first), then one row per channel in the channel
     * row's order. [uploads] is the merged feed (newest first). Empty shelves are left out, and
     * [length] filters every shelf.
     */
    fun build(
        uploads: List<StoryUpload>,
        channels: List<StoryChannel>,
        progress: Map<String, StoryPlayback>,
        length: StoryLength,
        now: Long
    ): List<StoryShelf> {
        val ids = channels.map { it.id }.toSet()
        val feed = uploads.filter { it.channelId in ids && length.matches(it.durationSec) }
        val out = ArrayList<StoryShelf>(channels.size + 2)

        val cont = feed.filter { StoryProgress.isInProgress(progress[it.videoId]) }
            .sortedByDescending { progress[it.videoId]?.at ?: 0L }
            .take(MAX_CONTINUE)
        if (cont.isNotEmpty()) out.add(StoryShelf(StoryShelf.Kind.CONTINUE, "", "", cont))

        val fresh = feed.filter { it.publishedAt > 0L && now - it.publishedAt in 0 until WEEK_MS }
            .sortedByDescending { it.publishedAt }
            .take(MAX_PER_SHELF)
        if (fresh.isNotEmpty()) out.add(StoryShelf(StoryShelf.Kind.NEW, "", "", fresh))

        for (c in channels) {
            val mine = feed.filter { it.channelId == c.id }.take(MAX_PER_SHELF)
            if (mine.isNotEmpty()) out.add(StoryShelf(StoryShelf.Kind.CHANNEL, c.id, c.name, mine))
        }
        return out
    }

    /** Every upload of one channel (newest first), filtered by [length]: the list a row title opens. */
    fun channelList(uploads: List<StoryUpload>, channelId: String, length: StoryLength): List<StoryUpload> =
        uploads.filter { it.channelId == channelId && length.matches(it.durationSec) }

    /** True when a heard upload should show dimmed. */
    fun isDim(p: StoryPlayback?): Boolean = p?.done == true
}
