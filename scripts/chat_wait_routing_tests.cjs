const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('main/resources/static/js/chat.js', 'utf8');
function functionSource(name) {
  const start = source.indexOf('function ' + name + '(');
  assert.ok(start >= 0);
  const end = source.indexOf('\n}', start);
  return source.slice(start, end + 2);
}
let cap = null;
const context = vm.createContext({
  dom: {modelSelect: {value: 'installed-model'}, modelSelectionMode: {value: 'preferred'}},
  document: {querySelector: () => cap === null ? null : {getAttribute: () => cap}},
  STREAM_SERVER_MODEL_BUDGET_MS: 90000,
  STREAM_SERVER_WEB_BUDGET_MS: 30000,
  STREAM_SERVER_EVIDENCE_BUDGET_MS: 120000
});
vm.runInContext(functionSource('modelSelectionPayload') + '\n' + functionSource('streamServerBudgetMs'), context);
assert.equal(context.modelSelectionPayload().model, 'installed-model');
assert.equal(context.modelSelectionPayload().strictModelSelection, false);
context.dom.modelSelectionMode.value = 'strict';
assert.equal(context.modelSelectionPayload().strictModelSelection, true);
context.dom.modelSelectionMode.value = 'auto';
assert.equal(context.modelSelectionPayload().model, 'llmrouter.auto');
assert.equal(context.modelSelectionPayload().strictModelSelection, false);
delete context.dom.modelSelectionMode;
assert.equal(context.modelSelectionPayload().strictModelSelection, true);
assert.equal(context.streamServerBudgetMs({useRag: true}), 600000);
cap = '240000';
assert.equal(context.streamServerBudgetMs({useRag: true}), 240000);
assert.equal(context.streamServerBudgetMs({useWebSearch: false}), 240000);
cap = '30000';
assert.equal(context.streamServerBudgetMs({useRag: true}), 30000);
cap = '3600001';
assert.equal(context.streamServerBudgetMs({useRag: true}), 3600001);
for (cap of ['NaN', '-1', '1.5', '']) {
  assert.equal(context.streamServerBudgetMs({useRag: true}), 600000);
}
Object.assign(context, {
  isAssistantStreamStopped: () => false,
  appendMessageAriaSource: (_, text) => text,
  messageAriaLabel: (_, text) => text,
  reflectAssistantModelFallback: () => {},
  looksLikeMojibake: () => false
});
context.document.createTextNode = text => ({textContent: text});
context.document.createElement = () => ({});
vm.runInContext(functionSource('appendTextWithBreaks'), context);
const bubble = {dataset: {state: 'pending', waitMs: '80000'},
  title: 'Response still pending (client-wait:80000ms). Use Stop to cancel.',
  appendChild() {}, setAttribute() {}};
context.appendTextWithBreaks(bubble, 'completed answer');
assert.equal(bubble.dataset.state, 'ready');
assert.equal(bubble.dataset.waitMs, undefined);
assert.equal(bubble.title, '');
console.log('PASS chat wait budget, model selection and completed response state');
