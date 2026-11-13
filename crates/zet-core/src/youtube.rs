//! YouTube video bilgisi alma ve indirme seçenekleri.
//!
//! YouTube'un uygulama API'si (InnerTube `player` uç noktası) bir istemci
//! kimliğiyle çağrılır; bazı istemciler doğrudan indirilebilir URL döndürür
//! (imza çözme gerekmez). Ağ kısmı [`fetch_info`], geri kalan her şey saf
//! fonksiyonlardır ve testlerle doğrulanır.

use serde::Serialize;
use serde_json::Value;

/// URL'den 11 karakterlik YouTube video kimliğini çıkarır.
pub fn video_id_from_url(url: &str) -> Option<String> {
    let rest = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))?;
    let (host, path) = rest.split_once('/').unwrap_or((rest, ""));
    let host = host.to_ascii_lowercase();
    let host = host.strip_prefix("www.").unwrap_or(&host);

    let candidate = match host {
        "youtu.be" => path.split(['?', '#', '/']).next(),
        "youtube.com" | "m.youtube.com" | "music.youtube.com" | "youtube-nocookie.com" => {
            let (p, query) = path.split_once('?').unwrap_or((path, ""));
            let p = p.split('#').next().unwrap_or("");
            if p == "watch" || p == "watch/" {
                query
                    .split(['&', '#'])
                    .find_map(|kv| kv.strip_prefix("v="))
            } else {
                ["shorts/", "embed/", "live/", "v/"]
                    .iter()
                    .find_map(|pre| p.strip_prefix(pre))
                    .and_then(|s| s.split('/').next())
            }
        }
        _ => None,
    }?;
    is_valid_id(candidate).then(|| candidate.to_string())
}

fn is_valid_id(id: &str) -> bool {
    id.len() == 11
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// API yanıtındaki tek bir akış (format).
#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Stream {
    pub itag: u32,
    pub url: String,
    pub mime: String,
    pub codecs: String,
    pub height: u32,
    pub bitrate: u64,
    /// Bayt cinsinden boyut; API vermezse 0.
    pub size: u64,
    pub has_video: bool,
    pub has_audio: bool,
}

