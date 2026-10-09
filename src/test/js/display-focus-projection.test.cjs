const {test}=require('node:test'),assert=require('node:assert/strict');
const {createProjection,decode}=require('../../../main/resources/static/assets/display/display-focus.js');
const base={active:true,phase:'ANSWER_READY',serverInstanceId:'boot',activationId:'a',turnId:'t',stateVersion:1,answerVersion:1,draftText:'',questionText:'질문',answerText:'답변',idleRemainingMs:0,renderTarget:'lens',renderReceiptTicket:'a'.repeat(64)};
function groundingFixture(){
 const doc={hidden:false,createElement(tag){const node={tagName:tag.toUpperCase(),style:{},children:[],attributes:[],textContent:'',get lastElementChild(){return this.children.at(-1);},appendChild(node){node.parent=this;this.children.push(node);},append(...nodes){nodes.forEach(node=>this.appendChild(node));},replaceChildren(...nodes){this.children=[];this.append(...nodes);},remove(){this.removed=true;if(this.parent)this.parent.children=this.parent.children.filter(node=>node!==this);},attachShadow(){return this.shadow={};}};
   if(tag==='template')Object.defineProperty(node,'innerHTML',{set(value){this.content={querySelectorAll:()=>value.includes('<script')?[{tagName:'SCRIPT',attributes:[]}]:[]};}});return node;}};
 const answer=doc.createElement('div');answer.ownerDocument=doc;const calls=[],projection=createProjection({host:{},document:doc,target:'fold',answer,receipt:async(name)=>calls.push(name),requestFrame:()=>0,cancelFrame(){}});
 const html='<div><a href="https://www.google.com/search?q=fixture">Google Search</a></div>';
 const value={...base,renderTarget:'fold',grounding:{originalText:'원문'.repeat(5000),model:'gemini-fixture',metadata:{groundingChunks:[{web:{uri:'https://example.com/source',title:'Source'}}],groundingSupports:[{groundingChunkIndices:[0]}],searchEntryPoint:{renderedContent:html}}}};
 return {doc,answer,calls,projection,value,html};
}
test('phone presents original, related sources and intact Suggestions with one receipt pair',async()=>{
 const f=groundingFixture();f.projection.update(f.value);await new Promise(setImmediate);
 const block=f.answer.children[0];assert.equal(block.children[0].textContent,f.value.grounding.originalText);
 assert.equal(block.children[1].children[0].href,'https://example.com/source');assert.equal(block.children[2].shadow.innerHTML,f.html);
 assert.equal(f.answer.style.overflow,'auto');assert.deepEqual(f.calls,['first_visible','presentation_done']);
 f.projection.update({...f.value,stateVersion:2});await new Promise(setImmediate);assert.equal(f.calls.length,2);
 f.projection.update({...f.value,active:false,stateVersion:3});assert.equal(block.removed,true);
 f.projection.update(f.value);assert.equal(f.projection.isActive(),false);
});
test('unsafe Suggestions hold only grounded rendering without acknowledging a displayed result',async()=>{
 const f=groundingFixture();f.value.grounding.metadata.searchEntryPoint.renderedContent='<script>bad()</script>';
 f.projection.update(f.value);await new Promise(setImmediate);assert.equal(f.calls.length,0);assert.equal(f.answer.children.some(node=>node.shadow),false);
 assert.match(f.answer.textContent,/출처 표시/);
});
test('hidden phone waits for fresh visible projection before acknowledging grounded result',async()=>{
 const f=groundingFixture();f.doc.hidden=true;f.projection.update(f.value);await new Promise(setImmediate);assert.equal(f.calls.length,0);
 f.doc.hidden=false;f.projection.visibility();f.projection.update({...f.value,stateVersion:2});await new Promise(setImmediate);
 assert.deepEqual(f.calls,['first_visible','presentation_done']);
});
function fixture(target='lens',extra={}){
 const frames=new Map(),timers=new Map(),diagnostics=[],calls=[],panel={},status={},draft={},doc={hidden:false};let id=0,time=0;
 const projection=createProjection({host:{setTimeout(fn,ms){timers.set(++id,{fn,at:time+ms});return id;},clearTimeout:id=>timers.delete(id)},document:doc,target,panel,status,draft,diagnostic:(name,detail)=>diagnostics.push({name,detail}),...extra,requestFrame:fn=>{frames.set(++id,fn);return id;},cancelFrame:id=>frames.delete(id),fitsLine:()=>true,receipt:async(name,detail)=>calls.push({name,detail})});
 return {projection,frames,timers,diagnostics,calls,panel,status,draft,doc,advance(ms){for(let t=0;t<ms;t+=20){time+=20;for(const [key,timer] of timers)if(timer.at<=time){timers.delete(key);timer.fn();}const f=[...frames.values()];frames.clear();f.forEach(fn=>fn(time));}}};
}
test('fold keeps active panel and draft during reconnect and resumes the same answer once',async()=>{
 const f=fixture('fold'),value={...base,renderTarget:'fold',answerText:'가'.repeat(40)};
 f.projection.update(value);f.advance(400);await new Promise(setImmediate);
 f.projection.update(value,false,'RECONNECTING');assert.equal(f.panel.hidden,false);assert.match(f.status.textContent,/재연결 중/);assert.equal(f.frames.size,0);
 f.projection.update(null,false,'RECONNECTING');assert.equal(f.panel.hidden,false);assert.equal(f.draft.textContent,value.questionText);
 f.projection.update(value,true);f.advance(6000);await new Promise(setImmediate);
 assert.equal(f.calls.filter(x=>x.name==='first_visible').length,1);assert.equal(f.calls.filter(x=>x.name==='presentation_done').length,1);
 assert.equal(f.diagnostics.some(x=>x.detail.cause==='not_connected:RECONNECTING'),true);
});
test('fold shows terminal reason for four seconds without poll timer sliding or stale resurrection',()=>{
 const f=fixture('fold',{reasonText:reason=>reason==='wake_no_question'?'질문이 들리지 않았습니다.':reason});
 f.projection.update({...base,renderTarget:'fold'});
 const ended={...base,active:false,phase:'ARMED',stateVersion:2,reason:'wake_no_question',answerText:''};
 assert.equal(f.projection.update(ended),false);assert.equal(f.panel.hidden,false);assert.equal(f.projection.isActive(),true);assert.match(f.status.textContent,/노바 종료됨.*질문이 들리지/);assert.equal(f.frames.size,0);
 f.advance(3000);f.projection.update(ended);f.advance(1000);assert.equal(f.panel.hidden,true);
 f.projection.update(ended);f.projection.update(base);assert.equal(f.panel.hidden,true);
 assert.equal(f.diagnostics.filter(x=>x.detail.cause==='server_inactive:wake_no_question'&&!x.detail.hidden).length,1);
});
test('fold contract error retains panel and last draft then recovers on valid snapshot',()=>{
 const f=fixture('fold');f.projection.update(base);
 assert.equal(f.projection.update({...base,phase:'bad'},false),true);assert.equal(f.panel.hidden,false);assert.match(f.status.textContent,/表示|표시 오류/);assert.equal(f.frames.size,0);
 f.projection.update(base);assert.equal(f.panel.hidden,false);assert.doesNotMatch(f.status.textContent,/오류/);
});
test('fold unknown terminal code is safe and debug retention remains dismissible without reopening',()=>{
 const f=fixture('fold',{keepClosed:()=>true});f.projection.update(base);
 f.projection.update({...base,active:false,stateVersion:2,reason:'new_reason'});assert.match(f.status.textContent,/노바가 종료됐습니다.*new_reason/);
 f.advance(10000);assert.equal(f.panel.hidden,false);f.projection.dismiss();assert.equal(f.panel.hidden,true);assert.equal(f.projection.isActive(),false);
 f.projection.update({...base,active:false,stateVersion:3,reason:'new_reason'});assert.equal(f.panel.hidden,true);
 f.projection.update({...base,activationId:'new',stateVersion:4});assert.equal(f.panel.hidden,false);
});
test('fold terminal explanation survives a missing snapshot and disappears on schedule',()=>{
 const f=fixture('fold');f.projection.update(base);f.projection.update({...base,active:false,stateVersion:2,reason:'idle_timeout'});
 f.advance(100);f.projection.update(null);assert.equal(f.panel.hidden,false);assert.equal(f.projection.isActive(),true);assert.match(f.status.textContent,/終了|종료됨/);
 f.projection.update({...base,active:false,stateVersion:3,reason:''});assert.equal(f.panel.hidden,false);assert.equal(f.projection.isActive(),true);
 f.projection.update({...base,stateVersion:2});assert.match(f.status.textContent,/종료됨/);
 f.advance(3900);assert.equal(f.panel.hidden,true);assert.equal(f.projection.isActive(),false);
});
test('optional focus is backwards compatible and malformed focus is isolated',()=>{
 assert.equal(decode(null),null);assert.throws(()=>decode({...base,answerText:'가'.repeat(8001)}));const f=fixture();assert.equal(f.projection.update(null),false);assert.equal(f.projection.update({...base,phase:'untrusted'}),false);assert.equal(f.panel.hidden,true);
});
test('bounded answer does not promise a durable full history',()=>{
 const f=fixture();f.projection.update({...base,answerTruncated:true,hasMoreOnFold:true});
 assert.match(f.status.textContent,/긴 응답 일부 표시/);assert.doesNotMatch(f.status.textContent,/폴드 기록/);
 assert.throws(()=>decode({...base,hasMoreOnFold:'untrusted'}));
});
test('manual listening projection has no answer or receipt yet and still opens',()=>{
 const f=fixture('fold'),listening={...base,phase:'LISTENING',turnId:'',answerText:'',renderTarget:'fold',renderReceiptTicket:''};
 assert.equal(decode(listening).renderReceiptTicket,null);
 assert.equal(f.projection.update(listening),true);assert.equal(f.panel.hidden,false);assert.match(f.status.textContent,/듣는 중/);
 assert.equal(f.calls.length,0);
});
test('only selected renderer sends first and done once; phone cannot close lens early',async()=>{
 const lens=fixture(),phone=fixture('fold');lens.projection.update(base);phone.projection.update(base);lens.advance(400);phone.advance(400);await new Promise(setImmediate);
 assert.deepEqual(lens.calls.map(x=>x.name),['first_visible','presentation_done']);assert.equal(phone.calls.length,0);for(let i=0;i<10;i++)lens.projection.update(base);await new Promise(setImmediate);assert.equal(lens.calls.length,2);
});
test('disconnection freezes presentation; visibility resumes only after fresh poll',()=>{
 const f=fixture();f.projection.update({...base,answerText:'가'.repeat(600)});f.advance(200);f.doc.hidden=true;f.projection.visibility();assert.equal(f.frames.size,0);f.doc.hidden=false;f.projection.visibility();assert.equal(f.frames.size,0);f.projection.update({...base,answerText:'가'.repeat(600)});assert.equal(f.frames.size,1);
 f.projection.update(base,false);assert.equal(f.frames.size,0);assert.equal(f.panel.hidden,true);f.projection.dispose();
});
test('old activation version cannot replace a newer question and close cancels animation',()=>{
 const f=fixture();f.projection.update({...base,stateVersion:9,activationId:'new',questionText:'새 질문'});f.projection.update(base);assert.equal(f.draft.textContent,'새 질문');f.projection.update({...base,active:false,stateVersion:10});assert.equal(f.panel.hidden,true);assert.equal(f.frames.size,0);
});
test('repeated poll and paint preserve the in-progress character clock',async()=>{
 const f=fixture();const s={...base,answerText:'가'.repeat(20)};f.projection.update(s);
 for(let i=0;i<120;i++){f.advance(20);f.projection.update(s);}
 await new Promise(setImmediate);
 assert.equal(f.calls.filter(x=>x.name==='presentation_done').length,1);
});
test('malformed poll pauses without retiring the current answer; valid retry resumes once',async()=>{
 const f=fixture(),good={...base,answerText:'가'.repeat(40)};
 f.projection.update(good);f.advance(400);await new Promise(setImmediate);
 assert.equal(f.calls.filter(x=>x.name==='first_visible').length,1);
 assert.equal(f.projection.update({...good,phase:'bad'}),true);assert.equal(f.frames.size,0);
 assert.equal(f.projection.update(good),true);assert.equal(f.frames.size,1);
 f.advance(6000);await new Promise(setImmediate);
 assert.equal(f.calls.filter(x=>x.name==='first_visible').length,1);
 assert.equal(f.calls.filter(x=>x.name==='presentation_done').length,1);
});
