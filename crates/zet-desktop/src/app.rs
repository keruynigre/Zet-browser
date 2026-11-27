//! Zet Browser — Windows masaüstü kabuğu (uygulama ve olay döngüsü).
//!
//! Tek pencere: üstte HTML ile çizilen sekme/adres çubuğu (UI webview),
//! altında her sekme için ayrı bir WebView2 (yalnızca etkin olan görünür).
//! Reklam engelleme `zet-core` motoruyla, Tor `zet-tor` ile (ayrı profil + SOCKS5).

use crate::{engine, settings, updater, win};
use std::borrow::Cow;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use serde_json::{json, Value};
use tao::dpi::{LogicalSize, PhysicalSize};
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy};
use tao::window::{Window, WindowBuilder};
use wry::http::{Request, Response};
use wry::{NewWindowResponse, PageLoadEvent, Rect, WebContext, WebView, WebViewBuilder};

/// Üst çubuğun yüksekliği (mantıksal piksel): sekmeler 38 + araç çubuğu 46.
const UI_HEIGHT: f64 = 84.0;

/// Yerleşik sayfalar `zet` protokolüyle sunulur. WebView2'de özel protokoller
/// `http://zet.<ad>/` biçiminde görünür.
const INTERNAL: &str = "http://zet.";

fn internal(page: &str) -> String {
    format!("http://zet.{page}/")
}

const CONTENT_JS: &str = include_str!("content.js");
const UI_HTML: &str = include_str!("ui.html");
const NEWTAB_HTML: &str = include_str!("newtab.html");
const SETTINGS_HTML: &str = include_str!("settings.html");
const NO_WEBRTC_JS: &str = "(function(){['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel',\
'RTCSessionDescription','RTCIceCandidate'].forEach(function(k){try{Object.defineProperty(window,k,\
{get:function(){return undefined},set:function(){},configurable:false})}catch(e){}});})();";

#[derive(Clone, Copy, PartialEq, Debug)]
enum Kind {
    Normal,
    Private,
    Tor,
}

impl Kind {
    fn name(self) -> &'static str {
        match self {
            Kind::Normal => "normal",
            Kind::Private => "private",
            Kind::Tor => "tor",
        }
    }
}

enum UserEvent {
    Ui(String),
    Page(u32, String, String),
    Title(u32, String),
    Load(u32, String, bool),
    OpenTab(String, Kind),
    Blocked(u32),
    Toast(String),
    TorStarted(Result<u16, String>),
    TorTick,
    Update(updater::Release),
    UpdateNone(bool),
    Quit,
}

struct Tab {
    id: u32,
    kind: Kind,
    webview: WebView,
    title: String,
    url: String,
    loading: bool,
    blocked: Arc<AtomicU32>,
}

struct App {
    window: Window,
    ui: WebView,
    tabs: Vec<Tab>,
    active: usize,
    next_id: u32,
    proxy: EventLoopProxy<UserEvent>,
    ctx: WebContext,
    tor_ctx: Option<WebContext>,
    tor_port: u16,
    tor_pending: usize,
    settings: settings::Settings,
    closed: Vec<String>,
    update: Option<updater::Release>,
}

fn yt_wrap(script: &str) -> String {
    format!("if(/(^|\\.)youtube\\.com$/.test(location.hostname)){{{script}\n}}")
}

fn is_youtube(url: &str) -> bool {
    url.split("://")
        .nth(1)
        .and_then(|r| r.split(['/', ':', '?', '#']).next())
        .is_some_and(|h| h == "youtube.com" || h.ends_with(".youtube.com"))
}

/// `zet` protokolü: yerleşik sayfalar.
fn internal_page(req: Request<Vec<u8>>) -> Response<Cow<'static, [u8]>> {
    let uri = req.uri().to_string();
    let body: &'static str = if uri.contains("settings") { SETTINGS_HTML } else { NEWTAB_HTML };
    Response::builder()
        .header("Content-Type", "text/html; charset=utf-8")
        .body(Cow::Borrowed(body.as_bytes()))
        .unwrap_or_else(|_| Response::new(Cow::Borrowed(&b""[..])))
}

