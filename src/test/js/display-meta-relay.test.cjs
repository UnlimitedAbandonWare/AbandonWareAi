const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createReceiver}=require('../../../main/resources/static/assets/display/meta/receiver.js');
function fixture(options={}){let reply;const calls=[];const receiver=createReceiver({clientId:'a'.repeat(32),channel:'test-1234567890abcdef',setTimer:options.setTimer||(()=>1),clearTimer:options.clearTimer||(()=>{}),now:()=>100,fetchImpl:async(url,options)=>{calls.push({url,options});return {ok:true,json:async()=>reply};}});return {receiver,calls,set(v){reply=v;}};}
const event=(id,text='new',extra={})=>({serverId:'server-one',eventId:id,generation:1,sentAt:100,enabled:true,producerConnected:true,caption:{text,expiresAt:10100},hint:null,...extra});
test('receiver rejects late or invalid snapshots and acknowledges only rendered event',async()=>{
 const f=fixture();f.set(event(2));await f.receiver.poll();assert.equal(f.receiver.state.caption,'new');
 f.set(event(1,'old'));await f.receiver.poll();assert.equal(f.receiver.state.caption,'new');
 f.set(event(3,'x'.repeat(2049)));await f.receiver.poll();assert.equal(f.receiver.state.eventId,2);
 await f.receiver.acknowledge(2);assert.match(f.calls.at(-1).url,/relay\/ack$/);
 assert.equal(f.calls[0].options.headers['X-Display-Test-Channel'],'test-1234567890abcdef');
 assert.equal(f.calls.some(c=>/phone-test|audio|input/.test(c.url)),false);
});
test('transcript arrives without hint and later hint does not delay or replace it',async()=>{
 const f=fixture();f.set(event(1,'question'));await f.receiver.poll();assert.equal(f.receiver.state.hint,'');
 f.set(event(2,'question',{hint:{text:'short cue',expiresAt:10100}}));await f.receiver.poll();
 assert.equal(f.receiver.state.caption,'question');assert.equal(f.receiver.state.hint,'short cue');
 f.set(event(3,null,{enabled:false,caption:null,hint:null}));await f.receiver.poll();assert.equal(f.receiver.state.caption,'');
});

test('each new hint owns 20 seconds and expiration never clears rolling transcript',async()=>{
 const timers=new Map();let n=0;const f=fixture({setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id)});
 const caption={text:'첫 발화\n다음 발화',expiresAt:Number.MAX_SAFE_INTEGER,rolling:true};
 const hint=(id)=>({text:'힌트 '+id,requestId:id,expiresAt:25100});
 f.set(event(1,null,{caption,hint:hint('one')}));await f.receiver.poll();let t=[...timers.values()].find(t=>t.ms===20000);assert.ok(t);
 f.set(event(2,null,{caption,hint:null}));await f.receiver.poll();assert.equal(f.receiver.state.hint,'힌트 one');assert.equal([...timers.values()].filter(t=>t.ms===20000).length,1);
 t.fn();assert.equal(f.receiver.state.hint,'');assert.equal(f.receiver.state.caption,caption.text);
 f.set(event(3,null,{caption,hint:hint('one')}));await f.receiver.poll();assert.equal(f.receiver.state.hint,'');
 f.set(event(4,null,{caption,hint:hint('two')}));await f.receiver.poll();assert.equal(f.receiver.state.hint,'힌트 two');assert.equal(f.receiver.state.caption,caption.text);f.receiver.dispose();
});

const {createPresentation}=require('../../../main/resources/static/assets/display/meta/receiver.js');
test('minimal card retains the full hint across pages and does not restart on repeated polls',()=>{
 const timers=new Map();let id=0,view;
 const card=createPresentation({setTimer(fn,ms){timers.set(++id,{fn,ms});return id;},clearTimer:id=>timers.delete(id),onChange:v=>view=v});
 const text='확인한 내용만 답하고 아직 확인하지 못한 부분은 먼저 다시 확인하세요. 상대방이 동의했다고 추측하거나 최종 결과가 확정되었다고 말하지 마세요. 앞서 나온 질문과 같은 취지인지 구분해서, 모르면 모른다고 답하는 편이 안전합니다. 마지막 문장은 확인 질문으로 끝내세요.';
 const state={connection:'CONNECTED',enabled:true,producerConnected:true,caption:'진행 중인 전사',hint:text,display:{autoPageMs:5000}};
 card.update(state);const first=view.text;assert.match(view.title,/힌트 1\//);
 card.update({...state});assert.equal(view.text,first);assert.equal(timers.size,1);
 let whole=first;const count=Math.ceil(Array.from(text).length/120);
 for(let n=1;n<count;n++){const timer=[...timers.values()][0];assert.equal(timer.ms,5000);timer.fn();whole+=view.text;}
 assert.equal(whole,text);assert.match(whole,/끝내세요\.$/);
 card.update({...state,hint:''});assert.equal(view.text,'진행 중인 전사');assert.equal(timers.size,0);card.dispose();
});
test('rolling caption opens at the newest page and all earlier text remains reachable',()=>{
 let view;const card=createPresentation({onChange:v=>view=v});
 const text='가'.repeat(120)+'나'.repeat(120)+'마지막 문장';
 card.update({connection:'CONNECTED',enabled:true,producerConnected:true,caption:text,hint:''});
 assert.equal(view.text,'마지막 문장');card.move(-1);assert.equal(view.text,'나'.repeat(120));
 card.move(-1);assert.equal(view.text,'가'.repeat(120));card.dispose();
});
