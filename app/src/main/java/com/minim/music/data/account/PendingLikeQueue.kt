package com.minim.music.data.account

/** Pure account-routing and confirmation rules for queued YouTube Music like changes. */
internal object PendingLikeQueue {
    const val UNASSIGNED_ACCOUNT = "unassigned"

    data class Change(val videoId: String, val liked: Boolean)

    fun ownerForChange(activeAccountKey: String?, lastAccountKey: String?): String =
        activeAccountKey ?: lastAccountKey ?: UNASSIGNED_ACCOUNT

    fun preferenceKey(owner: String): String = "pending_likes_$owner"

    fun parse(entry: String): Change? {
        val separator = entry.lastIndexOf('|')
        if (separator <= 0) return null
        val liked = when (entry.substring(separator + 1)) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val videoId = entry.substring(0, separator)
        return videoId.takeIf { it.isNotBlank() }?.let { Change(it, liked) }
    }

    /** A queued mutation is complete only when the remote library reflects its desired state. */
    fun isConfirmed(entry: String, remoteIds: Set<String>): Boolean? =
        parse(entry)?.let { change -> (change.videoId in remoteIds) == change.liked }
}
