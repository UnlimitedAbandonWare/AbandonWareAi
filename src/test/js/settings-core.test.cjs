const test = require('node:test');
const assert = require('node:assert/strict');
const core = require('../../../main/resources/static/js/chat-settings-bridge.js');
const memory = () => {
  const values = new Map(); let writes = 0;
  return { getItem: k => values.has(k) ? values.get(k) : null,
    setItem(k,v) { writes++; values.set(k,v); }, removeItem:k=>values.delete(k), get writes() { return writes; } };
};
test('unsaved first visit uses AUTO search and RAG while explicit OFF remains sparse', () => {
  assert.equal(core.mergeDefaults({}).searchMode, 'AUTO');
  assert.equal(core.mergeDefaults({}).useRag, true);
  assert.deepEqual(core.mergeDefaults({searchMode:'OFF',useRag:false}),
    {modelSelectionMode:'preferred',executionMode:'AUTO',searchMode:'OFF',useRag:false});
  const html = require('node:fs').readFileSync(require.resolve('../../../main/resources/templates/chat-ui.html'),'utf8');
  assert.match(html, /<option value="AUTO" selected>/);
  assert.match(html, /id="useRagToggle"[^>]*\bchecked\b/);
});
test('false and explicit OFF survive defaults and round trip', () => {
  const values = {useRag:false,searchMode:'OFF'};
  assert.deepEqual(core.importSettings(core.exportSettings(values)), values);
  assert.equal(core.mergeDefaults(values).useRag, false);
});
test('reading missing storage never persists explanatory defaults', () => {
  const storage = memory();
  assert.deepEqual(core.readSettings(storage), {});
  assert.equal(storage.writes, 0);
});
test('Gemini rescue explicit ON/OFF round trips while omission stays sparse', () => {
  for (const enabled of [true, false]) {
    const storage = memory(), values = {googleSearchRescueEnabled:enabled};
    core.writeSettings(storage, values);
    assert.deepEqual(core.readSettings(storage), values);
    assert.deepEqual(core.validatePreferences(values), values);
    assert.deepEqual(core.importPreferences(JSON.stringify({version:2,values})), values);
    assert.deepEqual(core.withoutKeys(values, ['googleSearchRescueEnabled']), {});
  }
  assert.equal(Object.hasOwn(core.mergeDefaults({}), 'googleSearchRescueEnabled'), false);
  for (const value of [null, 'false', 0])
    assert.throws(() => core.validatePreferences({googleSearchRescueEnabled:value}));
});
test('unknown fields, invalid values and envelope versions are rejected atomically', () => {
  const storage = memory();
  for (const v of [{useRag:'false'}, {searchMode:'BAD'}, {provider:'x'}, {modelSelectionMode:'exact'}])
    assert.throws(() => core.writeSettings(storage,v));
  assert.equal(storage.writes,0);
  assert.throws(() => core.importSettings('{"version":2,"values":{}}'));
  assert.throws(() => core.importSettings('{"version":1,"values":{},"extra":1}'));
  assert.throws(() => core.importSettings('x'.repeat(16385)));
});
test('single-key export and reset preserve independent browser values', () => {
  const storage = memory();
  core.writeSettings(storage,{model:'local:fixture',useRag:false,searchMode:'AUTO'});
  assert.equal(storage.writes,1);
  assert.deepEqual(core.withoutKeys(core.readSettings(storage),['model']),{useRag:false,searchMode:'AUTO'});
});
test('response preferences validate Unicode limits, sparse clearing and existing memory scope', () => {
  const values={customInstructions:'🙂'.repeat(2000),responseTone:'neutral',responseLength:'brief',responseLanguage:'auto',memoryMode:'hybrid'};
  assert.deepEqual(core.importPreferences(JSON.stringify({version:2,values})),values);
  assert.deepEqual(core.validatePreferences({customInstructions:''}),{customInstructions:''});
  for(const invalid of [{customInstructions:'🙂'.repeat(2001)},{responseTone:'system'},{responseLength:'max'},
    {responseLanguage:'xx'},{memoryMode:'global'},{reasoningEffort:'high'}]) assert.throws(()=>core.validatePreferences(invalid));
});

const factoryValues={model:'fixture:a',modelSelectionMode:'preferred',executionMode:'AUTO',searchMode:'OFF',useRag:true,
  useWebSearch:false,googleSearchRescueEnabled:false,temperature:0.2,topP:1,frequencyPenalty:0,presencePenalty:0,maxTokens:2048,ragAnswerPolicy:'adaptive'};
