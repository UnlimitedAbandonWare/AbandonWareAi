'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const root = process.cwd();
const source = fs.readFileSync(path.join(root, 'main/resources/static/js/chat.js'), 'utf8');
function activeFunction(name) {
  const start = new RegExp(`^function ${name}\\(`, 'm').exec(source)?.index;
  assert.notEqual(start, undefined, 'active helper must exist');
  const end = source.indexOf('\n}', start);
  assert.ok(end > start, 'active helper must have a complete body');
  return source.slice(start, end + 2);
}
const helpers = ['modelRuntimeStatus', 'isClearedLocalLlmOperatorAction',
  'localLlmRecoveryDetail', 'normalizedNextAction'].map(activeFunction).join('\n');
const start = source.indexOf('  const explicitLocalLlmOperatorAction = modelRuntime.localLlmOperatorAction || {};');
const end = source.indexOf('  if (!supabaseRow.projectScopeStatus', start);
assert.ok(start > 0 && end > start, 'active model heartbeat branch must exist');
const branch = source.slice(start, end);
const action = { failureClass: 'LOCAL_LLM_UNAVAILABLE', nextAction: 'inspect_model_route_or_start_local_llm', triggered: true };
const ready = { state: 'READY', modelReady: true, port: 11435 };
function observe(modelRuntime, recovery = null) {
  const context = { modelRuntime, lastLocalLlmRecovery: recovery, lastAssistantModelFallback: null,
    coreRow: {}, uiRow: {}, externalRow: {}, goalNextRow: {}, heartbeatReason: '', heartbeatFallbackActive: false };
  vm.runInNewContext(`${helpers}\nconst recoveryDetail = localLlmRecoveryDetail(lastLocalLlmRecovery);\n${branch}\n`
    + 'globalThis.result = { status: modelStatus, detail: modelDetail, action: primaryModelNextAction };', context, { timeout: 1000 });
  return context.result;
}
test('ordinary runtime status stays authoritative without recovery', () => {
  const result = observe({ status: 'OK', route: 'local' });
  assert.equal(result.status, 'OK'); assert.equal(result.action, null);
});
test('unresolved operator action remains actionable without recovery', () => {
  const result = observe({ status: 'WARN', localLlmOperatorAction: action });
  assert.equal(result.status, 'WARN'); assert.equal(result.action, action.nextAction);
  assert.ok(result.detail.includes(action.failureClass));
});
test('process and model readiness cannot clear an unresolved operator warning', () => {
  const result = observe({ status: 'WARN', localLlmOperatorAction: action }, ready);
  assert.equal(result.status, 'WARN'); assert.equal(result.action, action.nextAction);
});
test('ready recovery preserves the unresolved failure and action in Model detail', () => {
  const result = observe({ status: 'WARN', localLlmOperatorAction: action }, ready);
  assert.ok(result.detail.includes('11435'));
  assert.ok(result.detail.includes(action.failureClass));
  assert.ok(result.detail.includes(action.nextAction));
});
test('an explicitly cleared action permits ready state without stale recommendation', () => {
  const result = observe({ status: 'OK', localLlmOperatorAction: {
    failureClass: 'none', nextAction: 'none', triggered: false, triggerReason: 'native_success'
  } }, ready);
  assert.equal(result.status, 'OK'); assert.equal(result.action, null);
  assert.ok(!result.detail.includes('llm:'));
});
test('ready recovery without an operator action keeps existing ready presentation', () => {
  const result = observe({ status: 'WARN' }, ready);
  assert.equal(result.status, 'OK'); assert.equal(result.action, null);
});
test('warming recovery retains the unresolved action detail and warning', () => {
  const result = observe({ status: 'WARN', localLlmOperatorAction: action }, { state: 'MODEL_WARMING', modelReady: false });
  assert.equal(result.status, 'WARN'); assert.equal(result.action, action.nextAction);
  assert.ok(result.detail.includes(action.failureClass));
});
test('disabled or stopped recovery preserves runtime decisions including unknown', () => {
  for (const state of ['DISABLED', 'STOPPED']) {
    const result = observe({ status: 'WARN', localLlmOperatorAction: action }, { state });
    assert.equal(result.status, 'WARN'); assert.equal(result.action, action.nextAction);
    assert.ok(result.detail.includes(action.failureClass));
  }
  assert.equal(observe({ status: 'UNKNOWN' }).status, 'UNKNOWN');
});
