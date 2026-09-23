const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createCapture}=require('../../../main/resources/static/assets/display/display-voice.js');
const flush=()=>new Promise(setImmediate);
function fixture(overrides={}) {
  const calls=[],nodes=[],timers=new Map();let stopped=0;
  const media={getTracks:()=>[{stop(){stopped++;}}],getAudioTracks:()=>[]};
  class Context {constructor(){this.audioWorklet={addModule:async path=>calls.push(path)};this.destination={};}async resume(){}async close(){calls.push('close');}createMediaStreamSource(){return {connect:()=>({connect:()=>({connect(){}})})};}createGain(){return {gain:{value:1}};}}
  class Worklet {constructor(){this.port={postMessage(){},onmessage:null};nodes.push(this);}disconnect(){}}
  const client={state:{audioAvailable:true},async beginVoice(){calls.push('start');},async voiceChunk(sequence,pcm){calls.push(['chunk',sequence,pcm.length]);},async endVoice(){calls.push('stop');},...overrides.client};
  const env={isSecureContext:true,navigator:{mediaDevices:{getUserMedia:async()=>media}},AudioContext:Context,AudioWorkletNode:Worklet,btoa, ...overrides.env};
  const capture=createCapture({client,env,setTimer(fn){timers.set(1,fn);return 1;},clearTimer(id){timers.delete(id);},...overrides.options});
  return {capture,client,env,calls,nodes,timers,media,stopped:()=>stopped};
}
test('explicit capture waits for ready, sends ordered PCM once and stop closes tracks',async()=>{
  const f=fixture();assert.deepEqual(f.calls,[]);await f.capture.start();assert.equal(f.capture.state.phase,'LISTENING');
  f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();
  assert.deepEqual(f.calls.find(Array.isArray),['chunk',0,10240]);await f.capture.stop();assert.equal(f.stopped(),1);assert.equal(f.calls.filter(x=>x==='stop').length,1);assert.equal(f.timers.size,0);
});
test('unsupported or denied microphone never starts remote audio',async()=>{
  const f=fixture({env:{isSecureContext:false}});await f.capture.start();assert.equal(f.calls.length,0);
  const g=fixture({env:{navigator:{mediaDevices:{getUserMedia:async()=>{throw Object.assign(Error(),{name:'NotAllowedError'});}}}}});await g.capture.start();assert.equal(g.calls.includes('start'),false);assert.equal(g.capture.state.phase,'ERROR');
});
test('late permission after page hide is stopped without remote start',async()=>{
  let done;const f=fixture({env:{navigator:{mediaDevices:{getUserMedia:()=>new Promise(r=>done=r)}}}});const start=f.capture.start();await f.capture.stop();done(f.media);await start;
  assert.equal(f.stopped(),1);assert.equal(f.calls.includes('start'),false);
});
test('failed chunk is never replayed and queue overflow closes the capture',async()=>{
  const f=fixture({client:{async voiceChunk(){throw Error('synthetic');}}});await f.capture.start();f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();assert.equal(f.stopped(),1);assert.equal(f.capture.state.phase,'ERROR');
  let release;const g=fixture({client:{voiceChunk:()=>new Promise(r=>release=r)}});await g.capture.start();for(let i=0;i<34;i++)g.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();release();assert.equal(g.stopped(),1);assert.equal(g.calls.filter(x=>x==='stop').length,1);
});

