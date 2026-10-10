const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs');
const {createProjection}=require('../../../main/resources/static/assets/display/display-focus.js');
const {createFlow}=require('../../../main/resources/static/assets/display/display-focus-flow.js');
const {normDisplay,hintLineBudget}=require('../../../main/resources/static/assets/display/meta/receiver.js');
const {createCapture}=require('../../../main/resources/static/assets/display/display-voice.js');
const {mount}=require('../../../main/resources/static/assets/display/display-focus-controls.js');
function fixture(options={}){
  let now=0,id=0;const frames=new Map();
  const doc={hidden:false,createElement(){return {style:{},textContent:'',remove(){}};}};
  const answer={style:{},children:[],ownerDocument:doc,clientHeight:288,appendChild(row){this.children.push(row);},get lastElementChild(){return this.children.at(-1);}};
  const question={textContent:'',dataset:{}},draft={textContent:'',scrollHeight:160,scrollTop:0},panel={hidden:true},status={textContent:''};
  const host={requestAnimationFrame(fn){frames.set(++id,fn);return id;},cancelAnimationFrame(n){frames.delete(n);},getComputedStyle(){return {lineHeight:'40',fontSize:'26'};}};
  const projection=createProjection({host,document:doc,target:'fold',panel,status,question,draft,answer,fitsLine:()=>true,...options});
  return {projection,question,draft,panel,answer,run(ms){for(let t=0;t<ms;t+=20){now+=20;const calls=[...frames.values()];frames.clear();calls.forEach(fn=>fn(now));}},text(){return answer.children.map(x=>x.textContent).join('');}};
}
const base={serverInstanceId:'boot',activationId:'active',turnId:'turn-1',answerVersion:1,stateVersion:1,active:true,phase:'ANSWER_READY',draftText:'Q2 being dictated',questionText:'Q1',answerText:'A1',renderTarget:'fold',idleRemainingMs:0};
test('Fold retains answer across 30s delayed frames and pairs Q1 separately from Q2 draft',()=>{
  const f=fixture();f.projection.update(base);f.run(8000);
  assert.equal(f.text(),'A1');assert.equal(f.answer.style.opacity,'1');
  assert.equal(f.question.textContent,'Q1');assert.equal(f.draft.textContent,'Q2 being dictated');
  assert.equal(f.draft.scrollTop,f.draft.scrollHeight);
  f.projection.update({...base,stateVersion:2,phase:'THINKING',answerText:''});f.run(30000);
  assert.equal(f.text(),'A1');
  f.projection.update({...base,stateVersion:3,phase:'WAITING',answerText:'A1'});f.run(200);
  assert.equal(f.text(),'A1');
  f.projection.update({...base,stateVersion:4,active:false,reason:'voice_exit'});assert.equal(f.text(),'');
  assert.equal(f.question.textContent,'');assert.equal(f.question.dataset.turnId,undefined);
});
test('maximum-font long hints reserve three measured caption lines without changing saved preference',()=>{
  const display=normDisplay({transcriptFontPx:36,hintFontPx:36,hintPageLines:13,transcriptMaxLines:3});
  const lines=hintLineBudget(display,582,21,28,10);
  assert.equal(lines,8);assert.equal(display.hintPageLines,13);
  assert.ok(lines*45+3*45+2+21+28+20<=582);
  const short=hintLineBudget(display,500,42,28,10);assert.ok(short*45+3*45+2+42+28+20<=500);
});
test('legacy 1 and 2 line transcript values are promoted to three',()=>{
  for(const lines of [1,2])assert.equal(normDisplay({transcriptMaxLines:lines}).transcriptMaxLines,3);
});
test('exit control is populated, saves custom word and rereads it after epoch reconnect',async()=>{
  const elements=new Map(),element=id=>{if(!elements.has(id))elements.set(id,{value:'',type:'number',checked:false,disabled:false,hidden:true,textContent:'',append(){},replaceChildren(){},removeAttribute(){}});return elements.get(id);};
  let settings={enabled:true,wakeWord:'노바',cameraWakeWord:'데빈',utteranceQuietMs:1200,followupIdleMs:20000,wakeListenTimeoutMs:12000,presentation:{},snapshot:{enabled:false,source:'FOLD_REAR',cameraAllowed:true}};
  const host={NovaFocus:{createProjection:()=>({update(){},visibility(){},isActive:()=>false,reset(){},dispose(){}}),receiptSender:()=>()=>{}}};
  const controls=mount({host,document:{getElementById:element,createElement:()=>({append(){}}),addEventListener(){},removeEventListener(){}},client:{async focusRequest(route,body){if(route==='settings'&&body?.settings)settings={...settings,...body.settings};return {settingsVersion:1,settings};}}});
  const flush=()=>new Promise(setImmediate),state={assistId:'test',epoch:1,ready:true,connection:'READY',focusProducer:true};
  try{
    controls.update(state);await flush();assert.equal(element('nf-exit').value,'클린');
    element('nf-exit').value='finish';await element('nova-settings-form').onsubmit({preventDefault(){}});
    assert.equal(settings.exitWord,'finish');assert.equal(settings.utteranceQuietMs,1200);
    controls.update({...state,epoch:2});await flush();assert.equal(element('nf-exit').value,'finish');
    const html=fs.readFileSync('main/resources/static/assets/display/index.html','utf8');assert.ok(/id="nf-exit"[^>]*maxlength="16"/.test(html));
  }finally{controls.dispose();}
});
test('rotated reconnect receipt acknowledges retained answer once without replay',async()=>{
  const old='a'.repeat(64),ticket='b'.repeat(64),events=[],f=fixture({receipt:async(name,detail)=>events.push([name,detail.renderReceiptTicket])});
  f.projection.update({...base,renderReceiptTicket:old});f.run(8000);await new Promise(setImmediate);
  const before=f.text();assert.equal(before,'A1');
  const reconnect={...base,stateVersion:2,renderReceiptTicket:ticket};
  f.projection.update(reconnect);f.projection.update(reconnect);f.run(100);await new Promise(setImmediate);
  assert.equal(f.text(),before);
  assert.deepEqual(events.filter(e=>e[1]===ticket),[['first_visible',ticket],['presentation_done',ticket]]);
  f.projection.dispose();
});
test('Fold and lens reserve real three-line transcript space and lens four-line answer space',()=>{
  const fold=fs.readFileSync('main/resources/static/assets/display/index.html','utf8');
  const lens=fs.readFileSync('main/resources/static/assets/display/meta/index.html','utf8');
  assert.ok(/#nova-fold-draft\s*\{[^}]*min-height:87px/.test(fold));
  assert.ok(/#nova-draft\s*\{[^}]*min-height:3\.75em/.test(lens));
  assert.ok(/#nova-answer\s*\{[^}]*min-height:5em/.test(lens));
});
test('focus screen wake lock shares ownership and reacquires only when visible',async()=>{
  let requests=0,releases=0,onRelease;
  const document={hidden:false};
  const env={document,navigator:{wakeLock:{request:async()=>{requests++;return {released:false,release:async()=>{releases++;},addEventListener(_name,fn){onRelease=fn;}};}}}};
  const capture=createCapture({client:{state:{}},env}),flush=()=>new Promise(setImmediate);
  capture.setFocusActive(true);capture.setFocusActive(true);await flush();assert.equal(requests,1);
  document.hidden=true;onRelease();capture.setFocusActive(true);await flush();assert.equal(requests,1);
  document.hidden=false;capture.setFocusActive(true);await flush();assert.equal(requests,2);
  capture.setFocusActive(false);await flush();assert.equal(releases,1);
  env.navigator.wakeLock.request=async()=>{throw new Error('permission_denied');};
  capture.setFocusActive(true);await flush();assert.equal(capture.isActive(),false);
  capture.setFocusActive(false);
});

test('failed unfinished answer clears while a transient thinking snapshot preserves the prefix',()=>{
  const f=fixture();
  const partial={...base,answerText:'unfinished ',answerComplete:false,answerPrefixStable:true};
  f.projection.update(partial);f.run(2000);assert.equal(f.text(),'unfinished ');
  f.projection.update({...partial,stateVersion:2,phase:'THINKING',answerText:''});
  assert.equal(f.text(),'unfinished ');
  f.projection.update({...partial,stateVersion:3,phase:'WAITING',answerText:'',reason:'focus_stream_final_mismatch'});f.run(30000);
  assert.equal(f.text(),'');assert.equal(f.panel.hidden,false);
});

test('repeated throttled frames advance one glyph at a time without repaying a suspended backlog',()=>{
  let now=0,id=0;const frames=new Map(),events=[];
  const flow=createFlow({retainAfterPresentation:true,fitsLine:()=>true,requestFrame(fn){frames.set(++id,fn);return id;},cancelFrame(n){frames.delete(n);},onEvent(name){events.push(name);}});
  const step=ms=>{now+=ms;const calls=[...frames.values()];frames.clear();calls.forEach(fn=>fn(now));};
  flow.accept(base);step(20);step(80);const before=flow.state().index;
  step(30000);assert.equal(flow.state().index,before);
  for(let n=0;n<5;n++){const index=flow.state().index;step(1200);assert.ok(flow.state().index-index<=1);}
  assert.equal(flow.state().lines.join(''),'A1');assert.equal(flow.state().done,true);
  assert.equal(events.filter(name=>name==='presentation_done').length,1);
});
