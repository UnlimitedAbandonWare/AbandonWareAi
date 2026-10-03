const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const studio = require('../main/resources/static/assets/interview/debug-studio.js');

const assetDir = path.resolve(__dirname, '../main/resources/static/assets/interview');
const jsFile = path.join(assetDir, 'debug-studio.js');
const cssFile = path.join(assetDir, 'debug-studio.css');

function email() { return ['user', '@', 'example.com'].join(''); }
function hex() { return 'ab'.repeat(20); }
function shaped() { return ['s', 'k-'].join('') + 'c'.repeat(24); }

function response(status, jsonText, headerId) {
  return {
    ok: status >= 200 && status < 300,
    status: status,
    headers: { get: function (name) { return String(name).toLowerCase() === 'x-request-id' ? (headerId || null) : null; } },
    clone: function () { return { text: async function () { return jsonText; }, json: async function () { return JSON.parse(jsonText); } }; },
    json: async function () { return JSON.parse(jsonText); },
    text: async function () { return jsonText; }
  };
}

function flush() { return new Promise(function (resolve) { setTimeout(resolve, 0); }); }

test('asset names match the interview static allowlist', function () {
  for (const name of ['debug-studio.js', 'debug-studio.css']) {
    assert.match('/assets/interview/' + name, studio.assetNamePattern);
    assert.equal(fs.existsSync(path.join(assetDir, name)), true);
  }
  assert.equal(studio.assetNamePattern.test('/assets/interview/Debug_Studio.js'), false);
  assert.equal(studio.assetNamePattern.test('/assets/interview/debug-studio.js'), true);
});

test('masking removes email, shaped secrets, and long hex', function () {
  const rawEmail = email();
  const rawHex = hex();
  const rawShaped = shaped();
  const labeled = ['to', 'ken'].join('') + '=abc123456789';
  const masked = [rawEmail, rawHex, rawShaped, labeled].map(studio.maskText).join('\n');
  assert.equal(masked.includes(rawEmail), false);
  assert.equal(masked.includes(rawHex), false);
  assert.equal(masked.includes(rawShaped), false);
  assert.equal(masked.includes('abc123456789'), false);
  assert.match(masked, /\[email\]/);
  assert.match(masked, /\[hex\]/);
  assert.match(masked, /\[redacted\]/);
});

test('overlay source does not mention persistent browser storage', function () {
  const source = fs.readFileSync(jsFile, 'utf8') + fs.readFileSync(cssFile, 'utf8');
  assert.equal(source.includes('localStorage'), false);
});

test('fetch wrapper returns the original response and records status without query values', async function () {
  const rawEmail = email();
  const rawShaped = shaped();
  const queryValue = 'hidden-query-value';
  const answer = 'RAW_ANSWER_TEXT';
  const payload = JSON.stringify({ requestId: 'req-1', reasonCode: 'OK', message: rawEmail, answer: answer });
  const made = response(201, payload, 'req-header');
  let tick = 10;
  const win = { fetch: function () { return Promise.resolve(made); } };
  const store = studio.createStore();
  studio.installFetch(win, store, function () { tick += 30; return tick; });
  const body = JSON.stringify({ message: rawEmail, note: rawShaped, reasonCode: 'OK' });
  const out = await win.fetch('/api/chat/sync?q=' + queryValue + '&epoch=1', { method: 'POST', body: body });
  assert.equal(out, made);
  assert.equal((await out.json()).answer, answer);
  await flush();
  await flush();
  const dumped = JSON.stringify(store.rows);
  assert.equal(store.rows.length, 1);
  assert.equal(store.rows[0].status, 201);
  assert.equal(store.rows[0].path, '/api/chat/sync?q&epoch');
  assert.equal(store.rows[0].requestId, 'req-header');
  assert.equal(store.rows[0].reasonCode, 'OK');
  assert.equal(store.rows[0].ms, 30);
  assert.equal(dumped.includes(queryValue), false);
  assert.equal(dumped.includes(rawEmail), false);
  assert.equal(dumped.includes(rawShaped), false);
  assert.equal(dumped.includes(answer), false);
});

