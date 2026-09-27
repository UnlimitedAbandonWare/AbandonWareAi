const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createLensReceiver,splitHintPages,fitCaption,normDisplay}=require('../../../main/resources/static/assets/display/meta/receiver.js');
const flush=()=>new Promise(setImmediate);
function lensFixture(options={}){
 const timers=new Map(),calls=[];let n=0,reply={conversation:'',hint:''},status=200,nowMs=1000;
 const receiver=createLensReceiver({token:'a'.repeat(64),now:()=>nowMs,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id),
  fetchImpl:async(url,opts)=>{calls.push({url,body:JSON.parse(opts.body)});return{ok:status===200,status,json:async()=>reply};},...options});
 return {receiver,timers,calls,set(body,code=200){reply=body;status=code;},expiry:()=>[...timers.values()].find(t=>t.ms===20000),advance(ms){nowMs+=ms;},
  async poll(){await receiver.poll();await flush();}};
}
test('a lens hint lives 20s from first show; identical polls never extend it and an expired hint never returns',async()=>{
 const f=lensFixture();
 const A={conversation:'이븐리얼리티 G2 중고 판매 문의',hint:'G2 판매가와 배터리 상태를 먼저 확인하세요.',hintId:'req-a',display:{transcriptTtlMs:90000}};
 f.set(A);await f.poll();
 assert.equal(f.receiver.state.hint,A.hint);assert.equal(f.receiver.state.hintId,'req-a');
 const firstShown=f.receiver.state.hintFirstShownAt;assert.equal(f.receiver.state.hintExpiresAt,firstShown+20000);
 for(let i=0;i<40;i++){f.set(A);await f.poll();assert.equal(f.receiver.state.hintExpiresAt,firstShown+20000);}
 assert.equal(f.timers.size,2,'identical polls must not reschedule hint or transcript expiry');
 f.advance(20000);f.expiry().fn();assert.equal(f.receiver.state.hint,'');assert.equal(f.receiver.state.hintId,'');assert.equal(f.receiver.state.conversation,A.conversation);
 for(let i=0;i<3;i++){f.set(A);await f.poll();assert.equal(f.receiver.state.hint,'','expired hint must stay hidden');}
 f.receiver.dispose();
});
test('a newer hint replaces at once and owns a fresh 20s; a stale late arrival cannot overwrite or hide it',async()=>{
 const f=lensFixture();
 f.set({conversation:'이븐리얼리티 G2',hint:'G2 힌트',hintId:'req-a',display:{transcriptTtlMs:90000}});await f.poll();assert.equal(f.receiver.state.hintId,'req-a');
 const expiresA=f.receiver.state.hintExpiresAt,timerA=f.expiry();
 f.advance(10000);f.set({conversation:'레이벤 메타 디스플레이 렌즈',hint:'렌즈의 무지갯빛 반사 현상을 확인하세요.',hintId:'req-b',display:{transcriptTtlMs:90000}});await f.poll();
 assert.equal(f.receiver.state.hint,'렌즈의 무지갯빛 반사 현상을 확인하세요.');
 assert.ok(f.receiver.state.hintExpiresAt>expiresA,'B owns a fresh window');
 assert.ok(![...f.timers.values()].includes(timerA),'A timer was cleared');
 f.set({conversation:'레이벤 메타 디스플레이 렌즈',hint:'G2 힌트',hintId:'req-a',display:{transcriptTtlMs:90000}});await f.poll();
 assert.equal(f.receiver.state.hint,'렌즈의 무지갯빛 반사 현상을 확인하세요.','stale A cannot overwrite B');
 f.advance(20000);f.expiry().fn();assert.equal(f.receiver.state.hint,'','B expires on its own clock');
 f.receiver.dispose();
});
test('identical text under a new request id is a new hint; text-only hints suppress after expiry',async()=>{
 const f=lensFixture();
 const cfg={display:{transcriptTtlMs:90000}};
 f.set({conversation:'x',hint:'같은 문구',hintId:'req-1',...cfg});await f.poll();f.advance(20000);f.expiry().fn();assert.equal(f.receiver.state.hint,'');
 f.set({conversation:'x',hint:'같은 문구',hintId:'req-2',...cfg});await f.poll();
 assert.equal(f.receiver.state.hint,'같은 문구');assert.equal(f.receiver.state.hintId,'req-2');
 f.advance(20000);f.expiry().fn();assert.equal(f.receiver.state.hint,'');
 // Without a server id the text itself is the only identity; an expired one stays hidden.
 f.set({conversation:'x',hint:'다른 문구',...cfg});await f.poll();assert.equal(f.receiver.state.hint,'다른 문구');
 f.advance(20000);f.expiry().fn();assert.equal(f.receiver.state.hint,'');
 f.set({conversation:'x',hint:'다른 문구',...cfg});await f.poll();assert.equal(f.receiver.state.hint,'','same text stays suppressed');
 f.receiver.dispose();
});
test('transcript keeps rolling while a hint expires and while the same hint repeats',async()=>{
 const f=lensFixture();
 f.set({conversation:'전사 1',hint:'힌트',hintId:'r1',display:{transcriptTtlMs:90000}});await f.poll();
 f.advance(20000);f.expiry().fn();assert.equal(f.receiver.state.hint,'');
 f.set({conversation:'전사 2',hint:'',display:{transcriptTtlMs:90000}});await f.poll();
 assert.equal(f.receiver.state.conversation,'전사 2');assert.equal(f.receiver.state.hint,'');
 f.receiver.dispose();
});
test('transcript owns its configured TTL from first show; identical captions never extend it and expired text stays hidden',async()=>{
 const f=lensFixture();
 const A={conversation:'전사 내용',hint:'',display:{transcriptTtlMs:7000}};
 f.set(A);await f.poll();
 assert.equal(f.receiver.state.conversation,'전사 내용');
 assert.equal(f.receiver.state.conversationExpiresAt,8000,'transcript hold anchors at first show + 7 s');
 for(let i=0;i<10;i++){await f.poll();assert.equal(f.receiver.state.conversationExpiresAt,8000,'identical re-polls never extend the transcript');}
 f.advance(7000);[...f.timers.values()].find(t=>t.ms===7000).fn();
 assert.equal(f.receiver.state.conversation,'','transcript expires on its own clock');
 for(let i=0;i<3;i++){f.set(A);await f.poll();assert.equal(f.receiver.state.conversation,'','expired caption stays hidden while identical');}
 f.set({conversation:'다음 전사',hint:'',display:{transcriptTtlMs:7000}});await f.poll();
 assert.equal(f.receiver.state.conversation,'다음 전사','new content re-anchors the clock');
 assert.equal(f.receiver.state.conversationExpiresAt,15000);
 f.receiver.dispose();
});
test('server conversationExpiresAt caps the configured transcript hold',async()=>{
 const f=lensFixture();
 f.set({conversation:'짧은 전사',hint:'',display:{transcriptTtlMs:90000},conversationExpiresAt:6000});await f.poll();
 assert.equal(f.receiver.state.conversationExpiresAt,6000,'server cap wins over a 90 s setting');
 f.advance(5000);[...f.timers.values()].find(t=>t.ms===5000).fn();
 assert.equal(f.receiver.state.conversation,'');
 f.receiver.dispose();
});
test('normDisplay clamps transport values into the 1-100 s window and keeps auto-page 0 as off',()=>{
 assert.equal(normDisplay({hintTtlMs:999}).hintTtlMs,1000);
 assert.equal(normDisplay({hintTtlMs:150000}).hintTtlMs,100000);
 assert.equal(normDisplay({transcriptTtlMs:23000}).transcriptTtlMs,23000);
 assert.equal(normDisplay({transcriptTtlMs:46000}).transcriptTtlMs,46000);
 assert.equal(normDisplay({transcriptTtlMs:0}).transcriptTtlMs,1000);
 assert.equal(normDisplay({autoPageMs:0}).autoPageMs,0,'0 still disables auto paging');
 assert.equal(normDisplay({autoPageMs:46000}).autoPageMs,46000);
 assert.equal(normDisplay({autoPageMs:100001}).autoPageMs,100000);
});
test('splitHintPages fits the configured line band and loses nothing through the hard cap',()=>{
 let store='';
 const el={scrollHeight:0,clientHeight:0};
 Object.defineProperty(el,'textContent',{get:()=>store,set:v=>{store=v;el.scrollHeight=Math.ceil(Array.from(v).length/18)*32.5;}});
 const fits=(page,lines=11)=>{el.textContent=page;return el.scrollHeight<=lines*32.5+0.75;};
 assert.deepEqual(splitHintPages(null,'짧은 힌트'),['짧은 힌트']);
 assert.deepEqual(splitHintPages(el,'짧은 힌트'),['짧은 힌트']);
 for(const chars of [198,199,216,540,612,900,1180]){
  const text='가'.repeat(chars),pages=splitHintPages(el,text);
  assert.equal(pages.join('').length,chars,'all characters preserved for '+chars);
  for(const page of pages)assert.ok(fits(page),'page fits the 11-line band for '+chars);
  assert.equal(pages.length>1,chars>198,'paging boundary for '+chars);
 }
 for(const chars of [144,145]){
  const pages=splitHintPages(el,'가'.repeat(chars),8);
  assert.equal(pages.length>1,chars>144,'explicit 8-line band for '+chars);
 }
 assert.deepEqual(splitHintPages(el,'앞  뒤\n줄'),['앞 뒤 줄']);
});
test('a measured clientHeight smaller than the configured band still caps the page',()=>{
 let store='';
 const el={scrollHeight:0,clientHeight:130}; // ~4 rendered lines at 32.5px
 Object.defineProperty(el,'textContent',{get:()=>store,set:v=>{store=v;el.scrollHeight=Math.ceil(Array.from(v).length/18)*32.5;}});
 const pages=splitHintPages(el,'가'.repeat(300),13);
 assert.equal(pages.join('').length,300);
 for(const page of pages){el.textContent=page;assert.ok(el.scrollHeight<=130.75);}
});
test('lens hint TTL follows the delivered display setting and the server expiry ceiling',async()=>{
 const f=lensFixture();
 f.set({conversation:'',hint:'설정된 수명',hintId:'cfg',display:{hintTtlMs:40000},hintExpiresAt:0});await f.poll();
 assert.equal(f.receiver.state.display.hintTtlMs,40000);
 const first=f.receiver.state.hintFirstShownAt;
 assert.equal(f.receiver.state.hintExpiresAt,first+40000,'configured 40s TTL applies');
 assert.ok([...f.timers.values()].some(t=>t.ms===40000));
 // A server-side expiry earlier than the configured TTL wins.
 f.set({conversation:'',hint:'서버 상한',hintId:'cap',display:{hintTtlMs:40000},hintExpiresAt:1000+25000});await f.poll();
 assert.equal(f.receiver.state.hintExpiresAt,f.receiver.state.hintFirstShownAt+25000,'server expiresAt caps the client window');
 f.receiver.dispose();
});
function captionEl(clientHeight){
 let store='';const el={clientHeight:clientHeight||0,scrollHeight:0};
 Object.defineProperty(el,'textContent',{get:()=>store,set:v=>{store=v;el.scrollHeight=Math.ceil(Array.from(v).length/18)*32.5;}});
 return el;
}
test('fitCaption keeps the newest complete units inside the measured lens band',()=>{
 const el=captionEl(163); // ~5 rendered lines at 32.5px
 const text=[1,2,3,4,5,6,7,8,9].map(n=>'질문이 아주 길게 이어지는 문장 번호 '+n+'입니다.').join(' ');
 const out=fitCaption(el,text,13);
 assert.ok(el.scrollHeight<=el.clientHeight+0.75,'content fits the measured box');
 assert.ok(out.endsWith('9입니다.'),'newest tail kept');
 assert.ok(!out.startsWith('질문이 아주 길게 이어지는 문장 번호 1'),'oldest units dropped first');
});
test('fitCaption without layout uses the requested line band and keeps what fits',()=>{
 const el=captionEl(0);
 assert.equal(fitCaption(el,'짧은 전사',13),'짧은 전사');
 const text=[1,2,3,4,5,6,7,8].map(n=>'아주 길게 이어지는 문장 번호 '+n+'입니다.').join(' ');
 const out=fitCaption(el,text,5);
 assert.ok(el.scrollHeight<=5*32.5+0.75,'fits the five-line fallback band');
 assert.ok(out.endsWith('8입니다.'),'tail preserved');
});
test('fitCaption keeps a fitting tail even when no sentence boundary exists',()=>{
 const el=captionEl(66);
 const out=fitCaption(el,'가'.repeat(120),13);
 assert.ok(el.scrollHeight<=el.clientHeight+0.75,'long unbroken token still fits');
 assert.ok(out.endsWith('가'));
});
