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
    activeRunIdentitySnapshot:()=>run,clearActiveRunIdentity(){},clearActiveRunIdentityIfMatch(){},
    isActiveStreamRenderTarget:()=>true,isAssistantStreamStopped:()=>false,
    assistantReasoningStates:new WeakMap(),
    renderChatEvent:(payload,element,event)=>{
      observed.rendered.push({event,payload});
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

test('NW ordinary client and server budgets never exceed 30 seconds or a shorter admission',()=>{
  let admitted=240000;
  const context=vm.createContext({document:{querySelector:()=>({getAttribute:()=>String(admitted)})},
    STREAM_SERVER_EVIDENCE_BUDGET_MS:120000,STREAM_SERVER_WEB_BUDGET_MS:30000,
    STREAM_SERVER_MODEL_BUDGET_MS:90000});
  vm.runInContext(source.slice(source.indexOf('function streamClientDeadlineMs('),
    source.indexOf('function streamServerBudgetHeaders(')),context);
  for(const payload of [{},{useRag:true},{useWebSearch:true},{searchMode:'FORCE_DEEP'}]) {
    assert.equal(context.streamClientDeadlineMs(payload),30000);
    assert.equal(context.streamServerBudgetMs(payload),30000);
  }
  admitted=1500;
  assert.equal(context.streamClientDeadlineMs({}),1500);
  assert.equal(context.streamServerBudgetMs({}),1500);
});

// WP6 intent change: live SSE status/blank tokens can wait, but never renew the total deadline.
test('NW live SSE status and blank tokens remain inside the original total deadline',async()=>{
  const s=setup({wire:event('status',{code:'working'})+event('token',{data:'   '}),
    stall:true,deadline:30000});
  const pending=s.send();
  const rejected=assert.rejects(pending,{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=5000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  assert.equal(s.assistant.dataset.streamAnswerSeen,'false');
  assert.equal(s.assistant.dataset.streamFirstAnswerMs,undefined);
  s.observed.clock=30000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,1);
  assert.equal(s.context.streamController.signal.aborted,true);
  await rejected;
});

test('NW a real answer delta permits progress only within the original total deadline',async()=>{
  const s=setup({wire:event('token',{data:'answer delta'}),stall:true,deadline:30000});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=5000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  s.observed.clock=30000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,1);
  assert.equal(s.context.streamController.signal.aborted,true);
  await rejected;
});

test('NW a stalled fetch before run identity is cancelled at the first answer deadline',async()=>{
  const s=setup({fetchStall:true,deadline:30000});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=5000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,1);
  assert.equal(s.context.streamController.signal.aborted,true);
  await rejected;
});

// WP6 intent change: reasoning is not a visible answer, while validated SSE proves liveness.
test('NW hidden reasoning is not a visible answer and cannot renew the total deadline',async()=>{
  const s=setup({wire:event('token',{data:'<think>internal reasoning</think>'}),stall:true,deadline:30000});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  assert.equal(s.assistant.dataset.ariaText,'');
  s.observed.clock=5000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  assert.equal(s.assistant.dataset.streamAnswerSeen,'false');
  assert.equal(s.assistant.dataset.streamFirstAnswerMs,undefined);
  s.observed.clock=30000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,1);
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

test('WP6 validated SSE headers wait only until the original total deadline',async()=>{
  const s=setup({stall:true,deadline:30000});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=5000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  s.observed.clock=30000;s.observed.tick();
  assert.equal(s.observed.cancelRequests,1);
  assert.equal(s.assistant.dataset.streamFirstAnswerMs,undefined);
  await rejected;
});

test('WP6 delayed SSE headers cannot retroactively evade the five-second first-signal limit',async()=>{
  const s=setup({wire:event('token',{data:'late'}),deadline:30000});
  const originalFetch=s.context.fetch;
  s.context.fetch=async(...args)=>{
    s.observed.clock=6000;
    return originalFetch(...args);
  };
  await assert.rejects(s.send(),{name:'DeadlineError'});
  assert.equal(s.observed.cancelRequests,1);
  assert.equal(s.observed.rendered.length,0);
});

test('WP6 live progress does not extend a shorter admitted total budget',async()=>{
  const s=setup({wire:event('status',{code:'working'}),stall:true,deadline:1500});
  const rejected=assert.rejects(s.send(),{name:'DeadlineError'});
  await new Promise(setImmediate);
  s.observed.clock=1499;s.observed.tick();
  assert.equal(s.observed.cancelRequests,0);
  s.observed.clock=1500;s.observed.tick();
  assert.equal(s.observed.cancelRequests,1);
  assert.equal(s.context.streamController.signal.aborted,true);
  await rejected;
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
