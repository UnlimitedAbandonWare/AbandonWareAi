'use strict';
// V9 auxiliary evidence (DEMO1-DEVIN-JEV-VOCAB-ALIGN-ASSIST-20260929 task C):
// simulate a non-admin reader — the diagnostics page API answers 403 (or the
// dock mounts without data-diagnostics-read) and the trace dock must show
// "진단 읽기 권한 없음(관리자 로그인 시 표시)", keep history empty, and stop
// polling. Records unit-level evidence only; it does not raise V9 to PASS.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('main/resources/static/js/chat-trace-dock.js', 'utf8');
const validHash = 'hash:012345abcdef';
const otherHash = 'hash:ffeeffeeffee';

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
    return Promise.resolve(result||{status:200,json:async()=>({items:[],nextCursor:null,hasMore:false})});
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

test('non-admin: diagnostics read 403 stops polling, shows no-permission, history stays empty',async()=>{
  const h=harness({allowed:true,responses:[{status:403}]});
  h.dock.setRequest({requestIdHash:validHash});
  assert.equal(h.calls.length,0,'poll is scheduled, not immediate');
  await h.fireNext();
  assert.equal(h.calls.length,1);
  assert.match(h.status.textContent,/진단 읽기 권한 없음/);
  assert.equal(h.delays().length,0,'no retry is scheduled after 403');
  assert.equal(h.history.children.length,0,'no diagnostic rows rendered');
  // a later turn on the same non-admin session keeps the no-permission state
  h.dock.setRequest({requestIdHash:otherHash});
  assert.match(h.status.textContent,/진단 읽기 권한 없음\(관리자 로그인 시 표시\)/);
  assert.equal(h.calls.length,1,'no new fetch after the forbidden latch');
  assert.equal(h.history.children.length,0);
  h.dock.resume();
  assert.equal(h.delays().length,0,'resume must not restart polling');
  assert.match(h.status.textContent,/권한 없음/);
  h.dock.dispose();
});

test('non-admin: dock without read allowance never calls the diagnostics API',async()=>{
  const h=harness({allowed:false});
  h.dock.setRequest({requestIdHash:validHash});
  assert.equal(h.calls.length,0,'no fetch attempted');
  assert.match(h.status.textContent,/진단 읽기 권한 없음\(관리자 로그인 시 표시\)/);
  assert.equal(h.history.children.length,0);
  assert.equal(h.current.textContent,'요청 '+validHash);
  h.dock.dispose();
});
