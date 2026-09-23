const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const code=fs.readFileSync('main/resources/static/conversate/app.js','utf8');
test('newer bootstrap budget observation survives an older capture snapshot',async()=>{
  const s=screen({deferBootstrap:true}),budget=used=>({verificationCapMicros:1000000,monthlyCapMicros:5000000,reserved:{verificationMicros:used,monthlyMicros:used,totalMicros:used,requests:1}});
  s.bootstrapReplies[0].resolve({asrAvailable:true,stt:{observedAt:20,budget:budget(300000)}});await new Promise(setImmediate);await s.el('start').onclick();
  s.streams.at(-1).handlers.assist({data:JSON.stringify({...s.initial,version:2,audio:{runtime:{cloudStatus:{observedAt:10,budget:budget(0)}}}})});
  assert.match(s.el('sttDiagnostics').textContent,/검증 누적 US\$0.300000/);assert.match(s.el('sttDiagnostics').textContent,/잔여 US\$0.700000/);s.context.window.listeners.pagehide();
});
test('STT diagnostics preserve unknowns and separate reserved budget from invoice and free credit',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  assert.match(s.el('sttDiagnostics').textContent,/검증 누적 미관측 \/ 미관측/);
  s.streams.at(-1).handlers.assist({data:JSON.stringify({...s.initial,version:2,audio:{chunks:1,lastAsrMs:500,runtime:{provider:'whisper',device:'cuda',gpu:'RTX3060_b7c4e670',reason:'primary',vramFreeMiB:8192,vramObservedAt:1789385333000,queueLength:0,errors:0,cloudStatus:{state:'observed',cloud:{configured:true,state:'CLOSED'},budget:{verificationCapMicros:1000000,monthlyCapMicros:5000000,reserved:{verificationMicros:200000,monthlyMicros:300000,totalMicros:400000,requests:10}}}}}})});
  const text=s.el('sttDiagnostics').textContent;
  assert.match(text,/음성 인식 whisper · 실행 cuda/);assert.match(text,/8192MiB \(기동·장치 변경 시 표본/);
  assert.match(text,/잔여 US\$0.800000/);assert.match(text,/잔여 US\$4.700000/);
  assert.match(text,/실제 청구액과 다름 · 무료 혜택 미반영/);assert.match(text,/실제 연결 별도 확인/);
  s.context.window.listeners.pagehide();
});
test('transport pause keeps the validated card visible on the authenticated web screen',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  const card={text:'Retained synthetic answer',kind:'FACT',sourceIds:['source-7'],expiresAt:Date.now()+60000};
  const output=s.streams.at(-1);
  output.handlers.assist({data:JSON.stringify({...s.initial,version:2,card})});
  assert.equal(s.el('answer').textContent,card.text);
  output.handlers.assist({data:JSON.stringify({...s.initial,epoch:s.initial.epoch+1,version:3,state:'PAUSED',reason:'output_lost',card})});
  assert.equal(s.el('answer').textContent,card.text);
  assert.equal(s.el('microphoneStatus').textContent.includes('CAPTURING'),false);
  s.context.window.listeners.pagehide();
});
test('diagnostic sink failure cannot discard a valid card or disconnect output',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  s.context.console={info(){throw Error('sink unavailable');}};
  const current=s.streams.at(-1),card={text:'Synthetic answer',kind:'FACT',sourceIds:[],expiresAt:Date.now()+60000};
  current.handlers.assist({data:JSON.stringify({...s.initial,version:3,card,metrics:{generationAttempts:1,stages:{}}})});
  assert.equal(s.el('answer').textContent,card.text);assert.equal(current.closes,0);s.context.window.listeners.pagehide();
});
test('hidden and pinned cards never claim screen application; received and rendered are separate',async()=>{
  const s=screen();s.context.location.search='?display';s.context.document.visibilityState='visible';await new Promise(setImmediate);await s.el('start').onclick();
  const card={text:'First synthetic card',kind:'FACT',sourceIds:[],expiresAt:Date.now()+60000};
  const send=(version,text)=>s.streams.at(-1).handlers.assist({data:JSON.stringify({...s.initial,version,card:{...card,text}})});
  send(2,card.text);await new Promise(setImmediate);
  assert.equal(s.requests.filter(r=>r.body?.phase==='rendered').length,0);
  while(s.frames.length)s.frames.shift()();await new Promise(setImmediate);
  assert.equal(s.requests.filter(r=>r.body?.phase==='rendered').length,1);
  assert.equal(typeof s.el('pin').onclick,'function');s.el('pin').onclick();
  s.streams.at(-1).handlers.assist({data:JSON.stringify({...s.initial,version:3,card:null})});
  assert.equal(s.el('answer').textContent,card.text,'new work clearing its card must not clear the pinned reading card');
  send(4,'Second synthetic card');await new Promise(setImmediate);
  assert.equal(s.el('answer').textContent,card.text);while(s.frames.length)s.frames.shift()();await new Promise(setImmediate);
  assert.equal(s.requests.filter(r=>r.body?.phase==='rendered').length,1);
  s.el('pin').onclick();while(s.frames.length)s.frames.shift()();await new Promise(setImmediate);
  assert.equal(s.el('answer').textContent,'Second synthetic card');assert.equal(s.requests.filter(r=>r.body?.phase==='rendered').length,2);
  s.el('hide').onclick();send(5,'Hidden synthetic card');while(s.frames.length)s.frames.shift()();await new Promise(setImmediate);
  assert.equal(s.requests.filter(r=>r.body?.phase==='rendered').length,2);
  s.el('hide').onclick();while(s.frames.length)s.frames.shift()();await new Promise(setImmediate);
  assert.equal(s.requests.filter(r=>r.body?.phase==='rendered').length,3);
  s.context.window.listeners.pagehide();
});
test('feedback follows the pinned cue request and evidence can return to its answer',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  const card={text:'Synthetic answer',kind:'FACT',sourceIds:['source1'],sourceTitles:['Synthetic title'],requestId:'original-request',expiresAt:Date.now()+60000};
  const send=(version,value,requestId)=>s.streams.at(-1).handlers.assist({data:JSON.stringify({...s.initial,version,card:value,diagnostics:{cueId:'1:'+version,requestId}})});
  send(2,card,'original-request');s.el('pin').onclick();send(3,{...card,text:'Later answer',requestId:'later-request'},'later-request');
  s.el('feedbackLate').onclick();assert.match(s.el('liveDiagnostics').textContent,/피드백 늦음 \/ cue 1:2 \/ 요청 original-request/);
  s.el('evidence').onclick();assert.equal(s.el('answer').textContent,'Synthetic title');
  s.el('evidence').onclick();assert.equal(s.el('answer').textContent,'Synthetic answer');s.context.window.listeners.pagehide();
});
test('shared RAG detail pages retain full content without claiming provider attempts',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  const card={text:'First page',detailPages:['First page','Later conditions'],kind:'RAG',sourceIds:[],sourceTitles:[],expiresAt:Date.now()+60000};
  s.streams.at(-1).handlers.assist({data:JSON.stringify({...s.initial,version:2,reason:'RAG_ANSWER',card,metrics:{generationAttempts:0,stages:{}}})});
  assert.equal(s.el('answer').textContent,'First page');s.el('nextCard').onclick();assert.equal(s.el('answer').textContent,'Later conditions');
  assert.match(s.el('counts').textContent,/개별 시도 미관측/);assert.doesNotMatch(s.el('counts').textContent,/외부 유료 호출 0회/);s.context.window.listeners.pagehide();
});
test('multiline display cards paginate every line without losing answer or source text',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  const text=Array.from({length:12},(_,i)=>`조건 ${i+1}`).join('\n');
  const source=Array.from({length:10},(_,i)=>`출처 ${i+1}`).join('\n');
  const card={text,kind:'RAG',sourceIds:[],sourceTitles:[source],expiresAt:Date.now()+60000};
  s.streams.at(-1).handlers.assist({data:JSON.stringify({...s.initial,version:2,card})});
  const collect=()=>{const total=Number(s.el('sources').textContent.match(/\/ (\d+)/)[1]),pages=[];
    for(let i=0;i<total;i++){const value=s.el('answer').textContent;assert.ok(value.split(/\r\n|\r|\n/).length<=4,'each page must fit four explicit lines');pages.push(value);s.el('nextCard').onclick();}
    return pages.join('');};
  assert.equal(collect(),text);s.el('evidence').onclick();assert.equal(collect(),source);
  s.context.window.listeners.pagehide();
});

