const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const html = fs.readFileSync(path.join(__dirname, '../main/resources/templates/debug-events.html'), 'utf8');
const script = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)]
  .map(match => match[1]).find(body => body.includes('async function fetchJson'));
assert.ok(script, 'diagnostics inline script must exist');

function eventPage(items, nextCursor = null) {
  return { items, nextCursor, hasMore: !!nextCursor, cursorStatus: 'initial' };
}

async function pageHarness(initialFetch, search = '') {
  const elements = new Map();
  const logs = [];
  const requests = [];
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      textContent: '', value: '', checked: false, innerHTML: '', children: [], style: {},
      listeners: {}, scrollHeight: 0, scrollTop: 0, clientHeight: 0,
      addEventListener(type, handler) { this.listeners[type] = handler; },
      appendChild(child) { this.children.push(child); return child; }
    });
    return elements.get(id);
  }
  class FakeEventSource {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 2;
    static instances = [];
    constructor(url) {
      this.url = url;
      this.readyState = FakeEventSource.CONNECTING;
      this.listeners = {};
      FakeEventSource.instances.push(this);
    }
    addEventListener(type, handler) { this.listeners[type] = handler; }
    close() { this.readyState = FakeEventSource.CLOSED; }
  }
  const context = vm.createContext({
    document: { getElementById: element, querySelector: () => null, createElement: () => element(Symbol()) },
    window: { location: { search } }, URLSearchParams, EventSource: FakeEventSource,
    console: { error: e => logs.push(String(e)), warn: e => logs.push(String(e)) },
    fetch: initialFetch || (async (url, options) => {
      requests.push({ url, options });
      return { ok: true, json: async () => url.includes('/events/page?') ? eventPage([]) : [] };
    })
  });
  vm.runInContext(script, context, { filename: 'debug-events.html' });
  // Let the real initial refresh sequence finish before arranging a scenario.
  await new Promise(resolve => setImmediate(resolve));
  return { context, element, logs, requests, FakeEventSource };
}

test('HTTP failures expose only status, without reading the response body or reflecting the URL', async () => {
  const h = await pageHarness();
  let bodyReads = 0;
  h.context.fetch = async () => ({ ok: false, status: 500, text: async () => {
    bodyReads++;
    return 'synthetic-private-error-body';
  } });
  await assert.rejects(h.context.fetchJson('/fixture?private=synthetic-query'), { message: 'http_500' });
  await h.context.refreshAll();
  assert.match(h.element('status').textContent, /http_500/);
  assert.equal(bodyReads, 0);
  assert.ok(h.logs.every(log => !/synthetic-private|synthetic-query/.test(log)));
});

test('malformed JSON does not reflect parser excerpts into the page or console', async () => {
  const h = await pageHarness();
  h.context.fetch = async () => ({ ok: true, json: async () => {
    throw new SyntaxError('synthetic-private-parser-excerpt');
  } });
  await h.context.refreshAll();
  assert.match(h.element('status').textContent, /invalid_json/);
  assert.ok(!h.element('status').textContent.includes('synthetic-private'));
  assert.ok(h.logs.every(log => !log.includes('synthetic-private')));
});

test('successful JSON retains caller options, credentials and CSRF headers', async () => {
  const h = await pageHarness();
  const expected = { decision: 'HOLD' };
  let received;
  h.context.fetch = async (url, options) => {
    received = { url, options };
    return { ok: true, json: async () => expected };
  };
  assert.equal(await h.context.fetchJson('/fixture', {
    method: 'POST', body: '{}', headers: { 'X-CSRF-TOKEN': 'synthetic-csrf' }
  }), expected);
  assert.equal(received.options.method, 'POST');
  assert.equal(received.options.body, '{}');
  assert.equal(received.options.credentials, 'same-origin');
  assert.equal(received.options.headers.Accept, 'application/json');
  assert.equal(received.options.headers['X-CSRF-TOKEN'], 'synthetic-csrf');
});

