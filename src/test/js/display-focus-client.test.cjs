const {test}=require('node:test'),assert=require('node:assert/strict');
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
const {mount}=require('../../../main/resources/static/assets/display/display-focus-controls.js');
const flush=()=>new Promise(setImmediate);
function cameraControlsFixture(auto=false){
 const elements=new Map(),calls=[],captures=[];let stops=0,failSave=false;
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,hidden:true,type:'number',textContent:'',append(){},replaceChildren(){},removeAttribute(name){delete this[name];}});return elements.get(id);};
 let settings={enabled:true,wakeWord:'노바',cameraWakeWord:'데빈',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:400,presentation:{},snapshot:{enabled:auto,source:'FOLD_REAR',cameraAllowed:true}};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},DisplaySnapshot:{createSnapshotter:()=>({captureOnce:()=>new Promise((resolve,reject)=>{resolve.reject=reject;captures.push(resolve);}),stop(){stops++;}})}};
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{focusRequest:async(route,body)=>{calls.push({route,body});if(route==='snapshot/claim')return {claimed:true,granted:true};if(route==='settings'){if(failSave)throw Error('synthetic_settings_failed');settings={...settings,...body.settings};}return {settingsVersion:1,settings};}}});
 const state={assistId:'camera-fixture',epoch:1,ready:true,connection:'READY',focusProducer:true};
 const command=(trigger='camera_wake',id='capture-a')=>({...state,focusControl:{kind:'snapshot',trigger,source:'FOLD_REAR',requestId:'request-'+id,captureId:id,expiresInMs:12000,claimed:false}});
 return {controls,element,calls,captures,command,get stops(){return stops;},set failSave(value){failSave=value;},async ready(){controls.update(state);await flush();},save:()=>element('nova-settings-form').onsubmit({preventDefault(){}})};
}
test('camera wake after saving auto OFF captures and uploads exactly once',async()=>{
 const f=cameraControlsFixture(true);try{await f.ready();f.element('nf-snapshot-enabled').checked=false;await f.save();
  f.controls.update(f.command());await flush();assert.equal(f.captures.length,1);
  f.captures[0]({ok:true,base64:'QUJD',mimeType:'image/jpeg'});await flush();
  assert.equal(f.calls.filter(x=>x.route==='snapshot/claim').length,1);assert.equal(f.calls.filter(x=>x.route==='snapshot/result'&&x.body.imageBase64==='QUJD').length,1);
 }finally{f.controls.dispose();}
});
test('prompt-only save with auto OFF preserves pending camera capture and its IDs',async()=>{
 const f=cameraControlsFixture();try{await f.ready();f.controls.update(f.command());await flush();assert.equal(f.captures.length,1);
  const stops=f.stops;f.element('nf-answer-instruction').value='두 문장으로 답한다.';await f.save();assert.equal(f.stops,stops);
  f.captures[0]({ok:true,base64:'QUJD',mimeType:'image/jpeg'});await flush();
  const upload=f.calls.find(x=>x.route==='snapshot/result');assert.ok(upload);assert.equal(upload.body.captureId,'capture-a');assert.equal(upload.body.requestId,'request-capture-a');
 }finally{f.controls.dispose();}
});
test('auto ON to OFF and camera permission OFF still cancel pending capture',async()=>{
 for(const wholeCamera of [false,true]){const f=cameraControlsFixture(true);try{await f.ready();f.controls.update(f.command(wholeCamera?'camera_wake':'auto'));await flush();
  if(wholeCamera)f.element('nf-camera-allowed').checked=false;else f.element('nf-snapshot-enabled').checked=false;
  await f.save();f.captures[0]({ok:true,base64:'QUJD',mimeType:'image/jpeg'});await flush();assert.equal(f.calls.filter(x=>x.route==='snapshot/result').length,0);
 }finally{f.controls.dispose();}}
});
test('test-shot late result never restores preview after close, scope loss, camera OFF or dispose',async()=>{
 for(const action of ['close','scope','off','dispose']){const f=cameraControlsFixture();try{await f.ready();
  const shot=f.element('nf-snapshot-test').onclick();assert.equal(f.captures.length,1);
  if(action==='close')await f.controls.close();
  if(action==='scope')f.controls.update({assistId:'other',epoch:2,ready:true,connection:'READY',focusProducer:false});
  if(action==='off'){f.element('nf-camera-allowed').checked=false;await f.save();}
  if(action==='dispose')f.controls.dispose();
  f.captures[0]({ok:true,base64:'QUJD',mimeType:'image/jpeg',width:640,height:480});await shot;
  assert.equal(f.element('nf-snapshot-preview').hidden,true);assert.equal(f.element('nf-snapshot-preview').src,undefined);
  assert.equal(f.calls.filter(x=>x.route==='snapshot/claim'||x.route==='snapshot/result').length,0);
 }finally{f.controls.dispose();}}
});
test('cancelled capture A late resolve or reject cannot stop or clear active capture B',async()=>{
 for(const reject of [false,true]){const f=cameraControlsFixture();try{await f.ready();f.controls.update(f.command());await flush();
  await f.controls.close();f.controls.update(f.command('camera_wake','capture-b'));await flush();assert.equal(f.captures.length,2);
  const stops=f.stops,status=f.element('nova-snapshot-status').textContent;
  if(reject)f.captures[0].reject(Error('late_failure'));else f.captures[0]({ok:true,base64:'OLD',mimeType:'image/jpeg'});
  await flush();assert.equal(f.stops,stops);assert.equal(f.element('nova-snapshot-status').textContent,status);
  f.captures[1]({ok:true,base64:'NEW',mimeType:'image/jpeg'});await flush();
  assert.deepEqual(f.calls.filter(x=>x.route==='snapshot/result').map(x=>x.body.imageBase64),['NEW']);
 }finally{f.controls.dispose();}}
});
test('source change with auto OFF cancels the owned pending camera immediately',async()=>{
 const f=cameraControlsFixture();try{await f.ready();f.controls.update(f.command());await flush();const stops=f.stops;
  f.element('nf-snapshot-source').value='META_GLASSES';await f.save();assert.equal(f.stops,stops+1);
  f.captures[0]({ok:true,base64:'QUJD',mimeType:'image/jpeg'});await flush();assert.equal(f.calls.filter(x=>x.route==='snapshot/result').length,0);
 }finally{f.controls.dispose();}
});
test('whole camera OFF remains locally blocked when settings save fails',async()=>{
 const f=cameraControlsFixture();try{await f.ready();f.element('nf-camera-allowed').checked=false;f.failSave=true;await f.save();
  f.controls.update(f.command());await flush();assert.equal(f.captures.length,0);assert.equal(f.calls.filter(x=>x.route==='snapshot/claim').length,0);
  await f.element('nf-snapshot-test').onclick();assert.equal(f.captures.length,0);
 }finally{f.controls.dispose();}
});
test('server closure and owned scope replacement fence old callbacks before the next capture',async()=>{
 for(const boundary of ['server-close','scope'])for(const reject of [false,true]){const f=cameraControlsFixture();try{
  await f.ready();f.controls.update({...f.command(),focus:{active:true}});await flush();
  let b=f.command('camera_wake','capture-b');
  if(boundary==='server-close')f.controls.update({...f.command(),focusControl:null,focus:{active:false}});
  else b={...b,assistId:'new-owner-scope',epoch:2};
  f.controls.update({...b,focus:{active:true}});await flush();assert.equal(f.captures.length,2);
  const stops=f.stops,status=f.element('nova-snapshot-status').textContent;
  if(reject)f.captures[0].reject(Error('late_failure'));else f.captures[0]({ok:true,base64:'OLD',mimeType:'image/jpeg'});
  await flush();assert.equal(f.stops,stops);assert.equal(f.element('nova-snapshot-status').textContent,status);
  f.captures[1]({ok:true,base64:'NEW',mimeType:'image/jpeg'});await flush();assert.deepEqual(f.calls.filter(x=>x.route==='snapshot/result').map(x=>x.body.imageBase64),['NEW']);
 }finally{f.controls.dispose();}}
});
test('camera OFF intent cancels capture before unrelated form validation fails',async()=>{
 for(const wholeCamera of [false,true]){const f=cameraControlsFixture(true);try{
  await f.ready();f.controls.update(f.command(wholeCamera?'camera_wake':'auto'));await flush();const stops=f.stops;
  if(wholeCamera)f.element('nf-camera-allowed').checked=false;else f.element('nf-snapshot-enabled').checked=false;
  f.element('nf-answer-length').value='invalid';await f.save();assert.ok(f.stops>stops);
  f.captures[0]({ok:true,base64:'OLD',mimeType:'image/jpeg'});await flush();
  assert.equal(f.calls.filter(x=>x.route==='settings'||x.route==='snapshot/result').length,0);
 }finally{f.controls.dispose();}}
});
test('Fold projection retains same-scope reconnect but clears old content on assist or epoch replacement',()=>{
 const elements=new Map(),timers=new Map();let id=0;
 const doc={hidden:false,getElementById:name=>name==='nova-fold-answer'?null:element(name),addEventListener(){},removeEventListener(){}};
 function element(name){if(!elements.has(name))elements.set(name,{value:'',hidden:true,textContent:'',children:[],style:{},ownerDocument:doc,replaceChildren(){this.children=[];},removeAttribute(){}});return elements.get(name);}
 const host={NovaFocus:require('../../../main/resources/static/assets/display/display-focus.js'),setTimeout(fn){timers.set(++id,fn);return id;},clearTimeout:id=>timers.delete(id),requestAnimationFrame:()=>0,cancelAnimationFrame(){}};
 const client={state:{},focusRequest:async()=>({})};
 const controls=mount({host,document:doc,client});
 const focus={active:true,phase:'LISTENING',serverInstanceId:'server',activationId:'old',turnId:'',stateVersion:9,answerVersion:0,draftText:'previous scope',questionText:'',answerText:'',renderTarget:'fold',idleRemainingMs:0};
 try{
  controls.update({assistId:'A',epoch:1,ready:false,connection:'PREPARING',focus});assert.equal(element('nova-fold').hidden,false);
  controls.update({assistId:'A',epoch:1,ready:false,connection:'RECONNECTING',focus:null});assert.equal(element('nova-fold').hidden,false);
  controls.update({assistId:'B',epoch:2,ready:false,connection:'RECONNECTING',focus:null});assert.equal(element('nova-fold').hidden,true);assert.equal(element('nova-fold-draft').textContent,'');assert.equal(controls.active(),false);
  controls.update({assistId:'B',epoch:2,ready:false,connection:'PREPARING',focus:{...focus,activationId:'new',stateVersion:1,draftText:'new scope'}});assert.equal(element('nova-fold').hidden,false);assert.equal(element('nova-fold-draft').textContent,'new scope');
  const ended={...focus,active:false,phase:'ARMED',activationId:'',stateVersion:1,draftText:'',reason:'session_changed'};
  controls.update({assistId:'C',epoch:1,ready:false,connection:'RECONNECTING',focus:ended});
  assert.equal(element('nova-fold').hidden,false);assert.equal(element('nova-fold-draft').textContent,'');assert.match(element('nova-fold-status').textContent,/노바 종료됨.*연결 세션/);
  controls.update({assistId:'C',epoch:1,ready:false,connection:'RECONNECTING',focus:ended});assert.equal(timers.size,1);
  for(const fn of timers.values())fn();assert.equal(element('nova-fold').hidden,true);assert.equal(controls.active(),false);
 }finally{controls.dispose();}
});

