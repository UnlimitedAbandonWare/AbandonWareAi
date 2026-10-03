const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {mount}=require('../../../main/resources/static/assets/display/display-focus-controls.js');
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
const {createCapture}=require('../../../main/resources/static/assets/display/display-voice.js');
const flush=()=>new Promise(setImmediate);
function controlsFixture({fetchImpl,jev}={}){
 const nodes=new Map(),calls=[],gets=[];const node=()=>({value:'',checked:false,disabled:false,textContent:'',children:[],type:'number',removeAttribute(){},replaceChildren(){this.children=[];},append(...v){this.children.push(...v);},get options(){return this.children;}});
 const $=id=>{if(!nodes.has(id))nodes.set(id,node());return nodes.get(id);};
 for(const id of ['nf-enabled','nf-recall','nf-remember','nf-sequential','nf-fade-on','nf-snapshot-enabled','nf-answer-fallback','nf-recent-enabled'])$(id).type='checkbox';
 let settings={enabled:true,recallEnabled:false,rememberFactsEnabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,
  presentation:{sequentialTextEnabled:true,charIntervalMs:80,maxVisibleLines:6,autoFadeEnabled:true,tailHoldMs:5000,fadeMs:400},
  answerSelection:{mode:'FIXED',modelId:'fixture-primary',routing:{executionTarget:'API_ONLY',fallbackAllowed:true,allowedFallbackIds:['fixture-backup']}},
  recentContext:{enabled:true,maxAgeSeconds:90,maxUtterances:7,tokenBudget:1000}};
 const host={NovaFocus:{receiptSender:()=>()=>{},createProjection:()=>({update(){},visibility(){},dispose(){},isActive:()=>false})},
  async fetch(url){gets.push(url);if(fetchImpl)return fetchImpl(url);return{ok:true,json:async()=>[{id:'fixture-primary',provider:'fixture',selectable:true},{id:'fixture-backup',provider:'fixture',selectable:true},{id:'a'.repeat(129)+'+variant',provider:'fixture',selectable:true},{id:'disabled-fixture',provider:'fixture',selectable:false}]};}};
 const client={async focusRequest(route,body){calls.push({route,body});if(route==='settings')settings=body.settings;return{settingsVersion:3,cacheScope:'a'.repeat(64),settings,jev};}};
 const document={getElementById:$,createElement:node,addEventListener(){},removeEventListener(){}};
 const controls=mount({host,document,client});controls.update({assistId:'synthetic',epoch:1,focusProducer:true,ready:true,connection:'READY'});
 return{$,calls,gets,controls};
}
test('phone restores model routing and recent limits then saves the versioned choices',async()=>{
 const f=controlsFixture();try{await flush();await flush();
  assert.equal(f.$('nf-answer-model').value,'fixture-primary');assert.equal(f.$('nf-answer-target').value,'API_ONLY');
  assert.equal(f.$('nf-recent-age').value,90);assert.deepEqual(f.gets,['/api/chat/models']);
  assert.ok(f.$('nf-answer-model').children.some(c=>c.value==='a'.repeat(129)+'+variant'));
  assert.ok(!f.$('nf-answer-model').children.some(c=>c.value==='disabled-fixture'));
  f.$('nf-answer-target').value='LOCAL_ONLY';f.$('nf-answer-model').value='fixture-backup';f.$('nf-answer-fallback').checked=false;
  f.$('nf-recent-enabled').checked=false;f.$('nf-recent-age').value='60';f.$('nf-recent-count').value='4';f.$('nf-recent-budget').value='512';
  await f.$('nova-settings-form').onsubmit({preventDefault(){}});
  const save=f.calls.find(x=>x.route==='settings').body;
  assert.equal(save.settingsVersion,3);assert.deepEqual(save.settings.answerSelection,{mode:'FIXED',modelId:'fixture-backup',routing:{executionTarget:'LOCAL_ONLY',fallbackAllowed:false,allowedFallbackIds:[]}});
  assert.deepEqual(save.settings.recentContext,{enabled:false,maxAgeSeconds:60,maxUtterances:4,tokenBudget:512});
 }finally{f.controls.dispose();}
});
test('audio start sends a snapshot of requested STT policy despite later caller mutation',async()=>{
 const calls=[],timers=new Map();let n=0,release;const wait=new Promise(r=>release=r);
 const view={assistId:'12345678-1234-4234-8234-123456789abc',epoch:1,version:1,ready:true,state:'RUNNING',audioAvailable:true,audioState:'READY'};
 const client=createClient({uuid:()=>view.assistId,setTimer(fn){timers.set(++n,fn);return n;},clearTimer:id=>timers.delete(id),
  async fetchImpl(url,opts){const body=JSON.parse(opts.body);calls.push({url,body});if(url.endsWith('/bootstrap'))await wait;return{ok:true,headers:{get:()=>null},json:async()=>view};}});
 const policy={engine:'soniox',fallbackAllowed:true,allowedFallbacks:['deepgram']};
 try{const start=client.beginVoice({sttPolicy:policy});policy.engine='local';policy.allowedFallbacks.length=0;release();await start;
  assert.deepEqual(calls.find(c=>c.url.endsWith('/audio/start')).body.sttPolicy,{engine:'soniox',fallbackAllowed:true,allowedFallbacks:['deepgram']});
 }finally{release();await client.endVoice();client.dispose();}
});
test('capture keeps its selected STT engine through reconnect and adopts edits only on next start',async()=>{
 const calls=[],timers=new Map();let n=0,policy={engine:'local',fallbackAllowed:false,allowedFallbacks:[]};
 class Context{constructor(){this.audioWorklet={addModule:async()=>{}};this.destination={};}async resume(){}async close(){}createMediaStreamSource(){return{connect:()=>({connect:()=>({connect(){}})})};}createGain(){return{gain:{value:1}};}}
 class Node{constructor(){this.port={postMessage(){}};}disconnect(){}}
 const client={state:{role:'STANDALONE',audioAvailable:true,audioFinished:true},beginVoice:async opts=>calls.push(opts),endVoice:async()=>{},reconnect:async()=>{},voiceChunk:async()=>{}};
 const env={isSecureContext:true,AudioContext:Context,AudioWorkletNode:Node,navigator:{mediaDevices:{getUserMedia:async()=>({getTracks:()=>[],getAudioTracks:()=>[]})}}};
 const voice=createCapture({client,env,continuous:true,sttPolicy:()=>policy,setTimer(fn){timers.set(++n,fn);return n;},clearTimer:id=>timers.delete(id)});
 try{await voice.start();policy.engine='soniox';await voice.reconnect();
  assert.deepEqual(calls.map(c=>c.sttPolicy?.engine),['local','local']);
  await voice.stop();await voice.start();assert.equal(calls.at(-1).sttPolicy.engine,'soniox');
 }finally{await voice.stop();}
});
test('Fold settings expose selection and recent controls with Jev explicitly unavailable',()=>{
 const html=fs.readFileSync('main/resources/static/assets/display/index.html','utf8');
 for(const id of ['nf-answer-target','nf-answer-model','nf-answer-fallback','nf-recent-enabled','nf-recent-age','nf-recent-count','nf-recent-budget','stt-engine','stt-fallback','stt-apply','nf-jev'])assert.ok(html.includes('id="'+id+'"'),id);
 assert.match(html,/Jev: 확인 중/);assert.match(html,/<option value="SHADOW" disabled>/);assert.match(html,/<option value="ON" disabled>/);
});
test('Jev reason status uses its own table and never the camera permission text',async()=>{
 const cases={auth_invalid:'키 만료·무효',key_invalid_or_expired:'키 만료·무효',auth_blocked:'인증 차단', // jev-vocab: legacy-alias
  plan_gate:'요금제 제한',permission_denied:'Jev 접근 거부',forbidden:'Jev 접근 거부', // jev-vocab: legacy-alias
  budget_skip:'예산 제한',rate_limited:'요청 한도 초과',upstream_error:'Jev 서버 오류'};
 for(const [reason,frag] of Object.entries(cases)){
  const f=controlsFixture({jev:{configured:true,mode:'on',callsAllowed:false,reason}});
  try{await flush();await flush();
   const s=f.$('nf-jev-status').textContent;
   assert.ok(s.includes(frag),'reason='+reason+' text='+s);
   assert.ok(s.includes('('+reason+')'),'raw code kept for '+reason+' in '+s);
   assert.ok(!s.includes('카메라 권한'),'camera text must not leak for '+reason);
  }finally{f.controls.dispose();}
 }
 const wait=controlsFixture({jev:{configured:true,mode:'on',callsAllowed:false,reason:'mystery_code'}});
 try{await flush();await flush();assert.match(wait.$('nf-jev-status').textContent,/대기\(mystery_code\)/);}finally{wait.controls.dispose();}
 const unconfigured=controlsFixture({jev:{configured:false,mode:'on'}});
 try{await flush();await flush();assert.match(unconfigured.$('nf-jev-status').textContent,/Jev 키 미설정 · 기존 검색 판단을 사용합니다/);}finally{unconfigured.controls.dispose();}
});


