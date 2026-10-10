const {test}=require('node:test'),assert=require('node:assert/strict');
const {createCapture}=require('../../../main/resources/static/assets/display/display-voice.js');
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
const flush=()=>new Promise(setImmediate);
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};};
const id='12345678-1234-4234-8234-123456789abc';
const view=(extra={})=>({assistId:id,epoch:1,version:1,ready:true,state:'RUNNING',audioAvailable:true,audioState:'READY',...extra});
function transport(handler,options={}){
  const calls=[],timers=new Map();let n=0;
  const client=createClient({...options,uuid:()=>id,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:k=>timers.delete(k),
    fetchImpl:async(url,options)=>{const route=url.replace('/api/assist/display/','');calls.push({route,body:JSON.parse(options.body)});
      return{ok:true,headers:{get:()=>null},json:async()=>handler(route,JSON.parse(options.body))};}});
  return{client,calls,timers};
}
function capture(start=async()=>{},options={}){
  let time=0,n=0,acquisitions=0,starts=0,stops=0;
  const nodes=[],sources=[],tracks=[],sent=[],constraints=[],timers=new Map(),permission={state:'granted',addEventListener(_name,fn){this.listener=fn;},removeEventListener(){this.listener=null;}};
  const stream=()=>{const track={readyState:'live',label:'synthetic',getSettings:()=>({deviceId:'input-one'}),stop(){this.readyState='ended';}};
    tracks.push(track);return{getTracks:()=>[track],getAudioTracks:()=>[track]};};
  class Context{
    constructor(){this.state='running';this.sampleRate=48000;this.destination={};this.audioWorklet={addModule:async()=>{}};}
    async resume(){this.state='running';}async close(){this.state='closed';}
    createMediaStreamSource(media){const source={media,disconnects:0,connect:node=>node,disconnect(){this.disconnects++;}};sources.push(source);return source;}createGain(){return{gain:{value:1},connect(){}};}
  }
  class Worklet{constructor(){this.port={postMessage(){}};nodes.push(this);}connect(node){return node;}disconnect(){}}
  const client=options.client||{state:{audioAvailable:true,role:'STANDALONE',audioFinished:true,audioRenewAfterMs:60000,connection:'READY'},
    async beginVoice(){starts++;return start(starts);},async endVoice(){stops++;},
    async voiceChunk(sequence,pcm){sent.push({sequence,marker:Buffer.from(pcm,'base64').readInt16LE(0)});}};
  const env={isSecureContext:true,AudioContext:Context,AudioWorkletNode:Worklet,btoa,
    navigator:{onLine:true,permissions:{query:async()=>permission},mediaDevices:{
      getUserMedia:async args=>{constraints.push(args);acquisitions++;return stream();},enumerateDevices:async()=>[{kind:'audioinput',deviceId:'input-one'}]}}};
  const voice=createCapture({client,env,continuous:true,now:()=>time,...options,
    setTimer(fn,ms){timers.set(++n,{fn,ms,due:time+ms});return n;},clearTimer:k=>timers.delete(k)});
  return{voice,client,env,nodes,sources,tracks,sent,timers,permission,constraints,get starts(){return starts;},get acquisitions(){return acquisitions;},
    pcm(marker=1000){const samples=new Int16Array(320);samples.fill(marker);nodes.at(-1).port.onmessage({data:{pcm:samples.buffer}});},
    async advance(ms){time+=ms;for(const[k,timer]of [...timers].sort((a,b)=>a[1].due-b[1].due)){if(timer.due<=time&&timers.delete(k))timer.fn();}await flush();await flush();}
  };
}

