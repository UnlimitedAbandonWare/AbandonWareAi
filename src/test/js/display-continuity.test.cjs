const {test}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
const {createLensReceiver}=require('../../../main/resources/static/assets/display/meta/receiver.js');
const html=fs.readFileSync('main/resources/static/assets/display/meta/index.html','utf8');
const flush=()=>new Promise(setImmediate);
function lensFixture(options={}){
 const timers=new Map(),calls=[];let n=0,reply={conversation:'',hint:''},status=200,fail=false;
 const receiver=createLensReceiver({token:'a'.repeat(64),setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id),
  fetchImpl:async(url,opts)=>{calls.push({url,body:JSON.parse(opts.body)});if(fail)throw Error('offline');return{ok:status===200,status,json:async()=>reply};},...options});
 return {receiver,timers,calls,set(body,code=200){reply=body;status=code;fail=false;},fail(){fail=true;},
  async poll(){await receiver.poll();await flush();},async next(){const item=[...timers][0];assert.ok(item,'retry scheduled');timers.delete(item[0]);await item[1].fn();await flush();}};
}
test('lens text retains valid content through empty, malformed, unavailable and network responses',async()=>{
 const f=lensFixture();f.set({conversation:'대화 <b>글자</b>',hint:'짧은 힌트'});await f.poll();
 assert.equal(f.receiver.state.conversation,'대화 <b>글자</b>');assert.equal(f.receiver.state.hint,'짧은 힌트');
 for(const [body,status] of [[{conversation:'',hint:''},200],['bad',200],[{},503],[{conversation:'새 전사',hint:'a'.repeat(1181)},200]]){
  f.set(body,status);await f.poll();assert.equal(f.receiver.state.conversation,'대화 <b>글자</b>');assert.equal(f.receiver.state.hint,'짧은 힌트');
 }
 f.fail();await f.poll();assert.equal(f.receiver.state.errorCode,'network');
 f.set({conversation:'이어진 전사',hint:''});await f.poll();assert.equal(f.receiver.state.connection,'CONNECTED');assert.equal(f.receiver.state.hint,'짧은 힌트');
 assert.ok(f.calls.every(x=>x.url==='/api/assist/display/lens/text'&&Object.keys(x.body).join()==='token'));f.receiver.dispose();
});
test('lens polls sequentially, retries 503 and stops with actionable invalid-token state',async()=>{
 const f=lensFixture();f.set({},503);f.receiver.start();await flush();assert.equal(f.receiver.state.connection,'RECONNECTING');assert.equal(f.timers.size,1);
 f.set({conversation:'다시 연결됨',hint:''});await f.next();assert.equal(f.receiver.state.connection,'CONNECTED');assert.equal(f.timers.size,1);
 f.set({},404);await f.next();assert.equal(f.receiver.state.connection,'DISCONNECTED');assert.equal(f.receiver.state.errorCode,'lens_link_expired');assert.equal(f.receiver.state.action,'reconnect_from_phone');assert.equal(f.timers.size,0);
 f.receiver.start();await f.poll();assert.equal(f.calls.length,3);f.receiver.dispose();
});
test('pending lens request has bounded timeout and cannot overlap a second poll',async()=>{
 let requests=0,signal;const f=lensFixture({fetchImpl:async(_url,opts)=>{requests++;signal=opts.signal;return new Promise(()=>{});}});
 f.receiver.start();await flush();await f.poll();assert.equal(requests,1);
 const timeout=[...f.timers].find(([,v])=>v.ms===4000);assert.ok(timeout);f.timers.delete(timeout[0]);timeout[1].fn();await flush();
 assert.equal(signal.aborted,true);assert.equal(f.receiver.state.errorCode,'timeout');assert.equal(f.timers.size,1);f.receiver.dispose();assert.equal(f.timers.size,0);
});
const id='12345678-1234-4234-8234-123456789abc';
const view=extra=>({assistId:id,epoch:1,version:1,ready:true,role:'STANDALONE',hintsEnabled:true,captionTtlMs:100000,cardTtlMs:100000,...extra});
function clientFixture(storage,handler){
 const calls=[],timers=new Map();let n=0;
 const c=createClient({transcription:true,standalone:true,storage,uuid:()=>id,setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:k=>timers.delete(k),
  fetchImpl:async(url,opts)=>{const route=url.split('/').slice(4).join('/');calls.push(route);const [body,status=200]=await handler(route,JSON.parse(opts.body));return{ok:status===200,status,headers:{get:()=>null},json:async()=>body};}});
 return {c,calls,timers,async poll(ms=1000){const t=[...timers].find(([,v])=>v.ms===ms);assert.ok(t,'poll timer');timers.delete(t[0]);await t[1].fn();await flush();}};
}
test('Fold reuses its saved grant across reload and only replaces a rejected grant',async()=>{
 const data=new Map(),storage={getItem:k=>data.get(k),setItem:(k,v)=>data.set(k,v)};
 let expired=false,issued=0;
 const handler=async route=>route==='lens/text'?[{conversation:'',hint:''},expired?404:200]:route==='lens/link'?[{token:(++issued===1?'a':'b').repeat(64),expiresAt:Date.now()+43200000}]:[view()];
 const first=clientFixture(storage,handler);first.c.start();await flush();const a=await first.c.lensLink();first.c.dispose();
 const next=clientFixture(storage,handler);next.c.start();await flush();assert.equal(next.c.storedLensLink().token,a.token);assert.equal((await next.c.lensLink()).token,a.token);assert.equal(issued,1);
 expired=true;assert.notEqual((await next.c.lensLink()).token,a.token);assert.equal(issued,2);next.c.dispose();
});
test('temporary validation failure preserves saved grant without silently rotating it',async()=>{
 const saved={token:'a'.repeat(64),expiresAt:Date.now()+43200000};let writes=0;
 const f=clientFixture({getItem:()=>JSON.stringify(saved),setItem:()=>writes++},async route=>route==='lens/text'?[{},503]:[view()]);
 f.c.start();await flush();await assert.rejects(f.c.lensLink());assert.equal(writes,0);assert.equal(f.calls.includes('lens/link'),false);f.c.dispose();
});
test('Fold keeps last text while polling waits and resumes without a new session',async()=>{
 let next=view({caption:{utteranceId:'u',revision:1,isFinal:true,text:'마지막 전사'},card:{requestId:'cue',kind:'CUE',text:'마지막 힌트'}}),status=200;
 const f=clientFixture(null,async()=>[next,status]);f.c.start();await flush();
 status=503;await f.poll();assert.equal(f.c.state.caption.text,'마지막 전사');assert.equal(f.c.state.hint.text,'마지막 힌트');
 status=200;next=view({version:2,caption:null,card:null});await f.poll();assert.equal(f.c.state.caption.text,'마지막 전사');assert.equal(f.c.state.hint.text,'마지막 힌트');assert.equal(f.calls.filter(r=>r==='phone-test').length,1);f.c.dispose();
});

