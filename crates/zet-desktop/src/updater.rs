//! GitHub Releases üzerinden güncelleme. Android ile aynı sürüm numarası
//! (`android-N`, N = CI çalışma numarası); Windows dosyası aynı sürümde
//! `zet-browser-windows.exe` olarak yayınlanır.

use std::io::Read;
use std::path::PathBuf;

const API: &str = "https://api.github.com/repos/keruynigre/Zet-browser/releases/latest";
const ASSET: &str = "zet-browser-windows.exe";

/// Bu derlemenin numarası (CI'da GITHUB_RUN_NUMBER). Yerel derlemede 0.
pub fn current() -> u32 {
    option_env!("ZET_BUILD").and_then(|s| s.parse().ok()).unwrap_or(0)
}

#[derive(Clone, Debug)]
pub struct Release {
    pub code: u32,
    pub tag: String,
    pub notes: String,
    pub url: String,
}

/// Son sürümü getirir; bu derlemeden yeniyse döner.
pub fn check() -> Result<Option<Release>, String> {
    let mut resp = zet_core::net::agent()
        .get(API)
        .header("User-Agent", "ZetBrowser")
        .header("Accept", "application/vnd.github+json")
        .call()
        .map_err(|e| e.to_string())?;
    let json: serde_json::Value = resp.body_mut().read_json().map_err(|e| e.to_string())?;
    let tag = json["tag_name"].as_str().unwrap_or_default().to_string();
    let code: u32 = tag.trim_start_matches("android-").parse().unwrap_or(0);
    let url = json["assets"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|a| a["name"].as_str() == Some(ASSET))
        .and_then(|a| a["browser_download_url"].as_str())
        .map(str::to_string);
    let notes = json["body"]
        .as_str()
        .unwrap_or_default()
        .lines()
        .filter(|l| {
            !(l.starts_with("Commit:")
                || l.contains(".apk")
                || l.contains(".exe")
                || l.starts_with("Co-Authored-By")
                || l.starts_with("Claude-Session"))
        })
        .collect::<Vec<_>>()
        .join("\n")
        .trim()
        .to_string();
    match url {
        Some(url) if current() > 0 && code > current() => Ok(Some(Release { code, tag, notes, url })),
        _ => Ok(None),
    }
}

/// Yeni sürümü indirir, çalışan dosyanın yerine koyar ve yeniden başlatır.
/// Windows çalışan .exe'nin adını değiştirmeye izin verir; eskisi `.old` olur
/// ve bir sonraki açılışta silinir.
pub fn install(rel: &Release) -> Result<(), String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let new = exe.with_extension("new");
    let old = exe.with_extension("old");
    let resp = zet_core::net::agent()
        .get(&rel.url)
        .header("User-Agent", "ZetBrowser")
        .call()
        .map_err(|e| e.to_string())?;
    let mut bytes = Vec::new();
    resp.into_body()
        .into_reader()
        .take(200 * 1024 * 1024)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() < 1024 * 1024 || !bytes.starts_with(b"MZ") {
        return Err("indirilen dosya geçersiz".into());
    }
    std::fs::write(&new, &bytes).map_err(|e| e.to_string())?;
    let _ = std::fs::remove_file(&old);
    std::fs::rename(&exe, &old).map_err(|e| e.to_string())?;
    if let Err(e) = std::fs::rename(&new, &exe) {
        let _ = std::fs::rename(&old, &exe);
        return Err(e.to_string());
    }
    std::process::Command::new(&exe).spawn().map_err(|e| e.to_string())?;
    Ok(())
}

/// Önceki güncellemeden kalan eski dosyayı siler.
pub fn cleanup() {
    if let Ok(exe) = std::env::current_exe() {
        let old: PathBuf = exe.with_extension("old");
        let _ = std::fs::remove_file(old);
    }
}
