 'use strict';
const {chromium}=require('C:/Users/nninn/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const hash=x=>crypto.createHash('sha256').update(x||'').digest('hex').slice(0,12);
const base='http://127.0.0.1:18180',outDir=path.join(__dirname,'focus-final-browser');
fs.mkdirSync(outDir,{recursive:true});
(async()=>{
const browser=await chromium.launch({channel:'msedge',headless:true});
const out={checkedAtUtc:new Date().toISOString(),freshContexts:true,restoredAuthState:false,hardwareRenderedObserved:false,scenarios:[]};
try{for(const fallback of [false]){
 const ctx=await browser.newContext(),page=await ctx.newPage();let binding,opened=false;
 const row={scenario:fallback?'explicit-isolated-fallback':'fixed-oauth-luna',requests:[],consoleErrorCount:0,requestFailureCount:0};
 const channel='test-8fca932e1930448e881c2440d1d311a4',client=crypto.randomBytes(16).toString('hex');
 async function post(endpoint,body){
  const {status,j}=await page.evaluate(async({endpoint,body,headers})=>{const r=await fetch(endpoint,{method:'POST',headers,body:JSON.stringify(body)});return {status:r.status,j:await r.json()};},{endpoint,body,headers:{'Content-Type':'application/json',Origin:base,'X-Display-Client':'1','X-Display-Test-Channel':channel}});
  row.requests.push({endpoint,status});if(status>=400){row.failureReason=/^[a-z][a-z0-9_]{0,63}$/.test(j.reason||'')?j.reason:'not_provided';throw Error('http_'+status);}return j;
 }
 try{
 page.on('console',m=>{if(m.type()==='error')row.consoleErrorCount++});page.on('requestfailed',()=>row.requestFailureCount++);
 await page.goto(base+'/chat',{waitUntil:'domcontentloaded'});
 const v=await post('/api/assist/display/phone-test',{assistId:null,epoch:0,clientId:client,activate:true});binding={assistId:v.assistId,epoch:v.epoch,clientId:client};
 const store=await post('/api/assist/display/focus/settings/read',binding);
 const settings={...store.settings,...JSON.parse(fs.readFileSync(path.join(__dirname,'focus-oauth-luna-settings.json'),'utf8')).settings,enabled:true};
 if(fallback)settings.answerSelection={mode:'FIXED',modelId:'llmrouter.synthetic-unavailable',routing:{executionTarget:'API_ONLY',fallbackAllowed:true,allowedFallbackIds:['llmrouter.gemini-cue']}};
 await post('/api/assist/display/focus/settings',{...binding,settingsVersion:store.settingsVersion,settings});
 const read=await post('/api/assist/display/focus/settings/read',binding);row.settingsVersion=read.settingsVersion;row.readSelection=read.settings.answerSelection;
 await post('/api/assist/display/focus/open',{...binding,renderTarget:'fold'});opened=true;
 const started=Date.now();let focus=await post('/api/assist/display/focus/input',{...binding,requestId:crypto.randomUUID(),text:'Explain why leaves appear green in one sentence.'});
 for(let i=0;i<35;i++){
  if(['ANSWER_READY','PRESENTING'].includes(focus.phase)||focus.active===false)break;
  await new Promise(r=>setTimeout(r,1000));focus=(await post('/api/assist/display/poll',binding)).focus||{};
 }
 Object.assign(row,{elapsedMs:Date.now()-started,phase:focus.phase,reason:focus.reason,answerChars:(focus.answerText||'').length,answerHash:hash(focus.answerText),epoch:binding.epoch,sessionHash:'hash:'+hash(binding.assistId),requestHash:'hash:'+hash(focus.turnId),isFallback:focus.isFallback,requestedModel:focus.requestedModel,effectiveRoute:focus.effectiveRoute,effectiveModel:focus.effectiveModel,fallbackReasonCode:focus.fallbackReasonCode,originalError:focus.originalError});
 await page.addScriptTag({url:base+'/assets/display/display-focus-flow.js'});await page.addScriptTag({url:base+'/assets/display/display-focus.js'});
 row.render=await page.evaluate(({focus,fallback})=>{
  const panel=document.createElement('section');panel.id='verified-fold-projection';Object.assign(panel.style,{position:'fixed',inset:'80px 20px auto 20px',zIndex:'99999',padding:'20px',background:'#141c27',color:'#fff',border:'1px solid #7d8fa5',font:'18px sans-serif'});
  const title=document.createElement('h2');title.textContent=fallback?'Fold · 실제 허용 폴백 응답':'Fold · OAuth Luna 정상 응답';const status=document.createElement('div'),answer=document.createElement('div');panel.append(title,status,answer);document.body.append(panel);
  const projection=window.NovaFocus.createProjection({target:'fold',host:window,document,panel,status,answer});
  projection.update({...focus,presentation:{...focus.presentation,sequentialTextEnabled:false}});
  const notice=panel.querySelector('[role="status"]');return {bannerVisible:!!notice&&!notice.hidden,bannerCodeShown:!!notice?.textContent.includes(focus.fallbackReasonCode||'none'),answerVisible:!!answer.textContent,renderedAnswerChars:answer.textContent.length};
 },{focus,fallback});
 await page.locator('#verified-fold-projection').screenshot({path:path.join(outDir,row.scenario+'.png')});
 const events=await page.evaluate(async()=>await (await fetch('/api/diagnostics/debug/events?limit=200')).json());
 row.diagnostics=events.map(e=>e.data||{}).filter(d=>d.sessionHash===row.sessionHash&&d.requestHash===row.requestHash&&d.epoch===row.epoch).map(d=>Object.fromEntries(['stage','requestHash','sessionHash','serverInstanceHash','epoch','outcome','reasonCode','latencyMs','answerModel'].filter(k=>k in d).map(k=>[k,d[k]])));
 row.verdict=row.phase==='ANSWER_READY'&&row.answerChars>0&&row.isFallback===fallback&&row.render.bannerVisible===fallback&&row.diagnostics.some(d=>d.stage==='focus_terminal'&&d.outcome==='success')?'PASS':'FAIL';
 }catch(e){row.verdict='NOT_PROVEN';row.errorClass=e.name;}
 finally{if(binding){if(opened)try{await post('/api/assist/display/focus/close',binding)}catch{};try{await post('/api/assist/display/relay/settings',{...binding,enabled:false,segmentSeconds:0})}catch{}};await ctx.close();out.scenarios.push(row);fs.writeFileSync(path.join(outDir,'evidence.json'),JSON.stringify(out,null,2));}
}}
finally{await browser.close()}
console.log(JSON.stringify(out.scenarios.map(({requests,diagnostics,...x})=>({...x,diagnosticEventCount:diagnostics?.length||0}))));
process.exitCode=out.scenarios.every(x=>x.verdict==='PASS')?0:1;
})();
