const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createClient, createCommitter } = require('../main/resources/static/assets/display/display-conversate.js');
const id = '12345678-1234-4234-8234-123456789abc';
const view = (extra = {}) => ({ assistId: id, epoch: 1, ready: true, state: 'RUNNING', reason: 'started', card: null, ...extra });
const response = (body, status = 200, retry = null) => ({ ok: status === 200, status, headers: { get: () => retry }, json: async () => body });
const flush = () => new Promise(setImmediate);
test('normal cue disappears after 15 seconds without starting a new conversation', async () => {
  let req;
  const f=fixture(r=>{if(r.route==='input')req=r.body.requestId;return Promise.resolve(response(view(req?{requestId:req,cardTtlMs:15000,card:{requestId:req,text:'짧은 힌트',sourceTitles:[]}}:{})));});
  f.c.start();await flush();f.c.setMessage('질문');await f.c.submit();assert.equal(f.c.state.phase,'RESULT');
  const connects=f.calls.filter(r=>r.route==='bootstrap').length;
  await f.run(15000);assert.equal(f.c.state.result,null);assert.equal(f.c.state.message,'');assert.equal(f.c.state.phase,'IDLE');
  assert.equal(f.calls.filter(r=>r.route==='bootstrap').length,connects);f.c.dispose();
});
test('NO_CUE completes quietly and a gate error is distinct', async () => {
  for(const reason of ['NO_CUE','CUE_GATE_UNAVAILABLE','GENERATION_INVALID_OUTPUT']){
    const f=fixture(r=>Promise.resolve(response(view(r.route==='input'?{requestId:r.body.requestId,processing:false,reason}:{}))));
    f.c.start();await flush();f.c.setMessage('인사');await f.c.submit();assert.equal(f.c.state.result,null);
    assert.equal(f.c.state.phase,reason==='NO_CUE'?'IDLE':'ERROR');f.c.dispose();
  }
});
test('expiring an old cue preserves a newly typed draft', async () => {
  const f=fixture(r=>Promise.resolve(response(view(r.route==='input'?{requestId:r.body.requestId,cardTtlMs:15000,card:{requestId:r.body.requestId,text:'합성 힌트',sourceTitles:[]}}:{}))));
  f.c.start();await flush();f.c.setMessage('첫 질문');await f.c.submit();f.c.setMessage('작성 중인 다음 질문');
  await f.run(15000);assert.equal(f.c.state.result,null);assert.equal(f.c.state.message,'작성 중인 다음 질문');f.c.dispose();
});
function fixture(handler, extra = {}) {
  const timers = new Map(), calls = []; let time = 0, next = 1;
  const c = createClient({ now: () => time, uuid: () => id, setTimer(fn, ms) { const n = next++; timers.set(n, { fn, ms }); return n; }, clearTimer(n) { timers.delete(n); },
    fetchImpl(url, options) { const request = { route: url.split('/').at(-1), options, body: JSON.parse(options.body) }; calls.push(request); return handler(request); }, ...extra });
  return { c, calls, timers, advance(ms) { time += ms; }, async run(ms) { const t = [...timers.entries()].find(([, t]) => t.ms === ms); assert.ok(t, 'scheduled timer'); timers.delete(t[0]); t[1].fn(); await flush(); } };
}
test('direct verification belongs to one input and completes without replay in transcription mode', async () => {
  const f=fixture(r=>Promise.resolve(response(view({version:1,captionTtlMs:0,cardTtlMs:15000,hintsEnabled:true,
    ...(r.route==='input'?{requestId:r.body.requestId,processing:false,reason:'OPENAI_DIRECT_ERROR',audioRuntime:{cue:{status:'OPENAI_DIRECT_ERROR'}}}:{})}))),{transcription:true});
  f.c.start();await flush();f.c.setMessage('합성 질문');
  const done=f.c.submit({verificationMode:'openai-direct'});await flush();
  assert.equal(f.calls.find(r=>r.route==='input').body.verificationMode,'openai-direct');
  assert.equal(f.c.state.phase,'ERROR');await done;
  await f.run(1000);assert.equal(f.calls.filter(r=>r.route==='input').length,1);
  f.c.setMessage('다음 일반 질문');f.c.submit();await flush();
  assert.equal(f.calls.filter(r=>r.route==='input')[1].body.verificationMode,undefined);f.c.dispose();
});
test('auto bootstrap has cookie identity, no auth and no microphone or question payload', async () => {
  const f = fixture(() => Promise.resolve(response(view()))); f.c.start(); await flush();
  assert.equal(f.c.state.connection, 'READY'); assert.equal(f.calls.length, 1); assert.equal(f.calls[0].route, 'bootstrap');
  assert.equal(f.calls[0].options.credentials, 'same-origin'); assert.equal(f.calls[0].options.headers['X-Display-Client'], '1');
  assert.equal(f.calls[0].options.headers.Authorization, undefined); assert.equal(f.calls[0].body.text, undefined); f.c.dispose();
});

