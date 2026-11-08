package com.minim.music.data.account

import android.content.Context
import com.minim.music.data.local.LibraryDatabase
import com.minim.music.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Keeps Minim's liked list aligned with the signed-in YouTube Music account. */
class YouTubeMusicSync(
    context: Context,
    private val database: LibraryDatabase,
    private val account: YouTubeMusicAccount,
    httpClient: OkHttpClient,
) {
    data class Status(val message: String, val error: String? = null, val syncedAt: Long = 0L)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val api = YouTubeMusicApi(httpClient)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pendingLock = Any()
    private val _status = kotlinx.coroutines.flow.MutableStateFlow(Status("Sign in to sync liked songs"))
    val status: kotlinx.coroutines.flow.StateFlow<Status> = _status
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        database.onYouTubeMusicLikeChanged = { track, liked -> queueLike(track, liked) }
        scope.launch {
            account.session.collect { session ->
                if (session != null) syncNow()
                else _status.value = Status("Sign in to sync liked songs")
            }
        }
    }

    fun onForeground() {
        if (account.session.value != null) scope.launch { syncNow() }
    }

    suspend fun syncNow() = withContext(Dispatchers.IO) {
        val session = account.session.value ?: run {
            _status.value = Status("Sign in to sync liked songs")
            return@withContext
        }
        mutex.withLock {
            _status.value = Status("Syncing liked songs…")
            try {
                claimUnassignedLikes(session.accountKey)
                flushPending(session)
                var remote = api.fetchLikedSongs(session)
                val initialKey = "initial_${session.accountKey}"
                if (!prefs.getBoolean(initialKey, false)) {
                    val remoteIds = remote.mapTo(HashSet()) { it.id }
                    val localOnly = database.getLikedTracks().filterNot { it.id in remoteIds }
                    for (track in localOnly) enqueuePendingForAccount(session.accountKey, track.id, true)
                    flushPending(session)
                    remote = api.fetchLikedSongs(session)
                    prefs.edit().putBoolean(initialKey, true).apply()
                }
                if (account.session.value?.accountKey != session.accountKey) return@withLock
                database.applyYouTubeMusicLikedTracks(remote, replaceExisting = true)
                _status.value = Status("Liked songs synced", syncedAt = System.currentTimeMillis())
            } catch (error: Exception) {
                _status.value = Status("Couldn't sync liked songs", error.message ?: "Check your connection and sign in again")
            }
        }
    }

    private fun queueLike(track: Track, liked: Boolean) {
        val owner = PendingLikeQueue.ownerForChange(account.session.value?.accountKey, account.lastAccountKey())
        enqueuePendingForAccount(owner, track.id, liked)
        if (account.session.value?.accountKey == owner) scope.launch { syncNow() }
    }

    /** Claim pre-account local edits once, on the first account only. */
    private fun claimUnassignedLikes(accountKey: String) = synchronized(pendingLock) {
        if (prefs.getBoolean(HAS_CONNECTED_ACCOUNT, false)) return@synchronized
        val unassignedKey = PendingLikeQueue.preferenceKey(PendingLikeQueue.UNASSIGNED_ACCOUNT)
        val targetKey = PendingLikeQueue.preferenceKey(accountKey)
        val unassigned = prefs.getStringSet(unassignedKey, emptySet()).orEmpty().toSet()
        if (unassigned.isNotEmpty()) {
            val target = prefs.getStringSet(targetKey, emptySet()).orEmpty().toMutableSet()
            target.addAll(unassigned)
            prefs.edit().putStringSet(targetKey, target).remove(unassignedKey).apply()
        }
        prefs.edit().putBoolean(HAS_CONNECTED_ACCOUNT, true).apply()
    }

    private fun enqueuePendingForAccount(owner: String, videoId: String, liked: Boolean) = synchronized(pendingLock) {
        val key = PendingLikeQueue.preferenceKey(owner)
        val entries = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        entries.removeAll { it.substringBeforeLast('|', "") == videoId }
        entries.add("$videoId|${if (liked) 1 else 0}")
        prefs.edit().putStringSet(key, entries).apply()
    }

    private suspend fun flushPending(session: YouTubeMusicAccount.Session) {
        val key = PendingLikeQueue.preferenceKey(session.accountKey)
        val pending = synchronized(pendingLock) {
            prefs.getStringSet(key, emptySet()).orEmpty().toList()
        }
        for (entry in pending) {
            if (account.session.value?.accountKey != session.accountKey) return
            val split = entry.lastIndexOf('|')
            if (split <= 0) {
                removePending(key, entry)
                continue
            }
            val id = entry.substring(0, split)
            val liked = entry.substring(split + 1) == "1"
            api.setLiked(session, id, liked)
            removePending(key, entry)
            delay(100L)
        }
    }

    private fun removePending(key: String, entry: String) = synchronized(pendingLock) {
        val entries = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        entries.remove(entry)
        prefs.edit().putStringSet(key, entries).apply()
    }

    companion object {
        private const val PREFS = "youtube_music_sync"
        private const val HAS_CONNECTED_ACCOUNT = "has_connected_account"
    }
}
