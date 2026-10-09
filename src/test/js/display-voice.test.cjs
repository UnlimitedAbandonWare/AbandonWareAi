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

for(const outcome of ['success','event_owner_required','asr_quota_exceeded','asr_rate_limited','transport']) {
 test(`late renewal ${outcome} cannot affect a capture started after explicit stop`,async()=>{
  let settle,begins=0,ends=0;
  const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},
   beginVoice(){begins++;return begins===2?new Promise((resolve,reject)=>{settle=()=>outcome==='success'?resolve():reject(Object.assign(Error(outcome),{status:outcome==='event_owner_required'?403:503}));}):Promise.resolve();},
   async endVoice(){ends++;},async reconnect(){}}});
  await f.capture.start();const renewing=f.capture.reconnect();await flush();
  assert.equal(begins,2);await f.capture.stop();await f.capture.start();
  const before={ends,stopped:f.stopped(),timers:f.timers.size,events:f.capture.state.events.length,reconnects:f.capture.state.reconnects};
  settle();await renewing;
  assert.equal(f.capture.isActive(),true);assert.equal(f.capture.state.phase,'LISTENING');
  assert.equal(f.capture.state.errorCode,null);assert.equal(f.capture.state.sttPausedReason,null);
  assert.deepEqual({ends,stopped:f.stopped(),timers:f.timers.size,events:f.capture.state.events.length,reconnects:f.capture.state.reconnects},before);
  assert.equal(begins,3);await f.capture.stop();
 });
}

test('late renewal failure after stop alone leaves capture OFF without a retry',async()=>{
 let rejectRenewal,begins=0,ends=0;
 const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},
  beginVoice(){return ++begins===2?new Promise((_,reject)=>{rejectRenewal=reject;}):Promise.resolve();},
  async endVoice(){ends++;},async reconnect(){}}});
 await f.capture.start();const renewing=f.capture.reconnect();await flush();await f.capture.stop();
 const before={ends,stopped:f.stopped(),events:f.capture.state.events.length};
 rejectRenewal(Object.assign(Error('event_owner_required'),{status:403}));await renewing;
 assert.equal(f.capture.isActive(),false);assert.equal(f.capture.state.phase,'OFF');assert.equal(f.capture.state.errorCode,null);
 assert.equal(f.timers.size,0);assert.equal(begins,2);
 assert.deepEqual({ends,stopped:f.stopped(),events:f.capture.state.events.length},before);
});

test('the current renewal still fails explicitly for an owner permission error',async()=>{
 let begins=0;
 const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},
  async beginVoice(){if(++begins===2)throw Object.assign(Error('event_owner_required'),{status:403});},async reconnect(){}}});
 await f.capture.start();await f.capture.reconnect();
 assert.equal(f.capture.isActive(),false);assert.equal(f.capture.state.phase,'ERROR');
 assert.equal(f.capture.state.errorCode,'event_owner_required');assert.equal(f.stopped(),1);assert.equal(f.timers.size,0);
});

test('a quota pause awaiting server stop cannot change the next capture phase',async()=>{
 let rejectRenewal,releasePause,begins=0,ends=0;
 const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},
  beginVoice(){return ++begins===2?new Promise((_,reject)=>{rejectRenewal=reject;}):Promise.resolve();},
  endVoice(){return ++ends===2?new Promise(resolve=>{releasePause=resolve;}):Promise.resolve();},async reconnect(){}}});
 await f.capture.start();const renewing=f.capture.reconnect();await flush();
 rejectRenewal(Error('asr_rate_limited'));await flush();assert.ok(releasePause);
 await f.capture.stop();await f.capture.start();const before={ends,stopped:f.stopped(),timers:f.timers.size,events:f.capture.state.events.length};
 releasePause();await renewing;
 assert.equal(f.capture.isActive(),true);assert.equal(f.capture.state.phase,'LISTENING');assert.equal(f.capture.state.sttPausedReason,null);
 assert.deepEqual({ends,stopped:f.stopped(),timers:f.timers.size,events:f.capture.state.events.length},before);await f.capture.stop();
});

