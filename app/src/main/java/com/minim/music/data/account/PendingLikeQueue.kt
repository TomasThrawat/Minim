package com.minim.music.data.account

/** Pure account-routing rules for likes changed while the app is offline or signed out. */
internal object PendingLikeQueue {
    const val UNASSIGNED_ACCOUNT = "unassigned"

    fun ownerForChange(activeAccountKey: String?, lastAccountKey: String?): String =
        activeAccountKey ?: lastAccountKey ?: UNASSIGNED_ACCOUNT

    fun preferenceKey(owner: String): String = "pending_likes_$owner"
}
