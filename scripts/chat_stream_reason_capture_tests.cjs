const assert=require('node:assert/strict');
const {test}=require('node:test');
const {safeMetadata}=require('./chat_rag_golden_browser.js');
const event=(type,payload)=>`event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`;

test('current error DTO data carries the terminal machine reason',()=>{
  assert.equal(safeMetadata(event('error',{type:'error',data:'backend_unavailable'})).reasonCode,'backend_unavailable');
});
test('compatible terminal code envelope carries the reason',()=>{
  assert.equal(safeMetadata(event('stream_failed',{type:'stream_failed',code:'backend_timeout'})).reasonCode,'backend_timeout');
});
test('plain JSON admission rejection preserves the bounded reason',()=>{
  assert.equal(safeMetadata(JSON.stringify({reasonCode:'public_body_executor_saturated'})).reasonCode,'public_body_executor_saturated');
});
test('answer data and arbitrary error prose stay outside metadata',()=>{
  assert.equal(safeMetadata(event('token',{type:'token',data:'backend_unavailable'})).reasonCode,undefined);
  assert.equal(safeMetadata(event('error',{type:'error',data:'private response text'})).reasonCode,undefined);
  assert.equal(safeMetadata(event('status',{type:'status',statusSignal:{code:'web_search_running'}})).reasonCode,undefined);
});
test('JSON fallback preserves the existing metadata allowlist',()=>{
  const metadata=safeMetadata(JSON.stringify({reasonCode:'chat_admission_exceeded',debugBody:'synthetic private prose',data:'synthetic answer body'}));
  assert.deepEqual(metadata,{reasonCode:'chat_admission_exceeded'});
});
