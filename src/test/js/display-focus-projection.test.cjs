const {test}=require('node:test'),assert=require('node:assert/strict');
const {createProjection,decode}=require('../../../main/resources/static/assets/display/display-focus.js');
const base={active:true,phase:'ANSWER_READY',serverInstanceId:'boot',activationId:'a',turnId:'t',stateVersion:1,answerVersion:1,draftText:'',questionText:'질문',answerText:'답변',idleRemainingMs:0,renderTarget:'lens',renderReceiptTicket:'a'.repeat(64)};
function fixture(target='lens'){
 const frames=new Map(),calls=[],panel={},status={},draft={},doc={hidden:false};let id=0,time=0;
 const projection=createProjection({host:{},document:doc,target,panel,status,draft,requestFrame:fn=>{frames.set(++id,fn);return id;},cancelFrame:id=>frames.delete(id),fitsLine:()=>true,receipt:async(name,detail)=>calls.push({name,detail})});
 return {projection,frames,calls,panel,status,draft,doc,advance(ms){for(let t=0;t<ms;t+=20){time+=20;const f=[...frames.values()];frames.clear();f.forEach(fn=>fn(time));}}};
}
test('optional focus is backwards compatible and malformed focus is isolated',()=>{
 assert.equal(decode(null),null);assert.throws(()=>decode({...base,answerText:'가'.repeat(8001)}));const f=fixture();assert.equal(f.projection.update(null),false);assert.equal(f.projection.update({...base,phase:'untrusted'}),false);assert.equal(f.panel.hidden,true);
});
test('bounded answer directs the reader to full Fold history',()=>{
 const f=fixture();f.projection.update({...base,answerTruncated:true,hasMoreOnFold:true});
 assert.match(f.status.textContent,/전문은 폴드 기록에서/);
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
