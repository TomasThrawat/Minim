package com.minim.music.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.minim.music.R
import com.minim.music.data.account.YouTubeMusicSessionAuth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
class YouTubeMusicLoginActivity : AppCompatActivity() {
    private data class PageAuthData(
        val visitorData: String?,
        val dataSyncId: String?,
        val authUser: String,
        val apiKey: String?,
        val clientVersion: String?,
    )

    private class LoginJsInterface {
        var onVisitorDataReceived: ((String?) -> Unit)? = null
        var onDataSyncIdReceived: ((String?) -> Unit)? = null
        var onAuthUserReceived: ((String?) -> Unit)? = null
        var onApiKeyReceived: ((String?) -> Unit)? = null
        var onClientVersionReceived: ((String?) -> Unit)? = null

        @JavascriptInterface
        fun onRetrieveVisitorData(value: String?) {
            onVisitorDataReceived?.invoke(value)
        }

        @JavascriptInterface
        fun onRetrieveDataSyncId(value: String?) {
            onDataSyncIdReceived?.invoke(value)
        }

        @JavascriptInterface
        fun onRetrieveAuthUser(value: String?) {
            onAuthUserReceived?.invoke(value)
        }

        @JavascriptInterface
        fun onRetrieveApiKey(value: String?) {
            onApiKeyReceived?.invoke(value)
        }

