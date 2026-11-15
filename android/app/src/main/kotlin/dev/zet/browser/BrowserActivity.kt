package dev.zet.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Editable
import android.text.TextWatcher
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.SeekBar
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class BrowserActivity : Activity() {

    /** Bir sekme: kendi WebView'u ve durumu. */
    private inner class Tab(val incognito: Boolean) {
        val id = nextTabId++
        var title: String = ""
        var thumb: android.graphics.Bitmap? = null

        /** Henüz yüklenmemiş (geri yüklenen) sekmenin adresi. */
        var pendingUrl: String? = null

        /** shouldInterceptRequest arka plan thread'lerinde çalışır. */
        @Volatile
        var pageUrl: String = ""
        val blocked = AtomicInteger(0)
        var loading = false
        var adblockScript: ScriptHandler? = null
        var backgroundScript: ScriptHandler? = null
        var torScript: ScriptHandler? = null
        var desktop = false
        var mobileUa = ""

        // En sonda: createWebView yukarıdaki alanlara yazar, önce onlar hazır olmalı.
        val webView: ZetWebView = createWebView(this)

        val url: String? get() = webView.url ?: pendingUrl
    }

    private var nextTabId = 1
    private val tabs = mutableListOf<Tab>()
    private val recentlyClosed = ArrayDeque<Pair<String, String>>() // (başlık, adres)
    private lateinit var tabSwitcher: TabSwitcher
    private lateinit var db: ZetDb
    private var current: Tab? = null
    private var mediaTab: Tab? = null

    private lateinit var webContainer: FrameLayout
    private lateinit var toolbar: View
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var shield: TextView
    private lateinit var btnTabs: TextView
    private lateinit var bottomBar: View
    private lateinit var urlField: View
    private lateinit var siteIcon: TextView
    private val totalBlocked = java.util.concurrent.atomic.AtomicLong(0)
    private lateinit var browserLayout: View
    private lateinit var fullscreenLayer: FrameLayout
    private lateinit var btnDownload: TextView
    private lateinit var ytDownloader: YouTubeDownloader

    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private lateinit var suggestList: ListView
    private lateinit var suggestions: Suggestions
    private var suggestItems: List<Suggestions.Item> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val permCallbacks = HashMap<Int, (Set<String>) -> Unit>()
    private var nextPermRequest = 100

    /** Bu oturumda sitelere verilen izin kararları (kaynak anahtarı -> izin). */
    private val siteDecisions = HashMap<String, Boolean>()
    private var appliedTextZoom = 100
    private var appliedDarkPages = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var adblockEnabled = true
    private var torEnabled = false
    private lateinit var btnTor: TextView
    private val torHandler = Handler(Looper.getMainLooper())
    private var incognitoProfile: String? = null
    private val docStartSupported by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }
    private val profilesSupported by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        webContainer = findViewById(R.id.webContainer)
        toolbar = findViewById(R.id.toolbar)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        shield = findViewById(R.id.btnShield)
        db = ZetDb.get(this)
        tabSwitcher = TabSwitcher(this, findViewById(R.id.root), SwitcherCallbacks())
        btnTabs = findViewById(R.id.btnTabs)
        bottomBar = findViewById(R.id.bottomBar)
        urlField = findViewById(R.id.urlField)
        siteIcon = findViewById(R.id.siteIcon)
        browserLayout = findViewById(R.id.browser)
        fullscreenLayer = findViewById(R.id.fullscreen)
        btnDownload = findViewById(R.id.btnDownload)
        btnTor = findViewById(R.id.btnTor)
        btnTor.setOnClickListener { showTorStatus() }
        ytDownloader = YouTubeDownloader(
            this,
            findViewById(R.id.downloadBar),
            findViewById(R.id.downloadText),
            findViewById(R.id.downloadProgress),
            findViewById(R.id.downloadCancel),
        )

        adblockEnabled = prefs().getBoolean(PREF_ADBLOCK, true)
        totalBlocked.set(prefs().getLong(PREF_TOTAL_BLOCKED, 0))
        ZetCore.setEnabled(adblockEnabled)
        CookieManager.getInstance().setAcceptCookie(true)
        deleteOldIncognitoProfiles()
        appliedTextZoom = Settings.textZoom(this)
        appliedDarkPages = Settings.darkPages(this)
        setupToolbar()
        setupFindBar()
        setupSuggestions()

        PlaybackService.controller = { action ->
            runOnUiThread {
                mediaTab?.webView?.evaluateJavascript(
                    "window.__zetMedia && window.__zetMedia(${JSONObject.quote(action)})", null,
                )
            }
        }

        thread(name = "zet-filters", isDaemon = true) {
            FilterLists(applicationContext).loadAndRefresh { updating ->
                runOnUiThread {
                    val msg = if (updating) R.string.lists_updating else R.string.lists_ready
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
            ZetCore.setEnabled(adblockEnabled)
        }

        // Tor açık kaldıysa sekmeler yüklenmeden önce proxy'yi kur.
        if (prefs().getBoolean(PREF_TOR, false)) setTor(true, announce = false)

        restoreTabs()
        if (!handleIntent(intent) && tabs.isEmpty()) {
            openTab(NTP_URL, incognito = false)
        }
    }

    // ---- Tor --------------------------------------------------------------

    private fun setTor(on: Boolean, announce: Boolean = true) {
        if (on && !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Toast.makeText(this, R.string.tor_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        torEnabled = on
        prefs().edit().putBoolean(PREF_TOR, on).apply()
        tabs.forEach { updateTorScript(it) }
        if (on) {
            // Proxy kurulana kadar hiçbir isteğin doğrudan gitmemesi için
            // önce erişilemeyen bir proxy'ye yönlendir.
            applyProxy("socks5://127.0.0.1:9")
            btnTor.visibility = View.VISIBLE
            val dir = java.io.File(filesDir, "tor").path
            thread(name = "zet-tor-start") {
                val port = ZetCore.torStart(dir)
                runOnUiThread {
                    if (!torEnabled) return@runOnUiThread
                    if (port > 0) {
                        applyProxy("socks5://127.0.0.1:$port")
                        current?.webView?.reload()
                    }
                    pollTor()
                }
            }
            if (announce) Toast.makeText(this, getString(R.string.tor_starting, 0), Toast.LENGTH_SHORT).show()
        } else {
            torHandler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacksAndMessages(null)
            btnTor.visibility = View.GONE
            ProxyController.getInstance().clearProxyOverride({ it.run() }) {
                current?.webView?.reload()
            }
            thread(name = "zet-tor-stop") { ZetCore.torStop() }
            if (announce) Toast.makeText(this, R.string.tor_disabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyProxy(rule: String) {
        val config = ProxyConfig.Builder().addProxyRule(rule).build()
        ProxyController.getInstance().setProxyOverride(config, { it.run() }) {}
    }

    /** Tor durumunu araç çubuğundaki 🧅 göstergesine yansıtır. */
    private fun pollTor() {
        torHandler.removeCallbacksAndMessages(null)
        val st = JSONObject(ZetCore.torStatus())
        when (st.optString("state")) {
            "ready" -> {
                btnTor.text = "🧅"
                btnTor.alpha = 1f
                if (btnTor.tag != "ready") {
                    btnTor.tag = "ready"
                    Toast.makeText(this, R.string.tor_ready, Toast.LENGTH_SHORT).show()
                }
                return
            }
            "failed" -> {
                btnTor.text = "🧅!"
                btnTor.alpha = 1f
                btnTor.tag = "failed"
                return
            }
            else -> {
                btnTor.text = "🧅${st.optInt("progress")}"
                btnTor.alpha = 0.55f
                btnTor.tag = "starting"
            }
        }
        torHandler.postDelayed(::pollTor, 700)
    }

    private fun showTorStatus() {
        val st = JSONObject(ZetCore.torStatus())
        val msg = when (st.optString("state")) {
            "ready" -> getString(R.string.tor_ready)
            "failed" -> getString(R.string.tor_failed) + ":\n" + st.optString("error")
            "off" -> getString(R.string.tor_disabled)
            else -> getString(R.string.tor_starting, st.optInt("progress"))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tor_status_title)
            .setMessage(msg + "\n\n" + getString(R.string.tor_info))
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.tor_off) { _, _ -> setTor(false) }
            .show()
    }

    /** Tor açıkken WebRTC'yi kapatır (SOCKS'u atlayıp gerçek IP'yi sızdırabilir). */
    private fun updateTorScript(tab: Tab, wv: WebView = tab.webView) {
        if (!docStartSupported) return
        if (torEnabled && tab.torScript == null) {
            tab.torScript = WebViewCompat.addDocumentStartJavaScript(wv, NO_WEBRTC_JS, setOf("*"))
        } else if (!torEnabled) {
            tab.torScript?.remove()
            tab.torScript = null
        }
    }

    // ---- Sekme yönetimi ---------------------------------------------------

    private fun openTab(url: String?, incognito: Boolean, select: Boolean = true): Tab {
        val tab = Tab(incognito)
        val index = current?.let { tabs.indexOf(it) + 1 } ?: tabs.size
        tabs.add(index.coerceIn(0, tabs.size), tab)
        if (url != null) navigate(tab, url)
        if (select) selectTab(tab)
        updateTabCount()
        return tab
    }

    private fun selectTab(tab: Tab) {
        if (current === tab) return
        exitFullscreen()
        if (::findBar.isInitialized) closeFind()
        current?.let {
            captureThumb(it)
            webContainer.removeView(it.webView)
        }
        current = tab
        webContainer.addView(
            tab.webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        tab.pendingUrl?.let {
            tab.pendingUrl = null
            navigate(tab, it)
        }
        tab.webView.requestFocus()
        applyTabTheme(tab)
        showUrl(tab.url)
        progress.visibility = if (tab.loading) View.VISIBLE else View.GONE
        updateShield()
        updateNavButtons()
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (!tab.incognito) {
            tab.url?.takeIf { it.startsWith("http") && it != NTP_URL }?.let {
                recentlyClosed.addFirst(tab.title to it)
                while (recentlyClosed.size > 15) recentlyClosed.removeLast()
            }
        }
        if (mediaTab === tab) {
            mediaTab = null
            PlaybackService.stop(this)
        }
        if (current === tab) {
            webContainer.removeView(tab.webView)
            current = null
            if (tabs.isEmpty()) {
                openTab(NTP_URL, incognito = false)
            } else {
                selectTab(tabs[(index - 1).coerceAtLeast(0)])
            }
        }
        tab.webView.destroy()
        if (tab.incognito && tabs.none { it.incognito }) wipeIncognito()
        updateTabCount()
    }

    private fun updateTabCount() {
        btnTabs.text = if (tabs.size > 99) ":D" else tabs.size.toString()
    }

    private fun applyTabTheme(tab: Tab) {
        val dark = tab.incognito
        val barColor = getColor(if (dark) R.color.incognito_bg else R.color.bar_bg)
        val textColor = if (dark) Color.WHITE else getColor(R.color.text)
        toolbar.setBackgroundColor(barColor)
        bottomBar.setBackgroundColor(barColor)
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        if (dark) {
            urlField.background.mutate().setTint(getColor(R.color.incognito_field))
        } else {
            urlField.background.mutate().setTintList(null)
        }
        urlBar.setTextColor(textColor)
        urlBar.setHintTextColor(if (dark) 0xFFC4B5FD.toInt() else getColor(R.color.text_dim))
        urlBar.setHint(if (dark) R.string.incognito_hint else R.string.url_hint)
        val tint = android.content.res.ColorStateList.valueOf(textColor)
        listOf(R.id.btnHome, R.id.btnBookmarks, R.id.btnSearch, R.id.btnMenu)
            .forEach { findViewById<android.widget.ImageView>(it).imageTintList = tint }
        btnTabs.setTextColor(textColor)
        // Sekme sayacı: içi boş, kenarlıklı kutu.
        btnTabs.background = android.graphics.drawable.GradientDrawable().apply {
            setStroke((2 * resources.displayMetrics.density).toInt(), textColor)
            cornerRadius = 6 * resources.displayMetrics.density
        }
        siteIcon.setTextColor(textColor)
    }

    /** Gizli oturumun profili (her oturumda yeni; kapanınca silinir). */
    private fun incognitoProfileName(): String? {
        if (!profilesSupported) return null
        return incognitoProfile ?: "$INCOGNITO_PREFIX${System.currentTimeMillis()}".also {
            ProfileStore.getInstance().getOrCreateProfile(it)
            incognitoProfile = it
        }
    }

    private fun wipeIncognito() {
        val name = incognitoProfile ?: return
        incognitoProfile = null
        // WebView'lar yok edildikten sonra profil silinebilir.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                ProfileStore.getInstance().deleteProfile(name)
            } catch (e: Exception) {
                // Kullanımdaysa bir sonraki açılışta silinir.
            }
        }, 500)
    }

    private fun deleteOldIncognitoProfiles() {
        if (!profilesSupported) return
        try {
            val store = ProfileStore.getInstance()
            store.allProfileNames
                .filter { it.startsWith(INCOGNITO_PREFIX) }
                .forEach { runCatching { store.deleteProfile(it) } }
        } catch (e: Exception) {
            // yoksay
        }
    }

    /** Normal sekmeleri bir sonraki açılış için kaydeder (gizliler kaydedilmez). */
    private fun saveTabs() {
        val normal = tabs.filter { !it.incognito }
        val arr = JSONArray()
        normal.forEach { t -> t.url?.takeIf { it.startsWith("http") }?.let { arr.put(it) } }
        val currentIndex = current?.takeIf { !it.incognito }?.let { normal.indexOf(it) } ?: 0
        prefs().edit()
            .putString(PREF_TABS, arr.toString())
            .putInt(PREF_CURRENT_TAB, currentIndex.coerceAtLeast(0))
            .apply()
    }

    private fun restoreTabs() {
        val saved = prefs().getString(PREF_TABS, null)
        val urls = if (saved != null) {
            runCatching { JSONArray(saved) }.getOrNull()?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList()
        } else {
            listOfNotNull(prefs().getString(PREF_LAST_URL, null))
        }
        if (urls.isEmpty()) return
        urls.forEach { u ->
            val tab = Tab(incognito = false)
            tab.pendingUrl = u
            tabs.add(tab)
        }
        val idx = prefs().getInt(PREF_CURRENT_TAB, 0).coerceIn(0, tabs.size - 1)
        selectTab(tabs[idx])
        updateTabCount()
    }

    private fun showTabSwitcher() {
        hideKeyboard()
        current?.let { captureThumb(it) }
        tabSwitcher.show(current?.incognito == true)
    }

    /** Sekmenin küçük ekran görüntüsünü alır (sekme ızgarası için). */
    private fun captureThumb(tab: Tab) {
        val wv = tab.webView
        if (wv.width <= 0 || wv.height <= 0) return
        runCatching {
            val scale = 0.4f
            val bmp = android.graphics.Bitmap.createBitmap(
                (wv.width * scale).toInt(), (wv.height * scale).toInt(), android.graphics.Bitmap.Config.RGB_565,
            )
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(scale, scale)
            canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(canvas)
            tab.thumb = bmp
        }
    }

    private inner class SwitcherCallbacks : TabSwitcher.Callbacks {
        override fun tabs() = this@BrowserActivity.tabs.map {
            TabSwitcher.Info(
                it.id, it.title, it.url?.takeIf { u -> u != NTP_URL } ?: "",
                it.incognito, it.thumb, it === current,
            )
        }

        override fun select(id: Int) {
            tabs.find { it.id == id }?.let { selectTab(it) }
            tabSwitcher.hide()
        }

        override fun close(id: Int) {
            tabs.find { it.id == id }?.let { closeTab(it) }
            tabSwitcher.refresh()
        }

        override fun newTab(incognito: Boolean) {
            tabSwitcher.hide()
            if (incognito) openIncognitoTab(NTP_URL) else openTab(NTP_URL, incognito = false)
        }

        override fun closeAll(incognito: Boolean) {
            tabs.filter { it.incognito == incognito }.forEach { closeTab(it) }
            if (incognito && tabs.isNotEmpty()) selectTab(tabs.last())
            tabSwitcher.refresh()
        }
    }

    private fun openIncognitoTab(url: String) {
        if (!profilesSupported) {
            Toast.makeText(this, R.string.incognito_limited, Toast.LENGTH_LONG).show()
        }
        openTab(url, incognito = true)
    }

    // ---- WebView kurulumu -------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: Tab): ZetWebView {
        val wv = ZetWebView(this)
        if (tab.incognito) {
            incognitoProfileName()?.let { WebViewCompat.setProfile(wv, it) }
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            textZoom = appliedTextZoom
            setGeolocationEnabled(true)
            if (tab.incognito && !profilesSupported) cacheMode = WebSettings.LOAD_NO_CACHE
            // "; wv" işaretini kaldır: bazı siteler (Google girişi vb.)
            // gömülü WebView'ı engelliyor, normal Chrome gibi görünelim.
            userAgentString = userAgentString
                .replace("; wv)", ")")
                .replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        tab.mobileUa = wv.settings.userAgentString
        wv.setFindListener { active, total, _ ->
            if (tab === current && findBar.visibility == View.VISIBLE) {
                findCount.text = if (total == 0) "0/0" else "${active + 1}/$total"
            }
        }
        wv.webViewClient = ZetWebViewClient(tab)
        wv.webChromeClient = ZetChromeClient(tab)
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
        wv.setOnLongClickListener { onLongPress(tab) }
        if (Build.VERSION.SDK_INT >= 26) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        wv.addJavascriptInterface(MediaBridge(tab), "ZetMedia")
        if (docStartSupported) {
            tab.backgroundScript = WebViewCompat.addDocumentStartJavaScript(
                wv, ZetCore.ytBackgroundScript(), YT_ORIGINS,
            )
        }
        updateYoutubeScript(tab, wv)
        updateTorScript(tab, wv)
        return wv
    }

    /** YouTube reklam betiğini reklam engelleme ayarına göre ekler/kaldırır. */
    private fun updateYoutubeScript(tab: Tab, wv: WebView = tab.webView) {
        if (!docStartSupported) return
        if (adblockEnabled && tab.adblockScript == null) {
            tab.adblockScript = WebViewCompat.addDocumentStartJavaScript(
                wv, ZetCore.ytAdblockScript(), YT_ORIGINS,
            )
        } else if (!adblockEnabled) {
            tab.adblockScript?.remove()
            tab.adblockScript = null
        }
    }

    /** Sayfadaki betiğin çalma durumunu bildirdiği köprü. */
    private inner class MediaBridge(private val tab: Tab) {
        @JavascriptInterface
        fun onState(playing: Boolean, title: String) {
            runOnUiThread {
                if (tabs.contains(tab) && isYoutube(tab.webView.url)) {
                    if (playing) {
                        mediaTab?.webView?.keepPlaying = false
                        mediaTab = tab
                    }
                    if (mediaTab === tab) {
                        // Çalarken ekran kapanınca duraklatılmasın.
                        tab.webView.keepPlaying = playing && Settings.backgroundPlay(this@BrowserActivity)
                        PlaybackService.report(this@BrowserActivity, title, playing)
                    }
                }
            }
        }
    }

    private fun onLongPress(tab: Tab): Boolean {
        val result = tab.webView.hitTestResult
        when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                result.extra?.let { showLinkMenu(it, tab.incognito) }
                return result.extra != null
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val handler = Handler(Looper.getMainLooper()) { msg ->
                    msg.data.getString("url")?.let { showLinkMenu(it, tab.incognito) }
                    true
                }
                tab.webView.requestFocusNodeHref(handler.obtainMessage())
                return true
            }
        }
        return false
    }

    private fun showLinkMenu(url: String, fromIncognito: Boolean) {
        val items = arrayOf(
            getString(R.string.open_in_new_tab),
            getString(R.string.open_in_incognito),
            getString(R.string.copy_link),
            getString(R.string.share_link),
        )
        AlertDialog.Builder(this)
            .setTitle(url)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openTab(url, incognito = fromIncognito)
                    1 -> openIncognitoTab(url)
                    2 -> {
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("url", url))
                        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
                    }
                    3 -> shareText(url)
                }
            }
            .show()
    }

    private fun shareText(text: String) {
        openExternal(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                null,
            ),
        )
    }

    private fun isYoutube(url: String?): Boolean {
        val host = url?.let { Uri.parse(it).host } ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com")
    }

    // ---- Araç çubuğu ------------------------------------------------------

    private fun setupToolbar() {
        findViewById<View>(R.id.btnHome).setOnClickListener { current?.let { navigate(it, NTP_URL) } }
        findViewById<View>(R.id.btnBookmarks).setOnClickListener {
            startActivityForResult(LibraryActivity.intent(this, LibraryActivity.MODE_BOOKMARKS), REQ_LIBRARY)
        }
        findViewById<View>(R.id.btnSearch).setOnClickListener { focusUrlBar() }
        btnTabs.setOnClickListener { showTabSwitcher() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        btnDownload.setOnClickListener {
            val id = ZetCore.ytVideoId(current?.url ?: "")
            if (id.isNotEmpty()) ytDownloader.start(id)
        }
        shield.setOnClickListener {
            adblockEnabled = !adblockEnabled
            ZetCore.setEnabled(adblockEnabled)
            prefs().edit().putBoolean(PREF_ADBLOCK, adblockEnabled).apply()
            tabs.forEach { updateYoutubeScript(it) }
            val msg = if (adblockEnabled) R.string.adblock_on else R.string.adblock_off
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            updateShield()
            current?.webView?.reload()
        }
        urlBar.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                load(normalize(urlBar.text.toString()))
                hideKeyboard()
                true
            } else {
                false
            }
        }
        urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                showUrl(current?.url)
                if (::suggestions.isInitialized) hideSuggestions()
            }
        }
    }

    private fun showMenu(anchor: View) {
        val tab = current ?: return
        val wv = tab.webView
        val pageUrl = wv.url?.takeIf { it.startsWith("http") && it != NTP_URL }
        val bookmarked = pageUrl != null && db.isBookmarked(pageUrl)
        BrowserMenu(this).show(
            anchor,
            listOf(
                BrowserMenu.Item(R.drawable.ic_add, R.string.new_tab) { openTab(NTP_URL, incognito = false) },
                BrowserMenu.Item(R.drawable.ic_incognito, R.string.new_incognito_tab) { openIncognitoTab(NTP_URL) },
                null,
                BrowserMenu.Item(R.drawable.ic_history, R.string.history) {
                    startActivityForResult(LibraryActivity.intent(this, LibraryActivity.MODE_HISTORY), REQ_LIBRARY)
                },
                BrowserMenu.Item(R.drawable.ic_download, R.string.downloads) {
                    openExternal(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                },
                BrowserMenu.Item(R.drawable.ic_bookmarks, R.string.bookmarks) {
                    startActivityForResult(LibraryActivity.intent(this, LibraryActivity.MODE_BOOKMARKS), REQ_LIBRARY)
                },
                BrowserMenu.Item(R.drawable.ic_recent_tabs, R.string.recent_tabs) { showRecentTabs() },
                null,
                BrowserMenu.Item(R.drawable.ic_find, R.string.find_in_page) { openFind() },
                BrowserMenu.Item(R.drawable.ic_reader, R.string.reader_mode) { toggleReader(tab) },
                BrowserMenu.Item(R.drawable.ic_translate, R.string.translate_page) {
                    pageUrl?.let { translate(tab, it) }
                },
                BrowserMenu.Item(R.drawable.ic_desktop, R.string.desktop_site, toggle = tab.desktop) {
                    toggleDesktop(tab)
                },
                BrowserMenu.Item(R.drawable.ic_dark, R.string.dark_pages, toggle = appliedDarkPages) {
                    setDarkPages(!appliedDarkPages)
                },
                BrowserMenu.Item(R.drawable.ic_text_size, R.string.text_size) { showTextSize() },
                BrowserMenu.Item(R.drawable.ic_phone, R.string.add_to_home) {
                    pageUrl?.let { addToHome(tab, it) }
                },
                BrowserMenu.Item(R.drawable.ic_print, R.string.print_page) { printPage(tab) },
                null,
                BrowserMenu.Item(R.drawable.ic_settings, R.string.settings) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                },
                BrowserMenu.Item(R.drawable.ic_shield, R.string.tor_vpn, toggle = torEnabled) { setTor(!torEnabled) },
                BrowserMenu.Item(R.drawable.ic_exit, R.string.exit) { exitApp() },
            ),
            listOf(
                BrowserMenu.Action(R.drawable.ic_forward, R.string.forward, wv.canGoForward()) { wv.goForward() },
                BrowserMenu.Action(R.drawable.ic_share, R.string.share_page, pageUrl != null) {
                    pageUrl?.let { shareText(it) }
                },
                BrowserMenu.Action(
                    if (bookmarked) R.drawable.ic_star_filled else R.drawable.ic_star,
                    R.string.add_bookmark, pageUrl != null,
                ) { pageUrl?.let { toggleBookmark(it, tab.title) } },
                BrowserMenu.Action(R.drawable.ic_refresh, R.string.reload) {
                    if (tab.loading) wv.stopLoading() else wv.reload()
                },
            ),
        )
    }

    private fun toggleBookmark(url: String, title: String) {
        if (db.isBookmarked(url)) {
            db.removeBookmark(url)
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        } else {
            db.addBookmark(url, title)
            Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRecentTabs() {
        if (recentlyClosed.isEmpty()) {
            Toast.makeText(this, R.string.no_recent_tabs, Toast.LENGTH_SHORT).show()
            return
        }
        val list = recentlyClosed.toList()
        AlertDialog.Builder(this)
            .setTitle(R.string.recent_tabs)
            .setItems(list.map { it.first.ifBlank { it.second } }.toTypedArray()) { _, which ->
                val (_, url) = list[which]
                recentlyClosed.remove(list[which])
                openTab(url, incognito = false)
            }
            .show()
    }

    private fun exitApp() {
        saveTabs()
        PlaybackService.stop(this)
        finishAndRemoveTask()
    }

    /** Adres çubuğu girdisini seçili arama motoruyla URL'ye çevirir. */
    private fun normalize(input: String): String =
        ZetCore.normalizeInputWith(input, Settings.engine(this).prefix)

    /** Yeni sekme sayfası özel adres; diğerleri normal yüklenir. */
    private fun navigate(tab: Tab, url: String) {
        if (url == NTP_URL) {
            tab.webView.loadDataWithBaseURL(NTP_URL, newTabHtml(tab), "text/html", "utf-8", NTP_URL)
        } else {
            tab.webView.loadUrl(url)
        }
    }

    private fun newTabHtml(tab: Tab): String {
        val blocked = totalBlocked.get()
        val savedSec = blocked * 50 / 1000 // istek başına ~50 ms
        val saved = when {
            savedSec >= 3600 -> "${savedSec / 3600} sa ${savedSec % 3600 / 60} dk"
            savedSec >= 60 -> "${savedSec / 60} dk"
            else -> "$savedSec sn"
        }
        val mode = when {
            tab.incognito && torEnabled -> "Gizli sekme · Tor açık"
            tab.incognito -> "Gizli sekme: geçmiş, çerez ve site verileri kaydedilmez"
            torEnabled -> "Tor açık: trafiğin Tor ağından geçiyor"
            else -> ""
        }
        val tpl = assets.open("newtab.html").bufferedReader().use { it.readText() }
        return tpl
            .replace("{{TILES}}", topSiteTiles())
            .replace("{{BLOCKED}}", java.text.NumberFormat.getInstance(java.util.Locale("tr")).format(blocked))
            .replace("{{SAVED}}", saved)
            .replace("{{MODE}}", mode)
    }

    /** Yeni sekme sayfası kısayolları: en çok ziyaret edilenler, yoksa varsayılanlar. */
    private fun topSiteTiles(): String {
        val defaults = listOf(
            "YouTube" to "https://m.youtube.com", "Google" to "https://www.google.com",
            "Vikipedi" to "https://tr.m.wikipedia.org", "X" to "https://x.com",
            "Instagram" to "https://www.instagram.com", "Reddit" to "https://www.reddit.com",
            "Ekşi" to "https://eksisozluk.com", "GitHub" to "https://github.com",
        )
        val visited = if (Settings.saveHistory(this)) {
            runCatching { db.topSites(8) }.getOrDefault(emptyList())
                .map { LibraryActivity.host(it.url) to it.url }
        } else {
            emptyList()
        }
        val seen = HashSet<String>()
        val sites = (visited + defaults).filter { seen.add(LibraryActivity.host(it.second)) }.take(8)
        val colors = listOf("#dc2626", "#2563eb", "#475569", "#0f766e", "#c026d3", "#ea580c", "#65a30d", "#7c3aed")
        return sites.mapIndexed { i, (name, url) ->
            val host = LibraryActivity.host(url)
            val origin = Uri.parse(url).let { "${it.scheme}://${it.host}" }
            val label = android.text.TextUtils.htmlEncode(name.removeSuffix(".com").take(12))
            val letter = android.text.TextUtils.htmlEncode(LibraryActivity.siteLetter(url))
            "<a class=\"tile\" href=\"${android.text.TextUtils.htmlEncode(url)}\">" +
                "<div class=\"ic\" style=\"background:${colors[i % colors.size]}\">$letter" +
                "<img src=\"$origin/favicon.ico\" alt=\"\" onerror=\"this.remove()\"></div>" +
                "${if (label.isNotBlank()) label else android.text.TextUtils.htmlEncode(host)}</a>"
        }.joinToString("")
    }

    private fun focusUrlBar() {
        urlBar.requestFocus()
        urlBar.selectAll()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun load(url: String) {
        val tab = current ?: openTab(null, incognito = false)
        tab.pendingUrl = null
        navigate(tab, url)
        tab.webView.requestFocus()
    }

    private fun showUrl(url: String?) {
        val isNtp = url == null || url == NTP_URL || url == "about:blank"
        if (!urlBar.hasFocus()) urlBar.setText(if (isNtp) "" else url)
        siteIcon.text = when {
            isNtp -> "🔍"
            url?.startsWith("https://") == true -> "🔒"
            else -> "⚠"
        }
        val isVideo = url != null && ZetCore.ytVideoId(url).isNotEmpty()
        btnDownload.visibility = if (isVideo) View.VISIBLE else View.GONE
    }

    private fun updateShield() {
        if (adblockEnabled) {
            shield.text = "🛡 ${current?.blocked?.get() ?: 0}"
            shield.background.mutate().setTint(getColor(R.color.accent))
        } else {
            shield.text = "🛡 " + getString(R.string.adblock_off_short)
            shield.background.mutate().setTint(getColor(R.color.accent_off))
        }
    }

    private fun updateNavButtons() = Unit

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

    private inner class ZetWebViewClient(private val tab: Tab) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (request.isForMainFrame) {
                tab.pageUrl = url
                return null
            }
            if (ZetCore.shouldBlock(url, tab.pageUrl, false) == 1) {
                tab.blocked.incrementAndGet()
                totalBlocked.incrementAndGet()
                if (tab === current) runOnUiThread { updateShield() }
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
                "zet" -> {
                    if (uri.host == "focus") focusUrlBar()
                    true
                }
                "intent" -> {
                    openIntentUri(view, uri.toString()); true
                }
                else -> {
                    openExternal(Intent(Intent.ACTION_VIEW, uri)); true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            tab.blocked.set(0)
            tab.loading = true
            // Eski WebView'larda belge başı betiği yok: mümkün olan en erken anda enjekte et.
            if (!docStartSupported && isYoutube(url)) {
                if (adblockEnabled) view.evaluateJavascript(ZetCore.ytAdblockScript(), null)
                view.evaluateJavascript(ZetCore.ytBackgroundScript(), null)
            }
            if (mediaTab === tab && !isYoutube(url)) {
                tab.webView.keepPlaying = false
                mediaTab = null
                PlaybackService.stop(this@BrowserActivity)
            }
            if (tab !== current) return
            updateShield()
            showUrl(url)
            progress.visibility = View.VISIBLE
            updateNavButtons()
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            injectCosmetics(view, url)
            applyPageExtras(tab, url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            tab.loading = false
            if (!tab.incognito && url != null && url.startsWith("http") && url != NTP_URL &&
                Settings.saveHistory(this@BrowserActivity)
            ) {
                val title = view.title?.takeIf { it != url } ?: ""
                thread { runCatching { db.addVisit(url, title) } }
            }
            injectCosmetics(view, url)
            applyPageExtras(tab, url)
            injectGenericCosmetics(tab, url)
            // Sonradan beliren çerez uyarıları için bir tur daha.
            uiHandler.postDelayed({ injectGenericCosmetics(tab, url) }, 2500)
            if (tab !== current) return
            progress.visibility = View.GONE
            showUrl(url)
            updateNavButtons()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (tab !== current) return
            showUrl(url)
            updateNavButtons()
        }
    }

    private inner class ZetChromeClient(private val tab: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (tab !== current) return
            progress.progress = newProgress
            progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title ?: ""
            val url = view.url
            if (!tab.incognito && url != null && url.startsWith("http") && !title.isNullOrBlank() &&
                Settings.saveHistory(this@BrowserActivity)
            ) {
                thread { runCatching { db.updateTitle(url, title) } }
            }
        }

        /** target=_blank ve window.open: yalnızca kullanıcı dokunduysa yeni sekme aç. */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            if (!isUserGesture) return false
            val newTab = openTab(null, incognito = tab.incognito)
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = newTab.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            tabs.find { it.webView === window }?.let { closeTab(it) }
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

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            val intent = params.createIntent().apply {
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_FILE)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                Toast.makeText(this@BrowserActivity, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread { handlePermissionRequest(request) }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback,
        ) {
            askSite(origin, Uri.parse(origin).host ?: origin, getString(R.string.perm_location), "geo",
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            ) { granted -> callback.invoke(origin, granted.isNotEmpty(), false) }
        }
    }

    // ---- Site izinleri ----------------------------------------------------

    private fun handlePermissionRequest(request: PermissionRequest) {
        val res = request.resources.toSet()
        val autoGrant = res.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
        val wants = mutableListOf<String>()
        val perms = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in res) {
            wants += getString(R.string.perm_camera)
            perms += android.Manifest.permission.CAMERA
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in res) {
            wants += getString(R.string.perm_mic)
            perms += android.Manifest.permission.RECORD_AUDIO
        }
        if (wants.isEmpty()) {
            if (autoGrant.isNotEmpty()) request.grant(autoGrant.toTypedArray()) else request.deny()
            return
        }
        val origin = request.origin.toString()
        askSite(origin, request.origin.host ?: origin, wants.joinToString(" ve "), perms.joinToString(","),
            perms.toTypedArray(),
        ) { granted ->
            val ok = autoGrant.toMutableList()
            if (android.Manifest.permission.CAMERA in granted &&
                PermissionRequest.RESOURCE_VIDEO_CAPTURE in res
            ) ok += PermissionRequest.RESOURCE_VIDEO_CAPTURE
            if (android.Manifest.permission.RECORD_AUDIO in granted &&
                PermissionRequest.RESOURCE_AUDIO_CAPTURE in res
            ) ok += PermissionRequest.RESOURCE_AUDIO_CAPTURE
            if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
        }
    }

    /**
     * Siteye izin sorar (oturum boyunca hatırlanır), sonra gereken Android
     * izinlerini ister. [onResult] verilen Android izinlerini alır (boş = red).
     */
    private fun askSite(
        origin: String,
        host: String,
        what: String,
        key: String,
        perms: Array<String>,
        onResult: (Set<String>) -> Unit,
    ) {
        val decisionKey = "$origin|$key"
        val proceed = { allowed: Boolean ->
            siteDecisions[decisionKey] = allowed
            if (allowed) withAndroidPermissions(perms, onResult) else onResult(emptySet())
        }
        siteDecisions[decisionKey]?.let { proceed(it); return }
        var answered = false
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.perm_ask, host, what))
            .setPositiveButton(R.string.allow) { _, _ -> answered = true; proceed(true) }
            .setNegativeButton(R.string.deny) { _, _ -> answered = true; proceed(false) }
            .setOnDismissListener { if (!answered) onResult(emptySet()) }
            .show()
    }

    private fun withAndroidPermissions(perms: Array<String>, onResult: (Set<String>) -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            onResult(perms.toSet())
            return
        }
        val code = nextPermRequest++
        permCallbacks[code] = onResult
        requestPermissions(missing.toTypedArray(), code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val cb = permCallbacks.remove(requestCode) ?: return
        // İstenmeden önce zaten verilmiş olanlar da sayılır.
        val granted = permissions.filterIndexed { i, _ ->
            grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
        }.toMutableSet()
        cb(granted + permissions.filter { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }

    // ---- Sayfa araçları ---------------------------------------------------

    private fun isWebPage(url: String?) = url != null && url.startsWith("http") && url != NTP_URL

    /** Her sayfa yüklemesinde: koyu sayfa ve masaüstü görünümü. */
    private fun applyPageExtras(tab: Tab, url: String?) {
        if (!isWebPage(url)) return
        val wv = tab.webView
        if (appliedDarkPages) wv.evaluateJavascript(PageScripts.DARK_ON, null)
        if (tab.desktop) {
            wv.evaluateJavascript(
                "(function(){var m=document.querySelector('meta[name=viewport]');" +
                    "if(m)m.content='width=1100';})();",
                null,
            )
        }
    }

    /** Genel gizleme kuralları: sayfadaki sınıf/kimliklere göre Rust motorundan alınır. */
    private fun injectGenericCosmetics(tab: Tab, url: String?) {
        if (!adblockEnabled || !isWebPage(url) || !tabs.contains(tab)) return
        val wv = tab.webView
        if (wv.url != url) return
        wv.evaluateJavascript(PageScripts.COLLECT_CLASS_IDS) { res ->
            val json = runCatching { JSONObject(JSONArray("[$res]").getString(0)) }.getOrNull()
                ?: return@evaluateJavascript
            val c = json.optJSONArray("c") ?: JSONArray()
            val i = json.optJSONArray("i") ?: JSONArray()
            val classes = Array(c.length()) { c.getString(it) }
            val ids = Array(i.length()) { i.getString(it) }
            thread(name = "zet-generic", isDaemon = true) {
                val css = ZetCore.cosmeticGeneric(url!!, classes, ids)
                if (css.isEmpty()) return@thread
                runOnUiThread {
                    if (tabs.contains(tab) && wv.url == url) {
                        wv.evaluateJavascript(PageScripts.injectStyle("zet-generic", css), null)
                    }
                }
            }
        }
    }

    private fun setDarkPages(on: Boolean) {
        appliedDarkPages = on
        Settings.prefs(this).edit().putBoolean(Settings.DARK_PAGES, on).apply()
        for (t in tabs) {
            if (isWebPage(t.webView.url)) {
                t.webView.evaluateJavascript(if (on) PageScripts.DARK_ON else PageScripts.DARK_OFF, null)
            }
        }
    }

    private fun applyTextZoom(zoom: Int) {
        appliedTextZoom = zoom
        tabs.forEach { it.webView.settings.textZoom = zoom }
    }

    private fun showTextSize() {
        val label = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.text))
            gravity = Gravity.CENTER
        }
        val update = { z: Int ->
            label.text = getString(R.string.text_size_desc, z)
            applyTextZoom(z)
            Settings.prefs(this).edit().putInt(Settings.TEXT_ZOOM, z).apply()
        }
        val bar = SeekBar(this).apply {
            max = 15 // %50 .. %200, 10'ar
            progress = (appliedTextZoom - 50) / 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) update(50 + p * 10)
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        }
        label.text = getString(R.string.text_size_desc, appliedTextZoom)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(label)
            addView(bar)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.text_size)
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("%100") { _, _ -> update(100) }
            .show()
    }

    private fun toggleDesktop(tab: Tab) {
        tab.desktop = !tab.desktop
        tab.webView.settings.userAgentString =
            if (tab.desktop) PageScripts.desktopUserAgent(tab.mobileUa) else tab.mobileUa
        tab.webView.reload()
    }

    private fun toggleReader(tab: Tab) {
        if (!isWebPage(tab.webView.url)) return
        tab.webView.evaluateJavascript(PageScripts.READER) { r ->
            if (r?.contains("none") == true) {
                Toast.makeText(this, R.string.reader_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun translate(tab: Tab, url: String) {
        val enc = java.net.URLEncoder.encode(url, "UTF-8")
        navigate(tab, "https://translate.google.com/translate?sl=auto&tl=tr&hl=tr&u=$enc")
    }

    private fun printPage(tab: Tab) {
        val pm = getSystemService(PrintManager::class.java) ?: return
        val name = tab.title.ifBlank { "Zet" }
        pm.print(name, tab.webView.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
    }

    private fun addToHome(tab: Tab, url: String) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, R.string.home_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val label = tab.title.ifBlank { LibraryActivity.host(url) }.take(24)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), this, BrowserActivity::class.java)
        val info = ShortcutInfo.Builder(this, "site-${url.hashCode()}")
            .setShortLabel(label)
            .setIcon(Icon.createWithBitmap(shortcutIcon(tab.webView.favicon, url)))
            .setIntent(intent)
            .build()
        sm.requestPinShortcut(info, null)
    }

    /** Kısayol ikonu: yeterince büyük favicon varsa o, yoksa harfli renkli daire. */
    private fun shortcutIcon(favicon: Bitmap?, url: String): Bitmap {
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (favicon != null && favicon.width >= 32) {
            paint.color = Color.WHITE
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            val inset = size / 5
            canvas.drawBitmap(
                favicon, null,
                android.graphics.Rect(inset, inset, size - inset, size - inset),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        } else {
            val colors = intArrayOf(0xFF2563EB.toInt(), 0xFF16A34A.toInt(), 0xFFDC2626.toInt(), 0xFF7C3AED.toInt())
            paint.color = colors[Math.floorMod(url.hashCode(), colors.size)]
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = Color.WHITE
            paint.textSize = size * 0.45f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(LibraryActivity.siteLetter(url), size / 2f, y, paint)
        }
        return out
    }

    // ---- Sayfada bul -----------------------------------------------------

    private fun setupFindBar() {
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
        findInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                if (findBar.visibility != View.VISIBLE) return
                val q = e?.toString().orEmpty()
                if (q.isEmpty()) {
                    current?.webView?.clearMatches()
                    findCount.text = ""
                } else {
                    current?.webView?.findAllAsync(q)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        findInput.setOnEditorActionListener { _, _, _ ->
            current?.webView?.findNext(true); true
        }
        findViewById<View>(R.id.findPrev).setOnClickListener { current?.webView?.findNext(false) }
        findViewById<View>(R.id.findNext).setOnClickListener { current?.webView?.findNext(true) }
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }
    }

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findCount.text = ""
        findInput.setText("")
        findInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE
        current?.webView?.clearMatches()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(findInput.windowToken, 0)
    }

    // ---- Adres çubuğu önerileri ------------------------------------------

    private fun setupSuggestions() {
        suggestions = Suggestions(this, db)
        suggestList = findViewById(R.id.suggestList)
        val adapter = SuggestAdapter()
        suggestList.adapter = adapter
        suggestList.setOnItemClickListener { _, _, pos, _ ->
            val item = suggestItems.getOrNull(pos) ?: return@setOnItemClickListener
            hideSuggestions()
            load(item.url ?: normalize(item.text))
            hideKeyboard()
        }
        urlBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                if (!urlBar.hasFocus()) return
                val remote = Settings.suggestions(this@BrowserActivity) && !torEnabled &&
                    current?.incognito != true
                suggestions.query(e?.toString().orEmpty(), remote) { items ->
                    if (!urlBar.hasFocus()) return@query
                    suggestItems = items
                    adapter.notifyDataSetChanged()
                    suggestList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun hideSuggestions() {
        suggestions.cancel()
        suggestItems = emptyList()
        suggestList.visibility = View.GONE
    }

    private inner class SuggestAdapter : BaseAdapter() {
        override fun getCount() = suggestItems.size
        override fun getItem(position: Int) = suggestItems[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = suggestItems[position]
            val dp = resources.displayMetrics.density
            val row = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (52 * dp).toInt()
                setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            }
            row.addView(ImageView(this@BrowserActivity).apply {
                setImageResource(
                    when (item.kind) {
                        1 -> R.drawable.ic_history
                        2 -> R.drawable.ic_star
                        else -> R.drawable.ic_search
                    },
                )
                alpha = 0.7f
                layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply {
                    marginEnd = (16 * dp).toInt()
                }
            })
            val texts = LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this@BrowserActivity).apply {
                text = item.text
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.text))
            })
            if (item.url != null && item.url != item.text) {
                texts.addView(TextView(this@BrowserActivity).apply {
                    text = item.url
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.text_dim))
                })
            }
            row.addView(texts)
            return row
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
                val cookies = current?.takeIf { it.incognito && profilesSupported }
                    ?.let { WebViewCompat.getProfile(it.webView).cookieManager }
                    ?: CookieManager.getInstance()
                cookies.getCookie(url)?.let { addRequestHeader("Cookie", it) }
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

    /** Dışarıdan gelen link / paylaşım / arama: yeni sekmede açılır. */
    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val target = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { extractUrl(it) }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")
            else -> null
        } ?: return false
        openTab(normalize(target), incognito = false)
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
        val wv = current?.webView
        when {
            tabSwitcher.isShowing -> tabSwitcher.hide()
            customView != null -> exitFullscreen()
            findBar.visibility == View.VISIBLE -> closeFind()
            urlBar.hasFocus() -> {
                urlBar.clearFocus(); hideKeyboard()
            }
            wv != null && wv.canGoBack() -> wv.goBack()
            // Geçmişi olmayan sekme: kapat (son sekme değilse).
            current != null && tabs.size > 1 -> closeTab(current!!)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    private fun prefs() = getSharedPreferences("zet", Context.MODE_PRIVATE)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val cb = fileCallback
            fileCallback = null
            val uris = if (resultCode == RESULT_OK && data != null) {
                data.clipData?.let { c -> Array(c.itemCount) { c.getItemAt(it).uri } }
                    ?: data.data?.let { arrayOf(it) }
            } else {
                null
            }
            cb?.onReceiveValue(uris)
            return
        }
        if (requestCode != REQ_LIBRARY || resultCode != RESULT_OK) return
        val url = data?.getStringExtra(LibraryActivity.EXTRA_URL) ?: return
        if (data.getBooleanExtra(LibraryActivity.EXTRA_NEW_TAB, false)) {
            openTab(url, incognito = current?.incognito == true)
        } else {
            load(url)
        }
    }

    override fun onResume() {
        super.onResume()
        // Ayarlar ekranında değişmiş olabilir.
        val adblock = prefs().getBoolean(PREF_ADBLOCK, true)
        if (adblock != adblockEnabled) {
            adblockEnabled = adblock
            ZetCore.setEnabled(adblock)
            tabs.forEach { updateYoutubeScript(it) }
            updateShield()
        }
        val zoom = Settings.textZoom(this)
        if (zoom != appliedTextZoom) applyTextZoom(zoom)
        val dark = Settings.darkPages(this)
        if (dark != appliedDarkPages) setDarkPages(dark)
        ytDownloader.startWatching()
    }

    override fun onPause() {
        super.onPause()
        ytDownloader.stopWatching()
        saveTabs()
        prefs().edit().putLong(PREF_TOTAL_BLOCKED, totalBlocked.get()).apply()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        torHandler.removeCallbacksAndMessages(null)
        PlaybackService.controller = null
        PlaybackService.stop(this)
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    companion object {
        /** Yeni sekme sayfasının (yerel HTML) adresi. */
        private const val NTP_URL = "https://zet.local/newtab"
        private const val PREF_TOTAL_BLOCKED = "total_blocked"
        private const val REQ_LIBRARY = 7
        private const val REQ_FILE = 8
        private const val INCOGNITO_PREFIX = "zet-incognito-"
        private val YT_ORIGINS = setOf(
            "https://www.youtube.com", "https://m.youtube.com",
            "https://youtube.com", "https://music.youtube.com",
        )
        private const val PREF_ADBLOCK = "adblock_enabled"
        private const val PREF_LAST_URL = "last_url"
        private const val PREF_TABS = "tabs"
        private const val PREF_CURRENT_TAB = "current_tab"
        private const val PREF_TOR = "tor_enabled"
        private const val NO_WEBRTC_JS = "(function(){['RTCPeerConnection','webkitRTCPeerConnection'," +
            "'RTCDataChannel','RTCSessionDescription','RTCIceCandidate'].forEach(function(k){" +
            "try{Object.defineProperty(window,k,{value:undefined,writable:false,configurable:false})}" +
            "catch(e){}});})();"
    }
}
