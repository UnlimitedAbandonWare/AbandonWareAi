// Synthetic transport fixtures; these never call a provider or prove hardware support.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const root = path.resolve(__dirname, '..');
const corePath = path.join(root, 'main/resources/static/assets/display/display-core.js');
const exists = fs.existsSync(corePath);
test('Display client implementation exists', () => assert.ok(exists, 'Display client is not implemented'));
if (!exists) return;
const { projectResponse, safeSourceUrl, createClient, isEditingKey, paginate } = require(corePath);
const response = (body, status = 200, headers = {}) => ({ ok: status >= 200 && status < 300, status,
  headers: { get: name => headers[name] || null }, json: async () => body });
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const success = { content: 'A complete answer.', sessionId: 21, evidence: [{ title: 'Official source', marker: '1', source: 'https://example.com/docs', filePath: '/private/file' }], learningContext: { private: true } };

test('actual DTO projects only content, numeric session and public evidence', () => {
  const p = projectResponse(success);
  assert.equal(p.answer, success.content); assert.equal(p.sessionId, 21);
  assert.deepEqual(p.sources, [{ title: 'Official source', marker: '1', url: 'https://example.com/docs' }]);
  assert.ok(!JSON.stringify(p).includes('private'));
});
test('empty evidence stays empty regardless of ragUsed', () => assert.deepEqual(projectResponse({ content: 'text', ragUsed: true }).sources, []));
test('fallback model marker remains visible as status without exposing model metadata', () => {
  assert.equal(projectResponse({content:'safe fallback',modelUsed:'gemma4:26b:fallback:local-lite'}).fallback,true);
  assert.equal(projectResponse({content:'answer',modelUsed:'gemma4:26b'}).fallback,false);
  assert.equal(projectResponse({content:'unknown'}).fallback,false);
});
test('wrong DTO and unsafe session identity fail closed', () => {
  assert.throws(() => projectResponse({ answer: 'wrong contract' }));
  assert.equal(projectResponse({ content: 'ok', sessionId: '21' }).sessionId, null);
  assert.equal(projectResponse({ content: 'ok', sessionId: 9007199254740992 }).sessionId, null);
});
test('markup remains plain text for safe DOM rendering', () => {
  const p = projectResponse({ content: '<img src=x onerror=alert(1)>', evidence: [{ title: '<b>title</b>', source: 'javascript:alert(1)' }] });
  assert.equal(p.answer, '<img src=x onerror=alert(1)>'); assert.equal(p.sources[0].url, null);
});
for (const url of ['javascript:alert(1)', '/local', 'https://user:pass@example.com', 'http://localhost', 'http://foo.localhost', 'http://host.local', 'http://host.internal', 'http://intranet', 'http://127.1', 'http://2130706433', 'http://0x7f000001', 'http://10.1.2.3', 'http://172.20.0.1', 'http://192.168.1.1', 'http://169.254.1.1', 'http://0.0.0.0', 'http://[::1]', 'http://[::ffff:127.0.0.1]', 'http://[fc00::1]', 'http://example.com./', 'https://example.com\\@127.0.0.1']) {
  test('ineligible citation URL is non-clickable: ' + url, () => assert.equal(safeSourceUrl(url), null));
}
test('public absolute HTTPS URL remains eligible', () => assert.equal(safeSourceUrl('https://example.com/docs#a'), 'https://example.com/docs#a'));
test('paging preserves the entire answer including unicode and whitespace', () => {
  const text = '가나다 😀\n  '.repeat(80); const pages = paginate(text, 108);
  assert.equal(pages.join(''), text); assert.ok(pages.length > 1);
  assert.ok(pages.every(p => Array.from(p).length <= 108));
});
test('default server budget leaves response time inside the client deadline', async () => {
  let sent, clientDeadline;
  const c = createClient({
    fetchImpl: async (url, options) => { sent = options; return response(success); },
    setTimer: (fn, ms) => { clientDeadline = ms; return 1; }, clearTimer: () => {}
  });
  c.setMessage('synthetic budget fixture'); await c.submit();
  assert.equal(sent.headers['X-Budget-Ms'], '80000');
  assert.equal(clientDeadline, 90000);
  assert.equal(clientDeadline - Number(sent.headers['X-Budget-Ms']), 10000);
});

