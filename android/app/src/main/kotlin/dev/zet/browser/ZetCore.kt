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

    external fun setEnabled(enabled: Boolean)

    /** Adres çubuğuna yazılanı URL'ye ya da arama adresine çevirir. */
    external fun normalizeInput(input: String): String
}