test('dedicated Gemini search is an explicit existing target option with a manual general-mode action',()=>{
 const html=require('node:fs').readFileSync(require('node:path').resolve(__dirname,'../../../main/resources/static/assets/display/index.html'),'utf8');
 assert.match(html,/value="GEMINI_WEBSEARCH_ONLY"/);
 assert.match(html,/id="nf-general-mode"/);
});

test('dedicated reload preserves disabled backups and manual mode change only saves the next-request policy',async()=>{
 const elements=new Map(),calls=[];
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,type:'number',textContent:'',append(){},replaceChildren(){},removeAttribute(){}});return elements.get(id);};
 let settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:400,quickAnswerEnabled:false,webSearchEnabled:false,presentation:{},
  answerSelection:{mode:'FIXED',modelId:'llmrouter.gemini-pro',routing:{executionTarget:'GEMINI_WEBSEARCH_ONLY',fallbackAllowed:false,allowedFallbackIds:['llmrouter.backup']}}};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}}};
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{async focusRequest(route,body){calls.push({route,body});if(route==='settings')settings=body.settings;return {settingsVersion:route==='settings'?2:1,settings};}}});
 try{
  controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
  assert.equal(element('nf-answer-target').value,'GEMINI_WEBSEARCH_ONLY');assert.equal(element('nf-web-search').value,'false');
  assert.equal(element('nf-answer-backup-1').value,'llmrouter.backup');assert.equal(element('nf-answer-backup-1').disabled,true);
  const before=calls.length;element('nf-general-mode').onclick();assert.equal(calls.length,before);assert.equal(element('nf-answer-target').value,'AUTO');assert.equal(element('nf-answer-backup-1').disabled,false);
  await element('nova-settings-form').onsubmit({preventDefault(){}});
  assert.equal(calls.at(-1).route,'settings');assert.equal(calls.at(-1).body.settingsVersion,1);
  assert.equal(calls.at(-1).body.settings.answerSelection.modelId,'llmrouter.gemini-pro');assert.equal(calls.at(-1).body.settings.answerSelection.routing.executionTarget,'AUTO');
  assert.deepEqual(calls.at(-1).body.settings.answerSelection.routing.allowedFallbackIds,['llmrouter.backup']);assert.equal(calls.at(-1).body.settings.webSearchEnabled,false);
  assert.equal(calls.filter(c=>c.route==='input').length,0);
 }finally{controls.dispose();}
});

