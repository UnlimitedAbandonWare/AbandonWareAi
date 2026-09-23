// Execute the actual homepage script against a small synthetic DOM/transport.
const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const vm=require('node:vm');const path=require('node:path');
const base=path.resolve(__dirname,'../main/resources/static/assets');
function harness(){
  class El{constructor(){this.listeners={};this.children=[];this.value='';this.textContent='';this.dataset={};this.style={};this.disabled=false;this.hidden=false;}addEventListener(n,f){this.listeners[n]=f;}focus(){}prepend(x){this.children.unshift(x);}append(x){this.children.push(x);}replaceChildren(){this.children=[];}get lastChild(){return{remove:()=>this.children.pop()};}}
  const elements=new Map();const $=id=>{if(!elements.has(id))elements.set(id,new El());return elements.get(id);};
  const radios=['question','hint','answer'].map(value=>Object.assign(new El(),{value,checked:value==='hint'}));
  const presets=['architecture','recovery','changes'].map(value=>Object.assign(new El(),{dataset:{preset:value}}));let submits=0,message='';
  const timers=[];const calls=[];let rejectCard=false,releaseCard;
  const snapshot={assistId:'12345678-1234-1234-1234-123456789012',epoch:1,version:1,state:'RUNNING',outputConnections:1,card:null,metrics:{}};
  const fetch=async(url,options)=>{calls.push({url,options});if(url.endsWith('/card')){if(JSON.parse(options.body).kind==='status')return{ok:true,status:200,json:async()=>snapshot};if(rejectCard)throw Error('PRIVATE_TRANSPORT');return new Promise(resolve=>{releaseCard=()=>resolve({ok:true,status:200,json:async()=>({...snapshot,version:2,card:{text:'fixture',expiresAt:Date.now()+60000}})});});}return{ok:true,status:200,json:async()=>snapshot};};
  const document={getElementById:$,createElement:()=>new El(),querySelectorAll:s=>s==='input[name="kind"]'?radios:s==='[data-preset]'?presets:[],querySelector:s=>s==='input[name="kind"]:checked'?radios.find(r=>r.checked):s.startsWith('input[name="kind"][value=')?radios.find(r=>s.includes('"'+r.value+'"')):new El()};
  let render;const logs=[];const window={InterviewCore:require(path.join(base,'interview/interview-core.js')),DisplayCore:{createClient:({onChange})=>{render=onChange;return{canSubmit:()=>false,cancel(){},setMessage(value){message=value;},submit(){submits++;onChange({phase:'LOADING',message});},newConversation(){}};}},addEventListener(){}};
  const context={window,document,console:{debug:(_tag,row)=>logs.push(row)},location:{origin:'http://192.168.1.4:18080'},performance,AbortController,Date,JSON,URL,Number,Math,setTimeout:f=>{timers.push(f);return timers.length;},clearTimeout(){},fetch,EventSource:class{addEventListener(){}close(){}}};
  vm.runInNewContext(fs.readFileSync(path.join(base,'interview/app.js'),'utf8'),context);
  return{$,calls,logs,presets,render,submits:()=>submits,connect:()=>$('connect-display').listeners.click(),send:()=>$('display-form').listeners.submit({preventDefault(){}}),edit:text=>{$('display-text').value=text;$('display-text').listeners.input();},reject:()=>{rejectCard=true;},release:()=>releaseCard()};
}