// Execute the real app across two documents, retaining only tab sessionStorage.
function reloadApp({store=new Map(),permission='granted',initial={},time=1000,permissionGate,clientFactory}={}){
  const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
  const nodes=new Map(),handlers=new Map();let f,clientOptions;
  const storage={getItem:key=>store.get(key)||null,setItem:(key,value)=>store.set(key,value),removeItem:key=>store.delete(key)};
  const document={body:{hasAttribute:()=>true},visibilityState:'visible',hidden:false,addEventListener(){},
    getElementById(key){if(!nodes.has(key))nodes.set(key,{value:'',hidden:true,checked:false,style:{setProperty(){}},classList:{toggle(){}},setAttribute(){},removeAttribute(){},addEventListener(){},focus(){},add(){},replaceChildren(){}});return nodes.get(key);}};
  let client={state:{assistId:id,epoch:2,version:2,connection:'READY',ready:true,role:'STANDALONE',audioAvailable:true,audioFinished:true,testStatus:{relay:{eventOwner:'THIS DEVICE',segmentSeconds:0}},...initial},
    start(){clientOptions.onChange(this.state);},pause(){},dispose(){},storedLensLink:()=>null,async acknowledge(){},async relaySettings(){}};
  const navigator={permissions:permission===null?undefined:{query:()=>permissionGate?.promise||Promise.resolve({state:permission})}};
  vm.runInNewContext(fs.readFileSync(path.resolve(__dirname,'../../../main/resources/static/assets/display/app.js'),'utf8'),{
    document,navigator,location:{search:''},URLSearchParams,Date:{now:()=>time},crypto:{randomUUID:()=>id},sessionStorage:storage,localStorage:{getItem:()=>null},
    window:{location:{search:''},DisplayCore:require('../../../main/resources/static/assets/display/display-core.js'),
      DisplayConversate:{createClient(options){clientOptions=options;if(clientFactory)client=clientFactory(options);return client;}},
      DisplayVoice:{createCapture(options){f=capture(undefined,{onChange:options.onChange,...(clientFactory?{client}:{})});Object.assign(f.client.state,client.state);return f.voice;}},addEventListener:(name,fn)=>handlers.set(name,fn)},
    requestAnimationFrame(){},setTimeout:()=>1,clearTimeout(){}});
  return{f,store,nodes,handlers,client,update(patch){Object.assign(client.state,patch);clientOptions.onChange(client.state);}};
}
test('reload resume intent auto starts a continuation only after same-session ownership and granted permission',async()=>{
  const first=reloadApp();await first.f.voice.start();first.handlers.get('pagehide')();await flush();
  const second=reloadApp({store:first.store,initial:{ready:false}});await flush();assert.equal(second.f.acquisitions,0);
  second.update({ready:true});await flush();await flush();
  try{assert.equal(second.f.acquisitions,1);assert.equal(second.f.voice.isActive(),true);assert.equal(second.store.size,0);}
  finally{await second.f.voice.stop();}
});
test('explicit stop leaves no resume intent and reload cannot record automatically',async()=>{
  const first=reloadApp();await first.f.voice.start();await first.f.voice.stop();first.handlers.get('pagehide')();await flush();
  const second=reloadApp({store:first.store});await flush();assert.equal(second.store.size,0);assert.equal(second.f.acquisitions,0);
});
test('persisted pageshow does not consume resume intent or automatically start the microphone',async()=>{
  const app=reloadApp();await app.f.voice.start();app.handlers.get('pagehide')();await flush();
  const saved=[...app.store];assert.equal(saved.length,1);
  app.handlers.get('pageshow')({persisted:true});await flush();assert.deepEqual([...app.store],saved);assert.equal(app.f.acquisitions,1);assert.equal(app.f.voice.isActive(),false);
});
test('prompt denied and unsupported permission consume intent and leave a one-tap resume button',async()=>{
  for(const permission of ['prompt','denied',null]){
    const first=reloadApp();await first.f.voice.start();first.handlers.get('pagehide')();await flush();
    const second=reloadApp({store:first.store,permission});await flush();await flush();
    assert.equal(second.f.acquisitions,0);assert.equal(second.store.size,0);assert.equal(second.nodes.get('microphone').textContent,'폴드6 수음 재개');
    second.nodes.get('microphone').onclick();await flush();await flush();assert.equal(second.f.acquisitions,1);await second.f.voice.stop();
  }
});
test('expired different-session and auto-voice resume intentions never auto-start capture',async()=>{
  for(const initial of [{assistId:'87654321-4321-4321-8321-abcdef123456'},{autoVoiceSettings:{modeEnabled:true}},{}]){
    const first=reloadApp();await first.f.voice.start();first.handlers.get('pagehide')();await flush();
    const second=reloadApp({store:first.store,initial,time:Object.keys(initial).length?2000:61001});await flush();
    assert.equal(second.f.acquisitions,0);assert.equal(second.store.size,0);
  }
});
test('pagehide while permission is being checked fences a late automatic resume',async()=>{
  const first=reloadApp();await first.f.voice.start();first.handlers.get('pagehide')();await flush();
  const gate=deferred(),second=reloadApp({store:first.store,permissionGate:gate});await flush();second.handlers.get('pagehide')();
  gate.resolve({state:'granted'});await flush();assert.equal(second.f.acquisitions,0);
});
test('explicit stop or finish while reload permission is pending prevents late capture',async()=>{
  for(const button of ['stop','finish']){
    const first=reloadApp();await first.f.voice.start();first.handlers.get('pagehide')();await flush();
    const gate=deferred(),second=reloadApp({store:first.store,permissionGate:gate});await flush();
    await second.nodes.get(button).onclick();gate.resolve({state:'granted'});await flush();await flush();
    try{assert.equal(second.f.acquisitions,0,button+' must cancel a pending reload resume');}
    finally{await second.f.voice.stop();}
  }
});
test('server begin and chunk failure diagnostics identify the stage without recording audio or response text',async()=>{
  const f=capture(()=>{throw Object.assign(Error('display-timeout'),{status:0});}),reported=[];
  f.client.state.epoch=7;f.client.captureDiagnostic=async event=>reported.push(event);f.client.reconnect=async()=>{};
  try{
    await f.voice.start();assert.equal(reported[0]?.stage,'server_begin');assert.equal(reported[0]?.httpStatus,0);assert.equal(reported[0]?.epoch,7);
    f.client.beginVoice=async()=>{};await f.voice.reconnect();
    f.client.voiceChunk=async()=>{throw Object.assign(Error('event_owner_required'),{status:403});};f.pcm();await flush();await flush();
    const event=reported.find(e=>e.stage==='transport');assert.equal(event?.httpStatus,403);assert.equal(event?.producerMismatch,true);
    for(const row of reported)for(const forbidden of ['pcm','text','body','transcript','grant','cookie','authorization'])assert.equal(Object.hasOwn(row,forbidden),false);
  }finally{await f.voice.stop();}
});
test('capture diagnostics use the existing endpoint with an allowlisted payload and an abort deadline',async()=>{
  const f=transport(()=>view());
  try{
    assert.equal(typeof f.client.captureDiagnostic,'function');
    await f.client.captureDiagnostic({stage:'transport',httpStatus:503,epoch:2,producerMismatch:false,lastFrameAgeMs:42,errorCode:'audio_transport_failed',pcm:'private',body:'private',grant:'private'});
    const call=f.calls.find(c=>c.route==='relay/diagnostics');assert.equal(call.body.event,'capture_error');assert.equal(call.body.code,'http_503');
    assert.equal(call.body.stage,'transport');assert.equal(call.body.lastFrameAgeMs,42);
    for(const forbidden of ['pcm','body','grant'])assert.equal(Object.hasOwn(call.body,forbidden),false);
    assert.equal(f.timers.size,0);
  }finally{f.client.dispose();}
});

