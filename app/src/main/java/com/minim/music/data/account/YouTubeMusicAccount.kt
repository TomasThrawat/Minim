package com.minim.music.data.account

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** Google OAuth session for the YouTube Music library. Access tokens stay in memory only. */
class YouTubeMusicAccount(
    context: Context,
    private val httpClient: OkHttpClient,
) {
    data class Session(
        val accessToken: String,
        val accountKey: String,
        val displayName: String,
    )

    sealed interface AuthorizationStep {
        data class Authorized(val session: Session) : AuthorizationStep
        data class NeedsConsent(val pendingIntent: PendingIntent) : AuthorizationStep
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    init {
        // Remove the obsolete locally encrypted session and its device key after upgrading.
        prefs.edit().remove(LEGACY_SESSION_KEY).apply()
        runCatching {
            val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(LEGACY_KEY_ALIAS)) store.deleteEntry(LEGACY_KEY_ALIAS)
        }
    }

    suspend fun authorize(activity: Activity): AuthorizationStep {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES)
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        return consumeAuthorization(result)
    }

    suspend fun completeAuthorization(activity: Activity, data: Intent?): Session {
        val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        val token = result.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IOException("Google did not return a YouTube Music access token")
        return createSession(token)
    }

    private suspend fun consumeAuthorization(
        result: AuthorizationResult,
    ): AuthorizationStep {
        if (result.hasResolution()) {
            return AuthorizationStep.NeedsConsent(
                result.pendingIntent ?: throw IOException("Google authorization did not provide a consent screen")
            )
        }
        val token = result.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IOException("Google did not return a YouTube Music access token")
        return AuthorizationStep.Authorized(createSession(token))
    }

    private suspend fun createSession(accessToken: String): Session = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(USER_INFO_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        val user = httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Google account lookup failed (${response.code})")
            }
            runCatching { JSONObject(text) }.getOrElse {
                throw IOException("Google returned an invalid account profile", it)
            }
        }
        val subject = user.optString("sub").takeIf { it.isNotBlank() }
            ?: throw IOException("Google did not return a stable account identifier")
        val accountKey = MessageDigest.getInstance("SHA-256")
            .digest(subject.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
        val displayName = user.optString("name").ifBlank { "YouTube Music" }
        prefs.edit()
            .putBoolean(CONNECTED_KEY, true)
            .putString(LAST_ACCOUNT_KEY, accountKey)
            .apply()
        Session(accessToken, accountKey, displayName).also { _session.value = it }
    }

    fun hasStoredAuthorization(): Boolean = prefs.getBoolean(CONNECTED_KEY, false)

    /** Used only to keep offline like/unlike operations attached to their original account. */
    fun lastAccountKey(): String? = prefs.getString(LAST_ACCOUNT_KEY, null)?.takeIf { it.isNotBlank() }

    fun signOut(activity: Activity) {
        _session.value = null
        prefs.edit().putBoolean(CONNECTED_KEY, false).apply()
        runCatching {
            Identity.getAuthorizationClient(activity).revokeAccess(
                RevokeAccessRequest.builder().setScopes(SCOPES).build()
            )
        }
    }

    companion object {
        private const val PREFS = "youtube_music_account"
        private const val CONNECTED_KEY = "connected"
        private const val LAST_ACCOUNT_KEY = "last_account_key"
        private const val LEGACY_SESSION_KEY = "encrypted_session_v1"
        private const val LEGACY_KEY_ALIAS = "minim.youtube.music.session.v1"
        private const val USER_INFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
        private val SCOPES = listOf(
            Scope("https://www.googleapis.com/auth/youtube"),
            Scope("openid"),
            Scope("profile"),
        )
    }
}
