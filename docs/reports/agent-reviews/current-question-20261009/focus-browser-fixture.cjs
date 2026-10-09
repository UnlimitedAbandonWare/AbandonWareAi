 'use strict';
const {chromium}=require('C:/Users/nninn/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('node:fs'),path=require('node:path');
const outDir=path.join(__dirname,'focus-browser-fixture');fs.mkdirSync(outDir,{recursive:true});
(async()=>{const browser=await chromium.launch({channel:'msedge',headless:true});const ctx=await browser.newContext(),page=await ctx.newPage();
const out={checkedAtUtc:new Date().toISOString(),fixtureOnly:true,providerGenerationCount:0,userProfileWriteCount:0,restoredAuthState:false,hardwareRenderedObserved:false,consoleErrorCount:0};
try{
 page.on('console',m=>{if(m.type()==='error')out.consoleErrorCount++});
 await page.goto('http://127.0.0.1:18180/chat',{waitUntil:'domcontentloaded'});
 await page.addScriptTag({url:'/assets/display/display-focus-flow.js'});await page.addScriptTag({url:'/assets/display/display-focus.js'});await page.addScriptTag({url:'/assets/display/display-focus-controls.js'});
 const html=fs.readFileSync(path.resolve(__dirname,'../../../../main/resources/static/assets/display/index.html'),'utf8');
 out.controls=await page.evaluate(async html=>{
  const source=new DOMParser().parseFromString(html,'text/html');source.querySelectorAll('script,iframe,img,link,video,audio').forEach(x=>x.remove());document.body.innerHTML=source.body.innerHTML;
  const settings={enabled:false,wakeWord:'노바',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:8000,answerLengthChars:200,quickAnswerEnabled:false,webSearchEnabled:false,recallEnabled:false,rememberFactsEnabled:false,presentation:{},answerSelection:{mode:'FIXED',modelId:'fixture-retired',routing:{executionTarget:'API_ONLY',fallbackAllowed:false,allowedFallbackIds:[]}}};
  const host={NovaFocus:{...window.NovaFocus,receiptSender:()=>async()=>{}},document,navigator,Date,console,setTimeout:window.setTimeout.bind(window),clearTimeout:window.clearTimeout.bind(window),requestAnimationFrame:window.requestAnimationFrame.bind(window),cancelAnimationFrame:window.cancelAnimationFrame.bind(window),addEventListener:window.addEventListener.bind(window),removeEventListener:window.removeEventListener.bind(window),fetch:async()=>({ok:true,json:async()=>[{id:'fixture-retired',provider:'fixture',selectable:false},{id:'chatgpt-oauth:gpt-5.6-luna',provider:'chatgpt-oauth',selectable:true}]})};
  const client={state:{},focusRequest:async()=>({settingsVersion:1,settings})};const c=window.NovaFocusControls.mount({host,document,client});c.update({assistId:'fixture',epoch:1,ready:true,connection:'READY',focusProducer:true});await new Promise(r=>setTimeout(r,100));
  const model=document.getElementById('nf-answer-model');const disabledSavedOption=Array.from(model.options).some(o=>o.value==='fixture-retired'&&o.disabled);
  document.getElementById('nf-preset-luna').click();const validPreset={model:model.value,target:document.getElementById('nf-answer-target').value,search:document.getElementById('nf-web-search').value,fallback:document.getElementById('nf-answer-fallback').checked};c.dispose();
  host.fetch=async()=>({ok:true,json:async()=>[{id:'fixture-retired',provider:'fixture',selectable:false}]});const d=window.NovaFocusControls.mount({host,document,client});d.update({assistId:'fixture2',epoch:1,ready:true,connection:'READY',focusProducer:true});await new Promise(r=>setTimeout(r,100));const before=[model.value,document.getElementById('nf-answer-target').value,document.getElementById('nf-web-search').value];document.getElementById('nf-preset-luna').click();const unavailablePreservesFields=JSON.stringify(before)===JSON.stringify([model.value,document.getElementById('nf-answer-target').value,document.getElementById('nf-web-search').value]);const notice=document.getElementById('nf-preset-status').textContent;d.dispose();
  return {disabledSavedOption,validPreset,unavailablePreservesFields,unavailableNoticeShown:/선택 가능한.*없/.test(notice)};
 },html);
 out.banner=await page.evaluate(async()=>{
 document.body.innerHTML='';document.body.style.cssText='background:#101827;color:white;font:18px sans-serif;padding:24px';
 const panel=document.createElement('section');panel.id='fixture-fold';panel.style.cssText='border:1px solid #6d829b;padding:24px';const title=document.createElement('h2');title.textContent='Fold 표시 · 합성 회귀 검증';const status=document.createElement('div'),answer=document.createElement('div');answer.style.cssText='height:180px;line-height:24px;width:100%';panel.append(title,status,answer);document.body.append(panel);
 const projection=window.NovaFocus.createProjection({target:'fold',host:window,document,panel,status,answer});
 const base={active:true,phase:'ANSWER_READY',serverInstanceId:'fixture-boot',activationId:'fixture-activation',turnId:'fixture-turn',stateVersion:1,answerVersion:1,idleRemainingMs:0,draftText:'',questionText:'합성 질문',answerText:'합성 응답입니다.',answerComplete:true,renderTarget:'fold',presentation:{sequentialTextEnabled:false,autoFadeEnabled:false},isFallback:true,requestedModel:'fixture-primary',effectiveRoute:'fixture-backup',effectiveModel:'gemini-fixture',fallbackReasonCode:'backend_timeout',originalError:'ModelSelectionException'};
 projection.update(base);for(let i=0;i<12&&!answer.textContent.includes('합성 응답');i++)await new Promise(r=>requestAnimationFrame(r));
 const notice=panel.querySelector('[role="status"]');const result={fallbackBannerVisible:!!notice&&!notice.hidden,reasonShown:notice?.textContent.includes('backend_timeout'),modelShown:notice?.textContent.includes('gemini-fixture'),answerPainted:answer.textContent.includes('합성 응답'),warningExcludedFromAnswer:!answer.textContent.includes('backend_timeout')};
 window.fixture={projection,panel,notice,base,result};return result;
 });
 await page.locator('#fixture-fold').screenshot({path:path.join(outDir,'fallback-banner.png')});
 Object.assign(out.banner,await page.evaluate(()=>{
 const {projection,notice,base}=window.fixture;const normal={...base,stateVersion:2,answerVersion:2,turnId:'next-turn',isFallback:false};projection.update(normal);const normalClearsBanner=notice.hidden;projection.update(base);const retiredStateCannotRestoreBanner=notice.hidden;
 const panel=document.createElement('section'),answer=document.createElement('div');panel.append(answer);document.body.append(panel);const lensNotice=document.createElement('div');lensNotice.hidden=true;panel.append(lensNotice);const lens=window.NovaFocus.createProjection({target:'lens',host:window,document,panel,answer,fallbackStatus:lensNotice});lens.update({...base,renderTarget:'lens'});const lensWarningHidden=lensNotice.hidden;lens.dispose();projection.dispose();return {normalClearsBanner,retiredStateCannotRestoreBanner,lensWarningHidden};
 }));
 const c=out.controls,b=out.banner;out.verdict=c.disabledSavedOption&&c.validPreset.model==='chatgpt-oauth:gpt-5.6-luna'&&c.validPreset.target==='API_ONLY'&&c.validPreset.search==='false'&&!c.validPreset.fallback&&c.unavailablePreservesFields&&c.unavailableNoticeShown&&Object.values(b).every(x=>x===true)?'PASS':'FAIL';
}catch(e){out.verdict='NOT_PROVEN';out.errorClass=e.name;out.errorMessage=String(e.message).slice(0,300)}finally{fs.writeFileSync(path.join(outDir,'evidence.json'),JSON.stringify(out,null,2));await ctx.close();await browser.close()}
console.log(JSON.stringify(out));process.exitCode=out.verdict==='PASS'?0:1;
})();