impl App {
    fn content_bounds(&self) -> Rect {
        let scale = self.window.scale_factor();
        let size: LogicalSize<f64> = self.window.inner_size().to_logical(scale);
        Rect {
            position: wry::dpi::LogicalPosition::new(0.0, UI_HEIGHT).into(),
            size: wry::dpi::LogicalSize::new(size.width, (size.height - UI_HEIGHT).max(0.0)).into(),
        }
    }

    fn ui_bounds(&self) -> Rect {
        let scale = self.window.scale_factor();
        let size: LogicalSize<f64> = self.window.inner_size().to_logical(scale);
        Rect {
            position: wry::dpi::LogicalPosition::new(0.0, 0.0).into(),
            size: wry::dpi::LogicalSize::new(size.width, UI_HEIGHT).into(),
        }
    }

    fn layout(&self) {
        let _ = self.ui.set_bounds(self.ui_bounds());
        let b = self.content_bounds();
        for t in &self.tabs {
            let _ = t.webview.set_bounds(b);
        }
    }

    fn tab_index(&self, id: u32) -> Option<usize> {
        self.tabs.iter().position(|t| t.id == id)
    }

    fn current(&self) -> Option<&Tab> {
        self.tabs.get(self.active)
    }

    /// Yeni sekme oluşturur (Tor sekmesi için Tor başlatılmış olmalı).
    /// `select` = false: arka planda, sona eklenir (oturum geri yükleme).
    fn open_tab(&mut self, url: &str, kind: Kind, select: bool) {
        if kind == Kind::Tor && self.tor_ctx.is_none() {
            self.start_tor();
            self.tor_pending += 1;
            return;
        }
        let id = self.next_id;
        self.next_id += 1;
        let bounds = self.content_bounds();
        let blocked = Arc::new(AtomicU32::new(0));
        let tor_port = self.tor_port;
        let p = self.proxy.clone();

        let ctx = match kind {
            Kind::Tor => self.tor_ctx.as_mut().expect("tor bağlamı"),
            _ => &mut self.ctx,
        };
        let mut b = WebViewBuilder::new_with_web_context(ctx)
            .with_bounds(bounds)
            .with_url(url)
            .with_visible(false)
            .with_incognito(kind != Kind::Normal)
            .with_devtools(true)
            .with_initialization_script(CONTENT_JS)
            .with_initialization_script(yt_wrap(zet_core::youtube::ADBLOCK_SCRIPT))
            .with_custom_protocol("zet".into(), |_, req| internal_page(req));
        if kind == Kind::Tor {
            b = b.with_initialization_script(NO_WEBRTC_JS);
        }
        let px = p.clone();
        b = b.with_ipc_handler(move |req: Request<String>| {
            let uri = req.uri().to_string();
            let _ = px.send_event(UserEvent::Page(id, uri, req.into_body()));
        });
        let px = p.clone();
        b = b.with_document_title_changed_handler(move |title| {
            let _ = px.send_event(UserEvent::Title(id, title));
        });
        let px = p.clone();
        b = b.with_on_page_load_handler(move |ev, url| {
            let _ = px.send_event(UserEvent::Load(id, url, matches!(ev, PageLoadEvent::Finished)));
        });
        let px = p.clone();
        b = b.with_new_window_req_handler(move |url, _features| {
            let _ = px.send_event(UserEvent::OpenTab(url, kind));
            NewWindowResponse::Deny
        });
        let px = p.clone();
        b = b.with_download_started_handler(move |_url, path| {
            let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            let _ = px.send_event(UserEvent::Toast(format!("İndiriliyor: {name}")));
            true
        });
        let px = p.clone();
        b = b.with_download_completed_handler(move |_url, path, ok| {
            let name = path
                .and_then(|p| p.file_name().map(|n| n.to_string_lossy().to_string()))
                .unwrap_or_default();
            let msg = if ok { format!("İndirildi: {name}") } else { "İndirme başarısız".to_string() };
            let _ = px.send_event(UserEvent::Toast(msg));
        });
        {
            use wry::WebViewBuilderExtWindows;
            if kind == Kind::Tor {
                b = b.with_additional_browser_args(format!(
                    "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection \
                     --proxy-server=socks5://127.0.0.1:{tor_port} \
                     --force-webrtc-ip-handling-policy=disable_non_proxied_udp"
                ));
            }
        }

        let webview = match b.build_as_child(&self.window) {
            Ok(w) => w,
            Err(e) => {
                self.toast(&format!("Sekme açılamadı: {e}"));
                return;
            }
        };
        {
            let px = p.clone();
            let page = Arc::new(Mutex::new(String::new()));
            if let Err(e) = win::install_blocker(&webview, page, blocked.clone(), move || {
                let _ = px.send_event(UserEvent::Blocked(id));
            }) {
                eprintln!("reklam engelleyici kurulamadı: {e}");
            }
        }

        let tab = Tab { id, kind, webview, title: String::new(), url: url.to_string(), loading: true, blocked };
        let index = if self.tabs.is_empty() || !select {
            self.tabs.len()
        } else {
            (self.active + 1).min(self.tabs.len())
        };
        self.tabs.insert(index, tab);
        if select || self.tabs.len() == 1 {
            self.select(index);
        } else {
            self.refresh_ui();
        }
    }

