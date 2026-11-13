//! Reklam engelleme: Brave'in `adblock` motoru üzerinde ince bir sarmalayıcı.
//!
//! Kabuklar (Windows/Android) her ağ isteği için [`AdBlocker::check`] çağırır,
//! her sayfa yüklemesinde [`AdBlocker::cosmetic_css`] ile gizlenecek
//! öğelerin CSS'ini ve enjekte edilecek scriptleti alır.

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;

/// İstek türü. WebView'ların bildirdiği türler bunlara eşlenir.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResourceType {
    Document,
    SubDocument,
    Script,
    Stylesheet,
    Image,
    Media,
    Font,
    Xhr,
    WebSocket,
    Other,
}

impl ResourceType {
    fn as_str(self) -> &'static str {
        match self {
            ResourceType::Document => "document",
            ResourceType::SubDocument => "subdocument",
            ResourceType::Script => "script",
            ResourceType::Stylesheet => "stylesheet",
            ResourceType::Image => "image",
            ResourceType::Media => "media",
            ResourceType::Font => "font",
            ResourceType::Xhr => "xmlhttprequest",
            ResourceType::WebSocket => "websocket",
            ResourceType::Other => "other",
        }
    }

    /// Android WebView türü bildirmediği için URL uzantısından tahmin eder.
    pub fn guess_from_url(url: &str) -> Self {
        let path = url
            .split(['?', '#'])
            .next()
            .unwrap_or("")
            .to_ascii_lowercase();
        let ext = path.rsplit('.').next().unwrap_or("");
        match ext {
            "js" | "mjs" => ResourceType::Script,
            "css" => ResourceType::Stylesheet,
            "png" | "jpg" | "jpeg" | "gif" | "webp" | "svg" | "ico" | "avif" => ResourceType::Image,
            "mp4" | "webm" | "m3u8" | "mp3" | "ogg" => ResourceType::Media,
            "woff" | "woff2" | "ttf" | "otf" => ResourceType::Font,
            _ => ResourceType::Other,
        }
    }
}

/// Bir ağ isteği için verilen karar.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BlockDecision {
    /// İsteğe dokunma.
    Allow,
    /// İsteği engelle (boş yanıt dön).
    Block,
    /// İstek yerine bu içeriği döndür (örn. boş bir reklam scripti).
    /// Değer `data:` URL'si biçimindedir.
    Redirect(String),
    /// İsteği bu URL'ye yönlendir (izleme parametreleri temizlenmiş hali).
    Rewrite(String),
}

/// Reklam engelleme motoru.
pub struct AdBlocker {
    engine: Engine,
    enabled: bool,
}

impl AdBlocker {
    /// Filtre listesi metinlerinden (EasyList vb.) motor oluşturur.
    pub fn from_lists<I, S>(lists: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        let mut set = FilterSet::new(false);
        for list in lists {
            set.add_filter_list(list.into(), ParseOptions::default());
        }
        Self {
            engine: Engine::new_with_filter_set(set),
            enabled: true,
        }
    }

    /// Hiç filtre içermeyen motor (listeler indirilene kadar kullanılır).
    pub fn empty() -> Self {
        Self::from_lists(std::iter::empty::<String>())
    }

    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }

    /// `url`: istenen kaynak, `page_url`: isteği yapan sayfa.
    pub fn check(&self, url: &str, page_url: &str, kind: ResourceType) -> BlockDecision {
        if !self.enabled || !(url.starts_with("http://") || url.starts_with("https://")) {
            return BlockDecision::Allow;
        }
        let Ok(request) = Request::new(url, page_url, kind.as_str(), "GET") else {
            return BlockDecision::Allow;
        };
        let result = self.engine.check_network_request(&request);
        if result.should_block() {
            if let Some(redirect) = result.redirect {
                return BlockDecision::Redirect(redirect);
            }
            return BlockDecision::Block;
        }
        if let Some(rewritten) = result.rewritten_url {
            return BlockDecision::Rewrite(rewritten);
        }
        BlockDecision::Allow
    }

    /// Sayfaya enjekte edilecek gizleme CSS'i ve scriptlet JS'i.
    pub fn cosmetic(&self, page_url: &str) -> Cosmetic {
        if !self.enabled {
            return Cosmetic::default();
        }
        let res = self.engine.url_cosmetic_resources(page_url);
        let mut selectors: Vec<String> = res.hide_selectors.into_iter().collect();
        selectors.sort();
        let css = if selectors.is_empty() {
            String::new()
        } else {
            format!("{}{{display:none!important}}", selectors.join(","))
        };
        Cosmetic {
            css,
            script: res.injected_script,
        }
    }
}

/// Bir sayfa için kozmetik filtreleme çıktısı.
#[derive(Debug, Default, Clone)]
pub struct Cosmetic {
    pub css: String,
    pub script: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    const LIST: &str = "\
||doubleclick.net^
||ads.example.com^$third-party
@@||ads.example.com/allowed.js
example.org##.reklam-kutusu
example.org###sponsor
";

    fn blocker() -> AdBlocker {
        AdBlocker::from_lists([LIST])
    }

    #[test]
    fn blocks_known_ad_domain() {
        let b = blocker();
        assert_eq!(
            b.check(
                "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
                "https://news.site/",
                ResourceType::Script
            ),
            BlockDecision::Block
        );
    }

    #[test]
    fn allows_normal_requests() {
        let b = blocker();
        assert_eq!(
            b.check("https://news.site/app.js", "https://news.site/", ResourceType::Script),
            BlockDecision::Allow
        );
    }

    #[test]
    fn exception_rules_win() {
        let b = blocker();
        assert_eq!(
            b.check(
                "https://ads.example.com/allowed.js",
                "https://news.site/",
                ResourceType::Script
            ),
            BlockDecision::Allow
        );
        assert_eq!(
            b.check(
                "https://ads.example.com/banner.js",
                "https://news.site/",
                ResourceType::Script
            ),
            BlockDecision::Block
        );
    }

    #[test]
    fn disabled_allows_everything() {
        let mut b = blocker();
        b.set_enabled(false);
        assert_eq!(
            b.check("https://doubleclick.net/x", "https://a.com/", ResourceType::Script),
            BlockDecision::Allow
        );
    }

    #[test]
    fn cosmetic_css_for_site() {
        let b = blocker();
        let c = b.cosmetic("https://example.org/haber");
        assert!(c.css.contains(".reklam-kutusu"), "{}", c.css);
        assert!(c.css.contains("#sponsor"), "{}", c.css);
        assert!(b.cosmetic("https://other.com/").css.is_empty());
    }

    #[test]
    fn guesses_resource_type() {
        assert_eq!(ResourceType::guess_from_url("https://a.com/x.js?v=1"), ResourceType::Script);
        assert_eq!(ResourceType::guess_from_url("https://a.com/p.WEBP"), ResourceType::Image);
        assert_eq!(ResourceType::guess_from_url("https://a.com/api"), ResourceType::Other);
    }
}