test('session preparation has its own deadline instead of the ordinary four-second request limit',async()=>{
  const gate=deferred(),f=transport(route=>route==='bootstrap'?gate.promise:view());
  const starting=f.client.beginVoice();
  try{await flush();assert.ok([...f.timers.values()].some(t=>t.ms>=10000),'preparation must survive the ordinary 4s limit');}
  finally{gate.resolve(view());await starting.catch(()=>{});await f.client.endVoice();f.client.dispose();}
});
test('actual audio-start timeout recovers through the client capture and app controls without reopening the microphone',async()=>{
  const first=deferred();let network,starts=0;
  const ready=()=>view({epoch:2,role:'STANDALONE',audioFinished:true,captionTtlMs:20000,cardTtlMs:20000,testStatus:{relay:{eventOwner:'THIS DEVICE',segmentSeconds:0}}});
  const app=reloadApp({clientFactory:options=>{
    network=transport((route,body)=>{
      if(route==='audio/start'&&++starts===1)return first.promise;
      if(route==='audio/chunk-batch')return{view:ready(),acceptedCount:body.frames.length,acceptedThrough:body.frames.at(-1).sequence};
      return ready();
    },options);return network.client;
  }});
  try{
    await flush();await flush();assert.equal(app.nodes.get('microphone').disabled,false);
    app.nodes.get('microphone').onclick();await flush();await flush();
    assert.equal(starts,1);assert.equal(app.f.acquisitions,1);
    const deadline=[...network.timers.values()].find(t=>t.ms===35000);assert.ok(deadline,'expire the actual audio/start deadline');
    await app.f.advance(35000);deadline.fn();await flush();await flush();
    assert.equal(app.f.voice.state.phase,'WAITING');assert.equal(app.f.voice.isActive(),true);
    assert.equal(app.nodes.get('microphone').disabled,false,'Stop remains usable while recovery waits');
    await app.f.advance(1251);await flush();await flush();
    assert.equal(starts,2);assert.equal(app.client.state.connection,'READY');assert.equal(app.f.voice.state.phase,'LISTENING');
    assert.equal(app.f.acquisitions,1);assert.equal(app.nodes.get('microphone').disabled,false);assert.equal(app.nodes.get('finish').hidden,false);
    first.resolve({...ready(),epoch:99});await flush();assert.equal(app.client.state.epoch,2,'the timed-out response cannot replace the recovered epoch');
    app.f.pcm(1234);await app.f.advance(20);await flush();await flush();
    assert.equal(network.calls.filter(c=>c.route==='audio/chunk-batch').length,1,'recovery delivers each new frame once');
    await app.nodes.get('stop').onclick();await flush();await flush();
    assert.equal(app.f.voice.isActive(),false);await app.f.advance(120000);assert.equal(starts,2,'Stop fences every later recovery timer');
  }finally{first.resolve(ready());await app.f.voice.stop();app.client.dispose();}
});
test('a failed audio-start rolls back its own voice latch so the next start can succeed',async()=>{
  let calls=0;const f=transport(route=>{if(route==='audio/start'&&++calls===1)throw Error('synthetic_network');return view();});
  try{await assert.rejects(f.client.beginVoice());assert.equal(f.client.state.voiceActive,false);await assert.doesNotReject(()=>f.client.beginVoice());}
  finally{await f.client.endVoice();f.client.dispose();}
});
test('an older failed start cannot clear a newer successful voice attempt',async()=>{
  const gate=deferred();let starts=0;
  const f=transport(route=>route==='audio/start'&&++starts===1?gate.promise:view({epoch:2}));
  const first=f.client.beginVoice();first.catch(()=>{});
  try{
    await flush();await f.client.endVoice();await f.client.beginVoice();gate.reject(Error('synthetic_late_failure'));await assert.rejects(first);
    assert.equal(f.client.state.voiceActive,true);await f.client.voiceChunk(0,'AAA=');
    assert.equal(f.calls.filter(c=>c.route==='audio/chunk').length,1);
  }finally{gate.reject(Error('synthetic_cleanup'));await first.catch(()=>{});await f.client.endVoice();f.client.dispose();}
});
test('PCM capture starts before the handshake and releases buffered audio once in order',async()=>{
  const gate=deferred(),f=capture(()=>gate.promise),starting=f.voice.start();
  try{
    await flush();assert.equal(f.nodes.length,1,'worklet must exist during handshake');
    f.pcm(1000);f.pcm(2000);assert.deepEqual(f.sent,[]);
    gate.resolve();await starting;await flush();
    assert.deepEqual(f.sent,[{sequence:0,marker:1000},{sequence:1,marker:2000}]);
  }finally{gate.resolve();await starting;await f.voice.stop();}
});
test('pre-ready audio older than fifteen seconds is discarded and counted as a gap',async()=>{
  const gate=deferred(),f=capture(()=>gate.promise),starting=f.voice.start();
  try{
    await flush();assert.equal(f.nodes.length,1,'early worklet required');f.pcm(1000);
    await f.advance(16001);f.pcm(2000);gate.resolve();await starting;await flush();
    assert.deepEqual(f.sent.map(x=>x.marker),[2000]);assert.ok(f.voice.state.droppedAudioMs>=20);
  }finally{gate.resolve();await starting;await f.voice.stop();}
});
test('continuous noise cannot become a prerequisite that permanently blocks transport retry',async()=>{
  const f=capture(attempt=>{if(attempt<13)throw Error('asr_unavailable');});
  try{
    await f.voice.start();
    for(let i=0;i<120&&f.starts<13;i++){f.pcm(8000);await f.advance(5000);}
    assert.ok(f.starts>=13,'healthy transport must be retried without waiting for quiet then speech');
    f.pcm(8000);await flush();assert.equal(f.voice.state.phase,'LISTENING');
    await f.voice.stop();const stopped=f.starts;await f.advance(120000);assert.equal(f.starts,stopped);
  }finally{await f.voice.stop();}
});
test('an ended input track is reacquired while microphone permission and capture intent remain on',async()=>{
  const f=capture();
  try{
    await f.voice.start();f.tracks[0].readyState='ended';f.tracks[0].onended();await flush();await flush();
    assert.equal(f.acquisitions,2);assert.equal(f.voice.isActive(),true);
  }finally{await f.voice.stop();}
});
test('an unrelated devicechange leaves the active input alone',async()=>{
  const f=capture();
  try{
    await f.voice.start();assert.equal(typeof f.voice.deviceChanged,'function');await f.voice.deviceChanged();
    assert.equal(f.acquisitions,1);assert.equal(f.tracks[0].readyState,'live');assert.equal(f.voice.isActive(),true);
  }finally{await f.voice.stop();}
});
test('revoked microphone permission is terminal and cannot reacquire automatically',async()=>{
  const f=capture();
  try{
    await f.voice.start();f.permission.state='denied';f.tracks[0].readyState='ended';f.tracks[0].onended();await flush();await flush();
    await f.advance(120000);assert.equal(f.acquisitions,1);assert.equal(f.voice.isActive(),false);
  }finally{await f.voice.stop();}
});

