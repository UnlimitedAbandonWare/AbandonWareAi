import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = fs.readFileSync('main/resources/static/js/chat.js', 'utf8');
function activeFunction(name) {
  const start = new RegExp('^(?:async )?function ' + name + '\\(', 'm').exec(source)?.index;
  assert.notEqual(start, undefined, 'active function must exist: ' + name);
  const closing = /\r?\n}(?:\r?\n|$)/.exec(source.slice(start));
  assert.ok(closing, 'complete active function: ' + name);
  return source.slice(start, start + closing.index + closing[0].indexOf('}') + 1);
}
const production = ['streamChat', 'streamClientDeadlineMs', 'streamServerBudgetMs',
  'streamClientDeadlineError', 'streamAbortError', 'responseHeader', 'sseFieldValue',
  'createSseEventParser', 'decodeSseEvent', 'normalizeSessionIdValue', 'normalizeRunToken',
  'activeRunIdentitySnapshot', 'sameActiveRunIdentity',
  'recoverExactRunAfterTransportLoss', 'cancelActiveStream'].map(activeFunction).join('\n');

async function flush() { for (let i = 0; i < 16; i++) await Promise.resolve(); }
function fixture({ headersPending = false, attach = false, budget = 40, initialTime = 0 } = {}) {
  let now = initialTime, sequence = 0, readResolve, readReject, cancelResolve;
  const timers = new Map(), requests = [], cancels = [];
  const assistant = { dataset: attach ? { streamStartedAt: '0', streamSseStartMs: '1' } : {},
    textContent: '', replaceChildren() {} };
  const ctx = {
    document: { getElementById: () => assistant,
      querySelector: () => ({ getAttribute: () => String(budget) }) },
    window: { setInterval(fn) { const id = ++sequence; timers.set(id, fn); return id; },
      clearInterval(id) { timers.delete(id); } },
    AbortController, TextDecoder, TextEncoder, DOMException, console,
    nowMs: () => now, MAX_SSE_EVENT_UTF8_BYTES: 65536, STREAM_STALE_WAIT_MS: 30000,
    activeStreamAssistant: assistant, activeStreamHeartbeatTimer: null, streamController: null,
    streamCancelRequested: false, streamRenderSuppressed: false, streamCancelInFlight: null,
    activeSessionId: 7, activeRunToken: 'fixture-run-token', activeRunGeneration: 1,
    pendingStopBeforeToken: null, dom: { stopBtn: { disabled: false } },
    syncSessionSelectionCapability() {}, invalidatePendingSessionSelectionForTranscriptOwnership() {},
    updateOrchestrationSignalBar() {}, setCoreStatus() {}, markAssistantClientWait() {},
    withChatCorrelationHeaders: h => h, streamServerBudgetHeaders: () => ({}),
    generationIdempotencyHeaders: () => ({}), chatTraceRequestUrl: u => u,
    applyChatResponseHeaders() {}, recordChatTransitionDebug() {},
    isAssistantStreamStopped: () => false, isActiveStreamRenderTarget: () => true,
    renderChatEvent() {}, clearAssistantPendingPlaceholder() {},
    clearActiveRunIdentity() {}, clearActiveRunIdentityIfMatch() {},
    acknowledgeExactRun: async () => true,
    clearActiveStreamHeartbeat(id) {
      timers.delete(id ?? ctx.activeStreamHeartbeatTimer);
      ctx.activeStreamHeartbeatTimer = null;
    },
    async apiCall(url) {
      assert.equal(url, '/api/chat/state?sessionId=7');
      requests.push({ url });
      return { json: async () => ({ attachable: true, runStatus: 'running', persisted: false }) };
    },
    fetch(url, options) {
      requests.push({ url, body: JSON.parse(options.body), signal: options.signal });
      const aborted = () => {
        const error = new DOMException('fixture transport aborted', 'AbortError');
        readReject?.(error);
      };
      options.signal.addEventListener('abort', aborted, { once: true });
      if (headersPending) return new Promise((resolve, reject) => {
        options.signal.addEventListener('abort',
          () => reject(new DOMException('fixture header abort', 'AbortError')), { once: true });
      });
      return Promise.resolve({ ok: true, status: 200, redirected: false,
        headers: { get: name => name.toLowerCase() === 'content-type' ? 'text/event-stream' : null },
        body: { getReader: () => ({ read: () => new Promise((resolve, reject) => {
          readResolve = resolve; readReject = reject;
        }) }) } });
    },
    requestServerCancelWithTimeout(sessionId, runToken) {
      cancels.push({ sessionId, runToken });
      return new Promise(resolve => { cancelResolve = resolve; });
    },
    applySuccessfulStreamCancel(expected) {
      assert.equal(expected.sessionId, 7); assert.equal(expected.runToken, 'fixture-run-token');
      ctx.streamCancelRequested = true; ctx.streamController?.abort(); return true;
    }
  };
  vm.createContext(ctx);
  vm.runInContext(production, ctx, { timeout: 1000 });
  return { ctx, assistant, requests, cancels,
    time(value) { now = value; },
    async tick() { for (const fn of [...timers.values()]) fn(); await flush(); },
    async chunk(text) {
      assert.equal(typeof readResolve, 'function', 'reader must already be awaiting progress');
      const resolve = readResolve; readResolve = null;
      resolve({ done: false, value: new TextEncoder().encode(text) }); await flush();
    },
    releaseCancel() { cancelResolve({ outcome: 'cancelled' }); },
    async cleanup(pending) { ctx.streamController?.abort(); await pending?.catch(() => {}); }
  };
}
function start(f, payload = {}) {
  const pending = f.ctx.streamChat({ message: 'synthetic', sessionId: 7,
    runToken: 'fixture-run-token', ...payload }, 'assistant');
  pending.catch(() => {});
  return pending;
}