test('remote stop refreshes the current capture epoch before stopping', async () => {
  let epoch = 1;
  const f = fixture(r => Promise.resolve(response(view({ epoch }), r.route === 'stop' && r.body.epoch !== epoch ? 409 : 200)));
  f.c.start(); await flush(); epoch = 2;
  await f.c.stopAudio();
  const stops = f.calls.filter(r => r.route === 'stop');
  assert.equal(stops.length, 1); assert.equal(stops[0].body.epoch, 2);
  assert.equal(f.calls.filter(r => r.route === 'bootstrap').length, 2);
  assert.equal(f.calls.filter(r => r.route === 'start' || r.route === 'input').length, 0);
  f.c.dispose();
});

test('remote stop does not send a stale request when refresh fails', async () => {
  let unavailable = false;
  const f = fixture(r => Promise.resolve(response(view(), unavailable && r.route === 'bootstrap' ? 503 : 200)));
  f.c.start(); await flush(); unavailable = true;
  await assert.rejects(f.c.stopAudio());
  assert.equal(f.calls.filter(r => r.route === 'stop').length, 0); f.c.dispose();
});
test('commit waits for same in-flight bootstrap and one final input reaches same session', async () => {
  let release; const bootstrap = new Promise(r => { release = r; });
  const f = fixture(r => r.route === 'bootstrap' ? bootstrap : Promise.resolve(response(view({ reason: 'QUESTION', processing: true, requestId: r.body.requestId }))));
  f.c.start(); f.c.setMessage('짧게 요약해 줘'); const done = f.c.submit(); f.c.submit();
  assert.equal(f.calls.length, 1); release(response(view())); await flush();
  assert.equal(f.calls.filter(r => r.route === 'input').length, 1); assert.equal(f.calls[1].body.text, '짧게 요약해 줘');
  assert.equal(f.calls[1].body.assistId, id); assert.deepEqual(f.calls[1].body.eventOrder, ['submit']);
  f.c.cancel(); await done; f.c.dispose();
});
test('same-page poll delivers bounded card and does not replay after fresh page construction', async () => {
  const card = { requestId: id, text: '짧은 답변', detailPages: ['짧은 답변'], sourceTitles: ['공개 출처'], kind: 'RAG' };
  const f = fixture(r => Promise.resolve(response(view(r.route === 'poll' ? { card, requestId: id, reason: 'RAG_ANSWER', processing: false, processingMs: 12 } : r.route === 'input' ? { requestId: id, reason: 'QUESTION', processing: true } : {}))));
  f.c.start(); await flush(); f.c.setMessage('질문'); const done = f.c.submit(); await flush(); await f.run(1000); await done;
  assert.equal(f.c.state.phase, 'RESULT'); assert.equal(f.c.state.result.answer, '짧은 답변'); assert.equal(f.c.state.metrics.processingMs, 12);
  await f.c.submit(); assert.equal(f.calls.filter(r => r.route === 'input').length, 1); f.c.dispose();
  const reload = fixture(() => Promise.resolve(response(view({ card })))); reload.c.start(); await flush();
  assert.equal(reload.c.state.phase, 'RESULT'); assert.equal(reload.c.state.message, ''); assert.equal(reload.calls.filter(r => r.route === 'input').length, 0); reload.c.dispose();
});
test('input POST failure is not retried by reconnect or duplicate change', async () => {
  const f = fixture(r => r.route === 'input' ? Promise.reject(Error('private error')) : Promise.resolve(response(view())));
  f.c.start(); await flush(); f.c.setMessage('질문'); await f.c.submit(); await f.run(1000); await f.c.submit();
  assert.equal(f.calls.filter(r => r.route === 'input').length, 1); assert.equal(f.c.state.error.code, 'outcome-unknown'); assert.ok(!JSON.stringify(f.c.state).includes('private error')); f.c.dispose();
});
test('hung bootstrap has deadline and hidden page stops retry loop', async () => {
  const f = fixture(() => new Promise(() => {})); f.c.start(); await f.run(4000);
  assert.equal(f.c.state.connection, 'RECONNECTING'); f.c.pause(); assert.equal(f.timers.size, 0); f.c.dispose();
});
test('server preparing recovers automatically and rate retry is respected', async () => {
  let ready = false; const f = fixture(() => Promise.resolve(ready ? response(view()) : response({}, 503)));
  f.c.start(); await flush(); assert.equal(f.c.state.connection, 'RECONNECTING'); ready = true; await f.run(1000); assert.equal(f.c.state.connection, 'READY'); f.c.dispose();
  const r = fixture(q => Promise.resolve(q.route === 'input' ? response({}, 429, '12') : response(view())));
  r.c.start(); await flush(); r.c.setMessage('질문'); await r.c.submit(); r.c.setMessage('다른 질문'); assert.equal(r.c.canSubmit(), false); r.advance(12001); assert.equal(r.c.canSubmit(), true); r.c.dispose();
});
test('composer draft, cancel, composition and duplicate commits never duplicate generation', async () => {
  const f = fixture(r => Promise.resolve(response(view({ requestId: id, reason: 'QUESTION', processing: true }))));
  const event = value => ({ target: { value } });
  const commit = createCommitter({ setMessage: f.c.setMessage, submit: f.c.submit, record: f.c.recordEvent });
  commit.input(event('초안')); assert.equal(f.calls.length, 0); commit.cancel(); commit.change(event('초안')); assert.equal(f.calls.length, 0);
  commit.activate(); commit.compositionstart(); commit.input(event('확정 질문')); commit.change(event('확정 질문')); assert.equal(f.calls.length, 0);
  commit.compositionend(); commit.change(event('확정 질문')); await flush(); assert.equal(f.calls.filter(r => r.route === 'input').length, 1); f.c.dispose();
});
test('empty, cancel and an input-only draft cannot submit on reconnect', async () => {
  const f = fixture(() => Promise.resolve(response(view()))); f.c.setMessage(' '); assert.equal(await f.c.submit(), false);
  f.c.setMessage('미확정 초안'); f.c.start(); await flush(); f.c.pause(); f.c.start(); await flush(); assert.equal(f.calls.filter(r => r.route === 'input').length, 0); f.c.dispose();
});

