# Zet Browser

Rust ile yazılmış, reklam engelleyen ve YouTube'dan video indirebilen kişisel tarayıcı.
Hedef platformlar: **Windows** ve **Android**.

## Mimari

| Katman | Teknoloji |
|---|---|
| Ortak çekirdek (`crates/zet-core`) | Rust: reklam engelleme (`adblock`), indirme, depolama |
| Windows kabuğu | Rust + `wry`/`tao` (WebView2) |
| Android kabuğu | Kotlin `WebView` + JNI ile `zet-core` |

## Yol haritası

- [x] Aşama 0: Çekirdek reklam engelleme motoru
- [x] Aşama 0: Android kabuğu + GitHub Actions ile APK derleme
- [ ] Aşama 1: Temel tarayıcı (sekmeler, geçmiş, yer imleri)
- [ ] Aşama 2: Reklam engelleme entegrasyonu (ağ + kozmetik)
- [ ] Aşama 3: YouTube indirici
- [ ] Aşama 4: Cila ve hız

## Geliştirme

```sh
cargo test
```

## Android APK'yı telefona kurma

Her `main` güncellemesinde GitHub Actions APK derler ve **Releases** sayfasına koyar.
Telefondan en son sürüm:

https://github.com/keruynigre/Zet-browser/releases/latest/download/zet-browser.apk

İlk kurulumda "bilinmeyen kaynaklardan yüklemeye" izin vermen gerekir.
Tüm sürümler aynı anahtarla imzalandığı için yeni APK eskisinin üzerine kurulur, veriler silinmez.
