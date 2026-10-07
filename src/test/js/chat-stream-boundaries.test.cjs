const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('main/resources/static/js/chat.js','utf8');
function setup({type='text/event-stream', wire='', wires=null, run=null, abort=false,
  stall=false, fetchStall=false, controlledReads=false, deadline=null}={}) {
  const observed={calls:0,readers:0,cancelled:0,acks:0,rendered:[],heartbeatCleared:0,
    fetchCalls:[],stateCalls:[],cancelRequests:0,dockEvents:[],pendingReads:[],clock:0};
  const assistant={dataset:{},replaceChildren(){}};
  let responseIndex=0;
  let activeRun=run;
  const context=vm.createContext({
    TextEncoder,TextDecoder,AbortController,MAX_SSE_EVENT_UTF8_BYTES:100000,
    document:{getElementById(){return assistant;},dispatchEvent(event){observed.dockEvents.push(event);}},
    CustomEvent:class {constructor(type,options){this.type=type;this.detail=options.detail;}},
    activeStreamAssistant:null,streamController:null,activeStreamHeartbeatTimer:null,
    streamCancelRequested:false,streamRenderSuppressed:false,
    nowMs:()=>observed.clock,window:{setInterval:callback=>{observed.tick=callback;return 1;}},
    invalidatePendingSessionSelectionForTranscriptOwnership(){},syncSessionSelectionCapability(){},
    clearActiveStreamHeartbeat(){observed.heartbeatCleared++;},streamClientDeadlineMs:()=>deadline,
    STREAM_STALE_WAIT_MS:60000,
    updateOrchestrationSignalBar(){},markAssistantClientWait(){},setCoreStatus(){},
    cancelActiveStream:async()=>{observed.cancelRequests++;},
    streamClientDeadlineError:()=>Object.assign(new Error('client deadline'),{name:'DeadlineError'}),
    withChatCorrelationHeaders:x=>x,generationIdempotencyHeaders:()=>({}),streamServerBudgetHeaders:()=>({}),
    responseHeader:(response,name)=>response.headers.get(name),
    applyChatResponseHeaders(){},
    activeRunIdentitySnapshot:()=>activeRun,clearActiveRunIdentity(){activeRun=null;},
    clearActiveRunIdentityIfMatch(expected){if(activeRun===expected)activeRun=null;},
    isActiveStreamRenderTarget:()=>true,isAssistantStreamStopped:()=>false,
    assistantReasoningStates:new WeakMap(),
    renderChatEvent:(payload,element,event,finalRun)=>{
      observed.rendered.push({event,payload,finalRun});
      if(event==='token'||event==='message')
        element.dataset.ariaText=(element.dataset.ariaText||'')+context.filterAssistantReasoningChunk(payload.data,element);
    },
    recordChatTransitionDebug(){},
    acknowledgeExactRun:async()=>{observed.acks++;return true;},
    classifyChatFailure:x=>x,
    chatFailureError:meta=>Object.assign(new Error('typed failure'),{chatFailure:meta}),
    streamProtocolError:code=>Object.assign(new Error(code),{streamFailureCode:code}),
    streamAbortError:()=>Object.assign(new Error('aborted'),{name:'AbortError'}),
    sseFailureCode:payload=>payload.code,
    fetch:async(url,options={})=>{
      observed.calls++;
      observed.fetchCalls.push({url,options});
      if(fetchStall)return new Promise(()=>{});
      const responseWire=Array.isArray(wires) ? wires[responseIndex++] : wire;
      let sent=false;
      return {ok:true,status:200,redirected:false,headers:{get:()=>type},
        body:{cancel:async()=>{observed.cancelled++;},getReader:()=>{observed.readers++;return {
          read:async()=>{if(abort) context.streamController.abort();
            if(controlledReads)return new Promise(resolve=>observed.pendingReads.push(resolve));
            if(sent || !responseWire)return stall ? new Promise(()=>{}) : {done:true};
            sent=true;return {done:false,value:new TextEncoder().encode(responseWire)};}
        };}}
      };
    }
  });
  vm.runInContext(source.slice(source.indexOf('function chatTraceRequestUrl('),source.indexOf('function validateSessionDetail(')),context);
  vm.runInContext(source.slice(source.indexOf('function filterAssistantReasoningChunk('),source.indexOf('function appendTextWithBreaks(')),context);
  vm.runInContext(source.slice(source.indexOf('function sseFieldValue('),source.indexOf('function streamClientDeadlineError(')),context);
  vm.runInContext(source.slice(source.indexOf('async function streamChat('),source.indexOf('function dispatchBrainStateSignal(')),context);
  return {context,observed,assistant,send:()=>context.streamChat({model:'synthetic'},'assistant')};
}
const event=(name,data)=>'event: '+name+'\ndata: '+JSON.stringify(data)+'\n\n';
const timeoutRun={sessionId:719,runToken:'synthetic-timeout-run'};
function assertTransportDetached(s,expectedRun=timeoutRun) {
  assert.equal(s.observed.cancelRequests,0,'transport loss must not cancel the admitted run');
  assert.equal(s.context.streamController.signal.aborted,true);
  assert.equal(s.context.activeRunIdentitySnapshot(),expectedRun,'exact recovery identity must survive');
  assert.equal(s.observed.acks,0,'a detached transport cannot acknowledge a final answer');
  assert.equal(s.observed.calls,1,'timeout must not start another generation');
}
function setupExactRecovery({sessionId=711,runToken='synthetic-run',replaceDuringState=false,traceSignal=null}={}) {
  const s=setup({wires:[
    event('session',{sessionId,data:runToken,traceSignal}),
    event('final',{answer:'synthetic-final'})
  ]});
  const {context,observed,assistant}=s;
  const storage=new Map();
  context.window.sessionStorage={
    setItem:(key,value)=>storage.set(key,value),
    getItem:(key)=>storage.get(key)||null,
    removeItem:(key)=>storage.delete(key)
  };
  context.window.crypto={randomUUID:()=> 'synthetic-request-id'};
  context.state={currentSessionId:null};
  context.withCsrfHeaders=(headers)=>headers;
  context.clearAssistantPendingPlaceholder=()=>{};
  context.setCoreStatus=()=>{};
  context.updateOrchestrationSignalBar=()=>{};
  context.dispatchBrainStateSignal=()=>{};
  context.sessionTraceLabel=(sid)=>'trace:'+sid;
  observed.sessionListRefreshes=[];
  context.refreshSessionList=(reason)=>{observed.sessionListRefreshes.push(reason);return Promise.resolve(true);};
  context.apiCall=async(url,options)=>{
    observed.stateCalls.push({url,options});
    return {json:async()=>{
      if(replaceDuringState)
        vm.runInContext('rememberActiveRunIdentity('+sessionId+', "replacement-run")',context);
      return {persisted:false,runStatus:'running',attachable:true};
    }};
  };
  vm.runInContext('let activeSessionId=null; let activeRunToken=null; let activeRunGeneration=0; let sessionSelectionGeneration=0; let pendingStopBeforeToken=null; const ACTIVE_RUN_STORAGE_KEY="chat.activeRun"; const CURRENT_SESSION_STORAGE_KEY="chat.currentSessionId";',context);
  vm.runInContext(source.slice(source.indexOf('function normalizeSessionIdValue('),
    source.indexOf('function restoreCurrentSessionId(')),context);
  vm.runInContext(source.slice(source.indexOf('function sessionIdFromPayload('),
    source.indexOf('function stripAssistantReasoningBlocks(')),context);
  vm.runInContext(source.slice(source.indexOf('function copyHeaders('),
    source.indexOf('function responseHeader(')),context);
  vm.runInContext(source.slice(source.indexOf('function renderChatEvent('),
    source.indexOf('async function requestServerCancel(')),context);
  const productionRender=vm.runInContext('renderChatEvent',context);
  context.__renderTransportEvent=(payload,bubble,type)=>{
    if(type==='session')return productionRender(payload,bubble,type);
    observed.rendered.push({event:type,payload});
  };
  vm.runInContext('renderChatEvent=__renderTransportEvent',context);
  vm.runInContext(source.slice(source.indexOf('async function recoverExactRunAfterTransportLoss('),
    source.indexOf('function reconcileRestoredSessionTranscript(')),context);
  return s;
}
test('HTTP 200 HTML is cancelled before SSE parsing and never retried',async()=>{
  const s=setup({type:'text/html',wire:'<html>login</html>'});
  await assert.rejects(s.send(),e=>e.chatFailure.protocolCode==='unexpected_content_type');
  assert.equal(s.observed.calls,1);assert.equal(s.observed.readers,0);assert.equal(s.observed.cancelled,1);
});
test('partial answer without terminal event is preserved and never called complete',async()=>{
  const s=setup({wire:event('token',{data:'partial'})});
  await assert.rejects(s.send(),e=>e.chatFailure.protocolCode==='stream_incomplete');
  assert.equal(s.observed.rendered.length,1);assert.equal(s.observed.rendered[0].event,'token');
  assert.equal(s.observed.calls,1);assert.equal(s.observed.acks,0);
});
test('EOF with known run retains exact recovery identity without another generation',async()=>{
  const run={sessionId:7,runToken:'synthetic-run'};
  const s=setup({wire:event('token',{data:'partial'}),run});
  await assert.rejects(s.send(),e=>e.message==='exact_run_transport_eof'&&e.exactRun===run);
  assert.equal(s.observed.calls,1);assert.equal(s.observed.acks,0);
});
test('ordinary session carries exact hashes to dock before generation completes',async()=>{
  const requestIdHash='hash:012345abcdef', traceIdHash='hash:fedcba987654';
  const s=setupExactRecovery({sessionId:713,runToken:'run-713',
    traceSignal:{requestIdHash,traceIdHash}});
  await assert.rejects(s.send(),e=>e.message==='exact_run_transport_eof');
  assert.deepEqual(s.observed.sessionListRefreshes,['session']);
  assert.deepEqual(s.observed.dockEvents.filter(event=>event.detail.requestIdHash)
    .map(event=>({requestIdHash:event.detail.requestIdHash,traceIdHash:event.detail.traceIdHash})),
    [{requestIdHash,traceIdHash}]);
});
test('final is acknowledged once and later events in the chunk are not rendered',async()=>{
  const s=setup({wire:event('final',{answer:'complete'})+event('token',{data:'late'}),
    run:{sessionId:7,runToken:'synthetic-run'}});
  await s.send();
  assert.equal(s.observed.acks,1);assert.equal(s.observed.rendered.length,1);
  assert.equal(s.observed.rendered[0].event,'final');assert.equal(s.observed.calls,1);
  assert.equal(s.observed.rendered[0].finalRun.runToken,'synthetic-run');
});
test('terminal failure preserves received tokens and blocks blind retry or ACK',async()=>{
  const s=setup({wire:event('token',{data:'partial'})+event('error',{code:'provider_unauthorized'})});
  await assert.rejects(s.send(),e=>e.chatFailure.streamCode==='provider_unauthorized');
  assert.equal(s.observed.rendered.length,1);assert.equal(s.observed.calls,1);assert.equal(s.observed.acks,0);
  assert.equal(s.observed.dockEvents.filter(event=>event.detail.complete===true).length,1);
});
test('local cancellation rejects later bytes and clears heartbeat',async()=>{
  const s=setup({wire:event('final',{answer:'late'}),abort:true});
  await assert.rejects(s.send(),{name:'AbortError'});
  assert.equal(s.observed.rendered.length,0);assert.equal(s.observed.acks,0);
  assert.equal(s.observed.heartbeatCleared,1);
  assert.equal(s.observed.dockEvents.filter(event=>event.detail.complete===true).length,1);
});
test('known session EOF checks exact state then attaches the same run without new generation',async()=>{
  const s=setupExactRecovery({sessionId:711,runToken:'run-711-eof'});
  await assert.rejects(s.send(),e=>e.message==='exact_run_transport_eof'
    && e.exactRun?.runToken==='run-711-eof');
  const expected=vm.runInContext('activeRunIdentitySnapshot()',s.context);
  assert.equal(await s.context.recoverExactRunAfterTransportLoss(expected,'assistant'),true);
  assert.deepEqual(s.observed.fetchCalls.map(call=>call.url),
    ['/api/chat/stream','/api/chat/stream?attach=true']);
  assert.equal(s.observed.stateCalls.length,1);
  assert.equal(s.observed.stateCalls[0].url,'/api/chat/state?sessionId=711');
  assert.equal(s.observed.stateCalls[0].options.headers['x-chat-run-token'],'run-711-eof');
  const attach=s.observed.fetchCalls[1].options;
  assert.equal(attach.method,'POST');
  assert.equal(attach.headers['x-chat-run-token'],'run-711-eof');
  assert.equal(JSON.parse(attach.body).attach,true);
  assert.equal(JSON.parse(attach.body).runToken,'run-711-eof');
});
test('a replaced run cannot attach after an older state response',async()=>{
  const s=setupExactRecovery({sessionId:712,runToken:'run-712-old',replaceDuringState:true});
  await assert.rejects(s.send(),e=>e.message==='exact_run_transport_eof');
  const expected=vm.runInContext('activeRunIdentitySnapshot()',s.context);
  assert.equal(await s.context.recoverExactRunAfterTransportLoss(expected,'assistant'),false);
  assert.deepEqual(s.observed.fetchCalls.map(call=>call.url),['/api/chat/stream']);
  assert.equal(s.observed.stateCalls.length,1);
  assert.equal(vm.runInContext('activeRunIdentitySnapshot().runToken',s.context),'replacement-run');
});