for(const reason of ['event_owner_required','asr_quota_exceeded','transport']) {
 test(`finish during renewal still reports ${reason} instead of a successful drain`,async()=>{
  let rejectRenewal,begins=0,ends=0;
  const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true,audioFinished:true},
   beginVoice(){return ++begins===2?new Promise((_,reject)=>{rejectRenewal=reject;}):Promise.resolve();},
   async endVoice(){ends++;},async reconnect(){}}});
  await f.capture.start();const renewing=f.capture.reconnect();await flush();
  f.nodes[0].port.postMessage=message=>{if(message==='finish')queueMicrotask(()=>f.nodes[0].port.onmessage({data:{stopped:true}}));};
  const finishing=f.capture.finish();assert.equal(f.capture.state.phase,'FINISHING');
  rejectRenewal(Object.assign(Error(reason),{status:reason==='event_owner_required'?403:503}));
  await renewing;await finishing;
  assert.equal(f.capture.state.phase,'ERROR');assert.equal(f.capture.state.errorCode,reason);
  assert.equal(f.capture.isActive(),false);assert.equal(begins,2);assert.equal(ends,2);assert.equal(f.timers.size,0);
  await f.capture.stop();assert.equal(f.stopped(),1);
 });
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
test('server readiness failure closes tracks and the early audio worklet',async()=>{
  const f=fixture({client:{async beginVoice(){throw Error('unavailable');}}});await f.capture.start();assert.equal(f.stopped(),1);assert.equal(f.nodes.length,1);assert.ok(f.calls.includes('close'));assert.equal(f.capture.state.phase,'ERROR');
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

function respond(handler){
  const calls=[],timers=new Map();let n=0;
  const client=createClient({uuid:()=>id,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:k=>timers.delete(k),fetchImpl:async(url,options)=>{
    const route=url.replace('/api/assist/display/','');calls.push({route,body:JSON.parse(options.body)});return handler(route,JSON.parse(options.body));}});
  return {client,calls,timers,
    ok:v=>({ok:true,headers:{get:()=>null},json:async()=>v}),
    bad:(status,reason)=>({ok:false,status,headers:{get:()=>null},json:async()=>({reason})})};
}
test('stale_epoch on beginVoice is recoverable waiting, not a fatal stop',async()=>{
  const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},async beginVoice(){const e=Error('stale_epoch');e.status=409;throw e;}}});
  await f.capture.start();assert.equal(f.stopped(),0);assert.equal(f.capture.state.phase,'WAITING');assert.equal(f.timers.size>0,true);await f.capture.stop();
});
test('drop recovery uses one backoff retry at a time while preserving capture intent',async()=>{
  let starts=0,n=0;const timers=new Map();
  const f=fixture({options:{continuous:true,setTimer(fn,ms){timers.set(++n,fn);return n;},clearTimer:k=>timers.delete(k)},
    client:{state:{role:'STANDALONE',audioAvailable:true},async beginVoice(){starts++;const e=Error('segment_not_ready');e.status=409;throw e;},async endVoice(){},async voiceChunk(){}}});
  await f.capture.start();assert.equal(f.capture.state.phase,'WAITING');
  for(let i=0;i<40;i++){const batch=[...timers.values()];timers.clear();for(const fn of batch)fn();await flush();await flush();}
  assert.equal(f.stopped(),0);assert.equal(f.capture.state.phase,'WAITING');
  assert.ok(starts>=13,'transport retries must not require acoustic quiet: '+starts);await f.capture.stop();const stopped=starts;for(const fn of timers.values())fn();await flush();assert.equal(starts,stopped);
});
test('audio/stop on a drifted epoch refreshes once and keeps the assist session',async()=>{
  let stops=0,boots=0;
  const f=respond(route=>{
    if(route==='bootstrap'){boots++;return f.ok(view(boots>2?{epoch:3}:{}));}
    if(route==='audio/stop'){stops++;return stops===1?f.bad(409,'stale_epoch'):f.ok(view({epoch:3,audioState:'STOPPED'}));}
    return f.ok(view());});
  f.client.start();await flush();await f.client.beginVoice();await f.client.voiceChunk(0,'AAA=');
  await f.client.endVoice({finish:true});
  assert.equal(stops,2);const stopBodies=f.calls.filter(c=>c.route==='audio/stop').map(c=>c.body);
  assert.equal(stopBodies[1].epoch,3);assert.equal(f.client.state.assistId,id);f.client.dispose();
});
test('a transient poll conflict during voice keeps the session and assistId',async()=>{
  const f=respond(route=>route==='poll'?f.bad(409,'stale_epoch'):f.ok(view()));
  f.client.start();await flush();await f.client.beginVoice();
  const before=f.calls.filter(c=>c.route==='bootstrap').length;
  const fire=async()=>{const batch=[...f.timers.entries()];f.timers.clear();for(const[,t]of batch)t.fn();await flush();await flush();};
  await fire();await fire();
  assert.equal(f.client.state.assistId,id);assert.equal(f.calls.filter(c=>c.route==='bootstrap').length,before);f.client.dispose();
});
test('quota limit suspends only provider traffic and keeps microphone until explicit stop',async()=>{
 let outbound=0;const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},async voiceChunk(){outbound++;throw Error('asr_quota_exceeded');}}});
 await f.capture.start();f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();assert.equal(f.stopped(),0);assert.equal(f.capture.state.phase,'LISTENING');assert.equal(f.capture.state.sttPausedReason,'asr_quota_exceeded');
 for(let i=0;i<100;i++)f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();assert.equal(outbound,1);assert.equal(f.capture.state.droppedAudioMs,24240);assert.equal(f.stopped(),0);await f.capture.stop();assert.equal(f.stopped(),1);
});

