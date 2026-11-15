# Zet Browser — oturum devir notları

Bu dosya yeni Claude oturumlarının kaldığı yerden devam edebilmesi içindir.
Kullanıcıyla **Türkçe** konuş. Kullanıcı genelde **telefondan** çalışıyor;
bilgisayarı (Windows) çoğu zaman kapalı.

## Proje

Kişisel kullanım için Rust çekirdekli tarayıcı: reklam engelleme, YouTube
video indirme, Tor, gizli sekme. Hedefler: **Android** (aktif), **Windows** (henüz yok).

- Depo: `keruynigre/Zet-browser` (GitHub'da adı büyük Z ile; eski küçük harfli
  ad yönlendiriyor). Doğrudan `main`'e push ediliyor — kullanıcı onayladı.
- Her `main` push'unda GitHub Actions APK derleyip Release yayınlar.
  Kullanıcıya verilen kalıcı link:
  https://github.com/keruynigre/Zet-browser/releases/latest/download/zet-browser.apk
- APK sabit anahtarla imzalı (`android/app/zet-release.jks`, şifre `zetbrowser`)
  ki güncellemeler üzerine kurulsun. Kişisel proje; bilinçli tercih.

## Mimari

```
crates/zet-core     Platformdan bağımsız Rust: adblock (Brave adblock crate),
                    url (adres/arama ayrıştırma), youtube (InnerTube çözümleme),
                    download (parçalı paralel indirme), net (ureq ajanı),
                    scripts/*.js (WebView'a enjekte edilen betikler)
crates/zet-tor      Arti (Rust Tor) + kendi SOCKS5 sunucumuz (socks.rs)
crates/zet-android  JNI köprüsü (cdylib libzet_android.so)
android/            Kotlin kabuk (androidx yok sayılır, yalnızca androidx.webkit)
.github/workflows/android.yml   test + cargo-ndk + gradle + Release
```

Kotlin dosyaları (`android/app/src/main/kotlin/dev/zet/browser/`):
- `BrowserActivity.kt` — ana ekran; sekmeler (inner class `Tab`), WebView
  istemcileri, Tor, menü/ızgara bağlantıları, yeni sekme sayfası (NTP)
- `ZetCore.kt` — tüm `external fun` JNI bildirimleri (Rust ile birebir eşleşmeli)
- `ZetWebView.kt` — ekran kapalıyken çalma için görünürlük hilesi
- `TabSwitcher.kt`, `BrowserMenu.kt` — Brave tarzı sekme ızgarası ve ⋮ menü
- `LibraryActivity.kt` (geçmiş/yer işaretleri), `SettingsActivity.kt`, `Settings.kt`
- `ZetDb.kt` — SQLite geçmiş + yer işaretleri
- `DownloadService.kt` (dataSync FGS), `PlaybackService.kt` (mediaPlayback FGS + MediaSession)
- `YouTubeDownloader.kt`, `Muxer.kt` (MediaMuxer ile görüntü+ses birleştirme)
- `FilterLists.kt` (EasyList/EasyPrivacy/Peter Lowe + Fanboy çerez listesi; önbellek
  `engine-v3-c.bin` / `engine-v3.bin` — çerez ayarına göre)
- `PageScripts.kt` — enjekte edilen JS: okuma modu, koyu sayfa, sınıf/kimlik toplama
  (genel kozmetik kurallar için `ZetCore.cosmeticGeneric`), masaüstü UA
- `Suggestions.kt` — adres çubuğu önerileri (geçmiş/yer işareti + Google/DDG öneri servisi)
- `Updater.kt` — GitHub Releases'tan güncelleme: `releases/latest` tag'i `android-N`,
  N = versionCode (= CI run_number). Açılışta ve her onResume'da (saatte en fazla 1) denetler,
  diyalog + bildirim gösterir,
  APK'yı indirip PackageInstaller oturumuyla kurar (`Updater$InstallReceiver`).
  Release notu = commit mesajı (workflow body) → uyarıda "neler yeni" olarak görünür.
  **Bu yüzden commit mesajlarını kullanıcıya dönük, Türkçe ve anlaşılır yaz.**
