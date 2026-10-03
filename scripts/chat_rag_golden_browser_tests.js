'use strict';
// All HTTP traffic in this suite stays on a synthetic fixture server.
const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const {parseArgs,SendBudget,guardGenerationRoute,judge,safeMetadata,collectStream,run}=require('./chat_rag_golden_browser');
const root=path.resolve(__dirname,'..');
let browser;
before(async()=>{const {chromium}=require('playwright');browser=await chromium.launch({channel:'msedge',headless:true});});
after(async()=>{if(browser)await browser.close();});
test('loopback and finite send limits are enforced before navigation',()=>{
  for(const base of ['https://example.com','http://127.0.0.1.evil.invalid','http://user:pass@localhost','http://localhost/other'])assert.throws(()=>parseArgs(['--base',base]));
  assert.throws(()=>parseArgs(['--max-sends','41']));
  assert.throws(()=>parseArgs(['--repeat','4']));
  assert.equal(parseArgs(['--only','C2,C3','--repeat','3']).repeat,3);
  assert.equal(parseArgs(['--only','C1','--selection-mode','auto']).selectionMode,'auto');
  assert.equal(parseArgs(['--only','C1']).selectionMode,'strict');
  assert.throws(()=>parseArgs(['--selection-mode','preferred']));
  const budget=new SendBudget(1);budget.reserve();assert.throws(()=>budget.reserve(),/MAX_SENDS_REACHED/);assert.equal(budget.used,1);
});
test('canary keywords, evidence provenance, abstention and observed model are separate assertions',()=>{
  const q={expected:['은하수-7','2026-10-15'],evidence:['A_project_codename.md']};
  assert.equal(judge('C2',q,{answer:'은하수-7 2026-10-15',evidence:'A_project_codename.md'}).verdict,'PASS');
  assert.equal(judge('C2',q,{answer:'은하수-9 2026-10-15',evidence:'A_project_codename.md'}).verdict,'FAIL');
  assert.equal(judge('C2',q,{answer:'은하수-7 2026-10-15',evidence:''}).verdict,'FAIL');
  assert.equal(judge('C4',{expectedAny:['없'],forbidden:[]},{answer:'문서에 없습니다. 100만 원일 것입니다.'}).verdict,'FAIL');
  assert.equal(judge('C7',{},{answer:'안녕하세요',modelStatus:'요청 x / 응답 x'}).verdict,'FAIL');
  assert.equal(judge('C1',{},{answer:'응답 대기 시간이 초과되었습니다.',firstBodyMs:100,modelStatus:'요청 x'}).verdict,'FAIL');
});
test('api-model requests fail on silent local fallback or unexplained substitution',()=>{
  const slide=judge('C7',{},{answer:'ok',observedModel:'gemma4:12b',requestedModel:'llmrouter.api3',requestedProvider:'groq',observedProvider:'Ollama',modelMatched:false});
  assert.equal(slide.verdict,'FAIL');assert(slide.reasons.includes('silent_local_fallback'));
  const sub=judge('C3',{},{answer:'ok',observedModel:'llmrouter.openai-economy',requestedModel:'llmrouter.api3',requestedProvider:'groq',observedProvider:'openai',modelMatched:false});
  assert.equal(sub.verdict,'FAIL');assert(sub.reasons.includes('model_substitution_unexplained'));
  const honored=judge('C7',{},{answer:'ok',observedModel:'openai/gpt-oss-120b',requestedModel:'llmrouter.api3',requestedProvider:'groq',observedProvider:'groq',modelMatched:true,modelStatus:'openai/gpt-oss-120b'});
  assert.equal(honored.verdict,'PASS');
  assert.equal(judge('C1',{},{answer:'⚠️ LLM 호출 오류: 모델 \'FallbackAwareChatModel\' 을(를) 찾을 수 없습니다',firstBodyMs:100,modelStatus:'응답 x'}).verdict,'FAIL');
});
test('automatic stream retry and sync fallback share the actual POST budget',async()=>{
  const budget=new SendBudget(1), blocked=[], actions=[];
  const route=(endpoint,method='POST')=>({request:()=>({url:()=> 'http://127.0.0.1'+endpoint,method:()=>method}),
    continue:async()=>actions.push('sent'),abort:async()=>actions.push('blocked')});
  await guardGenerationRoute(route('/api/chat/stream'),budget,blocked);
  await guardGenerationRoute(route('/api/chat/stream'),budget,blocked);
  await guardGenerationRoute(route('/api/chat/sync'),budget,blocked);
  await guardGenerationRoute(route('/api/chat/cancel'),budget,blocked);
  assert.equal(budget.used,1);assert.deepEqual(actions,['sent','blocked','blocked','sent']);
  assert.equal(blocked.length,2);
});
test('safe SSE summary stores reason codes and counts without prompt or credentials',()=>{
  const value=safeMetadata('data: '+JSON.stringify({data:{releaseReason:'verification_insufficient',evidenceCount:0,prompt:'private text',authorization:'synthetic-value'}}));
  assert.deepEqual(value,{releaseReason:'verification_insufficient',evidenceCount:0});
});

