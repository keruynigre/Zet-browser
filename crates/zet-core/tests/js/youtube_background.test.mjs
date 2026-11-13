// node crates/zet-core/tests/js/youtube_background.test.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const src = readFileSync(new URL('../../src/scripts/youtube_background.js', import.meta.url), 'utf8');

const listeners = { window: {}, document: {} };
const add = (bucket) => (type, fn, capture) => { (bucket[type] ||= []).push({ fn, capture }); };
const reports = [];
const video = { tagName: 'VIDEO', paused: true, ended: false, play() { this.paused = false; }, pause() { this.paused = true; } };
class Document {}
Object.defineProperty(Document.prototype, 'hidden', { configurable: true, get: () => true });
Object.defineProperty(Document.prototype, 'visibilityState', { configurable: true, get: () => 'hidden' });
const document = Object.create(Document.prototype);
Object.assign(document, {
  title: 'Şarkı - YouTube',
  addEventListener: add(listeners.document),
  querySelector: (s) => (s === 'video' ? video : null),
  hasFocus: () => false,
});
const ctx = { document, Document, Object, String, ZetMedia: { onState: (p, t) => reports.push([p, t]) } };
ctx.window = ctx;
ctx.addEventListener = add(listeners.window);
vm.createContext(ctx);
vm.runInContext(src, ctx);

assert.equal(document.hidden, false);
assert.equal(document.visibilityState, 'visible');
assert.equal(document.hasFocus(), true);

// visibilitychange yakalama aşamasında durdurulur
let stopped = 0;
const ev = { type: 'visibilitychange', target: document, stopImmediatePropagation: () => stopped++ };
listeners.document.visibilitychange[0].fn(ev);
assert.equal(stopped, 1);
assert.ok(listeners.window.visibilitychange[0].capture);
// form alanındaki blur engellenmez
const blur = { type: 'blur', target: {}, stopImmediatePropagation: () => stopped++ };
listeners.window.blur[0].fn(blur);
assert.equal(stopped, 1);

// çalma durumu bildirilir
video.paused = false;
listeners.document.play[0].fn({ target: video });
assert.deepEqual(reports.at(-1), [true, 'Şarkı']);
video.paused = true;
listeners.document.pause[0].fn({ target: video });
assert.deepEqual(reports.at(-1), [false, 'Şarkı']);

// komutlar
ctx.__zetMedia('toggle');
assert.equal(video.paused, false);
ctx.__zetMedia('pause');
assert.equal(video.paused, true);
ctx.__zetMedia('play');
assert.equal(video.paused, false);

console.log('youtube_background.js: tüm testler geçti');