- `PasswordStore.kt` — şifreler; Android Keystore AES-GCM ile şifreli tek blok
  (prefs "zet_pw"). Yakalama/doldurma JS'i `PageScripts.PW_CAPTURE` / `pwFill`;
  kaydetme sorusu sonraki sayfa geçişinde sorulur. Liste ayarlarda, cihaz kilidiyle.
- `assets/newtab.html` — NTP şablonu ({{BLOCKED}} {{SAVED}} {{TILES}} {{MODE}})
- İkonlar: `res/drawable/ic_*.xml` (Material Icons yolları)

## Derleme ve test

```sh
cargo test                                   # tüm Rust testleri (zet-tor dahil)
cargo clippy --all-targets                   # uyarısız tutuluyor
node crates/zet-core/tests/js/youtube_adblock.test.mjs
node crates/zet-core/tests/js/youtube_background.test.mjs

# Android (ortamın kurulum betiği SDK/NDK'yı /opt/android-sdk'ya kurar)
export ANDROID_HOME=/opt/android-sdk ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.2.12479018
cargo ndk -t arm64-v8a -o android/app/src/main/jniLibs build --release -p zet-android
cd android && echo "sdk.dir=/opt/android-sdk" > local.properties && gradle --no-daemon -q assembleRelease
```
- Yerel derleme hatasız geçmeden push etme; CI ~10 dk (Arti yüzünden).
- JNI eklerken `llvm-nm -D` ile `Java_dev_zet_browser_ZetCore_*` sembollerini
  `ZetCore.kt` ile karşılaştır.
- CI durumu: `curl https://api.github.com/repos/keruynigre/Zet-browser/actions/runs?head_sha=<sha>`
  (Monitor ile izle; `sleep` engelli).

## Bu ortamın kısıtları

- **YouTube bu bulut ortamından engelli** ("bot olmadığını doğrula").
  YouTube özelliklerini burada uçtan uca test edemezsin; kullanıcı telefonda dener.
- Tor ağına buradan bağlanılamaz; Tor'un gerçek bağlantısı yalnızca telefonda doğrulanır.
- Emülatör yok (KVM yok). Arayüz telefonda doğrulanır; HTML (NTP) Playwright ile
  ekran görüntüsü alınarak kontrol edilebilir.
- Ortam: "Zet Browser" (izinli: www.youtube.com, youtubei.googleapis.com,
  dl.google.com, easylist.to).

## Önemli kararlar / öğrenilenler