        @JavascriptInterface
        fun onRetrieveClientVersion(value: String?) {
            onClientVersionReceived?.invoke(value)
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private val loginJsInterface = LoginJsInterface()
    private var captureJob: Job? = null
    private var errorDialog: AlertDialog? = null
    private var returnedSession = false
    private var hasAuthCookie = false
    private var hasVisitorData = false
    private var hasWebRemixConfig = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val close = TextView(this).apply {
            text = "×"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = getString(R.string.close)
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        toolbar.addView(close, LinearLayout.LayoutParams(dp(56), dp(52)))
        val title = TextView(this).apply {
            text = "Sign in to YouTube Music"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)),
        )

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        root.addView(
            progressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)),
        )

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false)
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            addJavascriptInterface(loginJsInterface, "Android")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme.orEmpty()
                    if (scheme == "http" || scheme == "https") {
                        return !isTrustedGoogleOrYouTubeHost(uri.host.orEmpty())
                    }
                    if (scheme == "intent") {
                        openIntentUrl(uri)
                        return true
                    }
                    return true
                }

                override fun onPageStarted(
                    view: WebView,
                    url: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull()
                    if (uri?.scheme == "https" && uri.host == MUSIC_HOST) {
                        tryExtractSession()
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) showLoginError()
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) showLoginError()
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: android.net.http.SslError,
                ) {
                    handler.cancel()
                    showLoginError()
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        root.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            }
        })

        webView.loadUrl(LOGIN_URL)
    }

    private fun tryExtractSession() {
        if (captureJob?.isActive == true || isFinishing || returnedSession) return
        captureJob = lifecycleScope.launch {
            repeat(MAX_EXTRACTION_ATTEMPTS) {
                if (isFinishing || returnedSession) return@launch

                val cookie = CookieManager.getInstance().getCookie(MUSIC_URL).orEmpty()
                val cookieMap = YouTubeMusicSessionAuth.parseCookieString(cookie)
                hasAuthCookie = listOf(
                    "SAPISID",
                    "__Secure-3PAPISID",
                    "__Secure-1PAPISID",
                ).any { !cookieMap[it].isNullOrBlank() }

                // Ask the page for the same WEB_REMIX values MetroList reads through its bridge.
                val pageData = readPageAuthData()
                hasVisitorData = !pageData?.visitorData.isNullOrBlank()
                hasWebRemixConfig = !pageData?.apiKey.isNullOrBlank() &&
                    !pageData?.clientVersion.isNullOrBlank()

                if (hasAuthCookie && pageData != null && hasVisitorData && hasWebRemixConfig) {
                    val result = Intent()
                        .putExtra(
                            EXTRA_COOKIE,
                            YouTubeMusicSessionAuth.normalizeCookieString(cookie),
                        )
                        .putExtra(EXTRA_VISITOR_DATA, pageData.visitorData.orEmpty())
                        .putExtra(
                            EXTRA_DATA_SYNC_ID,
                            pageData.dataSyncId.orEmpty().substringBefore("||"),
                        )
                        .putExtra(
                            EXTRA_AUTH_USER,
                            pageData.authUser.filter(Char::isDigit).ifBlank { "0" },
                        )
                        .putExtra(EXTRA_API_KEY, pageData.apiKey.orEmpty())
                        .putExtra(EXTRA_CLIENT_VERSION, pageData.clientVersion.orEmpty())

                    returnedSession = true
                    // Return the verified session immediately. Cookie cleanup is asynchronous and
                    // must not hold the Activity open after Google sign-in has completed.
                    CookieManager.getInstance().flush()
                    setResult(Activity.RESULT_OK, result)
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                    }
                    WebStorage.getInstance().deleteAllData()
                    webView.stopLoading()
                    webView.clearHistory()
                    webView.clearCache(true)
                    webView.clearFormData()
                    finish()
                    return@launch
                }

                delay(EXTRACTION_RETRY_DELAY_MS)
            }

            if (!isFinishing && !returnedSession) showLoginError()
        }
    }

    private suspend fun readPageAuthData(): PageAuthData? {
        val visitorDeferred = CompletableDeferred<String?>()
        val dataSyncDeferred = CompletableDeferred<String?>()
        val authUserDeferred = CompletableDeferred<String?>()
        val apiKeyDeferred = CompletableDeferred<String?>()
        val clientVersionDeferred = CompletableDeferred<String?>()

        loginJsInterface.onVisitorDataReceived = { value ->
            if (!visitorDeferred.isCompleted) visitorDeferred.complete(value)
        }
        loginJsInterface.onDataSyncIdReceived = { value ->
            if (!dataSyncDeferred.isCompleted) dataSyncDeferred.complete(value)
        }
        loginJsInterface.onAuthUserReceived = { value ->
            if (!authUserDeferred.isCompleted) authUserDeferred.complete(value)
        }
        loginJsInterface.onApiKeyReceived = { value ->
            if (!apiKeyDeferred.isCompleted) apiKeyDeferred.complete(value)
        }
        loginJsInterface.onClientVersionReceived = { value ->
            if (!clientVersionDeferred.isCompleted) clientVersionDeferred.complete(value)
        }

        return withTimeoutOrNull(PAGE_AUTH_TIMEOUT_MS) {
            webView.loadUrl(
                "javascript:Android.onRetrieveVisitorData(" +
                    "window.yt&&window.yt.config_?window.yt.config_.VISITOR_DATA:null)",
            )
            webView.loadUrl(
                "javascript:Android.onRetrieveDataSyncId(" +
                    "window.yt&&window.yt.config_?window.yt.config_.DATASYNC_ID:null)",
            )
            webView.loadUrl(
                "javascript:Android.onRetrieveAuthUser(" +
                    "window.yt&&window.yt.config_?String(window.yt.config_.SESSION_INDEX||0):'0')",
            )
            webView.loadUrl(
                "javascript:Android.onRetrieveApiKey(" +
                    "(window.ytcfg&&window.ytcfg.get&&window.ytcfg.get('INNERTUBE_API_KEY'))||" +
                    "(window.yt&&window.yt.config_?window.yt.config_.INNERTUBE_API_KEY:null))",
            )
            webView.loadUrl(
                "javascript:Android.onRetrieveClientVersion(" +
                    "(window.ytcfg&&window.ytcfg.get&&(window.ytcfg.get('INNERTUBE_CLIENT_VERSION')||" +
                    "window.ytcfg.get('INNERTUBE_CONTEXT_CLIENT_VERSION')))||" +
                    "(window.yt&&window.yt.config_?(window.yt.config_.INNERTUBE_CLIENT_VERSION||" +
                    "window.yt.config_.INNERTUBE_CONTEXT_CLIENT_VERSION):null))",
            )

            PageAuthData(
                visitorData = visitorDeferred.await(),
                dataSyncId = dataSyncDeferred.await(),
                authUser = authUserDeferred.await().orEmpty(),
                apiKey = apiKeyDeferred.await(),
                clientVersion = clientVersionDeferred.await(),
            )
        }
    }

    private fun clearWebSession(onComplete: () -> Unit = {}) {
        CookieManager.getInstance().flush()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            if (::webView.isInitialized) {
                webView.clearHistory()
                webView.clearCache(true)
                webView.clearFormData()
            }
            onComplete()
        }
    }

    private fun showLoginError() {
        if (isFinishing || errorDialog?.isShowing == true) return
        val detail = when {
            !hasAuthCookie ->
                "Google sign-in finished, but YouTube Music did not provide an authenticated session cookie."
            !hasVisitorData ->
                "The YouTube Music page loaded, but its account data did not finish loading."
            !hasWebRemixConfig ->
                "The signed-in YouTube Music page did not expose its WEB_REMIX API key and client version."
            else ->
                "YouTube Music sign-in could not be completed. Check your connection and retry."
        }
        errorDialog = AlertDialog.Builder(this)
            .setTitle("Sign in to YouTube Music")
            .setMessage("$detail Press Retry to try again.")
            .setPositiveButton("Retry") { _, _ ->
                captureJob?.cancel()
                errorDialog = null
                webView.reload()
            }
            .setNegativeButton(R.string.close) { _, _ ->
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { errorDialog = null }
                dialog.show()
            }
    }

    private fun openIntentUrl(uri: Uri) {
        val intent = runCatching {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return
        val fallback = intent.getStringExtra("browser_fallback_url")
        try {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else if (
                !fallback.isNullOrBlank() &&
                isTrustedGoogleOrYouTubeHost(Uri.parse(fallback).host.orEmpty())
            ) {
                webView.loadUrl(fallback)
            }
        } catch (_: Exception) {
            if (
                !fallback.isNullOrBlank() &&
                isTrustedGoogleOrYouTubeHost(Uri.parse(fallback).host.orEmpty())
            ) {
                webView.loadUrl(fallback)
            }
        }
    }

    private fun isTrustedGoogleOrYouTubeHost(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == "google.com" ||
            normalized.endsWith(".google.com") ||
            normalized == "google.com.eg" ||
            normalized.endsWith(".google.com.eg") ||
            normalized == "google.co.uk" ||
            normalized.endsWith(".google.co.uk") ||
            normalized == "g.co" ||
            normalized.endsWith(".g.co") ||
            normalized == "youtube.com" ||
            normalized.endsWith(".youtube.com") ||
            normalized == "gstatic.com" ||
            normalized.endsWith(".gstatic.com") ||
            normalized == "googleusercontent.com" ||
            normalized.endsWith(".googleusercontent.com") ||
            normalized == "googleapis.com" ||
            normalized.endsWith(".googleapis.com") ||
            normalized == "recaptcha.net" ||
            normalized.endsWith(".recaptcha.net")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        captureJob?.cancel()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.removeJavascriptInterface("Android")
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        if (!returnedSession) clearWebSession()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_COOKIE = "com.minim.music.youtube.EXTRA_COOKIE"
        const val EXTRA_VISITOR_DATA = "com.minim.music.youtube.EXTRA_VISITOR_DATA"
        const val EXTRA_DATA_SYNC_ID = "com.minim.music.youtube.EXTRA_DATA_SYNC_ID"
        const val EXTRA_AUTH_USER = "com.minim.music.youtube.EXTRA_AUTH_USER"
        const val EXTRA_API_KEY = "com.minim.music.youtube.EXTRA_API_KEY"
        const val EXTRA_CLIENT_VERSION = "com.minim.music.youtube.EXTRA_CLIENT_VERSION"

        private const val MUSIC_HOST = "music.youtube.com"
        private const val MUSIC_URL = "https://music.youtube.com"
        private const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
        private const val PAGE_AUTH_TIMEOUT_MS = 1_000L
        private const val EXTRACTION_RETRY_DELAY_MS = 500L
        private const val MAX_EXTRACTION_ATTEMPTS = 20
    }
}