test('client errors and feedback remain bounded and contain no raw payload',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  assert.equal(typeof s.context.window.listeners.error,'function');
  for(let i=0;i<100;i++)s.context.window.listeners.error({message:'PRIVATE_TRANSCRIPT',filename:'secret-token'});
  assert.doesNotMatch(s.el('liveDiagnostics').textContent,/PRIVATE_TRANSCRIPT|secret-token/);
  assert.ok(s.el('liveDiagnostics').textContent.length<4096);
  assert.match(s.el('liveDiagnostics').textContent,/client_exception/);s.context.window.listeners.pagehide();
});
test('Conversate shell links its same-origin Display manifest and 128 pixel PNG',()=>{
  const html=fs.readFileSync('main/resources/static/conversate/index.html','utf8');
  assert.match(html,/<link\b[^>]*rel="manifest"[^>]*href="\/conversate\/manifest\.webmanifest"/);
  assert.match(html,/<link\b[^>]*rel="icon"[^>]*href="\/conversate\/favicon\.png"/);
  assert.match(html,/name="mrbd-web-app-capable" content="yes"/);
  const manifest=JSON.parse(fs.readFileSync('main/resources/static/conversate/manifest.webmanifest','utf8'));
  const origin='https://fixture.invalid',start=new URL(manifest.start_url,origin);
  assert.equal(start.origin,origin);assert.equal(start.pathname,'/conversate');assert.equal(start.search,'?display');
  assert.equal(manifest.scope,'/conversate');assert.equal(manifest.icons[0].sizes,'128x128');assert.equal(manifest.icons[0].type,'image/png');
  assert.equal(new URL(manifest.icons[0].src,origin+'/conversate/manifest.webmanifest').pathname,'/conversate/favicon.png');
  const png=fs.readFileSync('main/resources/static/conversate/favicon.png');
  assert.deepEqual([...png.subarray(0,8)],[137,80,78,71,13,10,26,10]);assert.equal(png.readUInt32BE(16),128);assert.equal(png.readUInt32BE(20),128);
  assert.deepEqual(png,fs.readFileSync('main/resources/static/assets/display/favicon.png'));
});
function screen(options={}){
  const elements=new Map();const el=id=>{if(!elements.has(id))elements.set(id,{textContent:'',value:'',hidden:false,classList:{toggle(){}},focus(){context.document.activeElement=this;},replaceChildren(){},querySelectorAll(){return []}});return elements.get(id);};
  const streams=[],requests=[],deferred=[],controls=[],statuses=[],timers=[];let pending,pendingReject,microphoneRequests=0,resolveMicrophone;
  const initial={assistId:'fixture',epoch:1,version:1,state:'RUNNING',card:null};
  const response=(body,ok=true)=>({ok,json:async()=>structuredClone(body)});
  const context={document:{getElementById:el,body:{classList:{add(){}}},addEventListener(type,listener){this.listeners??={};this.listeners[type]=listener;},querySelectorAll(){return []}},location:{search:'',hash:''},window:{isSecureContext:true,addEventListener(){}},navigator:{mediaDevices:{getUserMedia(){microphoneRequests++;return new Promise(resolve=>{resolveMicrophone=resolve;});}}},AudioWorkletNode:class{},URLSearchParams,crypto:require('node:crypto').webcrypto,Date,clearTimeout,setTimeout:(f,n)=>{const t=setTimeout(f,n);t.unref();return t;},EventSource:class{constructor(){this.handlers={};this.closes=0;streams.push(this);}addEventListener(k,f){this.handlers[k]=f;}close(){this.closes++;}},fetch:async(path,opts)=>{const body=opts.body?JSON.parse(opts.body):undefined;requests.push({path,body});if(path.endsWith('bootstrap'))return response({csrfHeader:'X-Test',csrfToken:'fixture',fixtureEnabled:true,asrAvailable:true});if(options.deferMaterials&&(path.endsWith('/materials')||path.endsWith('/fixture-materials')))return new Promise(resolve=>deferred.push({path,resolve:(value,ok=true)=>resolve(response(value,ok))}));if(options.deferControls&&path.endsWith('/control'))return new Promise(resolve=>controls.push({body,resolve:(value,ok=true)=>resolve(response(value,ok))}));const custom=options.reply?.(path,body);if(custom)return response(custom.body,custom.ok!==false);if(path.endsWith('/utterance'))return new Promise((resolve,reject)=>{pending=resolve;pendingReject=reject;});return response(initial);}};
  if(options.deferStatus){const originalFetch=context.fetch;context.fetch=(path,opts)=>/^\/api\/assist\/sessions\/[^/]+$/.test(path)&&opts.body===undefined?new Promise(resolve=>statuses.push({resolve:(body,ok=true)=>resolve(response(body,ok))})):originalFetch(path,opts);const originalTimer=context.setTimeout;context.setTimeout=(fn,ms)=>[750,1500,3000].includes(ms)?(fn.delay=ms,timers.push(fn),0):originalTimer(fn,ms);}
  const starts=[],originalFetch=context.fetch;
  context.fetch=(path,opts)=>{const body=opts.body?JSON.parse(opts.body):undefined;
    if(options.deferStarts&&(path==='/api/assist/sessions'||(path.endsWith('/control')&&body?.action==='resume'))){
      requests.push({path,body});return new Promise(resolve=>starts.push({resolve:(value,ok=true)=>resolve(response(value,ok))}));
    }return originalFetch(path,opts);
  };
  context.window.addEventListener=function(type,listener){this.listeners??={};this.listeners[type]=listener;};
  context.AbortController=AbortController;
  context.getComputedStyle=element=>({visibility:element.visibility||'visible'});
  const bootstrapReplies=[],statusTimeouts=[],lifecycleFetch=context.fetch,lifecycleTimer=context.setTimeout;
  context.fetch=(path,opts)=>{
    if(options.deferBootstrap&&path==='/api/assist/bootstrap'){requests.push({path});return new Promise(resolve=>bootstrapReplies.push({resolve:(body,ok=true)=>resolve(response(body,ok))}));}
    if(options.boundedStatus&&/^\/api\/assist\/sessions\/[^/]+$/.test(path)&&opts.body===undefined){
      requests.push({path});return new Promise((resolve,reject)=>{statuses.push({resolve:(body,ok=true)=>resolve(response(body,ok))});opts.signal?.addEventListener('abort',()=>reject(Object.assign(new Error('aborted'),{name:'AbortError'})),{once:true});});
    }return lifecycleFetch(path,opts);
  };
  context.setTimeout=(fn,ms)=>options.boundedStatus&&ms===4250?(statusTimeouts.push(fn),0):lifecycleTimer(fn,ms);
  const audioStarts=[],audioNodes=[];
  if(options.audioHarness){
    const audioFetch=context.fetch;context.fetch=(path,opts)=>path.endsWith('/audio/start')?(requests.push({path}),new Promise(resolve=>audioStarts.push({resolve:body=>resolve(response(body))}))):audioFetch(path,opts);
    const chain=()=>({connect(){return this;},gain:{value:1}});
    context.AudioContext=class{constructor(){this.audioWorklet={addModule:async()=>{}};this.destination={};}async resume(){}async close(){}createMediaStreamSource(){return chain();}createGain(){return chain();}};
    context.AudioWorkletNode=class{constructor(){audioNodes.push(this);this.port={postMessage(){}};}connect(){return this;}disconnect(){}};
  }
  const frames=[];context.requestAnimationFrame=fn=>{frames.push(fn);return frames.length;};context.performance={now:()=>100};
  context.btoa=value=>Buffer.from(value,'binary').toString('base64');context.Uint8Array=Uint8Array;context.ArrayBuffer=ArrayBuffer;
  vm.runInNewContext(code,context);return {context,el,streams,requests,deferred,controls,statuses,timers,starts,bootstrapReplies,statusTimeouts,audioStarts,audioNodes,frames,initial,microphoneRequests:()=>microphoneRequests,resolveMicrophone:media=>resolveMicrophone(media),reject:error=>pendingReject(error),resolve:body=>pending(response(body))};
}