- **YouTube indirme**: InnerTube `player` uç noktası. Kasım 2026 itibarıyla
  çalışan istemci **VISIONOS** (yt-dlp `_base.py`'den alınan değerler).
  ANDROID_VR Ağustos 2026'dan beri yalnızca itag 18'i PO token'sız veriyor
  (yedek olarak 360p sunuluyor). IOS PO token ister. Her istemci 1 MB'lık
  baş+orta aralığı indirilerek sınanır (1 KB yanıltıcıydı). 403'te
  indirici 1 MB'lık sıralı parçalara düşer. Kırılırsa yt-dlp'nin güncel
  istemci tanımlarına bak.
- **YouTube reklam**: belge başı betik (player yanıtından adPlacements vb.
  budama + reklam atlama + CSS) + yerleşik ağ kuralları (`BUILTIN_RULES`).
- **Ekran kapalıyken çalma**: JS görünürlük sahteleme + `ZetWebView`
  (yalnızca çalan sekmede `onWindowVisibilityChanged` → VISIBLE) +
  PlaybackService. Telefonda ilk sürüm çalışmadı; ZetWebView düzeltmesi
  (android-14) sonrası kullanıcı henüz sonuç bildirmedi.
- **Tor**: kapalı-başarısızlık — Tor hazır olana kadar proxy erişilemeyen
  porta yönlendirilir, IP sızmaz. Tor açıkken WebRTC kapatılır. rustls için
  `ring` sağlayıcısı start()'ta açıkça kurulur (yoksa çöküyordu).
  Durum "kuşak" sayacıyla korunur. Bilinen sınır: ureq istekleri
  (YouTube indirme, liste güncelleme) Tor'dan geçmez.
- **Gizli sekme**: oturuma özel androidx.webkit Profile; açılışta ve son gizli
  sekme kapanınca silinir.
- **Arayüz**: Kullanıcı Brave mobil arayüzünü örnek istedi (marka/logo
  kopyalanmadan). Brave'e özel Cüzdan / Leo / sekme grupları eklenmedi.

## android-19..22 (YouTube geri bildirimleri)

- Kullanıcının "başlık görünmüyor" dediği şey aslında aşağı kaydırınca sabit oynatıcının
  altındaki "Tümü / İlgili" çipleriydi (Brave'de var, bizde yok). android-22'de Zet artık
  sadeleştirilmiş Chrome UA'sı ("Android 10; K") + Sec-CH-UA'da Chrome markaları
  (`UserAgentMetadata`) + X-Requested-With kapalı kullanıyor; çipler gelmezse sebep başka.
- YouTube'da genel kozmetik kurallar kapalı; dokunarak açılan video sesli başlar.
- `PageScripts.ytEnhance`: varsayılan kalite (Ayarlar > Medya, varsayılan 1080p) +
  oynatıcıda yukarı kaydırma = tam ekran. Tam ekranda yatay video → yatay yön.
- Tam ekran: WindowInsetsController + cutout SHORT_EDGES + decorFits(false);
  çıkışta WebView'a sıfır inset gönderiliyor (yoksa safe-area takılı kalıp sayfa sağa kayıyordu).
- Telefonda doğrulandı: yukarı kaydırarak tam ekran çalışıyor.

## android-18 ile eklenenler (telefonda henüz denenmedi)

GitHub'dan uygulama içi güncelleme + uyarı, şifre yöneticisi, aşağı çekip yenileme
(ZetWebView `onOverScrolled` + dokunma takibi), HTTPS-önce (başarısızsa http'ye
düşer, siteyi oturum boyunca muaf tutar; https→http yönlendirme döngüsü korumalı),
QR tarama (`play-services-code-scanner`, Google Play Hizmetleri gerekir).
Not: android-17 kurulu telefona android-18 bir kez elle kurulmalı; sonrası otomatik uyarır.

## android-17 ile eklenenler (telefonda henüz denenmedi)

Dosya yükleme (`onShowFileChooser`), site izinleri (kamera/mikrofon/konum; oturum
boyunca hatırlanır, DRM otomatik), sayfada bul, masaüstü sitesi (sekme başına),
koyu sayfalar (CSS ters çevirme; zaten koyu sitelere dokunmaz), metin boyutu,
çeviri (translate.google.com proxy), okuma modu (katman), ana ekrana ekle
(ShortcutManager), yazdır/PDF, arama önerileri (gizli sekme ve Tor'da ağ isteği yok),
çerez uyarısı engelleme + genel kozmetik kurallar (generichide'a uyar).
Not: Bu ortamda Maven 429 verebiliyor; gradle'ı birkaç kez dene, lint'i
`-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` ile atla.

## Durum (android-15)

Telefonda **doğrulananlar** (kullanıcı "hepsi çalışıyor" dedi): kurulum, tarama,
reklam engelleme, YouTube indirme, android-15 arayüzü, ekran kapalıyken çalma,
YouTube reklam engelleme, arka planda indirme, Tor.

## Yapılacaklar

1. android-17/18 özelliklerinin geri bildirimine göre düzeltmeler. Kalan fikir:
   scriptlet enjeksiyonu (`Cosmetic.script` şu an kullanılmıyor).
2. **Windows sürümü** (wry/tao + WebView2; zet-core ve zet-tor ortak;
   reklam engelleme için WebView2 `AddWebResourceRequestedFilter`;
   GitHub Actions `windows-latest` ile .exe). Henüz başlanmadı.
3. Fikirler: Tor için "yeni kimlik", YouTube indirmelerini Tor'dan geçirme
   seçeneği, NTP arka plan görseli.

## Oturum yönetimi

Kullanıcı isteği: bağlam dolunca yeni oturum aç, bu dosyayı güncelle,
eski oturumu (kullanıcı onayıyla) arşivle.