test('a brief slow chunk response retains ordered audio without reopening the provider',async()=>{
  let release;const sent=[];
  const f=fixture({client:{voiceChunk(sequence){sent.push(sequence);return sequence===0?new Promise(r=>release=r):Promise.resolve();}}});
  await f.capture.start();
  for(let i=0;i<4;i++)f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});
  await flush();assert.equal(f.stopped(),0);assert.equal(f.capture.state.phase,'LISTENING');
  release();await flush();assert.deepEqual(sent,[0,1,2,3]);assert.equal(f.calls.filter(x=>x==='start').length,1);
  await f.capture.stop();assert.equal(f.stopped(),1);
});
test('server readiness failure closes tracks and never creates an audio worklet node',async()=>{
  const f=fixture({client:{async beginVoice(){throw Error('unavailable');}}});await f.capture.start();assert.equal(f.stopped(),1);assert.equal(f.nodes.length,0);assert.equal(f.capture.state.phase,'ERROR');
});
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
const id='12345678-1234-4234-8234-123456789abc';
const view=extra=>({assistId:id,epoch:1,version:1,ready:true,state:'RUNNING',audioAvailable:true,audioState:'READY',...extra});
function transport(handler){
  const calls=[],timers=new Map();let n=0;
  const client=createClient({uuid:()=>id,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:k=>timers.delete(k),fetchImpl:async(url,options)=>{
    const route=url.replace('/api/assist/display/','');calls.push({route,body:JSON.parse(options.body)});return {ok:true,headers:{get:()=>null},json:async()=>handler(route,JSON.parse(options.body))};}});
  return {client,calls,timers};
}
test('voice final uses server correlation, ignores unrelated or older cards, preserves text after stop',async()=>{
  let next=view();const f=transport(route=>route==='audio/chunk'?next:route==='audio/stop'?view({epoch:2,audioState:'STOPPED'}):view());
  f.client.start();await flush();await f.client.beginVoice();f.client.setMessage('typed');assert.equal(f.client.canSubmit(),false);
  const card=requestId=>({requestId,text:'공개 답변',detailPages:['공개 답변'],sourceTitles:[],kind:'RAG'});
  next=view({version:3,voiceRequestId:'assist-current',card:card('assist-other')});await f.client.voiceChunk(0,'AAA=');assert.equal(f.client.state.result,null);
  next=view({version:4,voiceRequestId:'assist-current',card:card('assist-current')});await f.client.voiceChunk(1,'AAA=');assert.equal(f.client.state.result.answer,'공개 답변');const result=f.client.state.result;
  next=view({version:2,voiceRequestId:'assist-old',card:card('assist-old')});await f.client.voiceChunk(2,'AAA=');assert.equal(f.client.state.result,result);
  await f.client.endVoice();assert.equal(f.client.canSubmit(),true);assert.equal(f.calls.filter(c=>c.route==='input').length,0);f.client.dispose();
});
test('late audio-start completion after stop cannot reactivate voice or send a chunk',async()=>{
  let release;const f=transport(route=>route==='audio/start'?new Promise(r=>release=r):view());
  f.client.start();await flush();const start=f.client.beginVoice();await flush();await f.client.endVoice();release(view());await assert.rejects(start,/voice-cancelled/);
  await assert.rejects(f.client.voiceChunk(0,'AAA='),/audio-not-ready/);assert.equal(f.calls.filter(c=>c.route==='audio/chunk').length,0);f.client.dispose();
});
test('a restarted capture adopts its returned epoch and finish remains explicit',async()=>{
  const f=transport(route=>route==='audio/start'?view({epoch:2}):view({epoch:2,audioFinished:true}));
  await f.client.beginVoice();await f.client.voiceChunk(0,'AAA=');await f.client.endVoice({finish:true});
  assert.equal(f.calls.find(c=>c.route==='audio/chunk').body.epoch,2);
  assert.equal(f.calls.find(c=>c.route==='audio/stop').body.finish,true);assert.equal(f.client.state.audioFinished,true);f.client.dispose();
});

test('control keeps the last hint and rolling text while waiting for the next hint',async()=>{
 const timers=new Map();let n=0,next;
 const caption={utteranceId:'u1',revision:1,isFinal:true,text:'첫 문장\n다음 문장',rolling:true,expiresAt:Number.MAX_SAFE_INTEGER};
 const make=(version,card)=>view({version,role:'STANDALONE',hintsEnabled:true,audioState:'STREAMING',audioRenewAfterMs:65000,caption,captionTtlMs:Number.MAX_SAFE_INTEGER,cardTtlMs:14000,card});
 next=make(1,{requestId:'hint-one',kind:'CUE',text:'힌트1'});
 const client=createClient({transcription:true,standalone:true,uuid:()=>id,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:k=>timers.delete(k),fetchImpl:async()=>({ok:true,headers:{get:()=>null},json:async()=>next})});
 client.start();await flush();assert.equal(client.state.hint.text,'힌트1');assert.equal(client.state.audioRenewAfterMs,65000);
 const timer=[...timers.values()].find(t=>t.ms===15000);assert.equal(timer,undefined);assert.equal(client.state.hint.text,'힌트1');assert.equal(client.state.caption,caption);assert.equal(client.state.audioState,'STREAMING');
 next=make(2,{requestId:'hint-two',kind:'CUE',text:'힌트2'});await client.hints(true);assert.equal(client.state.hint.text,'힌트2');assert.equal([...timers.values()].filter(t=>t.ms===15000).length,0);client.dispose();assert.equal([...timers.values()].filter(t=>t.ms===15000).length,0);
});

test('quota limit suspends only provider traffic and keeps microphone until explicit stop',async()=>{
 let outbound=0;const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},async voiceChunk(){outbound++;throw Error('asr_quota_exceeded');}}});
 await f.capture.start();f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();assert.equal(f.stopped(),0);assert.equal(f.capture.state.phase,'LISTENING');assert.equal(f.capture.state.sttPausedReason,'asr_quota_exceeded');
 for(let i=0;i<100;i++)f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();assert.equal(outbound,1);assert.equal(f.capture.state.droppedAudioMs,24000);assert.equal(f.stopped(),0);await f.capture.stop();assert.equal(f.stopped(),1);
});