test('Display pages bound explicit lines and conservative Korean wrapping without dropping text', () => {
  for (const text of [('short line\n').repeat(12), '가나다라'.repeat(90), ('안내\r\n\t😀 다음\n\n').repeat(20)]) {
    const pages = paginate(text);
    assert.equal(pages.join(''), text);
    for (const page of pages) {
      let rows = 1, columns = 0;
      for (const char of Array.from(page)) {
        if (char === '\r' || char === '\n') { rows++; columns = 0; }
        else {
          const width = char === '\t' ? 8 : 1;
          if (columns + width > 18) { rows++; columns = 0; }
          columns += width;
        }
      }
      assert.ok(rows <= 5);
      assert.ok(Array.from(page).length <= 108);
    }
  }
});

test('one flight, cookie options, original request and session continuation', async () => {
  const first = deferred(); const calls = [];
  const c = createClient({ fetchImpl: (url, options) => { calls.push({ url, options }); return calls.length === 1 ? first.promise : Promise.resolve(response(success)); } });
  c.setMessage('  original wording  '); const pending = c.submit(); c.submit();
  assert.equal(calls.length, 1); assert.equal(c.state.phase, 'LOADING');
  assert.equal(c.state.metrics.requestId,calls[0].options.headers['X-Request-Id']);
  assert.equal(calls[0].url, '/api/chat/sync'); assert.equal(calls[0].options.credentials, 'same-origin');
  assert.deepEqual(JSON.parse(calls[0].options.body), { message: '  original wording  ', sessionId: null, inputType: 'text' });
  assert.match(calls[0].options.headers['Idempotency-Key'], /^[a-f0-9-]{36}$/i);
  first.resolve(response(success, 200, { 'X-Request-Id': 'request-fixture' })); await pending;
  assert.equal(c.state.phase, 'RESULT'); assert.equal(c.state.metrics.requestId, 'request-fixture');
  await c.submit(); assert.equal(calls.length, 1);
  c.setMessage('A different question'); await c.submit();
  assert.equal(JSON.parse(calls[1].options.body).sessionId, 21);
  assert.notEqual(calls[0].options.headers['Idempotency-Key'], calls[1].options.headers['Idempotency-Key']);
});
for (const [status, code] of [[400, 'invalid-input'], [403, 'session-forbidden'], [409, 'request-conflict'], [413, 'input-too-large'], [422, 'request-mismatch'], [429, 'rate-limited'], [503, 'admission-unavailable']]) {
  test('HTTP ' + status + ' is classified without raw server text or retry', async () => {
    let count = 0; const c = createClient({ fetchImpl: async () => { count++; return response({ content: 'PRIVATE_ERROR_BODY' }, status, { 'Retry-After': '12' }); } });
    c.setMessage('fixture'); await c.submit(); await c.submit();
    assert.equal(c.state.error.code, code); assert.equal(count, 1);
    assert.ok(!JSON.stringify(c.state).includes('PRIVATE_ERROR_BODY'));
    if (status === 429) assert.equal(c.state.error.retryAfterSeconds, 12);
  });
}
test('timeout then Enter never resends; late result never replaces newer state', async () => {
  const late = deferred(); let expire; let calls = 0;
  const c = createClient({ fetchImpl: async () => { calls++; return late.promise; }, setTimer: fn => { expire = fn; return 1; }, clearTimer: () => {} });
  c.setMessage('fixture'); const pending = c.submit(); expire(); await pending;
  assert.equal(c.state.error.code, 'outcome-unknown'); await c.submit(); assert.equal(calls, 1);
  c.newConversation(); late.resolve(response(success)); await new Promise(setImmediate);
  assert.equal(c.state.phase, 'IDLE'); assert.equal(c.state.sessionId, null);
});
test('cancel fences submission and ignores a late reply', async () => {
  const late = deferred(); let calls = 0;
  const c = createClient({ fetchImpl: () => { calls++; return late.promise; } });
  c.setMessage('fixture'); const pending = c.submit(); c.cancel(); await pending; await c.submit();
  assert.equal(calls, 1); assert.equal(c.state.error.code, 'outcome-unknown');
  late.resolve(response(success)); await new Promise(setImmediate); assert.equal(c.state.phase, 'ERROR');
});
test('network and non-JSON success failures are bounded and never retried', async () => {
  for (const fetchImpl of [async () => { throw Error('PRIVATE'); }, async () => ({ ok: true, status: 200, headers: { get: () => null }, json: async () => { throw Error('PRIVATE'); } })]) {
    const c = createClient({ fetchImpl }); c.setMessage('fixture'); await c.submit();
    assert.equal(c.state.phase, 'ERROR'); assert.ok(!JSON.stringify(c.state).includes('PRIVATE'));
  }
});
test('input and IME keyboard handling cannot move cards or submit', () => {
  for (const event of [{ isComposing: true }, { keyCode: 229 }, { target: { tagName: 'TEXTAREA' } }, { target: { tagName: 'INPUT' } }, { target: { isContentEditable: true } }]) assert.equal(isEditingKey(event), true);
  assert.equal(isEditingKey({ target: { tagName: 'BUTTON' } }), false);
});
test('empty questions never call transport', async () => {
  let count = 0; const c = createClient({ fetchImpl: async () => { count++; } }); c.setMessage('  '); await c.submit(); assert.equal(count, 0);
});
test('Retry-After fences a different question and new conversation until the deadline', async () => {
  let clock = 0, count = 0;
  const c = createClient({ now: () => clock, setTimer: () => 1, clearTimer: () => {}, fetchImpl: async () => { count++; return response({}, 429, { 'Retry-After': '12' }); } });
  c.setMessage('first'); await c.submit(); c.setMessage('second'); await c.submit();
  assert.equal(count, 1); c.newConversation(); c.setMessage('third'); await c.submit(); assert.equal(count, 1);
  clock = 12001; await c.submit(); assert.equal(count, 2);
});