function windowFixture(active = false, overrides = {}) {
  const listeners=new Map(),docListeners=new Map(),controls={},requests=[];
  for(const [id,value]of [['modelSelect','fixture:a'],['modelSelectionMode','preferred'],['searchModeSelect','OFF'],['useRagToggle',false],
    ['executionModeSelect','AUTO'],['googleSearchRescueToggle',false],['chatTraceToggle',true],
    ['chat-save-defaults',''],['chat-defaults-status',''],['sendBtn','']]) {
    controls[id]={dataset:{},value,checked:value,disabled:false,
      options:[{value:'fixture:a'},{value:'fixture:b'},{value:'preferred'},{value:'strict'},{value:'auto'},{value:'OFF'},{value:'AUTO'},{value:'STRIKE'},{value:'SELF_ASK'}],
      addEventListener(name,fn){listeners.set(id+name,fn);},
      dispatchEvent(e){listeners.get(id+e.type)?.(e);}};
  }
  const storage=memory();
  let state={overrides,effective:{...factoryValues,...overrides},factoryDefaults:factoryValues,sources:{},
    revision:0,hash:null,defaultsVersion:'1',ownerScopeId:'a'.repeat(64)};
  const win={localStorage:storage,sessionStorage:{getItem:()=>active?'existing-session':null},
    location:{search:''},document:{getElementById:id=>controls[id],
      addEventListener:(n,f)=>docListeners.set(n,f),dispatchEvent(){}},
    Event:class{constructor(type){this.type=type}},CustomEvent:class{},setTimeout:()=>1,clearTimeout(){},addEventListener(){},
    fetch:async(url,options)=>{
      requests.push({url,...options});
      if(options.method==='PATCH'){
        const body=JSON.parse(options.body);
        assert.equal(body.expectedRevision,state.revision);assert.equal(body.expectedHash,state.hash);
        const values={...state.overrides,...body.set};body.unset.forEach(key=>delete values[key]);
        state={...state,overrides:values,effective:{...factoryValues,...values},revision:state.revision+1,hash:'a'.repeat(64)};
      }
      return {ok:true,status:200,redirected:false,json:async()=>state};
    }};
  return {win,storage,controls,requests,emit:(name,detail)=>docListeners.get(name)?.({detail})};
}
const tick=()=>new Promise(resolve=>setImmediate(resolve));

test('display preference ON/OFF/unset restores for existing sessions without generation or memory settings',async()=>{
  for (const value of [true,false,undefined]) {
    const f=windowFixture(true,value===undefined?{}:{chatTraceEnabled:value});
    core.installBridge(f.win);await tick();
    assert.equal(f.controls.chatTraceToggle.checked,value!==false);
    assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);
    assert.equal(core.KEYS.includes('chatTraceEnabled'),false);
    assert.equal(core.PREFERENCE_KEYS.includes('chatTraceEnabled'),true);
  }
});

test('explicit trace toggle persists only display intent and reload restores OFF',async()=>{
  const f=windowFixture(true,{});
  core.installBridge(f.win);await tick();
  f.controls.chatTraceToggle.checked=false;
  await f.controls.chatTraceToggle.dispatchEvent(new f.win.Event('change'));
  await tick();await tick();await tick();
  const patches=f.requests.filter(r=>r.method==='PATCH');
  assert.equal(patches.length,1);
  assert.deepEqual(JSON.parse(patches[0].body).set,{chatTraceEnabled:false});
  assert.equal(JSON.parse(patches[0].body).expectedOwnerScopeId,'a'.repeat(64));
  const reloaded=windowFixture(true,{chatTraceEnabled:false});
  core.installBridge(reloaded.win);await tick();
  assert.equal(reloaded.controls.chatTraceToggle.checked,false);
});

test('new conversation and changed owner reset trace from that owner only',async()=>{
  const f=windowFixture(true,{chatTraceEnabled:false});
  core.installBridge(f.win);await tick();
  assert.equal(f.controls.chatTraceToggle.checked,false);
  f.emit('brain-state:session',{sessionId:null});await tick();
  assert.equal(f.controls.chatTraceToggle.checked,false);
  const read=f.win.fetch;
  f.win.fetch=async(url,options)=>{
    const result=await read(url,options);
    return {...result,json:async()=>({...await result.json(),overrides:{},ownerScopeId:'b'.repeat(64)})};
  };
  f.emit('brain-state:session',{sessionId:'other-session'});await tick();
  assert.equal(f.controls.chatTraceToggle.checked,true);
  assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);
});

