const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('main/resources/static/js/chat.js', 'utf8');
function node() {
  return {dataset:{}, children:[], textContent:'', setAttribute(){}, appendChild(child){this.children.push(child);}, addEventListener(type, handler){this[type]=handler;}};
}
function setup() {
  const context = vm.createContext({
    document:{createElement:node,getElementById(){return null;}}, dom:{messageInput:{value:'saved draft'},sendBtn:{}},
    setMessageContent(target, role, text){target.textContent=text; target.dataset.ariaText=text;},
    syncSendButtonState(){}, startNewChatSession(){}, refreshSessionList(){}, sessionStorage:{setItem(){}}
  });
  vm.runInContext(source.slice(source.indexOf('function chatFailureMeta('), source.indexOf('function safeUnknownEventName(')), context);
  return context;
}
function classify(ctx, status, body) { return ctx.classifyChatFailure({status,contentType:'application/json',boundedText:JSON.stringify(body)}); }
test('duplicate safe codes and errorCode retain session recovery; conflicting or private values are rejected',()=>{
  const ctx=setup();
  for (const body of [{code:'session_forbidden',error:'session_forbidden'},{errorCode:'session_forbidden'}]) {
    const meta=classify(ctx,403,body);assert.equal(meta.serverCode,'session_forbidden');assert.equal(meta.nextAction,'choose_or_start_session');assert.equal(meta.retryable,false);
  }
  for (const body of [{code:'session_forbidden',error:'forbidden'},{code:'private sentinel'},{code:{nested:'session_forbidden'}}]) assert.equal(classify(ctx,403,body).serverCode,null);
  assert.equal(ctx.boundedFailureCode({boundedText:'session_forbidden',truncated:true}),null);
});
test('service authentication, provider authentication, rate and quota have distinct actions',()=>{
  const ctx=setup();
  assert.equal(classify(ctx,401,{}).nextAction,'sign_in');
  assert.equal(classify(ctx,401,{code:'provider_unauthorized'}).nextAction,'choose_model');
  assert.equal(classify(ctx,429,{}).failureKind,'rate_limited');
  assert.equal(classify(ctx,429,{code:'quota_exceeded'}).nextAction,'check_quota');
  assert.equal(classify(ctx,403,{}).nextAction,'check_access');
});
test('protocol failure requires inspecting the existing run, never blind new generation',()=>{
  const ctx=setup();
  for(const protocolCode of ['unexpected_content_type','stream_incomplete','sse_event_too_large','malformed_terminal_event']) {
    const meta=ctx.classifyChatFailure({protocolCode});assert.equal(meta.nextAction,'check_run');assert.equal(meta.retryable,false);
  }
});
test('received answer survives an error and recovery notices never expose raw server text',()=>{
  const ctx=setup(), assistant=node();assistant.dataset.ariaText='already received';assistant.textContent='already received';
  const meta=classify(ctx,503,{error:'private sentinel'});ctx.renderChatFailureNotice(assistant,meta);
  assert.equal(assistant.textContent,'already received');assert.equal(assistant.children.length,1);
  assert.ok(assistant.children[0].children[0].textContent.includes('질문'));
  assert.ok(!JSON.stringify(assistant).includes('private sentinel'));
  assert.equal(ctx.dom.messageInput.value,'saved draft');
});
test('empty failed answer shows Korean guidance and explicit session recovery',()=>{
  const ctx=setup(), assistant=node();const meta=classify(ctx,403,{code:'session_forbidden'});
  ctx.renderChatFailureNotice(assistant,meta);assert.match(assistant.textContent,/이 대화에 접근/);
  assert.equal(assistant.children[0].children[0].textContent,'새 대화');
});
