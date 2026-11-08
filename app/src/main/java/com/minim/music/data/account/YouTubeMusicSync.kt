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
import java.security.MessageDigest

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
                flushPending(session)
                var remote = api.fetchLikedSongs(session)
                val accountKey = accountKey(session)
                val initialKey = "initial_$accountKey"
                if (!prefs.getBoolean(initialKey, false)) {
                    val remoteIds = remote.mapTo(HashSet()) { it.id }
                    val localOnly = database.getLikedTracks().filterNot { it.id in remoteIds }
                    for (track in localOnly) enqueuePending(track.id, true)
                    flushPending(session)
                    remote = api.fetchLikedSongs(session)
                    prefs.edit().putBoolean(initialKey, true).apply()
                }
                database.applyYouTubeMusicLikedTracks(remote, replaceExisting = true)
                _status.value = Status("Liked songs synced", syncedAt = System.currentTimeMillis())
            } catch (error: Exception) {
                _status.value = Status("Couldn't sync liked songs", error.message ?: "Check your connection and sign in again")
            }
        }
    }

    private fun queueLike(track: Track, liked: Boolean) {
        enqueuePending(track.id, liked)
        if (account.session.value != null) scope.launch { syncNow() }
    }

    /** Keeps likes and unlikes made while signed out/offline until a YouTube account is available. */
    private fun enqueuePending(videoId: String, liked: Boolean) = synchronized(pendingLock) {
        val entries = prefs.getStringSet(PENDING_KEY, emptySet()).orEmpty().toMutableSet()
        entries.removeAll { it.substringBeforeLast('|', "") == videoId }
        entries.add("$videoId|${if (liked) 1 else 0}")
        prefs.edit().putStringSet(PENDING_KEY, entries).apply()
    }

    private suspend fun flushPending(session: YouTubeMusicAccount.Session) {
        val pending = synchronized(pendingLock) {
            prefs.getStringSet(PENDING_KEY, emptySet()).orEmpty().toList()
        }
        for (entry in pending) {
            val split = entry.lastIndexOf('|')
            if (split <= 0) {
                removePending(entry)
                continue
            }
            val id = entry.substring(0, split)
            val liked = entry.substring(split + 1) == "1"
            api.setLiked(session, id, liked)
            removePending(entry)
            delay(100L)
        }
    }

    private fun removePending(entry: String) = synchronized(pendingLock) {
        val entries = prefs.getStringSet(PENDING_KEY, emptySet()).orEmpty().toMutableSet()
        entries.remove(entry)
        prefs.edit().putStringSet(PENDING_KEY, entries).apply()
    }

    private fun accountKey(session: YouTubeMusicAccount.Session): String {
        val raw = session.accountKey().ifBlank { session.cookie }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
    }

    companion object { private const val PREFS = "youtube_music_sync"; private const val PENDING_KEY = "pending_likes" }
}