test('diagnostics distinguish unobserved from zero and expose measured stages without raw text',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  const metrics={started:1,duplicates:0,suppressed:0,queueLength:0,lastProcessingMs:3,samples:1,generationAttempts:0,verificationHolds:0,stages:{indexed:2,retrieved:0,eligible:null,reranked:null,verified:null,retrievalMs:3,rerankMs:null,verificationMs:null}};
  s.streams.at(-1).handlers.assist({data:JSON.stringify({...s.initial,version:3,reason:'NO_MATCH',metrics})});
  assert.match(s.el('counts').textContent,/검색 결과 0/);assert.match(s.el('counts').textContent,/재정렬 미관측/);assert.match(s.el('counts').textContent,/생성 비성공률 미관측/);
  metrics.generationAttempts=2;metrics.verificationHolds=1;s.streams.at(-1).handlers.assist({data:JSON.stringify({...s.initial,version:4,reason:'GENERATION_TIMEOUT',metrics})});
  assert.match(s.el('counts').textContent,/생성 비성공률 50%/);s.context.window.listeners.pagehide();
});

test('readiness distinguishes historical startup proof from current model residency',async()=>{
  const s=screen({deferBootstrap:true});
  s.bootstrapReplies[0].resolve({csrfHeader:'X-Test',csrfToken:'fixture',asrAvailable:true,ollama:{observed:true,modelReady:true,modelPresent:true,serviceResponding:true,warmupStatus:'ok',warmupElapsedMs:100,warmupEvidenceAgeMs:1200000}});
  await new Promise(setImmediate);
  assert.match(s.el('readiness').textContent,/마지막 기동 검사/);
  assert.match(s.el('readiness').textContent,/당시 준비 확인/);
  assert.match(s.el('readiness').textContent,/현재 모델 상주·생성 가능 여부 미관측/);
  assert.match(s.el('readiness').textContent,/1200000ms/);s.context.window.listeners.pagehide();
});

test('diagnostics refresh cannot overwrite a restored page with a late bootstrap',async()=>{
  const s=screen({deferBootstrap:true});
  const fresh={csrfHeader:'X-Test',csrfToken:'fixture',asrAvailable:true,ollama:{observed:true,serviceResponding:true,modelPresent:true,modelReady:true,warmupStatus:'ok'}};
  s.bootstrapReplies[0].resolve(fresh);await new Promise(setImmediate);
  const pending=s.el('refreshDiagnostics').onclick();await new Promise(setImmediate);
  s.context.window.listeners.pagehide();s.context.window.listeners.pageshow({persisted:true});
  s.bootstrapReplies[2].resolve(fresh);await new Promise(setImmediate);
  const readiness=s.el('readiness').textContent;
  s.bootstrapReplies[1].resolve({...fresh,ollama:{observed:false}});await pending;
  assert.equal(s.el('readiness').textContent,readiness);s.context.window.listeners.pagehide();
});

