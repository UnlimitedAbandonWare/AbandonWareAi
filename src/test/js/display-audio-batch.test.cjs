const {test}=require('node:test'),assert=require('node:assert/strict');
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
const {createCapture}=require('../../../main/resources/static/assets/display/display-voice.js');
const flush=()=>new Promise(setImmediate),id='12345678-1234-4234-8234-123456789abc';
const view=()=>({assistId:id,epoch:1,version:1,ready:true,state:'RUNNING',audioAvailable:true,audioState:'READY'});
function clientFixture(ack){
 const calls=[],timers=new Map();let n=0;
 const client=createClient({uuid:()=>id,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:k=>timers.delete(k),
 fetchImpl:async(url,options)=>{const route=url.replace('/api/assist/display/',''),body=JSON.parse(options.body);calls.push({route,body});
  return{ok:true,headers:{get:()=>null},json:async()=>route==='audio/chunk-batch'?ack(body):view()};}});
 return{client,calls};
}
function captureFixture({latency=0,failFirst=false}={}){
 let time=0,n=0,release,active=0,maxActive=0;
 const gate=new Promise(r=>release=r),timers=new Map(),nodes=[],batches=[],single=[];
 function timer(fn,ms){timers.set(++n,{fn,due:time+ms});return n;}
 class Context{constructor(){this.audioWorklet={addModule:async()=>{}};this.destination={};}async resume(){}async close(){}
  createMediaStreamSource(){return{connect:()=>({connect:()=>({connect(){}})})};}createGain(){return{gain:{value:1}};}}
 class Node{constructor(){this.port={postMessage(){}};nodes.push(this);}disconnect(){}}
 const receive=async(frames,target)=>{active++;maxActive=Math.max(maxActive,active);
  const batch={at:time,sequences:frames.map(f=>f.sequence),markers:frames.map(f=>Buffer.from(f.pcm,'base64').readInt16LE(0))};target.push(batch);
  try{if(latency)await new Promise(resolve=>timer(resolve,latency));if(failFirst&&batches.length===1)throw Error('asr_unavailable');}
  finally{active--;}};
 const client={state:{role:'STANDALONE',audioAvailable:true,audioFinished:true,audioRenewAfterMs:60000},
  beginVoice:()=>gate,endVoice:async()=>{},voiceBatch:frames=>receive(frames,batches),voiceChunk:(sequence,pcm)=>receive([{sequence,pcm}],single)};
 const env={isSecureContext:true,AudioContext:Context,AudioWorkletNode:Node,btoa,
  navigator:{onLine:true,mediaDevices:{getUserMedia:async()=>({getTracks:()=>[{stop(){}}],getAudioTracks:()=>[]})}}};
 const voice=createCapture({client,env,continuous:true,now:()=>time,setTimer:timer,clearTimer:k=>timers.delete(k)});
 return{voice,batches,single,release,get maxActive(){return maxActive;},
  pcm(marker){const samples=new Int16Array(3840);samples.fill(marker);nodes[0].port.onmessage({data:{pcm:samples.buffer}});},
  async advance(ms){time+=ms;for(let turns=0;turns<10;turns++){
   const due=[...timers].filter(([,t])=>t.due<=time).sort((a,b)=>a[1].due-b[1].due);
   if(!due.length)break;for(const[k,t]of due)if(timers.delete(k))t.fn();await flush();await flush();
  }await flush();}};
}
test('client posts one ordered batch and accepts only its exact ordered ACK',async()=>{
 const f=clientFixture(body=>({view:view(),acceptedCount:body.frames.length,acceptedThrough:body.frames.at(-1).sequence}));
 try{await f.client.beginVoice();assert.equal(typeof f.client.voiceBatch,'function');
  await f.client.voiceBatch([{sequence:0,pcm:'AAA='},{sequence:1,pcm:'AAA='}]);
  assert.equal(f.calls.filter(c=>c.route==='audio/chunk-batch').length,1);assert.equal(f.calls.filter(c=>c.route==='audio/chunk').length,0);
 }finally{await f.client.endVoice();f.client.dispose();}
});
test('client rejects partial or mismatched ACK without replaying an ambiguous batch',async()=>{
 const f=clientFixture(()=>({view:view(),acceptedCount:1,acceptedThrough:0}));
 try{await f.client.beginVoice();assert.equal(typeof f.client.voiceBatch,'function');
  await assert.rejects(f.client.voiceBatch([{sequence:0,pcm:'AAA='},{sequence:1,pcm:'AAA='}]),/display-contract/);
  assert.equal(f.calls.filter(c=>c.route==='audio/chunk-batch').length,1);
 }finally{await f.client.endVoice();f.client.dispose();}
});
test('capture coalesces at most eight frames serially and caps backlog catchup at 1.25x',async()=>{
 const f=captureFixture(),starting=f.voice.start();
 try{await flush();for(let i=1;i<=10;i++)f.pcm(i);f.release();await starting;await flush();
  assert.equal(f.batches.length,0,'initial backlog must also earn its pacing allowance');
  await f.advance(1535);assert.equal(f.batches.length,0);await f.advance(1);
  assert.equal(f.batches.length,1);assert.deepEqual(f.batches[0].sequences,[0,1,2,3,4,5,6,7]);assert.equal(f.single.length,0);
  await f.advance(383);assert.equal(f.batches.length,1);await f.advance(1);
  assert.equal(f.batches.length,2);assert.deepEqual(f.batches[1].sequences,[8,9]);assert.equal(f.batches[1].at,1920);assert.equal(f.maxActive,1);
 }finally{f.release();await starting;await f.voice.stop();}
});
test('one-second HTTP round trips sustain live PCM with bounded sequential batching',async()=>{
 const f=captureFixture({latency:1000});f.release();await f.voice.start();
 try{for(let i=1;i<=80;i++){f.pcm(i);await f.advance(240);}
  for(let i=0;i<15;i++)await f.advance(240);
  const received=f.batches.flatMap(b=>b.markers);
  assert.equal(received.length,80);assert.deepEqual(received,Array.from({length:80},(_,i)=>i+1));
  assert.ok(f.batches.every(b=>b.markers.length<=8));assert.equal(f.maxActive,1);assert.equal(f.single.length,0);assert.equal(f.voice.state.droppedAudioMs,0);
 }finally{await f.voice.stop();await f.advance(2000);}
});
test('failed batch is counted as unconfirmed audio and never replayed on a new segment',async()=>{
 const f=captureFixture({failFirst:true}),starting=f.voice.start();
 try{await flush();for(let i=1;i<=8;i++)f.pcm(i);f.release();await starting;await flush();
  await f.advance(1536);assert.equal(f.batches.length,1);assert.equal(f.voice.state.droppedAudioMs,1920);
  f.pcm(99);await f.advance(2000);await flush();await f.advance(192);
  assert.equal(f.batches.length,2);assert.deepEqual(f.batches[1].markers,[99]);assert.deepEqual(f.batches[1].sequences,[0]);
  await f.voice.stop();await f.advance(120000);assert.equal(f.batches.length,2);
 }finally{f.release();await starting;await f.voice.stop();}
});
