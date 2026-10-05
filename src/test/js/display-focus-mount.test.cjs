const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const flush=()=>new Promise(setImmediate);
function page(){
 const nodes={},events={},frames=new Map(),timers=new Map(),requests=[];let id=0,now=0;
 const doc={hidden:false,addEventListener(name,fn){events[name]=fn;},getElementById:name=>nodes[name]||null,querySelector:()=>nodes.stage,createElement:()=>node()};
 function node(){const n={textContent:'',hidden:false,children:[],style:{setProperty(){}},classList:{toggle(){}},ownerDocument:doc,clientHeight:260,
  appendChild(child){child.parent=this;this.children.push(child);},remove(){this.parent.children.splice(this.parent.children.indexOf(this),1);}};
  Object.defineProperty(n,'lastElementChild',{get:()=>n.children.at(-1)});Object.defineProperty(n,'scrollHeight',{get:()=>Math.max(1,Math.ceil(Array.from(n.textContent).length/12))*32.5});return n;}
 for(const name of ['stage','transcript','hint','status','nova-focus','nova-status','nova-draft','nova-answer'])nodes[name]=node();
 let reply={conversation:'계속 들어오는 전사',hint:''};
 const host={document:doc,location:{hash:'#view='+'a'.repeat(64),search:''},URLSearchParams,AbortController,Intl,Date,console:{debug(){}},getComputedStyle:()=>({lineHeight:'32.5px',fontSize:'26px'}),
  requestAnimationFrame(fn){frames.set(++id,fn);return id;},cancelAnimationFrame:id=>frames.delete(id),setTimeout(fn,ms){timers.set(++id,{fn,ms});return id;},clearTimeout:id=>timers.delete(id),addEventListener(){},
  async fetch(url,opts){requests.push({url,body:JSON.parse(opts.body)});return {ok:true,status:200,json:async()=>reply};}};
 doc.defaultView=host;host.window=host;host.globalThis=host;
 vm.createContext(host);for(const path of ['display-focus-flow.js','display-focus.js','meta/receiver.js'])vm.runInContext(fs.readFileSync('main/resources/static/assets/display/'+path,'utf8'),host);
 return {host,nodes,requests,events,frames,set(value){reply=value;},async poll(){await host.DisplayReceiver.current.poll();await flush();},advance(ms){for(let t=0;t<ms;t+=20){now+=20;const f=[...frames.values()];frames.clear();f.forEach(fn=>fn(now));}}};
}
test('actual lens/text to mountLens renders focus beyond hint TTL then resumes latest captions',async()=>{
 const p=page();await flush();const focus={schemaVersion:1,active:true,phase:'ANSWER_READY',serverInstanceId:'boot',activationId:'a',turnId:'t',stateVersion:1,answerVersion:1,draftText:'',questionText:'긴 질문',answerText:'가'.repeat(600),idleRemainingMs:0,renderTarget:'lens',renderReceiptTicket:'b'.repeat(64)};
 p.set({conversation:'새로운 전사',hint:'',focus});await p.poll();assert.equal(p.nodes['nova-focus'].hidden,false);assert.equal(p.nodes.transcript.hidden,true);
 for(let i=0;i<20;i++){p.advance(1000);await p.poll();}
 assert.equal(p.requests.filter(x=>x.body.event==='presentation_done').length,0);assert.equal(p.nodes['nova-focus'].hidden,false);
 for(let i=0;i<50;i++){p.advance(1000);await p.poll();}
 assert.equal(p.requests.filter(x=>x.body.event==='first_visible').length,1);assert.equal(p.requests.filter(x=>x.body.event==='presentation_done').length,1);
 assert.ok(p.nodes['nova-answer'].children.length<=7);assert.equal(p.nodes['nova-answer'].style.opacity,'0');
 let prevented=false;p.events.keydown({key:'Enter',preventDefault(){prevented=true;}});assert.equal(prevented,true);
 p.set({conversation:'복귀 후 최신 전사',hint:'',focus:{...focus,active:false,phase:'ARMED',stateVersion:2,answerText:''}});await p.poll();assert.equal(p.nodes['nova-focus'].hidden,true);assert.equal(p.nodes.transcript.hidden,false);assert.equal(p.nodes.transcript.textContent,'복귀 후 최신 전사');
 assert.ok(p.requests.every(x=>['/api/assist/display/lens/text','/api/assist/display/focus/rendered'].includes(x.url)));
});