test('owner change during display save refuses a PATCH to the new owner',async()=>{
  const f=windowFixture(true,{});
  core.installBridge(f.win);await tick();
  const read=f.win.fetch;
  f.win.fetch=async(url,options)=>{
    const result=await read(url,options);
    return {...result,json:async()=>({...await result.json(),ownerScopeId:'b'.repeat(64)})};
  };
  f.controls.chatTraceToggle.checked=false;
  f.controls.chatTraceToggle.dispatchEvent(new f.win.Event('change'));
  await tick();await tick();
  assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);
  assert.equal(f.controls.chatTraceToggle.checked,true);
});
test('composer snapshots preserve sparse Gemini intent through unrelated model changes and reload',()=>{
  const fs=require('node:fs'),vm=require('node:vm');
  const source=fs.readFileSync(require.resolve('../../../main/resources/static/js/chat.js'),'utf8');
  const store=memory(),rescue={checked:false,defaultChecked:false,dataset:{}};
  const ctx=vm.createContext({dom:{googleSearchRescue:rescue,modelSelect:{value:'fixture:a'},useRag:{checked:true}},
    window:{sessionStorage:store},CURRENT_SESSION_STORAGE_KEY:'chat.currentSessionId',CONTROL_SETTINGS_STORAGE_KEY:'chat.controlSettings'});
  vm.runInContext(source.slice(source.indexOf('function currentControlSettings('),source.indexOf('function restoreStoredControlSettings(')),ctx);
  assert.equal(Object.hasOwn(ctx.currentControlSettings(),'googleSearchRescueEnabled'),false);
  ctx.dom.modelSelect.value='fixture:b';ctx.persistControlSettings('user');
  assert.equal(Object.hasOwn(JSON.parse(store.getItem('chat.controlSettings')),'googleSearchRescueEnabled'),false);
  for(const enabled of [true,false]) {
    rescue.checked=enabled;rescue.dataset.rescueExplicit='true';rescue.dataset.rescueOwnerScopeId='a'.repeat(64);
    ctx.persistControlSettings('user');
    const draft=ctx.storedControlSettings();
    assert.equal(draft.googleSearchRescueEnabled,enabled);
    assert.equal(draft.googleSearchRescueSessionId,null);
    ctx.dom.modelSelect.value='fixture:a';
    assert.equal(ctx.currentControlSettings().googleSearchRescueEnabled,enabled);
  }
});
test('Gemini bridge hydrates Boolean defaults and saves ON/OFF only on explicit Save',async()=>{
  for(const enabled of [true,false]) {
    const f=windowFixture(false,{googleSearchRescueEnabled:enabled});
    core.installBridge(f.win);f.emit('chat:model-catalog',{ready:true,hydrated:true});await tick();
    assert.equal(f.controls.googleSearchRescueToggle.checked,enabled);
    f.controls.googleSearchRescueToggle.checked=!enabled;
    f.controls.googleSearchRescueToggle.dispatchEvent(new f.win.Event('change'));
    assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);
    f.controls['chat-save-defaults'].dispatchEvent(new f.win.Event('click'));await tick();
    assert.equal(JSON.parse(f.requests.find(r=>r.method==='PATCH').body).set.googleSearchRescueEnabled,!enabled);
    assert.equal((await core.readPreferences(f.win)).overrides.googleSearchRescueEnabled,!enabled);
  }
});
test('Gemini owner-bound draft survives reload and late defaults, but shared A/B cache does not restore it',async()=>{
  const shared=memory();
  shared.setItem('chat.controlSettings',JSON.stringify({source:'user',googleSearchRescueEnabled:false,
    googleSearchRescueOwnerScopeId:'a'.repeat(64),googleSearchRescueSessionId:null}));
  for(const ownerScopeId of ['a'.repeat(64),'b'.repeat(64)]) {
    const f=windowFixture(false,{googleSearchRescueEnabled:true}),fetch=f.win.fetch;
    f.win.sessionStorage=shared;
    f.win.fetch=async(url,options)=>{const r=await fetch(url,options);return {...r,json:async()=>({...await r.json(),ownerScopeId})}};
    core.installBridge(f.win);f.emit('chat:model-catalog',{ready:true,hydrated:true});await tick();
    assert.equal(f.controls.googleSearchRescueToggle.checked,ownerScopeId==='b'.repeat(64));
    assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);
  }
});
test('Gemini legacy unscoped draft inherits; user OFF while GET is pending wins',async()=>{
  const f=windowFixture(false,{googleSearchRescueEnabled:true}),fetch=f.win.fetch;
  f.win.sessionStorage.getItem=key=>key==='chat.controlSettings'?JSON.stringify({source:'user',googleSearchRescueEnabled:false}):null;
  core.installBridge(f.win);f.emit('chat:model-catalog',{ready:true,hydrated:true});await tick();
  assert.equal(f.controls.googleSearchRescueToggle.checked,true);
  const g=windowFixture(false,{googleSearchRescueEnabled:true}),get=g.win.fetch;let release;
  g.win.fetch=(url,options)=>new Promise(resolve=>release=()=>get(url,options).then(resolve));
  core.installBridge(g.win);
  g.controls.googleSearchRescueToggle.checked=false;g.controls.googleSearchRescueToggle.dispatchEvent(new g.win.Event('change'));
  g.emit('chat:model-catalog',{ready:true,hydrated:true});release();await tick();
  assert.equal(g.controls.googleSearchRescueToggle.checked,false);
});
test('new-session event rejects late Gemini GET and re-inherits defaults',async()=>{
  const f=windowFixture(false,{googleSearchRescueEnabled:true}),get=f.win.fetch,storage=memory(),releases=[];
  f.win.sessionStorage=storage;
  f.win.fetch=(url,options)=>new Promise(resolve=>releases.push(()=>get(url,options).then(resolve)));
  core.installBridge(f.win);f.emit('chat:model-catalog',{ready:true,hydrated:true});
  f.controls.googleSearchRescueToggle.checked=false;
  f.controls.googleSearchRescueToggle.dispatchEvent(new f.win.Event('change'));
  f.emit('brain-state:session',{}); // a fresh conversation supersedes the first GET/selection
  releases[0]();await tick();
  assert.equal(f.controls.googleSearchRescueToggle.checked,false);
  releases[1]();await tick();
  assert.equal(f.controls.googleSearchRescueToggle.checked,true);
  assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);
});

