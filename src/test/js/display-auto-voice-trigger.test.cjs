const {test}=require('node:test');
const assert=require('node:assert/strict');
const {normDisplay,fitAtomicHint,createLensReceiver}=require('../../../main/resources/static/assets/display/meta/receiver.js');
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');

test('auto presentation keeps its own ranges without changing legacy pages',()=>{
  const d=normDisplay({hintPageLines:11,autoPageMs:5000,autoVoiceTrigger:{modeEnabled:true,hintsEnabled:true,hintLines:3,hintChars:200}});
  assert.equal(d.hintPageLines,11);assert.equal(d.autoPageMs,5000);
  assert.equal(d.autoVoiceTrigger.hintLines,3);assert.equal(d.autoVoiceTrigger.hintChars,200);
});
test('atomic fit retains complete sentences under code-point and measured line limits',()=>{
  const parent={appendChild(){}};
  const make=()=>({style:{},parentNode:parent,textContent:'',clientWidth:100,clientHeight:100,
    ownerDocument:{defaultView:{getComputedStyle:()=>({lineHeight:'20px',fontSize:'16px'})}},
    get scrollHeight(){return Math.ceil(Array.from(this.textContent).length/10)*20;},remove(){},cloneNode:make});
  const el=make(),text='첫 문장을 확인하세요. 다음 문장을 확인하세요. 마지막 문장을 확인하세요.';
  const fit=fitAtomicHint(el,text,3,200);
  assert.ok(fit.endsWith('.'));assert.ok(Array.from(fit).length<=30);assert.notEqual(fit,text);
  assert.equal(el.textContent,'','measurement must not expose candidate strings');
});
test('no complete sentence fits, so only a measured complete notice is committed',()=>{
  const parent={appendChild(){}};
  const make=()=>({style:{},parentNode:parent,textContent:'',clientWidth:100,clientHeight:60,
    ownerDocument:{defaultView:{getComputedStyle:()=>({lineHeight:'20px',fontSize:'16px'})}},
    get scrollHeight(){return Math.ceil(Array.from(this.textContent).length/10)*20;},remove(){},cloneNode:make});
  for(const language of ['ko','en']){
    const el=make(),fit=fitAtomicHint(el,'a'.repeat(70)+'.',3,200,language);
    assert.equal(fit,language==='en'?'Hint is too long.':'힌트가 너무 길어요.');
    assert.ok(Math.ceil(Array.from(fit).length/10)<=3);assert.equal(el.textContent,'');
  }
});