test('R2 owned trace requires actual observed identity in the same turn',async()=>{
  const {ownedTrace}=require('./chat_rag_golden_browser');
  const metadata={sessionId:91,traceTurnId:93,observedModel:'cloud-model',observedProvider:'cloud',routeId:'hash:123456789012',fallbackCount:0};
  const context={request:{get:async url=>{
    assert.match(url,/sessions\/91\/traces\/turn_(93|94)\/html\?format=json$/);
    return {ok:()=>true,status:()=>200,json:async()=>({traceTurnId:93,diagnostics:{...metadata}})};
  }}};
  assert.equal((await ownedTrace(context,'http://127.0.0.1:18180',metadata)).matched,true);
  assert.equal((await ownedTrace(context,'http://127.0.0.1:18180',{...metadata,traceTurnId:94})).matched,false);
});
test('R1 SSE compact final records claimed model without promoting it',()=>{
  const stats={};
  const meta=safeMetadata('event: final\ndata: {"modelUsed":"synthetic-cloud","sessionId":901,"traceTurnId":902}\n\n',stats);
  assert.equal(meta.claimedModel,'synthetic-cloud');
  assert.equal(meta.observedModel,undefined);
  assert.equal(meta.modelUsed,undefined);
  assert.equal(stats.eventCount,1);
  assert.equal(stats.jsonParseSucceeded,1);
});
test('R1 SSE multiline data is joined before JSON parsing',()=>{
  const stats={};
  const meta=safeMetadata('event: final\ndata: {\ndata: "observedProvider":"synthetic-provider",\ndata: "observedModel":"response-model",\ndata: "traceTurnId":902\ndata: }\n\n',stats);
  assert.equal(meta.observedModel,'response-model');
  assert.equal(meta.observedProvider,'synthetic-provider');
  assert.equal(stats.jsonParseSucceeded,1);
  assert.equal(stats.jsonParseFailed,0);
  assert.equal(stats.dataLineCount,5);
});
test('R1 SSE truncated stream records parsing failure without inventing metadata',()=>{
  const stats={},body='event: final\ndata: {"observedModel":';
  assert.deepEqual(safeMetadata(body,stats),{});
  assert.equal(stats.bodyBytes,Buffer.byteLength(body));
  assert.equal(stats.eventCount,1);
  assert.equal(stats.jsonParseFailed,1);
  assert.equal(stats.truncatedEvents,1);
});
test('R1 SSE error event retains reason and event counts',()=>{
  const stats={};
  const meta=safeMetadata('event: error\ndata: {"type":"error","data":{"reasonCode":"provider_timeout","prompt":"private synthetic"}}\n\n',stats);
  assert.equal(meta.reasonCode,'provider_timeout');
  assert.equal(meta.prompt,undefined);
  assert.equal(stats.eventTypes.error,1);
  assert.equal(stats.jsonParseSucceeded,1);
});
test('R1 SSE body rejection is visible and redacted',async()=>{
  const result=await collectStream({text:async()=>{throw Error('body unavailable Bearer synthetic-private-value');}});
  assert.equal(result.capture.bodyReadStatus,'failed');
  assert.equal(result.capture.bodyBytes,0);
  assert.match(result.capture.error,/body unavailable/);
  assert(!result.capture.error.includes('synthetic-private-value'));
  assert.deepEqual(result.metadata,{});
});