test('manual reconnect keeps the microphone and graph while renewing only the server segment',async()=>{
  const f=capture();let reconnects=0;
  f.client.reconnect=async options=>{assert.equal(options.preserveSession,true);reconnects++;};
  try{await f.voice.start();await f.voice.reconnect();assert.equal(f.acquisitions,1);assert.equal(f.nodes.length,1);
    assert.equal(f.tracks[0].readyState,'live');assert.equal(f.starts,2);assert.equal(reconnects,1);assert.equal(f.voice.isActive(),true);
  }finally{await f.voice.stop();}
});
test('buffer overflow preserves newest unsent audio and reports its gap',async()=>{
  const gate=deferred(),f=capture(()=>gate.promise),starting=f.voice.start();
  try{await flush();for(let marker=1;marker<=751;marker++)f.pcm(marker);gate.resolve();await starting;await flush();
    assert.equal(f.sent.length,750);assert.equal(f.sent[0].marker,2);assert.equal(f.sent.at(-1).marker,751);
    assert.equal(f.voice.state.droppedAudioMs,20);assert.ok(f.voice.state.events.some(e=>e.event==='audio_gap'));
  }finally{gate.resolve();await starting;await f.voice.stop();}
});
test('permission change to denied stops an otherwise live input without automatic restart',async()=>{
  const f=capture();try{await f.voice.start();await flush();assert.equal(typeof f.permission.listener,'function');
    f.permission.state='denied';f.permission.listener();await flush();await f.advance(120000);
    assert.equal(f.voice.isActive(),false);assert.equal(f.acquisitions,1);assert.equal(f.tracks[0].readyState,'ended');
  }finally{await f.voice.stop();}
});
test('explicit stop during device reacquisition disposes the late stream instead of restarting',async()=>{
  const f=capture(),gate=deferred();let disposed=0;
  try{await f.voice.start();f.env.navigator.mediaDevices.getUserMedia=()=>gate.promise;
    f.tracks[0].readyState='ended';f.tracks[0].onended();await flush();await f.voice.stop();
    gate.resolve({getTracks:()=>[{stop(){disposed++;}}]});await flush();await flush();
    assert.equal(disposed,1);assert.equal(f.voice.isActive(),false);assert.equal(f.starts,1);
  }finally{gate.resolve({getTracks:()=>[]});await f.voice.stop();}
});

