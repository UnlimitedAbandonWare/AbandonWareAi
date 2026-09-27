const {test}=require('node:test'),assert=require('node:assert/strict');
const {createCapture}=require('../../../main/resources/static/assets/display/display-voice.js');
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
const flush=()=>new Promise(setImmediate);
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};};
const id='12345678-1234-4234-8234-123456789abc';
const view=(extra={})=>({assistId:id,epoch:1,version:1,ready:true,state:'RUNNING',audioAvailable:true,audioState:'READY',...extra});
function transport(handler){
  const calls=[],timers=new Map();let n=0;
  const client=createClient({uuid:()=>id,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:k=>timers.delete(k),
    fetchImpl:async(url,options)=>{const route=url.replace('/api/assist/display/','');calls.push({route,body:JSON.parse(options.body)});
      return{ok:true,headers:{get:()=>null},json:async()=>handler(route,JSON.parse(options.body))};}});
  return{client,calls,timers};
}
function capture(start=async()=>{},options={}){
  let time=0,n=0,acquisitions=0,starts=0,stops=0;
  const nodes=[],tracks=[],sent=[],constraints=[],timers=new Map(),permission={state:'granted',addEventListener(_name,fn){this.listener=fn;},removeEventListener(){this.listener=null;}};
  const stream=()=>{const track={readyState:'live',label:'synthetic',getSettings:()=>({deviceId:'input-one'}),stop(){this.readyState='ended';}};
    tracks.push(track);return{getTracks:()=>[track],getAudioTracks:()=>[track]};};
  class Context{
    constructor(){this.state='running';this.sampleRate=48000;this.destination={};this.audioWorklet={addModule:async()=>{}};}
    async resume(){this.state='running';}async close(){this.state='closed';}
    createMediaStreamSource(){return{connect:node=>node,disconnect(){}};}createGain(){return{gain:{value:1},connect(){}};}
  }
  class Worklet{constructor(){this.port={postMessage(){}};nodes.push(this);}connect(node){return node;}disconnect(){}}
  const client={state:{audioAvailable:true,role:'STANDALONE',audioFinished:true,audioRenewAfterMs:60000,connection:'READY'},
    async beginVoice(){starts++;return start(starts);},async endVoice(){stops++;},
    async voiceChunk(sequence,pcm){sent.push({sequence,marker:Buffer.from(pcm,'base64').readInt16LE(0)});}};
  const env={isSecureContext:true,AudioContext:Context,AudioWorkletNode:Worklet,btoa,
    navigator:{onLine:true,permissions:{query:async()=>permission},mediaDevices:{
      getUserMedia:async args=>{constraints.push(args);acquisitions++;return stream();},enumerateDevices:async()=>[{kind:'audioinput',deviceId:'input-one'}]}}};
  const voice=createCapture({client,env,continuous:true,now:()=>time,...options,
    setTimer(fn,ms){timers.set(++n,{fn,ms,due:time+ms});return n;},clearTimer:k=>timers.delete(k)});
  return{voice,client,env,nodes,tracks,sent,timers,permission,constraints,get starts(){return starts;},get acquisitions(){return acquisitions;},
    pcm(marker=1000){const samples=new Int16Array(320);samples.fill(marker);nodes.at(-1).port.onmessage({data:{pcm:samples.buffer}});},
    async advance(ms){time+=ms;for(const[k,timer]of [...timers].sort((a,b)=>a[1].due-b[1].due)){if(timer.due<=time&&timers.delete(k))timer.fn();}await flush();await flush();}
  };
}

test('session preparation has its own deadline instead of the ordinary four-second request limit',async()=>{
  const gate=deferred(),f=transport(route=>route==='bootstrap'?gate.promise:view());
  const starting=f.client.beginVoice();
  try{await flush();assert.ok([...f.timers.values()].some(t=>t.ms>=10000),'preparation must survive the ordinary 4s limit');}
  finally{gate.resolve(view());await starting.catch(()=>{});await f.client.endVoice();f.client.dispose();}
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
test('failed readiness after epoch renewal cleans only the returned audio epoch',async()=>{
 const f=transport(route=>route==='audio/start'?view({epoch:2,audioState:'WAITING'}):view());
 try{await assert.rejects(f.client.beginVoice(),/audio-not-ready/);
   assert.equal(f.calls.find(c=>c.route==='audio/stop').body.epoch,2);assert.equal(f.client.state.voiceActive,false);
 }finally{await f.client.endVoice();f.client.dispose();}
});