    fn select(&mut self, index: usize) {
        if index >= self.tabs.len() {
            return;
        }
        for (i, t) in self.tabs.iter().enumerate() {
            if i != index {
                let _ = t.webview.set_visible(false);
            }
        }
        self.active = index;
        let t = &self.tabs[index];
        let _ = t.webview.set_bounds(self.content_bounds());
        let _ = t.webview.set_visible(true);
        let _ = t.webview.focus();
        self.refresh_ui();
    }

    fn close(&mut self, id: u32) {
        let Some(i) = self.tab_index(id) else { return };
        let tab = self.tabs.remove(i);
        if tab.kind == Kind::Normal && !tab.url.starts_with(INTERNAL) {
            self.closed.push(tab.url.clone());
        }
        drop(tab);
        if self.tabs.is_empty() {
            self.active = 0;
            self.open_tab(&internal("newtab"), Kind::Normal, true);
            return;
        }
        if self.active > i || self.active >= self.tabs.len() {
            self.active = self.active.saturating_sub(1);
        }
        self.select(self.active);
    }

    fn navigate(&mut self, text: &str) {
        let text = text.trim();
        if text.is_empty() {
            return;
        }
        let url = zet_core::url::normalize_input(text, self.settings.search_prefix());
        match self.current() {
            Some(t) => {
                let _ = t.webview.load_url(&url);
                let _ = t.webview.focus();
            }
            None => self.open_tab(&url, Kind::Normal, true),
        }
    }

    fn toast(&self, msg: &str) {
        let _ = self.ui.evaluate_script(&format!("toast({})", json!(msg)));
    }

    fn tor_state(&self) -> Value {
        #[cfg(feature = "tor")]
        {
            serde_json::from_str(&zet_tor::status_json()).unwrap_or(json!({"state": "off", "progress": 0}))
        }
        #[cfg(not(feature = "tor"))]
        {
            json!({"state": "off", "progress": 0})
        }
    }

    fn refresh_ui(&self) {
        let tabs: Vec<Value> = self
            .tabs
            .iter()
            .enumerate()
            .map(|(i, t)| {
                json!({
                    "id": t.id, "title": if t.title.is_empty() { display_url(&t.url) } else { t.title.clone() },
                    "kind": t.kind.name(), "active": i == self.active, "loading": t.loading,
                })
            })
            .collect();
        let cur = self.current();
        let state = json!({
            "tabs": tabs,
            "url": cur.map(|t| display_url(&t.url)).unwrap_or_default(),
            "kind": cur.map(|t| t.kind.name()).unwrap_or("normal"),
            "blocked": cur.map(|t| t.blocked.load(Ordering::Relaxed)).unwrap_or(0),
            "adblock": engine::enabled(),
            "tor": self.tor_state(),
            "update": self.update.as_ref().map(|r| r.tag.clone()),
        });
        let _ = self.ui.evaluate_script(&format!("window.zetUpdate&&zetUpdate({state})"));
        let title = cur
            .map(|t| if t.title.is_empty() { "Zet".to_string() } else { format!("{} — Zet", t.title) })
            .unwrap_or_else(|| "Zet".into());
        self.window.set_title(&title);
    }