test('device recovery preserves the selected input constraint',async()=>{
 const f=capture(undefined,{deviceId:()=> 'selected-input'});
 try{await f.voice.start();f.tracks[0].readyState='ended';f.tracks[0].onended();await flush();await flush();
   assert.equal(f.acquisitions,2);for(const args of f.constraints)assert.deepEqual(args.audio.deviceId,{exact:'selected-input'});
 }finally{await f.voice.stop();}
});

test('concurrent ended-track recovery rebinds one selected input without duplicating the graph or PCM',async()=>{
 const f=capture(undefined,{deviceId:()=> 'selected-input'});
 try{
   await f.voice.start();f.pcm(1000);await flush();
   const ended=f.tracks[0].onended;f.tracks[0].readyState='ended';ended();ended();
   await f.voice.deviceChanged();await flush();
   assert.equal(f.acquisitions,2,'concurrent notifications must share one reacquisition');
   assert.equal(f.sources.length,2);assert.equal(f.sources[0].disconnects,1);
   assert.equal(f.sources[1].media.getAudioTracks()[0],f.tracks[1]);
   assert.equal(f.tracks.filter(track=>track.readyState==='live').length,1);
   assert.equal(f.tracks[0].onended,null);
   assert.equal(f.nodes.length,1,'input recovery preserves the existing worklet');
   assert.equal(f.starts,1,'input recovery preserves the healthy server segment');
   for(const args of f.constraints)assert.deepEqual(args.audio.deviceId,{exact:'selected-input'});
   f.pcm(2000);await flush();
   assert.deepEqual(f.sent,[{sequence:0,marker:1000},{sequence:1,marker:2000}]);
 }finally{await f.voice.stop();}
});

