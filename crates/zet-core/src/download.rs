//! Arka planda çalışan, parçalı ve paralel dosya indirme işleri.
//!
//! googlevideo.com sunucuları tek parça uzun indirmeleri yavaşlatır; bu yüzden
//! dosya `&range=başlangıç-bitiş` parametresiyle ~10 MB'lık parçalara bölünüp
//! birkaç iş parçacığıyla aynı anda indirilir.

use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

const CHUNK: u64 = 10 * 1024 * 1024;
/// 403 alındığında parçalar bu boyuta bölünüp sırayla indirilir.
const SMALL_CHUNK: u64 = 1024 * 1024;
const WORKERS: usize = 3;
const RETRIES: usize = 3;

/// İndirilecek tek bir dosya.
#[derive(Debug, Clone)]
pub struct Part {
    pub url: String,
    pub path: String,
    /// Bilinen boyut (0 ise parça parça değil tek seferde indirilir).
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum JobState {
    Running,
    Done,
    Failed(String),
    Cancelled,
}

pub struct Job {
    pub downloaded: AtomicU64,
    pub total: AtomicU64,
    cancel: AtomicBool,
    state: Mutex<JobState>,
}

impl Job {
    pub fn state(&self) -> JobState {
        self.state.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    fn set_state(&self, s: JobState) {
        *self.state.lock().unwrap_or_else(|e| e.into_inner()) = s;
    }

    /// Kotlin tarafının okuyacağı JSON durum.
    pub fn status_json(&self) -> String {
        let (state, error) = match self.state() {
            JobState::Running => ("running", String::new()),
            JobState::Done => ("done", String::new()),
            JobState::Failed(e) => ("failed", e),
            JobState::Cancelled => ("cancelled", String::new()),
        };
        serde_json::json!({
            "state": state,
            "error": error,
            "downloaded": self.downloaded.load(Ordering::Relaxed),
            "total": self.total.load(Ordering::Relaxed),
        })
        .to_string()
    }
}

fn jobs() -> &'static Mutex<HashMap<u64, Arc<Job>>> {
    static JOBS: OnceLock<Mutex<HashMap<u64, Arc<Job>>>> = OnceLock::new();
    JOBS.get_or_init(Default::default)
}

static NEXT_ID: AtomicU64 = AtomicU64::new(1);

/// İndirmeyi arka planda başlatır ve iş kimliğini döner.
pub fn start(parts: Vec<Part>, user_agent: String) -> u64 {
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    let job = Arc::new(Job {
        downloaded: AtomicU64::new(0),
        total: AtomicU64::new(parts.iter().map(|p| p.size).sum()),
        cancel: AtomicBool::new(false),
        state: Mutex::new(JobState::Running),
    });
    jobs().lock().unwrap().insert(id, job.clone());
    std::thread::spawn(move || {
        let result = parts
            .iter()
            .try_for_each(|p| download_part(p, &user_agent, &job));
        let state = if job.cancel.load(Ordering::Relaxed) {
            JobState::Cancelled
        } else {
            match result {
                Ok(()) => JobState::Done,
                Err(e) => JobState::Failed(e),
            }
        };
        if state != JobState::Done {
            for p in &parts {
                let _ = std::fs::remove_file(&p.path);
            }
        }
        job.set_state(state);
    });
    id
}

pub fn get(id: u64) -> Option<Arc<Job>> {
    jobs().lock().unwrap().get(&id).cloned()
}

pub fn cancel(id: u64) {
    if let Some(job) = get(id) {
        job.cancel.store(true, Ordering::Relaxed);
    }
}

/// Bitmiş işi kayıttan siler.
pub fn forget(id: u64) {
    jobs().lock().unwrap().remove(&id);
}

pub(crate) fn with_range(url: &str, start: u64, end: u64) -> String {
    let sep = if url.contains('?') { '&' } else { '?' };
    format!("{url}{sep}range={start}-{end}")
}

fn download_part(part: &Part, ua: &str, job: &Job) -> Result<(), String> {
    if part.size == 0 {
        return download_whole(part, ua, job);
    }
    let file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&part.path)
        .map_err(|e| format!("Dosya açılamadı: {e}"))?;
    file.set_len(part.size).map_err(|e| e.to_string())?;
    let file = Arc::new(file);

    let chunks = part.size.div_ceil(CHUNK) as usize;
    let next = AtomicUsize::new(0);
    let error: Mutex<Option<String>> = Mutex::new(None);
    let ctx = ChunkCtx {
        url: &part.url,
        ua,
        file: &file,
        job,
        serial: AtomicBool::new(false),
        lock: Mutex::new(()),
    };

    std::thread::scope(|s| {
        for _ in 0..WORKERS.min(chunks) {
            s.spawn(|| loop {
                if job.cancel.load(Ordering::Relaxed) || error.lock().unwrap().is_some() {
                    return;
                }
                let i = next.fetch_add(1, Ordering::Relaxed);
                if i >= chunks {
                    return;
                }
                let start = i as u64 * CHUNK;
                let end = (start + CHUNK).min(part.size) - 1;
                if let Err(e) = ctx.fetch(start, end) {
                    error.lock().unwrap().get_or_insert(e);
                    return;
                }
            });
        }
    });

