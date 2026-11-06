package pl.profitos.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineView: View

    private var keepSplashOnScreen = true

    // Real scroll position of the app's inner <main class="overflow-auto"> container,
    // reported from JS via the ProfitOSNative bridge. WebView.scrollY stays 0 because
    // the page itself never scrolls the document — only its inner container does.
    @Volatile
    private var innerScrollTop = 0

    companion object {
        private val START_URL = BuildConfig.START_URL
        private val ALLOWED_HOST_EXACT = BuildConfig.ALLOWED_DOMAIN
        private val ALLOWED_HOST_SUFFIX = "." + BuildConfig.ALLOWED_DOMAIN
        // The backend recognises the app by the pl.profitos.app marker.
        private val UA_SUFFIX = " pl.profitos.app/" + BuildConfig.VERSION_NAME
        private const val SPLASH_TIMEOUT_MS = 3000L

        private const val SCROLL_BRIDGE_SCRIPT = """
            (function() {
              if (window.__profitOSScrollBridgeInstalled) { return; }
              window.__profitOSScrollBridgeInstalled = true;
              function report(e) {
                var top = (e && e.target && e.target.scrollTop) || window.scrollY || 0;
                if (window.ProfitOSNative && window.ProfitOSNative.onScroll) {
                  window.ProfitOSNative.onScroll(top);
                }
              }
              document.addEventListener('scroll', report, true);
              report(null);
            })();
        """
    }

    private lateinit var vault: BiometricVault

    /** Scroll reporting only; sensitive vault operations use an origin-scoped message listener. */
    private inner class NativeBridge {
        @JavascriptInterface
        fun onScroll(top: Int) {
            innerScrollTop = top
        }

    }

    private fun isTrustedPage(uri: android.net.Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val current = try { android.net.Uri.parse(webView.url) } catch (_: Exception) { null }
        val currentHost = current?.host?.lowercase()
        return scheme == "https" &&
            (host == ALLOWED_HOST_EXACT || host.endsWith(ALLOWED_HOST_SUFFIX)) &&
            current?.scheme?.lowercase() == "https" &&
            (currentHost == ALLOWED_HOST_EXACT || currentHost?.endsWith(ALLOWED_HOST_SUFFIX) == true)
    }

    private fun sendVaultReply(reply: JavaScriptReplyProxy, id: String?, ok: Boolean, value: Any? = null, error: String? = null) {
        if (id.isNullOrBlank()) return
        val valueJson = when (value) {
            is Boolean -> value.toString()
            is String -> jsString(value)
            null -> "null"
            else -> "null"
        }
        val errorJson = if (error == null) "null" else jsString(error)
        reply.postMessage("{\"id\":${jsString(id)},\"ok\":$ok,\"value\":$valueJson,\"error\":$errorJson}")
    }

    private fun installVaultMessageListener() {
        val allowedOrigins = setOf("https://$ALLOWED_HOST_EXACT", "https://*.$ALLOWED_HOST_EXACT")
        WebViewCompat.addWebMessageListener(webView, "ProfitOSNativeVault", allowedOrigins) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!isMainFrame) return@addWebMessageListener
            if (!isTrustedPage(sourceOrigin)) return@addWebMessageListener
            val data = message.data ?: return@addWebMessageListener
            val json = try { org.json.JSONObject(data) } catch (_: Exception) { return@addWebMessageListener }
            val id = if (json.has("id")) json.optString("id") else null
            when (json.optString("action")) {
                "biometricAvailable" -> sendVaultReply(replyProxy, id, true, vault.isBiometricAvailable())
                "hasStoredKey" -> sendVaultReply(replyProxy, id, true, vault.hasStoredKey())
                "clearKey" -> sendVaultReply(replyProxy, id, true, vault.clearKey())
                "storeKey" -> {
                    val keyB64 = json.optString("keyB64", "")
                    if (keyB64.isBlank()) sendVaultReply(replyProxy, id, false, error = BiometricVault.ERR_CRYPTO)
                    else vault.storeKey(keyB64) { ok, error ->
                        runOnUiThread { if (isTrustedPage(webView.url?.let(android.net.Uri::parse))) sendVaultReply(replyProxy, id, ok, error = error) }
                    }
                }
                "requestKey" -> vault.requestKey { keyB64, error ->
                    runOnUiThread {
                        if (isTrustedPage(webView.url?.let(android.net.Uri::parse))) sendVaultReply(replyProxy, id, keyB64 != null && error == null, value = keyB64, error = error)
                    }
                }
                else -> sendVaultReply(replyProxy, id, false, error = BiometricVault.ERR_CRYPTO)
            }
        }
    }

    /** Minimal JS string-literal escaping for values reflected back into the page. */
    private fun jsString(value: String): String {
        val sb = StringBuilder(value.length + 2).append('"')
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun initVault() {
        vault = BiometricVault(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }
        // Fallback so a slow/broken first load never leaves the splash stuck forever.
        Handler(Looper.getMainLooper()).postDelayed({ keepSplashOnScreen = false }, SPLASH_TIMEOUT_MS)

        window.statusBarColor = ContextCompat.getColor(this, R.color.ic_launcher_background)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.userAgentString = webView.settings.userAgentString + UA_SUFFIX
        initVault()
        webView.addJavascriptInterface(NativeBridge(), "ProfitOSNative")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            installVaultMessageListener()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                innerScrollTop = 0
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val host = url.host ?: ""
                val isAllowed = host == ALLOWED_HOST_EXACT || host.endsWith(ALLOWED_HOST_SUFFIX)
                return if (isAllowed) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                keepSplashOnScreen = false
                swipeRefresh.isRefreshing = false
                showContent()
                view.evaluateJavascript(SCROLL_BRIDGE_SCRIPT, null)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    keepSplashOnScreen = false
                    swipeRefresh.isRefreshing = false
                    showOffline()
                }
            }
        }

        swipeRefresh = SwipeRefreshLayout(this).apply {
            addView(
                webView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            setOnRefreshListener { webView.reload() }
            setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 || innerScrollTop > 0 }
        }

        offlineView = buildOfflineView()

        val root = FrameLayout(this)
        root.addView(
            swipeRefresh,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            offlineView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL)
        }
    }

    private fun buildOfflineView(): View {
        val density = resources.displayMetrics.density
        val padding = (32 * density).toInt()
        val brandColor = ContextCompat.getColor(this, R.color.ic_launcher_background)

        val message = TextView(this).apply {
            text = getString(R.string.offline_title)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(brandColor)
        }

        val retryButton = Button(this).apply {
            text = getString(R.string.offline_retry)
            setOnClickListener { webView.reload() }
        }
        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * density).toInt() }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            setPadding(padding, padding, padding, padding)
            visibility = View.GONE
            addView(message)
            addView(retryButton, buttonParams)
        }
    }

    private fun showOffline() {
        swipeRefresh.visibility = View.GONE
        offlineView.visibility = View.VISIBLE
    }

    private fun showContent() {
        offlineView.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
    }
}