test('new manual OFF survives late owner mismatch or GET failure without trusting an old owner draft',async()=>{
  for (const failed of [false,true]) {
    const f=windowFixture(true,{googleSearchRescueEnabled:true}),get=f.win.fetch;
    const rescue=f.controls.googleSearchRescueToggle;
    rescue.checked=true;rescue.dataset.rescueExplicit='true';rescue.dataset.rescueOwnerScopeId='a'.repeat(64);
    let release;
    f.win.fetch=(url,options)=>new Promise(resolve=>release=async()=>{
      if(failed) return resolve({ok:false,status:503});
      const value=await get(url,options);
      resolve({...value,json:async()=>({...await value.json(),ownerScopeId:'b'.repeat(64)})});
    });
    core.installBridge(f.win);
    rescue.checked=false;rescue.dataset.rescueExplicit='true';
    rescue.dispatchEvent(new f.win.Event('change'));
    await release();await tick();
    assert.equal(rescue.checked,false);
    assert.equal(rescue.dataset.rescueExplicit,'true');
    assert.equal(rescue.dataset.rescueOwnerScopeId,failed?undefined:'b'.repeat(64));
    assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);
  }
});
test('sampling metadata is read-only, bound and allowlisted without changing saved preferences',async()=>{
  const f=windowFixture();
  const value={overrides:{temperature:0},effective:factoryValues,factoryDefaults:factoryValues,sources:{},
    revision:1,hash:'a'.repeat(64),defaultsVersion:'1',
    sampling:{temperature:{model:'fixture:a',modelSelectionMode:'strict',support:'NO',reasonCode:'adapter_omits_sampling',privatePrompt:'private'}}};
  f.win.fetch=async()=>({ok:true,status:200,redirected:false,json:async()=>value});
  const snapshot=await core.readPreferences(f.win);
  assert.deepEqual(snapshot.sampling.temperature,{model:'fixture:a',modelSelectionMode:'strict',support:'NO',reasonCode:'adapter_omits_sampling'});
  assert.equal(snapshot.overrides.temperature,0);
  assert.equal(JSON.stringify(snapshot).includes('private'),false);
  value.sampling.temperature.reasonCode='private text';
  assert.equal((await core.readPreferences(f.win)).sampling,undefined);
  assert.throws(()=>core.validatePreferences({sampling:value.sampling}));
});
test('execution control hydrates STRIKE and saves an explicit AUTO only after Save',async()=>{
  const f=windowFixture(false,{executionMode:'STRIKE'});
  core.installBridge(f.win);f.emit('chat:model-catalog',{ready:true,hydrated:true});await tick();
  assert.equal(f.controls.executionModeSelect.value,'STRIKE');
  f.controls.executionModeSelect.value='AUTO';f.controls.executionModeSelect.dispatchEvent(new f.win.Event('change'));
  assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);
  f.controls['chat-save-defaults'].dispatchEvent(new f.win.Event('click'));await tick();
  const patch=JSON.parse(f.requests.find(r=>r.method==='PATCH').body);
  assert.equal(patch.set.executionMode,'AUTO');
  assert.equal((await core.readPreferences(f.win)).overrides.executionMode,'AUTO');
});
test('explicit AUTO draft survives late server SELF_ASK defaults',async()=>{
  const f=windowFixture(false,{executionMode:'SELF_ASK'});
  f.win.sessionStorage.getItem=key=>key==='chat.controlSettings'
    ? JSON.stringify({source:'composer',executionMode:'AUTO'}) : null;
  core.installBridge(f.win);f.emit('chat:model-catalog',{ready:true,hydrated:true});await tick();
  assert.equal(f.controls.executionModeSelect.value,'AUTO');
  assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);
});
test('execution strategy is validated as a sparse server preference without changing existing controls',async()=>{
  for(const mode of ['AUTO','STRIKE','SELF_ASK']) {
    const values={executionMode:mode};
    assert.deepEqual(core.validatePreferences(values),values);
    assert.deepEqual(core.importPreferences(JSON.stringify({version:2,values})),values);
  }
  for(const mode of ['BYPASS','auto',0,null])assert.throws(()=>core.validatePreferences({executionMode:mode}));
  const f=windowFixture(false,{executionMode:'SELF_ASK'});
  core.installBridge(f.win);f.emit('chat:model-catalog',{ready:true,hydrated:true});await tick();
  assert.equal(f.controls.modelSelect.dataset.awxSettingsReady,'ready');
  f.controls['chat-save-defaults'].dispatchEvent(new f.win.Event('click'));await tick();
  const state=await core.readPreferences(f.win);
  assert.equal(state.overrides.executionMode,'SELF_ASK');
});
test('bridge applies server control defaults without saving initialization',async()=>{
  const f=windowFixture(false,{model:'fixture:b',modelSelectionMode:'strict',searchMode:'AUTO',useRag:false});
  core.installBridge(f.win);await tick();
  assert.equal(f.controls.modelSelect.value,'fixture:a'); // response alone is not catalog readiness
  f.emit('chat:model-catalog',{ready:true,hydrated:true});
  assert.equal(f.controls.modelSelect.value,'fixture:b');assert.equal(f.controls.modelSelectionMode.value,'strict');
  assert.equal(f.controls.searchModeSelect.value,'AUTO');assert.equal(f.controls.useRagToggle.checked,false);
  assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);assert.equal(f.storage.getItem(core.STORAGE_KEY),null);
});
test('bridge preserves restored conversations and unavailable model selections',async()=>{
  const f=windowFixture(true,{model:'fixture:b'});core.installBridge(f.win);await tick();
  assert.equal(f.controls.modelSelect.value,'fixture:a');assert.equal(f.requests.length,1);
  assert.equal(f.requests[0].method,undefined, 'restored controls only read the draft owner descriptor');
  const g=windowFixture(false,{model:'fixture:missing'});core.installBridge(g.win);
  g.emit('chat:model-catalog',{ready:true,hydrated:true});await tick();
  assert.equal(g.controls.modelSelect.value,'fixture:a');assert.equal(g.controls.modelSelect.dataset.awxSettingsReady,'loading');
});
test('existing session preference GET failure preserves sending and prevents unverified Save',async()=>{
  const f=windowFixture(true);
  f.win.fetch=async()=>({ok:false,status:503});
  core.installBridge(f.win);await tick();
  assert.equal(f.controls.modelSelect.dataset.awxSettingsReady,'ready');
  assert.equal(f.controls['chat-save-defaults'].disabled,true);
  assert.equal(f.controls.googleSearchRescueToggle.dataset.rescueOwnerScopeId,undefined);
});
test('ordinary control changes never save; explicit Save patches once and verifies with GET',async()=>{
  const f=windowFixture();core.installBridge(f.win);f.emit('chat:model-catalog',{ready:true,hydrated:true});await tick();
  f.controls.searchModeSelect.value='AUTO';f.controls.searchModeSelect.dispatchEvent(new f.win.Event('change'));
  assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);assert.equal(f.storage.getItem(core.STORAGE_KEY),null);
  f.controls['chat-save-defaults'].dispatchEvent(new f.win.Event('click'));await tick();
  assert.equal(f.requests.filter(r=>r.method==='PATCH').length,1);
  const patch=JSON.parse(f.requests.find(r=>r.method==='PATCH').body);
  assert.equal(patch.set.searchMode,'AUTO');assert.deepEqual(Object.keys(patch).sort(),['expectedHash','expectedRevision','set','unset']);
  assert.equal(f.requests.filter(r=>!r.method).length,2);assert.equal(f.storage.getItem(core.STORAGE_KEY),null);
});
test('legacy values survive failed ACK/readback and delete only after explicit verified migration',async()=>{
  const f=windowFixture();core.writeSettings(f.storage,{useRag:false});
  const baseline=await core.readPreferences(f.win);const fetch=f.win.fetch;
  let reads=0;f.win.fetch=async(url,options)=>options.method?fetch(url,options):(++reads,{ok:false,status:503});
  await assert.rejects(core.savePreferences(f.win,baseline,{useRag:false},[],true));
  assert.deepEqual(core.readSettings(f.storage),{useRag:false});
  f.win.fetch=fetch;const fresh=await core.readPreferences(f.win);
  await core.savePreferences(f.win,fresh,{useRag:false},[],true);
  assert.equal(f.storage.getItem(core.STORAGE_KEY),null);
});
test('new preference validation keeps zero/false, rejects unknowns and rejects fractional maxTokens',()=>{
  assert.deepEqual(core.validatePreferences({temperature:0,useRag:false,topP:0}),{temperature:0,useRag:false,topP:0});
  for(const values of [{owner:'other'},{topP:NaN},{topP:1.01},{maxTokens:1.5},{useWebSearch:'false'}])assert.throws(()=>core.validatePreferences(values));
  assert.deepEqual(core.importPreferences('{"version":2,"values":{"temperature":0,"useRag":false}}'),{temperature:0,useRag:false});
});
test('bridge has no fetch interception or chat global access', () => {
  const text = require('node:fs').readFileSync(require.resolve('../../../main/resources/static/js/chat-settings-bridge.js'),'utf8');
  assert.doesNotMatch(text,/window\.fetch\s*=|root\.fetch\s*=|ChatApp|chat\.js\s*\[/);
});
test('existing conversation can explicitly save its controls without applying new defaults',async()=>{
 const f=windowFixture(true,{model:'fixture:b',searchMode:'AUTO'});core.installBridge(f.win);await tick();
 assert.equal(f.controls.modelSelect.value,'fixture:a');assert.equal(f.requests.length,1);
 assert.equal(f.requests[0].method,undefined, 'owner validation must never write saved defaults');
 assert.equal(f.controls['chat-save-defaults'].disabled,false);
 f.controls['chat-save-defaults'].dispatchEvent(new f.win.Event('click'));await tick();
 assert.equal(f.requests.filter(r=>r.method==='PATCH').length,1);
 const patch=JSON.parse(f.requests.find(r=>r.method==='PATCH').body);
 assert.equal(patch.set.model,'fixture:a');assert.equal(patch.set.searchMode,'OFF');
 assert.equal(f.controls.modelSelect.value,'fixture:a');assert.equal(f.requests.filter(r=>!r.method).length,2);
 assert.match(f.controls['chat-defaults-status'].textContent,/저장됨/);
});
test('user mode selected while server read is pending survives the late defaults response',async()=>{
 const f=windowFixture(false,{modelSelectionMode:'preferred',searchMode:'AUTO'}),fetch=f.win.fetch;
 let release;
 f.win.fetch=(url,options)=>new Promise(resolve=>release=()=>fetch(url,options).then(resolve));
 core.installBridge(f.win);
 f.controls.modelSelectionMode.value='strict';f.controls.modelSelectionMode.dispatchEvent(new f.win.Event('change'));
 f.emit('chat:model-catalog',{ready:true,hydrated:true});release();await tick();
 assert.equal(f.controls.modelSelectionMode.value,'strict');
 assert.equal(f.controls.searchModeSelect.value,'AUTO');assert.equal(f.controls.modelSelect.dataset.awxSettingsReady,'ready');
 assert.equal(f.requests.filter(r=>r.method==='PATCH').length,0);
});
