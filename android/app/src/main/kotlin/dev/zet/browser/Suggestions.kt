package dev.zet.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Adres çubuğu önerileri: geçmiş ve yer işaretlerinden eşleşmeler +
 * (izin verilirse) arama motorunun öneri servisi.
 */
class Suggestions(private val context: Context, private val db: ZetDb) {

    /** kind: 0 = arama önerisi, 1 = geçmiş, 2 = yer işareti */
    data class Item(val kind: Int, val text: String, val url: String?)

    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var generation = 0

    /** [remote] false ise ağa hiç çıkılmaz (gizli sekme / Tor). */
    fun query(text: String, remote: Boolean, onResult: (List<Item>) -> Unit) {
        val gen = ++generation
        val q = text.trim()
        if (q.isEmpty()) {
            onResult(emptyList())
            return
        }
        thread(name = "zet-suggest", isDaemon = true) {
            val local = runCatching { localMatches(q) }.getOrDefault(emptyList())
            main.post { if (gen == generation) onResult(local) }
            if (!remote) return@thread
            // Yazmaya devam ediliyorsa boşuna istek atma.
            Thread.sleep(180)
            if (gen != generation) return@thread
            val web = runCatching { fetchRemote(q) }.getOrDefault(emptyList())
            if (web.isEmpty()) return@thread
            val seen = local.mapTo(HashSet()) { it.text.lowercase() }
            val merged = local.take(3) + web.filter { seen.add(it.lowercase()) }.take(6).map { Item(0, it, null) }
            main.post { if (gen == generation) onResult(merged) }
        }
    }

    fun cancel() {
        generation++
    }

    private fun localMatches(q: String): List<Item> {
        val out = mutableListOf<Item>()
        val seen = HashSet<String>()
        for (b in db.bookmarks(q).take(3)) {
            if (seen.add(b.url)) out += Item(2, b.title.ifBlank { b.url }, b.url)
        }
        if (Settings.saveHistory(context)) {
            for (h in db.history(q, 20)) {
                if (out.size >= 5) break
                if (seen.add(h.url)) out += Item(1, h.title.ifBlank { h.url }, h.url)
            }
        }
        return out
    }

    private fun fetchRemote(q: String): List<String> {
        val enc = URLEncoder.encode(q, "UTF-8")
        val url = when (Settings.engine(context).id) {
            "google" -> "https://suggestqueries.google.com/complete/search?client=firefox&hl=tr&ie=UTF-8&oe=UTF-8&q=$enc"
            else -> "https://duckduckgo.com/ac/?type=list&kl=tr-tr&q=$enc"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        return conn.inputStream.use { input ->
            val body = input.bufferedReader(Charsets.UTF_8).readText()
            val arr = JSONArray(body).getJSONArray(1)
            List(arr.length()) { arr.getString(it) }
        }
    }
}
