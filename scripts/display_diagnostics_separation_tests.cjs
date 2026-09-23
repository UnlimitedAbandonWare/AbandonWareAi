const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const base=__dirname+'/../main/resources/static/assets/display/';
test('lens document has only empty reading content, with no controls or diagnostic loader',()=>{
  const html=fs.readFileSync(base+'lens.html','utf8');
  assert.doesNotMatch(html,/<(?:button|input|select|form|details|header|nav)\b/);
  assert.doesNotMatch(html,/서버 연결 중|마이크 대기|전사 진단|display-voice\.js|src="app\.js|diagnostics\.js/);
  assert.match(html,/src="lens\.js/);
});
function lens(){
  const elements=new Map();const handlers={};let options,acks=0;
  const document={visibilityState:'visible',getElementById(id){if(!elements.has(id))elements.set(id,{textContent:'',hidden:false});return elements.get(id);},addEventListener(k,fn){handlers[k]=fn;}};
  const client={start(){},pause(){},dispose(){},acknowledge(){acks++;return Promise.resolve();}};
  const window={DisplayConversate:{createClient(o){options=o;return client;}},addEventListener(k,fn){handlers[k]=fn;}};
  vm.runInNewContext(fs.readFileSync(base+'lens.js','utf8'),{window,document,requestAnimationFrame:fn=>fn()});
  return{render:options.onChange,elements,handlers,get acks(){return acks;}};
}
test('lens remains blank during startup, faults and developer state; renders only content',()=>{
  const f=lens();const caption={utteranceId:'synthetic',revision:1,isFinal:true,text:'합성 전사'};
  const common={epoch:1,version:1,connection:'READY',role:'DISPLAY',caption,hint:{kind:'SUGGESTION',text:'합성 힌트',sourceTitles:['짧은 근거']}};
  f.render(common);assert.equal(f.elements.get('caption-text').textContent,'합성 전사');
  assert.equal(f.elements.get('hint-text').textContent,'합성 힌트');assert.equal(f.elements.get('evidence-text').textContent,'짧은 근거');
  for(const connection of ['PREPARING','RECONNECTING','DISCONNECTED','PAUSED']){
    f.render({...common,connection,caption:null,hint:null,error:{message:'SECRET PROVIDER ERROR'},audioRuntime:{model:'private-model'}});
    assert.ok([...f.elements.values()].every(e=>e.textContent===''));
  }
  f.render({...common,hint:{kind:'STATUS',text:'검색 중',sourceTitles:[]}});assert.equal(f.elements.get('hint-text').textContent,'');
  f.render({...common,caption:null,hint:null,diagnosticsEnabled:true});assert.ok([...f.elements.values()].every(e=>e.textContent===''));
});
test('developer observer is off by default and ending observation only cancels its own request',async()=>{
  const {createObserver}=require(base+'diagnostics.js');let calls=0,aborted=false,resolve;
  const timers=new Map();let next=0;
  const observer=createObserver({fetchImpl:async(url,opts)=>{calls++;assert.equal(url,'/api/diagnostics/display?enabled=true');assert.equal(opts.credentials,'same-origin');opts.signal.addEventListener('abort',()=>{aborted=true;});return await new Promise(r=>resolve=r);},setTimer(fn,ms){timers.set(++next,{fn,ms});return next;},clearTimer(id){timers.delete(id);}});
  assert.equal(calls,0);observer.start();assert.equal(calls,1);observer.stop();assert.equal(aborted,true);
  resolve({ok:true,json:async()=>({observedAt:1})});await new Promise(setImmediate);assert.equal(timers.size,0);assert.equal(calls,1);
});
for(const kind of ['CUE','RAG_CUE'])test(`live ${kind} survives the lens transport and renderer without diagnostics`,async()=>{
  const {createClient}=require(base+'display-conversate.js');const f=lens();let latest;
  const c=createClient({transcription:true,lens:true,uuid:()=> '12345678-1234-4234-8234-123456789abc',setTimer:()=>1,clearTimer(){},onChange:s=>{latest=s;f.render(s);},fetchImpl:async()=>({ok:true,json:async()=>({assistId:'12345678-1234-4234-8234-123456789abc',epoch:1,version:1,ready:true,card:{kind,text:'실제 종류의 짧은 힌트',sourceTitles:['짧은 근거']},caption:null,captionTtlMs:0,cardTtlMs:15000})})});
  c.start();await new Promise(setImmediate);
  assert.equal(latest.hint?.kind,kind);assert.equal(f.elements.get('hint-text').textContent,'실제 종류의 짧은 힌트');assert.equal(f.elements.get('evidence-text').textContent,'짧은 근거');
  c.dispose();
});
test('lens transport requests only the server content projection, including ACKs',async()=>{
  const {createClient}=require(base+'display-conversate.js');const routes=[];
  const c=createClient({transcription:true,lens:true,uuid:()=> '12345678-1234-4234-8234-123456789abc',setTimer:()=>1,clearTimer(){},fetchImpl:async route=>{routes.push(route);return{ok:true,json:async()=>({assistId:'12345678-1234-4234-8234-123456789abc',epoch:1,version:1,ready:true,card:null,caption:null,captionTtlMs:0,cardTtlMs:0})};}});
  c.start();await new Promise(setImmediate);await c.acknowledge(1,'caption_rendered');c.dispose();
  assert.deepEqual(routes,['/api/assist/display/lens','/api/assist/display/lens/ack']);
});