test('output reconnect stops after three backoff attempts even with successful status GETs',async()=>{
  const s=screen({deferStatus:true});await new Promise(setImmediate);await s.el('start').onclick();
  for(let i=0;i<3;i++){s.streams.at(-1).onerror();assert.equal(s.timers.length,i+1);const pending=s.timers[i]();s.statuses[i].resolve(s.initial);await pending;}
  s.streams.at(-1).onerror();assert.equal(s.timers.length,3);assert.equal(s.streams.length,4);
  assert.deepEqual(s.timers.map(t=>t.delay),[750,1500,3000]);assert.equal(s.el('error').textContent,'output_reconnect_exhausted');
  s.context.window.listeners.pagehide();
});

test('only Display mode acknowledges validated receipt and duplicate versions do not loop',async()=>{
  for(const isDisplay of [false,true]){const s=screen();s.context.location.search=isDisplay?'?display':'';await new Promise(setImmediate);await s.el('start').onclick();
    const event={data:JSON.stringify(s.initial)};s.streams.at(-1).handlers.assist(event);await new Promise(setImmediate);s.streams.at(-1).handlers.assist(event);await new Promise(setImmediate);
    const acks=s.requests.filter(r=>r.path.endsWith('/ack'));assert.equal(acks.length,isDisplay?1:0);
    if(isDisplay)assert.deepEqual(acks[0].body,{epoch:1,version:1});s.context.window.listeners.pagehide();}
});

test('microphone separates configured, permission, starting and confirmed READY',async()=>{
  const s=screen({audioHarness:true});await new Promise(setImmediate);
  assert.match(s.el('microphoneStatus').textContent,/설정.*확인/);
  const pending=s.el('microphone').onclick();await new Promise(setImmediate);
  assert.match(s.el('microphoneStatus').textContent,/마이크 권한/);
  const track={stop(){}};s.resolveMicrophone({getTracks:()=>[track],getAudioTracks:()=>[track]});await new Promise(setImmediate);
  assert.equal(s.audioStarts.length,1);assert.equal(s.audioNodes.length,0);assert.match(s.el('microphoneStatus').textContent,/준비.*대기/);
  s.audioStarts[0].resolve({state:'READY',sampleRate:16000,maxChunkBytes:7680});await pending;
  assert.equal(s.audioNodes.length,1);assert.doesNotMatch(s.el('microphoneStatus').textContent,/음성 입력 중|듣는 중/);
  s.audioNodes[0].port.onmessage({data:{pcm:new ArrayBuffer(640)}});await new Promise(setImmediate);
  assert.match(s.el('microphoneStatus').textContent,/오디오 유입|음성 입력 중/);s.context.window.listeners.pagehide();
});
test('audio startup without READY cannot announce capture and releases local microphone',async()=>{
  const s=screen({audioHarness:true});await new Promise(setImmediate);const pending=s.el('microphone').onclick();await new Promise(setImmediate);
  let stopped=0;const track={stop(){stopped++;}};s.resolveMicrophone({getTracks:()=>[track],getAudioTracks:()=>[track]});await new Promise(setImmediate);
  s.audioStarts[0].resolve({state:'STARTING'});await pending;
  assert.equal(s.audioNodes.length,0);assert.equal(stopped,1);assert.doesNotMatch(s.el('microphoneStatus').textContent,/음성 입력 중/);
  assert.equal(s.requests.filter(r=>r.path.endsWith('/audio/start')).length,1);
});

test('malformed and oversized SSE fail closed without retaining the old card',async()=>{
  for(const data of ['{','x'.repeat(32769),JSON.stringify({state:'RUNNING',epoch:'wrong'})]){
    const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
    const stream=s.streams.at(-1);
    assert.doesNotThrow(()=>stream.handlers.assist({data}));
    assert.equal(stream.closes,1);assert.match(s.el('error').textContent,/output_payload_invalid/);
    assert.equal(s.context.document.getElementById('sources').textContent,'');
    s.context.window.listeners.pagehide();
  }
});

test('capture exposes device and format and explicit microphone stop releases the track',async()=>{
  const s=screen({audioHarness:true});await new Promise(setImmediate);const pending=s.el('microphone').onclick();await new Promise(setImmediate);
  let stopped=0;const track={label:'Synthetic microphone',getSettings:()=>({sampleRate:48000,channelCount:1}),stop(){stopped++;}};
  s.resolveMicrophone({getTracks:()=>[track],getAudioTracks:()=>[track]});await new Promise(setImmediate);
  s.audioStarts[0].resolve({state:'READY',sampleRate:16000,maxChunkBytes:7680});await pending;
  assert.match(s.el('inputDevice').textContent,/Synthetic microphone/);assert.match(s.el('inputDevice').textContent,/16000/);
  assert.equal(s.el('microphone').disabled,false);assert.match(s.el('microphone').textContent,/중지/);
  await s.el('microphone').onclick();assert.equal(stopped,1);
  assert.equal(s.requests.filter(r=>r.path.endsWith('/control')&&r.body.action==='pause').length,1);
  s.context.window.listeners.pagehide();
});

test('denied microphone permission starts no ASR child or model work',async()=>{
  const s=screen({audioHarness:true});await new Promise(setImmediate);
  s.context.navigator.mediaDevices.getUserMedia=async()=>{throw Object.assign(new Error('private detail'),{name:'NotAllowedError'});};
  await s.el('microphone').onclick();assert.equal(s.el('error').textContent,'microphone_permission_denied');
  assert.equal(s.audioStarts.length,0);assert.equal(s.audioNodes.length,0);assert.equal(s.requests.filter(r=>r.path.endsWith('/utterance')).length,0);
  s.context.window.listeners.pagehide();
});