test('Stop during input reacquisition fences late worklet PCM and every recovery timer',async()=>{
 const f=capture(),gate=deferred();let disposed=0;
 try{
   await f.voice.start();const oldNode=f.nodes[0];
   f.env.navigator.mediaDevices.getUserMedia=()=>gate.promise;
   f.tracks[0].readyState='ended';f.tracks[0].onended();await flush();
   await f.voice.stop();
   const emit=()=>{const samples=new Int16Array(320);samples.fill(3000);oldNode.port.onmessage({data:{pcm:samples.buffer}});};
   emit();gate.resolve({getTracks:()=>[{stop(){disposed++;}}]});await flush();await flush();
   await f.advance(120000);emit();await flush();
   assert.equal(disposed,1);assert.equal(f.voice.isActive(),false);
   assert.equal(f.starts,1);assert.equal(f.acquisitions,1);assert.equal(f.timers.size,0);
   assert.deepEqual(f.sent,[],'stopped graph callbacks must not transmit PCM');
 }finally{gate.resolve({getTracks:()=>[]});await f.voice.stop();}
});

test('an explicit new Start survives the old reacquisition and rejects the old worklet callback',async()=>{
 const f=capture(),gate=deferred();let disposed=0;
 try{
   await f.voice.start();const oldNode=f.nodes[0],getUserMedia=f.env.navigator.mediaDevices.getUserMedia;
   f.env.navigator.mediaDevices.getUserMedia=()=>gate.promise;
   f.tracks[0].readyState='ended';f.tracks[0].onended();await flush();await f.voice.stop();
   f.env.navigator.mediaDevices.getUserMedia=getUserMedia;await f.voice.start();
   gate.resolve({getTracks:()=>[{stop(){disposed++;}}]});await flush();await flush();
   const samples=new Int16Array(320);samples.fill(3000);oldNode.port.onmessage({data:{pcm:samples.buffer}});
   f.pcm(4000);await flush();
   assert.equal(disposed,1);assert.equal(f.starts,2);assert.equal(f.acquisitions,2);
   assert.equal(f.voice.isActive(),true);assert.equal(f.tracks[1].readyState,'live');
   assert.deepEqual(f.sent,[{sequence:0,marker:4000}]);
 }finally{gate.resolve({getTracks:()=>[]});await f.voice.stop();}
});
test('failed readiness after epoch renewal cleans only the returned audio epoch',async()=>{
 const f=transport(route=>route==='audio/start'?view({epoch:2,audioState:'WAITING'}):view());
 try{await assert.rejects(f.client.beginVoice(),/audio-not-ready/);
   assert.equal(f.calls.find(c=>c.route==='audio/stop').body.epoch,2);assert.equal(f.client.state.voiceActive,false);
 }finally{await f.client.endVoice();f.client.dispose();}
});