for (const staleFails of [false, true]) test('resume ignores an earlier poll ' + (staleFails ? 'failure' : 'success'), async () => {
  const pending = [];
  const f = fixture(r => r.route === 'poll' ? new Promise(resolve => pending.push(resolve)) : Promise.resolve(response(view())));
  f.c.start(); await flush(); await f.run(1000);
  f.c.pause(); f.c.start(); await flush();
  assert.equal(pending.length, 2);
  pending[1](response(view({ epoch: 2 }))); await flush();
  pending[0](staleFails ? response({}, 404) : response(view({ epoch: 1, ready: false }))); await flush();
  assert.equal(f.c.state.connection, 'READY');
  await f.run(1000);
  assert.equal(f.calls.at(-1).route, 'poll'); assert.equal(f.calls.at(-1).body.epoch, 2);
  assert.equal(f.calls.filter(r => r.route === 'input').length, 0); f.c.dispose();
});

test('context reset posts the owned connection and applies the returned view', async () => {
  const f = fixture(r => Promise.resolve(response(view(r.route === 'context-reset' ? { reason: 'CONTEXT_RESET', version: 9 } : {}))));
  f.c.start(); await flush();
  const result = await f.c.contextReset();
  const calls = f.calls.filter(r => r.route === 'context-reset');
  assert.equal(calls.length, 1); assert.equal(calls[0].body.assistId, id); assert.equal(calls[0].body.epoch, 1);
  assert.equal(calls[0].body.clientId.length, 32); assert.equal(result.reason, 'CONTEXT_RESET');
  assert.equal(f.calls.filter(r => r.route === 'input').length, 0); f.c.dispose();
});

test('pause preserves a committed input and resume receives its card without replay', async () => {
  let accept;
  const card = { requestId: id, text: '합성 결과', detailPages: ['합성 결과'], sourceTitles: [], kind: 'RAG' };
  const f = fixture(r => r.route === 'input' ? new Promise(resolve => { accept = resolve; }) : Promise.resolve(response(view(r.route === 'poll' ? { card, requestId: id, reason: 'RAG_ANSWER', processing: false } : {}))));
  f.c.start(); await flush(); f.c.setMessage('합성 입력'); const done = f.c.submit(); await flush();
  f.c.pause(); accept(response(view({ requestId: id, reason: 'QUESTION', processing: true }))); await flush();
  assert.equal(f.c.state.phase, 'LOADING');
  f.c.start(); await flush(); await done;
  assert.equal(f.c.state.phase, 'RESULT'); assert.equal(f.c.state.result.answer, '합성 결과');
  assert.equal(f.calls.filter(r => r.route === 'input').length, 1); f.c.dispose();
});