test('presentation lease withdraws a hint independently of its first-show TTL',async()=>{
  let now=1000,id=0,reply;const timers=new Map();
  const r=createLensReceiver({token:'a'.repeat(64),now:()=>now,setTimer(fn,ms){timers.set(++id,{fn,ms});return id;},clearTimer(id){timers.delete(id);},
    fetchImpl:async()=>({ok:true,json:async()=>reply})});
  reply={conversation:'전사',hint:'완성된 힌트입니다.',hintId:'one',display:{autoVoiceTrigger:{modeEnabled:true,hintsEnabled:true,hintLines:3,hintChars:200}},
    autoVoiceTrigger:{state:'ACTIVE',activationValidUntil:6000,hintDisplayValidUntil:6000}};
  await r.poll();assert.equal(r.state.hint,reply.hint);const ttl=r.state.hintExpiresAt;
  now=3000;reply={...reply,hint:'같은 ID를 부분 갱신하면 안 됩니다.',autoVoiceTrigger:{...reply.autoVoiceTrigger,activationValidUntil:8000,hintDisplayValidUntil:8000}};
  await r.poll();assert.equal(r.state.hint,'완성된 힌트입니다.');assert.equal(r.state.hintExpiresAt,ttl);
  now=8001;for(const t of [...timers.values()])if(t.ms===5000)t.fn();
  assert.equal(r.state.hint,'');assert.equal(r.state.conversation,'전사');
  await r.poll();assert.equal(r.state.hint,'');r.dispose();
});
test('producer rejects expired grants and retires the shown hint at grant expiry',async()=>{
  const id='12345678-1234-4234-8234-123456789abc';let now=1000,seq=0,reply;const timers=new Map();
  const base={assistId:id,epoch:1,version:1,ready:true,audioAvailable:true,audioState:'OFF',role:'STANDALONE',hintsEnabled:true,captionTtlMs:20000,cardTtlMs:20000,
    autoVoiceSettings:{modeEnabled:true,hintsEnabled:true},autoVoiceRuntime:{state:'ACTIVE',activationValidUntil:6000,hintDisplayValidUntil:6000},card:{kind:'CUE',text:'완성된 힌트입니다.',requestId:'one',expiresAt:21000}};
  reply={...base,autoVoiceRuntime:{...base.autoVoiceRuntime,activationValidUntil:999,hintDisplayValidUntil:999}};
  const c=createClient({transcription:true,standalone:true,uuid:()=>id,wallNow:()=>now,setTimer(fn,ms){timers.set(++seq,{fn,ms});return seq;},clearTimer:n=>timers.delete(n),fetchImpl:async()=>({ok:true,headers:{get:()=>null},json:async()=>reply})});
  await c.hints(true);assert.equal(c.state.hint,null);
  reply=base;await c.hints(true);assert.equal(c.state.hint.text,base.card.text);
  now=6001;for(const t of [...timers.values()])if(t.ms===5000)t.fn();assert.equal(c.state.hint,null);
  c.setAutoVoicePresentation(false);c.setAutoVoicePresentation(true);reply={...base,autoVoiceRuntime:{...base.autoVoiceRuntime,activationValidUntil:11001,hintDisplayValidUntil:11001}};
  await c.hints(true);assert.equal(c.state.hint,null);c.dispose();
});
test('automatic renewal cannot transfer consent to a new assist',async()=>{
  const id='12345678-1234-4234-8234-123456789abc',other='22345678-1234-4234-8234-123456789abc';let reply,starts=0;
  const timers=new Map();let next=0;
  const base={assistId:id,epoch:1,version:1,ready:true,audioAvailable:true,audioState:'OFF',role:'STANDALONE',hintsEnabled:false,captionTtlMs:0,cardTtlMs:0,autoVoiceSettings:{modeEnabled:true,hintsEnabled:true},autoVoiceRuntime:{state:'DISARMED'}};reply=base;
  const c=createClient({transcription:true,standalone:true,uuid:()=>id,setTimer(fn,ms){timers.set(++next,{fn,ms});return next;},clearTimer:n=>timers.delete(n),fetchImpl:async(url)=>{if(url.endsWith('/audio/start')){starts++;return {ok:true,headers:{get:()=>null},json:async()=>({...base,audioState:'READY',autoVoiceRuntime:{state:'ARMED'}})};}return {ok:true,headers:{get:()=>null},json:async()=>reply};}});
  await c.hints(true);await c.beginVoice({autoVoiceConsent:true});await c.endVoice({finish:true});const previous=starts;
  reply={...base,assistId:other};await assert.rejects(c.beginVoice({continuation:true,autoVoiceConsent:true}),/reprepare/);assert.equal(starts,previous);c.dispose();
});

test('new final question immediately withdraws and retires the previous auto hint',async()=>{
  const id='12345678-1234-4234-8234-123456789abc';let reply,seq=0;const timers=new Map();
  const base={assistId:id,epoch:1,version:1,ready:true,audioAvailable:true,audioState:'READY',role:'STANDALONE',hintsEnabled:true,captionTtlMs:20000,cardTtlMs:20000,
    autoVoiceSettings:{modeEnabled:true,hintsEnabled:true},autoVoiceRuntime:{state:'ACTIVE',activationValidUntil:6000,hintDisplayValidUntil:6000},card:{kind:'CUE',text:'이전 질문의 힌트입니다.',requestId:'old',expiresAt:21000}};
  reply=base;
  const c=createClient({transcription:true,standalone:true,uuid:()=>id,wallNow:()=>1000,setTimer(fn,ms){timers.set(++seq,{fn,ms});return seq;},clearTimer:n=>timers.delete(n),fetchImpl:async()=>({ok:true,headers:{get:()=>null},json:async()=>reply})});
  await c.hints(true);assert.equal(c.state.hint.requestId,'old');
  reply={...base,version:2,processing:true,card:null,caption:{utteranceId:'next-question',revision:1,isFinal:true,text:'다음 질문을 설명해 주세요.'}};
  await c.hints(true);assert.equal(c.state.hint,null,'pending next question must hide the stale card without waiting for a timer');
  reply={...base,version:3};await c.hints(true);assert.equal(c.state.hint,null,'retired old hint must not return in a later response');
  reply={...base,version:4,card:{...base.card,requestId:'new',text:'새 질문의 완성 힌트입니다.'}};
  await c.hints(true);assert.equal(c.state.hint.requestId,'new');c.dispose();
});