test('strict mode shows effective fallback OFF, preserves raw preference and explicit ON leaves strict atomically',async()=>{
 const elements=new Map(),calls=[];
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,type:'number',textContent:'',append(){},replaceChildren(){},removeAttribute(){}});return elements.get(id);};
 let settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:400,quickAnswerEnabled:false,webSearchEnabled:true,presentation:{},
  answerSelection:{mode:'FIXED',modelId:'llmrouter.gemini-pro',routing:{executionTarget:'GEMINI_WEBSEARCH_ONLY',fallbackAllowed:true,effectiveFallbackAllowed:false,allowedFallbackIds:['llmrouter.backup']}}};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}}};
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{async focusRequest(route,body){calls.push({route,body});if(route==='settings')settings=body.settings;return {settingsVersion:route==='settings'?2:1,settings};}}});
 try{
  controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
  assert.equal(element('nf-answer-fallback').checked,false);assert.equal(element('nf-answer-fallback').disabled,false);
  assert.equal(element('nf-answer-backup-1').disabled,true);assert.match(element('nf-model-status').textContent,/OFF/);
  await element('nova-settings-form').onsubmit({preventDefault(){}});
  assert.equal(calls.at(-1).body.settings.answerSelection.routing.fallbackAllowed,true);
  assert.equal(calls.at(-1).body.settings.answerSelection.routing.executionTarget,'GEMINI_WEBSEARCH_ONLY');
  const before=calls.length;element('nf-answer-fallback').checked=true;element('nf-answer-fallback').onchange();
  assert.equal(calls.length,before);assert.equal(element('nf-answer-target').value,'AUTO');
  assert.equal(element('nf-answer-model').value,'llmrouter.gemini-pro');assert.equal(element('nf-web-search').value,'true');
  assert.equal(element('nf-answer-backup-1').disabled,false);assert.match(element('nf-model-status').textContent,/전환 ON/);
  await element('nova-settings-form').onsubmit({preventDefault(){}});
  const saved=calls.at(-1).body.settings;
  assert.equal(saved.answerSelection.mode,'FIXED');assert.equal(saved.answerSelection.modelId,'llmrouter.gemini-pro');
  assert.equal(saved.answerSelection.routing.executionTarget,'AUTO');assert.equal(saved.answerSelection.routing.fallbackAllowed,true);
  assert.deepEqual(saved.answerSelection.routing.allowedFallbackIds,['llmrouter.backup']);assert.equal(saved.webSearchEnabled,true);
  assert.equal(calls.filter(c=>c.route==='input').length,0);
 }finally{controls.dispose();}
});