impl Stream {
    pub fn is_mp4_family(&self) -> bool {
        self.mime == "video/mp4" || self.mime == "audio/mp4"
    }
    pub fn is_avc(&self) -> bool {
        self.codecs.starts_with("avc1")
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct VideoInfo {
    pub id: String,
    pub title: String,
    pub author: String,
    pub duration_secs: u64,
    pub streams: Vec<Stream>,
    /// İndirme sırasında kullanılacak User-Agent (istemciyle uyumlu olmalı).
    pub user_agent: String,
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum OptionKind {
    /// Görüntü + ses tek dosyada.
    Muxed,
    /// Görüntü ve ses ayrı indirilip birleştirilecek.
    Merge,
    /// Sadece ses.
    Audio,
}

/// Kullanıcıya gösterilecek bir indirme seçeneği.
#[derive(Debug, Clone, Serialize)]
pub struct DownloadOption {
    pub label: String,
    pub kind: OptionKind,
    /// İndirilecek akışlar: Merge için [görüntü, ses], diğerlerinde tek akış.
    pub streams: Vec<Stream>,
    pub total_size: u64,
    /// Kaydedilecek dosya uzantısı.
    pub ext: String,
}

/// En yüksek çözünürlük sınırı (MediaMuxer + H.264 için makul üst sınır).
const MAX_HEIGHT: u32 = 1080;

/// API yanıtını ayrıştırır. Oynatılamıyorsa YouTube'un gerekçesini döner.
pub fn parse_player_response(json: &Value, user_agent: &str) -> Result<VideoInfo, String> {
    let status = json["playabilityStatus"]["status"].as_str().unwrap_or("");
    if status != "OK" {
        let reason = json["playabilityStatus"]["reason"]
            .as_str()
            .unwrap_or("bilinmeyen neden");
        return Err(format!("{status}: {reason}"));
    }
    let details = &json["videoDetails"];
    let data = &json["streamingData"];
    let mut streams = Vec::new();
    for key in ["formats", "adaptiveFormats"] {
        if let Some(list) = data[key].as_array() {
            streams.extend(list.iter().filter_map(parse_stream));
        }
    }
    if streams.is_empty() {
        return Err("İndirilebilir akış bulunamadı (şifreli ya da canlı yayın olabilir)".into());
    }
    Ok(VideoInfo {
        id: details["videoId"].as_str().unwrap_or("").to_string(),
        title: details["title"].as_str().unwrap_or("video").to_string(),
        author: details["author"].as_str().unwrap_or("").to_string(),
        duration_secs: num(&details["lengthSeconds"]),
        streams,
        user_agent: user_agent.to_string(),
    })
}

/// Sayı ya da sayı içeren string alanları okur.
fn num(v: &Value) -> u64 {
    v.as_u64()
        .or_else(|| v.as_str().and_then(|s| s.parse().ok()))
        .unwrap_or(0)
}

fn parse_stream(f: &Value) -> Option<Stream> {
    // Şifreli (signatureCipher) akışlar atlanır: çözmek için oynatıcı JS'i gerekir.
    let url = f["url"].as_str()?.to_string();
    let mime_full = f["mimeType"].as_str()?;
    let (mime, rest) = mime_full.split_once(';').unwrap_or((mime_full, ""));
    let codecs = rest
        .split_once("codecs=\"")
        .and_then(|(_, c)| c.split('"').next())
        .unwrap_or("")
        .to_string();
    let is_video_mime = mime.starts_with("video/");
    // Video mime'lı ama tek codec'li akışlar adaptive (sessiz) görüntüdür.
    let codec_count = codecs.split(',').filter(|c| !c.trim().is_empty()).count();
    let has_audio = mime.starts_with("audio/") || (is_video_mime && codec_count >= 2);
    Some(Stream {
        itag: num(&f["itag"]) as u32,
        url,
        mime: mime.trim().to_string(),
        codecs,
        height: num(&f["height"]) as u32,
        bitrate: num(&f["bitrate"]),
        size: num(&f["contentLength"]),
        has_video: is_video_mime,
        has_audio,
    })
}

/// Kullanıcıya sunulacak seçenekler: yüksekten düşüğe video, en sonda ses.
pub fn download_options(info: &VideoInfo) -> Vec<DownloadOption> {
    let best_audio = info
        .streams
        .iter()
        .filter(|s| !s.has_video && s.has_audio && s.mime == "audio/mp4")
        .max_by_key(|s| s.bitrate)
        .cloned();

    let mut options = Vec::new();
    let mut seen_heights = Vec::new();

    if let Some(audio) = &best_audio {
        let mut videos: Vec<&Stream> = info
            .streams
            .iter()
            .filter(|s| s.has_video && !s.has_audio && s.is_avc() && s.is_mp4_family())
            .filter(|s| s.height > 0 && s.height <= MAX_HEIGHT)
            .collect();
        videos.sort_by(|a, b| b.height.cmp(&a.height).then(b.bitrate.cmp(&a.bitrate)));
        for v in videos {
            if seen_heights.contains(&v.height) {
                continue;
            }
            seen_heights.push(v.height);
            options.push(DownloadOption {
                label: format!("{}p MP4", v.height),
                kind: OptionKind::Merge,
                total_size: v.size + audio.size,
                streams: vec![v.clone(), audio.clone()],
                ext: "mp4".into(),
            });
        }
    }

    // Birleşik (muxed) akışlar: birleştirme gerektirmez, yedek seçenek.
    let mut muxed: Vec<&Stream> = info
        .streams
        .iter()
        .filter(|s| s.has_video && s.has_audio && s.mime == "video/mp4")
        .collect();
    muxed.sort_by_key(|s| std::cmp::Reverse(s.height));
    for m in muxed {
        if seen_heights.contains(&m.height) {
            continue;
        }
        seen_heights.push(m.height);
        options.push(DownloadOption {
            label: format!("{}p MP4", m.height),
            kind: OptionKind::Muxed,
            total_size: m.size,
            streams: vec![m.clone()],
            ext: "mp4".into(),
        });
    }
    options.sort_by(|a, b| b.streams[0].height.cmp(&a.streams[0].height));

    if let Some(audio) = best_audio {
        options.push(DownloadOption {
            label: format!("Sadece ses (M4A, {} kbps)", audio.bitrate / 1000),
            kind: OptionKind::Audio,
            total_size: audio.size,
            streams: vec![audio],
            ext: "m4a".into(),
        });
    }
    options
}

/// Dosya adı için güvenli hale getirir.
pub fn safe_file_name(title: &str) -> String {
    let cleaned: String = title
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            c if c.is_control() => '_',
            c => c,
        })
        .collect();
    let trimmed = cleaned.trim().trim_matches('.');
    let mut out: String = trimmed.chars().take(120).collect();
    if out.is_empty() {
        out = "video".into();
    }
    out
}

/// Denenecek istemci kimlikleri (sırayla). YouTube bunları zaman zaman
/// değiştirdiği için burada tek yerde tutulur.
struct Client {
    name: &'static str,
    context: &'static str,
    user_agent: &'static str,
}

const CLIENTS: &[Client] = &[
    Client {
        name: "ANDROID_VR 1.65",
        context: r#"{"clientName":"ANDROID_VR","clientVersion":"1.65.10","deviceMake":"Oculus","deviceModel":"Quest 3","androidSdkVersion":32,"osName":"Android","osVersion":"12L"}"#,
        user_agent: "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    },
    Client {
        name: "ANDROID_VR 1.62",
        context: r#"{"clientName":"ANDROID_VR","clientVersion":"1.62.27","deviceMake":"Oculus","deviceModel":"Quest 3","androidSdkVersion":32,"osName":"Android","osVersion":"12L"}"#,
        user_agent: "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    },
    Client {
        name: "ANDROID_VR 1.57",
        context: r#"{"clientName":"ANDROID_VR","clientVersion":"1.57.29","deviceMake":"Oculus","deviceModel":"Quest 3","androidSdkVersion":32,"osName":"Android","osVersion":"12L"}"#,
        user_agent: "com.google.android.apps.youtube.vr.oculus/1.57.29 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    },
    Client {
        name: "IOS",
        context: r#"{"clientName":"IOS","clientVersion":"20.10.4","deviceMake":"Apple","deviceModel":"iPhone16,2","osName":"iPhone","osVersion":"18.3.2.22D82"}"#,
        user_agent: "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
    },
];

fn player_body(client: &Client, video_id: &str) -> Value {
    let mut ctx: Value = serde_json::from_str(client.context).expect("geçerli JSON");
    ctx["hl"] = "tr".into();
    ctx["gl"] = "TR".into();
    serde_json::json!({
        "videoId": video_id,
        "context": { "client": ctx },
        "contentCheckOk": true,
        "racyCheckOk": true,
    })
}

fn fetch_player(client: &Client, video_id: &str) -> Result<Value, String> {
    let mut resp = crate::net::agent()
        .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .header("User-Agent", client.user_agent)
        .header("Content-Type", "application/json")
        .header("Origin", "https://www.youtube.com")
        .send_json(player_body(client, video_id))
        .map_err(|e| format!("API isteği: {e}"))?;
    resp.body_mut()
        .with_config()
        .limit(20 << 20)
        .read_json()
        .map_err(|e| format!("API yanıtı okunamadı: {e}"))
}

/// Akışın gerçekten indirilebildiğini ilk 1 KB'ı isteyerek doğrular.
/// (Bazı istemcilerin URL'leri ek doğrulama jetonu olmadan 403 döner.)
fn probe_stream(stream: &Stream, user_agent: &str) -> Result<(), String> {
    let url = crate::download::with_range(&stream.url, 0, 1023);
    let resp = crate::net::agent()
        .get(&url)
        .header("User-Agent", user_agent)
        .call()
        .map_err(|e| format!("akış itag {}: {e}", stream.itag))?;
    let mut body = Vec::new();
    use std::io::Read;
    resp.into_body()
        .into_reader()
        .take(2048)
        .read_to_end(&mut body)
        .map_err(|e| format!("akış itag {}: {e}", stream.itag))?;
    if body.is_empty() {
        return Err(format!("akış itag {}: boş yanıt", stream.itag));
    }
    Ok(())
}

/// Video bilgisini ağdan alır. İstemcileri sırayla dener; her birinde
/// en iyi seçeneğin akışlarının indirilebildiğini doğrular.
pub fn fetch_info(video_id: &str) -> Result<VideoInfo, String> {
    let mut errors = Vec::new();
    for client in CLIENTS {
        let attempt = fetch_player(client, video_id)
            .and_then(|json| parse_player_response(&json, client.user_agent))
            .and_then(|info| {
                let options = download_options(&info);
                let first = options.first().ok_or("uygun format yok")?;
                for stream in &first.streams {
                    probe_stream(stream, client.user_agent)?;
                }
                Ok(info)
            });
        match attempt {
            Ok(info) => return Ok(info),
            Err(e) => errors.push(format!("• {}: {e}", client.name)),
        }
    }
    Err(format!(
        "Denenen tüm istemciler başarısız oldu:\n{}",
        errors.join("\n")
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn extracts_video_ids() {
        let id = Some("dQw4w9WgXcQ".to_string());
        for url in [
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=10",
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ?feature=x",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RD",
        ] {
            assert_eq!(video_id_from_url(url), id, "{url}");
        }
        for url in [
            "https://www.youtube.com/",
            "https://www.youtube.com/results?search_query=rust",
            "https://example.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=kisa",
            "youtube.com/watch?v=dQw4w9WgXcQ",
        ] {
            assert_eq!(video_id_from_url(url), None, "{url}");
        }
    }

    fn sample() -> Value {
        json!({
            "playabilityStatus": {"status": "OK"},
            "videoDetails": {"videoId": "dQw4w9WgXcQ", "title": "Test: Video/1", "author": "Kanal", "lengthSeconds": "212"},
            "streamingData": {
                "formats": [
                    {"itag": 18, "url": "https://v/18", "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", "height": 360, "bitrate": 500000, "contentLength": "1000", "audioQuality": "AUDIO_QUALITY_LOW"}
                ],
                "adaptiveFormats": [
                    {"itag": 137, "url": "https://v/137", "mimeType": "video/mp4; codecs=\"avc1.640028\"", "height": 1080, "bitrate": 4000000, "contentLength": "50000"},
                    {"itag": 248, "url": "https://v/248", "mimeType": "video/webm; codecs=\"vp9\"", "height": 1080, "bitrate": 3000000, "contentLength": "40000"},
                    {"itag": 299, "url": "https://v/299", "mimeType": "video/mp4; codecs=\"avc1.64002a\"", "height": 1080, "bitrate": 6000000, "contentLength": "70000"},
                    {"itag": 136, "url": "https://v/136", "mimeType": "video/mp4; codecs=\"avc1.4d401f\"", "height": 720, "bitrate": 2000000, "contentLength": "20000"},
                    {"itag": 401, "url": "https://v/401", "mimeType": "video/mp4; codecs=\"av01.0.12M.08\"", "height": 2160, "bitrate": 9000000, "contentLength": "90000"},
                    {"itag": 134, "signatureCipher": "s=abc&url=x", "mimeType": "video/mp4; codecs=\"avc1.4d401e\"", "height": 360},
                    {"itag": 139, "url": "https://v/139", "mimeType": "audio/mp4; codecs=\"mp4a.40.5\"", "bitrate": 48000, "contentLength": "300", "audioQuality": "AUDIO_QUALITY_LOW"},
                    {"itag": 140, "url": "https://v/140", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 130000, "contentLength": "800", "audioQuality": "AUDIO_QUALITY_MEDIUM"},
                    {"itag": 251, "url": "https://v/251", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000, "contentLength": "900", "audioQuality": "AUDIO_QUALITY_MEDIUM"}
                ]
            }
        })
    }

    #[test]
    fn parses_streams() {
        let info = parse_player_response(&sample(), "UA").unwrap();
        assert_eq!(info.title, "Test: Video/1");
        assert_eq!(info.duration_secs, 212);
        assert_eq!(info.streams.len(), 9, "şifreli akış atlanmalı");
        let muxed = info.streams.iter().find(|s| s.itag == 18).unwrap();
        assert!(muxed.has_video && muxed.has_audio);
        let v = info.streams.iter().find(|s| s.itag == 137).unwrap();
        assert!(v.has_video && !v.has_audio && v.is_avc());
        let a = info.streams.iter().find(|s| s.itag == 140).unwrap();
        assert!(!a.has_video && a.has_audio);
    }

    #[test]
    fn builds_options() {
        let info = parse_player_response(&sample(), "UA").unwrap();
        let opts = download_options(&info);
        let labels: Vec<&str> = opts.iter().map(|o| o.label.as_str()).collect();
        assert_eq!(labels, ["1080p MP4", "720p MP4", "360p MP4", "Sadece ses (M4A, 130 kbps)"]);

        // 1080p: en yüksek bitrate'li AVC (299) + en iyi AAC (140)
        assert_eq!(opts[0].kind, OptionKind::Merge);
        assert_eq!(opts[0].streams[0].itag, 299);
        assert_eq!(opts[0].streams[1].itag, 140);
        assert_eq!(opts[0].total_size, 70800);
        assert_eq!(opts[2].kind, OptionKind::Muxed);
        assert_eq!(opts[3].kind, OptionKind::Audio);
        assert_eq!(opts[3].ext, "m4a");
    }

    #[test]
    fn reports_unplayable_reason() {
        let j = json!({"playabilityStatus": {"status": "LOGIN_REQUIRED", "reason": "Oturum açın"}});
        assert_eq!(parse_player_response(&j, "UA").unwrap_err(), "LOGIN_REQUIRED: Oturum açın");
    }

    #[test]
    fn sanitizes_file_names() {
        assert_eq!(safe_file_name("Test: Video/1?"), "Test_ Video_1_");
        assert_eq!(safe_file_name("  ...  "), "video");
        assert_eq!(safe_file_name("Şarkı | Klip"), "Şarkı _ Klip");
    }

    #[test]
    fn client_contexts_are_valid_json() {
        for c in CLIENTS {
            let body = player_body(c, "dQw4w9WgXcQ");
            assert_eq!(body["context"]["client"]["hl"], "tr");
            assert!(body["context"]["client"]["clientName"].is_string());
        }
    }
}