test('ended audio track stops capture and selects text once without resending speech',async()=>{
  const s=screen({audioHarness:true});await new Promise(setImmediate);const pending=s.el('microphone').onclick();await new Promise(setImmediate);
  let stopped=0;const track={stop(){stopped++;}};s.resolveMicrophone({getTracks:()=>[track],getAudioTracks:()=>[track]});await new Promise(setImmediate);
  s.audioStarts[0].resolve({state:'READY'});await pending;await track.onended();assert.equal(stopped,1);
  assert.equal(s.requests.filter(r=>r.path.endsWith('/control')&&r.body.action==='text_fallback').length,1);
  await track.onended();assert.equal(s.requests.filter(r=>r.body?.action==='text_fallback').length,1);
  assert.equal(s.requests.filter(r=>r.path.endsWith('/utterance')).length,0);s.context.window.listeners.pagehide();
});

test('failed text switch is attempted only once per epoch and never resumes user pause',async()=>{
  const s=screen({reply:(path,body)=>body?.action==='text_fallback'?{body:{reason:'unavailable'},ok:false}:null});
  await new Promise(setImmediate);await s.el('start').onclick();
  const output=s.streams.at(-1),paused={...s.initial,epoch:2,version:2,state:'PAUSED',reason:'ASR_INPUT_LOST'};
  output.handlers.assist({data:JSON.stringify(paused)});await new Promise(setImmediate);
  output.handlers.assist({data:JSON.stringify({...paused,version:3})});await new Promise(setImmediate);
  assert.equal(s.requests.filter(r=>r.body?.action==='text_fallback').length,1);
  output.handlers.assist({data:JSON.stringify({...paused,epoch:3,version:4,reason:'user_pause'})});await new Promise(setImmediate);
  assert.equal(s.requests.filter(r=>r.body?.action==='text_fallback').length,1);
  assert.equal(s.requests.filter(r=>r.path.endsWith('/utterance')).length,0);s.context.window.listeners.pagehide();
});

for(const restoreVisibility of [false,true])test('backgrounding during session start cancels microphone intent even if visible again: '+restoreVisibility,async()=>{
  const s=screen({deferStarts:true});await new Promise(setImmediate);
  s.context.document.visibilityState='visible';const pending=s.el('microphone').onclick();
  s.context.document.visibilityState='hidden';s.context.document.listeners.visibilitychange();
  if(restoreVisibility){s.context.document.visibilityState='visible';s.context.document.listeners.visibilitychange();}
  s.starts[0].resolve(s.initial);await new Promise(setImmediate);
  try{assert.equal(s.microphoneRequests(),0);assert.equal(s.audioStarts.length,0);}
  finally{s.context.window.listeners.pagehide();if(s.microphoneRequests())s.resolveMicrophone({getTracks:()=>[]});await pending;}
});

test('hidden page cannot request microphone permission',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  s.context.document.visibilityState='hidden';const pending=s.el('microphone').onclick();await new Promise(setImmediate);
  try{assert.equal(s.microphoneRequests(),0);}
  finally{s.context.window.listeners.pagehide();if(s.microphoneRequests())s.resolveMicrophone({getTracks:()=>[]});await pending;}
});

test('permission arriving after backgrounding releases every track without starting ASR',async()=>{
  const s=screen({audioHarness:true});await new Promise(setImmediate);await s.el('start').onclick();
  const pending=s.el('microphone').onclick();s.context.document.visibilityState='hidden';s.context.document.listeners.visibilitychange();
  let stopped=0;s.resolveMicrophone({getTracks:()=>[{stop(){stopped++;}},{stop(){stopped++;}}]});await pending;
  assert.equal(stopped,2);assert.equal(s.audioStarts.length,0);assert.equal(s.audioNodes.length,0);s.context.window.listeners.pagehide();
});

test('ASR disabled keeps text available and never requests microphone permission',async()=>{
  const s=screen({deferBootstrap:true});s.bootstrapReplies[0].resolve({asrAvailable:false,fixtureEnabled:false});await new Promise(setImmediate);
  assert.equal(s.el('microphone').disabled,true);await s.el('start').onclick();await s.el('microphone').onclick();
  assert.equal(s.el('sendUtterance').disabled,false);assert.equal(s.microphoneRequests(),0);assert.equal(s.audioStarts.length,0);s.context.window.listeners.pagehide();
});

test('backgrounding phone capture stops the track and requests upstream pause',async()=>{
  const s=screen({audioHarness:true});await new Promise(setImmediate);const pending=s.el('microphone').onclick();await new Promise(setImmediate);
  let stopped=0;const track={stop(){stopped++;}};s.resolveMicrophone({getTracks:()=>[track],getAudioTracks:()=>[track]});await new Promise(setImmediate);
  s.audioStarts[0].resolve({state:'READY',sampleRate:16000,maxChunkBytes:7680});await pending;
  s.context.document.visibilityState='hidden';s.context.document.listeners.visibilitychange();await new Promise(setImmediate);
  assert.equal(stopped,1);assert.equal(s.requests.filter(r=>r.path.endsWith('/control')&&r.body.action==='pause').length,1);
  s.context.window.listeners.pagehide();
});

test('directional focus skips hidden geometry, invisible, inert and disabled controls',async()=>{
  const s=screen();await new Promise(setImmediate);
  const control=id=>Object.assign(s.el(id),{getClientRects(){return this.noGeometry?[]:[{}];},matches(){return !!this.disabled;},closest(){return this.inert?{}:null;},getAttribute(){return null;}});
  const cssHidden=control('cssHidden'),closedDetails=control('closedDetails'),invisible=control('invisible'),inert=control('inert'),disabled=control('disabled'),first=control('hide'),last=control('evidence');
  cssHidden.noGeometry=closedDetails.noGeometry=true;invisible.visibility='hidden';inert.inert=true;disabled.disabled=true;
  s.context.document.querySelectorAll=()=>[cssHidden,closedDetails,invisible,inert,disabled,first,last];last.focus();
  let prevented=false;s.context.document.listeners.keydown({key:'ArrowRight',target:last,preventDefault(){prevented=true;}});
  assert.equal(s.context.document.activeElement,first);assert.equal(prevented,true);
});

test('directional keys with no visible control keep native behavior',async()=>{
  const s=screen();await new Promise(setImmediate);s.context.document.querySelectorAll=()=>[];
  let prevented=false;s.context.document.listeners.keydown({key:'ArrowRight',target:{tagName:'BODY'},preventDefault(){prevented=true;}});
  assert.equal(prevented,false);
});

