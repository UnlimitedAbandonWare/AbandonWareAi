const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../../main/resources/static/js/settings-page.js'),'utf8');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
test('initial settings render remains checking while capability is unobserved',()=>{
  const f=fixture();
  assert.match(f.controls.get('local-status').textContent,/서버 개인 설정 확인 중/);
  assert.equal(f.field('temperature').disabled,true);
});

test('execution mode draft names the changed preference before save',async()=>{
  const f=fixture();await tick();
  f.field('executionMode').value='SELF_ASK';f.field('executionMode').events.change();
  assert.match(f.controls.get('local-status').textContent,/실행 전략 · 선택값: SELF_ASK \/ 마지막 저장값: 기본 설정 따르기/);
  assert.doesNotMatch(f.controls.get('local-status').textContent,/undefined/);
  assert.equal(f.patches.length,0);
});

function fixture(support='NO', reasonCode='adapter_omits_sampling') {
  function element(key) {return {dataset:key?{preference:key}:{},value:'',disabled:false,options:[],events:{},textContent:'',
    addEventListener(n,f){this.events[n]=f;},setAttribute(){},replaceChildren(){},append(){},prepend(o){this.options.unshift(o);}};}
  const fields=['model','modelSelectionMode','temperature','executionMode'].map(element);
  const controls=new Map();
  const document={querySelectorAll:()=>fields,getElementById(id){if(!controls.has(id))controls.set(id,element());return controls.get(id);},createElement:()=>element()};
  let state={overrides:{temperature:0.3},effective:{model:'chatgpt-oauth:gpt-5.5',modelSelectionMode:'strict',temperature:0.3,executionMode:'AUTO'},
    factoryDefaults:{},sources:{},revision:1,hash:'a'.repeat(64),
    sampling:{temperature:{model:'chatgpt-oauth:gpt-5.5',modelSelectionMode:'strict',support,reasonCode}}};
  const patches=[], reads=[];
  const core={PREFERENCE_KEYS:fields.map(f=>f.dataset.preference),CACHE_KEY:'fixture',MAX_BYTES:100,
    readPreferences:async(w,model)=>{reads.push(model);return structuredClone(state);},
    savePreferences:async(w,b,set,unset=[])=>{patches.push({set,unset});state={...state,overrides:{...state.overrides,...set},revision:state.revision+1};unset.forEach(k=>delete state.overrides[k]);return structuredClone(state);},
    importPreferences:JSON.parse};
  const window={AwxSettingsCore:core,addEventListener(){},confirm:()=>true};
  const fetch=async()=>({ok:true,status:200,redirected:false,json:async()=>({})});
  vm.runInNewContext(source,{window,document,fetch,URL,Blob,Map,Number,Object,String,console});
  return {field:key=>fields.find(f=>f.dataset.preference===key),controls,patches,reads,core};
}
test('NO and UNKNOWN temperature support disable input while preserving the stored request',async()=>{
  for(const support of ['NO','UNKNOWN']){
    const f=fixture(support);await tick();
    assert.equal(f.field('temperature').disabled,true);
    assert.equal(f.field('temperature').value,'0.3');
    assert.match(f.controls.get('temperature-status').textContent,/미전송|미확인/);
    assert.equal(f.patches.length,0);
  }
});
test('YES capability enables only the selected route',async()=>{
  const f=fixture('YES','configured_adapter');await tick();
  assert.equal(f.field('temperature').disabled,false);
});
test('imported unsupported temperature remains a draft and is omitted from a mixed save',async()=>{
  const f=fixture();await tick();
  f.field('temperature').value='0';f.field('temperature').events.change();
  f.field('executionMode').value='STRIKE';f.field('executionMode').events.change();
  await f.controls.get('local-save').events.click();
  assert.equal(f.patches.length,1);
  assert.deepEqual(JSON.parse(JSON.stringify(f.patches[0].set)),{executionMode:'STRIKE'});
  assert.equal(f.field('temperature').value,'0');
  assert.match(f.controls.get('local-status').textContent,/아직 저장되지/);
});
test('automatic route selection and stale draft route metadata cannot enable temperature',async()=>{
  const f=fixture('YES','configured_adapter');await tick();
  f.field('modelSelectionMode').value='auto';f.field('modelSelectionMode').events.change();
  assert.equal(f.field('temperature').disabled,true);
  f.field('model').value='fixture:other';f.field('model').events.change();
  await tick();assert.equal(f.field('temperature').disabled,true);
});
test('reset can unset unsupported sampling and Undo keeps it as an unsaved draft',async()=>{
  const f=fixture();await tick();
  await f.controls.get('local-reset').events.click();
  assert.ok(f.patches[0].unset.includes('temperature'));
  await f.controls.get('local-undo').events.click();
  assert.equal(f.patches.length,1);
  assert.equal(f.field('temperature').value,'0.3');
  assert.match(f.controls.get('local-status').textContent,/아직 저장되지/);
});