    fn start_tor(&mut self) {
        #[cfg(feature = "tor")]
        {
            if self.tor_pending > 0 {
                return;
            }
            self.toast("Tor başlatılıyor…");
            let dir = settings::data_dir().join("tor");
            let p = self.proxy.clone();
            std::thread::spawn(move || {
                let r = zet_tor::start(&dir.to_string_lossy());
                let _ = p.send_event(UserEvent::TorStarted(r));
            });
        }
        #[cfg(not(feature = "tor"))]
        self.toast("Bu derlemede Tor yok");
    }

    fn on_tor_started(&mut self, r: Result<u16, String>) {
        let pending = std::mem::take(&mut self.tor_pending);
        match r {
            Ok(port) => {
                self.tor_port = port;
                let dir = settings::data_dir().join("tor-profile");
                let _ = std::fs::remove_dir_all(&dir);
                self.tor_ctx = Some(WebContext::new(Some(dir)));
                for _ in 0..pending.max(1) {
                    self.open_tab(&internal("newtab"), Kind::Tor, true);
                }
                // Bağlantı ilerlemesini çubukta göster.
                let p = self.proxy.clone();
                std::thread::spawn(move || {
                    for _ in 0..600 {
                        std::thread::sleep(std::time::Duration::from_millis(700));
                        if p.send_event(UserEvent::TorTick).is_err() {
                            return;
                        }
                        #[cfg(feature = "tor")]
                        if matches!(zet_tor::state(), zet_tor::State::Ready | zet_tor::State::Failed(_)) {
                            let _ = p.send_event(UserEvent::TorTick);
                            return;
                        }
                    }
                });
            }
            Err(e) => self.toast(&format!("Tor başlatılamadı: {e}")),
        }
    }

    fn save_session(&self) {
        let tabs: Vec<String> = self
            .tabs
            .iter()
            .filter(|t| t.kind == Kind::Normal && t.url.starts_with("http") && !t.url.starts_with(INTERNAL))
            .map(|t| t.url.clone())
            .collect();
        let active = self
            .current()
            .and_then(|c| tabs.iter().position(|u| *u == c.url))
            .unwrap_or(0);
        settings::save_session(&settings::Session { tabs, active });
    }

    fn shortcut(&mut self, k: &str) {
        match k {
            "newtab" => self.open_tab(&internal("newtab"), Kind::Normal, true),
            "private" => self.open_tab(&internal("newtab"), Kind::Private, true),
            "close" => {
                if let Some(id) = self.current().map(|t| t.id) {
                    self.close(id);
                }
            }
            "reopen" => {
                if let Some(url) = self.closed.pop() {
                    self.open_tab(&url, Kind::Normal, true);
                }
            }
            "focus" => {
                let _ = self.ui.focus();
                let _ = self.ui.evaluate_script("focusUrl()");
            }
            "next" | "prev" if !self.tabs.is_empty() => {
                let n = self.tabs.len();
                let i = if k == "next" { (self.active + 1) % n } else { (self.active + n - 1) % n };
                self.select(i);
            }
            "reload" => {
                if let Some(t) = self.current() {
                    let _ = t.webview.reload();
                }
            }
            _ if k.starts_with("tab") => {
                if let Ok(n) = k[3..].parse::<usize>() {
                    let i = if n == 9 { self.tabs.len().saturating_sub(1) } else { n - 1 };
                    self.select(i);
                }
            }
            _ => {}
        }
    }

