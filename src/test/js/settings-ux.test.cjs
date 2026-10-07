'use strict';
const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../../..');
const key='awx.settings.v1.preferences';
const cacheKey='awx.settings.v2.preferences';
const preferences=(revision,overrides)=>({
 overrides:{...overrides},effective:{model:'fixture:a',modelSelectionMode:'preferred',executionMode:'AUTO',searchMode:'OFF',useRag:true,googleSearchRescueEnabled:false,...overrides},
 factoryDefaults:{model:'fixture:a',modelSelectionMode:'preferred',executionMode:'AUTO',searchMode:'OFF',useRag:true,googleSearchRescueEnabled:false},sources:{},
 revision,hash:String(revision).repeat(64),defaultsVersion:'1',ownerScopeId:'a'.repeat(64)
});
const empty=()=>({schemaVersion:1,enabled:false,bindings:{},additionalPaidAllowed:false,additionalCostCapUsd:0});
const binding=target=>({role:'MAIN_DEFAULT',selection:'registered-route',target,orderedFallbacks:[],maxExtraFallbackCalls:0});
const server=(revision=1,target='llmrouter.fixture-a')=>({
 profileRevision:revision,profileHash:String(revision).repeat(64),profile:{...empty(),bindings:{MAIN_DEFAULT:binding(target)}},
 runtimeEnabled:false,runtimeSupported:true,
 candidates:['a','b'].map(v=>({id:'llmrouter.fixture-'+v,modelId:'fixture-'+v,provider:'ollama'})),
 settingsView:[],pipelineView:{reasonCode:'not_observed'},outcome:null
});
let browser;
before(async()=>{browser=await chromium.launch({headless:true,channel:process.env.AWX_BROWSER_CHANNEL||'msedge'});});
after(async()=>{await browser?.close();});
async function fixture(t,{read=()=>server(),save=()=>({status:409,body:{reasonCode:'revision_conflict'}}),stored={searchMode:'AUTO'},locked=false,personalSave,models=[{id:'fixture:a',modelId:'fixture:a',provider:'ollama',status:'installed',selectable:true}]}={}){
 let personal=preferences(1,stored);
 const context=await browser.newContext({viewport:{width:390,height:844}});
 t.after(()=>context.close());
 await context.addInitScript(({key,stored})=>{
  localStorage.setItem(key,JSON.stringify({version:1,values:stored}));
  window.failWrites=false;
  const original=Storage.prototype.setItem;
  Storage.prototype.setItem=function(k,v){if((k===key||k==='awx.settings.v2.preferences')&&window.failWrites)throw new DOMException('synthetic','QuotaExceededError');return original.call(this,k,v);};
 },{key,stored});
 const page=await context.newPage(),requests=[],errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('http://127.0.0.1:18180/**',async route=>{
  const request=route.request(),url=new URL(request.url());requests.push({path:url.pathname,body:request.postDataJSON?.()});
  if(url.pathname==='/settings')return route.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(root,'main/resources/templates/settings.html'),'utf8')});
  if(/^\/(js|css)\//.test(url.pathname))return route.fulfill({contentType:url.pathname.endsWith('.js')?'application/javascript':'text/css',body:fs.readFileSync(path.join(root,'main/resources/static',url.pathname),'utf8')});
  let result;
  if(url.pathname==='/api/settings/preferences'){
   if(request.method()==='PATCH'){
    const patch=request.postDataJSON();
    if(await page.evaluate(()=>window.failPreferences))result={status:503,body:{reasonCode:'backend_unavailable'}};
    else if(patch.expectedRevision!==personal.revision||patch.expectedHash!==personal.hash)result={status:409,body:{reasonCode:'revision_conflict'}};
    else {
     if(personalSave)await personalSave(patch);
     const values={...personal.overrides,...patch.set};patch.unset.forEach(k=>delete values[k]);
     personal=preferences(personal.revision+1,values);result={status:200,body:personal};
    }
   }else result={status:200,body:personal};
  }else if(url.pathname==='/api/chat/models')result={status:200,body:models};
  else if(url.pathname==='/api/settings')result={status:403,body:{reasonCode:'forbidden'}};
  else if(url.pathname.endsWith('/read')){const data=read();result=locked?{status:403,body:{reasonCode:'forbidden'}}:data.status?data:{status:200,body:data};}
  else if(url.pathname.endsWith('/save'))result=await save(request.postDataJSON());
  else if(url.pathname.endsWith('/preview'))result={status:200,body:{effectiveBindings:{},externalCalls:0,writes:0}};
  else result={status:404,body:{reasonCode:'fixture_not_registered'}};
  await route.fulfill({status:result.status,contentType:'application/json',headers:{'X-Request-Id':'synthetic-settings-ux'},body:JSON.stringify(result.body)});
 });
 await page.goto('http://127.0.0.1:18180/settings');
 await page.locator('#routing-root .routing-status').waitFor({state:'attached'});
 await page.waitForFunction(()=>!document.querySelector('#routing-root .routing-status').textContent.includes('확인 중'));
 await page.waitForFunction(()=>document.querySelector('#local-status').textContent.includes('revision'));
 if(await page.locator('#developer-tools').count())await page.locator('#developer-tools').evaluate(el=>el.open=true);
 return {page,requests,errors,context,getPreferences:()=>personal,setPreferences:value=>personal=value};
}
const field=(page,key)=>page.locator('[data-preference="'+key+'"]');
const primary=page=>page.locator('[aria-label="주 답변 · 기본 모델"]');
test('409 -> reread preserves draft, compares latest, then explicitly saves latest revision/hash',async t=>{
 let current=server(),submitted=[];
 const {page}=await fixture(t,{read:()=>current,save:p=>{submitted.push(p);return submitted.length===1?{status:409,body:{reasonCode:'revision_conflict'}}:{status:200,body:{...current,profile:p.profile}};}});
 await primary(page).selectOption('llmrouter.fixture-b');
 await page.getByRole('button',{name:'서버 프로필 저장',exact:true}).click();
 await page.getByText('다른 저장이 먼저 반영되었습니다.',{exact:false}).waitFor();
 current=server(2);
 await page.getByRole('button',{name:'서버 값 다시 읽기',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('.routing-status').textContent.includes('2'));
 assert.equal(await primary(page).inputValue(),'llmrouter.fixture-b');
 await page.locator('#routing-comparison').getByText('llmrouter.fixture-a',{exact:false}).waitFor();
 await page.getByRole('button',{name:'내 변경 사용',exact:true}).click();
 await page.getByRole('button',{name:'서버 프로필 저장',exact:true}).click();
 assert.equal(submitted.length,2);
 assert.equal(submitted[1].expectedRevision,2);assert.equal(submitted[1].expectedProfileHash,'2'.repeat(64));
 assert.equal(submitted[1].profile.bindings.MAIN_DEFAULT.target,'llmrouter.fixture-b');
});
test('reread also preserves edits made before preview/save',async t=>{
 const {page}=await fixture(t);
 await primary(page).selectOption('llmrouter.fixture-b');
 await page.getByRole('button',{name:'서버 값 다시 읽기',exact:true}).click();
 await page.waitForTimeout(50);
 assert.equal(await primary(page).inputValue(),'llmrouter.fixture-b');
});
test('API save failure retains draft and saved value; retry, optional cache failure and revert recover',async t=>{
 const {page,errors,getPreferences}=await fixture(t);
 await page.evaluate(()=>window.failPreferences=true);
 await field(page,'searchMode').selectOption('OFF');
 await page.locator('#local-save').click();
 await page.waitForFunction(()=>document.querySelector('#local-status').textContent.includes('저장 실패'));
 assert.match(await page.locator('#local-status').innerText(),/저장되지|저장 실패/);
 assert.equal(await field(page,'searchMode').inputValue(),'OFF');
 assert.equal(getPreferences().overrides.searchMode,'AUTO');
 // Late catalog refresh/storage event must not replace the unsaved field.
 await page.evaluate(key=>dispatchEvent(new StorageEvent('storage',{key})),cacheKey);
 assert.equal(await field(page,'searchMode').inputValue(),'OFF');
 await page.evaluate(()=>{window.failPreferences=false;window.failWrites=true;});
 await page.getByRole('button',{name:'다시 저장',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('#local-status').textContent.includes('재조회 확인됨'));
 assert.equal(getPreferences().overrides.searchMode,'OFF');
 await field(page,'searchMode').selectOption('AUTO');
 await page.getByRole('button',{name:'저장된 값으로 되돌리기',exact:true}).click();
 assert.equal(await field(page,'searchMode').inputValue(),'OFF');
 assert.deepEqual(errors,[]);
});
test('reset cancellation preserves values; successful reset has guarded undo',async t=>{
 const {page,getPreferences}=await fixture(t);
 page.once('dialog',d=>d.dismiss());
 await page.locator('#local-reset').click();
 await page.waitForTimeout(50);
 assert.equal(await field(page,'searchMode').inputValue(),'AUTO');
 assert.equal(getPreferences().overrides.searchMode,'AUTO');
 page.once('dialog',d=>d.accept());
 await page.locator('#local-reset').click();
 await page.locator('#local-undo').waitFor();
 assert.deepEqual(getPreferences().overrides,{});
 await page.getByRole('button',{name:'복원 실행취소',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('[data-preference="searchMode"]').value==='AUTO');
 assert.equal(await field(page,'searchMode').inputValue(),'AUTO');
});
test('developer disclosure is display only, advanced summary survives collapse, 320px fits',async t=>{
 const {page,requests}=await fixture(t,{stored:{modelSelectionMode:'strict',useRag:false}});
 await page.locator('#developer-tools').evaluate(el=>el.open=false);
 assert.equal(await primary(page).isVisible(),false);
 const saved=await page.evaluate(key=>localStorage.getItem(key),key),calls=requests.length;
 assert.match(await page.locator('#local-advanced-summary').innerText(),/이 AI만 사용/);
 await page.locator('#developer-tools > summary').click();
 await page.locator('#developer-tools > summary').click();
 assert.equal(await page.evaluate(key=>localStorage.getItem(key),key),saved);
 assert.equal(requests.length,calls);
 await page.setViewportSize({width:320,height:844});
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
 assert.equal(requests.some(r=>/sync|stream|generate/.test(r.path)),false);
});
test('403 keeps general defaults usable and hides the unused disabled routing form',async t=>{
 const {page}=await fixture(t,{locked:true});
 assert.equal(await page.locator('#routing-root form').isVisible(),false);
 assert.equal(await field(page,'searchMode').isEnabled(),true);
 assert.match(await page.locator('#routing-root').innerText(),/권한/);
});
test('settings reuses searchable model picker without applying the server chat default',async t=>{
 const {page,requests,getPreferences}=await fixture(t,{stored:{},models:[{id:'fixture:a',modelId:'fixture:a',provider:'ollama',status:'installed',selectable:true,defaultChoice:true}]});
 assert.equal(await page.locator('#modelBrowserTrigger').count(),1);
 await page.locator('#modelBrowserTrigger').click();
 await page.locator('[data-model-search]').fill('fixture');
 await page.getByRole('button',{name:'fixture:a',exact:false}).first().click();
 assert.equal(await field(page,'model').inputValue(),'fixture:a');
 assert.equal(requests.filter(r=>r.path==='/api/settings/preferences'&&r.body).length,0);
 await page.locator('#local-save').click();
 await page.waitForFunction(()=>document.querySelector('#local-status').textContent.includes('재조회 확인됨'));
 assert.equal(getPreferences().overrides.model,'fixture:a');
 assert.equal(requests.filter(r=>r.path==='/api/chat/models').length,1);
});
test('new chat changes affect only conversation until explicit verified owner API save',async t=>{
 const context=await browser.newContext();t.after(()=>context.close());
 const page=await context.newPage();
 const html='<select id="modelSelect"><option value="fixture:a">A</option></select><select id="modelSelectionMode"><option value="preferred">preferred</option></select><select id="executionModeSelect"><option value="AUTO">AUTO</option></select><select id="searchModeSelect"><option value="OFF">OFF</option><option value="AUTO">AUTO</option></select><input id="useRagToggle" type="checkbox"><input id="googleSearchRescueToggle" type="checkbox"><button id="chat-save-defaults">Save</button><p id="chat-defaults-status"></p>';
 let personal=preferences(1,{}),patches=0;
 await page.route('http://127.0.0.1:18180/api/settings/preferences',route=>{
  if(route.request().method()==='PATCH'){patches++;personal=preferences(personal.revision+1,route.request().postDataJSON().set);}
  return route.fulfill({contentType:'application/json',body:JSON.stringify(personal)});
 });
 await page.route('http://127.0.0.1:18180/bridge-fixture',r=>r.fulfill({contentType:'text/html',body:html}));
 await page.goto('http://127.0.0.1:18180/bridge-fixture');
 await page.addScriptTag({content:fs.readFileSync(path.join(root,'main/resources/static/js/chat-settings-bridge.js'),'utf8')});
 await page.evaluate(()=>document.dispatchEvent(new CustomEvent('chat:model-catalog',{detail:{ready:true,hydrated:true}})));
 await page.waitForFunction(()=>document.querySelector('#modelSelect').dataset.awxSettingsReady==='ready');
 await page.locator('#searchModeSelect').selectOption('AUTO');
 await page.locator('#googleSearchRescueToggle').check();
 assert.equal(await page.evaluate(key=>localStorage.getItem(key),key),null);
 assert.equal(patches,0);
 await page.locator('#chat-save-defaults').click();
 await page.waitForFunction(()=>document.querySelector('#chat-defaults-status').textContent.includes('저장됨'));
 assert.equal(personal.overrides.searchMode,'AUTO');assert.equal(patches,1);
 assert.equal(personal.overrides.googleSearchRescueEnabled,true);
 await page.reload();
 await page.addScriptTag({content:fs.readFileSync(path.join(root,'main/resources/static/js/chat-settings-bridge.js'),'utf8')});
 await page.evaluate(()=>document.dispatchEvent(new CustomEvent('chat:model-catalog',{detail:{ready:true,hydrated:true}})));
 await page.waitForFunction(()=>document.querySelector('#modelSelect').dataset.awxSettingsReady==='ready');
 await page.locator('#searchModeSelect').selectOption('OFF');
 assert.equal(await page.locator('#googleSearchRescueToggle').isChecked(),true);
 await page.locator('#googleSearchRescueToggle').uncheck();
 assert.equal(personal.overrides.googleSearchRescueEnabled,true);
 assert.equal(personal.overrides.searchMode,'AUTO');assert.equal(patches,1);
});
test('cross-tab reset undo cannot overwrite newly saved values',async t=>{
 const {page,getPreferences,setPreferences}=await fixture(t);page.once('dialog',d=>d.accept());await page.locator('#local-reset').click();
 await page.locator('#local-undo').waitFor();
 setPreferences(preferences(3,{searchMode:'OFF'}));
 await page.evaluate(key=>dispatchEvent(new StorageEvent('storage',{key})),cacheKey);
 await page.waitForFunction(()=>document.querySelector('#local-status').textContent.includes('revision 3'));
 await page.getByRole('button',{name:'복원 실행취소',exact:true}).click();
 assert.equal(getPreferences().overrides.searchMode,'OFF');
 assert.match(await page.locator('#local-status').innerText(),/실행취소 실패/);
});
test('successful delayed personal save preserves edits made after submission',async t=>{
 let release;
 const {page,getPreferences}=await fixture(t,{personalSave:()=>new Promise(resolve=>release=resolve)});
 await field(page,'searchMode').selectOption('OFF');await page.locator('#local-save').click();
 await page.waitForFunction(()=>document.querySelector('#local-save').disabled);
 await field(page,'searchMode').selectOption('FORCE_LIGHT');
 release();
 await page.waitForFunction(()=>document.querySelector('#local-status').textContent.includes('재조회'));
 assert.equal(getPreferences().overrides.searchMode,'OFF');
 assert.equal(await field(page,'searchMode').inputValue(),'FORCE_LIGHT');
 assert.match(await page.locator('#local-status').innerText(),/저장되지/);
});
test('chat template exposes the implemented explicit save scope and status',()=>{
 const html=fs.readFileSync(path.join(root,'main/resources/templates/chat-ui.html'),'utf8');
 assert.doesNotMatch(html,/id="chat-remember-settings"/);
 assert.equal((html.match(/id="chat-save-defaults"/g)||[]).length,1);
 assert.equal((html.match(/id="chat-defaults-status"/g)||[]).length,1);
});
test('conflict choices cannot replace controls during a delayed save',async t=>{
 let calls=0,release;
 const {page}=await fixture(t,{save:p=>++calls===1?{status:409,body:{reasonCode:'revision_conflict'}}:new Promise(resolve=>release=()=>resolve({status:200,body:{...server(3),profile:p.profile}}))});
 await primary(page).selectOption('llmrouter.fixture-b');
 await page.getByRole('button',{name:'서버 프로필 저장',exact:true}).click();
 await page.getByRole('button',{name:'서버 값 다시 읽기',exact:true}).click();
 await page.getByRole('button',{name:'내 변경 사용',exact:true}).click();
 await page.getByRole('button',{name:'서버 프로필 저장',exact:true}).click();
 await page.waitForFunction(()=>Array.from(document.querySelectorAll('#routing-root button')).find(b=>b.textContent==='서버 프로필 저장').disabled);
 try{
  const useServer=page.getByRole('button',{name:'서버 값 사용',exact:true,includeHidden:true});
  assert.equal(await useServer.isVisible()&&!await useServer.isDisabled(),false);
 }finally{release();}
 await page.waitForFunction(()=>document.querySelector('.routing-status').textContent.includes('3'));
 assert.equal(await primary(page).inputValue(),'llmrouter.fixture-b');
});
test('authorized -> 403 removes previously displayed capability and run diagnostics',async t=>{
 let allowed=true;
 const initial={...server(),settingsView:[{id:'fixture-capability',observedValue:'fixture-observed'}],outcome:{mainResponseModelId:'fixture-private-model'}};
 const {page}=await fixture(t,{read:()=>allowed?initial:{status:403,body:{reasonCode:'forbidden'}}});
 assert.match(await page.locator('#capability-view').innerText(),/fixture-observed/);
 allowed=false;await page.getByRole('button',{name:'서버 값 다시 읽기',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('.routing-status').textContent.includes('권한'));
 assert.doesNotMatch(await page.locator('#capability-view').innerText(),/fixture-observed/);
 assert.doesNotMatch(await page.locator('#pipeline-view').innerText(),/fixture-private-model/);
});

test('acknowledged personal save reset and undo refresh effective value rows',async t=>{
 const {page,getPreferences}=await fixture(t,{stored:{searchMode:'AUTO'}});
 const readRow=()=>page.locator('#personal-values').evaluate(el=>{
  const name=[...el.querySelectorAll('dt')].find(x=>x.textContent==='searchMode');
  return name?.nextElementSibling?.textContent;
 });
 assert.match(await readRow(),/^AUTO · /);
 await field(page,'searchMode').selectOption('OFF');
 await page.locator('#local-save').click();
 await page.waitForFunction(()=>document.querySelector('#local-status').textContent.includes('재조회 확인됨'));
 assert.equal(getPreferences().overrides.searchMode,'OFF');
 assert.match(await readRow(),/^OFF · /);
 await field(page,'searchMode').selectOption('AUTO');
 await page.locator('#local-save').click();
 await page.waitForFunction(()=>document.querySelector('#local-status').textContent.includes('재조회 확인됨'));
 page.once('dialog',d=>d.accept());await page.locator('#local-reset').click();
 await page.locator('#local-undo').waitFor();
 assert.match(await readRow(),/^OFF · /);
 await page.locator('#local-undo').click();
 await page.waitForFunction(()=>document.querySelector('#local-status').textContent.includes('revision 5'));
 assert.match(await readRow(),/^AUTO · /);
});
