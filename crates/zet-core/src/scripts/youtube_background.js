// Zet: YouTube'un ekran kapalıyken / arka planda çalmaya devam etmesi.
// YouTube sayfa gizlenince (visibilitychange) videoyu durdurur; sayfaya
// her zaman görünür olduğunu söyleriz. Çalma durumunu da uygulamaya bildiririz
// (kilit ekranı kontrolleri ve arka plan servisi için).
(function () {
  'use strict';
  if (window.__zetYtBackground) return;
  window.__zetYtBackground = true;

  function always(obj, prop, value) {
    try {
      Object.defineProperty(obj, prop, { configurable: true, get: function () { return value; } });
    } catch (e) { /* yoksay */ }
  }
  var docProto = (window.Document && window.Document.prototype) || document;
  always(docProto, 'hidden', false);
  always(docProto, 'webkitHidden', false);
  always(docProto, 'visibilityState', 'visible');
  always(docProto, 'webkitVisibilityState', 'visible');
  always(document, 'hidden', false);
  always(document, 'visibilityState', 'visible');
  if (document.hasFocus) document.hasFocus = function () { return true; };

  var BLOCKED = ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'blur'];
  function stop(e) {
    // blur yalnızca pencere seviyesinde engellenir (form alanları etkilenmesin).
    if (e.type === 'blur' && e.target !== window) return;
    e.stopImmediatePropagation();
  }
  for (var i = 0; i < BLOCKED.length; i++) {
    window.addEventListener(BLOCKED[i], stop, true);
    document.addEventListener(BLOCKED[i], stop, true);
  }

  // Çalma durumunu Android tarafına bildir.
  function report(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO' || !window.ZetMedia) return;
    var player = document.querySelector('.html5-video-player');
    if (player && player.classList.contains('ad-showing')) return;
    try {
      window.ZetMedia.onState(!v.paused && !v.ended, String(document.title || '').replace(/ - YouTube$/, ''));
    } catch (err) { /* yoksay */ }
  }
  ['play', 'playing', 'pause', 'ended'].forEach(function (t) {
    document.addEventListener(t, report, true);
  });

  // Uygulamadan gelen komutlar (kilit ekranı düğmeleri).
  window.__zetMedia = function (action) {
    var v = document.querySelector('video');
    if (!v) return;
    if (action === 'play' || (action === 'toggle' && v.paused)) v.play();
    else v.pause();
  };
})();