test('reconnect status GET expires within the displayed five second grace without retry',async()=>{
  const s=screen({deferStatus:true,boundedStatus:true});await new Promise(setImmediate);await s.el('start').onclick();
  s.streams.at(-1).onerror();const refresh=s.timers[0]();
  assert.equal(s.statusTimeouts.length,1);s.statusTimeouts[0]();await refresh;
  assert.equal(s.statuses.length,1);assert.equal(s.el('status').textContent,'연결 상태 확인 시간이 초과되었습니다.');
  assert.equal(s.requests.filter(r=>r.path==='/api/assist/sessions').length,1);
});

test('persisted pageshow initializes once without restoring input or creating a session',async()=>{
  const s=screen();await new Promise(setImmediate);s.el('transcript').value='discard-on-hide';s.context.window.listeners.pagehide();
  s.context.window.listeners.pageshow?.({persisted:true});s.context.window.listeners.pageshow?.({persisted:true});await new Promise(setImmediate);
  assert.equal(s.requests.filter(r=>r.path==='/api/assist/bootstrap').length,2);
  assert.equal(s.requests.filter(r=>r.path==='/api/assist/sessions').length,0);
  assert.equal(s.el('transcript').value,'');assert.equal(s.el('status').textContent,'준비됨 · 저장 안 함');
});

test('bootstrap reply from before pagehide cannot repaint the current lifecycle',async()=>{
  const s=screen({deferBootstrap:true});s.context.window.listeners.pagehide();s.el('status').textContent='current-lifecycle';
  s.bootstrapReplies[0].resolve({csrfHeader:'X-Test',csrfToken:'fixture',fixtureEnabled:false,asrAvailable:false});await new Promise(setImmediate);
  assert.equal(s.el('status').textContent,'current-lifecycle');assert.equal(s.streams.length,0);
});

for(const second of ['start','microphone'])test('pending start blocks another '+second+' session creation',async()=>{
  const s=screen({deferStarts:true});await new Promise(setImmediate);
  const first=s.el('start').onclick(),duplicate=s.el(second).onclick();
  assert.equal(s.starts.length,1);assert.equal(s.el('start').disabled,true);assert.equal(s.el('microphone').disabled,true);
  s.starts[0].resolve(s.initial);await Promise.all([first,duplicate]);
  assert.equal(s.streams.length,1);assert.equal(s.microphoneRequests(),0);
});

for(const ok of [true,false])test('pagehide invalidates pending microphone session '+(ok?'response':'failure'),async()=>{
  const s=screen({deferStarts:true});await new Promise(setImmediate);const first=s.el('microphone').onclick();
  s.context.window.listeners.pagehide();s.el('error').textContent='current-status';
  s.starts[0].resolve(ok?s.initial:{reason:'obsolete_failure'},ok);await first;
  assert.equal(s.el('error').textContent,'current-status');assert.equal(s.microphoneRequests(),0);assert.equal(s.streams.length,0);
});

test('stop supersedes pending resume and ignores its late failure',async()=>{
  const options={reply:(path,body)=>path.endsWith('/control')?{body:{assistId:'fixture',epoch:2,version:2,state:body.action==='stop'?'STOPPED':'PAUSED',card:null}}:undefined};
  const s=screen(options);await new Promise(setImmediate);await s.el('start').onclick();await s.el('pause').onclick();
  options.deferStarts=true;const resume=s.el('start').onclick();await s.el('stop').onclick();
  s.el('error').textContent='current-status';s.starts[0].resolve({reason:'obsolete_failure'},false);await resume;
  assert.equal(s.el('error').textContent,'current-status');assert.equal(s.el('status').textContent,'중지됨');
});

