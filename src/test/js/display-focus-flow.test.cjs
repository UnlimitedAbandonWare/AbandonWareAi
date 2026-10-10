const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createFlow,segment,settings}=require('../../../main/resources/static/assets/display/display-focus-flow.js');
const snapshot=(text,extra={})=>({active:true,serverInstanceId:'boot',activationId:'activation',turnId:'turn',stateVersion:1,answerVersion:1,answerText:text,...extra});
test('server stable sentence becomes useful before terminal completion with a separate UI duration',()=>{
 const f=fixture({fitsLine:()=>true});f.flow.accept(snapshot('첫 유용 문장입니다.',{answerComplete:false,answerPrefixStable:true}));f.run(2000);
 assert.equal(f.flow.state().lines.join(''),'첫 유용 문장입니다.');
 const useful=f.events.filter(e=>e.name==='first_useful_visible');assert.equal(useful.length,1);
 assert.ok(useful[0].detail.elapsedMs>=f.events.find(e=>e.name==='first_visible').at-20);
 assert.equal(f.events.filter(e=>e.name==='presentation_done').length,0);
 f.flow.accept(snapshot('첫 유용 문장입니다. 최종 문장입니다.',{stateVersion:2,answerComplete:true,answerPrefixStable:false}));f.run(3000);
 assert.equal(f.events.filter(e=>e.name==='first_useful_visible').length,1);
 assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
});
test('late final receipt ticket acknowledges an already visible prefix without replay',()=>{
 const f=fixture({fitsLine:()=>true});f.flow.accept(snapshot('첫 문장입니다. ',{answerComplete:false}));f.run(1500);
 const before=f.flow.state().index;
 f.flow.accept(snapshot('첫 문장입니다. ',{stateVersion:2,answerComplete:true,renderReceiptTicket:'synthetic-ticket'}));f.run(400);
 const acknowledged=f.events.filter(e=>e.name==='first_visible'&&e.detail.renderReceiptTicket==='synthetic-ticket');
 assert.equal(acknowledged.length,1);assert.ok(f.flow.state().index>=before);
 assert.equal(f.events.filter(e=>e.name==='presentation_done'&&e.detail.renderReceiptTicket==='synthetic-ticket').length,1);
 f.flow.accept(snapshot('첫 문장입니다. ',{stateVersion:2,answerComplete:true,renderReceiptTicket:'synthetic-ticket'}));
 assert.equal(f.events.filter(e=>e.name==='first_visible'&&e.detail.renderReceiptTicket==='synthetic-ticket').length,1);
});
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
test('nonsequential completed answer begins displaying within two frames',()=>{
 const f=fixture();f.flow.accept(snapshot('간결한 합성 답변',{presentation:{sequentialTextEnabled:false}}));
 f.step(20);f.step(20);assert.ok(f.flow.state().index>0);f.step(20);
 assert.equal(f.events.filter(e=>e.name==='first_visible').length,1);
});
test('nonsequential ready lines appear without an artificial provider-sized wait',()=>{
 const f=fixture();f.flow.accept(snapshot('가'.repeat(48),{presentation:{sequentialTextEnabled:false,autoFadeEnabled:false}}));f.run(200);
 assert.equal(f.flow.state().index,48);
 assert.equal(f.events.filter(e=>e.name==='first_useful_visible').length,1);
 assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
 assert.ok(f.events.find(e=>e.name==='presentation_done').at<=200);
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
test('nonterminal cumulative text appends once and waits for completion before final receipt',()=>{
 const f=fixture();f.flow.accept(snapshot('가나다 ',{answerComplete:false}));f.run(500);
 assert.ok(f.events.some(e=>e.name==='first_visible'));assert.equal(f.events.some(e=>e.name==='presentation_done'),false);
 const first=f.flow.state().index;
 assert.equal(f.flow.accept(snapshot('가나다 라마바 ',{stateVersion:2,answerComplete:false})),true);f.run(700);
 assert.ok(f.flow.state().index>first);assert.equal(f.events.some(e=>e.name==='presentation_done'),false);
 assert.equal(f.flow.accept(snapshot('가나다 라마바 ',{stateVersion:3,answerComplete:true})),true);f.run(500);
 assert.equal(f.flow.state().lines.join(''),'가나다 라마바 ');
 assert.equal(f.events.filter(e=>e.name==='first_visible').length,1);assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
});
test('streaming text rejects revisions and post-completion growth under the same identity',()=>{
 const f=fixture();f.flow.accept(snapshot('같은 답변 ',{answerComplete:false}));f.run(500);
 assert.equal(f.flow.accept(snapshot('다른 답변 ',{stateVersion:2,answerComplete:false})),false);
 assert.equal(f.flow.accept(snapshot('같은 답변 끝.',{stateVersion:3,answerComplete:true})),true);f.run(2000);
 assert.equal(f.flow.accept(snapshot('같은 답변 끝. 추가',{stateVersion:4,answerComplete:false})),false);
 assert.equal(f.flow.accept(snapshot('같은 답변 끝.',{stateVersion:5,answerComplete:'false'})),false);
 assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
});
test('nonterminal Unicode tail cannot display a partial emoji grapheme',()=>{
 const f=fixture();f.flow.accept(snapshot('첫 문장. 👨',{answerComplete:false}));f.run(2000);
 assert.equal(f.flow.state().lines.join('').includes('👨'),false);
 f.flow.accept(snapshot('첫 문장. 👨‍👩‍👧‍👦 ',{stateVersion:2,answerComplete:false}));f.run(1200);
 assert.ok(f.flow.state().lines.join('').includes('👨‍👩‍👧‍👦'));assert.equal(f.events.some(e=>e.name==='presentation_done'),false);
 f.flow.accept(snapshot('첫 문장. 👨‍👩‍👧‍👦 ',{stateVersion:3,answerComplete:true}));f.run(500);
 assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
 assert.ok(f.paints.every(p=>!p.lines.join('').includes('👨')||p.lines.join('').includes('👨‍👩‍👧‍👦')));
});
test('sentence degradation withholds its growing tail until final completion',()=>{
 const f=fixture({Segmenter:null});f.flow.accept(snapshot('완결 문장. 미완',{answerComplete:false}));f.run(2500);
 assert.equal(f.flow.state().lines.join(''),'완결 문장.');assert.equal(f.events.some(e=>e.name==='presentation_done'),false);
 f.flow.accept(snapshot('완결 문장. 미완성입니다.',{stateVersion:2,answerComplete:true}));f.run(3000);
 assert.equal(f.flow.state().lines.join(''),' 미완성입니다.');assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
});
test('changed streaming payload cannot reuse the accepted state version',()=>{
 const f=fixture();f.flow.accept(snapshot('이 문장 ',{answerComplete:false}));f.run(700);
 assert.equal(f.flow.accept(snapshot('이 문장 다음',{answerComplete:false})),false);
 assert.equal(f.flow.accept(snapshot('이 문장 ',{answerComplete:true})),false);
 assert.equal(f.flow.accept(snapshot('이 문장 다음',{stateVersion:2,answerComplete:true})),true);f.run(2000);
 assert.equal(f.flow.state().lines.join(''),'이 문장 다음');assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
});
test('explicit replay keeps final answer metadata compatible with subsequent polling',()=>{
 const f=fixture(),s=snapshot('다시 보기');f.flow.accept(s);f.run(1000);
 f.flow.replay();assert.equal(f.flow.accept(s),true);f.run(1000);
 assert.equal(f.flow.state().lines.join(''),'다시 보기');assert.equal(f.flow.state().done,true);
});
test('fold keeps a completed answer through its hold and fade window when the snapshot answer clears',()=>{
 const f=fixture();f.flow.accept(snapshot('유지되는 답변'));f.run(2000);
 assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
 f.flow.accept(snapshot('',{stateVersion:2,phase:'LISTENING'}));f.run(1000);
 assert.equal(f.flow.state().lines.join(''),'유지되는 답변');
 assert.equal(f.events.filter(e=>e.name==='presentation_done').length,1);
 f.run(6000);assert.equal(f.paints.at(-1).opacity,0);
 f.flow.accept(snapshot('',{stateVersion:3,phase:'LISTENING'}));
 assert.equal(f.flow.state().lines.join(''),'');
});
test('fold honors the saved 3000ms tailHold window before a vacated snapshot may clear',()=>{
 const f=fixture();f.flow.accept(snapshot('삼초 유지',{presentation:{tailHoldMs:3000}}));f.run(2000);
 f.flow.accept(snapshot('',{stateVersion:2,phase:'LISTENING'}));f.run(1500);
 assert.equal(f.flow.state().lines.join(''),'삼초 유지');
 f.run(2500);assert.equal(f.paints.at(-1).opacity,0);
 f.flow.accept(snapshot('',{stateVersion:3,phase:'LISTENING'}));
 assert.equal(f.flow.state().lines.join(''),'');
});
test('fold without auto fade keeps the completed answer until the next answer',()=>{
 const f=fixture();f.flow.accept(snapshot('유지 답변',{presentation:{autoFadeEnabled:false}}));f.run(2000);
 assert.equal(f.flow.state().done,true);assert.equal(f.paints.at(-1).opacity,1);
 f.flow.accept(snapshot('',{stateVersion:2,phase:'LISTENING'}));f.step(400);
 assert.equal(f.flow.state().lines.join(''),'유지 답변');
 f.flow.accept(snapshot('다음 답변',{turnId:'next',answerVersion:2,stateVersion:3}));f.run(1000);
 assert.equal(f.flow.state().lines.join(''),'다음 답변');
});
test('fold explicit close inside the hold window still clears immediately',()=>{
 const f=fixture();f.flow.accept(snapshot('즉시 종료'));f.run(2000);
 f.flow.accept(snapshot('',{stateVersion:2,phase:'LISTENING'}));f.step(200);
 assert.equal(f.flow.state().lines.join(''),'즉시 종료');
 f.flow.accept(null);assert.equal(f.flow.state().lines.join(''),'');
});
test('a vacated snapshot mid-presentation still resets immediately',()=>{
 const f=fixture();f.flow.accept(snapshot('가'.repeat(80)));f.run(500);
 assert.equal(f.flow.state().done,false);assert.ok(f.flow.state().index>0);
 f.flow.accept(snapshot('',{stateVersion:2,phase:'LISTENING'}));
 assert.equal(f.flow.state().lines.join(''),'');
});
