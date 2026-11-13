//! Android kabuğu (`dev.zet.browser.ZetCore`) için JNI fonksiyonları.
//!
//! Kotlin tarafında `object ZetCore` içindeki `external fun` bildirimleriyle
//! birebir eşleşir. Tüm fonksiyonlar herhangi bir iş parçacığından çağrılabilir.

use std::sync::RwLock;

use jni::objects::{JObject, JObjectArray, JString};
use jni::objects::JLongArray;
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use zet_core::download::{self, Part};
use zet_core::url::{normalize_input, DEFAULT_SEARCH};
use zet_core::youtube;
use zet_core::{AdBlocker, BlockDecision, ResourceType};

static BLOCKER: RwLock<Option<AdBlocker>> = RwLock::new(None);

fn read_string(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    env.get_string(s).map(Into::into).unwrap_or_default()
}

fn new_string(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// Önbellekteki derlenmiş motoru yükler. Başarılıysa `true`.
pub fn load_cache(path: &str) -> bool {
    let Ok(bytes) = std::fs::read(path) else { return false };
    let Some(blocker) = AdBlocker::from_bytes(&bytes) else { return false };
    replace_blocker(blocker);
    true
}

/// Filtre listesi dosyalarından motoru derler ve önbelleğe yazar.
pub fn build_from_files(paths: &[String], cache_path: &str) -> bool {
    let lists: Vec<String> = paths
        .iter()
        .filter_map(|p| std::fs::read_to_string(p).ok())
        .collect();
    if lists.is_empty() {
        return false;
    }
    let blocker = AdBlocker::from_lists(lists);
    if !cache_path.is_empty() {
        let tmp = format!("{cache_path}.tmp");
        if std::fs::write(&tmp, blocker.to_bytes()).is_ok() {
            let _ = std::fs::rename(&tmp, cache_path);
        }
    }
    replace_blocker(blocker);
    true
}

fn replace_blocker(mut blocker: AdBlocker) {
    let mut guard = BLOCKER.write().unwrap_or_else(|e| e.into_inner());
    if let Some(old) = guard.as_ref() {
        blocker.set_enabled(old.is_enabled());
    }
    *guard = Some(blocker);
}

/// 0 = izin ver, 1 = engelle.
pub fn should_block(url: &str, page_url: &str, main_frame: bool) -> bool {
    if main_frame {
        return false;
    }
    let guard = BLOCKER.read().unwrap_or_else(|e| e.into_inner());
    let Some(blocker) = guard.as_ref() else { return false };
    let page = if page_url.is_empty() { url } else { page_url };
    matches!(
        blocker.check(url, page, ResourceType::guess_from_url(url)),
        BlockDecision::Block | BlockDecision::Redirect(_)
    )
}

pub fn cosmetic_css(page_url: &str) -> String {
    let guard = BLOCKER.read().unwrap_or_else(|e| e.into_inner());
    guard.as_ref().map(|b| b.cosmetic(page_url).css).unwrap_or_default()
}

pub fn set_enabled(enabled: bool) {
    let mut guard = BLOCKER.write().unwrap_or_else(|e| e.into_inner());
    if let Some(b) = guard.as_mut() {
        b.set_enabled(enabled);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_loadCache(
    mut env: JNIEnv,
    _this: JObject,
    path: JString,
) -> jboolean {
    let path = read_string(&mut env, &path);
    if load_cache(&path) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_buildFromFiles(
    mut env: JNIEnv,
    _this: JObject,
    paths: JObjectArray,
    cache_path: JString,
) -> jboolean {
    let len = env.get_array_length(&paths).unwrap_or(0);
    let mut files = Vec::with_capacity(len as usize);
    for i in 0..len {
        if let Ok(obj) = env.get_object_array_element(&paths, i) {
            let s = JString::from(obj);
            files.push(read_string(&mut env, &s));
        }
    }
    let cache = read_string(&mut env, &cache_path);
    if build_from_files(&files, &cache) { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_shouldBlock(
    mut env: JNIEnv,
    _this: JObject,
    url: JString,
    page_url: JString,
    main_frame: jboolean,
) -> jint {
    let url = read_string(&mut env, &url);
    let page = read_string(&mut env, &page_url);
    should_block(&url, &page, main_frame != JNI_FALSE) as jint
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_cosmeticCss(
    mut env: JNIEnv,
    _this: JObject,
    page_url: JString,
) -> jstring {
    let page = read_string(&mut env, &page_url);
    let css = cosmetic_css(&page);
    new_string(&mut env, &css)
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_setEnabled(
    _env: JNIEnv,
    _this: JObject,
    enabled: jboolean,
) {
    set_enabled(enabled != JNI_FALSE);
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_normalizeInput(
    mut env: JNIEnv,
    _this: JObject,
    input: JString,
) -> jstring {
    let text = read_string(&mut env, &input);
    let url = normalize_input(&text, DEFAULT_SEARCH);
    new_string(&mut env, &url)
}

/// Video bilgisini alır ve seçenekleri JSON olarak döner (ağ çağrısı yapar,
/// arka plan thread'inden çağrılmalı).
pub fn yt_options_json(video_id: &str) -> String {
    match youtube::fetch_info(video_id) {
        Ok(info) => serde_json::json!({
            "ok": true,
            "title": info.title,
            "author": info.author,
            "fileName": youtube::safe_file_name(&info.title),
            "userAgent": info.user_agent,
            "client": info.client,
            "options": youtube::download_options(&info),
        })
        .to_string(),
        Err(e) => serde_json::json!({ "ok": false, "error": e }).to_string(),
    }
}

fn read_string_array(env: &mut JNIEnv, arr: &JObjectArray) -> Vec<String> {
    let len = env.get_array_length(arr).unwrap_or(0);
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        if let Ok(obj) = env.get_object_array_element(arr, i) {
            let s = JString::from(obj);
            out.push(read_string(env, &s));
        }
    }
    out
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_ytVideoId(
    mut env: JNIEnv,
    _this: JObject,
    url: JString,
) -> jstring {
    let url = read_string(&mut env, &url);
    let id = youtube::video_id_from_url(&url).unwrap_or_default();
    new_string(&mut env, &id)
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_ytOptions(
    mut env: JNIEnv,
    _this: JObject,
    video_id: JString,
) -> jstring {
    let id = read_string(&mut env, &video_id);
    let json = yt_options_json(&id);
    new_string(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_dlStart(
    mut env: JNIEnv,
    _this: JObject,
    urls: JObjectArray,
    paths: JObjectArray,
    sizes: JLongArray,
    user_agent: JString,
) -> jlong {
    let urls = read_string_array(&mut env, &urls);
    let paths = read_string_array(&mut env, &paths);
    let n = env.get_array_length(&sizes).unwrap_or(0) as usize;
    let mut size_buf = vec![0i64; n];
    let _ = env.get_long_array_region(&sizes, 0, &mut size_buf);
    let ua = read_string(&mut env, &user_agent);
    let parts = urls
        .into_iter()
        .zip(paths)
        .zip(size_buf)
        .map(|((url, path), size)| Part { url, path, size: size.max(0) as u64 })
        .collect();
    download::start(parts, ua) as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_dlStatus(
    mut env: JNIEnv,
    _this: JObject,
    id: jlong,
) -> jstring {
    let json = download::get(id as u64)
        .map(|j| j.status_json())
        .unwrap_or_else(|| r#"{"state":"failed","error":"İş bulunamadı"}"#.into());
    new_string(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_dlCancel(_env: JNIEnv, _this: JObject, id: jlong) {
    download::cancel(id as u64);
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_dlForget(_env: JNIEnv, _this: JObject, id: jlong) {
    download::forget(id as u64);
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_ytAdblockScript(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    new_string(&mut env, youtube::ADBLOCK_SCRIPT)
}

#[no_mangle]
pub extern "system" fn Java_dev_zet_browser_ZetCore_ytBackgroundScript(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    new_string(&mut env, youtube::BACKGROUND_SCRIPT)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_cache_and_reload() {
        let dir = std::env::temp_dir().join(format!("zet-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let list = dir.join("list.txt");
        let cache = dir.join("engine.bin");
        std::fs::write(&list, "||doubleclick.net^\nnews.site##.ad\n").unwrap();

        assert!(build_from_files(&[list.to_string_lossy().into()], &cache.to_string_lossy()));
        assert!(should_block("https://doubleclick.net/x.js", "https://news.site/", false));
        assert!(!should_block("https://doubleclick.net/x.js", "https://news.site/", true));
        assert!(cosmetic_css("https://news.site/").contains(".ad"));

        set_enabled(false);
        assert!(!should_block("https://doubleclick.net/x.js", "https://news.site/", false));
        set_enabled(true);

        assert!(load_cache(&cache.to_string_lossy()));
        assert!(should_block("https://doubleclick.net/x.js", "https://news.site/", false));
        assert!(!load_cache("/yok/boyle/dosya"));
        std::fs::remove_dir_all(dir).ok();
    }
}
