'use strict';
// One bounded main-/chat fixture run. No saved auth, traces, credentials, or answer text.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {chromium}=require('playwright');
const ROOT=path.resolve(__dirname,'..');
const PHASE=path.join(ROOT,'data/agent-handoff/codex-autonomy/codex-interview-10turn-resume-8c10743b');
const BASE='http://127.0.0.1:18180';
const ATTACHMENT='C:/Users/nninn/Downloads/PASTE_CODEX_INTERVIEW_10TURN_TEST_AND_FIX_20261005.md';
const execute=process.argv.includes('--execute');
if(process.argv.slice(2).some(x=>!['--execute','--dry-run'].includes(x)))throw Error('unsupported_argument');
const hash=x=>crypto.createHash('sha256').update(String(x)).digest('hex');
const save=(name,data)=>{const file=path.join(PHASE,name),tmp=file+'.saving';
  fs.writeFileSync(tmp,JSON.stringify(data,null,2));fs.renameSync(tmp,file);};
const fixture=fs.readFileSync(ATTACHMENT,'utf8').split(/\r?\n/)
  .map(line=>line.match(/^\| (\d+) \|[^`]+`([^`]+)`/)).filter(Boolean)
  .filter(m=>Number(m[1])>=1&&Number(m[1])<=10).map(m=>({no:Number(m[1]),text:m[2]}));
if(fixture.length!==10||fixture.some((q,i)=>q.no!==i+1))throw Error('exact_fixture_missing');
const ledger=JSON.parse(fs.readFileSync(path.join(PHASE,'live-generation-ledger.json'),'utf8'));
if(execute&&(ledger.used!==0||ledger.attempts.length))throw Error('single_run_already_started_no_retry');
const run8=crypto.randomBytes(4).toString('hex').toUpperCase();
const out={schemaVersion:'demo1.interview-browser.v1',run8,base:BASE,execute,
  playwrightVersion:require('playwright/package.json').version,rows:[],networkGenerationRequests:0,
  blockedGenerationRequests:0,publicAttempts:0,exactOriginalTenScenarioRun:false,
  freshContext:true,authStateExported:false,rawQueriesOrAnswersPersisted:false};
let active=null,sessionA=null,sessionB=null;
const privateRuns=new Map(),pendingBodies=new Set();
const forbiddenStatus=new Set([401,403,429]);
function payload(row,p,event='') {
  if(!p||typeof p!=='object')return;
  if(p.sessionId)row.sessionId=p.sessionId;
  if(event==='session'&&typeof p.data==='string'){
    privateRuns.set(row.no,p.data);row.runIdentityHash=hash(p.data);}
  const observed=p.observedModel||p.observed_model;
  if(typeof observed==='string'&&observed.trim())row.observedModel=observed.trim();
  if(p.observedProvider||p.observed_provider)row.observedProvider=p.observedProvider||p.observed_provider;
  if(p.reasonCode||p.code)row.reasonCode=String(p.reasonCode||p.code).slice(0,100);
  if(event==='final'||p.type==='final'){
    row.serverTerminal='completed';row.answerMode=p.pipelineSnapshot?.answerMode||p.answerMode||null;
    row.traceTurnId=p.traceTurnId??null;
    row.sourceUrls=(p.evidence||[]).flatMap(x=>[x.source,x.url]).filter(x=>typeof x==='string'&&/^https?:/.test(x));}
  if(event==='error'||event==='stream_failed')row.serverTerminal='failed';
  if(event==='status'&&(p.cancelled===true||p.code==='cancelled'))row.serverTerminal='cancelled';
}
async function snapshot(page) {
  return page.evaluate(()=>{
    const messages=[...document.querySelectorAll('#chatWindow .message')];
    const a=messages.filter(x=>x.classList.contains('assistant')).at(-1);
    const text=a?.dataset.ariaText||'';
    const stop=document.querySelector('#stopBtn');
    return {text,assistantCount:messages.filter(x=>x.classList.contains('assistant')).length,
      userCount:messages.filter(x=>x.classList.contains('user')).length,
      enabled:!document.querySelector('#messageInput')?.disabled,
      stopVisible:!!stop&&!stop.hidden&&getComputedStyle(stop).display!=='none',
      firstAnswerSeen:a?.dataset.streamFirstAnswerMs!=null,
      requestId:a?.dataset.streamRequestId||null,
      selectedSession:document.querySelector('[data-session-id][aria-pressed="true"]')?.dataset.sessionId||null};
  });
}
function memoryVerdict(n,text) {
  const old='북극-'+run8,newWord='바람-'+run8;
  const table=/\|/.test(text),two=/(?:\b2\b|2개|두\s*개)/.test(text);
  if(n===1)return {ack:text.trim().length>0};
  if(n===2)return {cacheTermPresent:/캐시/.test(text),cookieTermPresent:/쿠키/.test(text)};
  if(n===3)return {previousUserExact:text.includes(fixture[1].text)};
  if(n===4)return {project:text.includes('종이배'),codeword:text.includes(old),
    orderedStages:/수집[\s\S]*분류[\s\S]*요약/.test(text),limit3:/3/.test(text),shortTable:table};
  if(n===5)return {newCodeword:text.includes(newWord),middle:text.includes('검토'),limit2:two,oldCodewordAbsent:!text.includes(old)};
  if(n===6||n===10)return {newCodeword:text.includes(newWord),middle:text.includes('검토'),limit2:two,shortTable:table,oldCodewordAbsent:!text.includes(old)};
  if(n===7)return {creator:text.includes('귀도'),support:text.includes('Python Software Foundation'),officialLinks:/https?:\/\/(?:www\.)?python\.org/.test(text)};
  if(n===8)return {unknown:/모르|알 수 없|알지 못|제공.*없/.test(text),nonceAbsent:!text.includes(run8),codewordAbsent:!text.includes('북극-')&&!text.includes('바람-')};
  return {};
}
async function main() {
  const browser=await chromium.launch({channel:'msedge',headless:true});
  const context=await browser.newContext({viewport:{width:1440,height:1000}});
  const page=await context.newPage();
  try {
    await page.route('**/api/chat/**',async route=>{
      const req=route.request(),u=new URL(req.url());
      if(req.method()==='POST'&&['/api/chat/stream','/api/chat/sync'].includes(u.pathname)){
        let body={};try{body=req.postDataJSON()||{};}catch{}
        if(u.searchParams.get('attach')!=='true'&&body.attach!==true){
          if(!execute||!active||active.wireRequests>=1||out.networkGenerationRequests>=12){
            out.blockedGenerationRequests++;return route.abort('blockedbyclient');}
          active.wireRequests++;out.networkGenerationRequests++;
          active.requestedModel=body.model||null;active.requestId=req.headers()['x-request-id']||null;
          active.effectiveFlags={useRag:body.useRag,useWebSearch:body.useWebSearch,searchMode:body.searchMode};
          if(body.sessionId)active.sessionId=body.sessionId;
        }
      }
      await route.continue();
    });
    page.on('response',response=>{
      const u=new URL(response.url()),row=active;if(!row||u.origin!==BASE)return;
      if(!['/api/chat/stream','/api/chat/sync','/api/chat/cancel'].includes(u.pathname))return;
      const headers=response.headers();
      if(u.pathname==='/api/chat/cancel')row.cancelHttpStatus=response.status();
      else {row.httpStatus=response.status();row.responseRequestId=headers['x-request-id']||null;}
      const task=(async()=>{try{
        const body=await response.text();
        if(u.pathname==='/api/chat/cancel'){
          const p=JSON.parse(body);row.cancelAcknowledged=p.cancelled===true;
          row.cancelReason=p.reason||p.reasonCode||null;row.cancelAckAtUtc=new Date().toISOString();
          const request=response.request().postDataJSON();
          if(request?.runToken){privateRuns.set(row.no,request.runToken);row.runIdentityHash=hash(request.runToken);}
        }else if((headers['content-type']||'').includes('text/event-stream')){
          for(const block of body.split(/\r?\n\r?\n/)){
            const event=block.match(/^event:\s*(.*)$/m)?.[1]||'';
            const data=block.split(/\r?\n/).filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n');
            if(data)try{payload(row,JSON.parse(data),event);}catch{}
          }
        }else payload(row,JSON.parse(body));
      }catch{row.streamBodyUnavailable=true;}})();
      pendingBodies.add(task);task.finally(()=>pendingBodies.delete(task));
    });
    await page.goto(BASE+'/chat',{waitUntil:'domcontentloaded'});
    await page.locator('#modelSelect').waitFor();
    out.initialModel=await page.locator('#modelSelect').inputValue();
    out.initialOptions=await page.locator('#modelSelect option').evaluateAll(opts=>opts.map(o=>({value:o.value,disabled:o.disabled})));
    if(!execute){out.fixture=fixture.map(q=>({no:q.no,questionHash:hash(q.text)}));out.generations=0;save('W3-dry-run.json',out);return;}
    // The explicit original-fixture request preserves the untouched default. It must be an API route.
    if(!out.initialModel.startsWith('chatgpt-oauth:'))throw Error('untouched_default_not_api_no_generation');
    save('W3-outcomes.json',out);
    for(const q of fixture){
      if(q.no===8){await page.locator('#newChatBtn').click();await page.waitForTimeout(200);}
      if(q.no===9){if(!sessionA)throw Error('own_session_A_unknown');
        await page.locator('[data-session-id="'+sessionA+'"]').click();}
      if(q.no===10&&out.rows.at(-1)?.serverTerminal!=='cancelled'){
        out.rows.push({no:10,verdict:'NOT_RUN',reasonCode:'cancel_terminal_not_confirmed'});break;}
      const before=await snapshot(page);
      if(!before.enabled)throw Error('composer_not_ready_no_submit');
      const text=q.text.replaceAll('RUN8',run8);
      active={no:q.no,sessionAlias:q.no===8?'B':'A',questionHash:hash(text),wireRequests:0,
        observedModel:'UNOBSERVED',httpStatus:null,reasonCode:null,submittedAtUtc:new Date().toISOString()};
      out.rows.push(active);ledger.used++;ledger.attempts.push({scenario:q.no,questionHash:active.questionHash,submittedAtUtc:active.submittedAtUtc});
      if(ledger.used>12)throw Error('budget_exceeded');
      save('live-generation-ledger.json',ledger);save('W3-outcomes.json',out);
      const t0=Date.now();await page.locator('#messageInput').fill(text);await page.locator('#sendBtn').click();
      let dom=before,stopped=false,lastProgress=0;
      while(Date.now()-t0<120000){
        dom=await snapshot(page);const elapsed=Date.now()-t0;
        if(q.no===9&&!stopped&&(dom.firstAnswerSeen||elapsed>=10000)&&dom.stopVisible){
          active.cancelClickAtUtc=new Date().toISOString();active.cancelClickMs=elapsed;
          await page.locator('#stopBtn').click();stopped=true;}
        if(dom.enabled&&dom.userCount>before.userCount&&active.httpStatus!==null)break;
        if(forbiddenStatus.has(active.httpStatus))break;
        if(elapsed-lastProgress>=10000){lastProgress=elapsed;
          process.stdout.write(JSON.stringify({scenario:q.no,stage:'waiting',elapsedMs:elapsed,http:active.httpStatus})+'\n');}
        await page.waitForTimeout(100);
      }
      await Promise.race([Promise.allSettled([...pendingBodies]),page.waitForTimeout(2500)]);
      dom=await snapshot(page);active.elapsedMs=Date.now()-t0;
      active.requestId=active.requestId||dom.requestId;active.sessionId=active.sessionId||Number(dom.selectedSession)||null;
      active.responseIdMatches=!!active.requestId&&active.requestId===active.responseRequestId;
      active.dom={userCount:dom.userCount,assistantCount:dom.assistantCount,answerLength:dom.text.length,answerHash:hash(dom.text)};
      active.hold=/\bHOLD\b|답변.*보류/.test(dom.text);active.backendUnavailable=/backend_unavailable/.test(dom.text);
      active.memoryChecks=memoryVerdict(q.no,dom.text);
      active.memoryMatches=Object.values(active.memoryChecks).every(Boolean);
      if(q.no===1)sessionA=active.sessionId;if(q.no===8)sessionB=active.sessionId;
      if(q.no===9&&active.cancelAcknowledged){
        const exactRunId=privateRuns.get(9);if(exactRunId&&sessionA){
          const state=await context.request.get(BASE+'/api/chat/state?sessionId='+sessionA,{headers:{'x-chat-run-token':exactRunId}});
          const data=await state.json();active.cancelStateHttp=state.status();
          active.cancelRunStatus=data.runStatus||null;
          if(data.runStatus==='cancelled')active.serverTerminal='cancelled';
        }
      }
      if(q.no===9&&!active.cancelAcknowledged&&active.serverTerminal==='completed')active.cancelVerdict='CANCEL_NOT_OBSERVABLE';
      active.verdict=active.httpStatus===200&&active.responseIdMatches&&!active.hold&&!active.backendUnavailable&&active.memoryMatches?'PASS':'FAIL';
      if(q.no===9)active.verdict=active.serverTerminal==='cancelled'&&active.cancelAcknowledged?'PASS':'FAIL';
      process.stdout.write(JSON.stringify({scenario:q.no,verdict:active.verdict,http:active.httpStatus,requestId:active.requestId,
        observedModel:active.observedModel,reasonCode:active.reasonCode,terminal:active.serverTerminal,memoryMatches:active.memoryMatches})+'\n');
      save('W3-outcomes.json',out);save('live-generation-ledger.json',ledger);
      if(forbiddenStatus.has(active.httpStatus)||forbiddenStatus.has(active.cancelHttpStatus))throw Error('auth_or_quota_no_retry');
      if(!dom.enabled)throw Error('accepted_run_still_active_no_next_submit');
      if(q.no===7){await page.reload({waitUntil:'domcontentloaded'});await page.locator('#modelSelect').waitFor();
        const after=await snapshot(page);out.reload={sessionA,selectedSession:after.selectedSession,
          userCount:after.userCount,assistantCount:after.assistantCount,countsMatch:after.userCount===dom.userCount&&after.assistantCount===dom.assistantCount};
        const value=await page.locator('#modelSelect').inputValue();
        if(await page.locator('#modelSelect option').evaluateAll((opts,v)=>opts.some(o=>o.value===v&&!o.disabled),value)){
          await page.locator('#modelSelect').selectOption(value);out.manualReselection={value,method:'native existing enabled option'};
        }
      }
    }
    out.sessionA=sessionA;out.sessionB=sessionB;out.sessionIsolation=sessionA!=null&&sessionB!=null&&sessionA!==sessionB;
    out.exactOriginalTenScenarioRun=out.rows.length===10&&out.rows.every(r=>r.wireRequests===1)&&out.rows[9].serverTerminal==='completed';
  }catch(e){out.failure=e.message.slice(0,150);process.exitCode=1;}
  finally{out.used=ledger.used;out.limit=12;save(execute?'W3-outcomes.json':'W3-dry-run.json',out);
    await context.close();await browser.close();}
}
main().catch(e=>{save(execute?'W3-outcomes.json':'W3-dry-run.json',{...out,failure:e.name});process.exitCode=1;});
