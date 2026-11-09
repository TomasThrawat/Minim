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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

class YouTubeMusicLoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var captureJob: Job? = null
    private var errorDialog: AlertDialog? = null
    private var returnedSession = false

    @SuppressLint("SetJavaScriptEnabled")
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
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52),
        ))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(2),
        ))

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

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
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
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
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
            repeat(20) {
                val cookie = CookieManager.getInstance().getCookie(MUSIC_URL).orEmpty()
                val pageConfig = readPageConfig()
                val visitorData = pageConfig?.optString("visitorData").orEmpty()
                if (YouTubeMusicSessionAuth.hasAuthenticationCookie(cookie) && visitorData.isNotBlank()) {
                    val result = Intent()
                        .putExtra(EXTRA_COOKIE, YouTubeMusicSessionAuth.normalizeCookieString(cookie))
                        .putExtra(EXTRA_VISITOR_DATA, visitorData)
                        .putExtra(
                            EXTRA_DATA_SYNC_ID,
                            pageConfig?.optString("dataSyncId").orEmpty().substringBefore("||"),
                        )
                        .putExtra(
                            EXTRA_AUTH_USER,
                            pageConfig?.optString("authUser").orEmpty().filter(Char::isDigit).ifBlank { "0" },
                        )
                    returnedSession = true
                    clearWebSession {
                        if (!isFinishing) {
                            setResult(Activity.RESULT_OK, result)
                            finish()
                        }
                    }
                    return@launch
                }
                delay(500L)
            }
            showLoginError()
        }
    }

    private suspend fun readPageConfig(): JSONObject? =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(PAGE_AUTH_SCRIPT) { raw ->
                if (!continuation.isActive) return@evaluateJavascript
                val parsed = runCatching {
                    when (val value = JSONTokener(raw).nextValue()) {
                        is JSONObject -> value
                        is String -> JSONObject(value)
                        else -> null
                    }
                }.getOrNull()
                continuation.resume(parsed)
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
        errorDialog = AlertDialog.Builder(this)
            .setTitle("Sign in to YouTube Music")
            .setMessage("Couldn&apos;t detect a signed-in YouTube Music session. Check your account and retry.")
            .setPositiveButton("Retry") { _, _ -> webView.reload() }
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
            } else if (!fallback.isNullOrBlank() && isTrustedGoogleOrYouTubeHost(Uri.parse(fallback).host.orEmpty())) {
                webView.loadUrl(fallback)
            }
        } catch (_: Exception) {
            if (!fallback.isNullOrBlank() && isTrustedGoogleOrYouTubeHost(Uri.parse(fallback).host.orEmpty())) {
                webView.loadUrl(fallback)
            }
        }
    }

    private fun isTrustedGoogleOrYouTubeHost(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized == "google.com" ||
            normalized.endsWith(".google.com") ||
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

        private const val MUSIC_HOST = "music.youtube.com"
        private const val MUSIC_URL = "https://music.youtube.com"
        private const val LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
        private const val PAGE_AUTH_SCRIPT = """
            (function () {
                var config = window.yt && window.yt.config_ ? window.yt.config_ : {};
                return JSON.stringify({
                    visitorData: config.VISITOR_DATA || "",
                    dataSyncId: config.DATASYNC_ID || "",
                    authUser: String(config.SESSION_INDEX || "0")
                });
            })()
        """
    }
}
