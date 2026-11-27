//! Zet Browser — Windows masaüstü sürümü.
//!
//! Tek pencere: üstte HTML ile çizilen sekme/adres çubuğu, altında her sekme
//! için ayrı bir WebView2. Reklam engelleme `zet-core`, Tor `zet-tor` ile.
//! Diğer sistemlerde (CI'daki Linux testleri) yalnızca ortak modüller derlenir.

#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]
#![cfg_attr(not(windows), allow(dead_code))]

mod engine;
mod settings;
mod updater;

#[cfg(windows)]
mod app;
#[cfg(windows)]
mod win;

fn main() {
    #[cfg(windows)]
    app::run();
    #[cfg(not(windows))]
    eprintln!("Zet masaüstü şimdilik yalnızca Windows'ta çalışır.");
}
