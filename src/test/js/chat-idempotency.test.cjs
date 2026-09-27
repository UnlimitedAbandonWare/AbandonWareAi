const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const {webcrypto}=require('node:crypto');
const source=fs.readFileSync('main/resources/static/js/chat.js','utf8');
test('logical payload retry retains a volatile key; separate sends and attach do not share it',()=>{
  const start=source.indexOf('const chatAdmissionKeys = new WeakMap();');
  const end=source.indexOf('async function streamChat(',start);
  assert.ok(start>=0&&end>start);
  const context=vm.createContext({crypto:webcrypto});
  vm.runInContext(source.slice(start,end)+';globalThis.headers=generationIdempotencyHeaders;',context);
  const first={message:'synthetic'},second={message:'synthetic'};
  const key=context.headers(first)['Idempotency-Key'];assert.match(key,/^[a-f0-9]{32}$/);
  assert.equal(key,context.headers(first)['Idempotency-Key']);assert.notEqual(key,context.headers(second)['Idempotency-Key']);assert.equal(Object.keys(context.headers({attach:true})).length,0);
  assert.equal(JSON.stringify(first),'{"message":"synthetic"}');
  assert.doesNotMatch(source.slice(start,end),/localStorage|sessionStorage|indexedDB/);
});
