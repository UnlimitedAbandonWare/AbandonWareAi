'use strict';
/*
 * session469 cancel-recovery probe — SOURCE_EXTRACTED boundary.
 *
 * Extracts top-level functions from main/resources/static/js/chat.js into a
 * vm context with stubbed collaborators and a fake clock, then drives the
 * cancel/recovery scenarios from the session469 brief (RED 3 / E6.8-13):
 *
 *   1 tokenless-stop-stalls-new-send   Stop before run token: heartbeat/timer
 *      state observed, sendMessageInFlight must not wedge the next send forever.
 *   2 late-token-pending-stop          completePendingStopBeforeToken cancels
 *      the exact run once identity arrives.
 *   3 cancel-timeout-state-cancelled   exact cancel times out, /state recheck
 *      reports cancelled -> terminal cleanup runs.
 *   4 cancel-timeout-state-streaming   recheck reports streaming -> stream
 *      survives, retryable outcome shown, heartbeat stays alive.
 *   5 duplicate-cancel-single-flight   a second Stop reuses streamCancelInFlight.
 *   6 send-after-successful-cancel     same-session new question dispatches.
 *
 * This is not a browser test and not a product PASS. Exit 0 = every extracted
 * scenario met its contract; exit 1 = at least one RED; exit 2 = tool error.
 * Node built-ins only. No network, server, Gradle, or product writes.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const CHAT_JS = path.join(ROOT, 'main', 'resources', 'static', 'js', 'chat.js');
const PROBE = 'session469-cancel-recovery';

const EXTRACT = [
  'clearActiveStreamHeartbeat',
  'cancelActiveStream',
  'applySuccessfulStreamCancel',
  'completePendingStopBeforeToken',
  'reconcilePendingStopRequest',
  'waitForPendingStreamCancel',
  'sendMessage',
  'sameActiveRunIdentity',
  'activeRunIdentitySnapshot',
  'clearActiveRunIdentityIfMatch',
  'clearActiveRunIdentity',
  'rememberActiveRunIdentity',
  'normalizeSessionIdValue',
  'normalizeRunToken',
  'requestServerCancelWithTimeout',
  'exactRunStateForCancel',
  'showRetryableCancelOutcome',
];

function extractFn(source, name) {
  const marker = 'function ' + name + '(';
  const at = source.indexOf(marker);
  if (at < 0) throw new Error('fn-missing:' + name);
  const lineStart = source.lastIndexOf('\n', at) + 1;
  const end = source.indexOf('\n}', at);
  if (end < 0) throw new Error('fn-unclosed:' + name);
  const body = source.slice(lineStart, end + 2);
  if (!body.includes('{')) throw new Error('fn-empty:' + name);
  return body;
}

function makeTimers() {
  const timers = new Map();
  const timeouts = new Map();
  const box = {
    now: 0,
    timers,
    timeouts,
    setInterval(fn) { const id = timers.size + timeouts.size + 100; timers.set(id, fn); return id; },
    clearInterval(id) { timers.delete(id); },
    setTimeout(fn, ms) { const id = timers.size + timeouts.size + 1; timeouts.set(id, { fn, at: box.now + (Number(ms) || 0) }); return id; },
    clearTimeout(id) { timeouts.delete(id); },
    advance(ms) {
      box.now += ms;
      const due = [...timeouts.entries()].filter(([, t]) => t.at <= box.now).sort((a, b) => a[1].at - b[1].at);
      for (const [id, t] of due) { timeouts.delete(id); t.fn(); }
    },
    alive(id) { return timers.has(id); },
  };
  return box;
}

const tick = () => new Promise((resolve) => setImmediate(resolve));
const ticks = async (n) => { for (let i = 0; i < n; i++) await tick(); };

function makeContext(overrides) {
  const clock = makeTimers();
  const calls = { dispatch: 0, cancelRequests: 0, retryableOutcome: 0, stateCalls: 0, signals: [], statuses: [] };
  const hb = clock.setInterval(() => {});
  const ctx = {
    console,
    AbortController,
    window: {
      setInterval: (fn) => clock.setInterval(fn),
      clearInterval: (id) => clock.clearInterval(id),
      setTimeout: (fn, ms) => clock.setTimeout(fn, ms),
      clearTimeout: (id) => clock.clearTimeout(id),
      sessionStorage: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
    },
    dom: {
      messageInput: { value: 'follow-up question', disabled: false, focus() {} },
      sendBtn: { disabled: false },
      stopBtn: { disabled: false },
      chatMessages: {},
      traceStatus: {},
      form: null,
    },
    pendingAttachments: [],
    chatModelCatalogReady: true,
    chatAccessState: 'open',
    sendMessageInFlight: false,
    restoredRunResumeInFlight: null,
    streamCancelInFlight: null,
    streamCancelRequested: false,
    streamRenderSuppressed: false,
    activeSessionId: null,
    activeRunToken: null,
    activeRunGeneration: 0,
    pendingStopBeforeToken: null,
    sessionSelectionGeneration: 0,
    ACTIVE_RUN_STORAGE_KEY: 'awx.probe.activeRun',
    state: { currentSessionId: null, latestVisibleTurnEvidence: null },
    invalidatePendingSessionSelectionForTranscriptOwnership: () => { ctx.sessionSelectionGeneration += 1; },
    activeStreamAssistant: { dataset: { state: 'pending' } },
    streamController: new AbortController(),
    activeStreamHeartbeatTimer: hb,
    SERVER_CANCEL_TIMEOUT_MS: 50,
    READY_ACK_TIMEOUT_MS: 50,
    FINAL_ACK_TIMEOUT_MS: 50,
    updateOrchestrationSignalBar: (x) => calls.signals.push(x),
    setCoreStatus: (a, b) => calls.statuses.push([a, b]),
    markAssistantStreamStopped: () => {},
    markActiveStreamStoppedRail: () => {},
    syncSendButtonState: () => {},
    syncSessionSelectionCapability: () => {},
    clearBlankComposerDraft: () => {},
    setStatusRailValue: () => {},
    requestServerCancel: async () => ({ outcome: 'cancelled' }),
    apiCall: async () => ({ json: async () => ({ runStatus: 'cancelled' }) }),
    chatTraceRequestUrl: (u) => u,
    withChatCorrelationHeaders: (h) => h || {},
    sendMessageUnlocked: async () => { calls.dispatch += 1; return 'sent'; },
  };
  Object.assign(ctx, overrides || {});
  ctx.__clock = clock;
  ctx.__calls = calls;
  ctx.__hb = hb;
  return ctx;
}

function load(context) {
  const source = fs.readFileSync(CHAT_JS, 'utf8');
  const code = EXTRACT.map((name) => extractFn(source, name)).join('\n');
  vm.createContext(context);
  vm.runInContext(code, context);
  const realRetryable = context.showRetryableCancelOutcome;
  context.showRetryableCancelOutcome = (reason) => {
    context.__calls.retryableOutcome += 1;
    return realRetryable(reason);
  };
  return context;
}

function row(scenario, status, obs) {
  return { probe: PROBE, scenario, status, boundary: 'source-extracted', productPass: false, obs };
}

async function scenarioTokenless() {
  const ctx = makeContext();
  load(ctx);
  let resolveSend;
  ctx.sendMessageUnlocked = () => {
    ctx.__calls.dispatch += 1;
    if (ctx.__calls.dispatch === 1) return new Promise((resolve) => { resolveSend = resolve; });
    return Promise.resolve('sent');
  };
  const p1 = vm.runInContext('sendMessage()', ctx);
  await ticks(2);
  const inFlightAfterSend = ctx.sendMessageInFlight === true;
  const res = await vm.runInContext('cancelActiveStream()', ctx);
  await vm.runInContext('sendMessage()', ctx);
  await ticks(2);
  const obs = {
    cancelReturned: res,
    pendingStop: ctx.pendingStopBeforeToken != null,
    stopDisabled: ctx.dom.stopBtn.disabled === true,
    controllerAborted: ctx.streamController.signal.aborted === true,
    heartbeatAlive: ctx.__clock.alive(ctx.__hb),
    inFlightAfterSend,
    sendMessageInFlight: ctx.sendMessageInFlight === true,
    dispatchCount: ctx.__calls.dispatch,
  };
  let settleRecovers = false;
  if (resolveSend) { resolveSend('settled'); await p1; await ticks(2); }
  await vm.runInContext('sendMessage()', ctx);
  await ticks(2);
  settleRecovers = ctx.__calls.dispatch >= 2;
  obs.settleRecovers = settleRecovers;
  const stuck = obs.sendMessageInFlight && !obs.controllerAborted && obs.dispatchCount === 1;
  return row('tokenless-stop-stalls-new-send', stuck ? 'RED' : 'PASS', obs);
}

async function scenarioLateToken() {
  const ctx = makeContext({
    activeSessionId: 469,
    activeRunToken: 'rt-469',
    activeRunGeneration: 3,
  });
  const pending = { assistant: { dataset: { state: 'pending' } }, controller: new AbortController(), options: {} };
  ctx.pendingStopBeforeToken = pending;
  load(ctx);
  const res = await vm.runInContext('completePendingStopBeforeToken(469, "rt-469", pendingStopBeforeToken)', ctx);
  const obs = {
    returned: res,
    pendingControllerAborted: pending.controller.signal.aborted === true,
    pendingCleared: ctx.pendingStopBeforeToken === null,
    heartbeatCleared: !ctx.__clock.alive(ctx.__hb),
    stopDisabled: ctx.dom.stopBtn.disabled === true,
  };
  const ok = res === true && obs.pendingControllerAborted && obs.pendingCleared && obs.heartbeatCleared;
  return row('late-token-pending-stop', ok ? 'PASS' : 'RED', obs);
}

async function scenarioTimeoutCancelled() {
  const ctx = makeContext({
    activeSessionId: 469,
    activeRunToken: 'rt-469',
    activeRunGeneration: 5,
    requestServerCancel: () => new Promise(() => {}),
    apiCall: async () => { ctx.__calls.stateCalls += 1; return { json: async () => ({ runStatus: 'cancelled' }) }; },
  });
  ctx.__calls.stateCalls = 0;
  load(ctx);
  const p = vm.runInContext('cancelActiveStream()', ctx);
  await ticks(2);
  ctx.__clock.advance(60);
  const res = await p;
  await ticks(2);
  const obs = {
    returned: res,
    controllerAborted: ctx.streamController.signal.aborted === true,
    identityCleared: ctx.activeSessionId === null && ctx.activeRunToken === null,
    pendingCleared: ctx.pendingStopBeforeToken === null,
    stateRecheckCalls: ctx.__calls.stateCalls,
    stopDisabled: ctx.dom.stopBtn.disabled === true,
  };
  const ok = res === true && obs.controllerAborted && obs.identityCleared && obs.stateRecheckCalls >= 1;
  return row('cancel-timeout-state-cancelled', ok ? 'PASS' : 'RED', obs);
}

async function scenarioTimeoutStreaming() {
  const ctx = makeContext({
    activeSessionId: 469,
    activeRunToken: 'rt-469',
    activeRunGeneration: 9,
    sendMessageInFlight: true,
    requestServerCancel: () => new Promise(() => {}),
    apiCall: async () => ({ json: async () => ({ runStatus: 'streaming' }) }),
  });
  load(ctx);
  const p = vm.runInContext('cancelActiveStream()', ctx);
  await ticks(2);
  ctx.__clock.advance(60);
  const res = await p;
  await ticks(2);
  const obs = {
    returned: res,
    controllerAborted: ctx.streamController.signal.aborted === true,
    retryableOutcomeShown: ctx.__calls.retryableOutcome >= 1,
    heartbeatAlive: ctx.__clock.alive(ctx.__hb),
    identityKept: ctx.activeSessionId === 469,
    stopDisabled: ctx.dom.stopBtn.disabled === true,
  };
  const recovered = obs.controllerAborted === false && obs.heartbeatAlive === true;
  return row('cancel-timeout-state-streaming', recovered ? 'PASS' : 'RED', obs);
}

async function scenarioDuplicateCancel() {
  const ctx = makeContext({
    activeSessionId: 469,
    activeRunToken: 'rt-469',
    activeRunGeneration: 11,
    requestServerCancel: () => { ctx.__calls.cancelRequests += 1; return new Promise(() => {}); },
  });
  ctx.__calls.cancelRequests = 0;
  load(ctx);
  const p1 = vm.runInContext('cancelActiveStream()', ctx);
  const p2 = vm.runInContext('cancelActiveStream()', ctx);
  await ticks(2);
  const sameTask = ctx.streamCancelInFlight != null;
  ctx.__clock.advance(60);
  await ticks(2);
  const obs = {
    singleFlight: sameTask,
    cancelRequestCalls: ctx.__calls.cancelRequests,
    settledSameOutcome: (await p1) === (await p2),
  };
  const ok = sameTask && obs.cancelRequestCalls === 1 && obs.settledSameOutcome;
  return row('duplicate-cancel-single-flight', ok ? 'PASS' : 'RED', obs);
}

async function scenarioSendAfterCancel() {
  const ctx = makeContext({
    activeSessionId: 469,
    activeRunToken: 'rt-469',
    activeRunGeneration: 13,
    requestServerCancel: async () => ({ outcome: 'cancelled' }),
  });
  load(ctx);
  const cancelRes = await vm.runInContext('cancelActiveStream()', ctx);
  await vm.runInContext('sendMessage()', ctx);
  await ticks(3);
  const obs = {
    cancelReturned: cancelRes,
    controllerAborted: ctx.streamController.signal.aborted === true,
    dispatchCount: ctx.__calls.dispatch,
    sendMessageInFlight: ctx.sendMessageInFlight === true,
    stopDisabled: ctx.dom.stopBtn.disabled === true,
    inputEnabled: ctx.dom.messageInput.disabled === false,
  };
  const ok = cancelRes === true && obs.dispatchCount >= 1 && !obs.sendMessageInFlight;
  return row('send-after-successful-cancel', ok ? 'PASS' : 'RED', obs);
}

async function main() {
  const scenarios = [
    scenarioTokenless,
    scenarioLateToken,
    scenarioTimeoutCancelled,
    scenarioTimeoutStreaming,
    scenarioDuplicateCancel,
    scenarioSendAfterCancel,
  ];
  const results = [];
  for (const run of scenarios) {
    try {
      results.push(await run());
    } catch (err) {
      results.push(row('error', 'RED', { error: String(err && err.message || err) }));
    }
  }
  for (const result of results) console.log(JSON.stringify(result));
  const red = results.filter((r) => r.status === 'RED').map((r) => r.scenario);
  const summary = {
    probe: PROBE,
    summary: true,
    status: red.length ? 'RED' : 'GREEN',
    red,
    count: results.length,
    boundary: 'source-extracted',
    productPass: false,
    note: 'Extracted functions from live chat.js under stubbed transport. Not a browser or served-runtime verdict.',
  };
  console.log(JSON.stringify(summary));
  process.exitCode = red.length ? 1 : 0;
}

main().catch((err) => {
  console.log(JSON.stringify({ probe: PROBE, summary: true, status: 'ERROR', error: String(err && err.stack || err), productPass: false }));
  process.exitCode = 2;
});