test('lens font save sends its CAS version and rejects a late response from the previous scope',async()=>{
 const a='12345678-1234-4234-8234-123456789abc',b='12345678-1234-4234-8234-123456789abd';
 const timers=new Map();let n=0,completeFont,scope=a;
 const calls=[];const snapshot=()=>({assistId:scope,epoch:1,ready:true,version:1,caption:null,card:null,captionTtlMs:0,cardTtlMs:0,audioAvailable:false,audioState:'READY',hintsEnabled:false,role:'STANDALONE',testStatus:{lensSettingsVersion:7,lensSettingsScope:scope,display:{hintFontPx:26}}});
 const client=createClient({transcription:true,standalone:true,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id),fetchImpl:async(url,options)=>{
  const body=JSON.parse(options.body);calls.push({url,body});
  if(url.endsWith('/relay/lens-settings'))return new Promise(resolve=>completeFont=()=>resolve({ok:true,json:async()=>({...snapshot(),assistId:a,testStatus:{lensSettingsVersion:8,lensSettingsScope:a,display:{hintFontPx:36}}})}));
  return {ok:true,json:async()=>snapshot()};
 }});
 try{client.start();await flush();const pending=client.lensSettings({hintFontPx:36});await flush();assert.equal(calls.at(-1).body.expectedSettingsVersion,7);
  scope=b;await client.reconnect({preserveSession:false});completeFont();await assert.rejects(pending,/lens_settings_stale/);
  assert.equal(client.state.assistId,b);assert.equal(client.state.testStatus.display.hintFontPx,26);
 }finally{client.dispose();}
});
test('a late lens save cannot roll back a newer settings revision in the same scope',async()=>{
 const scope='12345678-1234-4234-8234-123456789abc',timers=new Map();let n=0,completeFont,revision=7,font=26;
 const snapshot=()=>({assistId:scope,epoch:1,ready:true,version:1,caption:null,card:null,captionTtlMs:0,cardTtlMs:0,audioAvailable:false,audioState:'READY',hintsEnabled:false,role:'STANDALONE',testStatus:{lensSettingsVersion:revision,lensSettingsScope:scope,display:{hintFontPx:font}}});
 const client=createClient({transcription:true,standalone:true,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id),fetchImpl:async(url)=>{
  if(url.endsWith('/relay/lens-settings'))return new Promise(resolve=>completeFont=()=>resolve({ok:true,json:async()=>({...snapshot(),testStatus:{lensSettingsVersion:8,lensSettingsScope:scope,display:{hintFontPx:36}}})}));
  return {ok:true,json:async()=>snapshot()};
 }});
 try{client.start();await flush();const pending=client.lensSettings({hintFontPx:36});await flush();
  revision=9;font=20;await client.reconnect({preserveSession:true});completeFont();await assert.rejects(pending,/lens_settings_stale/);
  assert.equal(client.state.testStatus.lensSettingsVersion,9);assert.equal(client.state.testStatus.display.hintFontPx,20);
 }finally{client.dispose();}
});
test('an older poll preserves newer lens settings while updating audio state',async()=>{
 const scope='12345678-1234-4234-8234-123456789abc',timers=new Map();let n=0,completePoll;
 const snapshot=(revision,font,audioState='READY')=>({assistId:scope,epoch:1,ready:true,version:1,caption:null,card:null,captionTtlMs:0,cardTtlMs:0,audioAvailable:false,audioState,hintsEnabled:false,role:'STANDALONE',testStatus:{lensSettingsVersion:revision,lensSettingsScope:scope,lensDisplay:{hintFontPx:font},processing:false}});
 const client=createClient({transcription:true,standalone:true,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id),fetchImpl:async(url)=>{
  if(url.endsWith('/poll'))return new Promise(resolve=>completePoll=()=>resolve({ok:true,json:async()=>snapshot(7,26,'STOPPED')}));
  return {ok:true,json:async()=>url.endsWith('/relay/lens-settings')?snapshot(8,36):snapshot(7,26)};
 }});
 try{client.start();await flush();const poll=[...timers.values()].find(t=>t.ms===1000);const polling=poll.fn();await flush();
  await client.lensSettings({hintFontPx:36});assert.equal(client.state.testStatus.lensSettingsVersion,8);
  completePoll();await polling;assert.equal(client.state.testStatus.lensSettingsVersion,8);
  assert.equal(client.state.testStatus.lensDisplay.hintFontPx,36);assert.equal(client.state.audioState,'STOPPED');
 }finally{client.dispose();}
});
test('only selected memory API work requests its bounded transport budget',async()=>{
  const calls=[],timers=new Map();let n=0;
  const snapshot={assistId:'12345678-1234-4234-8234-123456789abc',epoch:1,ready:true,version:1,caption:null,card:null,captionTtlMs:0,cardTtlMs:0,audioAvailable:false,audioState:'READY',hintsEnabled:false,role:'STANDALONE'};
  const client=createClient({transcription:true,standalone:true,uuid:()=>snapshot.assistId,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id),
    fetchImpl:async(url,options)=>{calls.push({url,headers:options.headers});return {ok:true,json:async()=>url.includes('/focus/')?{}:snapshot};}});
  try{
    client.start();await flush();
    await client.focusRequest('memory/save',{edit:{}});assert.equal(calls.at(-1).headers['X-Budget-Ms'],'5000');
    await client.focusRequest('memory/search',{text:'synthetic query'});assert.equal(calls.at(-1).headers['X-Budget-Ms'],'5000');
    await client.focusRequest('settings/read');assert.equal(calls.at(-1).headers['X-Budget-Ms'],undefined);
    await client.focusRequest('memory/read');assert.equal(calls.at(-1).headers['X-Budget-Ms'],undefined);
  }finally{client.dispose();}
});
test('owner cache restores unacknowledged input without auto submission and reconciles durable acceptance',async()=>{
  const rows=new Map(),scopeA='a'.repeat(64),scopeB='b'.repeat(64);let scope=scopeA,accepted=false,online=true,inputCalls=0,failInput=true;
  const cache={async read(key){return key?structuredClone(rows.get(key)||{pages:{},outbox:[]}):null;},async change(key,fn){if(!key)return null;const row=structuredClone(rows.get(key)||{pages:{},outbox:[]});fn(row);rows.set(key,row);return row;}};
  const settings={presentation:{}};
  function setup(){
    const elements=new Map();const element=id=>{if(!elements.has(id))elements.set(id,{value:'',textContent:'',type:'text',disabled:false,children:[],append(){},removeAttribute(){},replaceChildren(){this.children=[];},append(...items){this.children.push(...items);}});return elements.get(id);};
    const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},crypto:{randomUUID:()=> 'stable-request'}};
    const client={async focusRequest(route,body){
      if(route==='settings/read')return {settingsVersion:1,settings,cacheScope:scope};
      if(route==='input/status')return {accepted};
      if(route==='input'){inputCalls++;if(failInput)throw Error('timeout');accepted=true;return {};}
      if(route==='history'){if(!online)throw Error('offline');return {turns:[{question:'synthetic question',answer:'synthetic answer',state:'COMPLETED'}],beforeSequence:null};}
      throw Error(route);
    }};
    const controls=mount({host,cache,document:{getElementById:element,removeEventListener(){},createElement:()=>({textContent:'',append(){}}),addEventListener(){},createElement:tag=>({textContent:'',append(){}})},client});
    return {controls,element,connect:id=>controls.update({assistId:id,epoch:1,ready:true,connection:'READY',focusProducer:true})};
  }
  const first=setup();first.connect('first');await flush();first.element('nova-question').value='pending synthetic question';
  await first.element('nova-question-form').onsubmit({preventDefault(){}});
  assert.equal(rows.get(scopeA).outbox.length,1);assert.equal(inputCalls,1);first.controls.dispose();
  const second=setup();second.connect('second');await flush();
  assert.equal(second.element('nova-question').value,'pending synthetic question');assert.equal(inputCalls,1);
  failInput=false;await second.element('nova-question-form').onsubmit({preventDefault(){}});
  assert.equal(inputCalls,2);assert.equal(rows.get(scopeA).outbox.length,0);
  await second.element('nova-history-open').onclick();assert.equal(Object.keys(rows.get(scopeA).pages).length,1);
  online=false;await second.element('nova-history-open').onclick();assert.match(second.element('nf-status').textContent,/임시 기록/);
  scope=scopeB;second.connect('other-owner');await flush();
  assert.equal(second.element('nova-question').value,'');assert.equal(second.element('nova-history-list').children.length,0);
  await second.element('nova-history-open').onclick();assert.equal(second.element('nova-history-list').children.length,0);
  second.controls.dispose();
});
test('Focus input uses its owned endpoint during capture and does not stop audio',async()=>{
  const calls=[],timers=new Map();let n=0;
  const snapshot={assistId:'12345678-1234-4234-8234-123456789abc',epoch:1,ready:true,version:1,caption:null,card:null,captionTtlMs:0,cardTtlMs:0,audioAvailable:true,audioState:'READY',hintsEnabled:false,role:'STANDALONE'};
  const focus={serverInstanceId:'s',activationId:'a',stateVersion:1,active:true};
  const client=createClient({transcription:true,standalone:true,uuid:()=>snapshot.assistId,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id),
    fetchImpl:async(url,options)=>{calls.push({url,body:JSON.parse(options.body)});return {ok:true,json:async()=>url.includes('/focus/')?focus:snapshot};}});
  client.start();await flush();await client.beginVoice();
  assert.equal(client.state.voiceActive,true);
  await client.focusRequest('input',{requestId:'r',text:'독립 질문'});
  const request=calls.at(-1);assert.match(request.url,/\/focus\/input$/);assert.equal(request.body.assistId,snapshot.assistId);assert.equal(request.body.epoch,1);
  assert.equal(client.state.voiceActive,true);assert.equal(client.state.hintsEnabled,false);
  await client.focusRequest('close');assert.equal(calls.filter(c=>c.url.endsWith('/audio/stop')).length,0);client.dispose();
});

