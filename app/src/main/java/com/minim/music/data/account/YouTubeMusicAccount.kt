package com.minim.music.data.account

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.webkit.CookieManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.minim.music.ui.YouTubeMusicLoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * YouTube Music web session. The cookie is encrypted with an Android Keystore AES key.
 * The Google password is entered only on Google's sign-in page and is never read by Minim.
 */
class YouTubeMusicAccount(
    context: Context,
    private val httpClient: OkHttpClient,
) {
    data class Session(
        val cookie: String,
        val visitorData: String,
        val dataSyncId: String,
        val authUser: String,
        val accountKey: String,
        val displayName: String,
    ) {
        override fun toString(): String =
            "Session(accountKey=" + accountKey + ", displayName=" + displayName + ")"
    }

    sealed interface AuthorizationStep {
        data class Authorized(val session: Session) : AuthorizationStep
        data class NeedsConsent(val pendingIntent: PendingIntent) : AuthorizationStep
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val api = YouTubeMusicApi(httpClient)
    private val _session = MutableStateFlow(restoreStoredSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    init {
        // Remove the obsolete OAuth session format and its device key after upgrading.
        prefs.edit().remove(LEGACY_SESSION_KEY).apply()
        runCatching {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (store.containsAlias(LEGACY_KEY_ALIAS)) store.deleteEntry(LEGACY_KEY_ALIAS)
        }
        if (_session.value == null) {
            prefs.edit().putBoolean(CONNECTED_KEY, false).apply()
        }
    }

    /**
     * Opens the same Google-to-YouTube-Music web sign-in route used by MetroList.
     * MainActivity already launches this PendingIntent for result.
     */
    suspend fun authorize(activity: Activity): AuthorizationStep {
        val loginIntent = Intent(activity, YouTubeMusicLoginActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            activity,
            LOGIN_REQUEST_CODE,
            loginIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return AuthorizationStep.NeedsConsent(pendingIntent)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun completeAuthorization(activity: Activity, data: Intent?): Session {
        val result = data ?: throw IOException("YouTube Music returned no session data")
        return completeLogin(
            cookie = result.getStringExtra(YouTubeMusicLoginActivity.EXTRA_COOKIE).orEmpty(),
            visitorData = result.getStringExtra(YouTubeMusicLoginActivity.EXTRA_VISITOR_DATA).orEmpty(),
            dataSyncId = result.getStringExtra(YouTubeMusicLoginActivity.EXTRA_DATA_SYNC_ID).orEmpty(),
            authUser = result.getStringExtra(YouTubeMusicLoginActivity.EXTRA_AUTH_USER).orEmpty(),
        )
    }

    private suspend fun completeLogin(
        cookie: String,
        visitorData: String,
        dataSyncId: String,
        authUser: String,
    ): Session = withContext(Dispatchers.IO) {
        val normalizedCookie = YouTubeMusicSessionAuth.normalizeCookieString(cookie)
        if (!YouTubeMusicSessionAuth.hasAuthenticationCookie(normalizedCookie)) {
            throw IOException("YouTube Music did not provide an authenticated session cookie. Sign in again.")
        }
        val normalizedVisitorData = visitorData.trim()
        if (normalizedVisitorData.isBlank()) {
            throw IOException("YouTube Music did not provide visitor data. Try signing in again.")
        }
        val normalizedDataSyncId = dataSyncId.substringBefore("||").trim()
        val normalizedAuthUser = authUser.filter(Char::isDigit).ifBlank { "0" }
        val accountKey = YouTubeMusicSessionAuth.accountKey(normalizedDataSyncId, normalizedCookie)
        val candidate = Session(
            cookie = normalizedCookie,
            visitorData = normalizedVisitorData,
            dataSyncId = normalizedDataSyncId,
            authUser = normalizedAuthUser,
            accountKey = accountKey,
            displayName = "YouTube Music",
        )

        // Do not save a cookie until the authenticated account endpoint confirms the session.
        val displayName = api.validateSession(candidate)
        val verified = candidate.copy(displayName = displayName.ifBlank { "YouTube Music" })
        persistSession(verified)
        _session.value = verified
        verified
    }

    fun hasStoredAuthorization(): Boolean = prefs.getBoolean(CONNECTED_KEY, false)

    /** Keeps offline like/unlike edits attached to the account they were made for. */
    fun lastAccountKey(): String? =
        prefs.getString(LAST_ACCOUNT_KEY, null)?.takeIf { it.isNotBlank() }

    @Suppress("UNUSED_PARAMETER")
    fun signOut(activity: Activity) {
        _session.value = null
        prefs.edit()
            .remove(ENCRYPTED_SESSION_KEY)
            .putBoolean(CONNECTED_KEY, false)
            .apply()
        runCatching {
            CookieManager.getInstance().removeAllCookies {
                runCatching { CookieManager.getInstance().flush() }
            }
        }
    }

    private fun persistSession(session: Session) {
        val json = JSONObject()
            .put("cookie", session.cookie)
            .put("visitorData", session.visitorData)
            .put("dataSyncId", session.dataSyncId)
            .put("authUser", session.authUser)
            .put("accountKey", session.accountKey)
            .put("displayName", session.displayName)
        val encrypted = encrypt(json.toString())
        val saved = prefs.edit()
            .putString(ENCRYPTED_SESSION_KEY, encrypted)
            .putBoolean(CONNECTED_KEY, true)
            .putString(LAST_ACCOUNT_KEY, session.accountKey)
            .commit()
        if (!saved) throw IOException("Could not securely save the YouTube Music session")
    }

    private fun restoreStoredSession(): Session? {
        val encrypted = prefs.getString(ENCRYPTED_SESSION_KEY, null) ?: return null
        return try {
            val json = JSONObject(decrypt(encrypted))
            val cookie = YouTubeMusicSessionAuth.normalizeCookieString(json.optString("cookie"))
            val visitorData = json.optString("visitorData")
            if (!YouTubeMusicSessionAuth.hasAuthenticationCookie(cookie) || visitorData.isBlank()) {
                throw IOException("Stored YouTube Music session is incomplete")
            }
            val dataSyncId = json.optString("dataSyncId").substringBefore("||")
            val accountKey = json.optString("accountKey").ifBlank {
                YouTubeMusicSessionAuth.accountKey(dataSyncId, cookie)
            }
            Session(
                cookie = cookie,
                visitorData = visitorData,
                dataSyncId = dataSyncId,
                authUser = json.optString("authUser").filter(Char::isDigit).ifBlank { "0" },
                accountKey = accountKey,
                displayName = json.optString("displayName").ifBlank { "YouTube Music" },
            ).also {
                prefs.edit()
                    .putBoolean(CONNECTED_KEY, true)
                    .putString(LAST_ACCOUNT_KEY, accountKey)
                    .apply()
            }
        } catch (_: Exception) {
            prefs.edit()
                .remove(ENCRYPTED_SESSION_KEY)
                .putBoolean(CONNECTED_KEY, false)
                .apply()
            null
        }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, payload, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, payload, cipher.iv.size, encrypted.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        if (payload.size <= GCM_IV_BYTES) throw IOException("Stored session data is invalid")
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFS = "youtube_music_account"
        private const val CONNECTED_KEY = "connected"
        private const val LAST_ACCOUNT_KEY = "last_account_key"
        private const val ENCRYPTED_SESSION_KEY = "encrypted_web_session_v1"
        private const val LEGACY_SESSION_KEY = "encrypted_session_v1"
        private const val KEY_ALIAS = "minim.youtube.music.websession.v1"
        private const val LEGACY_KEY_ALIAS = "minim.youtube.music.session.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val LOGIN_REQUEST_CODE = 5301
    }
}