// The tests above cover the supported core exports, including its legacy sync
// client. Below, execute the current caption/voice app with synthetic controls.
function mountDisplayApp(initialState = {}) {
  const vm = require('node:vm');
  const nodes = new Map(), calls = [], frames = [], windowListeners = new Map();
  let clientOptions, captureOptions;
  const document = { body:{hasAttribute:()=>false}, activeElement: null, visibilityState: 'visible', hidden: false, listeners: new Map(),
    getElementById: id => nodes.get(id),
    querySelectorAll: () => [...nodes.values()].filter(node => node.focusable),
    addEventListener(type, handler) { this.listeners.set(type, handler); } };
  const html = fs.readFileSync(path.join(root, 'main/resources/static/assets/display/companion.html'), 'utf8');
  for (const match of html.matchAll(/<([a-z][a-z0-9]*)\b([^>]*\bid="([^"]+)"[^>]*)>/g)) {
    const [, tag, attributes, id] = match;
    nodes.set(id, { id, value: '', textContent: '', tagName: tag.toUpperCase(),
      hidden: /\bhidden\b/.test(attributes), disabled: /\bdisabled\b/.test(attributes),
      focusable: /class="[^"]*\bfocusable\b/.test(attributes), attributes: new Map(),
      classList: { toggle() {} }, focus() { document.activeElement = this; },
      setAttribute(name, value) { this.attributes.set(name, value); },
      getClientRects() { return this.hidden ? [] : [1]; },
      querySelectorAll: document.querySelectorAll,
      click() { return this.onclick?.({ preventDefault() {} }); },
      set innerHTML(value) { throw Error('App content must remain plain text'); } });
  }
  const state = { connection: 'READY', ready: true, role: 'DISPLAY', linked: false,
    linkPending: false, audioAvailable: true, hintsEnabled: false, epoch: 1,
    version: 1, caption: null, hint: null, ...initialState };
  function record(name, ...args) { calls.push({ name, args }); }
  const client = { state,
    start() { record('client.start'); clientOptions.onChange(state); },
    pause() { record('client.pause'); }, dispose() { record('client.dispose'); },
    reconnect() { record('client.reconnect'); },
    storedLensLink() { return null; },
    async lensLink() { record('lens.link'); throw Error('lens_unavailable'); },
    async acknowledge(...args) { record('caption.ack', ...args); },
    async stopAudio() { record('audio.stop'); },
    async contextReset() { record('context.reset'); },
    async lensSettings(...args) { record('lens.settings', ...args); },
    async background(...args) { record('context.background', ...args); },
    async relaySettings(...args) { record('relay.settings', ...args); },
    async pairingCode() { record('pair.code'); return { code: '123456', validForMs: 120000 }; },
    async join(...args) { record('pair.join', ...args); return { pending: true, confirmation: '1234' }; },
    async approve() { record('pair.approve'); },
    async unlink() { record('pair.unlink'); },
    async hints(enabled) { record('hints', enabled); update({ hintsEnabled: enabled }); } };
  const voice = { state: { phase: 'IDLE', frames: 0, bytes: 0, level: 0, reconnects: 0,
      permission: 'not_requested', message: 'Synthetic capture' },
    isActive() { return this.state.phase !== 'IDLE'; },
    async start() { record('voice.start'); this.state.phase = 'LISTENING'; captureOptions.onChange(this.state); },
    async resume() { record('voice.resume'); return true; },
    async stop(...args) { record('voice.stop', ...args); this.state.phase = 'IDLE'; captureOptions.onChange(this.state); },
    async finish() { record('voice.finish'); this.state.phase = 'IDLE'; captureOptions.onChange(this.state); } };
  const DisplayConversate = { createClient(options) { clientOptions = options; return client; } };
  const DisplayVoice = { createCapture(options) { captureOptions = options; return voice; } };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'main/resources/static/assets/display/app.js'), 'utf8'),
    { URLSearchParams,location:{search:''},window: { location:{search:''},DisplayCore: require(corePath), DisplayConversate, DisplayVoice,
        addEventListener(type, handler) { windowListeners.set(type, handler); } },
      document, navigator: {}, requestAnimationFrame: callback => frames.push(callback),
      setTimeout: () => 1, clearTimeout() {} });
  function update(patch) { Object.assign(state, patch); clientOptions.onChange(state); }
  function key(id, key) {
    const event = { target: nodes.get(id), key, defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; } };
    document.listeners.get('keydown')(event);
    return event;
  }
  return { nodes, document, client, voice, captureOptions, clientOptions, update, key,
    called: name => calls.filter(call => call.name === name),
    click: id => nodes.get(id).click(),
    windowEvent: (type, event = {}) => windowListeners.get(type)?.(event),
    async paint() { while (frames.length) frames.shift()(); await new Promise(setImmediate); } };
}

