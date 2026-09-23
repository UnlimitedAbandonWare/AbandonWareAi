const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const html=fs.readFileSync('main/resources/static/assets/display/meta/index.html','utf8');
const receiverCode=fs.readFileSync('main/resources/static/assets/display/meta/receiver.js','utf8');
const flush=()=>new Promise(setImmediate);
function page(hash='',search=''){
 const nodes={transcript:{textContent:''},hint:{textContent:'',hidden:true},status:{textContent:'',hidden:true}},timers=new Map(),requests=[];let n=0;
 class Xhr{open(method,url){this.method=method;this.url=url;}setRequestHeader(){}send(body){this.body=JSON.parse(body);requests.push(this);}abort(){}reply(status,body){this.status=status;this.responseText=JSON.stringify(body);this.onload?.();}}
 const host={location:{hash,search},document:{hidden:false,getElementById:id=>nodes[id]||null,querySelector:()=>null,addEventListener(){}},XMLHttpRequest:Xhr,URLSearchParams,
  Date:{now:()=>nowMs},setTimeout(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimeout:id=>timers.delete(id),addEventListener(){}};
 let nowMs=0;
 host.window=host;vm.runInNewContext(receiverCode,host);return{nodes,timers,requests,host,advance(ms){nowMs+=ms;}};
}
test('bare or invalid lens URL stays blank with no microphone dependency, sample, clock or network',()=>{
 for(const hash of ['', '#view=bad']){
  const p=page(hash);assert.equal(p.nodes.transcript.textContent,'');assert.equal(p.nodes.hint.textContent,'');assert.equal(p.requests.length,0);assert.equal(p.timers.size,0);
 }
 assert.match(html,/height:\s*600px/);assert.match(html,/overflow:\s*hidden/);assert.doesNotMatch(html,/innerHTML|localStorage|serviceWorker|HELLO|DISPLAY TEST|<button|<input|id="connection"/);
});
test('mounted view token updates only text slots and never calls relay or ACK',async()=>{
 const p=page('#view='+'a'.repeat(64),'?clientRole=test&channel=test-1234567890abcdef');
 assert.equal(p.requests.length,1);assert.equal(p.requests[0].url,'/api/assist/display/lens/text');assert.deepEqual(Object.keys(p.requests[0].body),['token']);
 p.requests[0].reply(200,{conversation:'<b>회의는 내일입니다.</b>',hint:'시간을 확인하세요.'});await flush();
 assert.equal(p.nodes.transcript.textContent,'<b>회의는 내일입니다.</b>');assert.equal(p.nodes.hint.textContent,'시간을 확인하세요.');assert.equal(p.nodes.hint.hidden,false);assert.equal(p.timers.size,3);
 const [id,timer]=[...p.timers].find(([,v])=>v.ms===1000);p.timers.delete(id);timer.fn();assert.equal(p.requests.length,2);
 p.requests[1].reply(503,{});await flush();assert.equal(p.nodes.transcript.textContent,'<b>회의는 내일입니다.</b>');assert.equal(p.nodes.hint.textContent,'시간을 확인하세요.');
});
test('a long lens hint is paged without loss and expires on the first-show clock',async()=>{
 const p=page('#view='+'a'.repeat(64),'?clientRole=test&channel=test-1234567890abcdef');
 const hint='가'.repeat(600),fire=ms=>{const hit=[...p.timers].find(([,v])=>v.ms===ms);assert.ok(hit,'timer '+ms);p.timers.delete(hit[0]);return hit[1].fn();};
 // No autoPageMs override: the default 5 s interval is used exactly as configured —
 // under a 20 s TTL a 5-page hint never reaches page 5, and the interval is never
 // shortened to squeeze the last page in before expiry.
 p.requests[0].reply(200,{conversation:'진행 전사',hint,hintId:'req-long',display:{transcriptTtlMs:100000}});await flush();
 assert.equal(p.nodes.hint.textContent,hint.slice(0,120));assert.equal(p.nodes.status.textContent,'힌트 1/5');
 let whole=hint.slice(0,120);
 for(let i=0;i<2;i++){p.advance(5000);fire(5000);whole+=p.nodes.hint.textContent;}
 assert.equal(p.nodes.status.textContent,'힌트 3/5');
 fire(1000);p.requests[1].reply(200,{conversation:'진행 전사',hint,hintId:'req-long',display:{transcriptTtlMs:100000}});await flush();
 assert.equal(p.nodes.hint.textContent,hint.slice(240,360),'repeated poll must not reset the page');
 p.advance(5000);fire(5000);whole+=p.nodes.hint.textContent;
 assert.equal(p.nodes.status.textContent,'힌트 4/5');assert.equal(whole,hint.slice(0,480),'reached pages lose nothing');
 p.advance(5000);fire(20000);assert.equal(p.nodes.hint.hidden,true);assert.equal(p.nodes.hint.textContent,'');
 assert.equal(p.nodes.transcript.textContent,'진행 전사','transcript keeps its own hold past the hint TTL');
});
test('explicit relay test channel remains isolated from view-token transport and shows no sample',async()=>{
 const p=page('','?clientRole=test&channel=test-1234567890abcdef');
 assert.equal(p.requests.length,1);assert.equal(p.requests[0].url,'/api/assist/display/relay/poll');
 p.requests[0].reply(200,{serverId:'s',eventId:1,generation:0,sentAt:100,enabled:true,caption:null,hint:null});await flush();
 assert.equal(p.nodes.transcript.textContent,'');assert.equal(p.nodes.hint.textContent,'');
});
function phone(){
 const elements=new Map(),timers=new Map();let n=0,change,attempts=0,fail=true;
 const node=()=>({textContent:'',value:'',hidden:false,disabled:false,files:[],classList:{toggle(){}},setAttribute(){},focus(){},add(){},addEventListener(){},replaceChildren(){},getClientRects:()=>[]});
 const get=id=>{if(!elements.has(id))elements.set(id,node());return elements.get(id);};
 const saved={token:'a'.repeat(64),expiresAt:Date.now()+43200000};
 const c={state:{connection:'PREPARING',role:'DISPLAY',ready:false},storedLensLink:()=>saved,lensLink:async()=>{attempts++;if(fail)throw Object.assign(Error('display_http'),{status:503});return saved;},
 start(){change(this.state);},dispose(){},pause(){},reconnect(){},acknowledge:async()=>{}};
 const host={DisplayCore:{},DisplayConversate:{createClient(opts){change=opts.onChange;return c;}},DisplayVoice:{createCapture:()=>({state:{},isActive:()=>false,stop(){}})},location:{href:'https://example.test/assets/display/index.html',search:''},addEventListener(){}};
 const context={window:host,location:host.location,document:{body:{hasAttribute:()=>false},getElementById:get,addEventListener(){},visibilityState:'visible'},navigator:{},localStorage:{getItem:()=>null},
 URL,URLSearchParams,crypto:{randomUUID:()=> 'a'.repeat(32)},setTimeout(fn,ms){timers.set(++n,{fn,ms});return n;},clearTimeout:id=>timers.delete(id),requestAnimationFrame:fn=>fn()};
 vm.runInNewContext(fs.readFileSync('main/resources/static/assets/display/app.js','utf8'),context);
 return{c,elements,timers,get attempts(){return attempts;},setFail(v){fail=v;},emit(connection,assistId='session-one'){Object.assign(c.state,{connection,ready:connection==='READY',assistId,epoch:1});change(c.state);}};
}
test('Fold retries a transient saved-link failure after READY recovery without a per-render loop',async()=>{
 const p=phone();p.emit('READY');await flush();assert.equal(p.attempts,1);
 for(let i=0;i<8;i++)p.emit('READY');await flush();assert.equal(p.attempts,1);assert.equal(p.timers.size,1);
 p.emit('RECONNECTING');p.setFail(false);p.emit('READY');await flush();assert.equal(p.attempts,2);
 assert.match(p.elements.get('lens-address').value,/#view=[a-f0-9]{64}$/);assert.equal(p.timers.size,0);
 for(let i=0;i<8;i++)p.emit('READY');await flush();assert.equal(p.attempts,2);
});
