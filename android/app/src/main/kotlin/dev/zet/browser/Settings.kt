package dev.zet.browser

import android.content.Context

/** Uygulama ayarları (SharedPreferences "zet"). */
object Settings {
    data class Engine(val id: String, val name: String, val prefix: String)

    val ENGINES = listOf(
        Engine("google", "Google", "https://www.google.com/search?q="),
        Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="),
        Engine("bing", "Bing", "https://www.bing.com/search?q="),
        Engine("yandex", "Yandex", "https://yandex.com.tr/search/?text="),
        Engine("startpage", "Startpage", "https://www.startpage.com/do/search?q="),
    )

    const val ADBLOCK = "adblock_enabled"
    const val ENGINE = "search_engine"
    const val BACKGROUND_PLAY = "background_play"
    const val SAVE_HISTORY = "save_history"
    const val TEXT_ZOOM = "text_zoom"
    const val DARK_PAGES = "dark_pages"
    const val SUGGESTIONS = "search_suggestions"
    const val COOKIE_BANNERS = "cookie_banners"
    const val SAVE_PASSWORDS = "save_passwords"
    const val PULL_REFRESH = "pull_refresh"
    const val HTTPS_FIRST = "https_first"

    fun prefs(c: Context) = c.getSharedPreferences("zet", Context.MODE_PRIVATE)

    fun engine(c: Context): Engine {
        val id = prefs(c).getString(ENGINE, "google")
        return ENGINES.firstOrNull { it.id == id } ?: ENGINES[0]
    }

    fun backgroundPlay(c: Context) = prefs(c).getBoolean(BACKGROUND_PLAY, true)
    fun saveHistory(c: Context) = prefs(c).getBoolean(SAVE_HISTORY, true)
    fun textZoom(c: Context) = prefs(c).getInt(TEXT_ZOOM, 100)
    fun darkPages(c: Context) = prefs(c).getBoolean(DARK_PAGES, false)
    fun suggestions(c: Context) = prefs(c).getBoolean(SUGGESTIONS, true)
    fun cookieBanners(c: Context) = prefs(c).getBoolean(COOKIE_BANNERS, true)
    fun savePasswords(c: Context) = prefs(c).getBoolean(SAVE_PASSWORDS, true)
    fun pullRefresh(c: Context) = prefs(c).getBoolean(PULL_REFRESH, true)
    fun httpsFirst(c: Context) = prefs(c).getBoolean(HTTPS_FIRST, true)
}
