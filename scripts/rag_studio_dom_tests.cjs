// Synthetic DOM and HTTP response, executing the real Studio and shared client.
const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const base=path.resolve(__dirname,'../main/resources/static/assets/interview');
function harness(){
  class El{
    constructor(tag='div'){this.tagName=tag.toUpperCase();this.children=[];this.listeners={};this.dataset={};this.style={};this.attributes={};this.hidden=false;this.disabled=false;this.value='';this._text='';}
    set textContent(value){this._text=String(value);this.children=[];}get textContent(){return this._text+this.children.map(x=>x.textContent).join('');}
    append(...children){for(const c of children){c.parent=this;this.children.push(c);}}replaceChildren(...children){this._text='';this.children=[];this.append(...children);}get firstChild(){return this.children[0];}
    remove(){this.parent.children=this.parent.children.filter(c=>c!==this);}addEventListener(name,fn){this.listeners[name]=fn;}setAttribute(name,value){this.attributes[name]=value;}focus(){}scrollIntoView(){}
  }
  const html=fs.readFileSync(path.join(base,'studio.html'),'utf8'),nodes=new Map([...html.matchAll(/id="([^"]+)"/g)].map(m=>[m[1],new El()]));
  const tabs=['evidence','routing','metrics'].map(name=>{const el=nodes.get('tab-'+name);el.dataset.tab=name;return el;});
  const presets=[new El()];presets[0].dataset.preset='Synthetic preset';const calls=[],logs=[];let finish,client;
  const document={getElementById:id=>{assert.ok(nodes.has(id),'missing HTML id '+id);return nodes.get(id);},createElement:tag=>new El(tag),querySelectorAll:selector=>selector==='[data-tab]'?tabs:selector==='[data-preset]'?presets:[]};
  const fetchImpl=(url,options)=>{calls.push({url,options});return new Promise(resolve=>{finish=(dto,status=200)=>resolve({ok:status===200,status,headers:{get:()=>null},json:async()=>dto});});};
  const window={RagInspector:require(path.join(base,'rag-inspector.js')),StudioVoice:require(path.join(base,'studio-voice.js')),DisplayCore:{createClient:options=>{client=require('../main/resources/static/assets/display/display-core.js').createClient({...options,fetchImpl});return client;}},addEventListener(){}};
  vm.runInNewContext(fs.readFileSync(path.join(base,'studio.js'),'utf8'),{window,document,console:{debug:(tag,row)=>logs.push({tag,row})},URL,navigator:{clipboard:{writeText:async()=>{}}}});
  const emit=(id,event,extra={})=>nodes.get(id).listeners[event]({target:nodes.get(id),preventDefault(){},...extra});
  return{nodes,calls,logs,client,presets,tabs,emit,edit:text=>{nodes.get('question').value=text;emit('question','input');},send:()=>emit('question-form','submit'),finish:async(dto,status)=>{finish(dto,status);await new Promise(setImmediate);},text:id=>nodes.get(id).textContent};
}
test('actual Studio request shows only observed stages, literal source titles and bounded telemetry',async()=>{
  const h=harness();h.edit('PRIVATE_QUESTION');h.send();h.send();assert.equal(h.calls.length,1);
  assert.equal(JSON.parse(h.calls[0].options.body).useWebSearch,true);assert.equal(JSON.parse(h.calls[0].options.body).useRag,false);
  assert.equal(h.nodes.get('stage-retrieval').dataset.state,'pending');assert.notEqual(h.nodes.get('stage-answer').dataset.state,'done');
  await h.finish({content:'PRIVATE_ANSWER',modelUsed:'example-model',ragUsed:true,evidence:[{title:'<img onerror=alert(1)>',source:'javascript:alert(1)',rank:1,confidence:.5,confidenceSource:'reranker'}],pipelineSnapshot:{webCount:1,vectorCount:0,finalContextCount:1,route:'hybrid'}});
  assert.match(h.text('messages'),/PRIVATE_ANSWER/);assert.match(h.text('evidence-list'),/<img onerror/);assert.equal(h.nodes.get('evidence-list').children[0].children.filter(x=>x.tagName==='A').length,0);
  assert.equal(h.nodes.get('stage-context').dataset.state,'done');assert.match(h.text('routing-values'),/미관측/);assert.ok(!JSON.stringify(h.logs).includes('PRIVATE'));assert.ok(!JSON.stringify(h.logs).includes('img'));
});
test('empty metadata and HTTP200 fallback remain visibly incomplete, with one fallback response',async()=>{
  const h=harness();h.edit('synthetic');h.send();await h.finish({content:'Safe substitute',modelUsed:'fallback:evidence',ragUsed:true,evidence:[]});
  assert.equal(h.nodes.get('stage-answer').dataset.state,'warning');assert.notEqual(h.nodes.get('stage-retrieval').dataset.state,'done');assert.notEqual(h.nodes.get('stage-context').dataset.state,'done');assert.match(h.text('session-summary'),/fallback 1/);assert.match(h.text('evidence-list'),/근거가 없습니다/);
});
test('cancel prevents late completion, tabs support keyboard navigation and new conversation clears the old view',async()=>{
  const h=harness();h.edit('old');h.send();h.emit('cancel','click');assert.match(h.text('run-status'),/결과 미확인/);h.emit('new-conversation','click');
  await h.finish({content:'STALE',evidence:[]});assert.ok(!h.text('messages').includes('STALE'));assert.equal(h.nodes.get('welcome').hidden,false);assert.equal(h.client.state.sessionId,null);
  h.tabs[0].listeners.keydown({key:'ArrowRight',preventDefault(){}});assert.equal(h.nodes.get('panel-routing').hidden,false);assert.equal(h.tabs[1].attributes['aria-selected'],'true');
});
test('503 produces a clear error without retry or a false retrieval completion',async()=>{
  const h=harness();h.edit('synthetic');h.send();await h.finish({},503);assert.equal(h.calls.length,1);assert.match(h.text('request-notice'),/답변 서버/);assert.notEqual(h.nodes.get('stage-retrieval').dataset.state,'done');assert.match(h.text('session-summary'),/오류 1/);
});
test('choosing an earlier answer restores its pipeline and metrics as one coherent snapshot',async()=>{
  const h=harness();h.edit('first');h.send();await h.finish({content:'First',evidence:[],pipelineSnapshot:{webCount:2,finalContextCount:2}});
  const first=h.nodes.get('messages').children[1];const action=first.children.find(c=>c.className==='message-actions').children[0];
  h.edit('second');h.send();await h.finish({content:'Second',modelUsed:'fallback:evidence',evidence:[]});
  action.listeners.click();assert.match(h.text('detail-context'),/2개/);assert.equal(h.nodes.get('stage-answer').dataset.state,'done');assert.match(h.text('run-status'),/응답 수신/);
});

test('server held projection warns and counts a withheld answer without claiming final generation',async()=>{
  const h=harness();h.edit('synthetic');h.send();await h.finish({content:'검증된 근거가 추가로 필요해 응답 본문을 보류했습니다.\n\n<!-- rag-control-projection:v1 -->\nSafety disclosure.',evidence:[]});
  assert.match(h.text('run-status'),/답변 보류/);assert.equal(h.nodes.get('stage-answer').dataset.state,'warning');assert.match(h.text('session-summary'),/보류 1/);assert.match(h.text('announcer'),/보류/);
});