    fn on_ui(&mut self, msg: &str) {
        let Ok(v) = serde_json::from_str::<Value>(msg) else { return };
        let t = v["t"].as_str().unwrap_or_default();
        match t {
            "ready" => self.refresh_ui(),
            "go" => self.navigate(v["text"].as_str().unwrap_or_default()),
            "select" => {
                if let Some(i) = v["id"].as_u64().and_then(|id| self.tab_index(id as u32)) {
                    self.select(i);
                }
            }
            "close" => {
                if let Some(id) = v["id"].as_u64() {
                    self.close(id as u32);
                }
            }
            "newtab" => self.shortcut("newtab"),
            "newPrivate" => self.shortcut("private"),
            "newTor" => self.open_tab(&internal("newtab"), Kind::Tor, true),
            "back" => self.eval_current("history.back()"),
            "fwd" => self.eval_current("history.forward()"),
            "reload" => self.shortcut("reload"),
            "home" => {
                if let Some(t) = self.current() {
                    let _ = t.webview.load_url(&internal("newtab"));
                }
            }
            "focusPage" => {
                if let Some(t) = self.current() {
                    let _ = t.webview.focus();
                }
            }
            "shield" => {
                let on = !engine::enabled();
                engine::set_enabled(on);
                self.settings.adblock = on;
                settings::save(&self.settings);
                self.toast(if on { "Reklam engelleme açık" } else { "Reklam engelleme kapalı" });
                self.shortcut("reload");
                self.refresh_ui();
            }
            "downloads" => {
                let _ = std::process::Command::new("explorer").arg(settings::downloads_dir()).spawn();
            }
            "settings" => self.open_tab(&internal("settings"), Kind::Normal, true),
            "update" => self.start_update(),
            "key" => self.shortcut(v["k"].as_str().unwrap_or_default()),
            _ => {}
        }
    }

    fn eval_current(&self, js: &str) {
        if let Some(t) = self.current() {
            let _ = t.webview.evaluate_script(js);
        }
    }

    fn on_page(&mut self, id: u32, uri: String, msg: &str) {
        let Some(i) = self.tab_index(id) else { return };
        let Ok(v) = serde_json::from_str::<Value>(msg) else { return };
        let internal_page = uri.starts_with(INTERNAL);
        match v["t"].as_str().unwrap_or_default() {
            "nav" => {
                let url = v["url"].as_str().unwrap_or_default().to_string();
                let tab = &mut self.tabs[i];
                tab.url = url;
                if let Some(title) = v["title"].as_str().filter(|s| !s.is_empty()) {
                    tab.title = title.to_string();
                }
                if i == self.active {
                    self.refresh_ui();
                }
            }
            "key" => self.shortcut(v["k"].as_str().unwrap_or_default()),
            "cos" => {
                let url = v["url"].as_str().unwrap_or_default();
                let css = engine::cosmetic_css(url);
                if !css.is_empty() {
                    let _ = self.tabs[i]
                        .webview
                        .evaluate_script(&format!("window.__zetCos&&__zetCos('base',{})", json!(css)));
                }
            }
            "gen" => {
                let url = v["url"].as_str().unwrap_or_default();
                if is_youtube(url) {
                    return;
                }
                let list = |k: &str| -> Vec<String> {
                    v[k].as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(|x| x.as_str().map(str::to_string))
                        .take(5000)
                        .collect()
                };
                let css = engine::generic_css(url, &list("c"), &list("i"));
                if !css.is_empty() {
                    let _ = self.tabs[i]
                        .webview
                        .evaluate_script(&format!("window.__zetCos&&__zetCos('gen',{})", json!(css)));
                }
            }
            // Yerleşik sayfalardan gelenler
            "go" if internal_page => {
                let text = v["text"].as_str().unwrap_or_default().to_string();
                self.active = i;
                self.navigate(&text);
            }
            "ntp" if internal_page => {
                let mode = match self.tabs[i].kind {
                    Kind::Tor => "Tor sekmesi: trafiğin Tor ağından geçiyor, geçmiş kaydedilmez",
                    Kind::Private => "Gizli sekme: geçmiş, çerez ve site verileri kaydedilmez",
                    Kind::Normal => "",
                };
                let d = json!({ "blocked": engine::TOTAL_BLOCKED.load(Ordering::Relaxed), "mode": mode });
                let _ = self.tabs[i].webview.evaluate_script(&format!("window.zetNtp&&zetNtp({d})"));
            }
            "getSettings" if internal_page => {
                let engines: Vec<Value> =
                    settings::ENGINES.iter().map(|e| json!({"id": e.id, "name": e.name})).collect();
                let s = &self.settings;
                let version = match updater::current() {
                    0 => "geliştirme derlemesi".to_string(),
                    n => format!("Sürüm android-{n} (Windows)"),
                };
                let d = json!({
                    "engines": engines, "engine": s.engine, "adblock": s.adblock, "cookies": s.cookies,
                    "auto_update": s.auto_update, "version": version,
                });
                let _ = self.tabs[i].webview.evaluate_script(&format!("window.zetSettings&&zetSettings({d})"));
            }
            "set" if internal_page => {
                let key = v["key"].as_str().unwrap_or_default();
                let val = &v["value"];
                match key {
                    "engine" => self.settings.engine = val.as_str().unwrap_or("google").to_string(),
                    "adblock" => {
                        self.settings.adblock = val.as_bool().unwrap_or(true);
                        engine::set_enabled(self.settings.adblock);
                        self.refresh_ui();
                    }
                    "cookies" => self.settings.cookies = val.as_bool().unwrap_or(true),
                    "auto_update" => self.settings.auto_update = val.as_bool().unwrap_or(true),
                    _ => return,
                }
                settings::save(&self.settings);
            }
            "checkUpdate" if internal_page => self.check_update(true),
            "clearData" if internal_page => {
                let _ = std::fs::write(settings::data_dir().join("clear-data"), b"1");
                self.toast("Site verileri Zet yeniden açılınca silinecek");
            }
            _ => {}
        }
    }