test('entry connects the caption view without starting microphone capture', () => {
  const app = mountDisplayApp();
  assert.equal(app.clientOptions.transcription, true);
  assert.equal(app.document.activeElement.id, 'caption-card');
  assert.equal(app.nodes.get('connection').textContent, '서버 연결됨');
  assert.equal(app.nodes.get('microphone').disabled, true);
  assert.equal(app.called('client.start').length, 1);
  assert.equal(app.called('voice.start').length, 0);
});

test('final captions retain every normalized character across keyboard pages', () => {
  const text = '공개 합성 발화 😀\n'.repeat(24), expected = paginate(text.replace(/\s+/g, ' '), 54);
  const app = mountDisplayApp({ caption: { text, utteranceId: 'fixture', revision: 1, isFinal: true } });
  assert.equal(app.nodes.get('caption-label').textContent, '확정 발화');
  assert.equal(app.nodes.get('caption-text').textContent, expected.at(-1));
  for (let i = 0; i < expected.length; i++) app.key('caption-card', 'ArrowLeft');
  const seen = [];
  for (let i = 0; i < expected.length; i++) {
    seen.push(app.nodes.get('caption-text').textContent);
    assert.equal(app.key('caption-card', 'ArrowRight').defaultPrevented, true);
  }
  assert.equal(seen.join(''), text.replace(/\s+/g, ' '));
  assert.equal(app.nodes.get('caption-page').textContent, expected.length + '/' + expected.length);
  assert.equal(app.key('pair-code', 'ArrowLeft').defaultPrevented, false);
});

test('hint visibility follows state without losing captions or interpreting markup', async () => {
  const app = mountDisplayApp({ caption: { text: '공개 질문', utteranceId: 'fixture', revision: 1, isFinal: true } });
  app.update({ hint: { text: '<img src=x onerror=alert(1)> 공개 힌트' } });
  assert.equal(app.nodes.get('hint-text').hidden, false);
  assert.equal(app.nodes.get('hint-text').textContent, '힌트 · <img src=x onerror=alert(1)> 공개 힌트');
  await app.click('hints');
  assert.equal(app.called('hints')[0].args[0], true);
  assert.equal(app.nodes.get('hints').attributes.get('aria-pressed'), 'true');
  app.update({ hint: null });
  assert.equal(app.nodes.get('hint-text').hidden, true);
  assert.equal(app.nodes.get('hint-text').textContent, '');
  assert.equal(app.nodes.get('caption-text').textContent, '공개 질문');
});

test('caption ACK occurs once per visible Display revision and never on the phone', async () => {
  const caption = { text: '합성 자막', utteranceId: 'fixture', revision: 1, isFinal: false };
  const app = mountDisplayApp({ caption, version: 4 });
  assert.equal(app.called('caption.ack').length, 0);
  app.update({}); await app.paint(); app.update({}); await app.paint();
  assert.deepEqual(app.called('caption.ack').map(call => call.args), [[4, 'caption_rendered']]);
  app.update({ caption: { ...caption, revision: 2, isFinal: true }, version: 5 }); await app.paint();
  assert.deepEqual(app.called('caption.ack').map(call => call.args), [[4, 'caption_rendered'], [5, 'caption_rendered']]);
  const phone = mountDisplayApp({ role: 'PHONE', caption }); await phone.paint();
  assert.equal(phone.called('caption.ack').length, 0);
});