test('start failures record which await stage threw so the surfaced code is actionable',async()=>{
 const missing=fixture({env:{navigator:{mediaDevices:{getUserMedia:async()=>{throw Object.assign(Error(),{name:'NotFoundError'});}}}}});
 await missing.capture.start();assert.equal(missing.capture.state.phase,'ERROR');
 assert.equal(missing.capture.state.errorCode,'microphone_device_missing');assert.equal(missing.capture.state.errorStage,'mic_open');
 assert.equal(missing.capture.state.events.find(e=>e.event==='MIC_SESSION_STOP').stage,'mic_open');
 class BadContext{constructor(){this.audioWorklet={addModule:async()=>{throw Error('worklet_load_failed');}};this.destination={};}async resume(){}async close(){}createMediaStreamSource(){return{connect:()=>({connect:()=>({connect(){}})})};}createGain(){return{gain:{value:1}};}}
 const graph=fixture({env:{AudioContext:BadContext}});
 await graph.capture.start();assert.equal(graph.capture.state.phase,'ERROR');
 assert.equal(graph.capture.state.errorCode,'worklet_load_failed');assert.equal(graph.capture.state.errorStage,'audio_graph');
 const server=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},async beginVoice(){const e=Error('paired_phone_required');e.status=403;throw e;}}});
 await server.capture.start();assert.equal(server.capture.state.phase,'ERROR');
 assert.equal(server.capture.state.errorCode,'paired_phone_required');assert.equal(server.capture.state.errorStage,'server_begin');
});
test('a stale saved input device retries once on the system default input and clears the pin',async()=>{
 const constraints=[];let fallback=0;
 const media={getTracks:()=>[{stop(){}}],getAudioTracks:()=>[{label:'default input'}]};
 const f=fixture({options:{deviceId:()=>'stale-input',onDeviceFallback:()=>{fallback++;}},
  env:{navigator:{mediaDevices:{getUserMedia:async args=>{constraints.push(args);if(args.audio.deviceId)throw Object.assign(Error(),{name:'OverconstrainedError'});return media;}}}}});
 assert.equal(await f.capture.start(),true);assert.equal(f.capture.state.phase,'LISTENING');
 assert.equal(constraints.length,2);assert.deepEqual(constraints[0].audio.deviceId,{exact:'stale-input'});
 assert.equal('deviceId' in constraints[1].audio,false);
 assert.equal(fallback,1);assert.equal(f.capture.state.errorCode,null);assert.equal(f.capture.state.errorStage,null);
 assert.ok(f.capture.state.events.some(e=>e.event==='MIC_DEVICE_FALLBACK'));
 await f.capture.stop();
});
test('a failed default-input retry keeps the original error path and never fires the pin clear',async()=>{
 const constraints=[];let fallback=0;
 const f=fixture({options:{deviceId:()=>'stale-input',onDeviceFallback:()=>{fallback++;}},
  env:{navigator:{mediaDevices:{getUserMedia:async args=>{constraints.push(args);throw Object.assign(Error(),{name:args.audio.deviceId?'OverconstrainedError':'NotFoundError'});}}}}});
 assert.equal(await f.capture.start(),false);assert.equal(f.capture.state.phase,'ERROR');
 assert.equal(constraints.length,2);assert.equal(f.capture.state.errorCode,'microphone_device_missing');
 assert.equal(f.capture.state.errorStage,'mic_open');assert.equal(fallback,0);
});
test('assist_not_found on start reconnects the session and retries beginVoice once',async()=>{
 let begins=0,reconnects=0;
 const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},
  async reconnect(options){reconnects++;assert.equal(options.preserveSession,true);},
  async beginVoice(){begins++;if(begins===1)throw Error('assist_not_found');}}});
 assert.equal(await f.capture.start(),true);assert.equal(f.capture.state.phase,'LISTENING');
 assert.equal(begins,2);assert.equal(reconnects,1);assert.equal(f.capture.state.errorCode,null);assert.equal(f.capture.state.errorStage,null);
 await f.capture.stop();
});
test('assist_not_found surviving the reconnect stops with its code and stage exposed',async()=>{
 let reconnects=0;
 const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},
  async reconnect(){reconnects++;},async beginVoice(){throw Error('assist_not_found');}}});
 assert.equal(await f.capture.start(),false);assert.equal(f.capture.state.phase,'ERROR');
 assert.equal(reconnects,1);assert.equal(f.capture.state.errorCode,'assist_not_found');
 assert.equal(f.capture.state.errorStage,'server_begin');
});
test('assist_not_found without a reconnect-capable client still surfaces the code',async()=>{
 const f=fixture({options:{continuous:true},client:{state:{role:'STANDALONE',audioAvailable:true},async beginVoice(){throw Error('assist_not_found');}}});
 await f.capture.start();assert.equal(f.capture.state.phase,'ERROR');
 assert.equal(f.capture.state.errorCode,'assist_not_found');assert.equal(f.capture.state.errorStage,'server_begin');
});