    fn check_update(&self, manual: bool) {
        if manual {
            self.toast("Güncelleme denetleniyor…");
        }
        let p = self.proxy.clone();
        std::thread::spawn(move || match updater::check() {
            Ok(Some(r)) => {
                let _ = p.send_event(UserEvent::Update(r));
            }
            Ok(None) => {
                let _ = p.send_event(UserEvent::UpdateNone(manual));
            }
            Err(e) => {
                if manual {
                    let _ = p.send_event(UserEvent::Toast(format!("Güncelleme denetlenemedi: {e}")));
                }
            }
        });
    }

    fn start_update(&mut self) {
        let Some(rel) = self.update.clone() else { return };
        self.toast(&format!("{} indiriliyor…", rel.tag));
        let p = self.proxy.clone();
        std::thread::spawn(move || match updater::install(&rel) {
            Ok(()) => {
                let _ = p.send_event(UserEvent::Quit);
            }
            Err(e) => {
                let _ = p.send_event(UserEvent::Toast(format!("Güncelleme başarısız: {e}")));
            }
        });
    }

    fn handle(&mut self, ev: UserEvent) -> bool {
        match ev {
            UserEvent::Ui(m) => self.on_ui(&m),
            UserEvent::Page(id, uri, m) => self.on_page(id, uri, &m),
            UserEvent::Title(id, title) => {
                if let Some(i) = self.tab_index(id) {
                    self.tabs[i].title = title;
                    self.refresh_ui();
                }
            }
            UserEvent::Load(id, url, finished) => {
                if let Some(i) = self.tab_index(id) {
                    let t = &mut self.tabs[i];
                    t.loading = !finished;
                    t.url = url;
                    if !finished {
                        t.blocked.store(0, Ordering::Relaxed);
                    }
                    self.refresh_ui();
                }
            }
            UserEvent::OpenTab(url, kind) => self.open_tab(&url, kind, true),
            UserEvent::Blocked(id) => {
                if self.current().is_some_and(|t| t.id == id) {
                    self.refresh_ui();
                }
            }
            UserEvent::Toast(m) => self.toast(&m),
            UserEvent::TorStarted(r) => self.on_tor_started(r),
            UserEvent::TorTick => {
                if self.current().is_some_and(|t| t.kind == Kind::Tor) {
                    self.refresh_ui();
                }
            }
            UserEvent::Update(r) => {
                let skipped = self.settings.skipped_update == r.code;
                self.update = Some(r.clone());
                self.refresh_ui();
                if !skipped {
                    let notes = r.notes.lines().next().unwrap_or_default().to_string();
                    self.toast(&format!("Yeni sürüm {}: {notes} — üstteki Güncelle'ye bas", r.tag));
                }
            }
            UserEvent::UpdateNone(manual) => {
                if manual {
                    self.toast("Zet güncel");
                }
            }
            UserEvent::Quit => return false,
        }
        true
    }
}