const STUB_POLICY={invoke:(root,args)=>{
  if(args[0]==='resolve')return{verdict:'RESOLVED',mode:'api_first',selected:'api-fixture',alternates:[]};
  if(args[0]==='check')return{verdict:'OK'};
  if(args[0]==='record')return{recorded:true};
  return{verdict:'OK'};
}};
async function chatFixture(includeObserved, check, localAlternative=true, opts={}) {
  const catalog=[{id:'local-primary',modelId:'local-primary',provider:'Ollama',selectable:true},
    {id:'local-secondary',modelId:'local-secondary',provider:'Ollama',selectable:true},
    {id:'chatgpt-oauth:api-fixture',modelId:'api-fixture',provider:'openai',selectable:true}];
  if(!localAlternative)catalog.splice(1,1);
  const sentModels=[];
  const html=`<!doctype html><select id="modelSelect">
    ${catalog.map(model=>'<option value="'+model.id+'">'+model.id+'</option>').join('')}</select>
    <select id="modelSelectionMode"><option value="strict">strict</option></select>
    <select id="searchModeSelect"><option value="OFF">OFF</option><option value="FORCE_LIGHT">FORCE_LIGHT</option></select>
    <input id="useRagToggle" type="checkbox"><button id="newChatBtn">new</button>
    <textarea id="messageInput"></textarea><button id="sendBtn">send</button><button id="stopBtn" hidden>stop</button>
    <div id="modelStatus">요청 local-primary / 적용 unknown</div><div id="traceStatus">synthetic fixture</div>
    <div id="messages"></div><script>
      const select=document.querySelector('#modelSelect');
      select.addEventListener('change',()=>document.querySelector('#modelStatus').textContent='요청 '+select.value+' / 적용 unknown');
      document.querySelector('#newChatBtn').onclick=()=>document.querySelector('#messages').replaceChildren();
      document.querySelector('#sendBtn').onclick=async()=>{
        const response=await fetch('/api/chat/stream',{method:'POST',headers:{'content-type':'application/json'},
          body:JSON.stringify({model:select.value})});
        await response.text();
        const answer=document.createElement('div');
        answer.dataset.messageRole='assistant';answer.textContent='안녕하세요';
        document.querySelector('#messages').append(answer);
      };
    </script>`;
  const server=http.createServer((req,res)=>{
    if(req.url==='/api/chat/sessions/91/traces/turn_93/html?format=json'){
      res.writeHead(200,{'content-type':'application/json'});
      res.end(JSON.stringify({traceTurnId:93,diagnostics:{observedModel:'local-secondary',observedProvider:'ollama',routeId:'hash:123456789012',fallbackCount:0}}));return;
    }
    if(req.url==='/api/chat/models'){res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(catalog));return;}
    if(req.method==='POST'&&req.url==='/api/chat/stream'){
      let body='';req.on('data',chunk=>{body+=chunk;});
      req.on('end',()=>{
        const selected=JSON.parse(body).model;sentModels.push(selected);
        const meta=includeObserved?{observedModel:selected,observedProvider:'ollama',routeId:'hash:123456789012',fallbackCount:0,sessionId:91,traceTurnId:93}:{};
        res.writeHead(200,{'content-type':'text/event-stream'});
        res.end('data: '+JSON.stringify({answer:'안녕하세요',metadata:meta})+'\n\n');
      });return;
    }
    res.writeHead(200,{'content-type':'text/html'});res.end(html);
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try {
    const argv=['--base','http://127.0.0.1:'+server.address().port,
      '--only',opts.only||'C7','--max-sends','1'];
    if(opts.model!==null)argv.push('--model',opts.model||'local-primary');
    const result=await run(parseArgs(argv),{
        launch:async()=>({newContext:options=>browser.newContext(options),close:async()=>{}})
      },{policy:opts.policy||STUB_POLICY});
    result.surface='synthetic-fixture';result.realServerGenerationCalls=0;
    fs.writeFileSync(path.join(result.output,'result.json'),JSON.stringify(result,null,2));
    await check(result,sentModels);
  } finally { await new Promise(resolve=>server.close(resolve)); }
}
test('absent --model the strict runner resolves the policy model, not the page default',async()=>{
  await chatFixture(true,async(result,sentModels)=>{
    assert.deepEqual(sentModels,['chatgpt-oauth:api-fixture']);
    assert.equal(result.policy.selected,'chatgpt-oauth:api-fixture');
    assert.equal(result.results[0].requestedModel,'chatgpt-oauth:api-fixture');
    assert.equal(result.results[0].verdict,'PASS');
  },true,{policy:STUB_POLICY,only:'C1',model:null});
});
test('policy resolve failure blocks before any generation send',async()=>{
  const broken={invoke:()=>({verdict:'NO_API_CANDIDATE'})};
  await assert.rejects(
    chatFixture(true,async()=>{},true,{policy:broken,only:'C1',model:null}),
    /POLICY_RESOLVE_FAILED/);
});
test('policy check failure flips an otherwise-passing row to FAIL',async()=>{
  await chatFixture(true,async(result)=>{
    assert.equal(result.results[0].verdict,'FAIL');
    assert(result.results[0].reasons.includes('policy_check_local_before_cutoff'));
  },true,{policy:{invoke:(root,args)=>{
    if(args[0]==='check')return{verdict:'LOCAL_BEFORE_CUTOFF'};
    if(args[0]==='record')return{recorded:true};
    return{verdict:'RESOLVED',selected:'chatgpt-oauth:api-fixture',alternates:[]};
  }},only:'C1'});
});
test('C7 preserves an explicit local model lane while changing models',async()=>{
  await chatFixture(true,async(result,sentModels)=>{
    assert.deepEqual(sentModels,['local-secondary']);
    assert.equal(result.results[0].requestedModel,'local-secondary');
    assert.equal(result.results[0].verdict,'PASS');
  });
});
test('C7 requested-only model label cannot prove the observed model',async()=>{
  await chatFixture(false,async result=>{
    assert.equal(result.results[0].observedModel,null);
    assert.equal(result.results[0].verdict,'FAIL');
    assert(result.results[0].reasons.includes('observed_model_not_proven'));
  });
});
test('C7 without a second local model blocks before any generation',async()=>{
  await chatFixture(true,async(result,sentModels)=>{
    assert.deepEqual(sentModels,[]);
    assert.equal(result.sends,0);
    assert.equal(result.results[0].verdict,'BLOCKED');
    assert(result.results[0].reasons.includes('alternative_model_unavailable'));
  },false);
});
async function fixture(status,models,run){
  let posts=0,modelGets=0;
  const server=http.createServer((req,res)=>{
    if(req.method==='POST'){posts++;res.writeHead(500);res.end();return;}
    if(req.url==='/api/chat/models'){modelGets++;res.writeHead(status,{'content-type':'application/json'});res.end(JSON.stringify(models));return;}
    if(req.url==='/api/settings'){res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({OPENAI_MODEL:'fixture-global-model'}));return;}
    let file;
    if(req.url==='/settings')file=path.join(root,'main/resources/templates/settings.html');
    else if(['/js/settings-page.js','/js/chat-settings-bridge.js'].includes(req.url))file=path.join(root,'main/resources/static',req.url);
    if(file){res.writeHead(200,{'content-type':req.url.endsWith('.js')?'application/javascript':'text/html'});res.end(fs.readFileSync(file));return;}
    res.writeHead(200,{'content-type':req.url.endsWith('.js')?'application/javascript':'text/css'});res.end('');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const context=await browser.newContext();
  try{
    await context.addInitScript(()=>localStorage.setItem('awx.settings.v1.preferences',
      JSON.stringify({version:1,values:{model:'saved-model-missing'}})));
    const page=await context.newPage();
    await page.goto('http://127.0.0.1:'+server.address().port+'/settings');
    await run(page);
    assert.equal(posts,0,'browser defaults must never POST global settings');
    assert.equal(modelGets,1,'reuse catalog endpoint once');
  }finally{await context.close();await new Promise(resolve=>server.close(resolve));}
}
test('settings catalog shows provider, local/API, availability and preserves absent saved model',async()=>{
  await fixture(200,[{id:'local-fixture',provider:'Ollama',status:'installed',selectable:true},
    {id:'api-fixture',provider:'openai',status:'unknown',selectable:false}],async page=>{
    await page.waitForFunction(()=>document.querySelector('#local-model').options.length===4,null,{timeout:3000});
    const options=await page.locator('#local-model option').allTextContents();
    assert(options.some(t=>t.includes('local-fixture')&&t.includes('Ollama')&&t.includes('로컬')&&t.includes('사용 가능')));
    assert(options.some(t=>t.includes('api-fixture')&&t.includes('API')&&t.includes('미확인')));
    assert.equal(await page.locator('#local-model').inputValue(),'saved-model-missing');
  });
});
for(const [status,models,reason] of [[401,[],'권한'],[403,[],'권한'],[503,[],'실패'],[200,[],'비어'],[200,{bad:[]},'형식']]){
  test('settings catalog failure '+status+' '+reason+' is visible and keeps saved preference',async()=>{
    await fixture(status,models,async page=>{
      await page.waitForFunction(word=>document.querySelector('#model-catalog-status')?.textContent.includes(word),reason,{timeout:3000});
      assert.equal(await page.locator('#local-model').inputValue(),'saved-model-missing');
      assert.equal(await page.locator('#local-model option[value="saved-model-missing"]').count(),1);
    });
  });
}
