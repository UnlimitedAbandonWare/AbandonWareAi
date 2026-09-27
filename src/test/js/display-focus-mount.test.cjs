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
 host.window=host;host.globalThis=host;
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