test('font settings freeze an in-flight answer and apply to the next answer without duplicate receipts',async()=>{
 const p=page();await flush();let font=20;
 p.host.getComputedStyle=el=>({fontSize:el.style.fontSize||font+'px',lineHeight:(parseFloat(el.style.fontSize)||font)*1.25+'px'});
 const focus={schemaVersion:1,active:true,phase:'ANSWER_READY',serverInstanceId:'boot',activationId:'a',turnId:'t',stateVersion:1,answerVersion:1,draftText:'',questionText:'합성 질문',idleRemainingMs:0,answerText:'한글👨‍👩‍👧‍👦é'.repeat(10),renderTarget:'lens',renderReceiptTicket:'b'.repeat(64)};
 p.set({conversation:'',hint:'',focus});await p.poll();p.advance(500);
 assert.equal(p.nodes['nova-answer'].style.fontSize,'20px');
 const receipts=()=>p.requests.filter(x=>x.body.event==='first_visible').length;
 const before=receipts();font=36;await p.poll();p.advance(500);
 assert.equal(p.nodes['nova-answer'].style.fontSize,'20px');assert.equal(receipts(),before);
 p.set({conversation:'',hint:'',focus:{...focus,turnId:'next',answerVersion:2,stateVersion:2}});await p.poll();p.advance(500);
 assert.equal(p.nodes['nova-answer'].style.fontSize,'36px');assert.equal(receipts(),before+1);
 font=26;p.set({conversation:'',hint:'',focus:{...focus,turnId:'third',answerVersion:3,stateVersion:3}});await p.poll();
 assert.equal(p.nodes['nova-answer'].style.fontSize,'26px');
 assert.ok(p.requests.every(x=>['/api/assist/display/lens/text','/api/assist/display/focus/rendered'].includes(x.url)));
});

test('actual Fold app applies the delivered font before snapshotting a newly received answer',()=>{
 const Flow=require('../../../main/resources/static/assets/display/display-focus-flow.js'),nodes=new Map();let change;
 const doc={body:{hasAttribute:()=>true},visibilityState:'visible',addEventListener(){},getElementById:id=>get(id),createElement:()=>make()};
 const make=()=>({textContent:'',value:'0',hidden:false,disabled:false,files:[],children:[],style:{setProperty(k,v){this[k]=v;}},clientWidth:320,clientHeight:288,src:'about:blank',open:false,ownerDocument:doc,classList:{toggle(){}},setAttribute(){},removeAttribute(){},focus(){},add(){},addEventListener(){},replaceChildren(){},appendChild(c){this.children.push(c);},getClientRects:()=>[]});
 const get=id=>{if(!nodes.has(id))nodes.set(id,make());return nodes.get(id);};
 const client={state:{connection:'PREPARING',role:'STANDALONE',ready:false},storedLensLink:()=>null,start(){change(this.state);},hints:async()=>{},relaySettings:async()=>{},dispose(){},pause(){},acknowledge:async()=>{}};
 const host={DisplayCore:{},DisplayConversate:{createClient(o){change=o.onChange;return client;}},DisplayVoice:{createCapture:()=>({state:{},isActive:()=>false,stop(){}})},location:{href:'https://example.test/assets/display/index.html',search:''},addEventListener(){},requestAnimationFrame:()=>1,cancelAnimationFrame(){},getComputedStyle:el=>({fontSize:el.style.fontSize||el.style['--hint-font']||'26px',lineHeight:'32.5px'})};
 doc.defaultView=host;
 host.NovaFocusControls={mount(){const flow=Flow.createFlow({host,element:get('nova-fold-answer')});return {update(s){flow.accept(s.focus);}};}};
 vm.runInNewContext(fs.readFileSync('main/resources/static/assets/display/app.js','utf8'),{window:host,document:doc,location:host.location,navigator:{},localStorage:{getItem:()=>null},URL,URLSearchParams,crypto:{randomUUID:()=> 'a'.repeat(32)},setTimeout:()=>1,clearTimeout(){},requestAnimationFrame:fn=>fn()});
 Object.assign(client.state,{ready:true,connection:'READY',assistId:'fixture',epoch:1,focus:{active:true,serverInstanceId:'boot',activationId:'a',turnId:'t',stateVersion:1,answerVersion:1,answerText:'합성 답변'},testStatus:{relay:{eventOwner:'THIS DEVICE',segmentSeconds:0},lensDisplay:{hintFontPx:36,transcriptFontPx:26,transcriptMaxLines:4,hintPageLines:11,hintTtlMs:20000,autoPageMs:5000,hintTargetChars:1000},lensSettingsScope:'a'.repeat(64)}});
 change(client.state);assert.equal(get('nova-fold-answer').style.fontSize,'36px');
});
