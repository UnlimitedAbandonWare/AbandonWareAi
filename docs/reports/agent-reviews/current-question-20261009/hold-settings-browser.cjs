'use strict';
const {chromium}=require('C:/Users/nninn/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const base='http://127.0.0.1:18180',dir=path.join(__dirname,'hold-settings-browser-v5');fs.mkdirSync(dir,{recursive:true});
const digest=s=>crypto.createHash('sha256').update(s).digest('hex');
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true}),context=await browser.newContext(),page=await context.newPage();
 const out={checkedAtUtc:new Date().toISOString(),freshContext:true,restoredAuthState:false,providerGenerationCount:0,userLiveProfileWriteCount:0,requests:[],rows:[],hardwareRenderedObserved:false};
 const channel='test-8fca932e1930448e881c2440d1d311a4',clientId=crypto.randomBytes(16).toString('hex');let binding;
 const headers={'Content-Type':'application/json','X-Display-Client':'1','X-Display-Test-Channel':channel};
 async function post(endpoint,body){
  const response=await page.evaluate(async args=>{const r=await fetch(args.endpoint,{method:'POST',headers:args.headers,body:JSON.stringify(args.body)});return {status:r.status,value:await r.json()};},{endpoint,body,headers});
  out.requests.push({endpoint,status:response.status});if(response.status!==200)throw Error('http_'+response.status);return response.value;
 }
 async function mount(){
  await page.addScriptTag({url:'/assets/display/display-focus-flow.js'});await page.addScriptTag({url:'/assets/display/display-focus.js'});await page.addScriptTag({url:'/assets/display/display-focus-controls.js'});
  const html=await page.evaluate(async()=>await(await fetch('/assets/display/index.html',{cache:'no-store'})).text());
  await page.evaluate(async({html,binding,headers})=>{
   const parsed=new DOMParser().parseFromString(html,'text/html');parsed.querySelectorAll('script,iframe,img,link,video,audio').forEach(e=>e.remove());document.body.innerHTML=parsed.body.innerHTML;
   document.getElementById('phone-controls').hidden=false;
   window.holdCalls=[];window.holdControl=window.NovaFocusControls.mount({host:window,document,client:{focusRequest:async(route,body={})=>{
    if(!['settings','settings/read'].includes(route))throw Error('fixture_route_forbidden');
    const payload={...binding,...body};const r=await fetch('/api/assist/display/focus/'+route,{method:'POST',headers,body:JSON.stringify(payload)});
    const value=await r.json();window.holdCalls.push({route,status:r.status,cas:body.settingsVersion,storedVersion:value.settingsVersion,tailHoldMs:value.settings?.presentation?.tailHoldMs});if(!r.ok)throw Error('http_'+r.status);return value;
   }}});window.holdControl.update({assistId:binding.assistId,epoch:binding.epoch,ready:true,connection:'READY',focusProducer:true});
  },{html,binding,headers});
  await page.waitForFunction(()=>window.holdCalls.some(c=>c.route==='settings/read'&&c.status===200));
  out.fixtureAncestors=await page.locator('#nova-settings').evaluate(e=>{const a=[];for(let p=e;p;p=p.parentElement){const c=getComputedStyle(p);a.push({id:p.id,tag:p.tagName,hidden:p.hidden,display:c.display,visibility:c.visibility});}return a;});
  await page.locator('#advanced-settings > summary').click({timeout:5000});
  await page.locator('#nova-settings > summary').click({timeout:5000});
 }
 try{
  await page.goto(base+'/chat',{waitUntil:'domcontentloaded'});
  let v=await post('/api/assist/display/phone-test',{assistId:null,epoch:0,clientId,activate:true});binding={assistId:v.assistId,epoch:v.epoch,clientId};
  await mount();
  for(const seconds of [3,5]){
   const form=await page.evaluate(()=>{const e=document.getElementById('nf-hold');return {value:e.value,min:e.min,max:e.max,step:e.step,label:document.querySelector('label[for="nf-hold"]').textContent};});
   await page.locator('#nf-hold').fill(String(seconds));
   await page.evaluate(async()=>await document.getElementById('nova-settings-form').onsubmit({preventDefault(){}}));
   const save=await page.evaluate(()=>window.holdCalls.filter(c=>c.route==='settings').at(-1));
   const read=await post('/api/assist/display/focus/settings/read',binding);
   const critical=digest(JSON.stringify({answerSelection:read.settings.answerSelection,webSearchEnabled:read.settings.webSearchEnabled,reasoningPreset:read.settings.reasoningPreset,answerLengthChars:read.settings.answerLengthChars,snapshot:read.settings.snapshot}));
   const row={seconds,form,save,readTailHoldMs:read.settings.presentation.tailHoldMs,readVersion:read.settingsVersion,otherSettingsHash:critical};
   await page.evaluate(()=>window.holdControl.dispose());await page.reload({waitUntil:'domcontentloaded'});
   v=await post('/api/assist/display/phone-test',{...binding,activate:true});binding={assistId:v.assistId,epoch:v.epoch,clientId};await mount();
   const reconnected=await post('/api/assist/display/focus/settings/read',binding);row.reconnectedTailHoldMs=reconnected.settings.presentation.tailHoldMs;row.reconnectedVersion=reconnected.settingsVersion;
   row.hydratedSeconds=await page.locator('#nf-hold').inputValue();
   row.verdict=save?.status===200&&save.tailHoldMs===seconds*1000&&save.storedVersion===save.cas+1&&read.settingsVersion===save.storedVersion&&row.reconnectedVersion===read.settingsVersion&&row.reconnectedTailHoldMs===seconds*1000&&row.hydratedSeconds===String(seconds)&&form.min==='2'&&form.max==='15'&&form.step==='1'&&form.label==='답변 유지시간(초)'?'PASS':'FAIL';
   out.rows.push(row);
  }
  out.otherSettingsPreserved=out.rows[0].otherSettingsHash===out.rows[1].otherSettingsHash;
  await page.locator('#nf-hold').screenshot({path:path.join(dir,'hold-five-seconds.png')});
  out.verdict=out.rows.every(r=>r.verdict==='PASS')&&out.otherSettingsPreserved?'PASS':'FAIL';
 }catch(e){out.verdict='NOT_PROVEN';out.errorClass=e.name;out.errorCode=String(e.message).slice(0,80);}
 finally{
  if(binding)try{await post('/api/assist/display/relay/settings',{...binding,enabled:false,segmentSeconds:0});out.testProducerDisabled=true;}catch(e){out.cleanupError=e.name;}
  await context.close();await browser.close();fs.writeFileSync(path.join(dir,'evidence.json'),JSON.stringify(out,null,2));
 }
 console.log(JSON.stringify(out));process.exitCode=out.verdict==='PASS'?0:1;
})();