test('settings load from server, send CAS version, and never write localStorage',async()=>{
  const elements=new Map(),calls=[];let localWrites=0;
  const element=id=>{if(!elements.has(id))elements.set(id,{type:['nf-enabled','nf-sequential','nf-fade-on'].includes(id)?'checkbox':'number',value:'',checked:false,disabled:false,textContent:'',append(){},removeAttribute(){},replaceChildren(){},append(){}});return elements.get(id);};
  const settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:480,quickAnswerEnabled:false,presentation:{sequentialTextEnabled:true,charIntervalMs:80,maxVisibleLines:6,autoFadeEnabled:true,tailHoldMs:5000,fadeMs:400}};
  const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},localStorage:{setItem(){localWrites++;}},crypto:{randomUUID:()=> 'r'}};
  const controls=mount({host,document:{getElementById:element,removeEventListener(){},createElement:()=>({textContent:'',append(){}}),addEventListener(){}},client:{async focusRequest(route,body){calls.push({route,body});return {settingsVersion:route==='settings'?8:7,settings};}}});
  controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true,testStatus:null});await flush();
  assert.equal(element('nova-open').disabled,false);
  assert.equal(element('nf-speed').value,80);assert.equal(element('nf-enabled').checked,false);
  assert.equal(element('nf-hold').value,5);
  assert.equal(element('nf-answer-length').value,480);
  assert.equal(element('nf-reasoning').value,'STANDARD');
  element('nf-reasoning').value='DEEP';
  element('nf-answer-length').value='320';element('nf-quick').checked=true;element('nf-web-search').value='false';
  element('nf-speed').value='100';element('nf-hold').value='3';await element('nova-settings-form').onsubmit({preventDefault(){}});
  assert.equal(calls.at(-1).body.settingsVersion,7);assert.equal(calls.at(-1).body.settings.presentation.charIntervalMs,100);
  assert.equal(calls.at(-1).body.settings.answerLengthChars,320);assert.equal(calls.at(-1).body.settings.quickAnswerEnabled,true);
  assert.equal(calls.at(-1).body.settings.webSearchEnabled,false);
  assert.equal(calls.at(-1).body.settings.reasoningPreset,'DEEP');
  assert.equal(calls.at(-1).body.settings.presentation.tailHoldMs,3000);
  settings.presentation.tailHoldMs=3000;await element('nf-reload').onclick();assert.equal(element('nf-hold').value,3);
  element('nf-hold').value='5';await element('nova-settings-form').onsubmit({preventDefault(){}});
  assert.equal(calls.at(-1).body.settings.presentation.tailHoldMs,5000);
  assert.equal(localWrites,0);controls.dispose();
});
test('late settings save cannot re-enable a previous producer after scope loss',async()=>{
 const elements=new Map();let complete;
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,type:'number',textContent:'',append(){},replaceChildren(){},removeAttribute(){}});return elements.get(id);};
 const settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:400,presentation:{}};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}}};
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{focusRequest(route){return route==='settings'?new Promise(resolve=>{complete=resolve;}):Promise.resolve({settingsVersion:1,settings});}}});
 controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
 const saving=element('nova-settings-form').onsubmit({preventDefault(){}});
 controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:false});
 complete({settingsVersion:2,settings});await saving;
 assert.equal(element('nf-save').disabled,true);controls.dispose();
});
test('settings save echo preserves edits made while the save was pending and advances CAS',async()=>{
 const elements=new Map(),calls=[];let complete;
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,type:'number',textContent:'',append(){},replaceChildren(){},removeAttribute(){}});return elements.get(id);};
 const settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:400,presentation:{},answerSelection:{mode:'FIXED',modelId:'fixture-a'}};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}}};
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{focusRequest(route,body){calls.push({route,body});return route==='settings'?new Promise(resolve=>complete=resolve):Promise.resolve({settingsVersion:7,settings});}}});
 try{controls.update({assistId:'a',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
  const saving=element('nova-settings-form').onsubmit({preventDefault(){}});
  element('nf-answer-model').value='fixture-b';element('nf-answer-length').value='480';element('nova-settings-form').onchange?.();
  complete({settingsVersion:8,settings});await saving;
  assert.equal(element('nf-answer-model').value,'fixture-b');assert.equal(element('nf-answer-length').value,'480');
  const next=element('nova-settings-form').onsubmit({preventDefault(){}});assert.equal(calls.at(-1).body.settingsVersion,8);assert.equal(calls.at(-1).body.settings.answerSelection.modelId,'fixture-b');
  element('nf-answer-length-reset').onclick();assert.equal(element('nf-answer-length').value,400);
  complete({settingsVersion:9,settings:{...settings,answerLengthChars:480,answerSelection:{mode:'FIXED',modelId:'fixture-b'}}});await next;
  assert.equal(element('nf-answer-length').value,400);
  const resetSaving=element('nova-settings-form').onsubmit({preventDefault(){}});assert.equal(calls.at(-1).body.settingsVersion,9);assert.equal(calls.at(-1).body.settings.answerLengthChars,400);
  complete({settingsVersion:10,settings});await resetSaving;
 }finally{controls.dispose();}
});
test('owner change starts a new catalog request and old response preserves manual selection',async()=>{
 const elements=new Map(),catalog=[];
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,type:'number',children:[],textContent:'',append(...v){this.children.push(...v);},replaceChildren(){this.children=[];},removeAttribute(){}});return elements.get(id);};
 const settings={answerLengthChars:400,presentation:{},answerSelection:{mode:'FIXED',modelId:'fixture-a'}};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},fetch:()=>new Promise(resolve=>catalog.push(resolve))};
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{focusRequest:()=>Promise.resolve({settingsVersion:1,settings})}});
 controls.update({assistId:'a',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();assert.equal(catalog.length,1);
 controls.update({assistId:'b',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();assert.equal(catalog.length,2);
 element('nf-answer-model').value='fixture-b';catalog[0]({ok:true,json:async()=>[{id:'fixture-a',selectable:true}]});await flush();assert.equal(element('nf-answer-model').value,'fixture-b');
 catalog[1]({ok:true,json:async()=>[{id:'fixture-a',selectable:true},{id:'fixture-b',selectable:true}]});await flush();assert.equal(element('nf-answer-model').value,'fixture-b');controls.dispose();
});
test('catalog reload distinguishes known nonselectability from missing evidence and preserves the saved model',async()=>{
 const elements=new Map(),catalog=[];
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,type:'number',children:[],textContent:'',append(...v){this.children.push(...v);},replaceChildren(){this.children=[];},removeAttribute(){}});return elements.get(id);};
 const settings={answerLengthChars:480,presentation:{},answerSelection:{mode:'FIXED',modelId:'fixture-b'}};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},fetch:()=>new Promise(resolve=>catalog.push(resolve))};
 const calls=[];
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{focusRequest(route){calls.push(route);return Promise.resolve({settingsVersion:1,settings});}}});
 const choice=()=>element('nf-answer-model').children.find(row=>row.value==='fixture-b');
 const complete=async rows=>{catalog.at(-1)({ok:true,json:async()=>rows});await flush();};
 const reload=async rows=>{await element('nf-reload').onclick();await complete(rows);};
 try{
  controls.update({assistId:'a',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
  await complete([{id:'fixture-a',provider:'synthetic',selectable:true},{id:'fixture-b',provider:'synthetic',selectable:true}]);
  await reload([{id:'fixture-a',provider:'synthetic',selectable:true},{id:'fixture-b',provider:'synthetic',selectable:false,reason:'unsupported_endpoint'}]);
  assert.equal(element('nf-answer-model').value,'fixture-b');assert.equal(element('nf-answer-length').value,480);
  assert.match(choice().textContent,/현재 선택 불가/);assert.doesNotMatch(choice().textContent,/미확인/);assert.equal(choice().disabled,true);
  assert.match(element('nf-model-status').textContent,/현재 선택 불가/);
  element('nf-answer-model').value='fixture-a';element('nf-answer-model').onchange();
  assert.doesNotMatch(element('nf-model-status').textContent,/현재 선택 불가/);
  await reload([{id:'fixture-a',provider:'synthetic',selectable:true},{id:'fixture-b',provider:'synthetic',selectable:true}]);
  assert.equal(element('nf-answer-model').value,'fixture-b');assert.equal(choice().disabled,false);
  assert.doesNotMatch(choice().textContent,/현재 선택 불가|미확인/);
  await reload([{id:'fixture-a',provider:'synthetic',selectable:true}]);
  assert.equal(element('nf-answer-model').value,'fixture-b');assert.match(choice().textContent,/미확인/);assert.equal(choice().disabled,true);
  assert.match(element('nf-model-status').textContent,/미확인/);
  assert.ok(calls.every(route=>route==='settings/read'));
 }finally{controls.dispose();}
});