    if job.cancel.load(Ordering::Relaxed) {
        return Err("İptal edildi".into());
    }
    match error.into_inner().unwrap() {
        Some(e) => Err(e),
        None => Ok(()),
    }
}

/// Bir dosyanın parçalarını indiren iş parçacıklarının ortak durumu.
struct ChunkCtx<'a> {
    url: &'a str,
    ua: &'a str,
    file: &'a File,
    job: &'a Job,
    /// Sunucu 403 verdiyse true: istekler artık tek tek ve küçük parçalarla.
    serial: AtomicBool,
    lock: Mutex<()>,
}

impl ChunkCtx<'_> {
    fn fetch(&self, start: u64, end: u64) -> Result<(), String> {
        if !self.serial.load(Ordering::Relaxed) {
            match self.fetch_range(start, end) {
                Ok(()) => return Ok(()),
                Err(e) if is_forbidden(&e) => {
                    self.serial.store(true, Ordering::Relaxed);
                }
                Err(e) => return Err(e),
            }
        }
        // Yavaş mod: küçük parçalar, aynı anda tek istek.
        let _guard = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        let mut s = start;
        while s <= end {
            let e = (s + SMALL_CHUNK - 1).min(end);
            self.fetch_range(s, e)?;
            s = e + 1;
        }
        Ok(())
    }

    /// Aralığı yeniden denemelerle indirir.
    fn fetch_range(&self, start: u64, end: u64) -> Result<(), String> {
        let mut last_err = String::new();
        for attempt in 0..RETRIES {
            if attempt > 0 {
                std::thread::sleep(std::time::Duration::from_millis(400 * attempt as u64));
            }
            let mut written = 0u64;
            match try_fetch_chunk(self.url, self.ua, start, end, self.file, self.job, &mut written) {
                Ok(()) => return Ok(()),
                Err(e) => {
                    // Başarısız denemede sayılan baytları geri al.
                    self.job.downloaded.fetch_sub(written, Ordering::Relaxed);
                    if self.job.cancel.load(Ordering::Relaxed) {
                        return Err(e);
                    }
                    last_err = e;
                }
            }
        }
        Err(format!("Parça {start}-{end} indirilemedi: {last_err}"))
    }
}

fn is_forbidden(err: &str) -> bool {
    err.contains("403")
}

fn try_fetch_chunk(
    url: &str,
    ua: &str,
    start: u64,
    end: u64,
    file: &File,
    job: &Job,
    written: &mut u64,
) -> Result<(), String> {
    let resp = crate::net::agent()
        .get(&with_range(url, start, end))
        .header("User-Agent", ua)
        .call()
        .map_err(|e| e.to_string())?;
    let mut reader = resp.into_body().into_reader();
    let mut buf = vec![0u8; 256 * 1024];
    let mut offset = start;
    loop {
        if job.cancel.load(Ordering::Relaxed) {
            return Err("İptal edildi".into());
        }
        let n = reader.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        write_at(file, &buf[..n], offset)?;
        offset += n as u64;
        *written += n as u64;
        job.downloaded.fetch_add(n as u64, Ordering::Relaxed);
    }
    if offset != end + 1 {
        return Err(format!("Eksik parça: {}/{} bayt", offset - start, end + 1 - start));
    }
    Ok(())
}

#[cfg(unix)]
fn write_at(file: &File, data: &[u8], offset: u64) -> Result<(), String> {
    use std::os::unix::fs::FileExt;
    file.write_all_at(data, offset).map_err(|e| e.to_string())
}

#[cfg(windows)]
fn write_at(file: &File, mut data: &[u8], mut offset: u64) -> Result<(), String> {
    use std::os::windows::fs::FileExt;
    while !data.is_empty() {
        let n = file.seek_write(data, offset).map_err(|e| e.to_string())?;
        data = &data[n..];
        offset += n as u64;
    }
    Ok(())
}

