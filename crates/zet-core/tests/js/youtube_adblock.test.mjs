// node crates/zet-core/tests/js/youtube_adblock.test.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const src = readFileSync(new URL('../../src/scripts/youtube_adblock.js', import.meta.url), 'utf8');

function makeEnv() {
  const intervals = [];
  const classes = new Set();
  const video = { muted: false, duration: 30, currentTime: 0, paused: false, play() {} };
  let skipClicked = 0;
  const player = {
    classList: { contains: (c) => classes.has(c) },
    querySelector: (s) => (s === 'video' ? video : null),
  };
  const styles = [];
  const document = {
    head: { appendChild: (el) => styles.push(el) },
    documentElement: {},
    getElementById: (id) => styles.find((s) => s.id === id) || null,
    createElement: () => ({}),
    addEventListener() {},
    querySelector: (s) => {
      if (s === '.html5-video-player') return player;
      if (s.includes('skip') && classes.has('ad-showing')) return { click: () => skipClicked++ };
      return null;
    },
  };
  const playerJson = JSON.stringify({
    videoDetails: { videoId: 'x' },
    adPlacements: [{ a: 1 }], playerAds: [1], adSlots: [2],
    streamingData: { formats: [] },
  });
  class FakeResponse {
    constructor(body, init = {}) { this.body = body; this.ok = (init.status ?? 200) < 400; this.status = init.status ?? 200; this.statusText = ''; this.headers = init.headers || {}; }
    clone() { return new FakeResponse(this.body, { status: this.status }); }
    text() { return Promise.resolve(this.body); }
    json() { return Promise.resolve(JSON.parse(this.body)); }
  }
  class FakeXHR { open() {} }
  Object.defineProperty(FakeXHR.prototype, 'responseText', { configurable: true, get() { return playerJson; } });
  // Tarayıcıdaki gibi window === global nesne.
  const ctx = {
    document, JSON, Object, Array, Response: FakeResponse, isFinite, String,
    setInterval: (fn) => intervals.push(fn),
    fetch: () => Promise.resolve(new FakeResponse(playerJson)),
    XMLHttpRequest: FakeXHR,
  };
  ctx.window = ctx;
  const window = ctx;
  vm.createContext(ctx);
  vm.runInContext(src, ctx);
  return { ctx, window, classes, video, intervals, styles, playerJson, get skipClicked() { return skipClicked; } };
}

const noAds = (o) => !('adPlacements' in o) && !('playerAds' in o) && !('adSlots' in o);

// 1a: gömülü ilk yanıt
{
  const env = makeEnv();
  vm.runInContext('var ytInitialPlayerResponse = {adPlacements:[1], playerAds:[2], videoDetails:{}};', env.ctx);
  const v = vm.runInContext('ytInitialPlayerResponse', env.ctx);
  assert.ok(v && noAds(v) && v.videoDetails, 'ytInitialPlayerResponse budanmalı');
}
// 1b: JSON.parse
{
  const env = makeEnv();
  const o = env.ctx.JSON.parse(env.playerJson);
  assert.ok(noAds(o) && o.streamingData, 'JSON.parse budanmalı');
  const plain = env.ctx.JSON.parse('{"a":1}');
  assert.equal(plain.a, 1);
}
// 1c: fetch
{
  const env = makeEnv();
  const r = await env.window.fetch('https://m.youtube.com/youtubei/v1/player?key=1');
  const o = JSON.parse(await r.text());
  assert.ok(noAds(o) && o.videoDetails, 'fetch yanıtı budanmalı');
  const other = await env.window.fetch('https://m.youtube.com/other');
  assert.ok((await other.text()).includes('adPlacements'), 'diğer istekler değişmemeli');
}
// next uç noktasında gömülü playerResponse
{
  const env = makeEnv();
  const o = env.ctx.JSON.parse(JSON.stringify({ playerResponse: { adPlacements: [1], x: 1 } }));
  assert.ok(noAds(o.playerResponse) && o.playerResponse.x === 1, 'gömülü playerResponse budanmalı');
}
// 1d: XHR
{
  const env = makeEnv();
  const x = new env.window.XMLHttpRequest();
  x.open('POST', '/youtubei/v1/player');
  assert.ok(noAds(JSON.parse(x.responseText)), 'XHR yanıtı budanmalı');
}
// Katman 2: reklam atlama
{
  const env = makeEnv();
  env.intervals.forEach((f) => f());
  assert.equal(env.video.currentTime, 0, 'reklam yokken dokunulmamalı');
  env.classes.add('ad-showing');
  env.intervals.forEach((f) => f());
  assert.equal(env.video.currentTime, 30);
  assert.equal(env.video.muted, true);
  assert.equal(env.skipClicked, 1);
}
// Katman 3: CSS
{
  const env = makeEnv();
  assert.ok(env.styles.length === 1 && env.styles[0].textContent.includes('ad-slot-renderer'));
}
// Çift enjeksiyon güvenli
{
  const env = makeEnv();
  vm.runInContext(src, env.ctx);
  assert.equal(env.styles.length, 1);
}
console.log('youtube_adblock.js: tüm testler geçti');
