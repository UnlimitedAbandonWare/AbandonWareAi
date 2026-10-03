'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('main/resources/static/js/chat-trace-dock.js', 'utf8');
const validHash = 'hash:012345abcdef';

class Element {
  constructor() { this.children=[]; this.dataset={}; this.attrs={}; this.listeners=new Map(); this.textContent=''; this.hidden=false; }
  appendChild(child) { child.parent=this; this.children.push(child); return child; }
  prepend(child) { child.parent=this; this.children.unshift(child); return child; }
  remove() { if(this.parent)this.parent.children.splice(this.parent.children.indexOf(this),1); }
  setAttribute(key,value) { this.attrs[key]=String(value); }
  getAttribute(key) { return this.attrs[key]??null; }
  addEventListener(name,fn) { this.listeners.set(name,fn); }
  removeEventListener(name) { this.listeners.delete(name); }
  fire(name) { this.listeners.get(name)?.(); }
}
function page(items,nextCursor=null) {
  return { status:200, json:async()=>({items,nextCursor,hasMore:Boolean(nextCursor)}) };
}
function harness({allowed=true,responses=[]}={}) {
  const root=new Element(); root.setAttribute('data-diagnostics-read',String(allowed));
  const toggle=new Element(), body=new Element(), current=new Element(), history=new Element(), status=new Element();
  body.hidden=true;
  const children=new Map([
    ['[data-testid=trace-dock-toggle]',toggle],['#traceDockBody',body],
    ['[data-testid=trace-dock-current]',current],['[data-testid=trace-dock-history]',history],
    ['[data-testid=trace-dock-status]',status]
  ]);
  root.querySelector=selector=>children.get(selector);
  const listeners=new Map();
  const document={readyState:'complete',hidden:false,querySelector:selector=>selector==='[data-trace-dock]'?root:null,
    createElement:()=>new Element(),addEventListener(name,fn){listeners.set(name,fn);},
    removeEventListener(name){listeners.delete(name);}};
  const timers=new Map(), calls=[]; let nextTimer=1, clock=0;
  const fetch=(url,options)=>{
    calls.push({url,options});
    const result=responses.shift();
    if(typeof result==='function')return result(url,options);
    if(result instanceof Error)return Promise.reject(result);
    return Promise.resolve(result||page([]));
  };
  const deterministicMath=Object.create(Math); deterministicMath.random=()=>0.5;
  const window={};
  vm.runInNewContext(source,{window,document,fetch,AbortController,URLSearchParams,Math:deterministicMath,
    Date:{now:()=>clock},
    setTimeout(fn,ms){const id=nextTimer++;timers.set(id,{fn,ms});return id;},
    clearTimeout(id){timers.delete(id);}});
  const flush=()=>new Promise(resolve=>setImmediate(resolve));
  async function fireNext(){
    const entry=[...timers.entries()].sort((a,b)=>a[1].ms-b[1].ms)[0];
    assert.ok(entry,'expected a scheduled timer');
    timers.delete(entry[0]);clock+=entry[1].ms;entry[1].fn();await flush();
  }
  const delays=()=>[...timers.values()].map(entry=>entry.ms);
  return {dock:window.AwxTraceDock,toggle,body,current,history,status,document,listeners,calls,timers,flush,fireNext,delays};
}
test('invalidHashNeverFetches and validHashUsesExactQueryAndLimit50',async()=>{
  const h=harness();
  h.dock.setRequest({requestIdHash:'hash:INVALID',traceIdHash:validHash});
  assert.equal(h.calls.length,0);assert.match(h.status.textContent,/형식 불일치/);
  h.dock.setRequest({requestIdHash:validHash});await h.fireNext();
  const url=new URL(h.calls[0].url,'http://localhost');
  assert.equal(url.pathname,'/api/diagnostics/debug/events/page');
  assert.equal(url.searchParams.get('limit'),'50');
  assert.equal(url.searchParams.get('requestIdHash'),validHash);
  assert.equal(url.searchParams.has('traceIdHash'),false);
  assert.equal(h.calls[0].options.credentials,'same-origin');
  assert.equal(h.calls[0].options.cache,'no-store');h.dock.dispose();
});
test('no generation URL or streaming channel is used',async()=>{
  assert.equal(source.includes('/api/chat/stream'),false);
  assert.equal(source.includes('/api/chat/sync'),false);
  assert.equal(source.includes('EventSource'),false);
  const h=harness();h.dock.setRequest({traceIdHash:validHash});await h.fireNext();
  assert.ok(h.calls.every(call=>call.url.startsWith('/api/diagnostics/debug/events/page?')));
  assert.equal(new URL(h.calls[0].url,'http://localhost').searchParams.get('traceIdHash'),validHash);
  h.dock.dispose();
});
test('abortAfter3000ms',async()=>{
  const h=harness({responses:[()=>new Promise(()=>{})]});
  h.dock.setRequest({requestIdHash:validHash});await h.fireNext();
  assert.deepEqual(h.delays(),[3000]);await h.fireNext();
  assert.equal(h.calls[0].options.signal.aborted,true);h.dock.dispose();
});
test('backoffSequence 2,4,8,16,30 seconds',async()=>{
  const h=harness({responses:Array.from({length:5},()=>new Error('synthetic'))});
  h.dock.setRequest({requestIdHash:validHash});
  const delays=[];
  for(let i=0;i<5;i++){await h.fireNext();delays.push(h.delays()[0]);}
  assert.deepEqual(delays,[2000,4000,8000,16000,30000]);h.dock.dispose();
});
test('hiddenTabPauses and visibleResumesOnce',async()=>{
  const h=harness();h.document.hidden=true;h.dock.setRequest({requestIdHash:validHash});
  assert.equal(h.calls.length,0);h.document.hidden=false;h.listeners.get('visibilitychange')();
  await h.fireNext();assert.equal(h.calls.length,1);h.dock.dispose();
});
test('gone410ResetsCursorAndShowsGap',async()=>{
  const h=harness({responses:[page([{id:'one',ts:'now'}],'cursor-token'),{status:410},page([])]});
  h.dock.setRequest({requestIdHash:validHash});await h.fireNext();await h.fireNext();
  assert.match(h.status.textContent,/기록 유실/);
  assert.equal(new URL(h.calls[1].url,'http://localhost').searchParams.get('cursor'),'cursor-token');
  await h.fireNext();
  assert.equal(new URL(h.calls[2].url,'http://localhost').searchParams.has('cursor'),false);
  h.dock.dispose();
});
test('forbidden403StopsAndShowsNoPermission',async()=>{
  const h=harness({responses:[{status:403}]});h.dock.setRequest({requestIdHash:validHash});
  await h.fireNext();assert.match(h.status.textContent,/권한 없음/);
  assert.equal(h.delays().length,0);h.dock.resume();assert.equal(h.delays().length,0);h.dock.dispose();
});
test('domCapped100AndDedupById with allowlisted text only',async()=>{
  const items=Array.from({length:120},(_,i)=>({id:String(119-i),ts:'now',message:'private-message',
    data:{stage:'stage-'+(119-i),provider:'local',secret:'private-data'}}));
  const h=harness({responses:[page(items.slice(0,50),'older-1'),
    page(items.slice(50,100),'older-2'),page(items.slice(100)),page(items.slice(0,50),'older-1')]});
  h.dock.setRequest({requestIdHash:validHash});
  await h.fireNext();await h.fireNext();await h.fireNext();assert.equal(h.history.children.length,100);
  assert.match(h.current.textContent,/stage-119/);
  assert.equal(h.history.children.some(row=>row.textContent.includes('private-')),false);
  await h.fireNext();assert.equal(h.history.children.length,100);
  assert.equal(h.history.children[0].dataset.eventId,'119');
  assert.equal(h.calls[3].url.includes('cursor='),false);h.dock.dispose();
});
test('new head events appear once without replaying older pages',async()=>{
  const event=id=>({id:String(id),data:{stage:'stage-'+id}});
  const h=harness({responses:[page([event(3),event(2),event(1)]),
    page([event(5),event(4),event(3)]),page([event(5),event(4),event(3)])]});
  h.dock.setRequest({requestIdHash:validHash});
  await h.fireNext();await h.fireNext();await h.fireNext();
  assert.equal(h.history.children.length,5);
  assert.deepEqual(h.history.children.map(row=>row.dataset.eventId),['5','4','3','2','1']);
  assert.match(h.current.textContent,/stage-5/);
  assert.equal(h.calls.every(call=>!call.url.includes('cursor=')),true);h.dock.dispose();
});
test('terminal completion stops polling after sixty seconds',async()=>{
  const h=harness();h.dock.setRequest({requestIdHash:validHash});await h.fireNext();
  h.dock.setRequest({complete:true});
  for(let i=0;i<7;i++)await h.fireNext();
  assert.equal(h.delays().length,0);
  assert.match(h.status.textContent,/관찰 종료/);h.dock.dispose();
});
test('readFalseNeverPolls and toggle stays accessible',()=>{
  const h=harness({allowed:false});h.dock.setRequest({requestIdHash:validHash});
  assert.equal(h.calls.length,0);assert.match(h.status.textContent,/권한 없음/);
  h.toggle.fire('click');assert.equal(h.body.hidden,false);
  assert.equal(h.toggle.getAttribute('aria-expanded'),'true');h.dock.dispose();
});