test('catalog reload fences an earlier delayed response and preserves current selection',async()=>{
 const pending=[],f=controlsFixture({fetchImpl:()=>new Promise(resolve=>pending.push(resolve))});
 const response=id=>({ok:true,json:async()=>[{id,provider:'fixture',selectable:true}]});
 try{await flush();assert.equal(pending.length,1);
  await f.$('nf-reload').onclick();await flush();assert.equal(pending.length,2);
  pending[1](response('fresh-fixture'));await flush();
  assert.ok(f.$('nf-answer-model').children.some(c=>c.value==='fresh-fixture'));
  pending[0](response('stale-fixture'));await flush();
  assert.equal(f.$('nf-answer-model').value,'fixture-primary');
  assert.ok(!f.$('nf-answer-model').children.some(c=>c.value==='stale-fixture'));
 }finally{for(const resolve of pending)resolve(response('cleanup-fixture'));f.controls.dispose();}
});
test('Jev surface line shows focus/cue only while global mode is not off',async()=>{
 const html=fs.readFileSync('main/resources/static/assets/display/index.html','utf8');
 assert.ok(html.includes('id="nf-jev-surfaces"'),'index.html must carry nf-jev-surfaces');
 const f=controlsFixture({jev:{configured:true,mode:'shadow',callsAllowed:false,reason:'budget_skip',surfaceModes:{focus:'shadow',cue:'off',main:'off'}}});
 try{await flush();await flush();
  assert.equal(f.$('nf-jev-surfaces').textContent,'답변(Focus): SHADOW · 힌트(Cue): OFF');
  assert.equal(f.$('nf-jev-surfaces').hidden,false);
 }finally{f.controls.dispose();}
});
test('Jev surface line stays hidden when mode is off or surfaceModes missing',async()=>{
 for(const jev of [{configured:true,mode:'off',callsAllowed:false,reason:'disabled',surfaceModes:{focus:'off',cue:'off',main:'off'}},
                   {configured:true,mode:'shadow',callsAllowed:true,reason:'ready'},
                   undefined]){
  const f=controlsFixture({jev});
  try{await flush();await flush();
   const line=f.$('nf-jev-surfaces');
   assert.ok(line.hidden===true||line.textContent==='','hidden or empty for '+JSON.stringify(jev));
  }finally{f.controls.dispose();}
 }
});
test('unknown Jev surface values render OFF and main is never shown',async()=>{
 const f=controlsFixture({jev:{configured:true,mode:'on',callsAllowed:true,reason:'ready',surfaceModes:{focus:'garbage',cue:'on',main:'on',extra:'shadow'}}});
 try{await flush();await flush();
  const line=f.$('nf-jev-surfaces');
  assert.equal(line.textContent,'답변(Focus): OFF · 힌트(Cue): ON');
  assert.equal(line.textContent.split('·').length,2);
  assert.ok(!/MAIN/i.test(line.textContent));
 }finally{f.controls.dispose();}
});
test('Jev surface line writes via textContent only',()=>{
 const src=fs.readFileSync('main/resources/static/assets/display/display-focus-controls.js','utf8');
 const idx=src.indexOf('nf-jev-surfaces');
 assert.ok(idx>=0,'nf-jev-surfaces referenced in display-focus-controls.js');
 assert.ok(!/innerHTML/.test(src.slice(Math.max(0,idx-400),idx+800)),'no innerHTML near nf-jev-surfaces');
});