test('a manual retry after an unknown HTTP outcome keeps the same request identity',async()=>{
 const elements=new Map(),requests=[];let id=0;
 const element=name=>{if(!elements.has(name))elements.set(name,{value:'',textContent:'',disabled:false,append(){},removeAttribute(){},replaceChildren(){}});return elements.get(name);};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},crypto:{randomUUID:()=>String(++id)}};
 const row={pages:{},outbox:[]},cache={async read(){return row;},async change(scope,fn){fn(row);return row;}};
 const controls=mount({host,cache,document:{getElementById:element,removeEventListener(){},createElement:()=>({textContent:'',append(){}}),addEventListener(){}},client:{async focusRequest(route,body){
   if(route==='settings/read')return {settingsVersion:1,settings:{presentation:{}},cacheScope:'a'.repeat(64)};
   if(route==='input/status')return {accepted:true};
   requests.push({...body});if(requests.length===1)throw Error('timeout');return {};
 }}});
 controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
 element('nova-question').value='합성 질문';const submit=()=>element('nova-question-form').onsubmit({preventDefault(){}});
 await submit();assert.equal(element('nova-question').value,'합성 질문');await submit();
 assert.equal(requests[0].requestId,requests[1].requestId);assert.equal(element('nova-question').value,'');
 element('nova-question').value='합성 질문';await submit();assert.notEqual(requests[1].requestId,requests[2].requestId);controls.dispose();
});
test('manual input waits for producer scope and works without IndexedDB',async()=>{
 const elements=new Map(),requests=[];let resolveSettings;
 const element=name=>{if(!elements.has(name))elements.set(name,{value:'',textContent:'',disabled:false,append(){},removeAttribute(){},replaceChildren(){}});return elements.get(name);};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},crypto:{randomUUID:()=> 'r'}};
 const controls=mount({host,document:{getElementById:element,removeEventListener(){},createElement:()=>({textContent:'',append(){}}),addEventListener(){}},client:{async focusRequest(route){
   requests.push(route);if(route==='settings/read')return new Promise(resolve=>{resolveSettings=resolve;});return {};
 }}});
 controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});
 element('nova-question').value='pending';await element('nova-question-form').onsubmit({preventDefault(){}});
 assert.equal(requests.includes('input'),false);
 resolveSettings({settingsVersion:1,settings:{presentation:{}},cacheScope:'a'.repeat(64)});await flush();
 await element('nova-question-form').onsubmit({preventDefault(){}});
 assert.equal(requests.includes('input'),true);
 controls.dispose();
});
test('unavailable settings stop automatic retries and retain an explicit reload action',async()=>{
 const elements=new Map();let attempts=0;
 const element=name=>{if(!elements.has(name))elements.set(name,{value:'',textContent:'',disabled:false,append(){},removeAttribute(){},replaceChildren(){}});return elements.get(name);};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}}};
 const controls=mount({host,document:{getElementById:element,removeEventListener(){},createElement:()=>({textContent:'',append(){}}),addEventListener(){}},client:{async focusRequest(){attempts++;throw Error('unavailable');}}});
 for(let i=0;i<12;i++){controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();}
 assert.equal(attempts,3);await element('nf-reload').onclick();assert.equal(attempts,4);controls.dispose();
});

