package com.minim.music.data.account

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONTokener
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/** A YouTube Music web session, stored encrypted with a non-exportable Android Keystore key. */
class YouTubeMusicAccount(activityContext: android.content.Context) {
    data class Session(
        val cookie: String,
        val apiKey: String,
        val clientVersion: String,
        val visitorData: String,
        val authUser: String,
        val dataSyncId: String,
        val displayName: String,
        val userAgent: String,
    ) {
        internal fun toJson() = JSONObject()
            .put("cookie", cookie)
            .put("apiKey", apiKey)
            .put("clientVersion", clientVersion)
            .put("visitorData", visitorData)
            .put("authUser", authUser)
            .put("dataSyncId", dataSyncId)
            .put("displayName", displayName)
            .put("userAgent", userAgent)

        internal fun accountKey(): String = dataSyncId
    }

    private val context = activityContext.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Opens YouTube Music's own sign-in flow. Credentials are entered only on Google's page. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun signIn(activity: Activity): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = Dialog(activity)
        val webView = WebView(activity)
        var finished = false
        var extractionAttempts = 0

        fun finish(result: Session?) {
            if (finished) return
            finished = true
            if (result != null) {
                saveSession(result)
                _session.value = result
            }
            runCatching { webView.stopLoading() }
            runCatching { webView.removeAllViews() }
            runCatching { webView.destroy() }
            if (dialog.isShowing) dialog.dismiss()
            if (continuation.isActive) continuation.resume(result != null)
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (finished || Uri.parse(url.orEmpty()).host != MUSIC_HOST) return
                val cookie = CookieManager.getInstance().getCookie(MUSIC_ORIGIN).orEmpty()
                if (cookieValue(cookie, "SAPISID").isNullOrBlank() &&
                    cookieValue(cookie, "__Secure-3PAPISID").isNullOrBlank()) return
                fun extractSession() {
                    if (finished) return
                    view.evaluateJavascript(SESSION_SCRIPT) { encoded ->
                        if (finished) return@evaluateJavascript
                        val raw = encoded?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                        val values = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
                        val apiKey = values?.optString("apiKey").orEmpty()
                        val clientVersion = values?.optString("clientVersion").orEmpty()
                        val visitorData = values?.optString("visitorData").orEmpty()
                        if (values == null || apiKey.isBlank() || clientVersion.isBlank() || visitorData.isBlank()) {
                            extractionAttempts++
                            if (extractionAttempts < 20) view.postDelayed({ extractSession() }, 500L)
                            return@evaluateJavascript
                        }
                        finish(
                            Session(
                                cookie = cookie,
                                apiKey = apiKey,
                                clientVersion = clientVersion,
                                visitorData = visitorData,
                                authUser = values.optString("authUser", "0"),
                                dataSyncId = values.optString("dataSyncId").substringBefore("||"),
                                displayName = values.optString("displayName").ifBlank { "YouTube Music" },
                                userAgent = values.optString("userAgent").ifBlank { view.settings.userAgentString },
                            )
                        )
                    }
                }
                extractSession()
            }
        }
        dialog.setContentView(webView)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { finish(null) }
        dialog.setOnDismissListener { if (!finished) finish(null) }
        dialog.setOnShowListener { dialog.window?.setLayout(-1, -1) }
        continuation.invokeOnCancellation {
            activity.runOnUiThread {
                if (!finished) {
                    finished = true
                    runCatching { webView.stopLoading() }
                    runCatching { webView.destroy() }
                    if (dialog.isShowing) dialog.dismiss()
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        webView.loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
    }

    fun signOut() {
        prefs.edit().remove(SESSION_KEY).apply()
        _session.value = null
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun saveSession(session: Session) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(session.toJson().toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString(SESSION_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun loadSession(): Session? = runCatching {
        val packed = Base64.decode(prefs.getString(SESSION_KEY, null), Base64.NO_WRAP)
        require(packed.size > GCM_IV_BYTES)
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        Session(
            cookie = json.getString("cookie"),
            apiKey = json.getString("apiKey"),
            clientVersion = json.getString("clientVersion"),
            visitorData = json.getString("visitorData"),
            authUser = json.optString("authUser", "0"),
            dataSyncId = json.optString("dataSyncId"),
            displayName = json.optString("displayName", "YouTube Music"),
            userAgent = json.optString("userAgent"),
        )
    }.getOrNull()

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun cookieValue(cookie: String, key: String): String? = cookie.split(';').asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }

    companion object {
        private const val PREFS = "youtube_music_account"
        private const val SESSION_KEY = "encrypted_session_v1"
        private const val KEY_ALIAS = "minim.youtube.music.session.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val MUSIC_HOST = "music.youtube.com"
        private const val MUSIC_ORIGIN = "https://music.youtube.com"
        private const val SESSION_SCRIPT = """(function(){var c=window.ytcfg||{};var g=function(k){try{return typeof c.get==='function'?c.get(k):(c.data_||{})[k]}catch(e){return null}};var x=g('INNERTUBE_CONTEXT')||{};var cl=x.client||{};return JSON.stringify({apiKey:g('INNERTUBE_API_KEY'),clientVersion:g('INNERTUBE_CLIENT_VERSION')||cl.clientVersion,visitorData:g('VISITOR_DATA')||cl.visitorData,authUser:String(g('SESSION_INDEX')||'0'),dataSyncId:String(g('DATASYNC_ID')||''),displayName:String(g('ACCOUNT_NAME')||''),userAgent:navigator.userAgent})})();"""
    }
}
