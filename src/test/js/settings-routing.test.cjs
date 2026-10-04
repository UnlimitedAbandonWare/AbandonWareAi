const {test}=require('node:test');const assert=require('node:assert/strict');const core=require('../../../main/resources/static/js/settings-routing.js');
const reply=(status,body)=>({status,ok:status>=200&&status<300,redirected:false,json:async()=>body});
const empty=()=>({schemaVersion:1,enabled:false,bindings:{},additionalPaidAllowed:false,additionalCostCapUsd:0});
const aux=()=>({...empty(),enabled:true,bindings:{SELFASK_BQ:{role:'SELFASK_BQ',selection:'registered-route',target:'llmrouter.fixture',orderedFallbacks:[],maxExtraFallbackCalls:0,auxPaidAllowed:true,auxCostCapUsdPerRun:0.01}}});
test('saved auxiliary opt-in survives preview and save serialization',async()=>{
 const requests=[];const c=core.createClient(async(url,opt)=>{requests.push(JSON.parse(opt.body));return reply(200,{profileRevision:2})},()=>({}));
 await c.preview(aux());await c.save(aux(),{profileRevision:1,profileHash:null});
 for(const r of requests)assert.deepEqual(r.profile.bindings.SELFASK_BQ,aux().bindings.SELFASK_BQ);
});
test('MAIN binding cannot acquire auxiliary paid permission',()=>{
 const p=aux();p.bindings.MAIN_DEFAULT={...p.bindings.SELFASK_BQ,role:'MAIN_DEFAULT'};delete p.bindings.SELFASK_BQ;assert.throws(()=>core.profile(p));
});
test('client prices and unbounded auxiliary caps are rejected',()=>{
 for(const changed of [{price:{input:0}},{capabilityStatus:{schema:'YES'}},{auxCostCapUsdPerRun:0.051},{auxCostCapUsdPerRun:0}]){
  const p=aux();Object.assign(p.bindings.SELFASK_BQ,changed);assert.throws(()=>core.profile(p));
 }
});
test('role contract has seven named roles and malformed explanatory payload is rejected',()=>{assert.deepEqual(core.ROLES,['MAIN_DEFAULT','MAIN_FAST','MAIN_HIGH','SELFASK_BQ','SELFASK_ER','SELFASK_RC','PROMPT_POSE_DRAFT']);assert.throws(()=>core.profile({...empty(),observedValue:'PRIVATE'}));assert.deepEqual(core.profile(empty()),empty());});
test('401/403 produce locked state and never retry writes',async()=>{let calls=0;const c=core.createClient(async()=>{calls++;return reply(403,{private:'PRIVATE'})},()=>({}));assert.equal((await c.read()).kind,'locked');assert.equal(calls,1);assert.equal(c.state().data,null);});
test('409 preserves draft and requires explicit reload',async()=>{let calls=0;const c=core.createClient(async()=>{calls++;return reply(409,{reasonCode:'revision_conflict'})},()=>({}));let p=empty();p.enabled=true;let r=await c.save(p,{profileRevision:3,profileHash:'a'.repeat(64)});assert.equal(r.kind,'conflict');assert.equal(calls,1);assert.equal(c.state().draft.enabled,true);});
test('late response cannot replace newer read',async()=>{let release;let n=0;const c=core.createClient(()=>++n===1?new Promise(r=>release=r):Promise.resolve(reply(200,{profileRevision:2})),()=>({}));let a=c.read();await c.read();release(reply(200,{profileRevision:1}));assert.equal((await a).kind,'stale');assert.equal(c.state().data.profileRevision,2);});
test('only three POSTs carry CSRF and optimistic revision',async()=>{let requests=[];const c=core.createClient(async(url,opt)=>{requests.push({url,opt});return reply(200,{profileRevision:4})},()=>({'X-CSRF-TOKEN':'synthetic'}));await c.preview(empty());await c.save(empty(),{profileRevision:3,profileHash:null});assert.equal(requests[0].url,'/api/settings/routing/preview');let o=requests[1].opt;assert.equal(o.method,'POST');assert.equal(o.credentials,'same-origin');assert.equal(o.headers['X-CSRF-TOKEN'],'synthetic');assert.equal(JSON.parse(o.body).expectedRevision,3);assert.equal(JSON.parse(o.body).expectedProfileHash,null);});
test('missing observation stays unknown and false is not converted to unknown',()=>{assert.equal(core.value(null),'미관측');assert.equal(core.value(false),'OFF');assert.equal(core.value(0),'0');});
test('same-run identity is read-only and foreign fields are not copied',()=>{let writes=0;let storage={getItem:()=>JSON.stringify({sessionId:71,runToken:'synthetic-run',lastAssistant:'PRIVATE'}),setItem:()=>writes++};assert.deepEqual(core.activeIdentity(storage),{sessionId:71,runToken:'synthetic-run'});assert.equal(writes,0);assert.deepEqual(core.activeIdentity({getItem:()=>'{'}),{});});
test('preview and current profile cannot replace a pinned Run application',()=>{
 const role='MAIN_DEFAULT';const data={runtimeEnabled:true,profile:{bindings:{[role]:{target:'llmrouter.new'}}},outcome:{roles:[{role,configuredTarget:'llmrouter.old',effectiveTarget:'llmrouter.actual',responseModelId:'actual-model',attemptCount:1}]}};
 const preview={effectiveBindings:{[role]:{primary:{target:'llmrouter.preview'}}}};
 const values=new Map(core.roleValues(data,preview,role,{enabled:true}));assert.equal(values.get('현재 서버 배정'),'llmrouter.new');assert.equal(values.get('Run 설정 배정'),'llmrouter.old');assert.equal(values.get('Run 실제 적용'),'llmrouter.actual');assert.equal(values.get('미리보기 예상'),'llmrouter.preview');
 assert.equal(new Map(core.roleValues(data,preview,role,{enabled:false})).get('미리보기 예상'),null);
 assert.equal(new Map(core.roleValues({runtimeEnabled:true},preview,role,{enabled:true})).get('Run 실제 적용'),undefined);
});
