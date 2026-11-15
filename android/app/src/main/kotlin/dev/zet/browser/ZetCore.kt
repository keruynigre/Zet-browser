package dev.zet.browser

/** Rust çekirdeği (crates/zet-android) ile köprü. Tüm fonksiyonlar thread-safe. */
object ZetCore {
    init {
        System.loadLibrary("zet_android")
    }

    /** Önceden derlenmiş motoru dosyadan yükler (hızlı açılış). */
    external fun loadCache(path: String): Boolean

    /** Filtre listesi dosyalarından motoru derler ve [cachePath]'e kaydeder. */
    external fun buildFromFiles(paths: Array<String>, cachePath: String): Boolean

    /** 1 ise istek engellenmeli. */
    external fun shouldBlock(url: String, pageUrl: String, mainFrame: Boolean): Int

    /** Sayfada gizlenecek öğeler için CSS (boş olabilir). */
    external fun cosmeticCss(pageUrl: String): String

    /** Sayfadaki sınıf/kimliklere uyan genel gizleme CSS'i (çerez uyarıları vb.). */
    external fun cosmeticGeneric(pageUrl: String, classes: Array<String>, ids: Array<String>): String

    external fun setEnabled(enabled: Boolean)

    /** Adres çubuğuna yazılanı URL'ye ya da arama adresine çevirir. */
    external fun normalizeInput(input: String): String

    /** URL bir YouTube videosuysa kimliğini, değilse boş string döner. */
    external fun ytVideoId(url: String): String

    /**
     * Video bilgisini ağdan alır (arka plan thread'inde çağır).
     * JSON: {ok, title, fileName, userAgent, options:[{label, kind, ext, total_size, streams:[{url,size,...}]}]}
     * ya da {ok:false, error}.
     */
    external fun ytOptions(videoId: String): String

    /** Dosyaları arka planda indirmeye başlar, iş kimliği döner. */
    external fun dlStart(urls: Array<String>, paths: Array<String>, sizes: LongArray, userAgent: String): Long

    /** JSON: {state: running|done|failed|cancelled, error, downloaded, total} */
    external fun dlStatus(id: Long): String

    external fun dlCancel(id: Long)

    external fun dlForget(id: Long)

    /** YouTube sayfalarına belge başında enjekte edilecek reklam engelleme betiği. */
    external fun ytAdblockScript(): String

    /** Ekran kapalıyken YouTube çalmayı sağlayan betik. */
    external fun ytBackgroundScript(): String

    /** Yerleşik Tor'u başlatır; yerel SOCKS5 portunu (hata: -1) döner. Arka planda çağır. */
    external fun torStart(dataDir: String): Int

    /** JSON: {state: off|starting|ready|failed, progress: 0-100, error, port} */
    external fun torStatus(): String

    external fun torStop()

    /** [normalizeInput] gibi, ama aramalar için verilen arama motorunu kullanır. */
    external fun normalizeInputWith(input: String, searchPrefix: String): String
}