test('main-model preset buttons exist next to the answer model settings',()=>{
 const html=require('node:fs').readFileSync(require('node:path').resolve(__dirname,'../../../main/resources/static/assets/display/index.html'),'utf8');
 assert.match(html,/id="nf-preset-luna"/);assert.match(html,/id="nf-preset-gemini"/);assert.match(html,/id="nf-preset-auto"/);assert.match(html,/id="nf-preset-status"/);
});
test('Luna preset selects admitted OAuth only and leaves fields intact when OAuth is unavailable',async()=>{
 const elements=new Map(),calls=[];
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,type:'number',children:[],textContent:'',append(...v){this.children.push(...v);},replaceChildren(){this.children=[];},removeAttribute(){}});return elements.get(id);};
 const settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:400,quickAnswerEnabled:false,webSearchEnabled:true,presentation:{},
  answerSelection:{mode:'AUTO',modelId:null,routing:{executionTarget:'AUTO',fallbackAllowed:false,allowedFallbackIds:[]}}};
 let available=true;
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},fetch:async()=>({ok:true,json:async()=>[{id:'llmrouter.openai-economy',provider:'synthetic',selectable:false},{id:'chatgpt-oauth:gpt-5.6-luna',provider:'chatgpt-oauth',selectable:available},{id:'llmrouter.gemini-pro',provider:'synthetic',selectable:true}]})};
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{async focusRequest(route,body){calls.push({route,body});if(route==='settings')settings=body.settings;return {settingsVersion:route==='settings'?2:1,settings};}}});
 try{
  controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
  element('nf-preset-luna').onclick();
  assert.equal(element('nf-answer-target').value,'API_ONLY');assert.equal(element('nf-answer-model').value,'chatgpt-oauth:gpt-5.6-luna');
  assert.equal(element('nf-web-search').value,'false');assert.match(element('nf-preset-status').textContent,/루나/);
  await element('nova-settings-form').onsubmit({preventDefault(){}});
  const saved=calls.at(-1).body.settings;
  assert.equal(saved.answerSelection.mode,'FIXED');assert.equal(saved.answerSelection.modelId,'chatgpt-oauth:gpt-5.6-luna');
  assert.equal(saved.answerSelection.routing.executionTarget,'API_ONLY');assert.equal(saved.webSearchEnabled,false);
  assert.equal(calls.filter(c=>c.route==='input').length,0);
 }finally{controls.dispose();}
 available=false;calls.length=0;
 const blocked=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{async focusRequest(){return {settingsVersion:1,settings};}}});
 try{blocked.update({assistId:'s2',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
 const prior=[element('nf-answer-model').value,element('nf-answer-target').value,element('nf-web-search').value];
 element('nf-preset-luna').onclick();
 assert.deepEqual([element('nf-answer-model').value,element('nf-answer-target').value,element('nf-web-search').value],prior);
 assert.match(element('nf-preset-status').textContent,/선택 가능한.*없/);assert.equal(calls.length,0);
 }finally{blocked.dispose();}
});
test('Gemini preset selects the dedicated websearch route, prefers the pro route, and enables web search',async()=>{
 const elements=new Map(),calls=[];
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,type:'number',children:[],textContent:'',append(...v){this.children.push(...v);},replaceChildren(){this.children=[];},removeAttribute(){}});return elements.get(id);};
 const settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:400,quickAnswerEnabled:false,webSearchEnabled:false,presentation:{},
  answerSelection:{mode:'AUTO',modelId:null,routing:{executionTarget:'AUTO',fallbackAllowed:true,allowedFallbackIds:['llmrouter.backup']}}};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},fetch:async()=>({ok:true,json:async()=>[{id:'llmrouter.gemini-cue',provider:'synthetic',selectable:true},{id:'llmrouter.gemini-pro',provider:'synthetic',selectable:true}]})};
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{async focusRequest(route,body){calls.push({route,body});if(route==='settings')settings=body.settings;return {settingsVersion:route==='settings'?2:1,settings};}}});
 try{
  controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
  element('nf-preset-gemini').onclick();
  assert.equal(element('nf-answer-target').value,'GEMINI_WEBSEARCH_ONLY');assert.equal(element('nf-answer-model').value,'llmrouter.gemini-pro');
  assert.equal(element('nf-web-search').value,'true');assert.match(element('nf-preset-status').textContent,/제미나이/);
  assert.equal(element('nf-answer-backup-1').disabled,true);
  await element('nova-settings-form').onsubmit({preventDefault(){}});
  const saved=calls.at(-1).body.settings;
  assert.equal(saved.answerSelection.mode,'FIXED');assert.equal(saved.answerSelection.modelId,'llmrouter.gemini-pro');
  assert.equal(saved.answerSelection.routing.executionTarget,'GEMINI_WEBSEARCH_ONLY');
  assert.equal(saved.answerSelection.routing.fallbackAllowed,true);assert.deepEqual(saved.answerSelection.routing.allowedFallbackIds,['llmrouter.backup']);
  assert.equal(saved.webSearchEnabled,true);
 }finally{controls.dispose();}
});
test('auto preset restores server AUTO while an unavailable Luna preset retains the selection',async()=>{
 const elements=new Map(),calls=[];
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,type:'number',children:[],textContent:'',append(...v){this.children.push(...v);},replaceChildren(){this.children=[];},removeAttribute(){}});return elements.get(id);};
 const settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:400,quickAnswerEnabled:false,webSearchEnabled:false,presentation:{},
  answerSelection:{mode:'AUTO',modelId:null,routing:{executionTarget:'AUTO',fallbackAllowed:false,allowedFallbackIds:[]}}};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},fetch:()=>new Promise(()=>{})};
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{async focusRequest(route,body){calls.push({route,body});if(route==='settings')settings=body.settings;return {settingsVersion:route==='settings'?2:1,settings};}}});
 try{
  controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
  element('nf-preset-luna').onclick();
  assert.equal(element('nf-answer-target').value,'AUTO');assert.equal(element('nf-answer-model').value,'');
  assert.match(element('nf-preset-status').textContent,/선택 가능한.*없/);
  element('nf-preset-auto').onclick();
  assert.equal(element('nf-answer-target').value,'AUTO');assert.equal(element('nf-answer-model').value,'');
  assert.match(element('nf-preset-status').textContent,/자동/);
  await element('nova-settings-form').onsubmit({preventDefault(){}});
  const saved=calls.at(-1).body.settings;
  assert.equal(saved.answerSelection.mode,'AUTO');assert.equal(saved.answerSelection.modelId,null);
  assert.equal(saved.answerSelection.routing.executionTarget,'AUTO');assert.equal(saved.webSearchEnabled,false);
 }finally{controls.dispose();}
});


