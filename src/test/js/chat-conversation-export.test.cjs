const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const sourcePath = path.join(__dirname, '../../../main/resources/static/js/chat-conversation-export.js');
class Element {
  constructor() { this.listeners = {}; this.children = []; this.dataset = {}; this.hidden = false; this.open = false; }
  addEventListener(name, fn) { (this.listeners[name] ||= []).push(fn); }
  emit(name) { (this.listeners[name] || []).forEach(fn => fn()); }
  append(...items) { this.children.push(...items); }
  replaceChildren() { this.children = []; }
  click() { this.clicked = true; this.emit('click'); }
  remove() { this.removed = true; }
}
function harness({ sid = '1', responder, nativeDownload = false } = {}) {
  assert.ok(fs.existsSync(sourcePath), 'conversation export module must exist');
  const panel = new Element(), list = new Element(), status = new Element(), more = new Element(), fresh = new Element();
  const buttons = ['json', 'zip'].map(format => { const e = new Element(); e.dataset.exportFormat = format; return e; });
  const selectors = { '[data-export-options]': list, '[data-export-status]': status, '[data-export-more]': more, '[data-export-fresh]': fresh };
  panel.querySelector = selector => selectors[selector]; panel.querySelectorAll = () => buttons;
  const document = Object.assign(new Element(), { querySelector: selector => selector === '[data-conversation-export]' ? panel : null,
    createElement: () => new Element(), body: new Element() });
  const calls = [], downloads = [], revoked = [];
  const metadata = { exportId: 'capture-1', exportedAt: '2026-10-07T00:00:00Z',
    snapshot: { fences: [{ sessionId: '1', highWatermark: '401' }], exportStatus: 'partial' } };
  const json = JSON.stringify({ exportId: 'capture-1', retained: 'same capture' });
  const response = value => ({ ok: true, status: 200, json: async () => value, blob: async () => new Blob([json]) });
  const fetch = async (url, options) => {
    calls.push({ url, options });
    if (responder) { const answer = await responder(url, options, response); if (answer) return answer; }
    return response(url.includes('export-options') ? { items: [{ sessionId: '1', title: '<img onerror=bad>' }, { sessionId: '2', title: 'previous' }], hasMore: true, nextCursor: '2' } : metadata);
  };
  const root = new Element(); Object.assign(root, { document, fetch, URL: { createObjectURL: () => 'blob:fixture', revokeObjectURL: url => revoked.push(url) }, setTimeout: fn => fn() });
  vm.runInNewContext(fs.readFileSync(sourcePath, 'utf8'), { window: root });
  let current = sid;
  const controller = root.ChatConversationExport.bind({ getCurrentSessionId: () => current,
    download: nativeDownload ? null : async (blob, filename) => downloads.push({ body: await blob.text(), filename }) });
  const select = (id, checked) => {
    const label = list.children.find(l => l.children[0].dataset.sessionId === id);
    assert.ok(label, `selection ${id} exists`); const checkbox = label.children[0]; checkbox.checked = checked; checkbox.emit('change');
  };
  return { controller, root, panel, status, calls, downloads, list, more, fresh, buttons, revoked, document,
    select, switch: value => { current = value; document.emit('brain-state:session'); }, response };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
test('in-progress capture warns without mutating the retained JSON/ZIP pair', async () => {
  const h = harness({ responder: async (url, options, response) => options.method === 'POST' ? response({
    exportId: 'capture-flight', exportedAt: '2026-10-10T00:00:00Z', snapshot: {
      fences: [{ sessionId: '1', highWatermark: '1' }], exportStatus: 'partial', latestTurnCoverage: 'in_progress'
    }
  }) : null });
  await h.controller.save('json');
  assert.match(h.status.textContent, /진행 중인 답변/);
  assert.match(h.status.textContent, /다시 캡처/);
  h.document.emit('brain-state:answer'); await h.controller.save('zip');
  assert.equal(h.calls.filter(c => c.options.method === 'POST').length, 1);
  assert.equal(h.downloads[0].body, h.downloads[1].body);
  h.fresh.click(); await h.controller.save('json');
  assert.equal(h.calls.filter(c => c.options.method === 'POST').length, 2);
});
test('JSON and ZIP reuse one capture and only call export APIs', async () => {
  const h = harness(); await h.controller.load(); h.select('2', true);
  await h.controller.save('json'); await h.controller.save('zip');
  assert.equal(h.calls.filter(c => c.options.method === 'POST').length, 1);
  assert.deepEqual(JSON.parse(h.calls.find(c => c.options.method === 'POST').options.body).sessionIds, ['1', '2']);
  assert.equal(h.downloads.length, 2); assert.equal(h.downloads[0].body, h.downloads[1].body);
  assert.match(h.downloads[0].filename, /capture-1\.json$/); assert.match(h.downloads[1].filename, /capture-1\.zip$/);
  assert.match(h.status.textContent, /401/); assert.match(h.status.textContent, /보존되지/);
  assert.ok(h.calls.every(c => c.url.startsWith('/api/chat/sessions/')));
  assert.equal(h.more.hidden, false); assert.equal(h.list.children[1].children[1].textContent, 'previous');
});
test('empty selection does not capture and current selection can be unchecked', async () => {
  const h = harness(); h.select('1', false); await h.controller.save('json');
  assert.equal(h.calls.length, 0); assert.equal(h.buttons[0].disabled, true);
});
test('session switch invalidates capture and reloads open historical selector', async () => {
  const h = harness(); h.panel.open = true; await h.controller.load(); await h.controller.save('json');
  const before = h.calls.length; h.switch('2'); await flush();
  assert.equal(h.controller.getState().capture, null);
  assert.ok(h.calls.slice(before).some(c => c.url.includes('export-options')), 'open panel must reload choices');
  assert.deepEqual(Array.from(h.controller.getState().sessionIds), ['2']);
  assert.ok(h.list.children.length >= 2);
});
test('pending options response after a switch is discarded and reloaded', async () => {
  let release; let once = true;
  const h = harness({ responder: async (url, options, response) => {
    if (url.includes('export-options') && once) { once = false; await new Promise(r => { release = r; }); return response({ items: [{ sessionId: '9', title: 'stale' }], hasMore: false }); }
  } });
  h.panel.open = true; const work = h.controller.load(); await flush(); h.switch('2'); release(); await work; await flush();
  assert.ok(!h.list.children.some(l => l.children[0].dataset.sessionId === '9'));
  assert.ok(h.calls.filter(c => c.url.includes('export-options')).length >= 2);
});
test('capture race and duplicate clicks do not download an old session', async () => {
  let release;
  const h = harness({ responder: async (url, options) => { if (options.method === 'POST') await new Promise(r => { release = r; }); } });
  const first = h.controller.save('json'); await flush(); await h.controller.save('zip'); h.switch('2'); release(); await first;
  assert.equal(h.downloads.length, 0); assert.equal(h.calls.filter(c => c.options.method === 'POST').length, 1);
});
test('same-session events and answer changes keep the old pair until explicit recapture', async () => {
  const h = harness(); await h.controller.save('json'); h.document.emit('brain-state:session'); h.document.emit('brain-state:answer');
  assert.match(h.status.textContent, /최신 자료는 새로 캡처/); await h.controller.save('zip');
  assert.equal(h.calls.filter(c => c.options.method === 'POST').length, 1);
  h.fresh.click(); await h.controller.save('json'); assert.equal(h.calls.filter(c => c.options.method === 'POST').length, 2);
});
test('TTL and capacity errors remain explicit and retryable', async () => {
  for (const status of [410, 413, 503, 404]) {
    const h = harness({ responder: async url => url.includes('?format=') ? { ok: false, status, json: async () => ({ reasonCode: 'safe_failure' }) } : null });
    await h.controller.save('json'); assert.equal(h.downloads.length, 0); assert.ok(h.status.textContent.length > 0);
    assert.equal(h.controller.getState().busy, false);
    if (status === 410 || status === 404) assert.equal(h.controller.getState().capture, null);
  }
});
test('native download releases object URL and removes link', async () => {
  const h = harness({ nativeDownload: true }); await h.controller.save('json');
  assert.deepEqual(h.revoked, ['blob:fixture']); assert.equal(h.document.body.children[0].removed, true);
  assert.equal(h.document.body.children[0].clicked, true);
});

test('first server session event on document selects the current conversation', () => {
  const h = harness({ sid: null }); assert.equal(h.buttons[0].disabled, true);
  h.switch('7'); assert.deepEqual(Array.from(h.controller.getState().sessionIds), ['7']);
  assert.equal(h.buttons[0].disabled, false);
});
