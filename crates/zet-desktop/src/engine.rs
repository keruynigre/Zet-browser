//! Reklam engelleme motoru: filtre listelerini indirir, derler, önbellekler.
//! Android'deki `FilterLists.kt` + JNI katmanının masaüstü karşılığı.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::RwLock;
use std::time::{Duration, SystemTime};

use zet_core::{AdBlocker, BlockDecision, ResourceType};

static ENGINE: RwLock<Option<AdBlocker>> = RwLock::new(None);
static ENABLED: AtomicBool = AtomicBool::new(true);
/// Uygulama açıldığından beri engellenen toplam istek.
pub static TOTAL_BLOCKED: AtomicU64 = AtomicU64::new(0);

const MAX_AGE: Duration = Duration::from_secs(4 * 24 * 3600);

fn sources(cookies: bool) -> Vec<(&'static str, &'static str)> {
    let mut v = vec![
        ("easylist.txt", "https://easylist.to/easylist/easylist.txt"),
        ("easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt"),
        (
            "peter-lowe.txt",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
        ),
        ("ubo-filters.txt", "https://ublockorigin.github.io/uAssets/filters/filters.txt"),
        ("ubo-unbreak.txt", "https://ublockorigin.github.io/uAssets/filters/unbreak.txt"),
        ("ubo-quick-fixes.txt", "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt"),
    ];
    if cookies {
        v.push(("fanboy-cookie.txt", "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt"));
    }
    v
}

pub fn set_enabled(on: bool) {
    ENABLED.store(on, Ordering::Relaxed);
}

pub fn enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

pub fn should_block(url: &str, page_url: &str, kind: ResourceType) -> bool {
    if !enabled() {
        return false;
    }
    let guard = ENGINE.read().unwrap_or_else(|e| e.into_inner());
    let Some(engine) = guard.as_ref() else { return false };
    let page = if page_url.is_empty() { url } else { page_url };
    matches!(engine.check(url, page, kind), BlockDecision::Block | BlockDecision::Redirect(_))
}

/// Sayfaya özel gizleme CSS'i.
pub fn cosmetic_css(page_url: &str) -> String {
    if !enabled() {
        return String::new();
    }
    let guard = ENGINE.read().unwrap_or_else(|e| e.into_inner());
    guard.as_ref().map(|e| e.cosmetic(page_url).css).unwrap_or_default()
}

/// Sayfadaki sınıf/kimliklere uyan genel gizleme CSS'i.
pub fn generic_css(page_url: &str, classes: &[String], ids: &[String]) -> String {
    if !enabled() {
        return String::new();
    }
    let guard = ENGINE.read().unwrap_or_else(|e| e.into_inner());
    guard
        .as_ref()
        .map(|e| e.generic_css(page_url, classes, ids))
        .unwrap_or_default()
}

fn install(engine: AdBlocker) {
    *ENGINE.write().unwrap_or_else(|e| e.into_inner()) = Some(engine);
}

fn is_stale(p: &Path) -> bool {
    std::fs::metadata(p)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| SystemTime::now().duration_since(t).ok())
        .is_none_or(|age| age > MAX_AGE)
}

fn download(url: &str, target: &Path) -> Result<(), String> {
    let resp = zet_core::net::agent()
        .get(url)
        .header("User-Agent", "ZetBrowser")
        .call()
        .map_err(|e| e.to_string())?;
    let mut body = Vec::new();
    resp.into_body()
        .into_reader()
        .take(64 * 1024 * 1024)
        .read_to_end(&mut body)
        .map_err(|e| e.to_string())?;
    if body.is_empty() {
        return Err("boş yanıt".into());
    }
    let tmp = target.with_extension("tmp");
    std::fs::write(&tmp, &body).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, target).map_err(|e| e.to_string())
}

/// Arka planda çağrılır: önce önbellekten yükler, listeler eskiyse indirip
/// motoru yeniden derler. `status(true)` = güncelleme başladı, `false` = bitti.
pub fn load_and_refresh(dir: PathBuf, cookies: bool, status: impl Fn(bool)) {
    let _ = std::fs::create_dir_all(&dir);
    let cache = dir.join(if cookies { "engine-v4-c.bin" } else { "engine-v4.bin" });
    let from_cache = std::fs::read(&cache)
        .ok()
        .and_then(|b| AdBlocker::from_bytes(&b))
        .map(install)
        .is_some();

    let list = sources(cookies);
    let stale = list.iter().any(|(name, _)| is_stale(&dir.join(name)));
    if !stale && from_cache {
        return;
    }
    if stale {
        status(true);
        for (name, url) in &list {
            let target = dir.join(name);
            if is_stale(&target) {
                if let Err(e) = download(url, &target) {
                    eprintln!("liste indirilemedi {url}: {e}");
                }
            }
        }
    }
    let texts: Vec<String> = list
        .iter()
        .filter_map(|(name, _)| std::fs::read_to_string(dir.join(name)).ok())
        .filter(|t| !t.is_empty())
        .collect();
    if !texts.is_empty() {
        let engine = AdBlocker::from_lists(texts);
        let tmp = cache.with_extension("tmp");
        if std::fs::write(&tmp, engine.to_bytes()).is_ok() {
            let _ = std::fs::rename(&tmp, &cache);
        }
        install(engine);
    }
    if stale {
        status(false);
    }
}
