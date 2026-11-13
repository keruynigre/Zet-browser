//! Yerleşik Tor: Arti istemcisini uygulama içi yerel bir SOCKS5 proxy olarak
//! çalıştırır. Tarayıcı (WebView) bu proxy'ye yönlendirilir; böylece tüm sayfa
//! trafiği ve DNS çözümlemesi Tor ağı üzerinden geçer.

pub mod socks;

use std::sync::{Arc, Mutex};

use arti_client::config::TorClientConfigBuilder;
use arti_client::{BootstrapBehavior, TorClient};
use futures::StreamExt;
use tokio::net::TcpListener;
use tokio::runtime::Runtime;
use tor_rtcompat::PreferredRuntime;

/// Bağlantı durumu.
#[derive(Debug, Clone, PartialEq)]
pub enum State {
    Off,
    /// Tor ağına bağlanılıyor (0.0 - 1.0).
    Starting(f32),
    Ready,
    Failed(String),
}

struct Running {
    runtime: Runtime,
    port: u16,
}

static RUNNING: Mutex<Option<Running>> = Mutex::new(None);
/// (kuşak, durum): her start() yeni kuşak açar; durdurulmuş eski bir
/// çalışmanın görevleri durumu ezemez.
static STATE: Mutex<(u64, State)> = Mutex::new((0, State::Off));

fn new_generation(s: State) -> u64 {
    let mut g = STATE.lock().unwrap_or_else(|e| e.into_inner());
    g.0 += 1;
    g.1 = s;
    g.0
}

/// Yalnızca kuşak hâlâ geçerliyse durumu günceller.
fn set_state(generation: u64, s: State) {
    let mut g = STATE.lock().unwrap_or_else(|e| e.into_inner());
    if g.0 == generation {
        g.1 = s;
    }
}

pub fn state() -> State {
    STATE.lock().unwrap_or_else(|e| e.into_inner()).1.clone()
}

/// Durumu JSON olarak döner: {"state":"off|starting|ready|failed","progress":0-100,"error":"","port":N}
pub fn status_json() -> String {
    let port = RUNNING
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .as_ref()
        .map(|r| r.port)
        .unwrap_or(0);
    let (name, progress, error) = match state() {
        State::Off => ("off", 0, String::new()),
        State::Starting(f) => ("starting", (f * 100.0) as u32, String::new()),
        State::Ready => ("ready", 100, String::new()),
        State::Failed(e) => ("failed", 0, e),
    };
    serde_json::json!({ "state": name, "progress": progress, "error": error, "port": port })
        .to_string()
}

/// Tor'u başlatır ve yerel SOCKS5 portunu döner. Zaten çalışıyorsa aynı portu
/// döner. Port hemen açılır; gelen bağlantılar Tor hazır olana kadar bekler.
///
/// `data_dir`: Tor'un dizin önbelleği ve durumu için yazılabilir bir klasör.
pub fn start(data_dir: &str) -> Result<u16, String> {
    let mut running = RUNNING.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(r) = running.as_ref() {
        return Ok(r.port);
    }
    // Tor'un TLS bağlantıları için şifreleme sağlayıcısı (başka biri kurduysa yoksay).
    let _ = rustls::crypto::ring::default_provider().install_default();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("zet-tor")
        .enable_all()
        .build()
        .map_err(|e| format!("çalışma zamanı: {e}"))?;

    let config = TorClientConfigBuilder::from_directories(
        format!("{data_dir}/state"),
        format!("{data_dir}/cache"),
    )
    .build()
    .map_err(|e| format!("Tor ayarı: {e}"))?;

    let (client, listener) = runtime.block_on(async {
        let client = TorClient::builder()
            .config(config)
            .bootstrap_behavior(BootstrapBehavior::OnDemand)
            .create_unbootstrapped()
            .map_err(|e| format!("Tor istemcisi: {e}"))?;
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .map_err(|e| format!("yerel port: {e}"))?;
        Ok::<_, String>((client, listener))
    })?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();

    let generation = new_generation(State::Starting(0.0));
    runtime.spawn(bootstrap(client.clone(), generation));
    runtime.spawn(accept_loop(listener, client));

    *running = Some(Running { runtime, port });
    Ok(port)
}

/// Tor'u durdurur, açık bağlantıları kapatır.
pub fn stop() {
    let taken = RUNNING.lock().unwrap_or_else(|e| e.into_inner()).take();
    if let Some(r) = taken {
        r.runtime.shutdown_background();
    }
    new_generation(State::Off);
}

async fn bootstrap(client: Arc<TorClient<PreferredRuntime>>, generation: u64) {
    let mut events = client.bootstrap_events();
    let progress = tokio::spawn(async move {
        while let Some(status) = events.next().await {
            if matches!(state(), State::Starting(_)) {
                set_state(generation, State::Starting(status.as_frac()));
            }
            if status.ready_for_traffic() {
                break;
            }
        }
    });
    match client.bootstrap().await {
        Ok(()) => set_state(generation, State::Ready),
        Err(e) => set_state(generation, State::Failed(e.to_string())),
    }
    progress.abort();
}

async fn accept_loop(listener: TcpListener, client: Arc<TorClient<PreferredRuntime>>) {
    loop {
        let Ok((stream, _)) = listener.accept().await else { continue };
        let client = client.clone();
        tokio::spawn(async move {
            let _ = socks::serve(stream, |target| async move {
                client
                    .connect((target.host, target.port))
                    .await
                    .map_err(std::io::Error::other)
            })
            .await;
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn start_opens_local_port_and_stop_closes_it() {
        // Global durum paylaşıldığı için tüm yaşam döngüsü tek testte.
        let off: serde_json::Value = serde_json::from_str(&status_json()).unwrap();
        assert_eq!(off["state"], "off");
        assert_eq!(off["port"], 0);
        let dir = std::env::temp_dir().join(format!("zet-tor-{}", std::process::id()));
        let port = start(dir.to_str().unwrap()).expect("başlamalı");
        assert!(port > 0);
        assert_eq!(start(dir.to_str().unwrap()).unwrap(), port, "ikinci çağrı aynı portu dönmeli");
        assert!(std::net::TcpStream::connect(("127.0.0.1", port)).is_ok());
        let s: serde_json::Value = serde_json::from_str(&status_json()).unwrap();
        assert!(s["state"] == "starting" || s["state"] == "failed" || s["state"] == "ready");
        assert_eq!(s["port"], port);
        stop();
        assert_eq!(state(), State::Off);
        std::thread::sleep(std::time::Duration::from_millis(500));
        assert_eq!(state(), State::Off, "durdurulan çalışma durumu ezmemeli");
        assert!(std::net::TcpStream::connect(("127.0.0.1", port)).is_err(), "port kapanmalı");
        std::fs::remove_dir_all(dir).ok();
    }
}
