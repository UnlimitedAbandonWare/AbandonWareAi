const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('main/resources/static/js/chat.js','utf8');
function setup({type='text/event-stream', wire='', run=null, abort=false}={}) {
  const observed={calls:0,readers:0,cancelled:0,acks:0,rendered:[],heartbeatCleared:0};
  const assistant={};
  let sent=false;
  const context=vm.createContext({
    TextEncoder,TextDecoder,AbortController,MAX_SSE_EVENT_UTF8_BYTES:100000,
    document:{getElementById(){return assistant;}},
    activeStreamAssistant:null,streamController:null,activeStreamHeartbeatTimer:null,
    streamCancelRequested:false,streamRenderSuppressed:false,
    nowMs:()=>0,window:{setInterval:()=>1},
    invalidatePendingSessionSelectionForTranscriptOwnership(){},syncSessionSelectionCapability(){},
    clearActiveStreamHeartbeat(){observed.heartbeatCleared++;},streamClientDeadlineMs:()=>null,
    withChatCorrelationHeaders:x=>x,generationIdempotencyHeaders:()=>({}),streamServerBudgetHeaders:()=>({}),
    responseHeader:(response,name)=>response.headers.get(name),
    applyChatResponseHeaders(){},
    activeRunIdentitySnapshot:()=>run,clearActiveRunIdentity(){},clearActiveRunIdentityIfMatch(){},
    isActiveStreamRenderTarget:()=>true,isAssistantStreamStopped:()=>false,
    renderChatEvent:(payload,element,event)=>observed.rendered.push({event,payload}),
    recordChatTransitionDebug(){},
    acknowledgeExactRun:async()=>{observed.acks++;return true;},
    classifyChatFailure:x=>x,
    chatFailureError:meta=>Object.assign(new Error('typed failure'),{chatFailure:meta}),
    streamProtocolError:code=>Object.assign(new Error(code),{streamFailureCode:code}),
    streamAbortError:()=>Object.assign(new Error('aborted'),{name:'AbortError'}),
    sseFailureCode:payload=>payload.code,
    fetch:async()=>{observed.calls++;return {ok:true,redirected:false,headers:{get:()=>type},
      body:{cancel:async()=>{observed.cancelled++;},getReader:()=>{observed.readers++;return {
        read:async()=>{if(abort) context.streamController.abort();
          if(sent || !wire)return {done:true};sent=true;return {done:false,value:new TextEncoder().encode(wire)};}
      };}}
    };}
  });
  vm.runInContext(source.slice(source.indexOf('function sseFieldValue('),source.indexOf('function streamClientDeadlineError(')),context);
  vm.runInContext(source.slice(source.indexOf('async function streamChat('),source.indexOf('function dispatchBrainStateSignal(')),context);
  return {context,observed,send:()=>context.streamChat({model:'synthetic'},'assistant')};
}
const event=(name,data)=>'event: '+name+'\ndata: '+JSON.stringify(data)+'\n\n';
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
});
test('local cancellation rejects later bytes and clears heartbeat',async()=>{
  const s=setup({wire:event('final',{answer:'late'}),abort:true});
  await assert.rejects(s.send(),{name:'AbortError'});
  assert.equal(s.observed.rendered.length,0);assert.equal(s.observed.acks,0);
  assert.equal(s.observed.heartbeatCleared,1);
});

