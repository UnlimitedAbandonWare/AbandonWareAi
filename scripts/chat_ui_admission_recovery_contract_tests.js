'use strict';
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('main/resources/static/js/chat.js', 'utf8');
let now = Date.parse('Wed, 23 Sep 2026 12:00:00 GMT');
let sent = 0, inspected = 0, health = 0, focused = '', reloaded = 0;
const timers = [];
function element() {
  return { dataset: {}, children: [], textContent: '', disabled: false, isConnected: true,
    setAttribute() {}, appendChild(child) { this.children.push(child); },
    addEventListener(type, fn) { this[type] = fn; },
    focus() { focused = this.id; }, closest() { return null; } };
}
const input = Object.assign(element(), { id: 'input', value: 'preserved question' });
const model = Object.assign(element(), { id: 'model' });
const search = Object.assign(element(), { id: 'search' });
const context = vm.createContext({
  document: { getElementById: () => null, createElement: element },
  Date: class extends Date { static now() { return now; } },
  window: { setTimeout(fn) { timers.push(fn); }, clearTimeout() {}, location: { reload() { reloaded++; } } },
  CHAT_UI_HEARTBEAT_API: '/api/chat/ui-heartbeat', AbortController,
  async fetch(url, options) { assert.equal(options.method, 'HEAD'); health++; return { ok: true }; },
  dom: { messageInput: input, modelSelect: model, searchModeSelect: search },
  state: { currentSessionId: 5 }, sendMessageInFlight: false, restoredRunResumeInFlight: false,
  sessionStorage: { setItem() {} }, setMessageContent() {},
  strictBackendSessionId: value => Number.isSafeInteger(value) && value > 0 ? value : null,
  async sendMessage() { sent++; }, async selectSessionCandidate(id) { inspected = id; },
  async refreshSessionList() { inspected = -1; }, async refreshDebugHeartbeat() { health++; }
});
vm.runInContext(source.slice(source.indexOf('function chatFailureMeta('),
  source.indexOf('function chatFailureError(')), context);
