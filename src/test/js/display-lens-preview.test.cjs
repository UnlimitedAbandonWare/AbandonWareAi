const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const {createLensReceiver}=require('../../../main/resources/static/assets/display/meta/receiver.js');
const flush=()=>new Promise(setImmediate),grant={token:'a'.repeat(64)};
function fixtureToken(value){return value.token;}
const token=fixtureToken(grant);
test('lens reader sends preview only when explicitly true',async()=>{
 for(const preview of [undefined,false,true]){
  const requests=[],r=createLensReceiver({token,preview,host:{AbortController},setTimer:()=>1,clearTimer(){},
   fetchImpl:async(url,options)=>{requests.push(JSON.parse(options.body));return {ok:true,json:async()=>({conversation:'',hint:''})};}});
  await r.poll();r.dispose();assert.deepEqual(requests,[preview===true?{token,preview:true}:{token}]);
 }
});
function lensPage(preview){
 const requests=[],notes=[],receipts=[],nodes={transcript:{textContent:'',style:{}},hint:{textContent:'',style:{}},status:{textContent:''},'nova-focus':{}};
 const host={document:{hidden:false,getElementById:id=>nodes[id]||null,querySelector:()=>null,addEventListener(){}},location:{search:'',hash:'#view='+token+(preview?'&preview=1':'')},URLSearchParams,AbortController,
  setTimeout:()=>1,clearTimeout(){},addEventListener(){},console:{debug(...args){notes.push(args);}},
  NovaFocus:{receiptSender(){return (...args)=>receipts.push(args);},createProjection(options){options.receipt({event:'first_visible'});return {update:()=>false};}},
  async fetch(url,options){requests.push({url,body:JSON.parse(options.body)});return {ok:true,json:async()=>({conversation:'',hint:''})};}};
 host.window=host;vm.runInNewContext(fs.readFileSync('main/resources/static/assets/display/meta/receiver.js','utf8'),host);return {requests,notes,receipts};
}
test('view preview hash reaches reader and disables presentation receipts and diagnostics',async()=>{
 const p=lensPage(true);await flush();assert.equal(p.requests.length,1);assert.deepEqual(p.requests[0].body,{token,preview:true});assert.equal(p.receipts.length,0);assert.equal(p.notes.length,0);
 const ordinary=lensPage(false);await flush();assert.deepEqual(ordinary.requests[0].body,{token});assert.equal(ordinary.receipts.length,1);
});
function foldPage(saved={token,expiresAt:Date.now()+43200000},search=''){
 const nodes=new Map(),handlers={};let change,restores=0;
 const node=()=>({textContent:'',value:'',hidden:false,disabled:false,files:[],style:{},clientWidth:320,src:'about:blank',open:false,
  classList:{toggle(){}},setAttribute(){},removeAttribute(key){delete this[key];},focus(){},add(){},addEventListener(event,fn){this[event]=fn;},replaceChildren(){},getClientRects:()=>[]});
 const get=id=>{if(!nodes.has(id))nodes.set(id,node());return nodes.get(id);};
 const client={state:{connection:'PREPARING',role:'DISPLAY',ready:false},storedLensLink:()=>saved,
  lensLink:async()=>{restores++;return saved;},start(){change(this.state);},dispose(){},pause(){},reconnect(){},acknowledge:async()=>{}};
 const host={DisplayCore:{},DisplayConversate:{createClient(opts){change=opts.onChange;return client;}},DisplayVoice:{createCapture:()=>({state:{},isActive:()=>false,stop(){}})},location:{href:'https://example.test/assets/display/index.html',search},addEventListener(event,fn){handlers[event]=fn;}};
 const context={window:host,location:host.location,document:{body:{hasAttribute:()=>false},getElementById:get,addEventListener(){},visibilityState:'visible'},navigator:{},localStorage:{getItem:()=>null},
  URL,URLSearchParams,crypto:{randomUUID:()=> 'a'.repeat(32)},setTimeout:()=>1,clearTimeout(){},requestAnimationFrame:fn=>fn()};
 vm.runInNewContext(fs.readFileSync('main/resources/static/assets/display/app.js','utf8'),context);
 return {get,handlers,get restores(){return restores;},ready(){Object.assign(client.state,{connection:'READY',ready:true,assistId:'fixture',epoch:1});change(client.state);}};
}
test('Fold saved preview starts blank, scales the actual iframe, and stops on close or collapse',async()=>{
 const p=foldPage();p.ready();await flush();assert.equal(p.restores,0);assert.equal(p.get('lens-preview-frame').src,'about:blank');
 p.get('lens-preview-open').onclick();assert.match(p.get('lens-preview-frame').src,/#view=[a-f0-9]{64}&preview=1$/);assert.equal(p.get('lens-preview-note').textContent,'안경 화면 · …'+token.slice(-6));
 for(const width of [320,840]){p.get('lens-preview-viewport').clientWidth=width;p.handlers.resize();assert.equal(p.get('lens-preview-viewport').style.height,Math.min(600,width)+'px');assert.equal(p.get('lens-preview-frame').style.transform,'scale('+Math.min(1,width/600)+')');}
 p.get('lens-preview-close').onclick();assert.equal(p.get('lens-preview-frame').src,'about:blank');
 p.get('lens-preview-open').onclick();p.get('lens-preview').open=false;p.get('lens-preview').toggle();assert.equal(p.get('lens-preview-frame').src,'about:blank');assert.equal(p.restores,0);
});
test('Fold accepts pasted view URLs or exact tokens and never embeds their supplied origin',()=>{
 const p=foldPage(null);p.get('lens-preview-open').onclick();assert.equal(p.get('lens-preview-note').textContent,'먼저 안경 연결 주소 만들기를 누르세요');
 for(const input of [token,'https://other.example/meta/index.html#view='+token]){p.get('lens-preview-address').value=input;p.get('lens-preview-open').onclick();assert.equal(new URL(p.get('lens-preview-frame').src).origin,'https://example.test');assert.equal(p.get('lens-preview-address').value,'');}
 for(const input of ['bad',token.toUpperCase(),'https://other.example/#view=bad']){p.get('lens-preview-address').value=input;p.get('lens-preview-open').onclick();assert.equal(p.get('lens-preview-frame').src,'about:blank');}
});
test('Fold explicit test channel opens a preview URL while view renderer remains read only',()=>{
 const p=foldPage(null,'?mode=phone-test&clientRole=test&channel=synthetic-preview');p.get('lens-preview-open').onclick();
 const url=new URL(p.get('lens-preview-frame').src);assert.equal(url.searchParams.get('preview'),'1');assert.equal(url.searchParams.get('channel'),'synthetic-preview');
});
