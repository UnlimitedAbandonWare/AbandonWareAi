import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { createSession } from '../../../main/resources/soniox-sidecar/session.mjs';

class FakeSdk extends EventEmitter {
  sent = []; closed = false;
  async connect() { this.emit('connected'); }
  sendAudio(bytes) { this.sent.push(Buffer.from(bytes)); }
  close() { this.closed = true; }
}
async function fixture(options = {}) {
  const sdk = new FakeSdk(), events = [], errors = [];
  const session = createSession({ sdk, emit: e => events.push(e), fail: e => errors.push(e), ...options });
  await session.start();
  return { sdk, session, events, errors };
}
test('corrected partial suffix never becomes final until endpoint; repeated endpoint is empty', async () => {
  const f = await fixture();
  try {
    f.sdk.emit('result', { tokens: [{ text: '보증 ', is_final: true }, { text: '기', is_final: false }] });
    f.sdk.emit('result', { tokens: [{ text: '기간은?', is_final: true }, { text: '<end>', is_final: true }] });
    f.sdk.emit('endpoint');
    assert.deepEqual(f.events.filter(e => e.type === 'transcript').map(e => [e.text, e.final]), [['보증 기', false], ['보증 기간은?', true]]);
    f.sdk.emit('endpoint');
    assert.equal(f.events.filter(e => e.final).length, 1);
  } finally { f.session.close(); }
});
test('PCM acknowledgement follows SDK acceptance; invalid sequence terminates without another send', async () => {
  const f = await fixture();
  f.session.audio(JSON.stringify({seq: 0, pcm: Buffer.alloc(640).toString('base64')}));
  assert.equal(f.sdk.sent.length, 1);
  assert.equal(f.events.at(-1).type, 'ack');
  assert.equal(f.events.at(-1).seq, 0);
  f.session.audio(JSON.stringify({seq: 2, pcm: Buffer.alloc(640).toString('base64')}));
  assert.deepEqual(f.errors, ['ASR_PROTOCOL_FAILED']);
  assert.equal(f.sdk.sent.length, 1);
  assert.equal(f.sdk.closed, true);
});
test('oversized audio, provider errors and late events are bounded and redacted', async () => {
  const f = await fixture();
  f.sdk.emit('error', new Error('private provider details'));
  f.sdk.emit('result', { tokens: [{ text: 'late', is_final: true }] });
  f.sdk.emit('endpoint');
  assert.deepEqual(f.errors, ['ASR_PROVIDER_FAILED']);
  assert.equal(f.events.length, 1);
  const g = await fixture();
  g.session.audio(JSON.stringify({seq: 0, pcm: Buffer.alloc(8000).toString('base64')}));
  assert.deepEqual(g.errors, ['ASR_PROTOCOL_FAILED']);
  assert.equal(g.sdk.sent.length, 0);
});
test('connection deadline closes a hung SDK and never emits ready', async () => {
  const sdk = new FakeSdk(); sdk.connect = () => new Promise(() => {});
  const events = [], errors = [];
  const session = createSession({sdk, emit: e=>events.push(e), fail:e=>errors.push(e), connectTimeoutMs:25});
  await session.start();
  assert.deepEqual(errors, ['ASR_CONNECT_TIMEOUT']);
  assert.equal(sdk.closed, true); assert.deepEqual(events, []);
});
test('wall deadline closes provider even with no PCM and close is idempotent', async () => {
  const f = await fixture({ wallTimeoutMs: 30 });
  await new Promise(r=>setTimeout(r,60));
  assert.deepEqual(f.errors, ['ASR_CAPTURE_LIMIT']);
  f.session.close(); f.session.close();
  assert.equal(f.sdk.closed, true);
});
test('provider processing backlog bounds outstanding PCM to five seconds', async () => {
  const f = await fixture();
  try {
    for(let seq=0;seq<252;seq++)f.session.audio(JSON.stringify({seq,pcm:Buffer.alloc(640).toString('base64')}));
    assert.deepEqual(f.errors,['ASR_BACKPRESSURE']);
    assert.equal(f.sdk.sent.length,250);
  } finally {f.session.close();}
});
test('observed processing progress frees capacity; unknown progress cannot', async () => {
  const f = await fixture();
  try {
    for(let seq=0;seq<250;seq++)f.session.audio(JSON.stringify({seq,pcm:Buffer.alloc(640).toString('base64')}));
    f.sdk.emit('result',{tokens:[],total_audio_proc_ms:5000});
    f.session.audio(JSON.stringify({seq:250,pcm:Buffer.alloc(640).toString('base64')}));
    assert.deepEqual(f.errors,[]);assert.equal(f.sdk.sent.length,251);
  } finally {f.session.close();}
});
test('normal stop drains the last final before finished and releases SDK listeners', async () => {
  const f = await fixture();
  let complete;
  f.sdk.finish = () => new Promise(resolve => { complete = resolve; });
  f.session.audio('{"type":"finish"}');
  assert.deepEqual(f.errors, []);
  assert.equal(f.sdk.closed, false);
  f.sdk.emit('result', { tokens: [{text:'마지막 질문?',is_final:true}],total_audio_proc_ms:0 });
  f.sdk.emit('finished'); complete();
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(f.events.filter(e=>e.type==='transcript').map(e=>[e.text,e.final]), [['마지막 질문?',false],['마지막 질문?',true]]);
  assert.equal(f.events.at(-1).type, 'finished');
  assert.deepEqual(f.errors, []); assert.equal(f.sdk.closed,true);
  assert.equal(f.sdk.eventNames().length,0);
  f.sdk.emit('result',{tokens:[{text:'late',is_final:true}]});
  assert.equal(f.events.at(-1).type,'finished');
});
test('finish timeout and immediate cancel settle without a false completion', async () => {
  const f=await fixture({finishTimeoutMs:25}); f.sdk.finish=()=>new Promise(()=>{});
  f.session.audio('{"type":"finish"}');
  await new Promise(resolve=>setTimeout(resolve,50));
  assert.deepEqual(f.errors,['ASR_FINISH_TIMEOUT']);assert.equal(f.sdk.closed,true);
  assert.equal(f.events.some(e=>e.type==='finished'),false);
  const g=await fixture();let calls=0;g.sdk.finish=()=>{calls++;};g.session.close();
  assert.equal(calls,0);assert.equal(g.sdk.closed,true);assert.equal(g.sdk.eventNames().length,0);
});
test('provider error category survives the installed SDK raw boundary without private details', async () => {
  for(const [raw,expected] of [[{error_code:401,error_type:'unauthorized'},'ASR_AUTH_FAILED'],[{error_code:402,error_type:'insufficient_funds'},'ASR_QUOTA_EXCEEDED'],[{error_code:400,error_type:'invalid_request'},'ASR_AUDIO_FORMAT_INVALID']]) {
    const f=await fixture();const err=Object.assign(new Error('private details'),{raw});
    f.sdk.emit('error',err);
    assert.deepEqual(f.errors,[expected]);assert.equal(f.sdk.closed,true);
    assert.equal(JSON.stringify(f.events).includes('private'),false);
  }
});
test('token confidence and speaker metadata reach the existing transcript contract', async () => {
  const f=await fixture();
  try {
    f.sdk.emit('result',{tokens:[{text:'질문?',is_final:true,start_ms:0,end_ms:300,confidence:0.9,speaker:'2'}]});f.sdk.emit('endpoint');
    const final=f.events.find(e=>e.final);assert.equal(final.confidence,0.9);assert.equal(final.words[0].speaker,2);assert.equal(final.words[0].text,'질문?');
    assert.equal(final.words[0].start,0);assert.equal(final.words[0].end,0.3);
  } finally {f.session.close();}
});
test('error_type-only SDK result is failure even when marked finished with empty tokens', async () => {
  const f=await fixture();
  f.sdk.emit('result',{tokens:[],finished:true,raw:{error_type:'insufficient_funds'}});
  assert.deepEqual(f.errors,['ASR_QUOTA_EXCEEDED']);assert.equal(f.events.some(e=>e.type==='progress'),false);
});