test('HTTP 200 fallback does not mark model generation complete',()=>{const h=harness();h.render({phase:'RESULT',message:'safe question',result:{answer:'safe fallback',sources:[],fallback:true},metrics:{httpStatus:200,roundTripMs:1}});assert.equal(h.$('answer').textContent,'safe fallback');assert.notEqual(h.$('step-answer').dataset.state,'done');assert.match(h.$('answer-state').textContent,/대체 응답/);assert.match(h.$('rag-notice').textContent,/모델 생성/);});
test('one preset click submits immediately and cannot start a second in-flight query',()=>{const h=harness();h.presets[0].listeners.click();h.presets[0].listeners.click();h.presets[1].listeners.click();assert.equal(h.submits(),1);assert.equal(h.$('question').value,'architecture');});
test('homepage prevents double submit during flight and after unchanged successful card',async()=>{
  const h=harness();await h.connect();h.edit('synthetic hint');const pending=h.send();await h.send();
  assert.equal(h.calls.filter(x=>x.url.endsWith('/card')).length,1);h.release();await pending;await h.send();
  assert.equal(h.calls.filter(x=>x.url.endsWith('/card')).length,1);assert.equal(h.$('send-display').disabled,true);
  assert.equal(h.$('check-receiver').dataset.done,'false');assert.match(h.$('delivery-status').textContent,/수신 확인 대기/);
});
test('unknown outcome keeps the same card fenced and never logs raw content',async()=>{
  const h=harness();await h.connect();h.edit('PRIVATE_CARD_TEXT');h.reject();await h.send();await h.send();
  assert.equal(h.calls.filter(x=>x.url.endsWith('/card')).length,1);assert.equal(h.$('send-display').disabled,true);
  assert.match(h.$('delivery-status').textContent,/결과 확인 필요/);assert.ok(!JSON.stringify(h.logs).includes('PRIVATE'));
  h.edit('different deliberate card');assert.equal(h.$('send-display').disabled,false);
});
test('oversized unicode card sends no transport request',async()=>{
  const h=harness();await h.connect();h.edit('가'.repeat(121));await h.send();
  assert.equal(h.calls.filter(x=>x.url.endsWith('/card')).length,0);assert.equal(h.$('send-display').disabled,true);
});
test('completed RAG answer carries only its request and source titles through the existing card route',async()=>{
  const h=harness();await h.connect();
  h.render({phase:'RESULT',message:'synthetic',result:{answer:'A complete synthetic answer.',sources:[{marker:'[1]',title:'Policy',url:'https://example.org',filePath:'PRIVATE_PATH'}]},metrics:{requestId:'synthetic-rag-1',roundTripMs:2,sourceCount:1}});
  h.$('use-summary').listeners.click();
  const pending=h.send();const body=JSON.parse(h.calls.find(x=>x.url.endsWith('/card')).options.body);
  assert.equal(body.requestId,'synthetic-rag-1');assert.deepEqual(body.sourceTitles,['[1] Policy']);
  assert.ok(!JSON.stringify(body).includes('PRIVATE_PATH'));assert.equal(h.submits(),0);
  h.release();await pending;
});
test('manual edits and a new question cannot reuse the prior answer provenance',async()=>{
  for(const change of ['edit','new-question']){
    const h=harness();await h.connect();h.render({phase:'RESULT',message:'synthetic',result:{answer:'A complete synthetic answer.',sources:[{title:'Old source'}]},metrics:{requestId:'old-request',roundTripMs:2}});
    h.$('use-summary').listeners.click();
    if(change==='edit')h.edit('Operator replacement');else h.render({phase:'LOADING',message:'new synthetic'});
    const pending=h.send();const request=h.calls.find(x=>x.url.endsWith('/card'));
    if(request){const body=JSON.parse(request.options.body);assert.ok(!body.requestId);assert.equal((body.sourceTitles||[]).length,0);h.release();}
    await pending;
  }
});

test('processing and terminal status use the same request once without publishing private text',async()=>{
  for(const terminal of ['rate-limited','admission-unavailable','outcome-unknown','RESULT','FALLBACK']){
    const h=harness();await h.connect();
    h.render({phase:'LOADING',message:'PRIVATE_QUESTION',metrics:{requestId:'request-status-1'}});
    const state=['RESULT','FALLBACK'].includes(terminal)?{phase:'RESULT',message:'PRIVATE_QUESTION',result:{answer:'PRIVATE_ANSWER',sources:[],fallback:terminal==='FALLBACK'},metrics:{requestId:'request-status-1',roundTripMs:1}}:{phase:'ERROR',message:'PRIVATE_QUESTION',error:{code:terminal,message:'Safe state'},metrics:{requestId:'request-status-1'}};
    h.render(state);h.render(state);await new Promise(setImmediate);
    const bodies=h.calls.filter(x=>x.url.endsWith('/card')).map(x=>JSON.parse(x.options.body));
    assert.equal(bodies.length,2);assert.deepEqual(bodies.map(b=>b.requestState),['LOADING',terminal]);
    assert.ok(bodies.every(b=>b.kind==='status'&&b.requestId==='request-status-1'&&b.requestSequence===1));
    assert.ok(!JSON.stringify(bodies).includes('PRIVATE'));assert.equal(h.submits(),0);
    assert.equal(h.$('check-server').dataset.done,'false');
  }
});