test('refresh cannot report a connection when the current stream is closed or reconnecting', async () => {
  const h = await pageHarness();
  h.element('live').checked = true;
  h.context.startLive();
  const source = h.FakeEventSource.instances[0];
  source.readyState = h.FakeEventSource.CLOSED;
  source.onerror();
  assert.equal(h.element('status').textContent, 'LIVE: disconnected');
  await h.element('btnRefresh').listeners.click();
  assert.equal(h.element('status').textContent, 'LIVE: disconnected');
  source.readyState = h.FakeEventSource.CONNECTING;
  source.onerror();
  await h.element('btnRefresh').listeners.click();
  assert.equal(h.element('status').textContent, 'LIVE: reconnecting...');
  source.readyState = h.FakeEventSource.OPEN;
  source.onopen();
  assert.equal(h.element('status').textContent, 'LIVE: connected');
});

test('callbacks from a retired stream cannot change status or append stale events', async () => {
  const h = await pageHarness();
  h.context.startLive();
  const retired = h.FakeEventSource.instances[0];
  h.context.startLive();
  const current = h.FakeEventSource.instances[1];
  assert.equal(retired.readyState, h.FakeEventSource.CLOSED);
  current.readyState = h.FakeEventSource.OPEN;
  current.onopen();
  retired.onerror();
  assert.equal(h.element('status').textContent, 'LIVE: connected');
  current.readyState = h.FakeEventSource.CONNECTING;
  current.onerror();
  retired.onopen();
  retired.listeners.hello({ data: '{}' });
  retired.listeners['debug-event']({ data: '{"id":"retired-fixture"}' });
  assert.equal(h.element('status').textContent, 'LIVE: reconnecting...');
  assert.equal(h.element('listJson').textContent, '[]');
  h.context.stopLive();
  h.context.setStatus('stopped-fixture');
  current.onopen();
  assert.equal(h.element('status').textContent, 'stopped-fixture');
});

test('a slow auxiliary refresh cannot overwrite a newer stream or non-live state', async () => {
  const h = await pageHarness();
  h.element('live').checked = true;
  h.context.startLive();
  let finish;
  h.context.fetch = () => new Promise(resolve => { finish = resolve; });
  const refreshing = h.element('btnRefresh').listeners.click();
  h.context.startLive();
  const current = h.FakeEventSource.instances[1];
  current.readyState = h.FakeEventSource.CLOSED;
  current.onerror();
  h.context.fetch = async () => ({ ok: true, json: async () => [] });
  finish({ ok: true, json: async () => [] });
  await refreshing;
  assert.equal(h.element('status').textContent, 'LIVE: disconnected');
  h.context.fetch = () => new Promise(resolve => { finish = resolve; });
  const secondRefresh = h.element('btnRefresh').listeners.click();
  h.element('live').checked = false;
  h.context.stopLive();
  h.context.setStatus('non-live-fixture');
  h.context.fetch = async () => ({ ok: true, json: async () => [] });
  finish({ ok: true, json: async () => [] });
  await secondRefresh;
  assert.equal(h.element('status').textContent, 'non-live-fixture');
});

test('active SSE events still deduplicate and stay bounded', async () => {
  const h = await pageHarness();
  h.element('limit').value = '2';
  h.context.startLive();
  const source = h.FakeEventSource.instances[0];
  for (const id of ['one', 'one', 'two', 'three']) {
    source.listeners['debug-event']({ data: JSON.stringify({ id }) });
  }
  assert.deepEqual(JSON.parse(h.element('listJson').textContent).map(event => event.id), ['two', 'three']);
});

async function deferEventSnapshot(h) {
  let finish, markStarted;
  const eventFetchStarted = new Promise(resolve => { markStarted = resolve; });
  h.context.fetch = async url => url.startsWith('/api/diagnostics/debug/events/page?')
    ? new Promise(resolve => { finish = resolve; markStarted(); })
    : { ok: true, json: async () => [] };
  const refreshing = h.context.refreshAll();
  await eventFetchStarted;
  return { refreshing, finish: response => finish(response) };
}

