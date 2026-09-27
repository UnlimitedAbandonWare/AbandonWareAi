const {test}=require('node:test'),assert=require('node:assert/strict');
const {mount}=require('../../../main/resources/static/assets/display/display-focus-controls.js');
const flush=()=>new Promise(setImmediate);
function fixture(){
 const elements=new Map(),calls=[];let count=0;
 const element=id=>{if(!elements.has(id))elements.set(id,{value:'',checked:false,textContent:'',disabled:false,children:[],removeAttribute(){},replaceChildren(){this.children=[];},append(...x){this.children.push(...x);}});return elements.get(id);};
 const settings={recallEnabled:false,rememberFactsEnabled:true,presentation:{}};
 const host={crypto:{randomUUID:()=> '00000000-0000-0000-0000-'+String(++count).padStart(12,'0')},NovaFocus:{receiptSender:()=>()=>{},createProjection:()=>({update(){},visibility(){},dispose(){},isActive:()=>false})}};
 const client={async focusRequest(route,body){calls.push({route,body});if(route==='settings/read')return {settingsVersion:2,cacheScope:'a'.repeat(64),settings};if(route==='memory/read')return [];return {};}};
 const document={getElementById:element,removeEventListener(){},createElement:()=>({textContent:'',append(){}}),addEventListener(){},createElement:()=>({textContent:'',append(){}})};
 const controls=mount({host,document,client});const state={assistId:'s',epoch:1,focusProducer:true,ready:true,connection:'READY'};
 controls.update(state);return {controls,element,calls,client,state};
}
test('saving requires separate consent and never sends the whole conversation',async()=>{
 const f=fixture();await flush();f.element('nf-memory-text').value='선택한 합성 사실';f.element('nf-memory-entities').value='3090, 전원';
 await f.element('nf-memory-form').onsubmit({preventDefault(){}});
 assert.equal(f.calls.some(x=>x.route==='memory/save'),false);
 f.element('nf-memory-confirm').checked=true;
 await f.element('nf-memory-form').onsubmit({preventDefault(){}});
 const request=f.calls.find(x=>x.route==='memory/save').body;
 assert.equal(request.edit.text,'선택한 합성 사실');assert.equal(request.edit.consentRevision,2);
 assert.deepEqual(request.edit.entities,['3090','전원']);assert.equal(request.edit.confirmed,true);
 assert.equal('owner' in request,false);assert.equal('question' in request,false);
 assert.equal(f.element('nf-memory-text').value,'');f.controls.dispose();
});
test('server close clears an unacknowledged raw draft without resending',async()=>{
 const f=fixture();await flush();f.controls.update({...f.state,focus:{active:true}});
 f.element('nova-question').value='폐기할 합성 질문';
 f.controls.update({...f.state,focus:{active:false}});
 assert.equal(f.element('nova-question').value,'');assert.equal(f.calls.some(x=>x.route==='input'),false);f.controls.dispose();
});
test('unknown save retries reuse source identity and owner switch clears the draft',async()=>{
 const f=fixture();await flush();const base=f.client.focusRequest;
 f.client.focusRequest=async(route,body)=>{if(route==='memory/save'){f.calls.push({route,body});throw Error('timeout');}return base(route,body);};
 f.element('nf-memory-text').value='합성 사실';f.element('nf-memory-confirm').checked=true;
 await f.element('nf-memory-form').onsubmit({preventDefault(){}});
 await f.element('nf-memory-form').onsubmit({preventDefault(){}});
 const saves=f.calls.filter(x=>x.route==='memory/save');assert.equal(saves.length,2);assert.equal(saves[0].body.edit.sourceId,saves[1].body.edit.sourceId);
 f.controls.update({...f.state,assistId:'other'});assert.equal(f.element('nf-memory-text').value,'');assert.equal(f.element('nf-memory-confirm').checked,false);f.controls.dispose();
});
