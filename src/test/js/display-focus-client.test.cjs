const {test}=require('node:test'),assert=require('node:assert/strict');
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
const {mount}=require('../../../main/resources/static/assets/display/display-focus-controls.js');
const flush=()=>new Promise(setImmediate);

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
  assert.equal(element('nf-answer-length').value,480);
  element('nf-answer-length').value='320';element('nf-quick').checked=true;
  element('nf-speed').value='100';await element('nova-settings-form').onsubmit({preventDefault(){}});
  assert.equal(calls.at(-1).body.settingsVersion,7);assert.equal(calls.at(-1).body.settings.presentation.charIntervalMs,100);
  assert.equal(calls.at(-1).body.settings.answerLengthChars,320);assert.equal(calls.at(-1).body.settings.quickAnswerEnabled,true);
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
  assert.equal(element('nf-answer-model').value,'fixture-b');assert.match(choice().textContent,/미확인/);
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
