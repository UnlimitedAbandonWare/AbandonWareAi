const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createCapture}=require('../../../main/resources/static/assets/display/display-voice.js');
const flush=()=>new Promise(setImmediate);

function fixture({closeContext=()=>Promise.resolve()}={}) {
  let ended=0,stopped=0,timer,contextCloses=0;
  const track={stop(){stopped++;}}, media={getTracks:()=>[track],getAudioTracks:()=>[track]};
  class Context {
    constructor(){this.audioWorklet={addModule:async()=>{}};this.destination={};}
    async resume(){}
    close(){contextCloses++;return closeContext();}
    createMediaStreamSource(){return {connect:node=>node};}
    createGain(){return {gain:{value:1},connect(){}};}
  }
  class Worklet {constructor(){this.port={postMessage(){}};}connect(node){return node;}disconnect(){}}
  const client={state:{audioAvailable:true},async beginVoice(){},async voiceChunk(){},async endVoice(){ended++;}};
  const env={isSecureContext:true,navigator:{mediaDevices:{getUserMedia:async()=>media}},AudioContext:Context,AudioWorkletNode:Worklet,btoa};
  const capture=createCapture({client,env,setTimer(fn){timer=fn;return 1;},clearTimer(){timer=null;}});
  return {capture,track,counts:()=>({ended,stopped,contextCloses}),hasTimer:()=>timer!=null};
}

test('page-stop sends server cancellation even while AudioContext close is suspended',async()=>{
  let release;
  const f=fixture({closeContext:()=>new Promise(resolve=>release=resolve)});
  await f.capture.start();const stopping=f.capture.stop();
  try {
    await flush();
    assert.equal(f.counts().stopped,1);
    assert.equal(f.counts().ended,1,'Provider stop must not wait for the audio graph close promise');
    assert.equal(f.capture.state.phase,'OFF');
  } finally {release();await stopping;}
});

test('capture has no duration deadline; explicit stop and microphone removal close exactly once',async()=>{
  for(const action of ['user-stop','track-ended']) {
    const f=fixture();await f.capture.start();
    assert.equal(f.hasTimer(),false);if(action==='user-stop')await f.capture.stop();else f.track.onended();
    await flush();await f.capture.stop();
    assert.deepEqual(f.counts(),{ended:1,stopped:1,contextCloses:1});
    assert.equal(f.capture.isActive(),false);
  }
});

test('page disposal preserves the pending keepalive stop while cancelling ordinary requests',async()=>{
  const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
  const id='12345678-1234-4234-8234-123456789abc';
  const timers=new Map(), pending=new Map();let nextTimer=0;
  const view={assistId:id,epoch:1,version:1,ready:true,state:'RUNNING',audioAvailable:true,audioState:'READY'};
  const response=()=>({ok:true,headers:{get:()=>null},json:async()=>view});
  const client=createClient({uuid:()=>id,setTimer(fn,ms){timers.set(++nextTimer,{fn,ms});return nextTimer;},clearTimer:n=>timers.delete(n),
    fetchImpl:async(url,options)=>{
      const route=url.split('/').pop();
      if(route==='chunk'||route==='stop')return new Promise((resolve,reject)=>{
        pending.set(route,{options,resolve:()=>resolve(response())});
        options.signal.addEventListener('abort',()=>reject(Error('aborted')),{once:true});
      });
      return response();
    }});
  await client.beginVoice();
  const chunk=client.voiceChunk(0,'AAA=').catch(error=>error);
  const stopping=client.endVoice();
  // This is the app's pagehide order: voice.stop(), then client.dispose().
  client.dispose();
  try {
    assert.equal(pending.get('chunk').options.signal.aborted,true);
    assert.equal(pending.get('stop').options.keepalive,true);
    assert.equal(pending.get('stop').options.signal.aborted,false,'Page disposal must not abort provider cancellation');
  } finally {pending.get('stop').resolve();await stopping.catch(()=>{});await chunk;}
});


test('pagehide and bfcache restore never restart capture before an explicit start',async()=>{
  const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
  const source=fs.readFileSync(path.resolve(__dirname,'../../../main/resources/static/assets/display/app.js'),'utf8');
  const begin=source.indexOf("  window.addEventListener('online',");
  const end=source.indexOf("  $('caption-card').focus();client.start();",begin);
  assert.ok(begin>=0&&end>begin,'Use the actual app lifecycle listeners');
  const handlers=new Map(),counts={stop:0,start:0,dispose:0,connectionStart:0};
  let active=true;
  const voice={isActive:()=>active,stop(){counts.stop++;active=false;return Promise.resolve();},start(){counts.start++;active=true;return Promise.resolve();},reconnect(){return Promise.resolve();}};
  const client={dispose(){counts.dispose++;},start(){counts.connectionStart++;}};
  vm.runInNewContext(source.slice(begin,end),{window:{addEventListener:(name,fn)=>handlers.set(name,fn)},voice,client,clearTimeout(){},codeTimer:null,lensRestoreTimer:null});
  handlers.get('pagehide')();await flush();
  assert.equal(counts.stop,1);assert.equal(counts.dispose,1);assert.equal(active,false);
  handlers.get('pageshow')({persisted:true});await flush();
  assert.equal(counts.connectionStart,1,'The connection may be restored');
  assert.equal(counts.start,0,'Page exit must require a new explicit microphone start');
  assert.equal(active,false);
  await voice.start();assert.equal(counts.start,1);assert.equal(active,true);
});
