const {test}=require('node:test');const assert=require('node:assert/strict');
const {createCapture}=require('../../../main/resources/static/assets/display/display-voice.js');
const flush=()=>new Promise(setImmediate);
function fixture(seconds=5){const timers=new Map(),calls=[],nodes=[];let n=0,tracks=0;
 class Audio {constructor(){this.audioWorklet={addModule:async()=>{}};this.destination={};}async resume(){}async close(){}createGain(){return {gain:{value:1}};}createMediaStreamSource(){return {connect:()=>({connect:()=>({connect(){}})})};}}
 class Worklet {constructor(){this.port={postMessage:(message)=>{if(message==='finish')queueMicrotask(()=>this.port.onmessage({data:{stopped:true}}));}};}disconnect(){}}
 const client={state:{role:'STANDALONE',audioAvailable:true,audioFinished:true,testStatus:{asr:{renewAfterMs:65000}}},async beginVoice(options){calls.push(['start',options]);},async endVoice(options){calls.push(['end',options]);},async voiceChunk(){}};
 const env={isSecureContext:true,btoa,AudioContext:Audio,AudioWorkletNode:Worklet,navigator:{mediaDevices:{getUserMedia:async()=>({getTracks:()=>[{stop(){tracks++;}}],getAudioTracks:()=>[]})}}};
 const capture=createCapture({client,env,continuous:true,segmentSeconds:()=>seconds,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id)});
 return {capture,calls,timers,tracks:()=>tracks};
}
test('five-second segment drains before continuation and manual stop cancels the next segment',async()=>{
 const f=fixture();await f.capture.start();const next=[...f.timers.values()].find(t=>t.ms===5000);assert.ok(next);
 await next.fn();await flush();assert.equal(f.calls.filter(c=>c[0]==='start').length,2);
 assert.deepEqual(f.calls[1],['end',{finish:true}]);assert.equal(f.calls[2][1].continuation,true);
 assert.equal(f.capture.state.segments,2);assert.equal(f.tracks(),0);assert.equal(f.capture.state.phase,'LISTENING');assert.equal(f.capture.state.reconnects,0);
 await f.capture.stop();assert.equal(f.timers.size,0);assert.equal(f.capture.isActive(),false);
});

test('continuous capture has no duration stop and repeated provider renewal keeps one microphone',async()=>{
 const f=fixture(0);await f.capture.start();assert.equal([...f.timers.values()].some(t=>t.ms===1200000||t.ms===60000),false);
 for(let n=0;n<112;n++){const timer=[...f.timers.values()].find(t=>t.ms===65000);assert.ok(timer);await timer.fn();await flush();assert.equal(f.capture.state.phase,'LISTENING');assert.equal(f.tracks(),0);}
 assert.equal(f.capture.state.reconnects,0);await f.capture.stop();assert.equal(f.tracks(),1);
});