test('requested live mode still starts when the initial HTTP snapshot fails', async () => {
  const h = await pageHarness(async () => ({ ok: false, status: 500 }), '?live=1');
  assert.equal(h.element('live').checked, true);
  assert.equal(h.FakeEventSource.instances.length, 1);
  assert.equal(h.FakeEventSource.instances[0].readyState, h.FakeEventSource.CONNECTING);
});

test('late initial load cannot restart a stream the user has already opened', async () => {
  let finish;
  const h = await pageHarness(async url => url.startsWith('/api/diagnostics/debug/events/page?')
    ? new Promise(resolve => { finish = resolve; })
    : { ok: true, json: async () => [] });
  h.element('live').checked = true;
  h.context.startLive();
  const source = h.FakeEventSource.instances[0];
  source.readyState = h.FakeEventSource.OPEN;
  source.onopen();
  source.listeners['debug-event']({ data: JSON.stringify({ id: 'live-during-startup' }) });
  finish({ ok: true, json: async () => [] });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.FakeEventSource.instances.length, 1);
  assert.equal(source.readyState, h.FakeEventSource.OPEN);
  assert.deepEqual(JSON.parse(h.element('listJson').textContent).map(event => event.id), ['live-during-startup']);
});

test('a pending snapshot cannot replace events or status after live mode starts', async () => {
  const h = await pageHarness();
  const pending = await deferEventSnapshot(h);
  h.element('live').checked = true;
  h.context.startLive();
  const source = h.FakeEventSource.instances[0];
  source.readyState = h.FakeEventSource.OPEN;
  source.onopen();
  source.listeners['debug-event']({ data: JSON.stringify({ id: 'current-live' }) });
  pending.finish({ ok: true, json: async () => [{ id: 'old-snapshot' }] });
  await pending.refreshing;
  assert.deepEqual(JSON.parse(h.element('listJson').textContent).map(event => event.id), ['current-live']);
  assert.equal(h.element('status').textContent, 'LIVE: connected');
});

test('clearing the view retires a pending snapshot', async () => {
  const h = await pageHarness();
  const pending = await deferEventSnapshot(h);
  h.context.clearAll();
  pending.finish({ ok: true, json: async () => [{ id: 'before-clear' }] });
  await pending.refreshing;
  assert.deepEqual(JSON.parse(h.element('listJson').textContent), []);
});

test('the latest refresh wins when HTTP snapshots complete in reverse order', async () => {
  const h = await pageHarness();
  const older = await deferEventSnapshot(h);
  const newer = await deferEventSnapshot(h);
  newer.finish({ ok: true, json: async () => eventPage([{ id: 'newest' }]) });
  await newer.refreshing;
  older.finish({ ok: true, json: async () => [{ id: 'older' }] });
  await older.refreshing;
  assert.deepEqual(JSON.parse(h.element('listJson').textContent).map(event => event.id), ['newest']);
});

test('a retired HTTP error cannot overwrite live connection status', async () => {
  const h = await pageHarness();
  const pending = await deferEventSnapshot(h);
  h.element('live').checked = true;
  h.context.startLive();
  const source = h.FakeEventSource.instances[0];
  source.readyState = h.FakeEventSource.OPEN;
  source.onopen();
  pending.finish({ ok: false, status: 500 });
  await pending.refreshing;
  assert.equal(h.element('status').textContent, 'LIVE: connected');
});

test('malformed SSE JSON reports a stable reason without reflecting parser excerpts', async () => {
  const h = await pageHarness();
  h.context.startLive();
  const source = h.FakeEventSource.instances[0];
  source.listeners['debug-event']({ data: 'SSELEAK private stream payload' });
  source.listeners['debug-event']({ data: JSON.stringify({ id: 'after-invalid' }) });
  assert.ok(h.logs.length > 0);
  assert.ok(h.logs.every(log => !log.includes('SSELEAK')));
  assert.deepEqual(JSON.parse(h.element('listJson').textContent).map(event => event.id), ['after-invalid']);
});

