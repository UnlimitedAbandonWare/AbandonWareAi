'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('main/resources/static/js/chat.js', 'utf8');
const start = source.indexOf('const renderTraceSignalDetail =');
const end = source.indexOf('const signalLabel =', start);
assert(start >= 0 && end > start);
function harness() {
  const context = {
    document: { createElement: () => ({ dataset: {}, rows: [] }) },
    shouldSuppressMessageDiagnostics: () => false,
    markChatDiagnosticNode: () => {},
    appendTraceSignalLabel: (detail, label, value) => {
      if (value != null && value !== '') detail.rows.push([label, value]);
    },
    window: { AwxChatTraceUi: { upsertDiagnostic: (target, type, detail) => {
      target.detail = detail;
      return detail;
    } } }
  };
  vm.createContext(context);
  vm.runInContext(source.slice(start, end) + '\nglobalThis.render = renderTraceSignalDetail;', context);
  return { render: context.render, bubble: {} };
}
for (const status of ['FAIL_SOFT', 'OK', 'SKIPPED']) {
  test('trace renders observed search outcome ' + status + ' with zero results', () => {
    const { render, bubble } = harness();
    render({}, { agentWebSearch: { status, returnedCount: 0,
      reasonCode: status === 'OK' ? null : 'web_search_unavailable' } }, bubble);
    assert(bubble.detail.rows.some(([label, value]) => label === 'web search' && value === status));
    assert(bubble.detail.rows.some(([label, value]) => label === 'search results' && value === 0));
    assert.equal(bubble.detail.rows.some(([label]) => label === 'search reason'), status !== 'OK');
  });
}
test('later empty or unobserved outcome cannot retain an earlier failure reason', () => {
  const { render, bubble } = harness();
  render({}, { agentWebSearch: { status: 'FAIL_SOFT', reasonCode: 'web_search_failed', returnedCount: 0 } }, bubble);
  render({}, { agentWebSearch: { status: 'OK', returnedCount: 0 } }, bubble);
  assert(!bubble.detail.rows.some(([label]) => label === 'search reason'));
  render({}, {}, bubble);
  assert(!bubble.detail.rows.some(([label]) => label.startsWith('search') || label === 'web search'));
});
test('unknown outcome is not displayed as a success', () => {
  const { render, bubble } = harness();
  render({}, { agentWebSearch: { status: 'unexpected', returnedCount: 0 } }, bubble);
  assert(!bubble.detail.rows.some(([label]) => label === 'web search'));
});