test('a hidden or superseded caption cannot produce a stale render ACK', async () => {
  const caption = { text: '합성 자막', utteranceId: 'fixture', revision: 1, isFinal: false };
  const app = mountDisplayApp({ caption });
  app.document.visibilityState = 'hidden'; await app.paint();
  assert.equal(app.called('caption.ack').length, 0);
  app.document.visibilityState = 'visible'; app.update({});
  app.update({ caption: { ...caption, revision: 2, isFinal: true }, version: 2 }); await app.paint();
  assert.deepEqual(app.called('caption.ack').map(call => call.args), [[2, 'caption_rendered']]);
});

test('phone controls use continuous capture and finish returns to idle', async () => {
  const app = mountDisplayApp({ role: 'PHONE', linked: true });
  assert.equal(app.captureOptions.continuous, true);
  assert.equal(app.captureOptions.client, app.client);
  app.nodes.get('input-device').value = 'synthetic-device';
  assert.equal(app.captureOptions.deviceId(), 'synthetic-device');
  assert.equal(app.nodes.get('microphone').disabled, false);
  await app.click('microphone');
  assert.equal(app.called('voice.start').length, 1);
  assert.equal(app.nodes.get('microphone').attributes.get('aria-pressed'), 'true');
  assert.equal(app.nodes.get('finish').hidden, false);
  await app.click('finish');
  assert.equal(app.called('voice.finish').length, 1);
  assert.equal(app.voice.isActive(), false);
  assert.equal(app.nodes.get('finish').hidden, true);
});

test('stop uses the remote action on Display and local capture cleanup on phone disconnect', async () => {
  const display = mountDisplayApp({ linked: true }); await display.click('stop');
  assert.equal(display.called('audio.stop').length, 1);
  assert.equal(display.called('voice.start').length, 0);
  const phone = mountDisplayApp({ role: 'PHONE', linked: true }); await phone.click('microphone');
  phone.update({ ready: false, connection: 'DISCONNECTED', linked: false });
  assert.equal(phone.called('voice.stop').length, 1);
  assert.equal(phone.called('voice.stop')[0].args[1], true);
  assert.equal(phone.called('audio.stop').length, 0);
  assert.equal(phone.voice.isActive(), false);
});

test('diagnostics leave missing timing unmeasured and errors omit raw server bodies', () => {
  const app = mountDisplayApp();
  assert.equal(app.nodes.has('diagnostics'),false);
  app.update({ error: { code: 'pair_same_browser' } });
  assert.match(app.nodes.get('error').textContent, /서로 다른 기기/);
  app.update({ error: { message: 'PRIVATE_ERROR_BODY <html>' } });
  assert.ok(!app.nodes.get('error').textContent.includes('PRIVATE_ERROR_BODY'));
  assert.match(app.nodes.get('error').textContent, /연결 상태/);
});

test('page exit stops capture and disposes polling; bfcache restore revives only a frozen capture', async () => {
  const app = mountDisplayApp({ role: 'PHONE', linked: true }); await app.click('microphone');
  app.windowEvent('pagehide');
  assert.equal(app.voice.isActive(), false);
  assert.equal(app.called('voice.stop').length, 1);
  assert.equal(app.called('client.dispose').length, 1);
  app.windowEvent('pageshow', { persisted: false });
  assert.equal(app.called('client.start').length, 1);
  app.windowEvent('pageshow', { persisted: true });
  assert.equal(app.called('client.start').length, 2);
  assert.equal(app.called('voice.start').length, 2);
  const idle = mountDisplayApp({ role: 'PHONE', linked: true });
  idle.windowEvent('pagehide'); idle.windowEvent('pageshow', { persisted: true });
  assert.equal(idle.called('voice.start').length, 0);
});

test('an explicit user stop is never revived by pagehide or visibility return', async () => {
  const app = mountDisplayApp({ role: 'PHONE', linked: true }); await app.click('microphone');
  await app.click('stop'); assert.equal(app.called('voice.stop').length, 1);
  app.windowEvent('pagehide'); app.windowEvent('pageshow', { persisted: true });
  assert.equal(app.called('voice.start').length, 1);
  app.document.listeners.get('visibilitychange')?.();
});

test('returning to a visible page restarts polling and resumes a live capture', async () => {
  const app = mountDisplayApp({ role: 'PHONE', linked: true }); await app.click('microphone');
  const handler = app.document.listeners.get('visibilitychange');
  app.document.visibilityState = 'visible'; handler();
  assert.equal(app.called('voice.resume').length, 1);
  assert.equal(app.called('voice.stop').length, 0);
  assert.equal(app.called('client.start').length, 2);
});