test('start failure releases pending without automatic retry',async()=>{
  const s=screen({deferStarts:true});await new Promise(setImmediate);const first=s.el('start').onclick();
  s.starts[0].resolve({reason:'synthetic_failure'},false);await first;await new Promise(setImmediate);
  assert.equal(s.starts.length,1);assert.notEqual(s.el('start').disabled,true);
  const intentional=s.el('start').onclick();assert.equal(s.starts.length,2);s.starts[1].resolve(s.initial);await intentional;
});
test('late POST snapshot cannot overwrite a newer SSE card',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  s.el('transcript').value='보증 기간은 얼마인가요?';const post=s.el('sendUtterance').onclick();
  const fresh={...s.initial,epoch:2,version:3,card:{kind:'FACT',text:'보증 기간은 2년입니다.',sourceIds:['fixture'],expiresAt:Date.now()+10000}};
  s.streams.at(-1).handlers.assist({data:JSON.stringify(fresh)});
  s.resolve({...s.initial,version:2});await post;
  assert.equal(s.el('answer').textContent,'보증 기간은 2년입니다.');
  s.streams.at(-1).handlers.assist({data:JSON.stringify({...s.initial,epoch:1,version:99})});
  assert.equal(s.el('answer').textContent,'보증 기간은 2년입니다.');
});
test('obsolete output error and message cannot close or repaint the current output',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();const old=s.streams[0];await s.el('start').onclick();const current=s.streams[1];
  old.onerror();assert.equal(current.closes,0);
  old.handlers.assist({data:JSON.stringify({...s.initial,version:100,card:{kind:'FACT',text:'obsolete',sourceIds:[],expiresAt:Date.now()+10000}})});
  assert.notEqual(s.el('answer').textContent,'obsolete');
});
test('remote stop clears capture indication and prevents output reconnect',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();const output=s.streams.at(-1);
  s.el('transcript').value='중지하면 폐기할 전사문';
  output.handlers.assist({data:JSON.stringify({...s.initial,version:2,audio:{state:'CAPTURING',chunks:1,finals:1,partials:1,lastAsrMs:20}})});
  assert.match(s.el('microphoneStatus').textContent,/오디오 유입/);
  output.handlers.assist({data:JSON.stringify({...s.initial,epoch:2,version:3,state:'STOPPED',audio:{state:'STOPPED'}})});
  assert.doesNotMatch(s.el('microphoneStatus').textContent,/오디오 유입|음성 입력 중/);assert.ok(output.closes>0);assert.equal(s.el('transcript').value,'');
  output.onerror();assert.equal(s.el('status').textContent,'중지됨');
  output.handlers.assist({data:JSON.stringify({...s.initial,epoch:3,version:100})});assert.equal(s.el('status').textContent,'중지됨');
});
for(const button of ['fixtureMaterials','selectMaterials'])test(button+' blocks new input while applying and keeps typed text',async()=>{
  const s=screen({deferMaterials:true});await new Promise(setImmediate);await s.el('start').onclick();
  s.el('transcript').value='보증 기간은 얼마인가요?';const selection=s.el(button).onclick();
  assert.equal(s.el('sendUtterance').disabled,true);assert.equal(s.el('microphone').disabled,true);
  assert.equal(s.el('pause').disabled,false);assert.equal(s.el('stop').disabled,false);
  await s.el('sendUtterance').onclick();await s.el('microphone').onclick();await s.el(button==='fixtureMaterials'?'selectMaterials':'fixtureMaterials').onclick();
  assert.equal(s.requests.filter(r=>r.path.endsWith('/utterance')).length,0);assert.equal(s.microphoneRequests(),0);assert.equal(s.deferred.length,1);
  assert.equal(s.el('transcript').value,'보증 기간은 얼마인가요?');
  s.deferred[0].resolve({...s.initial,epoch:2,version:2});await selection;
  assert.equal(s.el('sendUtterance').disabled,false);assert.equal(s.el('microphone').disabled,false);
  const post=s.el('sendUtterance').onclick();assert.equal(s.requests.at(-1).body.epoch,2);s.resolve({...s.initial,epoch:2,version:3});await post;
});
test('selection failure releases input without losing text or retrying the selection',async()=>{
  const s=screen({deferMaterials:true});await new Promise(setImmediate);await s.el('start').onclick();
  s.el('transcript').value='입력을 유지합니다.';const pending=s.el('selectMaterials').onclick();s.deferred[0].resolve({reason:'material_index_unavailable'},false);await pending;
  assert.equal(s.el('sendUtterance').disabled,false);assert.equal(s.el('transcript').value,'입력을 유지합니다.');assert.equal(s.el('error').textContent,'material_index_unavailable');assert.equal(s.deferred.length,1);
});
for(const action of ['pause','stop'])test(action+' survives a material epoch race and ignores its late response',async()=>{
  let controls=0;const s=screen({deferMaterials:true,reply:(path,body)=>{
    if(path.endsWith('/control')){controls++;if(controls===1)return {ok:false,body:{reason:'stale_epoch'}};return {body:{...s.initial,epoch:3,version:4,state:action==='pause'?'PAUSED':'STOPPED'}};}
    if(path.endsWith('/fixture')&&body===undefined)return {body:{...s.initial,epoch:2,version:3}};
  }});await new Promise(setImmediate);await s.el('start').onclick();const pending=s.el('selectMaterials').onclick();
  await s.el(action).onclick();assert.equal(controls,2);assert.deepEqual(s.requests.filter(r=>r.path.endsWith('/control')).map(r=>r.body.epoch),[1,2]);
  const outputs=s.streams.length;s.deferred[0].resolve({...s.initial,epoch:2,version:99});await pending;
  assert.equal(s.el('status').textContent,action==='pause'?'일시정지':'중지됨');assert.equal(s.streams.length,outputs);assert.equal(s.el('sendUtterance').disabled,true);
});
test('control retries only one stale epoch, never permission or repeated conflicts',async()=>{
  for(const reason of ['stale_epoch','assist_forbidden']){
    let count=0;const s=screen({reply:(path)=>path.endsWith('/control')?(count++,{ok:false,body:{reason}}):undefined});await new Promise(setImmediate);await s.el('start').onclick();await s.el('stop').onclick();assert.equal(count,reason==='stale_epoch'?2:1);assert.equal(s.el('error').textContent,reason);
  }
});
test('old material completion cannot replace a newly started assist',async()=>{
  let starts=0;const s=screen({deferMaterials:true,reply:(path,body)=>{
    if(path==='/api/assist/sessions')return {body:{...s.initial,assistId:++starts===1?'fixture':'second'}};
    if(path.endsWith('/control'))return {body:{...s.initial,epoch:2,version:2,state:'STOPPED'}};
  }});await new Promise(setImmediate);await s.el('start').onclick();const pending=s.el('fixtureMaterials').onclick();await s.el('stop').onclick();await s.el('start').onclick();
  s.deferred[0].resolve({...s.initial,epoch:2,version:10});await pending;
  assert.match(s.el('displayLink').href,/assist=second$/);assert.equal(s.el('status').textContent,'대화 보조 중');
});
test('material selection closes a microphone still awaiting permission',async()=>{
  const s=screen({deferMaterials:true});await new Promise(setImmediate);await s.el('start').onclick();const mic=s.el('microphone').onclick();assert.equal(s.microphoneRequests(),1);
  const selection=s.el('fixtureMaterials').onclick();let stops=0;s.resolveMicrophone({getTracks:()=>[{stop(){stops++;}}]});await mic;
  assert.equal(stops,1);assert.equal(s.requests.filter(r=>r.path.endsWith('/audio/start')).length,0);assert.equal(s.el('microphone').disabled,true);
  s.deferred[0].resolve({...s.initial,epoch:2,version:2});await selection;
});
test('stop can supersede pending pause and late pause cannot revive controls',async()=>{
  const s=screen({deferControls:true});await new Promise(setImmediate);await s.el('start').onclick();const pause=s.el('pause').onclick();
  s.el('transcript').value='중지하면 폐기할 입력문';
  assert.equal(s.el('sendUtterance').disabled,true);assert.equal(s.el('stop').disabled,false);const stop=s.el('stop').onclick();
  assert.equal(s.el('transcript').value,'');assert.equal(s.controls.length,2);s.controls[1].resolve({...s.initial,epoch:3,version:3,state:'STOPPED'});await stop;
  s.controls[0].resolve({...s.initial,epoch:2,version:2,state:'PAUSED'});await pause;
  assert.equal(s.el('status').textContent,'중지됨');assert.equal(s.el('sendUtterance').disabled,true);assert.equal(s.el('start').disabled,false);
});
for(const ok of [true,false])test('late reconnect '+(ok?'snapshot':'failure')+' cannot alter a replacement assist',async()=>{
  let starts=0;const s=screen({deferStatus:true,reply:(path)=>{
    if(path==='/api/assist/sessions')return {body:{...s.initial,assistId:++starts===1?'fixture':'second'}};
    if(path.endsWith('/control'))return {body:{...s.initial,epoch:2,version:2,state:'STOPPED'}};
  }});await new Promise(setImmediate);await s.el('start').onclick();s.streams[0].onerror();const refresh=s.timers.shift()();assert.equal(s.statuses.length,1);
  await s.el('stop').onclick();await s.el('start').onclick();const output=s.streams.at(-1);
  s.statuses[0].resolve(ok?{...s.initial,version:99}:{reason:'assist_missing'},ok);await refresh;
  assert.match(s.el('displayLink').href,/assist=second$/);assert.equal(s.el('status').textContent,'대화 보조 중');assert.equal(output.closes,0);
});


