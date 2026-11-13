//! Adres çubuğuna yazılanı URL'ye ya da arama sorgusuna çevirme.

/// Varsayılan arama motoru (sorgu sonuna eklenir).
pub const DEFAULT_SEARCH: &str = "https://www.google.com/search?q=";

/// Kullanıcının yazdığını yüklenecek URL'ye çevirir.
///
/// - `https://...`, `about:`, `file:` gibi şemalı girdiler olduğu gibi kalır.
/// - `ornek.com`, `localhost:8080`, `192.168.1.1` gibi alan adları `https://` alır.
/// - Geri kalan her şey arama sorgusu olur.
pub fn normalize_input(input: &str, search_prefix: &str) -> String {
    let text = input.trim();
    if text.is_empty() {
        return "about:blank".to_string();
    }
    let lower = text.to_ascii_lowercase();
    if ["http://", "https://", "about:", "file://", "data:", "view-source:"]
        .iter()
        .any(|s| lower.starts_with(s))
    {
        return text.to_string();
    }
    if !text.contains(char::is_whitespace) && looks_like_host(text) {
        return format!("https://{text}");
    }
    format!("{search_prefix}{}", encode_query(text))
}

fn looks_like_host(text: &str) -> bool {
    let host = text.split(['/', '?', '#']).next().unwrap_or("");
    let host = host.rsplit('@').next().unwrap_or(host);
    let name = match host.rsplit_once(':') {
        Some((h, port)) if !port.is_empty() && port.chars().all(|c| c.is_ascii_digit()) => h,
        _ => host,
    };
    if name.eq_ignore_ascii_case("localhost") {
        return true;
    }
    let labels: Vec<&str> = name.split('.').collect();
    if labels.len() < 2 || labels.iter().any(|l| l.is_empty()) {
        return false;
    }
    let tld = labels[labels.len() - 1];
    let valid_chars = name.chars().all(|c| c.is_alphanumeric() || c == '-' || c == '.');
    let is_ip = labels.len() == 4 && labels.iter().all(|l| l.parse::<u8>().is_ok());
    valid_chars && (is_ip || (tld.len() >= 2 && tld.chars().all(char::is_alphabetic)))
}

fn encode_query(text: &str) -> String {
    let mut out = String::with_capacity(text.len() * 3);
    for b in text.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn n(s: &str) -> String {
        normalize_input(s, "S:")
    }

    #[test]
    fn keeps_full_urls() {
        assert_eq!(n("https://youtube.com/watch?v=1"), "https://youtube.com/watch?v=1");
        assert_eq!(n("about:blank"), "about:blank");
    }

    #[test]
    fn adds_https_to_hosts() {
        assert_eq!(n("youtube.com"), "https://youtube.com");
        assert_eq!(n("m.tr.wikipedia.org/wiki/Rust"), "https://m.tr.wikipedia.org/wiki/Rust");
        assert_eq!(n("localhost:8080"), "https://localhost:8080");
        assert_eq!(n("192.168.1.1"), "https://192.168.1.1");
        assert_eq!(n("  ekşi.com  "), "https://ekşi.com");
    }

    #[test]
    fn searches_everything_else() {
        assert_eq!(n("rust dili"), "S:rust+dili");
        assert_eq!(n("1.5"), "S:1.5");
        assert_eq!(n("çay"), "S:%C3%A7ay");
        assert_eq!(n("a+b=c?"), "S:a%2Bb%3Dc%3F");
        assert_eq!(n("   "), "about:blank");
    }
}
