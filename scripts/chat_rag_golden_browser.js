'use strict';
// Only loopback live scenarios; fixture assertions do not prove a live answer.
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync}=require('node:child_process');
const {selectModel}=require('./browser_model_select');

const ORDER = ['C1','C2','C5','C3','C4','C6','C7','C8','C9'];
function parseArgs(args) {
  const options = {base:'http://127.0.0.1:18180', only:ORDER.slice(0,8), repeat:1, maxSends:20, selectionMode:'strict', purpose:'quality', run:null};
  const names = {'--base':'base','--only':'only','--repeat':'repeat','--max-sends':'maxSends','--model':'model','--selection-mode':'selectionMode','--purpose':'purpose','--run':'run'};
  for (let i=0;i<args.length;i++) {
    if (!names[args[i]] || !args[i+1] || args[i+1].startsWith('--')) throw Error('invalid_arguments');
    options[names[args[i]]] = args[++i];
  }
  const url = new URL(options.base);
  if (url.protocol!=='http:' || !['127.0.0.1','localhost','[::1]'].includes(url.hostname)
      || url.username || url.password || url.pathname!=='/' || url.search || url.hash) throw Error('loopback_required');
  options.base=url.origin;
  if (typeof options.only==='string') options.only=options.only.split(',');
  if (!options.only.length || new Set(options.only).size!==options.only.length || options.only.some(id=>!ORDER.includes(id))) throw Error('invalid_scenarios');
  options.only=ORDER.filter(id=>options.only.includes(id));
  options.repeat=Number(options.repeat);options.maxSends=Number(options.maxSends);
  if(!['strict','auto'].includes(options.selectionMode))throw Error('invalid_selection_mode');
  if(!/^[a-z][a-z0-9_]{0,31}$/.test(options.purpose))throw Error('invalid_purpose');
  if(options.run!=null && !/^[A-Za-z0-9_.:-]{1,80}$/.test(options.run))throw Error('invalid_run');
  if (!Number.isInteger(options.repeat) || options.repeat<1 || options.repeat>3
      || !Number.isInteger(options.maxSends) || options.maxSends<1 || options.maxSends>40) throw Error('invalid_budget');
  return options;
}
class SendBudget {
  constructor(max){this.max=max;this.used=0;}
  reserve(){if(this.used>=this.max)throw Error('MAX_SENDS_REACHED');this.used++;}
}
function policyInvoke(root,args){
  const out=spawnSync('python',['-B',path.join(root,'scripts','test_model_policy.py'),...args],{encoding:'utf8',cwd:root,timeout:20000});
  const line=(out.stdout||'').trim().split('\n').filter(Boolean).pop();
  if(!line)return{verdict:'POLICY_TOOL_EMPTY',error:String((out.error&&out.error.message)||out.stderr||'').slice(0,160)};
  try{return JSON.parse(line);}catch{return{verdict:'POLICY_TOOL_PARSE'};}
}
const DEFAULT_POLICY={invoke:policyInvoke};
async function guardGenerationRoute(route, budget, blocked) {
  const request=route.request(), endpoint=new URL(request.url()).pathname;
  if(request.method()==='POST' && /^\/api\/chat\/(?:stream|sync)$/.test(endpoint)) {
    try { budget.reserve(); }
    catch { blocked.push('MAX_SENDS_REACHED'); await route.abort('blockedbyclient'); return; }
  }
  await route.continue();
}
function judge(id, question, observed) {
  const text=observed.answer||'', evidence=observed.evidence||'', reasons=[];
  if(!text.trim() || /Assistant is preparing|응답 본문을 보류|evidence_needed|Information unavailable|응답 대기 시간이 초과|요청이 취소|LLM (?:호출|설정) 오류|찾을 수 없습니다|질문은 보류|모델 공급자|응답이 지연/i.test(text))reasons.push('answer_unavailable');
  for(const keyword of question.expected||[])if(!text.includes(keyword))reasons.push('missing_keyword:'+keyword);
  if(question.expectedAny && !question.expectedAny.some(k=>text.includes(k)))reasons.push('document_absence_not_acknowledged');
  for(const keyword of question.forbidden||[])if(text.includes(keyword))reasons.push('forbidden_keyword:'+keyword);
  for(const file of question.evidence||[])if(!evidence.includes(file))reasons.push('missing_evidence_file:'+file);
  if(id==='C4' && /\d[\d,]*(?:\.\d+)?\s*(?:억|만|천)?\s*(?:원|달러|USD|KRW)/i.test(text))reasons.push('invented_budget');
  if(id==='C1' && (observed.firstBodyMs==null || observed.firstBodyMs>30000))reasons.push('first_body_over_30s');
  if(id==='C1' && !observed.modelStatus)reasons.push('model_not_displayed');
  if(observed.errors)reasons.push('ui_error');
  if(id==='C6' && !/https?:\/\//.test(evidence+text)
      && !/disabled|missing.?key|미설정|비활성|검색.*(?:실패|불가|꺼짐)|provider_disabled|no.?key/i.test(evidence+text+(observed.trace||'')))reasons.push('web_source_or_disabled_reason_missing');
  if(id==='C7' && !observed.observedModel)reasons.push('observed_model_not_proven');
  if(observed.requestedProvider && observed.requestedProvider!=='Ollama' && observed.observedProvider==='Ollama')
    reasons.push('silent_local_fallback');
  if(observed.observedModel && observed.modelMatched===false)reasons.push('model_substitution_unexplained');
  return {verdict:reasons.length?'FAIL':'PASS',reasons};
}
function safeMetadata(body, diagnostic={}) {
  const result={};
  Object.assign(diagnostic,{bodyBytes:Buffer.byteLength(body),eventCount:0,dataLineCount:0,
    jsonParseSucceeded:0,jsonParseFailed:0,truncatedEvents:0,eventTypes:{}});
  const allowed=/^(?:releaseReason|releaseStatus|verificationStatus|evidenceCount|localDocsCount|citableEvidenceCount|actualModel|observedModel|observedProvider|observedReason|selectedModel|selectedProvider|configuredModel|effectiveModel|finalModel|sessionId|traceTurnId|reasonCode|routeId|fallbackCount|fallbackReason|modelVerified|providerSurface|toolId)$/;
  function visit(value,depth=0,parent='') {
    if(!value || typeof value!=='object' || depth>8)return;
    for(const [key,item] of Object.entries(value)) {
      if(parent==='generationTermination' && ['reason','status'].includes(key) && typeof item==='string'
          && /^[a-z0-9_]{1,80}$/.test(item))result[key==='reason'?'terminalReason':'terminalStatus']=item;
      if(key==='modelUsed' && typeof item==='string' && item.length<=160
          && !/Bearer |sk-|token=/i.test(item))result.claimedModel=item;
      if(allowed.test(key) && ['string','number','boolean'].includes(typeof item)
          && String(item).length<=160 && !/Bearer |sk-|token=/i.test(String(item))) result[key]=item;
      if(typeof item==='object')visit(item,depth+1,key);
    }
  }
  const normalized=body.replace(/\r\n?/g,'\n');
  const blocks=normalized.split('\n\n');
  for(let index=0;index<blocks.length;index++){
    const block=blocks[index],data=[];let type='message';
    for(const line of block.split('\n')){
      if(line.startsWith('event:'))type=line.slice(6).trim();
      if(line.startsWith('data:')){data.push(line.slice(5).replace(/^ /,''));diagnostic.dataLineCount++;}
    }
    if(!data.length)continue;
    diagnostic.eventCount++;
    if(!/^[A-Za-z0-9_-]{1,48}$/.test(type))type='unknown';
    diagnostic.eventTypes[type]=(diagnostic.eventTypes[type]||0)+1;
    if(index===blocks.length-1 && !normalized.endsWith('\n\n'))diagnostic.truncatedEvents++;
    try{visit(JSON.parse(data.join('\n')));diagnostic.jsonParseSucceeded++;}
    catch{diagnostic.jsonParseFailed++;}
  }
  return result;
}
async function collectStream(response) {
  const capture={bodyReadStatus:'pending',bodyBytes:0,eventCount:0,dataLineCount:0,
    jsonParseSucceeded:0,jsonParseFailed:0,truncatedEvents:0,eventTypes:{}};
  try {
    const body=await response.text();
    capture.bodyReadStatus='success';
    capture.completedAtEpochMs=Date.now();
    return {metadata:safeMetadata(body,capture),capture};
  } catch(error) {
    capture.bodyReadStatus='failed';
    capture.completedAtEpochMs=Date.now();
    capture.error=String(error?.message||error).replace(/Bearer\s+\S+/gi,'Bearer <redacted>')
      .replace(/(?:sk-|token=|cookie=|authorization=)\S+/gi,'<redacted>')
      .replace(/[A-Za-z0-9+/_=-]{32,}/g,'<redacted>').slice(0,240);
    return {metadata:{},capture};
  }
}
async function ownedTrace(context, base, metadata) {
  if (!metadata.sessionId || !metadata.traceTurnId) return {matched:false,reason:'trace_pointer_missing'};
  const response=await context.request.get(base+'/api/chat/sessions/'+metadata.sessionId+
    '/traces/turn_'+metadata.traceTurnId+'/html?format=json');
  if(!response.ok())return {matched:false,reason:'trace_http_'+response.status(),status:response.status()};
  const value=await response.json(), diagnostic=value.diagnostics||{};
  const matched=value.traceTurnId===metadata.traceTurnId && !!metadata.observedModel &&
    diagnostic.observedModel===metadata.observedModel && diagnostic.observedProvider===metadata.observedProvider &&
    diagnostic.routeId===metadata.routeId && diagnostic.fallbackCount===metadata.fallbackCount;
  const safe={};
  for(const key of ['observedModel','observedProvider','routeId','fallbackCount','fallbackReason','observedReason',
    'attachment.bind.count','attachment.bind.reason','attachment.bind.attempted','attachment.bind.applied',
    'attachment.sessionFilter.allowedCount','prompt.localDocsCount','prompt.localDocsRenderedCount',
    'rag.evidence.promotion.candidateCount','rag.evidence.promotion.citableLocatorCount',
    'rag.evidence.promotion.evidenceGatePassed','rag.evidence.promotion.citationGateMinPassed',
    'rag.evidence.promotion.promotedCount','finalAnswer.releaseReason','finalAnswer.evidenceReleaseState',
    'finalAnswer.releaseAllowed','finalAnswer.evidenceScopeBound','finalAnswer.retrievalExecution'])
    if(['string','number','boolean'].includes(typeof diagnostic[key]))safe[key]=diagnostic[key];
  return {matched,reason:matched?null:'observed_trace_mismatch',traceTurnId:value.traceTurnId,status:response.status(),diagnostics:safe};
}
async function run(options, chromium, hooks) {
  const root=path.resolve(__dirname,'..'), fixtures=path.join(root,'docs/demo/fixtures/interview-rag');
  const policy=(hooks&&hooks.policy)||DEFAULT_POLICY;
  const runId=options.run||('golden-'+Date.now());
  const questions=JSON.parse(fs.readFileSync(path.join(fixtures,'questions.json'),'utf8'));
  const out=path.join(root,'output/playwright/chat-rag-golden',new Date().toISOString().replace(/[:.]/g,'-'));
  fs.mkdirSync(out,{recursive:true});
  const result={surface:'live-loopback',base:options.base,run:runId,repeat:options.repeat,sends:0,results:[],output:out};
  const budget=new SendBudget(options.maxSends);
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try {
    for(let round=1;round<=options.repeat;round++) {
      const context=await browser.newContext({viewport:{width:1440,height:1000}});
      await context.addInitScript(({parserSource})=>{
        const parse=new Function('return ('+parserSource+')')();
        const original=window.fetch.bind(window);
        window.__goldenStreamReads=[];
        window.fetch=async(...args)=>{
          const response=await original(...args);
          if(new URL(response.url,location.href).pathname==='/api/chat/stream'){
            const capture={source:'page_fetch_clone',bodyReadStatus:'pending'};
            window.__goldenStreamReads.push(response.clone().text().then(body=>{
              capture.bodyReadStatus='success';capture.completedAtEpochMs=Date.now();
              return {metadata:parse(body,capture),capture};
            },error=>{
              capture.bodyReadStatus='failed';capture.reason='page_body_read_failed';
              return {metadata:{},capture};
            }));
          }
          return response;
        };
      },{parserSource:safeMetadata.toString().replace('Buffer.byteLength(body)','new TextEncoder().encode(body).length')});
      await context.addInitScript(()=>{
        window.__goldenCorrelationHashes=[];
        document.addEventListener('awx:trace-turn',event=>{
          const hash=event.detail?.requestIdHash;
          if(typeof hash==='string' && /^(?:hash:)?[a-f0-9]{12,64}$/i.test(hash))
            window.__goldenCorrelationHashes.push(hash);
        });
      });
      const page=await context.newPage(), network=[], metadata=[], captures=[], blocked=[];
      await context.route('**/api/chat/**',route=>guardGenerationRoute(route,budget,blocked));
      page.on('request',request=>{
        const endpoint=new URL(request.url()).pathname;
        if(request.method()!=='POST' || !/^\/api\/chat\/(?:stream|sync)$/.test(endpoint))return;
        try {
          const payload=request.postDataJSON(), ids=payload.attachmentIds;
          network.push({endpoint,phase:'request',attachmentCount:Array.isArray(ids)?ids.length:0,
            sessionPresent:payload.sessionId!=null,atEpochMs:Date.now()});
        } catch { network.push({endpoint,phase:'request',reason:'request_shape_unavailable'}); }
      });
      let captured=[];
      page.on('response', response=>{
        const url=new URL(response.url());
        if(!url.pathname.startsWith('/api/chat/'))return;
        const timing=response.request().timing();
        network.push({endpoint:url.pathname,status:response.status(),latencyMs:Math.round(timing.responseStart>=0?timing.responseStart:0)});
        if(url.pathname.endsWith('/stream'))captured.push(collectStream(response).then(summary=>{
          metadata.push(summary.metadata);captures.push(summary.capture);
        }));
      });
      await page.goto(options.base+'/chat',{waitUntil:'domcontentloaded'});
      await page.locator('#modelSelect').waitFor();
      const catalogResponse=await context.request.get(options.base+'/api/chat/models');
      if(!catalogResponse.ok())throw Error('MODEL_CATALOG_HTTP_'+catalogResponse.status());
      const catalog=catalogResponse.ok()?await catalogResponse.json():[];
      const selectable=Array.isArray(catalog)?catalog.filter(m=>m.selectable!==false):[];
      await page.waitForFunction(()=>document.querySelector('#modelSelect')?.options.length>1,null,{timeout:10000});
      let policySelection=null, requested=options.model||null;
      // AWX-TEST-MODEL-POLICY: without --model the strict runner resolves through the
      // model policy (api-first until cutoff) instead of the page's current selection.
      if(!requested && options.selectionMode!=='auto' && options.only.some(id=>id!=='C9')) {
        policySelection=policy.invoke(root,['resolve','--purpose',options.purpose,'--base',options.base,'--run',runId]);
        if(!policySelection||policySelection.verdict!=='RESOLVED'||!policySelection.selected)
          throw Error('POLICY_RESOLVE_FAILED:'+((policySelection&&(policySelection.error||policySelection.verdict))||'no_output').toString().slice(0,120));
        const policyIds=[policySelection.selected,...(policySelection.alternates||[])];
        const policyEntry=policyIds.map(id=>selectable.find(m=>m.id===id||m.modelId===id)).find(Boolean);
        if(!policyEntry)throw Error('POLICY_MODEL_NOT_SELECTABLE:'+policyIds.slice(0,4).join(','));
        requested=policyEntry.id;
        result.policy={purpose:options.purpose,resolvedSelected:policySelection.selected,selected:policyEntry.id,verdict:policySelection.verdict,mode:policySelection.mode||null};
      }
      requested=requested||(options.selectionMode==='auto' ? selectable.find(m=>m.defaultChoice===true)?.id : null)
          ||await page.locator('#modelSelect').inputValue();
      const entry=selectable.find(m=>m.id===requested||m.modelId===requested);
      if(!entry)throw Error('MODEL_NOT_SELECTABLE');
      if(options.selectionMode==='strict')await selectModel(page,entry.id);
      await page.locator('#modelSelectionMode').selectOption(options.selectionMode);
      let lastId=null;
      async function scenario(id, prerequisite=false) {
        const q=questions[id], startNetwork=network.length,startMeta=metadata.length,startCapture=captures.length;
        const startPageCapture=await page.evaluate(()=>window.__goldenStreamReads.length);
        const startHashes=await page.evaluate(()=>window.__goldenCorrelationHashes.length);
        if(id!=='C5')await page.locator('#newChatBtn').click();
        await page.locator('#searchModeSelect').selectOption(id==='C6'?'FORCE_LIGHT':'OFF');
        await page.locator('#useRagToggle').setChecked((q.files||[]).length>0);
        let scenarioEntry=entry;
        if(id==='C7') {
          // Policy-driven runs take the C7 alternate from the resolved alternates
          // (only local_fallback alternates may be Ollama); explicit --model keeps
          // the legacy local-sibling behavior.
          let alternative=null;
          if(policySelection){
            for(const altId of [policySelection.selected,...(policySelection.alternates||[])]){
              alternative=selectable.find(m=>(m.id===altId||m.modelId===altId)&&m.id!==entry.id);
              if(alternative)break;
            }
          }
          if(!alternative)alternative=entry.provider!=='Ollama'?entry
              :selectable.find(m=>m.id!==entry.id&&m.provider==='Ollama');
          if(!alternative)return {id,round,verdict:'BLOCKED',reasons:['alternative_model_unavailable']};
          scenarioEntry=alternative;
        }
        if(options.selectionMode==='strict')await selectModel(page,scenarioEntry.id);
        await page.locator('#modelSelectionMode').selectOption(options.selectionMode);
        if(q.files.length) {
          await page.locator('#attachInput').setInputFiles(q.files.map(f=>path.join(fixtures,f)));
          await page.waitForFunction(()=>[...document.querySelectorAll('#attachList .attach-chip')].every(c=>!c.textContent.includes('업로드 중')),null,{timeout:20000});
          if(await page.locator('#attachList .attach-chip.error').count())throw Error('attachment_upload_failed');
        }
        const before=await page.locator('[data-message-role="assistant"]').count();
        await page.locator('#messageInput').fill(q.question);
        if(budget.used>=budget.max)throw Error('MAX_SENDS_REACHED');
        const started=Date.now();
        await page.locator('#sendBtn').click();
        let firstBodyMs=null;
        await page.waitForFunction(count=>{
          const nodes=document.querySelectorAll('[data-message-role="assistant"]'),last=nodes[nodes.length-1];
          return nodes.length>count && last && last.textContent.trim() && !/Assistant is preparing|답변을 준비/.test(last.textContent);
        },before,{timeout:90000});
        firstBodyMs=Date.now()-started;
        await page.waitForFunction(()=>{
          const stop=document.querySelector('#stopBtn');
          return !stop || stop.hidden || getComputedStyle(stop).display==='none';
        },null,{timeout:90000});
        await Promise.all(captured);captured=[];
        const pageCaptures=await page.evaluate(async start=>Promise.all(window.__goldenStreamReads.slice(start)),startPageCapture);
        for(const summary of pageCaptures){metadata.push(summary.metadata);captures.push(summary.capture);}
        const assistant=page.locator('[data-message-role="assistant"]').last();
        const answer=await assistant.innerText();
        const evidence=await assistant.locator('.evidence-rail').allTextContents();
        const trace=await page.locator('#traceStatus').innerText();
        const modelStatus=await page.locator('#modelStatus').innerText();
        const meta=Object.assign({},...metadata.slice(startMeta));
        const observedModel=meta.observedModel||null;
        const normId=s=>String(s||'').replace(/^chatgpt-oauth:/,'');
        const observedProvider=meta.observedProvider||null;
        const traceProof=await ownedTrace(context,options.base,meta);
        const observed={answer,evidence:answer+' '+evidence.join(' '),trace,modelStatus,firstBodyMs,
          observedModel,requestedModel:scenarioEntry.id,requestedProvider:scenarioEntry.provider,observedProvider,
          modelMatched:options.selectionMode==='strict'&&observedModel?[scenarioEntry.id,scenarioEntry.modelId].map(normId).includes(normId(observedModel)):null,
          errors:await page.locator('.toast.error:visible,.alert-danger:visible').count()};
        const verdict=judge(id,q,observed);
        if ((options.selectionMode==='auto'||id==='C7') && !traceProof.matched) {
          verdict.verdict='FAIL';verdict.reasons.push(traceProof.reason);
        }
        if(options.selectionMode==='auto' && !observedModel){verdict.verdict='FAIL';verdict.reasons.push('observed_model_not_proven');}
        if(blocked.length) {verdict.verdict='BLOCKED';verdict.reasons.push('MAX_SENDS_REACHED');}
        const screenshot=path.join(out,id+'-'+round+(prerequisite?'-prerequisite':'')+'.png');
        await page.screenshot({path:screenshot,fullPage:false});
        lastId=id;
        return {id,round,requestedModel:options.selectionMode==='auto'?'llmrouter.auto':scenarioEntry.id,observedModel,observedProvider,prerequisite,...verdict,firstBodyMs,totalMs:Date.now()-started,
          requestIdHashes:await page.evaluate(start=>[...new Set(window.__goldenCorrelationHashes.slice(start))],startHashes),
          answerChars:answer.length,keywords:(q.expected||[]).map(k=>({keyword:k,found:answer.includes(k)})),
          evidenceFiles:(q.evidence||[]).map(f=>({file:f,found:observed.evidence.includes(f)})),
          modelStatus,trace,metadata:meta,traceProof,captures:captures.slice(startCapture),network:network.slice(startNetwork),screenshot};
      }
      try {
        for(const id of options.only) {
          const rowMark=result.results.length;
          try{
            if(id==='C9'){
              await page.goto(options.base+'/settings',{waitUntil:'domcontentloaded'});
              await page.waitForFunction(()=>document.querySelector('#local-model').options.length>1,null,{timeout:10000});
              const count=await page.locator('#local-model option').count();
              result.results.push({id,round,verdict:count>2?'PASS':'FAIL',reasons:count>2?[]:['catalog_options_missing'],optionCount:count});
              continue;
            }
            if(id==='C5'&&lastId!=='C2')result.results.push(await scenario('C2',true));
            result.results.push(await scenario(id));
          }catch(error){
            await Promise.all(captured);captured=[];
            const pageCaptures=await page.evaluate(async()=>Promise.all(window.__goldenStreamReads||[])).catch(()=>[]);
            for(const summary of pageCaptures){metadata.push(summary.metadata);captures.push(summary.capture);}
            result.results.push({id,round,verdict:error.message==='MAX_SENDS_REACHED'?'BLOCKED':'FAIL',
              reasons:[error.message.split('\n')[0].slice(0,180)],network:[...network],
              metadata:Object.assign({},...metadata),captures:[...captures]});
          }
          // Per-send policy evidence: every stream|sync POST is recorded, and a
          // strict-mode row whose observed model fails `check` can never PASS.
          for(const r of result.results.slice(rowMark)){
            const posts=(r.network||[]).filter(n=>n.phase==='request'&&/^\/api\/chat\/(?:stream|sync)$/.test(n.endpoint||''));
            const statuses=(r.network||[]).filter(n=>n.status!=null&&/^\/api\/chat\/(?:stream|sync)$/.test(n.endpoint||'')).map(n=>n.status).join('+');
            for(let i=0;i<posts.length;i++)
              policy.invoke(root,['record','--agent','chat_rag_golden_browser','--purpose',options.purpose,
                '--model',String(r.requestedModel||entry.id),'--code',String(statuses||r.verdict||'sent').slice(0,64),'--run',runId]);
            if(posts.length && options.selectionMode==='strict' && r.requestedModel && r.requestedModel!=='llmrouter.auto' && r.verdict!=='BLOCKED'){
              const chk=policy.invoke(root,['check','--selected',String(r.requestedModel),'--observed',String(r.observedModel||'')]);
              if(chk&&chk.verdict&&chk.verdict!=='OK'&&!/^POLICY_TOOL/.test(chk.verdict)){
                r.verdict='FAIL';
                r.reasons=(r.reasons||[]).concat('policy_check_'+String(chk.verdict).toLowerCase().slice(0,48));
              }
            }
          }
          result.sends=budget.used;
          fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));
          if(budget.used>=budget.max)break;
        }
      }finally{await context.close();}
      if(budget.used>=budget.max)break;
    }
  }finally{await browser.close();result.sends=budget.used;fs.writeFileSync(path.join(out,'result.json'),JSON.stringify(result,null,2));}
  return result;
}
async function main(){
  let options;try{options=parseArgs(process.argv.slice(2));}catch(error){console.log(JSON.stringify({verdict:'USAGE',reason:error.message}));return 2;}
  let chromium;try{({chromium}=require('playwright'));}catch{console.log(JSON.stringify({verdict:'NOT_RUN',reason:'PLAYWRIGHT_MISSING'}));return 5;}
  try{
    const result=await run(options,chromium);
    console.log(JSON.stringify({output:result.output,sends:result.sends,results:result.results.map(({id,round,verdict,reasons})=>({id,round,verdict,reasons}))}));
    return result.results.length===options.only.length*options.repeat && result.results.every(r=>r.verdict==='PASS')?0:1;
  }catch(error){console.log(JSON.stringify({verdict:'BLOCKED',reason:error.message.split('\n')[0].slice(0,180)}));return 1;}
}
module.exports={parseArgs,SendBudget,guardGenerationRoute,judge,safeMetadata,collectStream,ownedTrace,run};
if(require.main===module)main().then(code=>{process.exitCode=code;});
