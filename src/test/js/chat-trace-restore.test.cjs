'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('main/resources/static/js/chat.js', 'utf8');

function elementStub(tag) {
  const el = {
    tagName: String(tag).toUpperCase(),
    children: [],
    dataset: {},
    attributes: {},
    style: {},
    textContent: '',
    setAttribute(k, v) { this.attributes[k] = String(v); },
    appendChild(child) { this.children.push(child); return child; },
    text() { return this.textContent + this.children.map(c => (c.text ? c.text() : c.textContent || '')).join(''); }
  };
  return el;
}

function contextWithAdmin(adminPresent) {
  const restoreCalls = [];
  const ctx = vm.createContext({
    window: { AwxChatTraceUi: {
      restore: (bubble, trace) => {
        restoreCalls.push({ bubble, trace });
        return elementStub('details');
      },
      withDebugQuery: url => adminPresent ? url + (url.includes('?') ? '&' : '?') + 'debug=true' : url
    } },
    document: {
      createElement: elementStub,
      querySelector: (sel) => (sel === '[data-admin-diagnostics]' && adminPresent ? elementStub('div') : null)
    },
    strictBackendSessionId: (v) => (typeof v === 'number' && Number.isSafeInteger(v) && v > 0 ? v : null)
  });
  const start = source.indexOf('const TURN_TRACE_SNAPSHOT_ID');
  const end = source.indexOf('function sessionListRowMetadata(');
  assert.ok(start > 0 && end > start, 'turn-trace slice markers missing');
  vm.runInContext(source.slice(start, end), ctx);
  ctx.restoreCalls = restoreCalls;
  return ctx;
}

test('validateSessionDetail preserves turn ids and carries structured turn traces', () => {
  const ctx = contextWithAdmin(false);
  const detail = {
    id: 7,
    found: true,
    messages: [
      { turnId: 11, role: 'user', content: 'q1' },
      { turnId: 12, role: 'assistant', content: 'a1' },
      { turnId: 13, role: 'system', content: '?TRACESNAP?snap-1' }
    ],
    turnTraces: [
      { turnId: 12, snapshotId: 'snap-1', fields: { modelUsed: 'gemma4:26b', reason: 'scored' } },
      { turnId: 'bad', snapshotId: 'snap-x', fields: {} },
      { turnId: 12, snapshotId: '../evil', fields: {} }
    ],
    settings: {}
  };
  const validated = ctx.validateSessionDetail(7, detail);
  assert.ok(validated, 'detail rejected');
  assert.deepEqual(Array.from(validated.messages.map(m => m.turnId)), [11, 12]);
  assert.equal(validated.messages.length, 2);
  assert.equal(validated.messages[1].content, 'a1');
  assert.equal(validated.turnTraces.length, 1);
  assert.equal(validated.turnTraces[0].turnId, 12);
  assert.equal(validated.turnTraces[0].snapshotId, 'snap-1');
  assert.equal(validated.turnTraces[0].fields.modelUsed, 'gemma4:26b');
});

test('malformed turn traces fail closed and never surface as chat text', () => {
  const ctx = contextWithAdmin(false);
  const detail = {
    id: 8,
    messages: [{ turnId: 1, role: 'assistant', content: 'a' }],
    turnTraces: 'not-an-array',
    settings: {}
  };
  const validated = ctx.validateSessionDetail(8, detail);
  assert.equal(validated.turnTraces.length, 0);
  const evil = ctx.validateTurnTraces([
    { turnId: 1, snapshotId: 'ok-1', fields: { reason: '<img onerror=x>' } },
    { turnId: 2, snapshotId: 'ok-2', fields: { 'bad key': 'v' } },
    { turnId: 3, snapshotId: 'ok-3', fields: { reason: 'line\nbreak' } }
  ]);
  assert.equal(evil.length, 1);
  assert.equal(evil[0].fields.reason, '<img onerror=x>');
});

test('turnTracesByTurnId indexes by owning turn and skips orphans', () => {
  const ctx = contextWithAdmin(false);
  const map = ctx.turnTracesByTurnId([
    { turnId: 12, snapshotId: 'snap-1', fields: {} },
    { turnId: 99, snapshotId: 'snap-2', fields: {} },
    { turnId: null, snapshotId: 'snap-3', fields: {} }
  ]);
  assert.ok(map.has(12));
  assert.ok(map.has(99));
  assert.equal(map.size, 2);
  assert.equal(map.get(999), undefined);
});

test('restored trace delegates the validated exact pointer to the per-answer renderer', () => {
  const ctxUser = contextWithAdmin(false);
  const bubble = elementStub('div');
  const trace = { turnId: 12, snapshotId: 'snap-1', fields: { modelUsed: 'synthetic-model' } };
  const card = ctxUser.renderRestoredTurnTrace(bubble, trace);
  assert.ok(card, 'card not rendered');
  assert.equal(ctxUser.restoreCalls[0].bubble, bubble);
  assert.equal(ctxUser.restoreCalls[0].trace, trace);
  assert.equal(ctxUser.renderRestoredTurnTrace(elementStub('div'), { turnId: 1, snapshotId: '../bad' }), null);
  assert.equal(ctxUser.restoreCalls.length, 1);
  assert.equal(ctxUser.chatTraceRequestUrl('/api/chat/stream'), '/api/chat/stream');
  const ctxAdmin = contextWithAdmin(true);
  assert.equal(ctxAdmin.chatTraceRequestUrl('/api/chat/stream?attach=true'),
    '/api/chat/stream?attach=true&debug=true');
});
