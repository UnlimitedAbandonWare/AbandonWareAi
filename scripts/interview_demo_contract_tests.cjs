// Synthetic client contracts; actual Spring, provider and hardware proof are separate.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const file = path.resolve(__dirname, '../main/resources/static/assets/interview/interview-core.js');
test('interview client exists', () => assert.ok(fs.existsSync(file), 'interview client missing'));
if (!fs.existsSync(file)) return;
const core = require(file);
test('short card never silently cuts a meaning-bearing sentence', () => {
  assert.equal(core.cardText('조건: 환불 불가', 'hint'), '조건: 환불 불가');
  assert.throws(() => core.cardText('가'.repeat(121), 'answer'), /card-too-long/);
  assert.equal(Array.from(core.cardText('😀'.repeat(120), 'question')).length, 120);
  assert.throws(() => core.cardText('  ', 'hint'), /empty-card/);
  assert.throws(() => core.cardText('text', 'admin'), /invalid-kind/);
});
test('server acceptance cannot become receiver or hardware success', () => {
  const s = {epoch:1,version:4,state:'RUNNING',outputConnections:1,card:{expiresAt:20000},metrics:{lastOutputAckEpoch:1,lastOutputAckVersion:0}};
  assert.equal(core.deliveryState(s, {epoch:1,version:4}, 1000), 'WAITING_RECEIPT');
  s.metrics.lastOutputAckVersion = 3;
  assert.equal(core.deliveryState(s, {epoch:1,version:4}, 1000), 'WAITING_RECEIPT');
  s.metrics.lastOutputAckVersion = 4;
  assert.equal(core.deliveryState(s, {epoch:1,version:4}, 1000), 'RECEIVER_ACK');
  s.metrics.lastOutputAckEpoch = 2;
  assert.equal(core.deliveryState(s, {epoch:1,version:4}, 1000), 'WAITING_RECEIPT');
  s.state = 'PAUSED';
  assert.equal(core.deliveryState(s, {epoch:1,version:4}, 1000), 'DISCONNECTED');
});
test('expired, missing and superseded cards cannot be presented as delivered', () => {
  const s={epoch:1,version:5,state:'RUNNING',outputConnections:1,card:{expiresAt:10},metrics:{}};
  assert.equal(core.deliveryState(s,{epoch:1,version:4},20),'EXPIRED');
  assert.equal(core.deliveryState({...s,card:null},{epoch:1,version:4},1),'EXPIRED');
  assert.equal(core.deliveryState({...s,epoch:2},{epoch:1,version:4},1),'DISCONNECTED');
});
test('debug payload is explicitly allowlisted and bounded', () => {
  const row=core.debugEvent('rag.result',{httpStatus:200,roundTripMs:12,sourceCount:2,message:'private',answer:'private',cookie:'private',requestId:'r-1'});
  assert.equal(row.httpStatus,200);
  assert.ok(!JSON.stringify(row).includes('private'));
  assert.equal(core.debugEvent('rag.error',{reason:'<script>raw body</script>'}).reason,undefined);
});
test('same-origin receiver link uses fragment and never includes source text', () => {
  const url=core.receiverUrl('http://192.168.1.4:18080','12345678-1234-1234-1234-123456789012',2);
  assert.equal(new URL(url).pathname,'/assets/display/receiver.html');
  assert.equal(new URL(url).search,'');
  assert.ok(new URL(url).hash.includes('epoch=2'));
  assert.throws(()=>core.receiverUrl('http://localhost:18080','bad',1));
});
test('HTTP LAN crypto without randomUUID still supports exactly one sync operation', async () => {
  const source=fs.readFileSync(path.resolve(__dirname,'../main/resources/static/assets/display/display-core.js'),'utf8');
  const sandbox={module:{exports:{}},URL,AbortController,performance,setTimeout,clearTimeout,crypto:{getRandomValues:require('node:crypto').webcrypto.getRandomValues.bind(require('node:crypto').webcrypto)}};
  vm.runInNewContext(source,sandbox);
  let count=0;
  const c=sandbox.module.exports.createClient({fetchImpl:async (_url,options)=>{
    count++; assert.match(options.headers['Idempotency-Key'],/^[a-f0-9-]{36}$/i);
    return {ok:true,status:200,headers:{get:()=>null},json:async()=>({content:'fixture',sessionId:1,evidence:[]})};
  }});
  c.setMessage('synthetic'); await c.submit(); await c.submit(); assert.equal(count,1); assert.equal(c.state.phase,'RESULT');
});
