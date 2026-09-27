'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('main/resources/static/js/chat.js', 'utf8');
const ctx = vm.createContext({ document: { getElementById(){return null;} } });
vm.runInContext(source.slice(source.indexOf('function chatFailureMeta('), source.indexOf('function safeUnknownEventName(')), ctx);
const classify = (body,status=503) => ctx.classifyChatFailure({status,contentType:'application/json',boundedText:JSON.stringify(body)});
test('machine reason survives prose and structured error descriptions',()=>{
 for(const body of [
  {reasonCode:'model_unavailable',content:'Model is currently unavailable.'},
  {reasonCode:'model_unavailable',error:{message:'private sentinel'}},
  {reasonCode:'model_unavailable',reason:'private sentinel'}
 ]) assert.equal(classify(body).serverCode,'model_unavailable');
});
test('conflicting machine fields fail closed while legacy categorical errors remain supported',()=>{
 for(const body of [
  {reasonCode:'model_unavailable',code:'backend_timeout'},
  {reasonCode:'model_unavailable',code:'unknown_private_code'},
  {code:{private:'model_unavailable'}},
  {code:'session_forbidden',error:'forbidden'}
 ]) assert.equal(classify(body).serverCode,null);
 assert.equal(classify({error:'chat_rate_limited'},429).serverCode,'chat_rate_limited');
});
test('deadline failures preserve code and inspect existing execution without regeneration',()=>{
 for(const code of ['public_request_deadline_exhausted','request_deadline_exhausted']){
  const meta=classify({reasonCode:code},408);
  assert.equal(meta.serverCode,code);assert.equal(meta.failureKind,'timeout');
  assert.equal(meta.nextAction,'check_run');assert.equal(meta.retryable,false);
 }
});
test('runtime, auth and storage errors do not propose changing the model',()=>{
 for(const code of ['backend_unavailable','provider_unauthorized','provider_not_configured','local_model_store_unavailable','local_capacity_exceeded','gpu_device_lost','model_circuit_open','model_request_invalid']){
  const meta=classify({reasonCode:code});
  assert.equal(meta.serverCode,code);assert.equal(meta.nextAction,'check_service');assert.equal(meta.retryable,false);
 }
 assert.equal(classify({reasonCode:'model_unavailable'}).nextAction,'choose_model');
 assert.equal(classify({reasonCode:'quota_exceeded'}).nextAction,'check_quota');
});
test('stream runtime failure contract matches HTTP and preserves cancellation',()=>{
 for(const code of ['local_model_store_unavailable','local_capacity_exceeded','backend_unavailable','backend_timeout','request_cancelled']){
  const meta=ctx.classifyChatFailure({streamCode:code});
  assert.equal(meta.serverCode,code);assert.equal(meta.retryable,false);
 }
});
test('malformed, oversized/truncated and private codes never become public text',()=>{
 assert.equal(ctx.boundedFailureCode({contentType:'application/json',boundedText:'{"reasonCode":'}),null);
 assert.equal(ctx.boundedFailureCode({contentType:'application/json',boundedText:'{"reasonCode":"model_unavailable"}',truncated:true}),null);
 assert.equal(classify({reasonCode:'private sentinel'}).serverCode,null);
});
