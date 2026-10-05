const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('main/resources/static/js/chat.js', 'utf8');
function presentation(usage) {
  const context = vm.createContext({});
  const start = source.indexOf('function presentContextUsage(');
  assert.ok(start >= 0, 'public context presenter must exist');
  vm.runInContext(source.slice(start, source.indexOf('function renderContextUsage(', start)), context);
  return context.presentContextUsage(usage);
}
test('unknown context maximum stays unknown and never invents a percentage', () => {
  const view = presentation({inputTokens:120, countMethod:'char_estimate'});
  assert.equal(view.percent, null);
  assert.match(view.contextText, /120.*추정.*한도 확인 안 됨/);
  assert.doesNotMatch(view.contextText, /0%|100%/);
});
test('observed model cap produces bounded accessible estimate, not output usage', () => {
  const view = presentation({inputTokens:1000, contextLimitTokens:4000,
    countMethod:'char_estimate', limitSource:'model_spec_snapshot', outputTokens:3999});
  assert.equal(view.percent, 25);
  assert.match(view.contextText, /1,000.*4,000.*추정/);
  assert.equal(presentation({inputTokens:5000, contextLimitTokens:4000,
    limitSource:'model_spec_snapshot'}).percent, 100);
  assert.equal(presentation({inputTokens:-1, contextLimitTokens:4000}).percent, null);
  assert.equal(presentation({inputTokens:0, contextLimitTokens:4000}).percent, null);
});
test('compression execution requires actual prompt inclusion before showing inclusion', () => {
  const base = {memoryBeforeChars:800, memoryAfterChars:300,
    memoryCompressionActivated:true, memoryCompressionReason:'overflow'};
  assert.match(presentation(base).compressionText, /전달 확인 안 됨/);
  assert.match(presentation({...base,memoryIncluded:true}).compressionText, /프롬프트에 포함/);
  assert.match(presentation({...base,memoryIncluded:false}).compressionText, /미포함/);
  assert.match(presentation({...base,memoryCompressionReason:'exception_original_returned'}).compressionText, /실패.*원문/);
});
test('public summary starts open outside the gated raw diagnostics', () => {
  const html = fs.readFileSync('main/resources/templates/chat-ui.html', 'utf8');
  assert.ok(/<details[^>]+id="contextSummary"[^>]+open/.test(html));
  assert.ok(/id="contextGauge"[^>]+role="progressbar"/.test(html));
  assert.ok(/data-testid="chat-diagnostics"/.test(html));
  assert.ok(/chat\.contextSummary\.collapsed/.test(source));
});
