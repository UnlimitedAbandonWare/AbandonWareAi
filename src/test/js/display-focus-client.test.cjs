const {test}=require('node:test'),assert=require('node:assert/strict');
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
const {mount}=require('../../../main/resources/static/assets/display/display-focus-controls.js');
const flush=()=>new Promise(setImmediate);
test('owner cache restores unacknowledged input without auto submission and reconciles durable acceptance',async()=>{
  const rows=new Map(),scopeA='a'.repeat(64),scopeB='b'.repeat(64);let scope=scopeA,accepted=false,online=true,inputCalls=0,failInput=true;
  const cache={async read(key){return key?structuredClone(rows.get(key)||{pages:{},outbox:[]}):null;},async change(key,fn){if(!key)return null;const row=structuredClone(rows.get(key)||{pages:{},outbox:[]});fn(row);rows.set(key,row);return row;}};
  const settings={presentation:{}};
  function setup(){
    const elements=new Map();const element=id=>{if(!elements.has(id))elements.set(id,{value:'',textContent:'',type:'text',disabled:false,children:[],replaceChildren(){this.children=[];},append(...items){this.children.push(...items);}});return elements.get(id);};
    const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},crypto:{randomUUID:()=> 'stable-request'}};
    const client={async focusRequest(route,body){
      if(route==='settings/read')return {settingsVersion:1,settings,cacheScope:scope};
      if(route==='input/status')return {accepted};
      if(route==='input'){inputCalls++;if(failInput)throw Error('timeout');accepted=true;return {};}
      if(route==='history'){if(!online)throw Error('offline');return {turns:[{question:'synthetic question',answer:'synthetic answer',state:'COMPLETED'}],beforeSequence:null};}
      throw Error(route);
    }};
    const controls=mount({host,cache,document:{getElementById:element,addEventListener(){},createElement:tag=>({textContent:'',append(){}})},client});
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
  const element=id=>{if(!elements.has(id))elements.set(id,{type:['nf-enabled','nf-sequential','nf-fade-on'].includes(id)?'checkbox':'number',value:'',checked:false,disabled:false,textContent:'',replaceChildren(){},append(){}});return elements.get(id);};
  const settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,presentation:{sequentialTextEnabled:true,charIntervalMs:80,maxVisibleLines:6,autoFadeEnabled:true,tailHoldMs:5000,fadeMs:400}};
  const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},localStorage:{setItem(){localWrites++;}},crypto:{randomUUID:()=> 'r'}};
  const controls=mount({host,document:{getElementById:element,addEventListener(){}},client:{async focusRequest(route,body){calls.push({route,body});return {settingsVersion:route==='settings'?8:7,settings};}}});
  controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true,testStatus:null});await flush();
  assert.equal(element('nova-open').disabled,false);
  assert.equal(element('nf-speed').value,80);assert.equal(element('nf-enabled').checked,false);
  element('nf-speed').value='100';await element('nova-settings-form').onsubmit({preventDefault(){}});
  assert.equal(calls.at(-1).body.settingsVersion,7);assert.equal(calls.at(-1).body.settings.presentation.charIntervalMs,100);
  assert.equal(localWrites,0);controls.dispose();
});
test('a manual retry after an unknown HTTP outcome keeps the same request identity',async()=>{
 const elements=new Map(),requests=[];let id=0;
 const element=name=>{if(!elements.has(name))elements.set(name,{value:'',textContent:'',disabled:false,replaceChildren(){}});return elements.get(name);};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},crypto:{randomUUID:()=>String(++id)}};
 const row={pages:{},outbox:[]},cache={async read(){return row;},async change(scope,fn){fn(row);return row;}};
 const controls=mount({host,cache,document:{getElementById:element,addEventListener(){}},client:{async focusRequest(route,body){
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
 const element=name=>{if(!elements.has(name))elements.set(name,{value:'',textContent:'',disabled:false,replaceChildren(){}});return elements.get(name);};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}},crypto:{randomUUID:()=> 'r'}};
 const controls=mount({host,document:{getElementById:element,addEventListener(){}},client:{async focusRequest(route){
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
 const element=name=>{if(!elements.has(name))elements.set(name,{value:'',textContent:'',disabled:false,replaceChildren(){}});return elements.get(name);};
 const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,dispose(){}}),receiptSender:()=>()=>{}}};
 const controls=mount({host,document:{getElementById:element,addEventListener(){}},client:{async focusRequest(){attempts++;throw Error('unavailable');}}});
 for(let i=0;i<12;i++){controls.update({assistId:'s',epoch:1,ready:true,connection:'READY',focusProducer:true});await flush();}
 assert.equal(attempts,3);await element('nf-reload').onclick();assert.equal(attempts,4);controls.dispose();
});
