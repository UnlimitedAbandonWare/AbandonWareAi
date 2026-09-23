const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const {createReceiver,createClientId}=require('../../../main/resources/static/assets/display/meta/receiver.js');
const boot=require('../../../main/resources/static/assets/display/meta/boot.js');
test('subscriber labels survive missing crypto and randomUUID without changing authentication',()=>{
 for(const host of [{},{crypto:{getRandomValues(bytes){bytes.fill(7);return bytes;}}}]){assert.match(createClientId(host),/^[a-f0-9]{32}$/);assert.match(boot.createId(host),/^[a-f0-9]{32}$/);}
});
test('XHR fallback connects when AbortController and fetch are unavailable',async()=>{
 const sent=[];class Xhr{open(method,url){this.url=url;}setRequestHeader(){}send(body){sent.push({url:this.url,body:JSON.parse(body)});this.status=200;this.responseText=JSON.stringify({serverId:'s',eventId:1,generation:0,sentAt:100,enabled:true,caption:null,hint:null});this.onload();}}
 const r=createReceiver({host:{XMLHttpRequest:Xhr}});await r.poll();assert.equal(r.state.connection,'CONNECTED');assert.match(sent[0].body.clientId,/^[a-f0-9]{32}$/);assert.equal(sent[0].url,'/api/assist/display/relay/poll');r.dispose();
});
test('unsupported network keeps static shell and gives an explicit non-sensitive failure',async()=>{
 const events=[];const r=createReceiver({host:{},diagnostic:(name,data)=>events.push({name,...data})});await r.poll();assert.equal(r.state.errorCode,'unsupported');assert.equal(events[0].code,'unsupported');r.dispose();
});
test('HTTP denial is classified without treating ACK or receipt as hardware rendering',async()=>{
 const events=[];const r=createReceiver({fetchImpl:async()=>({ok:false,status:403}),diagnostic:(name,data)=>events.push({name,...data})});await r.poll();assert.equal(r.state.errorCode,'http_403');assert.equal(events[0].name,'transport_error');r.dispose();
});
test('early script failure is captured without exception text, transcript, cookies, or stack',()=>{
 const listeners={},bodies=[],status={textContent:''};class Xhr{open(){}setRequestHeader(){}send(body){bodies.push(JSON.parse(body));}}
 const host={XMLHttpRequest:Xhr,location:{search:''},document:{hidden:false,getElementById:()=>status},addEventListener:(name,fn)=>listeners[name]=fn};
 boot.start(host);listeners.error({target:host,message:'PRIVATE_TEXT',error:{stack:'SECRET'}});listeners.unhandledrejection({reason:'PRIVATE_TEXT'});
 assert.match(status.textContent,/고정 화면 유지/);assert.ok(bodies.some(x=>x.event==='script_error'));assert.equal(JSON.stringify(bodies).includes('PRIVATE_TEXT'),false);assert.equal(JSON.stringify(bodies).includes('SECRET'),false);
});
test('minimal caption shell loads only its receiver and has empty text slots',()=>{
 const html=fs.readFileSync('main/resources/static/assets/display/meta/index.html','utf8');
 assert.match(html,/<p id="transcript"><\/p>/);assert.match(html,/<p id="hint" hidden><\/p>/);
 assert.doesNotMatch(html,/HELLO|DISPLAY TEST|<button|<input|app\.js|display-voice|display-conversate|boot\.js|position:\s*fixed/);
 assert.equal((html.match(/<script(?:\s|>)/g)||[]).length,1);assert.match(html,/src="receiver\.js\?/);
});