test('untracked asset fetches are not recorded and health is', async function () {
  const win = { fetch: function () { return Promise.resolve(response(200, '{"status":"UP"}', '')); } };
  const store = studio.createStore();
  studio.installFetch(win, store);
  await win.fetch('/assets/interview/app.js');
  await win.fetch('/actuator/health');
  await flush();
  assert.equal(store.rows.length, 1);
  assert.equal(store.rows[0].path, '/actuator/health');
  assert.equal(store.rows[0].status, 200);
});

test('network rows stop at 50 and drop the oldest', async function () {
  const win = { fetch: function () { return Promise.resolve(response(200, '{"reasonCode":"OK"}', '')); } };
  const store = studio.createStore();
  studio.installFetch(win, store);
  for (let i = 0; i < 55; i++) await win.fetch('/api/n' + i);
  await flush();
  assert.equal(store.rows.length, 50);
  assert.equal(store.rows[0].path, '/api/n5');
  assert.equal(store.rows[49].path, '/api/n54');
});

test('chat path warns and other paths do not', function () {
  assert.match(studio.chatCoverWarning('/chat'), /면접 플래그 ON/);
  assert.equal(studio.chatCoverWarning('/chat/'), studio.chatCoverWarning('/chat'));
  assert.equal(studio.chatCoverWarning('/chat-ui'), '');
  assert.equal(studio.chatCoverWarning('/assets/interview/index.html'), '');
  assert.equal(studio.chatCoverWarning('/debug/studio'), '');
});

test('curl copy keeps method and masked body only', function () {
  const curl = studio.toCurl({ method: 'POST', path: '/api/chat/sync?q', bodyPreview: '[redacted]' }, 'http://127.0.0.1:18180');
  assert.match(curl, /curl -X POST/);
  assert.match(curl, /\/api\/chat\/sync\?q/);
  assert.equal(curl.includes('-H'), false);
  assert.equal(/cookie/i.test(curl), false);
  assert.equal(/authorization/i.test(curl), false);
  assert.equal(curl.includes('[redacted]'), true);
});

test('stage clock records the six pipeline gaps', function () {
  const clock = studio.createStageClock();
  studio.noteDebugLine(clock, 't {"stage":"input.submit"}', 1000);
  studio.noteDebugLine(clock, 't {"stage":"rag.pending"}', 1100);
  studio.noteDebugLine(clock, 't {"stage":"rag.result","requestId":"req-1"}', 1500);
  studio.noteDebugLine(clock, 't {"stage":"display.summary"}', 1600);
  studio.noteDebugLine(clock, 't {"stage":"display.accepted"}', 1700);
  studio.noteDebugLine(clock, 't {"stage":"display.status","reason":"RECEIVER_ACK"}', 1900);
  assert.equal(clock.dur.input, 0);
  assert.equal(clock.dur.rag, 100);
  assert.equal(clock.dur.answer, 400);
  assert.equal(clock.dur.summary, 100);
  assert.equal(clock.dur.display, 100);
  assert.equal(clock.dur.ack, 200);
});

test('event source wrapper keeps close behavior and drops query values', function () {
  class Source {
    constructor(url) { this.url = url; this.listeners = {}; this.closed = false; }
    addEventListener(type, fn) { (this.listeners[type] || (this.listeners[type] = [])).push(fn); }
    removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] || []).filter(function (item) { return item !== fn; }); }
    close() { this.closed = true; }
    emit(type) { (this.listeners[type] || []).forEach(function (fn) { fn({ type: type }); }); }
  }
  const win = { EventSource: Source };
  const store = studio.createStore();
  studio.installEventSource(win, store);
  const es = new win.EventSource('/api/assist/sessions/abc/output?epoch=hidden-epoch');
  let heard = 0;
  es.addEventListener('assist', function () { heard += 1; });
  es.emit('assist');
  es.close();
  assert.equal(es.closed, true);
  assert.equal(heard, 1);
  assert.equal(store.rows[0].path, '/api/assist/sessions/abc/output?epoch');
  assert.equal(store.rows[0].events >= 1, true);
  assert.equal(store.rows[0].closed, true);
  assert.equal(JSON.stringify(store.rows).includes('hidden-epoch'), false);
});
