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

for(const boundary of ['complete','cancel','timeout','focus_close']) {
 test(`camera ${boundary} preserves a separate active microphone and subsequent PCM`,async()=>{
  const {createSnapshotter}=require('../../../main/resources/static/assets/display/display-snapshot.js');
  const {mount}=require('../../../main/resources/static/assets/display/display-focus-controls.js');
  const f=fixture();let videoStops=0;const cameraTimers=new Map(),mediaCalls=[];
  const videoStream={getTracks:()=>[{stop(){videoStops++;}}],getVideoTracks:()=>[{getSettings:()=>({facingMode:'environment'})}]};
  const navigator={mediaDevices:{async getUserMedia(constraints){mediaCalls.push(constraints);return constraints.audio===false?videoStream:f.media;}}};
  f.env.navigator=navigator;
  const video={readyState:boundary==='complete'?2:0,videoWidth:640,videoHeight:480,play:async()=>{},srcObject:null};
  const canvas={getContext:()=>({drawImage(){},getImageData:()=>({data:new Uint8ClampedArray(64).fill(64)})}),toDataURL:()=>'data:image/jpeg;base64,QUJD'};
  const snapshotter=createSnapshotter({navigator,document:{createElement:tag=>tag==='video'?video:canvas},setTimer(fn){const id={};cameraTimers.set(id,fn);return id;},clearTimer:id=>cameraTimers.delete(id)});
  const elements=new Map();const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,hidden:true,append(){},replaceChildren(){},removeAttribute(){}});return elements.get(id);};
  const focusCalls=[];const controls=mount({host:{DisplaySnapshot:{createSnapshotter:()=>snapshotter},NovaFocus:{createProjection:()=>({update(){},dismiss(){},visibility(){},dispose(){},isActive:()=>false}),receiptSender:()=>()=>{}}},document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{async focusRequest(route){focusCalls.push(route);return {};}}});
  try{
   await f.capture.start();f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();
   const chunksBefore=f.calls.filter(c=>Array.isArray(c)&&c[0]==='chunk').length;
   const shot=snapshotter.captureOnce();await flush();
   if(boundary==='cancel')snapshotter.stop();
   if(boundary==='timeout')cameraTimers.values().next().value();
   if(boundary==='focus_close')await element('nova-close').onclick();
   const result=await shot;assert.equal(result.ok,boundary==='complete');
   if(boundary==='timeout')assert.equal(result.error,'camera_frame_timeout');
   assert.ok(videoStops>0);assert.equal(video.srcObject,null);assert.equal(snapshotter.busy,false);
   assert.equal(f.stopped(),0);assert.equal(f.calls.filter(c=>c==='close'||c==='stop').length,0);assert.equal(f.capture.isActive(),true);
   f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();
   assert.equal(f.calls.filter(c=>Array.isArray(c)&&c[0]==='chunk').length,chunksBefore+1);
   assert.equal(mediaCalls.length,2);assert.equal(mediaCalls[0].video,false);assert.equal(mediaCalls[1].audio,false);
   if(boundary==='focus_close')assert.deepEqual(focusCalls,['close']);
   await f.capture.stop();assert.equal(f.stopped(),1);assert.equal(f.calls.filter(c=>c==='close').length,1);
  }finally{controls.dispose();snapshotter.stop();await f.capture.stop();}
 });
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
test('persistent stale conflicts back off then stop with the reason after three retries',async()=>{
  let starts=0,n=0;const timers=new Map();
  const f=fixture({options:{continuous:true,setTimer(fn,ms){timers.set(++n,fn);return n;},clearTimer:k=>timers.delete(k)},
    client:{state:{role:'STANDALONE',audioAvailable:true},async beginVoice(){starts++;const e=Error('segment_not_ready');e.status=409;throw e;},async endVoice(){},async voiceChunk(){}}});
  await f.capture.start();assert.equal(f.capture.state.phase,'WAITING');
  for(let i=0;i<3;i++){const batch=[...timers.values()];timers.clear();for(const fn of batch)fn();await flush();await flush();
    assert.equal(f.stopped(),0);assert.equal(f.capture.state.phase,'WAITING');}
  for(const [k,fn] of [...timers]){timers.delete(k);fn();await flush();await flush();}
  assert.equal(starts,4,'초기 시도 + 지수 백오프 재시도 3회에서 멈춰야 한다');
  assert.equal(f.stopped(),1);assert.equal(f.capture.state.phase,'ERROR');assert.equal(f.capture.state.errorCode,'segment_not_ready');
  assert.equal(f.timers.size,0);
  for(const fn of [...timers.values()])fn();await flush();assert.equal(starts,4);
});
test('successful starts cannot reset a persistent chunk409 recovery budget',async()=>{
  let starts=0,chunks=0,n=0;const timers=new Map();
  const f=fixture({options:{continuous:true,setTimer(fn,ms){const id=++n;timers.set(id,{fn,ms});return id;},clearTimer:k=>timers.delete(k)},
    client:{state:{role:'STANDALONE',audioAvailable:true},async beginVoice(){starts++;},async endVoice(){},
      async voiceChunk(){chunks++;throw Object.assign(Error('stale_epoch'),{status:409});}}});
  try{
    await f.capture.start();
    for(let cycle=0;cycle<8&&f.capture.isActive();cycle++){
      f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();await flush();
      assert.equal(f.capture.state.phase,'WAITING');
      const retry=[...timers.entries()].filter(([,value])=>value.ms<=30251).at(-1);
      assert.ok(retry);timers.delete(retry[0]);retry[1].fn();await flush();await flush();
    }
    assert.equal(starts,4,'initial start plus three retries must bound persistent PCM conflicts');
    assert.equal(chunks,4);assert.equal(f.capture.isActive(),false);
    assert.equal(f.capture.state.phase,'ERROR');assert.equal(f.capture.state.errorCode,'stale_epoch');
    assert.equal(f.stopped(),1);assert.equal(timers.size,0);
    await f.capture.stop();await flush();assert.equal(starts,4);
  }finally{await f.capture.stop();}
});
test('confirmed PCM delivery resets the budget for a later independent conflict',async()=>{
  let starts=0,accepted=0,rejectChunk=true,n=0;const timers=new Map();
  const f=fixture({options:{continuous:true,setTimer(fn,ms){const id=++n;timers.set(id,{fn,ms});return id;},clearTimer:k=>timers.delete(k)},
    client:{state:{role:'STANDALONE',audioAvailable:true},async beginVoice(){starts++;},async endVoice(){},
      async voiceChunk(){if(rejectChunk)throw Object.assign(Error('stale_epoch'),{status:409});accepted++;}}});
  try{
    await f.capture.start();
    for(let episode=0;episode<5;episode++){
      rejectChunk=true;
      for(let conflict=0;conflict<2;conflict++){
        f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();await flush();
        assert.equal(f.capture.state.phase,'WAITING');
        const retry=[...timers.entries()].filter(([,value])=>value.ms<=30251).at(-1);
        assert.ok(retry);timers.delete(retry[0]);retry[1].fn();await flush();await flush();
        assert.equal(f.capture.state.phase,'LISTENING');
      }
      rejectChunk=false;f.nodes[0].port.onmessage({data:{pcm:new ArrayBuffer(7680)}});await flush();await flush();
      assert.equal(f.capture.state.phase,'LISTENING');assert.equal(f.stopped(),0);
    }
    assert.equal(starts,11);assert.equal(accepted,5);
  }finally{await f.capture.stop();}
});
test('a stale conflict recovery restarts once and clears the bound after a healthy begin',async()=>{
  let starts=0,n=0;const timers=new Map();
  const f=fixture({options:{continuous:true,setTimer(fn,ms){timers.set(++n,fn);return n;},clearTimer:k=>timers.delete(k)},
    client:{state:{role:'STANDALONE',audioAvailable:true},beginVoice(){starts++;if(starts===1){const e=Error('stale_epoch');e.status=409;throw e;}return Promise.resolve();},async endVoice(){},async voiceChunk(){},async reconnect(){}}});
  await f.capture.start();assert.equal(f.capture.state.phase,'WAITING');
  for(let i=0;i<8&&f.capture.state.phase==='WAITING';i++){const batch=[...timers.values()];timers.clear();for(const fn of batch)fn();await flush();await flush();}
  assert.equal(starts,2);assert.equal(f.capture.state.phase,'LISTENING');assert.equal(f.stopped(),0);await f.capture.stop();
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
test('each voice start refetches status so a stale epoch is replaced before the retry',async()=>{
 let starts=0,boots=0;
 const f=respond(route=>{
   if(route==='bootstrap'){boots++;return f.ok(view({epoch:boots}));}
   if(route==='audio/start'){starts++;return starts===1?f.bad(409,'stale_epoch'):f.ok(view({epoch:boots+10,audioState:'READY'}));}
   return f.ok(view());});
 f.client.start();await flush();await assert.rejects(f.client.beginVoice(),/stale_epoch/);
 await f.client.beginVoice();
 const chunks=f.calls.filter(c=>c.route==='audio/start');
 assert.equal(chunks.length,2);assert.notEqual(chunks[0].body.epoch,chunks[1].body.epoch);
 assert.ok(boots>=3,'각 시작 시도는 서버 상태를 먼저 다시 읽는다: '+boots);
 await f.client.endVoice();f.client.dispose();
});
test('caption acknowledgements retry at most three times per rendered revision',async()=>{
 const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
 const nodes=new Map(),rafQ=[],acks=[];
 const el=()=>({value:'',hidden:true,checked:false,disabled:false,textContent:'',style:{setProperty(){}},classList:{toggle(){}},setAttribute(){},removeAttribute(){},addEventListener(){},focus(){},add(){},replaceChildren(){},querySelectorAll:()=>[]});
 const document={body:{hasAttribute:()=>true},visibilityState:'visible',hidden:false,addEventListener(){},getElementById:key=>{if(!nodes.has(key))nodes.set(key,el());return nodes.get(key);}};
 const state={assistId:id,epoch:2,version:5,connection:'READY',ready:true,role:'STANDALONE',audioAvailable:true,audioFinished:true,reconnects:0,
   caption:{utteranceId:'u1',revision:1,isFinal:true,text:'합성 전사 문장'},captionTtlMs:60000,cardTtlMs:14000,hintsEnabled:false};
 let onChange;const client={state,start(){onChange?.(state)},pause(){},dispose(){},storedLensLink:()=>null,relaySettings:async()=>{},
   acknowledge(version,phase){acks.push({version,phase});return Promise.reject(Object.assign(Error('stale_epoch'),{status:409}));}};
 vm.runInNewContext(fs.readFileSync(path.resolve(__dirname,'../../../main/resources/static/assets/display/app.js'),'utf8'),{
   document,navigator:{},location:{search:''},URLSearchParams,Date,crypto:{randomUUID:()=>id},
   sessionStorage:{getItem:()=>null,setItem(){},removeItem(){}},localStorage:{getItem:()=>null,setItem(){},removeItem(){}},
   window:{location:{search:''},DisplayCore:require('../../../main/resources/static/assets/display/display-core.js'),
     DisplayConversate:{createClient(options){onChange=options.onChange;return client;}},
     DisplayVoice:{createCapture:()=>({state:{},isActive:()=>false,stop(){},resume(){},reconnect(){},deviceChanged(){},finish(){},start:async()=>{}})},addEventListener(){}},
   requestAnimationFrame:fn=>rafQ.push(fn),setTimeout:()=>1,clearTimeout(){}});
 const pump=async()=>{const batch=rafQ.splice(0);for(const fn of batch)fn();await flush();await flush();const next=rafQ.splice(0);for(const fn of next)fn();await flush();await flush();};
 await pump();for(let i=0;i<6;i++){onChange(state);await pump();}
 assert.equal(acks.length,3,'한 캡션 리비전의 수신 확인 재시도는 3회에서 멈춘다');
 state.version=6;state.caption={utteranceId:'u1',revision:2,isFinal:true,text:'합성 전사 문장'};
 for(let i=0;i<6;i++){onChange(state);await pump();}
 assert.equal(acks.length,6,'새 리비전은 새 시도 한도를 가진다');
});