test('NW client idle and HTTP body budgets use the rendered positive policy or legacy fallback',()=>{
  let admitted=240000;
  const context=vm.createContext({document:{querySelector:()=>({getAttribute:()=>String(admitted)})},
    STREAM_SERVER_EVIDENCE_BUDGET_MS:120000,STREAM_SERVER_WEB_BUDGET_MS:30000,
    STREAM_SERVER_MODEL_BUDGET_MS:90000});
  vm.runInContext(source.slice(source.indexOf('function streamClientDeadlineMs('),
    source.indexOf('function streamServerBudgetHeaders(')),context);
  for(const payload of [{},{useRag:true},{useWebSearch:true},{searchMode:'FORCE_DEEP'}]) {
    assert.equal(context.streamClientDeadlineMs(payload),240000);
    assert.equal(context.streamServerBudgetMs(payload),240000);
  }
  admitted=1500;
  assert.equal(context.streamClientDeadlineMs({}),1500);
  assert.equal(context.streamServerBudgetMs({}),1500);
  for(admitted of [null,0,-1,1.5,'invalid']) {
    assert.equal(context.streamClientDeadlineMs({}),600000);
    assert.equal(context.streamServerBudgetMs({}),600000);
  }
});

// The transport has a byte-idle budget; detaching it leaves the server run recoverable.
test('NW status and blank tokens still detach after the byte-idle budget',async()=>{
  const s=setup({wire:event('status',{code:'working'})+event('token',{data:'   '}),
    stall:true,deadline:30000,run:timeoutRun});
  const pending=s.send();
  const rejected=assert.rejects(pending,{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=5000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  assert.equal(s.assistant.dataset.streamAnswerSeen,'false');
  assert.equal(s.assistant.dataset.streamFirstAnswerMs,undefined);
  s.observed.clock=30000;s.observed.tick();
  assertTransportDetached(s);
  await rejected;
});

test('NW a real answer delta does not exempt a stalled transport from the idle budget',async()=>{
  const s=setup({wire:event('token',{data:'answer delta'}),stall:true,deadline:30000,run:timeoutRun});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=5000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  s.observed.clock=30000;s.observed.tick();
  assertTransportDetached(s);
  await rejected;
});

test('NW a stalled fetch before run identity detaches at the first-signal deadline',async()=>{
  const s=setup({fetchStall:true,deadline:30000});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=5000;s.observed.tick();
  assertTransportDetached(s,null);
  await rejected;
});

// Reasoning is not a visible answer; a subsequent silent transport still detaches.
test('NW hidden reasoning stays hidden and does not exempt an idle transport',async()=>{
  const s=setup({wire:event('token',{data:'<think>internal reasoning</think>'}),stall:true,deadline:30000,run:timeoutRun});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  assert.equal(s.assistant.dataset.ariaText,'');
  s.observed.clock=5000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  assert.equal(s.assistant.dataset.streamAnswerSeen,'false');
  assert.equal(s.assistant.dataset.streamFirstAnswerMs,undefined);
  s.observed.clock=30000;s.observed.tick();
  assertTransportDetached(s);
  await rejected;
});

test('WP6 live SSE progress permits a first visible body at eight seconds',async()=>{
  const s=setup({controlledReads:true,deadline:30000,
    run:{sessionId:714,runToken:'synthetic-run-714'}});
  const outcome=s.send().then(()=>({ok:true}),error=>({error}));
  const deliver=async wire=>{
    assert.equal(s.observed.pendingReads.length,1);
    s.observed.pendingReads.shift()({done:false,value:new TextEncoder().encode(wire)});
    await new Promise(setImmediate);
  };
  await new Promise(setImmediate);
  s.observed.clock=1000;
  await deliver(event('status',{code:'working'}));
  s.observed.clock=4000;
  await deliver(event('status',{code:'heartbeat'}));
  s.observed.clock=5000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0,'live SSE must not cancel before the eight-second body');
  s.observed.clock=8000;
  await deliver(event('token',{data:'visible answer'}));
  assert.equal(s.assistant.dataset.ariaText,'visible answer');
  assert.equal(s.assistant.dataset.streamSseStartMs,'0');
  assert.equal(s.assistant.dataset.streamFirstAnswerMs,'8000');
  assert.equal(s.assistant.dataset.streamHttpStatus,'200');
  s.observed.clock=8100;
  await deliver(event('final',{answer:'visible answer'}));
  assert.equal((await outcome).ok,true);
  assert.equal(s.observed.acks,1);
  assert.equal(s.observed.cancelRequests,0);
});

test('WP6 validated SSE headers detach after the byte-idle budget',async()=>{
  const s=setup({stall:true,deadline:30000,run:timeoutRun});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=5000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  s.observed.clock=30000;s.observed.tick();
  assertTransportDetached(s);
  assert.equal(s.assistant.dataset.streamFirstAnswerMs,undefined);
  await rejected;
});

test('WP6 delayed SSE headers cannot retroactively evade the five-second first-signal limit',async()=>{
  const s=setup({wire:event('token',{data:'late'}),deadline:30000,run:timeoutRun});
  const originalFetch=s.context.fetch;
  s.context.fetch=async(...args)=>{
    s.observed.clock=6000;
    return originalFetch(...args);
  };
  await assert.rejects(s.send(),{name:'DeadlineError'});
  assertTransportDetached(s);
  assert.equal(s.observed.rendered.length,0);
});

test('WP6 a shorter rendered policy also bounds a silent SSE transport',async()=>{
  const s=setup({wire:event('status',{code:'working'}),stall:true,deadline:1500,run:timeoutRun});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=1499;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  s.observed.clock=1500;s.observed.tick();
  assertTransportDetached(s);
  await rejected;
});

test('live status, blank and reasoning bytes renew liveness but silence still detaches without cancel',async()=>{
  const s=setup({controlledReads:true,deadline:1500,run:timeoutRun});
  const outcome=s.send().then(()=>({ok:true}),error=>({error}));
  await new Promise(setImmediate);
  const wires=[event('status',{code:'working'}),event('token',{data:'   '}),
    event('token',{data:'<think>internal</think>'})];
  for(let i=0;i<wires.length;i++) {
    s.observed.clock=(i+1)*1000;
    s.observed.pendingReads.shift()({done:false,value:new TextEncoder().encode(wires[i])});
    await new Promise(setImmediate);
    s.observed.tick();
    assert.equal(s.context.streamController.signal.aborted,false);
    assert.equal(s.assistant.dataset.streamAnswerSeen,'false');
    assert.equal(s.assistant.dataset.streamFirstAnswerMs,undefined);
  }
  s.observed.clock=4500;s.observed.tick();
  assert.equal((await outcome).error.name,'DeadlineError');
  assertTransportDetached(s);
  assert.equal(s.observed.heartbeatCleared,1);
});

test('explicit concurrent user Stop posts the exact cancel once after transport detach',async()=>{
  const s=setup({stall:true,deadline:1500,run:timeoutRun});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=1500;s.observed.tick();await rejected;
  assertTransportDetached(s);
  Object.assign(s.context,{streamCancelInFlight:null,activeSessionId:timeoutRun.sessionId,
    activeRunToken:timeoutRun.runToken,pendingStopBeforeToken:null,dom:{stopBtn:{}},
    normalizeRunToken:x=>x,sameActiveRunIdentity:expected=>s.context.activeRunIdentitySnapshot()===expected,
    apiCall:async(url,options)=>{s.observed.stateCalls.push({url,options});
      await new Promise(setImmediate);return {json:async()=>({cancelled:true})};},
    applySuccessfulStreamCancel:(expected,options)=>{s.context.streamCancelRequested=true;
      s.context.streamRenderSuppressed=true;options.controller.abort();
      s.context.clearActiveRunIdentityIfMatch(expected);return true;}
  });
  vm.runInContext(source.slice(source.indexOf('async function requestServerCancel('),
    source.indexOf('async function acknowledgeExactRun(')),s.context);
  s.context.requestServerCancelWithTimeout=(sid,token)=>s.context.requestServerCancel(sid,token);
  vm.runInContext(source.slice(source.indexOf('async function cancelActiveStream('),
    source.indexOf('async function waitForPendingStreamCancel(')),s.context);
  assert.deepEqual(await Promise.all([s.context.cancelActiveStream(),s.context.cancelActiveStream()]),[true,true]);
  assert.equal(s.observed.stateCalls.length,1);
  const cancel=s.observed.stateCalls[0];
  assert.equal(cancel.url,'/api/chat/cancel');assert.equal(cancel.options.method,'POST');
  assert.deepEqual(JSON.parse(cancel.options.body),timeoutRun);
  assert.equal(s.context.activeRunIdentitySnapshot(),null);
  assert.equal(s.context.streamRenderSuppressed,true);
  assert.equal(s.observed.acks,0);
});

test('submission retains its settings and context while previous cancellation is pending',async()=>{
  const harness=fs.readFileSync('scripts/chat_ui_stream_contract_tests.js','utf8');
  const fixtureEnd=harness.indexOf('const script = fs.readFileSync(');
  assert(fixtureEnd>=0,'repository DOM fixture boundary missing');
  for(const mode of ['strict','preferred','auto']) {
    const scenario=`
      for(const id of ['modelSelectionMode','executionModeSelect','googleSearchRescueToggle',
        'attachmentGraphConsent','contextPreparationRequested','contextUsageText',
        'memoryCompressionText','contextPercent','contextEvidenceText']) elements.set(id,fakeElement(id));
      const gauge=fakeElement('contextGauge'); gauge.style.setProperty=()=>{};
      elements.set('contextGauge',gauge);
      context.document.addEventListener=()=>{};
      vm.runInContext(productSource,context,{filename:'chat.js'});
      context.__submittedMode=${JSON.stringify(mode)};
      return vm.runInContext(
        "(async()=>{let releaseCancel; const pending=new Promise(resolve=>releaseCancel=resolve); "+
        "waitForPendingStreamCancel=()=>pending; let sent; streamChat=async payload=>{sent=JSON.parse(JSON.stringify(payload));}; "+
        "state.currentSessionId=712; pendingAttachments.push({id:41}); "+
        "dom.modelSelect.value='synthetic-model-A'; dom.modelSelectionMode.value=__submittedMode; "+
        "dom.executionMode.value='AUTO'; dom.searchModeSelect.value='OFF'; dom.useRag.checked=false; "+
        "$('googleSearchRescueToggle').checked=false; $('googleSearchRescueToggle').dataset.rescueExplicit='true'; $('attachmentGraphConsent').checked=true; "+
        "$('contextPreparationRequested').checked=true; "+
        "const sending=sendMessageUnlocked('synthetic submitted question'); "+
        "dom.modelSelect.value='synthetic-model-B'; dom.modelSelectionMode.value='preferred'; "+
        "dom.executionMode.value='STRIKE'; dom.searchModeSelect.value='FORCE_DEEP'; dom.useRag.checked=true; "+
        "$('googleSearchRescueToggle').checked=true; $('attachmentGraphConsent').checked=false; "+
        "$('contextPreparationRequested').checked=false; state.currentSessionId=713; "+
        "pendingAttachments[0].id=42; releaseCancel(); await sending; return sent;})()",context);
    `;
    const sent=await vm.runInNewContext(harness.slice(0,fixtureEnd)+
      '\n(async()=>{'+scenario+'})()',
      {require,console,process,Buffer,TextEncoder,TextDecoder,URL,URLSearchParams,AbortController,
        setTimeout,clearTimeout,setInterval,clearInterval,productSource:source,
        __dirname:require('node:path').resolve('scripts')},
      {filename:'submit-settings-snapshot-regression.cjs',timeout:10000});
    assert.deepEqual(JSON.parse(JSON.stringify(sent)),{
      message:'synthetic submitted question',question:'synthetic submitted question',
      model:mode==='auto'?'llmrouter.auto':'synthetic-model-A',
      strictModelSelection:mode==='strict',useRag:false,useWebSearch:false,
      googleSearchRescueEnabled:false,searchMode:'OFF',executionMode:'AUTO',
      sessionId:712,attachmentIds:[41],attachmentGraphConsent:true,contextPreparationRequested:true
    },`submitted ${mode} settings changed during cancellation`);
  }
});

test('WP6 user cancellation after SSE start blocks a late answer without ACK',async()=>{
  const s=setup({controlledReads:true,deadline:30000,
    run:{sessionId:715,runToken:'synthetic-run-715'}});
  const rejected=assert.rejects(s.send(),{name:'AbortError'});
  await new Promise(setImmediate);
  s.observed.clock=6000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  s.context.streamController.abort();
  s.observed.pendingReads.shift()({done:false,
    value:new TextEncoder().encode(event('token',{data:'late answer'}))});
  await rejected;
  assert.equal(s.observed.rendered.length,0);
  assert.equal(s.observed.acks,0);
  assert.equal(s.observed.heartbeatCleared,1);
});

test('WP-U final provenance belongs to its answer and never borrows global evidence',()=>{
  // Reuse the existing DOM fixture, but execute the complete product renderer.
  // The transport setup above stubs final rendering and cannot prove this contract.
  const harness=fs.readFileSync('scripts/chat_ui_stream_contract_tests.js','utf8');
  const sourceStart=harness.indexOf('const script = fs.readFileSync(');
  const sourceEnd=harness.indexOf('\n',sourceStart);
  assert(sourceStart>=0 && sourceEnd>sourceStart,'repository DOM fixture boundary missing');
  const scenario=`
    const wrapperA=fakeElement('synthetic-message-A');
    const assistantA=fakeElement('synthetic-answer-A');
    wrapperA.appendChild(assistantA);
    elements.get('chatWindow').appendChild(wrapperA);
    context.__provenanceA=assistantA;
    vm.runInContext("state.currentSessionId=711; rememberActiveRunIdentity(711,'synthetic-run-A'); " +
      "state.latestEvidenceRailItems=[{marker:'W9',title:'POISON_GLOBAL_SOURCE',source:'https://poison.example.test/'}]; " +
      "renderChatEvent({type:'final',sessionId:711,traceTurnId:891701," +
      "data:'Synthetic answer A [W1]',evidence:[{marker:'W1',title:'OWN_SOURCE_A',source:'https://a.example.test/'}]}," +
      "globalThis.__provenanceA,'final',activeRunIdentitySnapshot())",context);
    assert(assistantA.dataset.ariaText.includes('Synthetic answer A'),'final answer body missing');
    const panelA=wrapperA.querySelector('[data-answer-provenance]');
    assert(panelA,'WP_U0_MISSING: accepted final with its own evidence has no answer provenance');
    assert(nodeText(panelA).includes('OWN_SOURCE_A'),'answer A own source missing');
    assert(!nodeText(panelA).includes('POISON_GLOBAL_SOURCE'),'answer A borrowed global evidence');
    assert(context.window.ChatEvidenceGraph.view(assistantA).status==='ready','own evidence was not accepted');
    vm.runInContext('clearActiveRunIdentity()',context);
    assert(context.window.ChatEvidenceGraph.view(assistantA).status==='ready','ACK cleanup erased final evidence');
    const wrapperB=fakeElement('synthetic-message-B');
    const assistantB=fakeElement('synthetic-answer-B');
    wrapperB.appendChild(assistantB);
    elements.get('chatWindow').appendChild(wrapperB);
    context.__provenanceB=assistantB;
    vm.runInContext("rememberActiveRunIdentity(711,'synthetic-run-B'); " +
      "renderChatEvent({type:'final',sessionId:711,traceTurnId:891702," +
      "data:'POISON_GLOBAL_SOURCE at poison.example.test is only answer text',evidence:[]}," +
      "globalThis.__provenanceB,'final',activeRunIdentitySnapshot())",context);
    assert(assistantB.dataset.ariaText.includes('only answer text'),'answer B body missing');
    const panelB=wrapperB.querySelector('[data-answer-provenance]');
    assert(panelB,'answer B empty provenance state missing');
    assert(!nodeText(panelB).includes('POISON_GLOBAL_SOURCE'),'answer text was promoted to evidence');
    assert(!nodeText(panelB).includes('OWN_SOURCE_A'),'answer B borrowed answer A evidence');
    assert(nodeText(panelA).includes('OWN_SOURCE_A'),'answer B replaced answer A snapshot');
    assert(!nodeText(panelA).includes('synthetic-run-A'),'raw run identity leaked to provenance DOM');
    const wrapperC=fakeElement('synthetic-message-C');
    const assistantC=fakeElement('synthetic-answer-C');
    wrapperC.appendChild(assistantC);
    elements.get('chatWindow').appendChild(wrapperC);
    context.__provenanceC=assistantC;
    vm.runInContext("rememberActiveRunIdentity(711,'synthetic-run-C'); state.responseTraceId=891703; "+
      "renderChatEvent({type:'final',sessionId:711,data:'Answer without terminal trace',evidence:[]},"+
      "globalThis.__provenanceC,'final',activeRunIdentitySnapshot())",context);
    assert(assistantC.dataset.ariaText.includes('without terminal trace'),'missing trace suppressed answer');
    assert(context.window.ChatEvidenceGraph.view(assistantC).status==='unavailable','global trace was borrowed');
    vm.runInContext("markAssistantStreamStopped(globalThis.__provenanceB); "+
      "renderChatEvent({type:'final',sessionId:711,traceTurnId:891702,data:'Late cancelled answer',evidence:[]},"+
      "globalThis.__provenanceB,'final',activeRunIdentitySnapshot())",context);
    assert(context.window.ChatEvidenceGraph.view(assistantB).status==='unavailable','stop did not revoke evidence');
    assert(!assistantB.dataset.ariaText.includes('Late cancelled answer'),'late final bypassed stop latch');
  `;
  vm.runInNewContext(harness.slice(0,sourceEnd)+
    '\ncontext.document.addEventListener=()=>{};\ncontext.window.document=context.document;\n'+
    'vm.runInContext(fs.readFileSync("main/resources/static/js/chat-evidence-graph.js","utf8"),context);\n'+
    'vm.runInContext(script,context,{filename:"chat.js"});\n'+scenario,
    {require,console,process,Buffer,TextEncoder,TextDecoder,URL,URLSearchParams,AbortController,
      setTimeout,clearTimeout,setInterval,clearInterval,__dirname:require('node:path').resolve('scripts')},
    {filename:'answer-provenance-regression.cjs',timeout:10000});
});


test('terminal observation never promotes requested model and retains legacy modes',()=>{
  const harness=fs.readFileSync('scripts/chat_ui_stream_contract_tests.js','utf8');
  const fixtureEnd=harness.indexOf('const script = fs.readFileSync(');
  assert(fixtureEnd>=0,'repository DOM fixture boundary missing');
  const cases=[
    {name:'missing',payload:{modelUsed:'synthetic-requested',observedModel:null,
      observedReason:'response_model_missing'},rail:'응답 UNKNOWN',mode:'chat'},
    {name:'actual-alias',payload:{modelUsed:'synthetic-requested',observedModel:'actual/alias:v2'},
      rail:'응답 actual/alias:v2',mode:'chat'},
    {name:'snake-alias',payload:{modelUsed:'synthetic-requested',observed_model:'actual/snake:v2'},
      rail:'응답 actual/snake:v2',mode:'chat'},
    {name:'legacy',payload:{modelUsed:'synthetic-legacy'},rail:'응답 synthetic-legacy',mode:'chat'},
    {name:'missing-history',payload:{modelUsed:'history:fallback:recent',observedModel:null,
      observedReason:'response_model_missing'},rail:'응답 UNKNOWN',mode:'HISTORY_RECENT'},
    {name:'legacy-history',payload:{modelUsed:'history:fallback:recent'},
      rail:'응답 recent history',mode:'HISTORY_RECENT'}
  ];
  for(const fixture of cases){
    const program=`
      context.document.addEventListener=()=>{};
      vm.runInContext(productSource,context,{filename:'chat.js'});
      const wrapper=fakeElement('synthetic-observation-wrapper');
      const assistant=fakeElement('synthetic-observation-answer');
      wrapper.appendChild(assistant);
      context.__observationAssistant=assistant;
      context.__observationPayload={type:'final',data:'synthetic answer body',ragUsed:false,
        ...fixture.payload};
      context.__observedModes=[];
      vm.runInContext("state.currentSessionId=null; state.responseModelUsed='synthetic-header-fallback'; "+
        "dom.modelSelect.value='synthetic-requested'; "+
        "const originalPersistMode=persistAnswerModeBadge; "+
        "persistAnswerModeBadge=(sid,mode)=>{globalThis.__observedModes.push(mode); return originalPersistMode(sid,mode);}; "+
        "renderChatEvent(globalThis.__observationPayload,globalThis.__observationAssistant,'final')",context);
      const rail=elements.get('modelStatus').textContent;
      if(fixture.rail)assert(rail===fixture.rail,fixture.name+': '+rail);
      assert(context.__observedModes.at(-1)===fixture.mode,fixture.name+': answer mode changed');
      assert(elements.get('modelSelect').value==='synthetic-requested','selected model changed');
      assert(assistant.dataset.ariaText==='synthetic answer body','answer body changed');
    `;
    vm.runInNewContext(harness.slice(0,fixtureEnd)+program,
      {require,console,process,Buffer,TextEncoder,TextDecoder,URL,URLSearchParams,AbortController,
        setTimeout,clearTimeout,setInterval,clearInterval,productSource:source,fixture,
        __dirname:require('node:path').resolve('scripts')},
      {filename:'terminal-observation-regression.cjs',timeout:10000});
  }
});
