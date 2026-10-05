const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('main/resources/static/js/chat.js', 'utf8');
test('history validation keeps the server-owned answer receipt through selector changes', () => {
  const start=source.indexOf('function validateSessionDetail(');
  const end=source.indexOf('\nfunction sessionListRowMetadata(',start);
  const context=vm.createContext({strictBackendSessionId:id=>Number(id)>0?Number(id):null,
    normalizeMessageTurnId:id=>Number(id)>0?Number(id):null,validateTurnTraces:()=>[]});
  vm.runInContext(source.slice(start,end)+';globalThis.validate=validateSessionDetail;',context);
  const receipt={requested:'SELF_ASK',effective:'AUTO',reason:'safety-gate',queryCount:1,httpAttempts:2,expanded:false};
  const result=context.validate(7,{id:7,messages:[{role:'assistant',content:'synthetic answer',turnId:66,executionMode:receipt}]});
  assert.ok(result.messages[0].executionMode,'history receipt must survive validation');
  assert.deepEqual(JSON.parse(JSON.stringify(result.messages[0].executionMode)),receipt);
  const {context:renderer,assistant}=harness();
  renderer.render({executionMode:result.messages[0].executionMode},assistant);
  assert.equal(assistant.dataset.executionModeEffective,'AUTO');
  assert.match(assistant.children[0].textContent,/안전 제한/);
});
function harness() {
  const start = source.indexOf('function renderExecutionModeReceipt(');
  const end = source.indexOf('\nconst renderTraceSignalDetail', start);
  assert.ok(start >= 0 && end > start, 'per-answer receipt renderer must exist');
  const context = vm.createContext({document: {createElement: () => ({dataset:{}, textContent:''})}});
  vm.runInContext(source.slice(start, end)+';globalThis.render=renderExecutionModeReceipt;', context);
  const assistant = {dataset:{}, children:[], querySelector(){ return this.children[0]; }, appendChild(node){this.children.push(node);}};
  return {context,assistant};
}
test('each answer keeps requested, actual, counts and fallback reason instead of current selector', () => {
  const {context,assistant} = harness();
  context.render({executionMode:{requested:'SELF_ASK',effective:'AUTO',reason:'safety-gate',queryCount:1,httpAttempts:2}}, assistant);
  assert.equal(assistant.dataset.executionModeRequested, 'SELF_ASK');
  assert.equal(assistant.dataset.executionModeEffective, 'AUTO');
  assert.match(assistant.children[0].textContent, /추가 확인 → 자동/);
  assert.match(assistant.children[0].textContent, /안전 제한/);
  assert.match(assistant.children[0].textContent, /질의 1/);
  context.render({executionMode:{requested:'STRIKE',effective:'STRIKE',reason:'user-strike',queryCount:1,httpAttempts:1}}, assistant);
  assert.equal(assistant.children.length, 1, 'events update one receipt');
});
test('unobserved receipt and poisoned modes never create a claimed actual strategy', () => {
  const {context,assistant} = harness();
  context.render({},assistant);
  context.render({executionMode:{requested:'BYPASS',effective:'<script>',reason:'private token'}}, assistant);
  assert.equal(assistant.children.length,0);
  assert.equal(assistant.dataset.executionModeEffective,undefined);
});