test('assist_not_found on capture start reconnects once and retries the handshake',async()=>{
 let reconnects=0;
 const f=capture(attempt=>{if(attempt===1)throw Object.assign(Error('assist_not_found'),{status:404});});
 f.client.reconnect=async options=>{assert.equal(options.preserveSession,true);reconnects++;};
 try{assert.equal(await f.voice.start(),true);
   assert.equal(f.starts,2);assert.equal(reconnects,1);
   assert.equal(f.voice.state.phase,'LISTENING');assert.equal(f.voice.state.errorCode,null);assert.equal(f.voice.state.errorStage,null);
 }finally{await f.voice.stop();}
});
test('assist_not_found surviving the reconnect stops with its code and stage',async()=>{
 const f=capture(()=>{throw Object.assign(Error('assist_not_found'),{status:404});});
 f.client.reconnect=async()=>{};
 try{assert.equal(await f.voice.start(),false);
   assert.equal(f.voice.state.phase,'ERROR');assert.equal(f.voice.state.errorCode,'assist_not_found');
   assert.equal(f.voice.state.errorStage,'server_begin');
   assert.equal(f.voice.state.events.find(e=>e.event==='MIC_SESSION_STOP').stage,'server_begin');
 }finally{await f.voice.stop();}
});


for(const terminal of ['pause','dispose']){
 test(`pending reconnect late success cannot resume or apply a view after ${terminal}`,async()=>{
  const gate=deferred(),f=transport(route=>route==='bootstrap'?gate.promise:view());
  const reconnecting=f.client.reconnect();reconnecting.catch(()=>{});
  try{
   await flush();f.client[terminal]();assert.equal(f.client.state.connection,'PAUSED');
   gate.resolve(view({epoch:2}));await reconnecting;await flush();await flush();
   assert.equal(f.client.state.connection,'PAUSED','late success must preserve '+terminal);
   assert.equal(f.client.state.assistId,undefined,'cancelled view must not be applied');
   assert.equal(f.calls.filter(c=>c.route==='poll').length,0,'no restarted transport after '+terminal);
   assert.equal(f.timers.size,0);
  }finally{gate.resolve(view());await reconnecting.catch(()=>{});f.client.dispose();}
 });

 test(`pending reconnect late failure cannot restart polling after ${terminal}`,async()=>{
 const gate=deferred(),f=transport(route=>route==='bootstrap'?gate.promise:view());
 const reconnecting=f.client.reconnect();reconnecting.catch(()=>{});
 try{
  await flush();f.client[terminal]();gate.reject(Error('synthetic_reconnect_failure'));
  await assert.rejects(reconnecting,/synthetic_reconnect_failure/);await flush();await flush();
  assert.equal(f.client.state.connection,'PAUSED');
  assert.equal(f.calls.length,1,'no new bootstrap or poll after terminal disposal');
  assert.equal(f.timers.size,0);
 }finally{gate.reject(Error('synthetic_cleanup'));await reconnecting.catch(()=>{});f.client.dispose();}
 });
}

