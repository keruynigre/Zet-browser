// Zet masaüstü: her sayfaya belge başında enjekte edilir.
// - Adres/başlık değişimini (SPA dahil) kabuğa bildirir
// - Sekme kısayollarını (Ctrl+T/W/L/Tab...) kabuğa iletir
// - Reklam gizleme: site CSS'i + sonradan eklenen öğeler için genel kurallar
(function () {
  if (window.__zet) return;
  window.__zet = 1;
  if (window !== window.top) return; // iframe'lerde kabuğa mesaj gönderilemez
  function post(o) {
    try { window.ipc && window.ipc.postMessage(JSON.stringify(o)); } catch (e) {}
  }
  var web = /^https?:/.test(location.href) && location.href.indexOf('http://zet.') !== 0;

  // ---- Adres takibi ----
  function nav() { post({ t: 'nav', url: location.href, title: document.title }); }
  ['pushState', 'replaceState'].forEach(function (k) {
    var orig = history[k];
    history[k] = function () { var r = orig.apply(this, arguments); setTimeout(nav, 0); return r; };
  });
  addEventListener('popstate', nav);
  addEventListener('hashchange', nav);

  // ---- Kısayollar ----
  addEventListener('keydown', function (e) {
    var k = '', c = e.ctrlKey || e.metaKey, key = (e.key || '').toLowerCase();
    if (c && e.shiftKey && key === 'n') k = 'private';
    else if (c && e.shiftKey && key === 't') k = 'reopen';
    else if (c && key === 't') k = 'newtab';
    else if (c && key === 'w') k = 'close';
    else if ((c && key === 'l') || e.key === 'F6' || (e.altKey && key === 'd')) k = 'focus';
    else if (c && e.key === 'Tab') k = e.shiftKey ? 'prev' : 'next';
    else if (c && key >= '1' && key <= '9') k = 'tab' + key;
    if (k) { e.preventDefault(); e.stopPropagation(); post({ t: 'key', k: k }); }
  }, true);

  if (!web) return;

  // ---- Reklam gizleme ----
  var st = document.createElement('style');
  st.id = 'zet-cos';
  var base = '', gen = '';
  function render() {
    st.textContent = base + gen;
    if (!st.parentNode) { var r = document.head || document.documentElement; if (r) r.appendChild(st); }
  }
  window.__zetCos = function (kind, css) {
    if (kind === 'base') base = css; else gen += css;
    render();
  };
  post({ t: 'cos', url: location.href });

  var sc = {}, si = {}, pc = [], pi = [], timer = 0;
  function col(e) {
    if (!e || e.nodeType !== 1) return;
    if (e.id && !si[e.id]) { si[e.id] = 1; pi.push(e.id); }
    var cl = e.classList;
    if (cl) for (var j = 0; j < cl.length; j++) { var c = cl[j]; if (!sc[c]) { sc[c] = 1; pc.push(c); } }
  }
  function scan(n) {
    if (!n || n.nodeType !== 1) return;
    col(n);
    var all = n.querySelectorAll('[class],[id]');
    for (var i = 0; i < all.length; i++) col(all[i]);
  }
  function flush() {
    timer = 0;
    if (!pc.length && !pi.length) return;
    post({ t: 'gen', url: location.href, c: pc, i: pi });
    pc = []; pi = [];
  }
  function sched() { if (!timer) timer = setTimeout(flush, 150); }
  new MutationObserver(function (ms) {
    for (var k = 0; k < ms.length; k++) {
      var m = ms[k];
      if (m.type === 'attributes') col(m.target);
      else for (var a = 0; a < m.addedNodes.length; a++) scan(m.addedNodes[a]);
    }
    if (pc.length || pi.length) sched();
  }).observe(document.documentElement || document, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'],
  });
  function full() { scan(document.documentElement); render(); sched(); nav(); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', full);
  else full();
})();
