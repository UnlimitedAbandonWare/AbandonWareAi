const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createCapture}=require('../main/resources/static/assets/display/display-voice.js');
const {createClient}=require('../main/resources/static/assets/display/display-conversate.js');
const flush=()=>new Promise(setImmediate);
test('default entry offers direct Fold6 controls and separate lens output',()=>{
  const fs=require('node:fs');const base=__dirname+'/../main/resources/static/assets/display/';
  const html=fs.readFileSync(base+'index.html','utf8');
  assert.match(html,/data-fold6-test/);assert.match(html,/id="microphone"/);
  assert.match(html,/id="context-file"/);assert.match(html,/id="device-debug"/);
  assert.match(html,/<option value="0" selected>/);
  assert.match(fs.readFileSync(base+'lens.html','utf8'),/src="lens\.js/);
});
test('background TXT uses its owned context route without submitting a question',async()=>{
  const routes=[];
  const c=createClient({transcription:true,standalone:true,uuid:()=> '12345678-1234-4234-8234-123456789abc',setTimer:()=>1,clearTimer(){},fetchImpl:async(url,options)=>{
    routes.push({url,body:JSON.parse(options.body)});return{ok:true,json:async()=>({assistId:'12345678-1234-4234-8234-123456789abc',epoch:1,version:1,ready:true,role:'STANDALONE',captionTtlMs:0,cardTtlMs:0,testStatus:{backgroundChars:9,asr:{provider:'local'},processing:false}})};
  }});
  c.start();await flush();await c.background('회의 배경 자료');
  assert.equal(routes.at(-1).url,'/api/assist/display/context');assert.equal(routes.at(-1).body.text,'회의 배경 자료');
  assert.equal(routes.some(r=>r.url.endsWith('/input')),false);assert.equal(c.state.testStatus.backgroundChars,9);c.dispose();
});
function captureFixture(overrides={}){
  let time=0,next=0,node,stops=0,starts=0,trackStops=0;const timers=new Map(),chunks=[],contexts=[];
  const track={label:'Synthetic input',stop(){trackStops++;}};
  const media={getTracks:()=>[track],getAudioTracks:()=>[track]};
  class Context{constructor(){contexts.push(this);this.audioWorklet={addModule:async()=>{}};this.destination={};this.state='running';this.resumes=0;this.closes=0;this.onstatechange=null;}async resume(){this.resumes++;this.state='running';if(this.onstatechange)this.onstatechange();}async close(){this.closes++;this.state='closed';if(this.onstatechange)this.onstatechange();}createMediaStreamSource(){return{connect:()=>node};}createGain(){return{gain:{value:1},connect(){}};}}
  const env={isSecureContext:true,navigator:{mediaDevices:{getUserMedia:async()=>media}},AudioContext:Context,AudioWorkletNode:class{constructor(){node=this;this.port={postMessage(message){if(message==='finish')queueMicrotask(()=>node.port.onmessage({data:{stopped:true}}));}};}disconnect(){}connect(x){return x;}},btoa:b=>Buffer.from(b,'binary').toString('base64')};
  const client={state:{audioAvailable:true,audioFinished:true,role:'PHONE',linked:true},beginVoice:async()=>{starts++;},endVoice:async()=>{stops++;},voiceChunk:async(s,pcm)=>{chunks.push({s,length:pcm.length});}};
  Object.assign(env.navigator.mediaDevices,overrides);
  const c=createCapture({client,env,continuous:true,now:()=>time,setTimer(fn,ms){timers.set(++next,{fn,ms});return next;},clearTimer(id){timers.delete(id);}});
  return{c,client,timers,track,chunks,media,contexts,get starts(){return starts;},get stops(){return stops;},get trackStops(){return trackStops;},frame(value=0){const pcm=new Int16Array(3840);pcm.fill(value);node.port.onmessage({data:{pcm:pcm.buffer}});},advance(ms){time+=ms;},async run(ms){const entry=[...timers].find(([,t])=>t.ms===ms);assert.ok(entry,'timer '+ms);timers.delete(entry[0]);await entry[1].fn();await flush();}};
}
test('continuous capture uses only paired phone; silence is distinct from missing frames',async()=>{
  const f=captureFixture();f.client.state.role='DISPLAY';assert.equal(await f.c.start(),false);assert.equal(f.starts,0);
  f.client.state.role='PHONE';assert.equal(await f.c.start(),true);f.frame();await flush();assert.equal(f.c.state.level,0);assert.equal(f.c.state.frames,1);assert.equal(f.c.state.bytes,7680);
  f.advance(6000);await f.run(1000);assert.equal(f.c.state.phase,'WAITING');assert.equal(f.c.isActive(),true);assert.equal(f.stops,0);assert.equal(f.trackStops,0);f.frame();await flush();assert.equal(f.c.state.phase,'LISTENING');await f.c.stop();
});
test('standalone capture remains active until the user stops',async()=>{
  const f=captureFixture();f.client.state.role='STANDALONE';f.client.state.linked=false;
  f.client.endVoice=async options=>{assert.deepEqual(options,{finish:true});f.client.state.audioFinished=true;};
  assert.equal(await f.c.start(),true);assert.equal(f.starts,1);
  assert.equal([...f.timers.values()].some(t=>t.ms===540000),true);
  assert.equal([...f.timers.values()].some(t=>t.ms===10000||t.ms===60000),false);
  f.frame(4000);await flush();assert.equal(f.chunks.length,1);
  assert.equal([...f.timers.values()].some(t=>t.ms===1200000),false);assert.equal(f.c.isActive(),true);await f.c.stop();assert.equal(f.trackStops,1);
});
test('planned cloud renewal drains the old stream and retains newly arriving PCM once',async()=>{
  const f=captureFixture();let finish;const ended=new Promise(resolve=>{finish=resolve;});const starts=[];
  f.client.endVoice=async options=>{assert.deepEqual(options,{finish:true});await ended;};
  f.client.beginVoice=async options=>{starts.push(options);};
  await f.c.start();f.frame(1000);await flush();
  const renewal=f.run(540000);await flush();f.frame(2000);f.frame(3000);await flush();
  assert.equal(f.chunks.length,1);finish();await renewal;await flush();
  assert.deepEqual(starts.at(-1),{continuation:true});
  assert.deepEqual(f.chunks.map(c=>c.s),[0,0,1]);
  assert.equal(f.trackStops,0);f.client.endVoice=async()=>{};await f.c.stop();
});
test('local Whisper capture does not restart on the cloud stream timer',async()=>{
  const f=captureFixture();f.client.state.role='STANDALONE';f.client.state.testStatus={asr:{provider:'whisper'}};
  await f.c.start();assert.equal([...f.timers.values()].some(t=>t.ms===540000),false);await f.c.stop();
});
test('manual finish during a planned renewal keeps buffered tail and stops tracks immediately',async()=>{
  const f=captureFixture();let release,ends=0;const pending=new Promise(resolve=>{release=resolve;});
  f.client.endVoice=async options=>{assert.deepEqual(options,{finish:true});if(++ends===1)await pending;};
  await f.c.start();f.frame(1000);await flush();
  const renewal=f.run(540000);await flush();f.frame(2000);await flush();
  const finishing=f.c.finish();assert.equal(f.trackStops,1);await flush();
  release();await Promise.all([renewal,finishing]);await flush();
  assert.deepEqual(f.chunks.map(c=>c.s),[0,0]);assert.equal(ends,2);
  assert.equal(f.c.state.phase,'OFF');assert.equal(f.timers.size,0);
});
test('standalone temporary connection failure waits before resuming the same microphone',async()=>{
  const f=captureFixture();f.client.state.role='STANDALONE';f.client.state.linked=false;
  f.client.voiceChunk=async()=>{throw Object.assign(Error('audio_transport_failed'),{status:503});};
  assert.equal(await f.c.start(),true);f.frame(4000);await flush();await flush();
  assert.equal(f.starts,1);assert.equal(f.c.state.phase,'WAITING');assert.equal(f.c.isActive(),true);assert.equal(f.trackStops,0);f.client.voiceChunk=async()=>{};await f.run(1000);await f.run(1000);assert.equal(f.starts,2);assert.equal(f.trackStops,0);await f.c.stop();
});
test('unanswered permission stops, and a late permission grant releases the track without server start',async()=>{
  let resolve;const f=captureFixture({getUserMedia:()=>new Promise(r=>{resolve=r;})});const start=f.c.start();await f.run(15000);
  assert.equal(f.c.isActive(),false);resolve(f.media);assert.equal(await start,false);assert.equal(f.trackStops,1);assert.equal(f.starts,0);
});
test('provider connection rolls over without replay and manual stop releases microphone',async()=>{
  const f=captureFixture();await f.c.start();f.frame(4000);await flush();assert.equal(f.chunks[0].s,0);
  await f.run(540000);assert.equal(f.starts,2);assert.equal(f.stops,1);assert.equal(f.trackStops,0);f.frame();await flush();assert.equal(f.chunks[1].s,0);
  assert.equal([...f.timers.values()].some(t=>t.ms===1200000),false);await f.c.stop();assert.equal(f.c.isActive(),false);assert.equal(f.trackStops,1);assert.equal(f.stops,2);assert.equal(f.timers.size,0);
});
test('permission denial and device end have separate bounded codes',async()=>{
  const denied=captureFixture({getUserMedia:async()=>{const e=Error();e.name='NotAllowedError';throw e;}});assert.equal(await denied.c.start(),false);assert.equal(denied.c.state.errorCode,'microphone_permission_denied');
  const ended=captureFixture();await ended.c.start();ended.track.onended();await flush();assert.equal(ended.c.state.errorCode,'microphone_device_ended');assert.equal(ended.c.isActive(),false);
});
test('provider configuration and quota limits preserve microphone without retrying paid traffic',async()=>{
  for(const code of ['asr_auth_failed','asr_quota_exceeded','asr_audio_format_invalid','asr_budget_unavailable']){
    const f=captureFixture();f.client.voiceChunk=async()=>{throw Object.assign(Error(code),{status:503});};
    await f.c.start();f.frame();await flush();await flush();
    assert.equal(f.starts,1,code);assert.equal(f.stops,1);assert.equal(f.c.isActive(),true);assert.equal(f.c.state.reconnects,0);assert.equal(f.c.state.sttPausedReason,code);assert.equal(f.trackStops,0);await f.c.stop();
  }
});
test('remote stop or epoch conflict closes capture without reconnecting',async()=>{
  const f=captureFixture();
  f.client.voiceChunk=async()=>{throw Object.assign(Error('stale_epoch'),{status:409});};
  await f.c.start();f.frame();await flush();await flush();
  assert.equal(f.starts,1);assert.equal(f.c.state.reconnects,0);
  assert.equal(f.c.isActive(),false);assert.equal(f.trackStops,1);
});
test('optional finish stops tracks immediately then waits for a confirmed drain',async()=>{
  const f=captureFixture();let release,options;
  f.client.endVoice=async value=>{options=value;await new Promise(resolve=>release=resolve);f.client.state.audioFinished=true;};
  await f.c.start();const ending=f.c.finish();
  assert.equal(f.trackStops,1);assert.equal(f.c.isActive(),false);assert.equal(f.c.state.phase,'FINISHING');await flush();assert.deepEqual(options,{finish:true});
  release();await ending;assert.equal(f.c.state.phase,'OFF');assert.equal(f.timers.size,0);
});
const id='12345678-1234-4234-8234-123456789abc';
test('finish drains pending and queued PCM in order before ASR finalization and blocks restart',async()=>{
  const f=captureFixture();let release;const calls=[];
  f.client.voiceChunk=async seq=>{calls.push('chunk-'+seq);if(seq===0)await new Promise(r=>release=r);};
  f.client.endVoice=async options=>{calls.push(options?.finish?'finish':'cancel');f.client.state.audioFinished=true;};
  await f.c.start();f.frame(1000);f.frame(1000);const done=f.c.finish();
  assert.equal(f.trackStops,1);assert.equal(await f.c.start(),false);assert.deepEqual(calls,['chunk-0']);
  release();await done;assert.deepEqual(calls,['chunk-0','chunk-1','finish']);assert.equal(f.c.state.phase,'OFF');
});
test('worklet flushes its partial tail before the finish acknowledgement',()=>{
  const fs=require('node:fs'),vm=require('node:vm');let Type;const messages=[];
  vm.runInNewContext(fs.readFileSync(__dirname+'/../main/resources/static/conversate/pcm-worklet.js','utf8'),{
    AudioWorkletProcessor:class{constructor(){this.port={postMessage:m=>messages.push(m)};}},sampleRate:48000,registerProcessor:(_,c)=>Type=c});
  const worklet=new Type();worklet.process([[new Float32Array(480).fill(.25)]]);worklet.port.onmessage({data:'finish'});
  assert.equal(messages[0].pcm.byteLength,640);assert.equal(messages[1].stopped,true);assert.equal(worklet.process([]),false);
  assert.ok(new Int16Array(messages[0].pcm).slice(0,160).every(v=>v>0));assert.ok(new Int16Array(messages[0].pcm).slice(160).every(v=>v===0));
});
test('standalone client bootstraps through the gated phone-test route and starts owned audio',async()=>{
  const routes=[];const view={assistId:id,epoch:1,version:1,ready:true,role:'STANDALONE',linked:false,audioAvailable:true,audioState:'READY',captionTtlMs:0,cardTtlMs:0,hintsEnabled:false};
  const c=createClient({transcription:true,standalone:true,uuid:()=>id,setTimer:()=>1,clearTimer(){},fetchImpl:async(url)=>{
    routes.push(url);return{ok:true,json:async()=>view};
  }});
  c.start();await flush();await c.beginVoice();
  assert.ok(routes[0].endsWith('/phone-test'));assert.ok(routes.some(r=>r.endsWith('/audio/start')));
  assert.equal(routes.some(r=>r.includes('/link/')),false);assert.equal(c.state.role,'STANDALONE');await c.endVoice();c.dispose();
});
test('pairing join sends only the production PairJoin fields',async()=>{
  let payload;
  const c=createClient({transcription:true,uuid:()=>id,setTimer:()=>1,clearTimer(){},fetchImpl:async(url,options)=>{
    if(url.endsWith('/link/join')){payload=JSON.parse(options.body);return{ok:true,json:async()=>({pending:true,confirmation:'1234'})};}
    return{ok:false,status:409,headers:{get:()=>null},json:async()=>({reason:'link_pending'})};
  }});
  await c.join('123456');await flush();
  assert.deepEqual(Object.keys(payload).sort(),['clientId','code','requestId']);c.dispose();
});
test('caption revisions replace text and stale or expired snapshots do not erase the last text',async()=>{
  const timers=new Map();let next=0,now=1_000_000;
  let view={assistId:id,epoch:2,version:2,ready:true,role:'DISPLAY',linked:true,captionTtlMs:20000,cardTtlMs:0,audioChunks:4,caption:{utteranceId:'speech-1',revision:1,isFinal:false,text:'임시'}};
  const c=createClient({transcription:true,uuid:()=>id,now:()=>now,setTimer(fn,ms){timers.set(++next,{fn,ms});return next;},clearTimer(id){timers.delete(id);},fetchImpl:async()=>{now+=100;return{ok:true,json:async()=>view};}});
  const poll=async()=>{const t=[...timers].find(([,t])=>t.ms===1000);assert.ok(t);timers.delete(t[0]);await t[1].fn();await flush();};
  c.start();await flush();assert.equal(c.state.caption.text,'임시');assert.equal([...timers.values()].some(t=>t.ms===19900),false);
  view={...view,version:3,caption:{...view.caption,revision:2,isFinal:true,text:'확정 문장'}};await poll();assert.equal(c.state.caption.text,'확정 문장');
  view={...view,epoch:1,version:999,caption:{...view.caption,text:'오래된 문장'}};await poll();assert.equal(c.state.caption.text,'확정 문장');
  view={...view,epoch:2,version:4,caption:null,captionTtlMs:0};await poll();assert.equal(c.state.caption.text,'확정 문장');c.dispose();
});
test('invalid caption cannot poison version watermark and reconnect is bounded',async()=>{
  const timers=new Map();let next=0,calls=0;
  const c=createClient({transcription:true,uuid:()=>id,setTimer(fn,ms){timers.set(++next,{fn,ms});return next;},clearTimer(id){timers.delete(id);},fetchImpl:async()=>{calls++;return{ok:true,json:async()=>({assistId:id,epoch:1,ready:true,version:999,captionTtlMs:20000,cardTtlMs:0,caption:{text:42}})};}});
  c.start();await flush();for(const delay of [1000,2000]){const t=[...timers].find(([,t])=>t.ms===delay);timers.delete(t[0]);await t[1].fn();await flush();}
  assert.equal(c.state.connection,'DISCONNECTED');assert.equal(c.state.caption,null);assert.equal(calls,3);assert.equal(timers.size,0);c.dispose();
});


test('temporary track mute preserves capture and resumes without a new microphone or server start',async()=>{
 const f=captureFixture();await f.c.start();f.track.onmute();f.advance(6000);await f.run(1000);
 assert.equal(f.c.isActive(),true);assert.equal(f.starts,1);assert.equal(f.stops,0);assert.equal(f.trackStops,0);
 f.track.onunmute();f.frame(1000);await flush();assert.equal(f.c.state.phase,'LISTENING');assert.equal(f.starts,1);
 assert.ok(f.c.state.events.some(e=>e.event==='audio_waiting'));assert.ok(f.c.state.events.some(e=>e.event==='audio_resumed'));await f.c.stop();
});
test('provider disconnect retries with backoff while preserving the active microphone',async()=>{
 const f=captureFixture();let recovering=false;
 f.client.voiceChunk=async()=>{throw Object.assign(Error('asr_provider_disconnected'),{status:503});};
 await f.c.start();f.frame();await flush();assert.equal(f.c.state.phase,'WAITING');
 f.client.beginVoice=async()=>{if(!recovering)throw Object.assign(Error('asr_unavailable'),{status:503});};
 await f.run(1000);await f.run(1000);assert.equal(f.trackStops,0);assert.equal(f.c.isActive(),true);assert.equal(f.c.state.phase,'WAITING');
 recovering=true;f.client.voiceChunk=async()=>{};await f.run(2000);assert.equal(f.c.state.phase,'LISTENING');assert.equal(f.trackStops,0);await f.c.stop();
});

test('initial provider wait retains microphone and automatically retries',async()=>{
 const f=captureFixture();let attempts=0;
 f.client.beginVoice=async()=>{if(++attempts===1)throw Object.assign(Error('asr_unavailable'),{status:503});};
 assert.equal(await f.c.start(),true);assert.equal(f.c.state.phase,'WAITING');assert.equal(f.trackStops,0);
 assert.equal([...f.timers.values()].some(t=>t.ms===540000),false);
 await f.run(1000);assert.equal(attempts,2);assert.equal(f.c.state.phase,'LISTENING');assert.equal(f.trackStops,0);await f.c.stop();
});
test('persistent provider failure pauses retries until a new utterance after cooldown',async()=>{
 const f=captureFixture();let attempts=0,recovered=false;
 await f.c.start();
 f.client.beginVoice=async()=>{attempts++;if(!recovered)throw Object.assign(Error('asr_unavailable'),{status:503});};
 f.client.voiceChunk=async()=>{throw Object.assign(Error('audio_transport_failed'),{status:503});};
 f.frame(4000);await flush();await f.run(1000);await f.run(1000);await f.run(2000);await f.run(4000);
 assert.equal(attempts,3);assert.equal(f.c.isActive(),true);assert.equal(f.trackStops,0);
 assert.equal([...f.timers.values()].some(t=>t.ms>1000),false);
 f.advance(31000);f.frame(4000);await flush();assert.equal(attempts,3);
 f.frame(0);f.advance(2100);f.frame(0);await flush();assert.equal(attempts,3);
 recovered=true;f.client.voiceChunk=async()=>{};f.frame(4000);await flush();await flush();
 assert.equal(attempts,4);assert.equal(f.c.state.phase,'LISTENING');assert.equal(f.trackStops,0);await f.c.stop();
});
test('Fold developer settings expose hint-context controls and a new-context action',()=>{
  const fs=require('node:fs');const html=fs.readFileSync(__dirname+'/../main/resources/static/assets/display/index.html','utf8');
  assert.match(html,/id="hint-context"/);assert.match(html,/id="hc-history"/);assert.match(html,/id="hc-window"/);
  assert.match(html,/id="hc-chars"/);assert.match(html,/id="hc-tokens"/);assert.match(html,/id="hc-topic"/);
  assert.match(html,/id="hc-apply"/);assert.match(html,/id="hc-reset"/);assert.match(html,/id="hc-status"/);
});
test('page return resumes a suspended audio context without a new microphone or server start',async()=>{
  const f=captureFixture();await f.c.start();assert.equal(f.contexts.length,1);
  const initialResumes=f.contexts[0].resumes;
  f.contexts[0].state='suspended';assert.equal(await f.c.resume(),true);
  assert.equal(f.contexts[0].resumes,initialResumes+1);assert.equal(f.c.state.audioContext,'running');
  assert.equal(f.starts,1);assert.equal(f.stops,0);assert.equal(f.trackStops,0);assert.equal(f.c.isActive(),true);
  assert.ok(f.c.state.events.some(e=>e.event==='audio_resumed'&&e.reason==='context_resumed'));await f.c.stop();
});
test('page return on a running context is a no-op and resume after user stop never restarts',async()=>{
  const f=captureFixture();assert.equal(await f.c.resume(),false);
  await f.c.start();const initialResumes=f.contexts[0].resumes;
  assert.equal(await f.c.resume(),true);assert.equal(f.contexts[0].resumes,initialResumes);
  await f.c.stop();assert.equal(await f.c.resume(),false);assert.equal(f.contexts[0].resumes,initialResumes);
  assert.equal(f.starts,1);assert.equal(f.trackStops,1);assert.equal(f.c.isActive(),false);
});