test('pending reconnect matching lifecycle still resumes normal polling',async()=>{
 const gate=deferred(),f=transport(route=>route==='bootstrap'?gate.promise:view());
 const reconnecting=f.client.reconnect();
 try{
  await flush();gate.resolve(view({epoch:2}));await reconnecting;await flush();await flush();
  assert.equal(f.client.state.assistId,id);
  assert.equal(f.calls.filter(c=>c.route==='poll').length,1);
  assert.ok(f.timers.size>0,'live matching reconnect keeps its existing poll schedule');
 }finally{gate.resolve(view());await reconnecting.catch(()=>{});f.client.dispose();}
});

const phoneView=(extra={})=>view({role:'STANDALONE',caption:null,captionTtlMs:0,cardTtlMs:0,...extra});
for(const terminal of ['pause','dispose']){
 for(const outcome of ['success','stale_epoch','assist_paused','synthetic_network']){
  test(`late audio stop ${outcome} preserves ${terminal} without reconnecting`,async()=>{
   const gate=deferred();let stops=0;
   const f=transport(route=>route==='audio/stop'&&++stops===1?gate.promise:phoneView(),{transcription:true,standalone:true});
   await f.client.beginVoice();
   const stopping=f.client.endVoice().catch(error=>error);
   try{
    await flush();f.client[terminal]();const callsAtStop=f.calls.length;
    if(outcome==='success')gate.resolve(phoneView({epoch:2}));else gate.reject(Error(outcome));
    await stopping;await flush();
    assert.equal(f.calls.length,callsAtStop,'a stopped lifecycle must not send phone-test or a second stop');
    assert.equal(f.client.state.connection,'PAUSED','late stop must not apply READY or RECONNECTING');
    assert.equal(f.client.state.epoch,1,'the late response must not replace the displayed epoch');
    assert.equal(f.timers.size,0);
    await assert.doesNotReject(()=>f.client.beginVoice(),'an explicit new Start must release the stop latch');
    assert.equal(f.client.state.voiceActive,true);
   }finally{gate.resolve(phoneView());await stopping;await f.client.endVoice();f.client.dispose();}
  });
 }
 test(`stop recovery pending phone-test cannot send a second stop after ${terminal}`,async()=>{
  const gate=deferred();let connects=0,stops=0;
  const f=transport(route=>{
   if(route==='phone-test'&&++connects===2)return gate.promise;
   if(route==='audio/stop'&&++stops===1)throw Error('stale_epoch');
   return phoneView();
  },{transcription:true,standalone:true});
  await f.client.beginVoice();const stopping=f.client.endVoice().catch(error=>error);
  try{
   await flush();assert.equal(connects,2,'exercise the already pending recovery connection');
   f.client[terminal]();const callsAtStop=f.calls.length;
   gate.resolve(phoneView({epoch:2}));await stopping;await flush();
   assert.equal(f.calls.length,callsAtStop,'cancelled recovery must not send another stop');
   assert.equal(f.client.state.connection,'PAUSED');assert.equal(f.client.state.epoch,1);
   assert.equal(f.timers.size,0);
  }finally{gate.resolve(phoneView());await stopping;f.client.dispose();}
 });
}
for(const reason of ['stale_epoch','assist_paused']){
 test(`matching stop lifecycle still recovers ${reason} once and permits the next Start`,async()=>{
  let stops=0;
  const f=transport(route=>{if(route==='audio/stop'&&++stops===1)throw Error(reason);return phoneView({epoch:stops?2:1});},
   {transcription:true,standalone:true});
  try{
   await f.client.beginVoice();await f.client.endVoice({finish:true});
   assert.equal(f.calls.filter(c=>c.route==='phone-test').length,2);
   const stopCalls=f.calls.filter(c=>c.route==='audio/stop');
   assert.equal(stopCalls.length,2);assert.equal(stopCalls[0].body.epoch,1);assert.equal(stopCalls[1].body.epoch,2);
   assert.equal(stopCalls[0].body.finish,true);assert.equal(stopCalls[1].body.finish,true);
   await f.client.beginVoice();assert.equal(f.client.state.voiceActive,true);
  }finally{await f.client.endVoice();f.client.dispose();}
 });
}