test('late Focus input response cannot replace a newer epoch or fallback banner',async()=>{
 const id='12345678-1234-4234-8234-123456789abc',timers=new Map();let n=0,epoch=1,complete;
 const current={active:true,phase:'ANSWER_READY',serverInstanceId:'boot',activationId:'new',turnId:'new-turn',stateVersion:1,answerVersion:1,draftText:'',questionText:'',answerText:'normal',idleRemainingMs:0,renderTarget:'fold',isFallback:false};
 const snapshot=()=>({assistId:id,epoch,ready:true,version:1,caption:null,card:null,captionTtlMs:0,cardTtlMs:0,audioAvailable:false,audioState:'READY',hintsEnabled:false,role:'STANDALONE',focus:current});
 const client=createClient({transcription:true,standalone:true,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id),fetchImpl:async(url)=>{
 if(url.endsWith('/focus/input'))return new Promise(resolve=>complete=()=>resolve({ok:true,json:async()=>({...current,stateVersion:99,activationId:'old',isFallback:true})}));
 return {ok:true,json:async()=>snapshot()};}});
 try{client.start();await flush();const pending=client.focusRequest('input',{requestId:'fixture',text:'synthetic'});await flush();epoch=2;await client.reconnect();complete();await assert.rejects(pending,/focus_session_stale/);assert.equal(client.state.epoch,2);assert.equal(client.state.focus.isFallback,false);}
 finally{client.dispose();}
});

test('answer instruction preset fills textarea, edits store CUSTOM, paint restores saved values',async()=>{
 const elements=new Map(),calls=[];
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,disabled:false,type:'number',textContent:'',append(){},replaceChildren(){},removeAttribute(){}});return elements.get(id);};
 let settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:400,quickAnswerEnabled:false,presentation:{},answerPreset:'INTERVIEW',answerInstruction:'저장된 지침'};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}}};
 const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{async focusRequest(route,body){calls.push({route,body});if(route==='settings')settings=body.settings;return {settingsVersion:route==='settings'?2:1,settings};}}});
 try{
  controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();
  assert.equal(element('nf-answer-preset').value,'INTERVIEW');assert.equal(element('nf-answer-instruction').value,'저장된 지침');
  element('nf-answer-preset').value='GENERAL';element('nf-answer-preset').onchange();
  assert.equal(element('nf-answer-instruction').value,'');
  element('nf-answer-preset').value='INTERVIEW';element('nf-answer-preset').onchange();
  assert.equal(element('nf-answer-instruction').value.includes('면접 중인 사용자'),true);
  element('nf-answer-instruction').value='고친 지침\n두 번째 줄';element('nf-answer-instruction').oninput();
  assert.equal(element('nf-answer-preset').value,'CUSTOM');
  assert.match(element('nf-answer-instruction-count').textContent,/^\d+\/1200$/);
  await element('nova-settings-form').onsubmit({preventDefault(){}});
  assert.equal(calls.at(-1).route,'settings');
  assert.equal(calls.at(-1).body.settings.answerPreset,'CUSTOM');
  assert.equal(calls.at(-1).body.settings.answerInstruction,'고친 지침\n두 번째 줄');
  element('nf-answer-instruction-reset').onclick();await element('nova-settings-form').onsubmit({preventDefault(){}});
  assert.equal(calls.at(-1).body.settings.answerPreset,'GENERAL');assert.equal(calls.at(-1).body.settings.answerInstruction,'');
 }finally{controls.dispose();}
});
