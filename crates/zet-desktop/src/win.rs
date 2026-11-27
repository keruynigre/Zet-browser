//! Windows'a özel: WebView2 ağ isteklerini yakalayıp reklamları engeller.

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use webview2_com::Microsoft::Web::WebView2::Win32::*;
use webview2_com::{take_pwstr, NavigationStartingEventHandler, WebResourceRequestedEventHandler};
use windows::core::{Interface, HSTRING, PWSTR};
use wry::{WebView, WebViewExtWindows};
use zet_core::ResourceType;

fn kind_of(ctx: COREWEBVIEW2_WEB_RESOURCE_CONTEXT) -> ResourceType {
    match ctx {
        COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT => ResourceType::SubDocument,
        COREWEBVIEW2_WEB_RESOURCE_CONTEXT_STYLESHEET => ResourceType::Stylesheet,
        COREWEBVIEW2_WEB_RESOURCE_CONTEXT_IMAGE => ResourceType::Image,
        COREWEBVIEW2_WEB_RESOURCE_CONTEXT_MEDIA => ResourceType::Media,
        COREWEBVIEW2_WEB_RESOURCE_CONTEXT_FONT => ResourceType::Font,
        COREWEBVIEW2_WEB_RESOURCE_CONTEXT_SCRIPT => ResourceType::Script,
        COREWEBVIEW2_WEB_RESOURCE_CONTEXT_XML_HTTP_REQUEST | COREWEBVIEW2_WEB_RESOURCE_CONTEXT_FETCH => {
            ResourceType::Xhr
        }
        COREWEBVIEW2_WEB_RESOURCE_CONTEXT_WEBSOCKET => ResourceType::WebSocket,
        _ => ResourceType::Other,
    }
}

/// Sekmenin WebView2'sine istek filtresini kurar.
/// `page`: ana çerçevenin adresi (üçüncü taraf kuralları için) — burada güncellenir.
pub fn install_blocker(
    wv: &WebView,
    page: Arc<Mutex<String>>,
    blocked: Arc<AtomicU32>,
    on_block: impl Fn() + 'static,
) -> windows::core::Result<()> {
    let core = wv.webview();
    let env = wv.environment();
    unsafe {
        let filter = HSTRING::from("*");
        match core.cast::<ICoreWebView2_22>() {
            // iframe ve worker istekleri de dahil
            Ok(c22) => c22.AddWebResourceRequestedFilterWithRequestSourceKinds(
                &filter,
                COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL,
                COREWEBVIEW2_WEB_RESOURCE_REQUEST_SOURCE_KINDS_ALL,
            )?,
            Err(_) => core.AddWebResourceRequestedFilter(&filter, COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL)?,
        }

        let page_nav = page.clone();
        let mut token = Default::default();
        core.add_NavigationStarting(
            &NavigationStartingEventHandler::create(Box::new(move |_, args| {
                if let Some(args) = args {
                    let mut uri = PWSTR::null();
                    args.Uri(&mut uri)?;
                    *page_nav.lock().unwrap_or_else(|e| e.into_inner()) = take_pwstr(uri);
                }
                Ok(())
            })),
            &mut token,
        )?;

        let mut token = Default::default();
        core.add_WebResourceRequested(
            &WebResourceRequestedEventHandler::create(Box::new(move |_, args| {
                let Some(args) = args else { return Ok(()) };
                if !crate::engine::enabled() {
                    return Ok(());
                }
                let request = args.Request()?;
                let mut uri = PWSTR::null();
                request.Uri(&mut uri)?;
                let uri = take_pwstr(uri);
                if !uri.starts_with("http") || uri.starts_with("http://zet.") {
                    return Ok(());
                }
                let mut ctx = COREWEBVIEW2_WEB_RESOURCE_CONTEXT::default();
                args.ResourceContext(&mut ctx)?;
                let top = page.lock().unwrap_or_else(|e| e.into_inner()).clone();
                // Ana sayfanın kendisi asla engellenmez.
                if ctx == COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT && (uri == top || top.is_empty()) {
                    return Ok(());
                }
                if crate::engine::should_block(&uri, &top, kind_of(ctx)) {
                    let resp = env.CreateWebResourceResponse(
                        None,
                        403,
                        &HSTRING::from("Blocked"),
                        &HSTRING::new(),
                    )?;
                    args.SetResponse(&resp)?;
                    blocked.fetch_add(1, Ordering::Relaxed);
                    crate::engine::TOTAL_BLOCKED.fetch_add(1, Ordering::Relaxed);
                    on_block();
                }
                Ok(())
            })),
            &mut token,
        )?;
    }
    Ok(())
}
