//! Ayarlar ve oturum (açık sekmeler) — %LOCALAPPDATA%\Zet altında JSON.

use std::path::PathBuf;

use serde::{Deserialize, Serialize};

pub struct Engine {
    pub id: &'static str,
    pub name: &'static str,
    pub prefix: &'static str,
}

pub const ENGINES: &[Engine] = &[
    Engine { id: "google", name: "Google", prefix: "https://www.google.com/search?q=" },
    Engine { id: "duckduckgo", name: "DuckDuckGo", prefix: "https://duckduckgo.com/?q=" },
    Engine { id: "bing", name: "Bing", prefix: "https://www.bing.com/search?q=" },
    Engine { id: "yandex", name: "Yandex", prefix: "https://yandex.com.tr/search/?text=" },
    Engine { id: "startpage", name: "Startpage", prefix: "https://www.startpage.com/do/search?q=" },
];

#[derive(Serialize, Deserialize, Clone)]
#[serde(default)]
pub struct Settings {
    pub engine: String,
    pub adblock: bool,
    pub cookies: bool,
    pub auto_update: bool,
    pub skipped_update: u32,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            engine: "google".into(),
            adblock: true,
            cookies: true,
            auto_update: true,
            skipped_update: 0,
        }
    }
}

impl Settings {
    pub fn search_prefix(&self) -> &'static str {
        ENGINES
            .iter()
            .find(|e| e.id == self.engine)
            .unwrap_or(&ENGINES[0])
            .prefix
    }
}

#[derive(Serialize, Deserialize, Default)]
#[serde(default)]
pub struct Session {
    pub tabs: Vec<String>,
    pub active: usize,
}

/// Uygulama verisi klasörü.
pub fn data_dir() -> PathBuf {
    let base = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share")))
        .unwrap_or_else(|| PathBuf::from("."));
    let dir = base.join("Zet");
    let _ = std::fs::create_dir_all(&dir);
    dir
}

/// Kullanıcının İndirilenler klasörü.
pub fn downloads_dir() -> PathBuf {
    std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(|h| PathBuf::from(h).join("Downloads"))
        .unwrap_or_else(|| PathBuf::from("."))
}

fn read<T: for<'de> Deserialize<'de> + Default>(name: &str) -> T {
    std::fs::read(data_dir().join(name))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn write<T: Serialize>(name: &str, value: &T) {
    if let Ok(json) = serde_json::to_vec_pretty(value) {
        let path = data_dir().join(name);
        let tmp = path.with_extension("tmp");
        if std::fs::write(&tmp, json).is_ok() {
            let _ = std::fs::rename(tmp, path);
        }
    }
}

pub fn load() -> Settings {
    read("settings.json")
}

pub fn save(s: &Settings) {
    write("settings.json", s);
}

pub fn load_session() -> Session {
    read("session.json")
}

pub fn save_session(s: &Session) {
    write("session.json", s);
}