/// Boyutu bilinmeyen dosyayı tek akışta indirir.
fn download_whole(part: &Part, ua: &str, job: &Job) -> Result<(), String> {
    let resp = crate::net::agent()
        .get(&part.url)
        .header("User-Agent", ua)
        .call()
        .map_err(|e| e.to_string())?;
    if let Some(len) = resp
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok())
    {
        job.total.fetch_add(len, Ordering::Relaxed);
    }
    let mut reader = resp.into_body().into_reader();
    let mut file = File::create(&part.path).map_err(|e| format!("Dosya açılamadı: {e}"))?;
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        if job.cancel.load(Ordering::Relaxed) {
            return Err("İptal edildi".into());
        }
        let n = reader.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            return Ok(());
        }
        file.write_all(&buf[..n]).map_err(|e| e.to_string())?;
        job.downloaded.fetch_add(n as u64, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};
    use std::net::TcpListener;

    /// `?range=a-b` parametresini anlayan küçük yerel HTTP sunucusu.
    fn serve(data: Vec<u8>) -> String {
        serve_limited(data, usize::MAX)
    }

    /// `max_range` bayttan büyük aralık isteklerine 403 döner (YouTube benzeri).
    fn serve_limited(data: Vec<u8>, max_range: usize) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let data = data.clone();
                std::thread::spawn(move || {
                    let mut reader = BufReader::new(stream.try_clone().unwrap());
                    let mut line = String::new();
                    reader.read_line(&mut line).unwrap();
                    loop {
                        let mut h = String::new();
                        if reader.read_line(&mut h).unwrap() == 0 || h == "\r\n" {
                            break;
                        }
                    }
                    let path = line.split_whitespace().nth(1).unwrap_or("/");
                    let body = match path.split_once("range=") {
                        Some((_, r)) => {
                            let (a, b) = r.split_once('-').unwrap();
                            let (a, b): (usize, usize) = (a.parse().unwrap(), b.parse().unwrap());
                            if b + 1 - a > max_range {
                                let mut s = stream;
                                let _ = write!(
                                    s,
                                    "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                );
                                return;
                            }
                            data[a..=b].to_vec()
                        }
                        None => data.clone(),
                    };
                    let mut s = stream;
                    let _ = write!(
                        s,
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                        body.len()
                    );
                    let _ = s.write_all(&body);
                });
            }
        });
        format!("http://{addr}/video?x=1")
    }

    fn wait(id: u64) -> JobState {
        for _ in 0..600 {
            let s = get(id).unwrap().state();
            if s != JobState::Running {
                return s;
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        panic!("zaman aşımı");
    }

    fn data(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i * 31 % 251) as u8).collect()
    }

    #[test]
    fn chunked_parallel_download_is_exact() {
        let content = data((CHUNK * 2 + 12345) as usize);
        let url = serve(content.clone());
        let path = std::env::temp_dir().join(format!("zet-dl-{}.bin", std::process::id()));
        let id = start(
            vec![Part { url, path: path.to_string_lossy().into(), size: content.len() as u64 }],
            "test".into(),
        );
        assert_eq!(wait(id), JobState::Done);
        let job = get(id).unwrap();
        assert_eq!(job.downloaded.load(Ordering::Relaxed), content.len() as u64);
        assert!(job.status_json().contains("\"done\""));
        assert_eq!(std::fs::read(&path).unwrap(), content);
        std::fs::remove_file(path).ok();
        forget(id);
        assert!(get(id).is_none());
    }

    #[test]
    fn falls_back_to_small_chunks_on_403() {
        let content = data((CHUNK * 2 + 777) as usize);
        let url = serve_limited(content.clone(), SMALL_CHUNK as usize);
        let path = std::env::temp_dir().join(format!("zet-dl-403-{}.bin", std::process::id()));
        let id = start(
            vec![Part { url, path: path.to_string_lossy().into(), size: content.len() as u64 }],
            "test".into(),
        );
        assert_eq!(wait(id), JobState::Done);
        assert_eq!(get(id).unwrap().downloaded.load(Ordering::Relaxed), content.len() as u64);
        assert_eq!(std::fs::read(&path).unwrap(), content);
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn reports_range_when_even_small_chunks_fail() {
        let content = data(3 * SMALL_CHUNK as usize);
        let url = serve_limited(content.clone(), 1000);
        let path = std::env::temp_dir().join(format!("zet-dl-403b-{}.bin", std::process::id()));
        let id = start(
            vec![Part { url, path: path.to_string_lossy().into(), size: content.len() as u64 }],
            "test".into(),
        );
        match wait(id) {
            JobState::Failed(e) => assert!(e.contains("Parça 0-") && e.contains("403"), "{e}"),
            s => panic!("beklenmeyen durum: {s:?}"),
        }
        assert!(!path.exists());
    }

    #[test]
    fn unknown_size_downloads_whole() {
        let content = data(50_000);
        let url = serve(content.clone());
        let path = std::env::temp_dir().join(format!("zet-dl-whole-{}.bin", std::process::id()));
        let id = start(
            vec![Part { url, path: path.to_string_lossy().into(), size: 0 }],
            "test".into(),
        );
        assert_eq!(wait(id), JobState::Done);
        assert_eq!(get(id).unwrap().total.load(Ordering::Relaxed), 50_000);
        assert_eq!(std::fs::read(&path).unwrap(), content);
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn failure_is_reported_and_file_removed() {
        let path = std::env::temp_dir().join(format!("zet-dl-fail-{}.bin", std::process::id()));
        let id = start(
            vec![Part {
                url: "http://127.0.0.1:1/yok".into(),
                path: path.to_string_lossy().into(),
                size: 100,
            }],
            "test".into(),
        );
        assert!(matches!(wait(id), JobState::Failed(_)));
        assert!(!path.exists());
    }

    #[test]
    fn range_param_appended() {
        assert_eq!(with_range("https://a/b?x=1", 0, 9), "https://a/b?x=1&range=0-9");
        assert_eq!(with_range("https://a/b", 5, 9), "https://a/b?range=5-9");
    }
}
