const {test}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const {createClient}=require('../../../main/resources/static/assets/display/display-conversate.js');
const {createLensReceiver}=require('../../../main/resources/static/assets/display/meta/receiver.js');
const html=fs.readFileSync('main/resources/static/assets/display/meta/index.html','utf8');
const flush=()=>new Promise(setImmediate);
const previewKey='awx.display.lensPreview.live',grantKey='awx.display.lens.live';
function previewStorage(){const data=new Map();return {data,getItem:k=>data.get(k)||null,setItem:(k,v)=>data.set(k,v),removeItem:k=>data.delete(k)};}
function previewPage(storage){
 const nodes=new Map(),calls=[],status={textContent:''};let client,observe;
 const get=id=>{if(!nodes.has(id))nodes.set(id,{value:'',textContent:'',hidden:false,style:{},src:'about:blank',open:false,clientWidth:320,files:[],
  classList:{toggle(){}},setAttribute(){},removeAttribute(k){delete this[k];},focus(){},add(){},replaceChildren(){},getClientRects:()=>[],addEventListener(e,fn){this[e]=fn;}});return nodes.get(id);};
 get('lens-preview-frame').contentDocument={getElementById:id=>id==='status'?status:null};
 const host={DisplayCore:{},DisplayConversate:{createClient(options){client=createClient({...options,storage,uuid:()=>id,setTimer:()=>1,clearTimer(){},
  fetchImpl:async(url,options)=>{calls.push(url.split('/').slice(4).join('/'));return {ok:true,status:200,headers:{get:()=>null},json:async()=>view()};}});return client;}},
  DisplayVoice:{createCapture:()=>({state:{},isActive:()=>false,stop(){}})},location:{href:'https://example.test/assets/display/index.html',search:''},addEventListener(){}};
 const context={window:host,location:host.location,document:{body:{hasAttribute:()=>false},getElementById:get,addEventListener(){},visibilityState:'visible'},navigator:{},localStorage:storage,
  URL,URLSearchParams,crypto:{randomUUID:()=>id},setTimeout:()=>1,clearTimeout(){},requestAnimationFrame:fn=>fn(),
  MutationObserver:class{constructor(fn){observe=fn;}observe(){}disconnect(){observe=null;}}};
 vm.runInNewContext(fs.readFileSync('main/resources/static/assets/display/app.js','utf8'),context);
 return {get,calls,client,open(){get('lens-preview-open').onclick();},reject(code){get('lens-preview-frame').onload?.();status.textContent=code===403?'Lens link denied.':'Lens link expired. Create a new glasses address.';observe?.();},dispose(){client.dispose();}};
}

