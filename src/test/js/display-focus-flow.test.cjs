const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createFlow,segment,settings}=require('../../../main/resources/static/assets/display/display-focus-flow.js');
const snapshot=(text,extra={})=>({active:true,serverInstanceId:'boot',activationId:'activation',turnId:'turn',stateVersion:1,answerVersion:1,answerText:text,...extra});
function fixture(options={}){let now=0,id=0;const pending=new Map(),events=[],paints=[];
 const flow=createFlow({requestFrame(fn){pending.set(++id,fn);return id;},cancelFrame(id){pending.delete(id);},fitsLine:text=>segment(text).units.length<=12,onEvent:(name,detail)=>events.push({name,detail,at:now}),onPaint:value=>paints.push(value),...options});
 return {flow,pending,events,paints,step(ms=80){now+=ms;const calls=[...pending.values()];pending.clear();calls.forEach(fn=>fn(now));},run(ms){for(let t=0;t<ms;t+=20)this.step(20);}};
}
test('600 graphemes remain active beyond 20s; completion and fade follow the final glyph',()=>{
 const f=fixture();f.flow.accept(snapshot('가'.repeat(600)));f.run(20000);assert.equal(f.events.some(e=>e.name==='presentation_done'),false);assert.ok(f.flow.state().remaining>0);
 f.run(45000);const done=f.events.filter(e=>e.name==='presentation_done');assert.equal(done.length,1);assert.ok(done[0].at>=48000);assert.equal(f.flow.state().index,600);assert.equal(f.pending.size,0);
 const all=f.paints.flatMap(p=>p.lines);assert.ok(all.some(x=>x==='가'.repeat(12)));assert.ok(f.paints.every(p=>p.lines.length<=7));
});
test('combining Korean, emoji ZWJ, flag and combining accent are single units',()=>{
 assert.deepEqual(segment('노바👨‍👩‍👧‍👦🇰🇷é').units,['노','바','👨‍👩‍👧‍👦','🇰🇷','é']);
 const f=fixture();f.flow.accept(snapshot('👨‍👩‍👧‍👦'));f.run(100);assert.equal(f.flow.state().index,1);assert.equal(f.flow.state().lines[0],'👨‍👩‍👧‍👦');
});
test('same poll cannot replay or extend fade and receipt emitted only once',()=>{
 const f=fixture();const s=snapshot('끝');f.flow.accept(s);f.run(300);for(let i=0;i<100;i++)f.flow.accept(s);f.run(6000);
 assert.equal(f.events.filter(e=>e.name==='first_visible').length,1);assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);assert.equal(f.paints.at(-1).opacity,0);assert.equal(f.pending.size,0);
 f.flow.accept(s);assert.equal(f.pending.size,0);
});
test('pause and hidden-tab gaps never dump the remaining answer or fade on resume',()=>{
 const f=fixture();f.flow.accept(snapshot('가'.repeat(120)));f.run(400);const n=f.flow.state().index;f.flow.pause();f.step(60000);assert.equal(f.flow.state().index,n);f.flow.pause(false);f.step(60000);assert.equal(f.flow.state().index,n);f.step();assert.equal(f.flow.state().index,n+1);
 f.step(120000);assert.equal(f.flow.state().index,n+1);assert.ok(f.pending.size<=1);
});
test('close disposes callbacks; old answer cannot reopen and older state cannot overwrite',()=>{
 const f=fixture();f.flow.accept(snapshot('이전'));const callback=[...f.pending.values()][0];f.flow.accept(null);callback(100000);assert.equal(f.flow.state().key,'');assert.equal(f.flow.accept(snapshot('이전')),false);
 f.flow.accept(snapshot('새 답변',{turnId:'new',stateVersion:9}));assert.equal(f.flow.accept(snapshot('오래됨',{stateVersion:8})),false);assert.ok(f.flow.state().key.includes('new'));f.flow.dispose();assert.equal(f.pending.size,0);
});
test('sequential OFF uses automatic lines without manual paging',()=>{
 const f=fixture();f.flow.accept(snapshot('가'.repeat(70),{presentation:{sequentialTextEnabled:false}}));f.run(22000);assert.equal(f.flow.state().index,70);assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
});
test('no auto fade still completes and idles without animation work',()=>{
 const f=fixture();f.flow.accept(snapshot('마지막은 5mg가 아닙니다.',{presentation:{autoFadeEnabled:false}}));f.run(6000);assert.equal(f.flow.state().done,true);assert.equal(f.paints.at(-1).opacity,1);assert.equal(f.pending.size,0);
});
test('fallback does not split Unicode; malformed settings and oversized answer are refused',()=>{
 assert.deepEqual(segment('👨‍👩‍👧‍👦 가족입니다.',null).units,['👨‍👩‍👧‍👦 가족입니다.']);assert.throws(()=>settings({charIntervalMs:1}));
 const f=fixture({Segmenter:null});f.flow.accept(snapshot('한 문장입니다.'));f.run(4000);assert.equal(f.flow.state().mode,'sentence');assert.ok(f.events.some(e=>e.name==='presentation_degraded'));
 assert.equal(f.flow.accept(snapshot('가'.repeat(8001),{turnId:'huge'})),false);
});
test('sentence fallback does not acknowledge an oversized sentence before its reading interval',()=>{
 const f=fixture({Segmenter:null});f.flow.accept(snapshot('가'.repeat(600)+'.'));f.run(20000);
 assert.equal(f.events.some(e=>e.name==='presentation_done'),false);
 f.run(50000);assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
});