test('lost initial start retries an untouched session normally and preserves real continuation',async()=>{
 for(const audioState of ['OFF','WAITING','STOPPED']){
  const starts=[];let epoch=1;
  const f=clientFixture(null,async(route,body)=>{
   if(route==='audio/start'){
    starts.push(body);
    if(starts.length===1)throw new TypeError('network unavailable');
    epoch=body.continuation?2:1;
    return [view({epoch,audioAvailable:true,audioState:'READY'})];
   }
   return [view({epoch,audioAvailable:true,audioState,audioFinished:audioState==='STOPPED'})];
  });
  await assert.rejects(f.c.beginVoice(),/network unavailable/);
  await f.c.endVoice({finish:true});await f.c.beginVoice({continuation:true});
  assert.equal(starts.length,2);assert.equal(starts[0].continuation,false);
  assert.equal(starts[1].continuation,audioState!=='OFF',audioState);
  assert.equal(starts[1].assistId,id);assert.equal(starts[1].epoch,1);
  await f.c.voiceChunk(0,'AAA=');f.c.dispose();
 }
});

test('expired cached link renews by producer connection without adopting its read token',async()=>{
 const saved={token:'a'.repeat(64),expiresAt:Date.now()-1000,sticky:true};let sent,stored;
 const f=clientFixture({getItem:()=>JSON.stringify(saved),setItem:(_k,v)=>stored=JSON.parse(v)},async(route,body)=>{
  if(route==='lens/link'){sent=body;return[{token:'b'.repeat(64),expiresAt:Date.now()+2592000000,sticky:true}];}return[view()];
 });
 f.c.start();await flush();assert.equal(f.c.storedLensLink(),null);assert.equal(f.c.storedLensLink({includeExpired:true}).token,saved.token);
 const link=await f.c.lensLink();assert.deepEqual(Object.keys(sent),['assistId','epoch','clientId']);assert.equal(link.token,'b'.repeat(64));assert.equal(stored.sticky,true);assert.equal(f.calls.includes('lens/text'),false);f.c.dispose();
});
test('concurrent lens requests issue once and sticky renewal uses lens/link only when due',async()=>{
 const data=new Map(),storage={getItem:k=>data.get(k),setItem:(k,v)=>data.set(k,v)};let issued=0;
 const f=clientFixture(storage,async route=>route==='lens/link'?[{token:'b'.repeat(64),expiresAt:Date.now()+2592000000,sticky:true,...(++issued&&{})}]:route==='lens/text'?[{conversation:'',hint:''}]:[view()]);
 f.c.start();await flush();const a=f.c.lensLink(),b=f.c.lensLink();assert.equal(a,b);assert.equal((await a).token,(await b).token);assert.equal(issued,1);
 await f.c.lensLink();assert.equal(issued,1);assert.ok(f.calls.includes('lens/text'));
 const key=[...data.keys()][0];data.set(key,JSON.stringify({token:'b'.repeat(64),expiresAt:Date.now()+1000,sticky:true}));
 await f.c.lensLink();assert.equal(issued,2);f.c.dispose();
});