test('preview remembers a pasted address across page recreation without input',async()=>{
 const storage=previewStorage(),a='c'.repeat(64),first=previewPage(storage);await flush();
 first.get('lens-preview-address').value='https://other.example/meta/index.html#view='+a;first.open();first.dispose();
 const next=previewPage(storage);await flush();assert.equal(next.get('lens-preview-address').value,'');next.open();
 assert.equal(new URL(next.get('lens-preview-frame').src).hash,'#view='+a+'&preview=1');
 assert.equal(JSON.parse(storage.getItem(previewKey)).token,a);assert.ok(next.get('lens-preview-address').placeholder.endsWith(a.slice(-6)));next.dispose();
});
test('preview uses its saved address after the Fold grant expires without issuing a grant',async()=>{
 const storage=previewStorage(),a='d'.repeat(64);storage.setItem(previewKey,JSON.stringify({token:a,savedAt:Date.now()}));
 storage.setItem(grantKey,JSON.stringify({token:'a'.repeat(64),expiresAt:Date.now()-1000}));
 const p=previewPage(storage);await flush();assert.equal(p.client.storedLensLink(),null);p.open();
 assert.equal(new URL(p.get('lens-preview-frame').src).hash,'#view='+a+'&preview=1');assert.equal(p.calls.includes('lens/link'),false);p.dispose();
});
test('remembered preview stays blank at page load and makes no lens reads',async()=>{
 const storage=previewStorage(),a='c'.repeat(64);storage.setItem(previewKey,JSON.stringify({token:a,savedAt:Date.now()}));
 const p=previewPage(storage);await flush();assert.equal(p.get('lens-preview-frame').src,'about:blank');assert.equal(p.get('lens-preview').open,false);
 assert.equal(p.calls.includes('lens/text'),false);assert.equal(p.calls.includes('lens/link'),false);p.dispose();
});
test('preview rejection clears only preview storage and asks for a new address',async()=>{
 for(const code of [403,404]){
  const storage=previewStorage(),grant={token:'a'.repeat(64),expiresAt:Date.now()+43200000},remembered='c'.repeat(64);storage.setItem(grantKey,JSON.stringify(grant));
  storage.setItem(previewKey,JSON.stringify({token:remembered,savedAt:Date.now()}));const p=previewPage(storage);await flush();p.open();p.reject(code);
  assert.equal(storage.getItem(previewKey),null);assert.equal(p.get('lens-preview-frame').src,'about:blank');
  assert.match(p.get('lens-preview-note').textContent,/주소를 다시 붙여넣으세요/);assert.deepEqual(JSON.parse(storage.getItem(grantKey)),grant);p.dispose();
 }
});
test('new pasted preview takes priority over Fold and preview saved addresses',async()=>{
 const storage=previewStorage(),a='e'.repeat(64),remembered='c'.repeat(64);storage.setItem(grantKey,JSON.stringify({token:'a'.repeat(64),expiresAt:Date.now()+43200000}));
 storage.setItem(previewKey,JSON.stringify({token:remembered,savedAt:Date.now()}));const p=previewPage(storage);await flush();
 p.get('lens-preview-address').value=a;p.open();assert.equal(new URL(p.get('lens-preview-frame').src).hash,'#view='+a+'&preview=1');
 assert.equal(JSON.parse(storage.getItem(previewKey)).token,a);assert.equal(p.calls.includes('lens/link'),false);p.dispose();
});
test('forgetting preview storage closes the frame without clearing the Fold grant',async()=>{
 const storage=previewStorage(),grant={token:'a'.repeat(64),expiresAt:Date.now()+43200000};storage.setItem(grantKey,JSON.stringify(grant));
 const p=previewPage(storage);await flush();p.open();p.get('lens-preview-clear').onclick();assert.equal(storage.getItem(previewKey),null);
 assert.equal(p.get('lens-preview-frame').src,'about:blank');assert.deepEqual(JSON.parse(storage.getItem(grantKey)),grant);p.dispose();
});
function lensFixture(options={}){
 const timers=new Map(),calls=[];let n=0,reply={conversation:'',hint:''},status=200,fail=false;
 const receiver=createLensReceiver({token:'a'.repeat(64),setTimer(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimer:id=>timers.delete(id),
  fetchImpl:async(url,opts)=>{calls.push({url,body:JSON.parse(opts.body)});if(fail)throw Error('offline');return{ok:status===200,status,json:async()=>reply};},...options});
 return {receiver,timers,calls,set(body,code=200){reply=body;status=code;fail=false;},fail(){fail=true;},
  async poll(){await receiver.poll();await flush();},async next(){const item=[...timers].sort((a,b)=>a[1].ms-b[1].ms)[0];assert.ok(item,'retry scheduled');timers.delete(item[0]);await item[1].fn();await flush();}};
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
 f.set({conversation:'다시 연결됨',hint:''});await f.next();assert.equal(f.receiver.state.connection,'CONNECTED');assert.equal([...f.timers.values()].filter(t=>t.ms===1000).length,1);
 f.set({},404);await f.next();assert.equal(f.receiver.state.connection,'DISCONNECTED');assert.equal(f.receiver.state.errorCode,'lens_link_expired');assert.equal(f.receiver.state.action,'reconnect_from_phone');assert.equal([...f.timers.values()].filter(t=>t.ms<20000).length,0);
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

function standalonePage(storage){
 const nodes=new Map();let client,captureOptions;
 const voice={state:{phase:'OFF',errorCode:null,errorStage:null,permission:'not_requested',events:[],level:0,frames:0,bytes:0},
  isActive(){return this.state.phase==='LISTENING';},start:async()=>true,async stop(){this.state.phase='OFF';},async finish(){},async resume(){return true;},async reconnect(){return false;},async deviceChanged(){return false;}};
 const get=id=>{if(!nodes.has(id))nodes.set(id,{value:'',textContent:'',hidden:false,style:{setProperty(){}},checked:false,open:false,files:[],
  classList:{toggle(){}},setAttribute(){},removeAttribute(){},focus(){},add(){},replaceChildren(){},getClientRects:()=>[1],addEventListener(e,fn){this[e]=fn;}});return nodes.get(id);};
 const host={DisplayCore:{},DisplayConversate:{createClient(options){client=createClient({...options,storage,uuid:()=>id,setTimer:()=>1,clearTimer(){},
   fetchImpl:async()=>({ok:true,status:200,headers:{get:()=>null},json:async()=>view({audioAvailable:true,audioState:'OFF',testStatus:{relay:{eventOwner:'THIS DEVICE',enabled:true,segmentSeconds:0}}})})});return client;}},
  DisplayVoice:{createCapture(options){captureOptions=options;return voice;}},location:{href:'https://example.test/assets/display/index.html',search:''},addEventListener(){}};
 vm.runInNewContext(fs.readFileSync('main/resources/static/assets/display/app.js','utf8'),
  {window:host,location:host.location,document:{body:{hasAttribute:()=>true},getElementById:get,addEventListener(){},visibilityState:'visible',activeElement:null},
   navigator:{},localStorage:storage,Option:class{constructor(text,value){this.text=text;this.value=value;}},URL,URLSearchParams,crypto:{randomUUID:()=>id},
   setTimeout:()=>1,clearTimeout(){},requestAnimationFrame:fn=>fn(),MutationObserver:class{observe(){}disconnect(){}}});
 return {get,client,voice,captureOptions,dispose(){client.dispose();}};
}
test('standalone mic failure shows the mapped reason and code without opening the debug panel',async()=>{
 const storage=previewStorage();storage.setItem('awx.display.settings.live',JSON.stringify({device:'stale-id'}));
 const p=standalonePage(storage);await flush();await flush();
 assert.equal(p.get('input-device').value,'stale-id');
 p.captureOptions.onDeviceFallback();
 assert.equal(p.get('input-device').value,'');
 assert.ok(!('device' in JSON.parse(storage.getItem('awx.display.settings.live'))));
 assert.match(p.get('input-label').textContent,/기본 입력으로 시작/);
 p.voice.state.phase='ERROR';p.voice.state.errorCode='microphone_device_changed';p.voice.state.errorStage='mic_open';
 p.captureOptions.onChange({phase:'ERROR',message:'음성 입력을 준비하지 못했습니다. 오류 코드를 확인해 주세요.',errorCode:'microphone_device_changed'});
 assert.match(p.get('microphone-status').textContent,/\(코드: microphone_device_changed\)/);
 assert.match(p.get('error').textContent,/저장된 입력 장치가 바뀌었습니다.*\(코드: microphone_device_changed\)/);
 assert.match(p.get('debug-state').textContent,/"errorStage": "mic_open"/);
 p.dispose();
});
test('unmapped and non-token errors fall back to the generic line with a safe code only',async()=>{
 const p=standalonePage(previewStorage());await flush();await flush();
 p.voice.state.phase='ERROR';p.voice.state.errorCode='server_mystery';
 p.captureOptions.onChange({phase:'ERROR',message:'x',errorCode:'server_mystery'});
 assert.match(p.get('error').textContent,/연결 상태를 확인하고 다시 시도해 주세요\. \(코드: server_mystery\)/);
 p.captureOptions.onChange({phase:'ERROR',message:'x',errorCode:'PRIVATE <html>'});
 assert.match(p.get('error').textContent,/\(코드: request_failed\)/);
 assert.ok(!p.get('error').textContent.includes('PRIVATE'));
 p.dispose();
});