test('exact correlation hashes are server query parameters and older pages replace bounded rows', async () => {
  const h = await pageHarness();
  h.element('requestIdHash').value = 'hash:111111111111';
  h.element('traceIdHash').value = 'hash:222222222222';
  const urls = [];
  h.context.fetch = async url => {
    urls.push(url);
    const older = new URLSearchParams(url.split('?')[1]).has('cursor');
    const items = [{ id: older ? 'older' : 'newer', requestId: 'hash:111111111111', traceId: 'hash:222222222222' }];
    return { ok: true, json: async () => eventPage(items, older ? null : 'opaque-cursor') };
  };
  assert.equal(await h.context.refreshEvents(), true);
  assert.equal(h.element('btnOlder').disabled, false);
  await h.element('btnOlder').listeners.click();
  const qs = new URLSearchParams(urls[1].split('?')[1]);
  assert.equal(qs.get('requestIdHash'), 'hash:111111111111');
  assert.equal(qs.get('traceIdHash'), 'hash:222222222222');
  assert.equal(qs.get('cursor'), 'opaque-cursor');
  assert.deepEqual(JSON.parse(h.element('listJson').textContent).map(e => e.id), ['older']);
  assert.equal(h.element('btnOlder').disabled, true);
});

test('eviction has an explicit page notice and never fetches a replacement latest page', async () => {
  const h = await pageHarness();
  h.context.fetch = async () => ({ ok: true, json: async () => eventPage([{ id: 'retained' }], 'cursor') });
  await h.context.refreshEvents();
  let calls = 0, bodyReads = 0;
  h.context.fetch = async () => ({ ok: false, status: (++calls, 410), json: async () => { bodyReads++; } });
  await h.element('btnOlder').listeners.click();
  assert.equal(calls, 1);
  assert.equal(bodyReads, 0);
  assert.equal(h.element('btnOlder').disabled, true);
  assert.match(h.element('pageStatus').textContent, /보관 범위/);
  assert.deepEqual(JSON.parse(h.element('listJson').textContent).map(e => e.id), ['retained']);
});

test('safe deep links fill exact filters and LIVE uses the same AND correlation', async () => {
  const h = await pageHarness(undefined, '?requestIdHash=hash%3A111111111111&traceIdHash=hash%3A222222222222');
  assert.equal(h.element('requestIdHash').value, 'hash:111111111111');
  assert.equal(h.element('traceIdHash').value, 'hash:222222222222');
  h.context.startLive();
  const source = h.FakeEventSource.instances[0];
  for (const ev of [
    { id: 'wrong-trace', requestId: 'hash:111111111111', traceId: 'hash:999999999999' },
    { id: 'match', requestId: 'hash:111111111111', traceId: 'hash:222222222222' },
    { id: 'wrong-request', requestId: 'hash:999999999999', traceId: 'hash:222222222222' }
  ]) source.listeners['debug-event']({ data: JSON.stringify(ev) });
  assert.deepEqual(JSON.parse(h.element('listJson').textContent).map(e => e.id), ['match']);
});

test('malformed exact deep links are ignored instead of exposing raw values', async () => {
  const h = await pageHarness(undefined, '?requestIdHash=synthetic-private-id&traceIdHash=hash%3A123');
  assert.equal(h.element('requestIdHash').value, '');
  assert.equal(h.element('traceIdHash').value, '');
});

test('gap notice survives reconnect callbacks and never reflects the SSE payload', async () => {
  const h = await pageHarness();
  h.context.startLive();
  const first = h.FakeEventSource.instances[0];
  first.listeners.gap({ data: 'synthetic-private-gap-payload' });
  assert.match(h.element('liveGapStatus').textContent, /재생할 수 없습니다/);
  first.readyState = h.FakeEventSource.OPEN;
  first.onopen();
  first.listeners.hello({ data: '{}' });
  assert.match(h.element('liveGapStatus').textContent, /재생할 수 없습니다/);
  assert.ok(!h.element('liveGapStatus').textContent.includes('synthetic-private'));
  h.context.startLive();
  assert.equal(h.element('liveGapStatus').textContent, '');
  first.listeners.gap({ data: '{}' });
  assert.equal(h.element('liveGapStatus').textContent, '');
});
