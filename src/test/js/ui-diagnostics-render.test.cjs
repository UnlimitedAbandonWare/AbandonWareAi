const {test}=require('node:test'),assert=require('node:assert/strict'),path=require('node:path');
const DIAG=require(path.join(__dirname,'..','..','..','main','resources','static','assets','display','diagnostics.js'));
// PASTE_DEVIN_JEV_OBSERVATION_SCORING_SUPPORT_20260930 WP1/WP2:
// 관리자 진단 응답의 Jev 관측 필드는 pipeline 안에 중첩되어 내려온다
// (DisplayConversateController: includeJevDiagnostics=true 경로만 허용).
// 프론트는 jev 라벨 행으로 렌더링하되 jevLatencyMs는 0 이상 정수만 노출하고,
// 공개 testStatus 형태(필드 부재)에서는 Jev 행을 만들지 않는다.
function fakeDom(){
 const els=new Map();
 const make=tag=>({tag,textContent:'',children:[],append(...kids){this.children.push(...kids);},replaceChildren(){this.children.splice(0);}});
 return{
  getElementById(id){if(!els.has(id))els.set(id,make('#'+id));return els.get(id);},
  createElement(tag){return make(tag);},
  el:id=>els.get(id)};
}
async function render(payload){
 const doc=fakeDom();const savedFetch=globalThis.fetch,savedListener=globalThis.addEventListener;
 globalThis.fetch=async()=>({ok:true,json:async()=>payload});
 if(typeof globalThis.addEventListener!=='function')globalThis.addEventListener=()=>{};
 try{
  DIAG.mount(doc);
  const button=doc.getElementById('observe'),output=doc.getElementById('diagnostic-data');
  button.onclick();
  for(let i=0;i<40&&output.children.length===0;i++)await new Promise(r=>setTimeout(r,5));
  const rows=[...output.children];
  button.onclick();
  const pairs=new Map();
  for(let i=0;i+1<rows.length;i+=2)pairs.set(rows[i].textContent,rows[i+1].textContent);
  return{pairs,text:JSON.stringify([...pairs.entries()])};
 }finally{globalThis.fetch=savedFetch;if(savedListener===undefined)delete globalThis.addEventListener;else globalThis.addEventListener=savedListener;}
}
test('admin diagnostics renders jev observation fields from pipeline',async()=>{
 const {pairs,text}=await render({enabled:true,observedAt:1,audioState:'listening',decision:'DISPLAY',reason:'ok',processingMs:12,searchResults:3,embeddingModel:'bge-m3',
  audio:{provider:'local'},
  pipeline:{selectedProvider:'BRAVE',totalLatencyMs:120,jevMode:'assist',jevDecision:'EVALUATE',jevReasonCode:'ok',jevApplied:true,jevLatencyMs:42}});
 assert.equal(pairs.get('Jev 지연시간 ms'),'42');
 assert.equal(pairs.get('Jev 판단'),'EVALUATE');
 assert.equal(pairs.get('Jev 모드'),'assist');
 assert.equal(pairs.get('Jev 결과 코드'),'ok');
 assert.equal(pairs.get('Jev 적용 여부'),'true');
 assert.match(pairs.get('RAG / LLM'),/"jevLatencyMs":42/);
});
test('unmeasured negative jevLatencyMs is never rendered',async()=>{
 const {pairs,text}=await render({enabled:true,observedAt:2,decision:'HOLD',reason:'shadow',
  pipeline:{selectedProvider:'BRAVE',jevMode:'shadow',jevLatencyMs:-1}});
 assert.equal(pairs.has('Jev 지연시간 ms'),false,'negative latency gets no label row');
 assert.equal(/jevLatencyMs/.test(text),false,'negative latency stays out of the pipeline blob');
 assert.equal(pairs.get('Jev 모드'),'shadow','non-latency jev fields still render');
});
test('public testStatus-shaped payload shows no jev rows and does not throw',async()=>{
 const {pairs,text}=await render({enabled:true,observedAt:3,decision:'HOLD',reason:'idle',processingMs:0,
  pipeline:{selectedProvider:'BRAVE',totalLatencyMs:88}});
 assert.equal([...pairs.keys()].some(k=>k.startsWith('Jev')),false);
 assert.doesNotMatch(text,/jev/i);
});
test('payload without pipeline renders without TypeError',async()=>{
 const {pairs}=await render({enabled:true,observedAt:4,audioState:'idle',decision:'HOLD'});
 assert.equal([...pairs.keys()].some(k=>k.startsWith('Jev')),false);
 assert.equal(pairs.get('수음 상태'),'idle');
});