test('one pending utterance sends once while pause and stop remain available',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  s.el('transcript').value='SYNTHETIC_QUESTION?';
  const first=s.el('sendUtterance').onclick();s.el('sendUtterance').onclick();
  const sent=s.requests.filter(r=>r.path.endsWith('/utterance'));
  assert.equal(sent.length,1);assert.equal(s.el('sendUtterance').disabled,true);
  assert.equal(s.el('pause').disabled,false);assert.equal(s.el('stop').disabled,false);
  s.el('transcript').value='SYNTHETIC_EDITED_QUESTION?';
  s.resolve({...s.initial,version:2});await first;
  assert.equal(s.el('transcript').value,'SYNTHETIC_EDITED_QUESTION?');
  assert.equal(s.el('sendUtterance').disabled,false);
});

test('utterance transport rejection keeps text and does not automatically retransmit',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  s.el('transcript').value='SYNTHETIC_QUESTION?';const pending=s.el('sendUtterance').onclick();
  s.reject(new Error('synthetic_transport_loss'));await pending;await new Promise(setImmediate);
  assert.equal(s.requests.filter(r=>r.path.endsWith('/utterance')).length,1);
  assert.equal(s.el('transcript').value,'SYNTHETIC_QUESTION?');
  assert.equal(s.el('sendUtterance').disabled,false);
  assert.match(s.el('liveDiagnostics').textContent,/error \/ HTTP/);
});

test('current request diagnostics clear an earlier HTTP 200 before pending and timeout',async()=>{
  const s=screen();await new Promise(setImmediate);
  const previousFetch=s.context.fetch;s.context.fetch=async(...args)=>({status:200,...await previousFetch(...args)});
  await s.el('start').onclick();assert.match(s.el('liveDiagnostics').textContent,/response \/ HTTP 200/);
  const deadlines=[],previousTimer=s.context.setTimeout;
  s.context.setTimeout=(fn,ms)=>ms===15000?(deadlines.push(fn),0):previousTimer(fn,ms);
  s.context.fetch=(_path,opts)=>new Promise((_resolve,reject)=>opts.signal.addEventListener('abort',()=>reject(new Error('synthetic-timeout')),{once:true}));
  s.el('transcript').value='SYNTHETIC_QUESTION?';const pending=s.el('sendUtterance').onclick();
  const pendingDiagnostic=s.el('liveDiagnostics').textContent;deadlines.shift()();await pending;
  const timeoutDiagnostic=s.el('liveDiagnostics').textContent;s.context.window.listeners.pagehide();
  assert.match(pendingDiagnostic,/pending \/ HTTP 미관측 · 브라우저 왕복 미관측ms/);
  assert.match(timeoutDiagnostic,/timeout \/ HTTP 미관측/);
  assert.match(timeoutDiagnostic,/request_timeout/);
});

test('obsolete request completion cannot replace a newer successful HTTP diagnostic',async()=>{
  const s=screen();await new Promise(setImmediate);
  const previousFetch=s.context.fetch;s.context.fetch=async(...args)=>({status:200,...await previousFetch(...args)});
  await s.el('start').onclick();s.el('transcript').value='SYNTHETIC_QUESTION?';const pending=s.el('sendUtterance').onclick();
  await s.el('stop').onclick();await s.el('start').onclick();
  const current=s.el('liveDiagnostics').textContent;
  s.reject(new Error('obsolete-transport-failure'));await pending;
  assert.equal(s.el('liveDiagnostics').textContent,current);assert.match(current,/response \/ HTTP 200/);
  s.context.window.listeners.pagehide();
});

test('current HTTP rejection retains its observed status and redacted reason',async()=>{
  const s=screen();await new Promise(setImmediate);await s.el('start').onclick();
  s.context.fetch=async()=>({ok:false,status:403,json:async()=>({reason:'assist_owner_denied',privateDetail:'SYNTHETIC_PRIVATE'})});
  s.el('transcript').value='SYNTHETIC_QUESTION?';await s.el('sendUtterance').onclick();
  assert.match(s.el('liveDiagnostics').textContent,/error \/ HTTP 403/);
  assert.equal(s.el('error').textContent,'assist_owner_denied');
  assert.doesNotMatch(s.el('liveDiagnostics').textContent,/SYNTHETIC_PRIVATE/);s.context.window.listeners.pagehide();
});

test('old utterance rejection cannot overwrite replacement assist status',async()=>{
  let starts=0;const s=screen({reply:(path,body)=>{
    if(path==='/api/assist/sessions')return {body:{...s.initial,assistId:++starts===1?'fixture':'second'}};
    if(path.endsWith('/control'))return {body:{...s.initial,epoch:2,version:2,state:'STOPPED'}};
  }});await new Promise(setImmediate);await s.el('start').onclick();
  s.el('transcript').value='SYNTHETIC_QUESTION?';const pending=s.el('sendUtterance').onclick();
  await s.el('stop').onclick();await s.el('start').onclick();
  s.el('error').textContent='current-status';s.reject(new Error('obsolete-error'));await pending;
  assert.equal(s.el('error').textContent,'current-status');assert.match(s.el('displayLink').href,/assist=second$/);
});

test('IME and editable arrow keys retain focus and browser default editing',async()=>{
  const s=screen();await new Promise(setImmediate);
  s.context.document.querySelectorAll=()=>[s.el('start'),s.el('sendUtterance')];
  const target=s.el('transcript');target.focus();
  const cases=[
    {isComposing:true,target:{tagName:'DIV'}},
    {keyCode:229,target:{tagName:'DIV'}},
    ...['INPUT','TEXTAREA','SELECT'].map(tagName=>({target:{tagName}})),
    {target:{tagName:'DIV',isContentEditable:true}},
    {target:{tagName:'SPAN',closest:()=>({contentEditable:'true'})}}
  ];
  for(const detail of cases){
    let prevented=false;s.context.document.listeners.keydown({key:'ArrowLeft',preventDefault(){prevented=true;},...detail});
    assert.equal(prevented,false);assert.equal(s.context.document.activeElement,target);
  }
});
