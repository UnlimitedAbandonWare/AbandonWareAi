'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(process.env.CHAT_SESSION_TEST_SOURCE || 'main/resources/static/js/chat.js', 'utf8');
const slice = (start, end) => {
  const i = source.indexOf(start), j = source.indexOf(end, i);
  assert.ok(i >= 0 && j > i, 'current source anchors must exist');
  return source.slice(i, j);
};
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const response = body => ({ json: async () => body });
function setup() {
  const observed = { calls: [], views: [], notices: [], remembered: [], signals: [], transcript: 'CURRENT_SYNTHETIC_HISTORY' };
  const context = vm.createContext({
    sessionListRefreshGeneration: 0, sessionListRefreshInFlight: null, sessionListRefreshPendingReason: null,
    sessionSelectionGeneration: 0, restoredSessionHydrationGeneration: 0, restoredSessionHydrated: true,
    localControlOverrideActive: false, chatAccessState: 'checking', state: { currentSessionId: 41 }, busy: false,
    strictBackendSessionId: id => Number.isSafeInteger(Number(id)) && Number(id) > 0 ? Number(id) : null,
    normalizeSessionIdValue: id => id == null ? null : String(id),
    withChatCorrelationHeaders: headers => headers,
    renderSessionList: view => observed.views.push(view), syncSendButtonState() {},
    apiCall: (url, options) => { const call = deferred(); observed.calls.push({ url, options, ...call }); return call.promise; },
    sessionSelectionBusy: () => context.busy,
    sessionListRowMetadata: () => ({}), chatTraceRequestUrl: url => url,
    validateSessionDetail: (id, detail) => detail?.found === true && detail?.id === id && Array.isArray(detail.messages) ? detail : null,
    setSessionSelectionNotice: (message = '') => observed.notices.push(message),
    clearActiveRunIdentity() {}, rememberCurrentSessionId: id => { observed.remembered.push(id); context.state.currentSessionId = id; },
    clearSessionModeDiagnostics() {}, dom: { chatMessages: { replaceChildren: () => { observed.transcript = ''; } } },
    clearSelectionEntropyTrace() {}, window: {}, turnTracesByTurnId: () => new Map(),
    appendMessage: (role, content) => { observed.transcript += content; return { dataset: {} }; },
    renderExecutionModeReceipt() {}, applyRestoredSessionSettings() {}, restoreSessionModeBadge() {},
    syncSelectedSessionRow() {}, syncSessionSelectionCapability() {},
    dispatchBrainStateSignal: (type, data) => observed.signals.push({ type, data })
  });
  vm.runInContext(slice('function refreshSessionList(', 'const TURN_TRACE_SNAPSHOT_ID'), context);
  vm.runInContext(slice('async function selectSessionCandidate(', 'function currentControlSettings('), context);
  return { context, observed };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
test('explicit Gemini choice cannot cross into another authorized existing session',async()=>{
  const s=setup();
  s.context.dom.googleSearchRescue={checked:true,dataset:{rescueExplicit:'true',rescueOwnerScopeId:'a'.repeat(64)}};
  s.context.syncControlStatus=()=>{};s.context.markControlHydrationReady=()=>{};
  vm.runInContext(slice('function restoredSessionSetting(', 'function applyRestoredTerminalStoppedState('),s.context);
  const selected=s.context.selectSessionCandidate(42);
  s.observed.calls[0].resolve(response({id:42,found:true,messages:[],turnTraces:[],settings:{googleSearchRescueEnabled:false}}));
  assert.equal(await selected,true);
  assert.equal(s.context.dom.googleSearchRescue.checked,false);
  assert.equal(s.context.dom.googleSearchRescue.dataset.rescueExplicit,undefined);
});

test('session and final invalidations discard the precreation snapshot and coalesce one fresh title request', async () => {
  const s = setup();
  const initial = s.context.refreshSessionList('init');
  s.context.state.currentSessionId = 42;
  assert.equal(s.context.refreshSessionList('session'), initial);
  assert.equal(s.context.refreshSessionList('final'), initial);
  assert.equal(s.observed.calls.length, 1);
  s.observed.calls[0].resolve(response([]));
  await tick();
  assert.equal(s.observed.calls.length, 2, 'a mutation must cause a postmutation list request');
  assert.equal(s.observed.views.some(view => view.state === 'ready'), false, 'stale empty snapshot cannot be rendered');
  s.observed.calls[1].resolve(response([{ id: 42, title: 'SYNTHETIC_NEW_TITLE' }]));
  assert.equal(await initial, true, 'callers await the coalesced fresh result');
  assert.equal(s.observed.views.at(-1).rows[0].title, 'SYNTHETIC_NEW_TITLE');
  assert.equal(s.context.sessionListRefreshInFlight, null);
});
test('nonmutation concurrent refresh still shares a single request', async () => {
  const s = setup(), initial = s.context.refreshSessionList('init');
  assert.equal(s.context.refreshSessionList('manual'), initial);
  s.observed.calls[0].resolve(response([]));
  assert.equal(await initial, true);
  assert.equal(s.observed.calls.length, 1);
});
test('queued refresh failure stays finite and preserves denied access', async () => {
  const s = setup(), initial = s.context.refreshSessionList('init');
  s.context.refreshSessionList('session');
  s.context.refreshSessionList('final');
  s.observed.calls[0].reject(Object.assign(new Error('denied'), { status: 401 }));
  await tick();
  assert.equal(s.observed.calls.length, 2);
  s.observed.calls[1].reject(Object.assign(new Error('denied'), { status: 403 }));
  assert.equal(await initial, false);
  await tick();
  assert.equal(s.observed.calls.length, 2, 'no automatic authorization retry loop');
  assert.equal(s.context.chatAccessState, 'forbidden');
  assert.equal(s.observed.views.at(-1).state, 'session_list_forbidden');
});
test('selection failures visibly explain status without changing current history or owner', async () => {
  for (const status of [401, 403, 404, 503, 0]) {
    const s = setup(), selection = s.context.selectSessionCandidate(42);
    s.observed.calls[0].reject(Object.assign(new Error('synthetic failure'), { status }));
    assert.equal(await selection, false);
    assert.ok((s.observed.notices.at(-1) || '').length > 0, 'failure has a visible explanation');
    assert.equal(s.context.state.currentSessionId, 41);
    assert.equal(s.observed.transcript, 'CURRENT_SYNTHETIC_HISTORY');
    assert.equal(s.observed.remembered.length, 0);
    assert.equal(s.context.chatAccessState, 'checking', 'detail denial must not change global session-list authority');
  }
});
test('invalid detail response preserves current history and reports failure', async () => {
  const s = setup(), selection = s.context.selectSessionCandidate(42);
  s.observed.calls[0].resolve(response({ id: 43, found: true, messages: [] }));
  assert.equal(await selection, false);
  assert.ok((s.observed.notices.at(-1) || '').length > 0);
  assert.equal(s.observed.transcript, 'CURRENT_SYNTHETIC_HISTORY');
  assert.equal(s.context.state.currentSessionId, 41);
});
test('older selection failure cannot overwrite a newer successful history', async () => {
  const s = setup(), old = s.context.selectSessionCandidate(42), latest = s.context.selectSessionCandidate(43);
  s.observed.calls[1].resolve(response({ id: 43, found: true, messages: [{ role: 'user', content: 'SYNTHETIC_LATEST' }], turnTraces: [] }));
  assert.equal(await latest, true);
  s.observed.calls[0].reject(Object.assign(new Error('denied'), { status: 403 }));
  assert.equal(await old, false);
  assert.equal(s.context.state.currentSessionId, 43);
  assert.equal(s.observed.transcript, 'SYNTHETIC_LATEST');
  assert.equal(s.observed.notices.some(Boolean), false);
});
test('a run admitted while selection is pending suppresses stale failure UI', async () => {
  const s = setup(), selection = s.context.selectSessionCandidate(42);
  s.context.busy = true;
  s.observed.calls[0].reject(Object.assign(new Error('denied'), { status: 403 }));
  assert.equal(await selection, false);
  assert.equal(s.observed.notices.some(Boolean), false);
  assert.equal(s.context.state.currentSessionId, 41);
});
