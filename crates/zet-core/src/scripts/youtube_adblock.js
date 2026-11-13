// Zet: YouTube reklam engelleyici. Sayfa betiklerinden ÖNCE (document-start) çalışır.
//
// Katman 1: Oynatıcı yanıtlarından reklam alanlarını sil (reklam hiç başlamaz).
// Katman 2: Yine de reklam oynarsa sessize al, sona sar, "Atla"ya bas.
// Katman 3: Sponsorlu kartları / banner'ları CSS ile gizle.
(function () {
  'use strict';
  if (window.__zetYtAdblock) return;
  window.__zetYtAdblock = true;

  // ---- Katman 1: budama -------------------------------------------------
  var AD_KEYS = [
    'adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams',
    'adBreakParams', 'adParams', 'adInferredBlockingStatus',
  ];

  function prune(obj, depth) {
    if (!obj || typeof obj !== 'object' || depth > 6) return obj;
    for (var i = 0; i < AD_KEYS.length; i++) {
      if (AD_KEYS[i] in obj) {
        try { delete obj[AD_KEYS[i]]; } catch (e) { obj[AD_KEYS[i]] = undefined; }
      }
    }
    // Yanıt içinde gömülü player yanıtları (next/reel uç noktaları).
    var nested = ['playerResponse', 'response', 'playerConfig'];
    for (var j = 0; j < nested.length; j++) {
      if (obj[nested[j]] && typeof obj[nested[j]] === 'object') prune(obj[nested[j]], depth + 1);
    }
    if (Array.isArray(obj)) {
      for (var k = 0; k < obj.length; k++) prune(obj[k], depth + 1);
    }
    return obj;
  }
  window.__zetPrune = prune;

  function hasAds(text) {
    return typeof text === 'string' &&
      (text.indexOf('"adPlacements"') !== -1 || text.indexOf('"playerAds"') !== -1 ||
       text.indexOf('"adSlots"') !== -1);
  }

  // 1a) Sayfa içine gömülü ilk yanıt: var ytInitialPlayerResponse = {...}
  function guardGlobal(name) {
    var value;
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        get: function () { return value; },
        set: function (v) { value = prune(v, 0); },
      });
    } catch (e) { /* zaten tanımlı */ }
  }
  guardGlobal('ytInitialPlayerResponse');
  guardGlobal('ytInitialData');

  // 1b) JSON.parse ile çözülen yanıtlar.
  var origParse = JSON.parse;
  JSON.parse = function (text, reviver) {
    var out = origParse.call(this, text, reviver);
    if (hasAds(text)) prune(out, 0);
    return out;
  };

  // 1c) fetch ile gelen /youtubei/v1/player, /next, /reel yanıtları.
  var API = /\/youtubei\/v1\/(player|next|reel)/;
  if (window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var p = origFetch.apply(this, arguments);
      if (!API.test(url)) return p;
      return p.then(function (resp) {
        if (!resp || !resp.ok) return resp;
        return resp.clone().text().then(function (text) {
          if (!hasAds(text)) return resp;
          var body = JSON.stringify(prune(origParse(text), 0));
          return new Response(body, {
            status: resp.status, statusText: resp.statusText, headers: resp.headers,
          });
        }).catch(function () { return resp; });
      });
    };
  }

  // 1d) XMLHttpRequest ile gelen yanıtlar.
  if (window.XMLHttpRequest) {
    var XHR = window.XMLHttpRequest.prototype;
    var origOpen = XHR.open;
    XHR.open = function (method, url) {
      this.__zetUrl = String(url || '');
      return origOpen.apply(this, arguments);
    };
    var desc = Object.getOwnPropertyDescriptor(XHR, 'responseText');
    if (desc && desc.get) {
      Object.defineProperty(XHR, 'responseText', {
        configurable: true,
        get: function () {
          var text = desc.get.call(this);
          if (this.__zetUrl && API.test(this.__zetUrl) && hasAds(text)) {
            try { return JSON.stringify(prune(origParse(text), 0)); } catch (e) { return text; }
          }
          return text;
        },
      });
    }
  }

  // ---- Katman 2: oynayan reklamı atla -----------------------------------
  var SKIP = '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
    '.ytp-ad-skip-button-container button, button[class*="skip-ad"]';
  function skipAd() {
    var player = document.querySelector('.html5-video-player');
    if (!player || !player.classList.contains('ad-showing')) return;
    var video = player.querySelector('video');
    if (video) {
      video.muted = true;
      if (isFinite(video.duration) && video.duration > 0) {
        video.currentTime = video.duration;
      }
      if (video.paused) { try { video.play(); } catch (e) {} }
    }
    var btn = document.querySelector(SKIP);
    if (btn) btn.click();
  }
  setInterval(skipAd, 300);

  // ---- Katman 3: sponsorlu öğeleri gizle --------------------------------
  var CSS = [
    'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-video-renderer',
    'ytm-companion-ad-renderer', 'ytm-companion-slot', 'ad-slot-renderer',
    'ytm-ad-slot-renderer', 'ytd-ad-slot-renderer', 'ytd-promoted-sparkles-web-renderer',
    'ytd-in-feed-ad-layout-renderer', 'ytd-banner-promo-renderer', 'ytm-banner-promo-renderer',
    'ytd-player-legacy-desktop-watch-ads-renderer', 'ytm-statement-banner-renderer',
    '.ytp-ad-overlay-container', '.ytp-ad-module', '#player-ads', '#masthead-ad',
    'ytm-rich-item-renderer:has(ad-slot-renderer)', 'ytd-rich-item-renderer:has(ytd-ad-slot-renderer)',
  ].join(',') + '{display:none!important}';
  function addStyle() {
    if (document.getElementById('zet-yt-css')) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var s = document.createElement('style');
    s.id = 'zet-yt-css';
    s.textContent = CSS;
    root.appendChild(s);
  }
  addStyle();
  document.addEventListener('DOMContentLoaded', addStyle);
})();
