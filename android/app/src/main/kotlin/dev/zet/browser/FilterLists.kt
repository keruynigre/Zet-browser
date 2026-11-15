package dev.zet.browser

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Reklam filtre listelerini indirir, günceller ve Rust motoruna yükler. */
class FilterLists(context: Context) {
    private val dir = File(context.filesDir, "filters").apply { mkdirs() }
    private val cookies = Settings.cookieBanners(context)

    init {
        File(dir, "engine-v2.bin").delete() // eski sürümün önbelleği
    }

    // Liste kümesi değişince (çerez listesi aç/kapa) ayrı önbellek kullanılır.
    private val cache = File(dir, if (cookies) "engine-v3-c.bin" else "engine-v3.bin")

    private val sources = listOfNotNull(
        "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
        "peter-lowe.txt" to
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
        if (cookies) "fanboy-cookie.txt" to "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt" else null,
    )

    /**
     * Arka planda çağrılmalı. Önce önbellekten anında yükler, listeler
     * eskiyse indirip motoru yeniden derler.
     * [onStatus] true = güncelleme başladı, false = bitti.
     */
    fun loadAndRefresh(onStatus: (updating: Boolean) -> Unit) {
        val fromCache = cache.exists() && ZetCore.loadCache(cache.path)
        val files = sources.map { File(dir, it.first) }
        val stale = files.any { !it.exists() || age(it) > MAX_AGE_MS }

        if (!stale && fromCache) return

        if (stale) {
            onStatus(true)
            for ((name, url) in sources) {
                download(url, File(dir, name))
            }
        }
        val existing = files.filter { it.exists() && it.length() > 0 }.map { it.path }
        if (existing.isNotEmpty()) {
            ZetCore.buildFromFiles(existing.toTypedArray(), cache.path)
        }
        if (stale) onStatus(false)
    }

    private fun age(f: File) = System.currentTimeMillis() - f.lastModified()

    private fun download(url: String, target: File) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "ZetBrowser")
            if (conn.responseCode != 200) {
                Log.w(TAG, "$url -> HTTP ${conn.responseCode}")
                return
            }
            val tmp = File(target.path + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() > 0) tmp.renameTo(target) else tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "İndirilemedi: $url", e)
        }
    }

    companion object {
        private const val TAG = "ZetFilters"
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(4)
    }
}