test('header wait detaches transport without a server cancel', async () => {
  const f = fixture({ headersPending: true, budget: 6000 });
  const pending = start(f);
  try {
    f.time(5001); await f.tick();
    await assert.rejects(pending, { name: 'StreamClientDeadlineError' });
    assert.equal(f.requests[0].signal.aborted, true);
    assert.equal(f.cancels.length, 0);
    assert.equal(f.ctx.activeRunToken, 'fixture-run-token');
  } finally { await f.cleanup(pending); }
});

test('same-run recovery starts a fresh transport window while keeping run elapsed time', async () => {
  const f = fixture({ headersPending: true, attach: true, budget: 6000, initialTime: 20000 });
  const pending = f.ctx.recoverExactRunAfterTransportLoss(f.ctx.activeRunIdentitySnapshot(), 'assistant');
  pending.catch(() => {});
  try {
    await flush(); f.time(20001); await f.tick();
    const attach = f.requests.find(r => r.body?.attach === true);
    assert.ok(attach); assert.equal(attach.url, '/api/chat/stream?attach=true');
    assert.equal(attach.body.runToken, 'fixture-run-token');
    assert.equal(attach.signal.aborted, false);
    assert.equal(f.assistant.dataset.streamStartedAt, '0');
    assert.equal(f.cancels.length, 0);
  } finally { await f.cleanup(pending); }
});

test('body byte progress keeps the connection alive beyond its positive idle budget', async () => {
  const f = fixture();
  const pending = start(f);
  try {
    await flush();
    for (const now of [30, 60, 90, 120]) {
      f.time(now); await f.chunk(': heartbeat\n\n'); await f.tick();
      assert.equal(f.requests[0].signal.aborted, false);
    }
    assert.equal(f.cancels.length, 0);
  } finally { await f.cleanup(pending); }
});

test('a real silent body stall detaches without cancelling the accepted run', async () => {
  const f = fixture();
  const pending = start(f);
  try {
    await flush(); f.time(41); await f.tick();
    await assert.rejects(pending, { name: 'StreamClientDeadlineError' });
    assert.equal(f.requests[0].signal.aborted, true);
    assert.equal(f.cancels.length, 0);
  } finally { await f.cleanup(pending); }
});

test('explicit Stop joins one exact-run cancel request until it is acknowledged', async () => {
  const f = fixture();
  f.ctx.streamController = new AbortController();
  const one = f.ctx.cancelActiveStream({ railReason: 'user-stop' });
  const two = f.ctx.cancelActiveStream({ railReason: 'user-stop' });
  assert.equal(f.cancels.length, 1);
  assert.equal(f.cancels[0].sessionId, 7);
  assert.equal(f.cancels[0].runToken, 'fixture-run-token');
  f.releaseCancel();
  assert.equal(await one, true); assert.equal(await two, true);
  assert.equal(f.ctx.streamController.signal.aborted, true);
});