/// Adres çubuğunda yerleşik sayfaların adresi gösterilmez.
fn display_url(url: &str) -> String {
    if url.starts_with(INTERNAL) || url == "about:blank" {
        String::new()
    } else {
        url.to_string()
    }
}

pub fn run() {
    updater::cleanup();
    let data = settings::data_dir();
    if data.join("clear-data").exists() {
        let _ = std::fs::remove_dir_all(data.join("profile"));
        let _ = std::fs::remove_file(data.join("clear-data"));
    }
    let _ = std::fs::remove_dir_all(data.join("tor-profile"));
    let settings = settings::load();
    engine::set_enabled(settings.adblock);

    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let proxy = event_loop.create_proxy();
    let window = WindowBuilder::new()
        .with_title("Zet")
        .with_inner_size(LogicalSize::new(1280.0, 840.0))
        .with_min_inner_size(LogicalSize::new(520.0, 360.0))
        .build(&event_loop)
        .expect("pencere oluşturulamadı");

    let mut ctx = WebContext::new(Some(data.join("profile")));
    let px = proxy.clone();
    let scale = window.scale_factor();
    let width = window.inner_size().to_logical::<f64>(scale).width;
    let ui = WebViewBuilder::new_with_web_context(&mut ctx)
        .with_html(UI_HTML)
        .with_bounds(Rect {
            position: wry::dpi::LogicalPosition::new(0.0, 0.0).into(),
            size: wry::dpi::LogicalSize::new(width, UI_HEIGHT).into(),
        })
        .with_ipc_handler(move |req: Request<String>| {
            let _ = px.send_event(UserEvent::Ui(req.into_body()));
        })
        .build_as_child(&window)
        .expect("arayüz oluşturulamadı");

    // Filtre listeleri arka planda
    {
        let p = proxy.clone();
        let cookies = settings.cookies;
        std::thread::spawn(move || {
            engine::load_and_refresh(data.join("filters"), cookies, |updating| {
                let msg = if updating { "Reklam listeleri güncelleniyor…" } else { "Reklam listeleri hazır" };
                let _ = p.send_event(UserEvent::Toast(msg.into()));
            });
        });
    }

    let mut app = App {
        window,
        ui,
        tabs: Vec::new(),
        active: 0,
        next_id: 1,
        proxy,
        ctx,
        tor_ctx: None,
        tor_port: 0,
        tor_pending: 0,
        settings,
        closed: Vec::new(),
        update: None,
    };

    // Komut satırından gelen adres (varsayılan tarayıcı olarak açılınca) veya önceki oturum
    let arg = std::env::args().nth(1).filter(|a| !a.starts_with('-'));
    let session = settings::load_session();
    if let Some(a) = arg {
        let url = zet_core::url::normalize_input(&a, app.settings.search_prefix());
        app.open_tab(&url, Kind::Normal, true);
    } else if !session.tabs.is_empty() {
        for u in &session.tabs {
            app.open_tab(u, Kind::Normal, false);
        }
        app.select(session.active.min(app.tabs.len().saturating_sub(1)));
    } else {
        app.open_tab(&internal("newtab"), Kind::Normal, true);
    }
    if app.settings.auto_update {
        app.check_update(false);
    }

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        match event {
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => {
                app.save_session();
                *control_flow = ControlFlow::Exit;
            }
            Event::WindowEvent { event: WindowEvent::Resized(PhysicalSize { .. }), .. }
            | Event::WindowEvent { event: WindowEvent::ScaleFactorChanged { .. }, .. } => app.layout(),
            Event::UserEvent(ev) => {
                if !app.handle(ev) {
                    app.save_session();
                    *control_flow = ControlFlow::Exit;
                }
            }
            _ => {}
        }
    });
}
