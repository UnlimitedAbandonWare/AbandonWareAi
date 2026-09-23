const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');const path=require('node:path');
function harness(deferFrames=false){
  const elements=new Map(),events={},calls=[],requests=[],frames=[];let receive,key;
  const document={visibilityState:'visible',activeElement:null,getElementById(id){if(!elements.has(id))elements.set(id,{textContent:'',hidden:false,addEventListener(n,f){events[id+':'+n]=f;},focus(){document.activeElement=this;}});return elements.get(id);},addEventListener(n,f){if(n==='keydown')key=f;}};
  const context={document,window:{addEventListener(){}},location:{protocol:'http:',hash:'#session=12345678-1234-1234-1234-123456789012&epoch=1'},URLSearchParams,Date,Number,JSON,Math,setTimeout(){return 1;},clearTimeout(){},requestAnimationFrame:f=>deferFrames?frames.push(f):f(),fetch:async(url,options)=>{calls.push(url);requests.push(JSON.parse(options.body));return{ok:true};},EventSource:class{addEventListener(n,f){if(n==='assist')receive=f;}close(){}}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../main/resources/static/assets/display/receiver.js'),'utf8'),context);
  return{$:id=>document.getElementById(id),calls,requests,frames,document,frame:()=>frames.shift()?.(),send:s=>receive({data:JSON.stringify(s)}),key:k=>key({key:k,preventDefault(){}})};
}
const snapshot=()=>({assistId:'12345678-1234-1234-1234-123456789012',epoch:1,state:'RUNNING',version:2,card:{kind:'ANSWER',text:'Complete synthetic answer.',requestId:'synthetic-rag-1',sourceTitles:['[1] Warranty policy','[2] Return policy'],expiresAt:Date.now()+60000}});

test('malformed version or card cannot render, acknowledge, or poison a later valid snapshot',()=>{
  const s=snapshot();
  const invalid=[...[undefined,null,'2',-1,0,1.5,Number.MAX_SAFE_INTEGER+1].map(version=>({...s,version})),
    ...[undefined,null,{},42].map(text=>({...s,card:{...s.card,text}})),
    ...[undefined,null,'2099999999999',Number.MAX_SAFE_INTEGER+1].map(expiresAt=>({...s,card:{...s.card,expiresAt}}))];
  for(const value of invalid){
    const h=harness();h.send(value);
    assert.equal(h.calls.length,0,'malformed snapshot must not ACK');
    assert.equal(h.$('receiver-text').textContent,'');
    h.send(s);assert.equal(h.$('receiver-text').textContent,s.card.text);
    assert.equal(h.calls.length,1,'valid recovery must still ACK');
  }
});

test('empty snapshot clears a prior card without acknowledging it',()=>{
  const h=harness(),s=snapshot();h.send(s);const count=h.calls.length;
  h.send({...s,version:3,card:null});
  assert.equal(h.$('receiver-text').textContent,'');assert.equal(h.calls.length,count);
});
test('interim and final captions render without a hint card and malformed metadata cannot poison recovery',()=>{
  const h=harness(),s=snapshot(),caption={utteranceId:'dg-0',revision:1,isFinal:false,text:'Synthetic interim',words:[],confidence:null,expiresAt:Date.now()+20000};
  h.send({...s,card:null,caption});
  assert.equal(h.$('receiver-caption').textContent,'Synthetic interim');
  h.send({...s,version:3,card:null,caption:{...caption,revision:2,isFinal:true,text:'Synthetic final'}});
  assert.equal(h.$('receiver-caption').textContent,'Synthetic final');
  h.send({...s,version:99,card:null,caption:{...caption,revision:'invalid'}});
  h.send({...s,version:4,card:null,caption:{...caption,utteranceId:'dg-1',text:'New utterance'}});
  assert.equal(h.$('receiver-caption').textContent,'New utterance');
  h.send({...s,version:5,card:null,caption:null});
  assert.equal(h.$('receiver-caption').textContent,'');
});
test('receiver pages same-request sources with D-pad and never requests another answer',()=>{
  const h=harness(),s=snapshot();h.send(s);assert.equal(h.$('receiver-text').textContent,s.card.text);assert.match(h.$('receiver-request').textContent,/synthetic-rag-1/);
  h.key('ArrowRight');assert.equal(h.$('receiver-text').textContent,'[1] Warranty policy');
  h.key('Enter');assert.equal(h.$('receiver-text').textContent,'[2] Return policy');
  h.key('ArrowLeft');assert.equal(h.$('receiver-text').textContent,'[1] Warranty policy');
  assert.ok(h.calls.every(url=>url.endsWith('/ack')));
});
test('late snapshots and expired cards cannot replace or retain current provenance',()=>{
  const h=harness(),s=snapshot();h.send(s);h.send({...s,version:1,card:{...s.card,text:'stale'}});assert.equal(h.$('receiver-text').textContent,s.card.text);
  h.send({...s,version:3,card:{...s.card,expiresAt:1}});assert.equal(h.$('receiver-text').textContent,'');assert.equal(h.$('receiver-request').textContent,'');
});

test('request status remains a single page and never masquerades as an answer or source',()=>{
  const h=harness(),s=snapshot();
  for(const [i,decision] of ['LOADING','rate-limited','outcome-unknown','FALLBACK','RESULT'].entries()){
    h.send({...s,version:2+i,card:{...s.card,kind:'STATUS',decision,text:'Fixed state '+decision,sourceTitles:[]}});
    assert.equal(h.$('receiver-kind').textContent,'요청 상태');assert.equal(h.$('page-count').textContent,'1 / 1');
    h.key('ArrowRight');assert.equal(h.$('receiver-text').textContent,'Fixed state '+decision);
    assert.match(h.$('receiver-request').textContent,/synthetic-rag-1/);
  }
  assert.ok(h.calls.every(url=>url.endsWith('/ack')));
});

test('caption ACK waits for two frames, is phase specific, and suppresses hidden or superseded frames',()=>{
  const s=snapshot(),caption={utteranceId:'dg-0',revision:1,isFinal:false,text:'Synthetic interim',words:[],expiresAt:Date.now()+20000};
  const h=harness(true);h.send({...s,card:null,caption});h.send({...s,card:null,caption});
  assert.equal(h.frames.length,1);assert.equal(h.calls.length,0);h.frame();assert.equal(h.calls.length,0);h.frame();
  assert.deepEqual(h.requests,[{epoch:1,version:2,phase:'caption_rendered'}]);
  const hidden=harness(true);hidden.send({...s,card:null,caption});hidden.document.visibilityState='hidden';hidden.frame();hidden.frame();assert.equal(hidden.calls.length,0);
  const replaced=harness(true);replaced.send({...s,card:null,caption});replaced.send({...s,version:3,card:null,caption:null});replaced.frame();replaced.frame();assert.equal(replaced.calls.length,0);
});
test('hint and caption are acknowledged separately and suggestions stay on one page',()=>{
  const h=harness(true),s=snapshot(),caption={utteranceId:'dg-0',revision:2,isFinal:true,text:'Synthetic final',words:[],expiresAt:Date.now()+20000};
  h.send({...s,caption,card:{...s.card,kind:'SUGGESTION',sourceTitles:[]}});
  while(h.frames.length)h.frame();
  assert.deepEqual(h.requests.map(r=>r.phase).sort(),['caption_rendered','rendered']);
  assert.equal(h.$('receiver-kind').textContent,'대화 힌트');assert.equal(h.$('page-count').textContent,'1 / 1');
});