let checks = 0;
function check(name, fn) { fn(); checks++; console.log('PASS ' + name); }
function classify(code, status = 429, extra = {}) {
  context.input = { status, contentType: 'application/json',
    boundedText: JSON.stringify({ status, reasonCode: code }), ...extra };
  return vm.runInContext('classifyChatFailure(input)', context);
}
const reasons = [
  ['chat_rate_limited', 'rate_limited', 'wait_then_retry', true],
  ['chat_admission_exceeded', 'concurrency_limited', 'check_run', false],
  ['chat_model_budget_exceeded', 'model_budget_exceeded', 'adjust_model_settings', false],
  ['chat_retrieval_budget_exceeded', 'retrieval_budget_exceeded', 'adjust_search_settings', false],
  ['chat_provider_budget_exceeded', 'provider_budget_exceeded', 'adjust_search_settings', false],
  ['public_body_executor_saturated', 'service_unavailable', 'check_service', false],
  ['chat_admission_unavailable', 'service_unavailable', 'check_service', false],
  ['chat_plan_budget_unavailable', 'service_unavailable', 'check_service', false],
  ['idempotency_duplicate', 'run_unresolved', 'check_run', false],
  ['idempotency_payload_mismatch', 'run_unresolved', 'check_run', false],
  ['session_quota_exceeded', 'quota_exceeded', 'check_quota', false]
];
for (const [code, kind, action, retryable] of reasons) {
  check('distinct reason ' + code, () => {
    const result = classify(code);
    assert.equal(result.serverCode, code); assert.equal(result.failureKind, kind);
    assert.equal(result.nextAction, action); assert.equal(result.retryable, retryable);
    assert(Object.isFrozen(result));
  });
}
check('actual admission filter error shape', () => {
  assert.equal(classify('', 429, { boundedText: '{"error":"chat_rate_limited"}' }).serverCode, 'chat_rate_limited');
});
check('unknown 429 never promises a timed retry', () => {
  const result = classify('private_unknown_reason');
  assert.equal(result.serverCode, null); assert.equal(result.retryable, false);
  assert.equal(result.nextAction, 'check_service');
});
check('conflicting code aliases fail closed', () => {
  assert.equal(classify('', 429, { boundedText: '{"reasonCode":"chat_retrieval_budget_exceeded","code":"chat_rate_limited"}' }).serverCode, null);
});
check('malformed/truncated bodies stay bounded', () => {
  assert.equal(classify('', 429, { boundedText: '{"reasonCode":' }).serverCode, null);
  assert.equal(classify('chat_rate_limited', 429, { truncated: true }).serverCode, null);
  assert.equal(classify('', 429, { boundedText: '{"reasonCode":{"private":"sentinel"}}' }).serverCode, null);
});
check('model errors retain meaning under 429', () => {
  assert.equal(classify('model_unavailable').nextAction, 'choose_model');
});
function retryAt(value) { context.header = value; return vm.runInContext('chatRetryAt(header)', context); }
check('Retry-After delta seconds', () => { assert.equal(retryAt('12'), now + 12000); });
check('Retry-After HTTP date', () => { assert.equal(retryAt('Wed, 23 Sep 2026 12:00:03 GMT'), now + 3000); });
check('Retry-After past date', () => { assert.equal(retryAt('Wed, 23 Sep 2026 11:00:00 GMT'), now); });
check('Retry-After invalid/negative/overflow', () => {
  for (const v of ['', '-2', '1.2', 'tomorrow', '9'.repeat(40)]) assert.equal(retryAt(v), null);
});
check('wait deadline attached only to time limit', () => {
  assert.equal(classify('chat_rate_limited', 429, { retryAfter: '12' }).retryAt, now + 12000);
  assert.equal(classify('chat_model_budget_exceeded', 429, { retryAfter: '12' }).retryAt, undefined);
});
function notice(meta, recovery = { draftText: 'preserved question', sessionId: 5 }) {
  context.assistant = element(); context.meta = meta; context.recovery = recovery;
  vm.runInContext('renderChatFailureNotice(assistant, meta, recovery)', context);
  return context.assistant.children.at(-1).children.at(-1);
}
(async () => {
  const button = notice(classify('chat_rate_limited', 429, { retryAfter: '2' }));
  timers.shift()();
  assert.equal(button.disabled, true); assert.match(button.textContent, /2초/);
  await button.click(); assert.equal(sent, 0);
  now += 2000; timers.shift()(); assert.equal(button.disabled, false);
  await button.click(); await button.click(); assert.equal(sent, 1);
  check('explicit retry obeys wait and sends once', () => assert.equal(input.value, 'preserved question'));

  const old = notice(classify('chat_rate_limited'));
  context.state.currentSessionId = 6;
  await old.click(); assert.equal(sent, 1);
  context.state.currentSessionId = 5; input.value = 'new draft';
  await old.click(); assert.equal(sent, 1);
  check('stale button preserves changed session and draft', () => assert.equal(input.value, 'new draft'));
  input.value = 'preserved question';

  const readyRetry = notice(classify('chat_rate_limited'));
  vm.runInContext('chatModelCatalogReady = false', context);
  await readyRetry.click(); assert.equal(readyRetry.disabled, false); assert.equal(sent, 1);
  vm.runInContext('chatModelCatalogReady = true; chatAccessState = "forbidden"', context);
  await readyRetry.click(); assert.equal(readyRetry.disabled, false); assert.equal(sent, 1);
  vm.runInContext('chatAccessState = "ready"', context);
  check('blocked composer leaves retry available', () => assert.equal(readyRetry.disabled, false));

  await notice(classify('chat_model_budget_exceeded')).click();
  check('model budget opens model control', () => assert.equal(focused, 'model'));
  await notice(classify('chat_retrieval_budget_exceeded')).click();
  check('search budget opens search control', () => assert.equal(focused, 'search'));
  const connectionAction = notice(classify('public_body_executor_saturated'));
  assert.equal(connectionAction.textContent, '서버 연결 확인');
  await connectionAction.click();
  assert.match(context.assistant.children.at(-1).children.at(-1).textContent, /서버 연결 응답/);
  check('saturation checks service without generation', () => { assert.equal(health, 1); assert.equal(sent, 1); });
  await notice(classify('idempotency_duplicate', 409)).click();
  check('duplicate checks existing session without generation', () => { assert.equal(inspected, 5); assert.equal(sent, 1); });
  const ambiguous = notice(classify('rate_limited'));
  await ambiguous.click();
  check('uncertain execution cannot regenerate', () => assert.equal(sent, 1));
  await notice(classify('csrf_invalid', 403)).click();
  check('CSRF recovery refreshes session', () => assert.equal(reloaded, 1));
  console.log('chat admission recovery: ' + checks + ' checks passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
