package dev.zet.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class BrowserActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var shield: TextView
    private lateinit var btnBack: TextView
    private lateinit var btnForward: TextView
    private lateinit var browserLayout: View
    private lateinit var fullscreenLayer: FrameLayout
    private lateinit var btnDownload: TextView
    private lateinit var ytDownloader: YouTubeDownloader

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** shouldInterceptRequest arka plan thread'lerinde çalışır. */
    @Volatile
    private var currentPageUrl: String = ""
    private val blockedOnPage = AtomicInteger(0)
    private var adblockEnabled = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        shield = findViewById(R.id.btnShield)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        browserLayout = findViewById(R.id.browser)
        fullscreenLayer = findViewById(R.id.fullscreen)
        btnDownload = findViewById(R.id.btnDownload)
        ytDownloader = YouTubeDownloader(
            this,
            findViewById(R.id.downloadBar),
            findViewById(R.id.downloadText),
            findViewById(R.id.downloadProgress),
            findViewById(R.id.downloadCancel),
        )

        adblockEnabled = prefs().getBoolean(PREF_ADBLOCK, true)
        ZetCore.setEnabled(adblockEnabled)
        updateShield()

        setupWebView()
        setupToolbar()

        thread(name = "zet-filters", isDaemon = true) {
            FilterLists(applicationContext).loadAndRefresh { updating ->
                runOnUiThread {
                    val msg = if (updating) R.string.lists_updating else R.string.lists_ready
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            // Motor yüklendikten sonra kayıtlı açık/kapalı ayarını uygula.
            ZetCore.setEnabled(adblockEnabled)
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else if (!handleIntent(intent)) {
            load(prefs().getString(PREF_LAST_URL, null) ?: HOME_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // "; wv" işaretini kaldır: bazı siteler (Google girişi vb.)
            // gömülü WebView'ı engelliyor, normal Chrome gibi görünelim.
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = ZetWebViewClient()
        webView.webChromeClient = ZetChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun setupToolbar() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<View>(R.id.btnReload).setOnClickListener {
            if (progress.visibility == View.VISIBLE) webView.stopLoading() else webView.reload()
        }
        btnDownload.setOnClickListener {
            val id = ZetCore.ytVideoId(webView.url ?: "")
            if (id.isNotEmpty()) ytDownloader.start(id)
        }
        shield.setOnClickListener {
            adblockEnabled = !adblockEnabled
            ZetCore.setEnabled(adblockEnabled)
            prefs().edit().putBoolean(PREF_ADBLOCK, adblockEnabled).apply()
            val msg = if (adblockEnabled) R.string.adblock_on else R.string.adblock_off
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            webView.reload()
        }
        urlBar.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                load(ZetCore.normalizeInput(urlBar.text.toString()))
                hideKeyboard()
                true
            } else {
                false
            }
        }
        urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) showUrl(webView.url)
        }
    }

    private fun load(url: String) {
        webView.loadUrl(url)
        webView.requestFocus()
    }

    private fun showUrl(url: String?) {
        if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
        val isVideo = url != null && ZetCore.ytVideoId(url).isNotEmpty()
        btnDownload.visibility = if (isVideo) View.VISIBLE else View.GONE
    }

    private fun updateShield() {
        if (adblockEnabled) {
            shield.text = "🛡${blockedOnPage.get()}"
            shield.setTextColor(getColor(R.color.accent))
        } else {
            shield.text = "🛡✕"
            shield.setTextColor(getColor(R.color.accent_off))
        }
    }

    private fun updateNavButtons() {
        btnBack.alpha = if (webView.canGoBack()) 1f else 0.35f
        btnForward.alpha = if (webView.canGoForward()) 1f else 0.35f
    }

    /** Gizlenecek reklam öğeleri için <style> enjekte eder. */
    private fun injectCosmetics(view: WebView, url: String?) {
        if (!adblockEnabled || url == null || !url.startsWith("http")) return
        val css = ZetCore.cosmeticCss(url)
        if (css.isEmpty()) return
        val js = """
            (function(){
              var id='zet-cosmetic', s=document.getElementById(id);
              if(!s){s=document.createElement('style');s.id=id;
                (document.head||document.documentElement).appendChild(s);}
              s.textContent=${JSONObject.quote(css)};
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private inner class ZetWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (request.isForMainFrame) {
                currentPageUrl = url
                return null
            }
            if (ZetCore.shouldBlock(url, currentPageUrl, false) == 1) {
                blockedOnPage.incrementAndGet()
                runOnUiThread { updateShield() }
                return WebResourceResponse(
                    "text/plain", "utf-8", 204, "No Content",
                    emptyMap(), ByteArrayInputStream(ByteArray(0)),
                )
            }
            return null
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https", "about", "data", "javascript", "blob", "file" -> false
                "intent" -> {
                    openIntentUri(view, uri.toString()); true
                }
                else -> {
                    openExternal(Intent(Intent.ACTION_VIEW, uri)); true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            blockedOnPage.set(0)
            updateShield()
            showUrl(url)
            progress.visibility = View.VISIBLE
            updateNavButtons()
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            injectCosmetics(view, url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.visibility = View.GONE
            showUrl(url)
            updateNavButtons()
            injectCosmetics(view, url)
            if (url != null && url.startsWith("http")) {
                prefs().edit().putString(PREF_LAST_URL, url).apply()
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            showUrl(url)
            updateNavButtons()
        }
    }

    private inner class ZetChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenLayer.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            fullscreenLayer.visibility = View.VISIBLE
            browserLayout.visibility = View.GONE
            setFullscreenMode(true)
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        fullscreenLayer.removeView(view)
        fullscreenLayer.visibility = View.GONE
        browserLayout.visibility = View.VISIBLE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        setFullscreenMode(false)
    }

    @Suppress("DEPRECATION")
    private fun setFullscreenMode(on: Boolean) {
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (!url.startsWith("http")) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(
                this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT,
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openIntentUri(view: WebView, uri: String) {
        try {
            val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback != null) view.loadUrl(fallback)
                else Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openExternal(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** Dışarıdan gelen link / paylaşım / arama. İşlendiyse true. */
    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val target = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { extractUrl(it) }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")
            else -> null
        } ?: return false
        load(ZetCore.normalizeInput(target))
        return true
    }

    /** Paylaşılan metnin içindeki ilk linki bulur, yoksa metnin kendisini döner. */
    private fun extractUrl(text: String): String =
        Regex("https?://\\S+").find(text)?.value ?: text

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            urlBar.hasFocus() -> {
                urlBar.clearFocus(); hideKeyboard()
            }
            webView.canGoBack() -> webView.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    private fun prefs() = getSharedPreferences("zet", Context.MODE_PRIVATE)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "https://www.google.com"
        private const val PREF_ADBLOCK = "adblock_enabled"
        private const val PREF_LAST_URL = "last_url"
    }
}
