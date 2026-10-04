(function(root,factory){const core=factory();if(typeof module==='object'&&module.exports)module.exports=core;else{root.AwxSettingsRouting=core;if(root.document)root.addEventListener('DOMContentLoaded',()=>core.mount(root.document,root.fetch.bind(root)));}})(typeof window==='object'?window:globalThis,function(){
'use strict';
const ROLES=Object.freeze(['MAIN_DEFAULT','MAIN_FAST','MAIN_HIGH','SELFASK_BQ','SELFASK_ER','SELFASK_RC','PROMPT_POSE_DRAFT']);
const LABELS=['주 답변 · 기본','주 답변 · 빠른 처리','주 답변 · 높은 품질','Self-Ask · 기초 질문','Self-Ask · 개체 관계','Self-Ask · 반례','프롬프트 초안'];
const id=v=>typeof v==='string'&&/^llmrouter\.[A-Za-z0-9_.:-]{1,240}$/.test(v);
function fields(o,allowed){if(!o||typeof o!=='object'||Array.isArray(o)||Object.keys(o).some(k=>!allowed.includes(k)))throw Error('invalid_profile');}
function profile(input){
 fields(input,['schemaVersion','revision','enabled','bindings','additionalPaidAllowed','additionalCostCapUsd']);
 if(input.schemaVersion!==1||typeof input.enabled!=='boolean'||input.additionalPaidAllowed!==false||input.additionalCostCapUsd!==0)throw Error('invalid_profile');
 fields(input.bindings,ROLES);const bindings={};
 for(const [role,b]of Object.entries(input.bindings)){
  fields(b,['role','selection','target','orderedFallbacks','maxExtraFallbackCalls','auxPaidAllowed','auxCostCapUsdPerRun']);
  const paid=b.auxPaidAllowed??false,cap=b.auxCostCapUsdPerRun??0;
  if(typeof paid!=='boolean'||typeof cap!=='number'||!Number.isFinite(cap)||cap<0||cap>0.05||paid!==(cap>0)||role.startsWith('MAIN_')&&paid)throw Error('invalid_binding');
  if(b.role!==role||b.selection!=='registered-route'||!id(b.target)||!Array.isArray(b.orderedFallbacks)||b.orderedFallbacks.length>3||b.orderedFallbacks.some(v=>!id(v)||v===b.target)||new Set(b.orderedFallbacks).size!==b.orderedFallbacks.length||!Number.isInteger(b.maxExtraFallbackCalls)||b.maxExtraFallbackCalls<0||b.maxExtraFallbackCalls>b.orderedFallbacks.length)throw Error('invalid_binding');
  bindings[role]={role,selection:b.selection,target:b.target,orderedFallbacks:[...b.orderedFallbacks],maxExtraFallbackCalls:b.maxExtraFallbackCalls};
  if(b.auxPaidAllowed!==undefined||b.auxCostCapUsdPerRun!==undefined)Object.assign(bindings[role],{auxPaidAllowed:paid,auxCostCapUsdPerRun:cap});
 }
 return {schemaVersion:1,enabled:input.enabled,bindings,additionalPaidAllowed:false,additionalCostCapUsd:0};
}
const value=v=>v===null||v===undefined?'미관측':v===true?'ON':v===false?'OFF':String(v);
function roleValues(data,preview,role,draft){
 const configured=data.profile?.bindings?.[role];const observed=data.outcome?.roles?.find(r=>r.role===role);
 const expected=draft?.enabled&&data.runtimeEnabled?preview?.effectiveBindings?.[role]?.primary?.target:null;
 return [['현재 서버 배정',configured?.target??'상속'],['Run 설정 배정',observed?.configuredTarget],['Run 실제 적용',observed?.effectiveTarget],['응답 관측',observed?.responseModelId],['관측 호출',observed?.attemptCount],['미리보기 예상',expected]];
}
function activeIdentity(storage){
 try{const raw=JSON.parse(storage.getItem('chat.activeRun')||'null');
  return raw&&Number.isSafeInteger(raw.sessionId)&&raw.sessionId>0&&typeof raw.runToken==='string'&&/^[A-Za-z0-9._:-]{1,128}$/.test(raw.runToken)?{sessionId:raw.sessionId,runToken:raw.runToken}:{};}
 catch(error){return {};}
}
function createClient(fetcher,csrf){
 let version=0,current={kind:'idle',data:null,draft:null};
 async function request(action,payload,draft){
  const seq=++version;current={...current,kind:'loading',draft:draft||current.draft};
  try{
   const response=await fetcher('/api/settings/routing/'+action,{method:'POST',credentials:'same-origin',headers:{'Accept':'application/json','Content-Type':'application/json',...csrf()},body:JSON.stringify(payload)});
   if(seq!==version)return {kind:'stale'};
   if(response.status===401||response.status===403||response.redirected){current={kind:'locked',data:null,draft:current.draft};return current;}
   if(response.status===409){current={...current,kind:'conflict'};return current;}
   if(!response.ok){current={...current,kind:'unavailable'};return current;}
   const data=await response.json();if(seq!==version)return {kind:'stale'};
   current={kind:'ready',data:action==='preview'?current.data:action==='save'?{...current.data,...data}:data,draft:current.draft,preview:action==='preview'?data:null};return current;
  }catch(error){if(seq!==version)return {kind:'stale'};current={...current,kind:'unavailable'};return current;}
 }
 return {state:()=>current,read:(identity={})=>request('read',identity),preview:p=>request('preview',{profile:profile(p)},profile(p)),
  save:(p,state)=>request('save',{profile:profile(p),expectedRevision:state.profileRevision,expectedProfileHash:state.profileHash??null},profile(p))};
}
function mount(doc,fetcher){
 const root=doc.getElementById('routing-root');if(!root)return;
 const make=(tag,text,cls)=>{const n=doc.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
 const csrf=()=>{const name=doc.querySelector('meta[name="_csrf_header"]')?.content;const val=doc.querySelector('meta[name="_csrf"]')?.content;return name&&val?{[name]:val}:{};};
 const client=createClient(fetcher,csrf);
 const identity=()=>activeIdentity(doc.defaultView.sessionStorage);
 const status=make('p','정책 확인 중','routing-status');status.setAttribute('role','status');status.setAttribute('aria-live','polite');
 const form=make('form');form.addEventListener('submit',e=>e.preventDefault());
 const enabled=make('input');enabled.type='checkbox';const enableLabel=make('label','이 프로필을 다음 실행부터 사용');enableLabel.prepend(enabled);form.append(enableLabel);
 const grid=make('div',undefined,'settings-grid');form.append(grid);const controls=new Map();
 const cards=ROLES.map((role,i)=>{
  const card=make('fieldset',undefined,'routing-role');card.append(make('legend',LABELS[i]));
  const select=make('select');select.setAttribute('aria-label',LABELS[i]+' 모델');const label=make('label','배정 모델');label.append(select);card.append(label);
  const falls=[];for(let j=0;j<3;j++){const s=make('select');s.setAttribute('aria-label',LABELS[i]+' 대체 후보 '+(j+1));const l=make('label','대체 후보 '+(j+1));l.append(s);card.append(l);falls.push(s);}
  const budget=make('select');for(let n=0;n<=3;n++){let o=make('option',String(n));o.value=String(n);budget.append(o);}const bl=make('label','허용할 추가 로컬 호출');bl.append(budget);card.append(bl);
  const values=make('dl',undefined,'value-list');card.append(values);grid.append(card);controls.set(role,{select,falls,budget,values});return card;
 });
 const actions=make('div',undefined,'settings-actions');form.append(actions);
 const button=(text,action)=>{const b=make('button',text);b.type='button';b.addEventListener('click',action);actions.append(b);return b;};
 let data=null,dirty=false,pending=false,needsDecision=false,editVersion=0;
 const comparison=make('div',undefined,'routing-comparison');comparison.id='routing-comparison';comparison.hidden=true;form.append(comparison);
 const useDraft=button('내 변경 사용',()=>{if(pending)return;needsDecision=false;comparison.hidden=true;useDraft.hidden=useServer.hidden=true;save.disabled=preview.disabled=false;status.textContent='내 변경을 보관했습니다. 최신 revision으로 서버 프로필 저장을 눌러 적용하세요.';});
 const useServer=button('서버 값 사용',()=>{if(pending)return;dirty=false;needsDecision=false;editVersion++;show(client.state(),true);});
 useDraft.hidden=useServer.hidden=true;
 form.addEventListener('change',()=>{dirty=true;editVersion++;comparison.hidden=true;useDraft.hidden=useServer.hidden=true;show({...client.state(),preview:null},false);});
 function draft(){const bindings={};for(const[role,c]of controls){if(c.select.value){
  bindings[role]={role,selection:'registered-route',target:c.select.value,orderedFallbacks:c.falls.map(s=>s.value).filter(Boolean),maxExtraFallbackCalls:Number(c.budget.value)};
  const saved=data?.profile?.bindings?.[role];
  if(!role.startsWith('MAIN_')&&saved?.target===c.select.value&&saved.auxPaidAllowed!==undefined)
   Object.assign(bindings[role],{auxPaidAllowed:saved.auxPaidAllowed,auxCostCapUsdPerRun:saved.auxCostCapUsdPerRun});
 }}return profile({schemaVersion:1,enabled:enabled.checked,bindings,additionalPaidAllowed:false,additionalCostCapUsd:0});}
 async function action(kind){
  if(pending)return;let selected;
  try{selected=draft();}catch(e){status.textContent='후보 중복이나 추가 호출 수를 확인하세요.';return;}
  const version=editVersion;pending=true;preview.disabled=save.disabled=reload.disabled=useDraft.disabled=useServer.disabled=true;
  try{
   const state=await client[kind](selected,data);
   if(kind==='save'&&state.kind==='conflict')needsDecision=true;
   if(kind==='save'&&state.kind==='ready'&&version===editVersion)dirty=false;
   show({...state,preview:version===editVersion?state.preview:null},false);
  }finally{pending=false;show({...client.state(),preview:version===editVersion?client.state().preview:null},false);reload.disabled=useDraft.disabled=useServer.disabled=false;}
 }
 const preview=button('적용 예상 확인',()=>action('preview'));
 const save=button('서버 프로필 저장',()=>action('save'));
 const reload=button('서버 값 다시 읽기',async()=>{
  if(pending)return;pending=true;preview.disabled=save.disabled=reload.disabled=useDraft.disabled=useServer.disabled=true;
  try{const state=await client.read(identity());if(state.kind==='ready'&&dirty)needsDecision=true;show(state,!dirty);}finally{pending=false;show(client.state(),!dirty);reload.disabled=useDraft.disabled=useServer.disabled=false;}
 });
 function pair(dl,label,v){dl.append(make('dt',label),make('dd',value(v)));}
 function options(select,choices,selected,inherit){select.replaceChildren();let first=make('option',inherit);first.value='';select.append(first);
  for(const c of choices){if(!id(c.id))continue;const o=make('option',c.modelId+' · '+c.provider);o.value=c.id;select.append(o);}
  if(selected&&!choices.some(c=>c.id===selected)){const o=make('option',selected+' · 현재 가용성 미관측');o.value=selected;o.disabled=true;select.append(o);}select.value=selected||'';}
 function execution(view){
  const target=doc.getElementById('capability-view');if(target){target.replaceChildren();for(const descriptor of view.settingsView||[]){
   const card=make('div',undefined,'execution-row');card.append(make('h3',descriptor.id));const dl=make('dl',undefined,'value-list');
   pair(dl,'설정',descriptor.configuredValue??descriptor.configured);pair(dl,'적용',descriptor.effectiveValue??descriptor.effective);pair(dl,'관측',descriptor.observedValue??descriptor.observed);
   pair(dl,'출처',descriptor.sourceKey);pair(dl,'적용 시점',descriptor.applyTiming);card.append(dl);target.append(card);
  }}
  const plan=doc.getElementById('pipeline-view');if(plan){plan.replaceChildren();const dl=make('dl',undefined,'value-list');pair(dl,'관측 Run',view.outcome?.runIdentityHash);pair(dl,'Run 프로필 revision',view.outcome?.profileRevision);pair(dl,'Plan DSL',view.pipelineView?.legacyDslStatus);pair(dl,'조건 선언',view.pipelineView?.whenPresent);pair(dl,'조건 판정',view.pipelineView?.whenState);pair(dl,'후반 조건 판정',view.pipelineView?.postWhenState);pair(dl,'후반 활성',view.pipelineView?.lateActivation);pair(dl,'상태 사유',view.pipelineView?.reasonCode);pair(dl,'최종 주 답변 모델',view.outcome?.mainResponseModelId);pair(dl,'최종 채택',view.outcome?.mainFinalAdopted);plan.append(dl);
   const stages=view.pipelineView?.stages;if(Array.isArray(stages)&&stages.length){for(const stage of stages){const row=make('div',undefined,'execution-row');row.append(make('h3',value(stage.stage)));const values=make('dl',undefined,'value-list');pair(values,'단계 상태',stage.status);pair(values,'결과 수',stage.count);pair(values,'소요 시간(ms)',stage.durationMs);pair(values,'상태 사유',stage.reason);row.append(values);plan.append(row);}}else plan.append(make('p','실행 단계 미관측'));
  }
 }
 function show(state,reset){
  if(state.kind==='stale')return;
  const locked=state.kind==='locked';const unavailable=locked||!state.data;
  for(const element of form.querySelectorAll('select,input'))element.disabled=unavailable;
  form.hidden=locked||!state.data;
  preview.disabled=save.disabled=unavailable||pending||needsDecision||state.kind!=='ready';
  status.textContent=locked?'서버 설정 접근 권한이 필요합니다. 브라우저 기본값은 계속 사용할 수 있습니다.':state.kind==='conflict'?'다른 저장이 먼저 반영되었습니다. 선택값은 유지했습니다. 서버 값을 다시 읽고 비교하세요.':state.kind==='unavailable'?'서버 정책을 확인할 수 없습니다. 자동 재시도나 저장은 하지 않습니다.':state.kind==='ready'?'프로필 revision '+state.data.profileRevision+' · 실행 라우팅 '+(state.data.runtimeEnabled?'ON':'OFF')+' · 런타임 지원 '+value(state.data.runtimeSupported):'확인 중';
  if(!state.data){
   data=null;comparison.hidden=true;useDraft.hidden=useServer.hidden=true;
   for(const elementId of ['capability-view','pipeline-view']){
    const target=doc.getElementById(elementId);if(target)target.replaceChildren(make('p',locked?'권한 확인 필요 · 이전 진단값을 제거했습니다.':'실행 미관측'));
   }
   return;
  }data=state.data;
  if(reset){enabled.checked=!!data.profile?.enabled;for(const[role,c]of controls){const b=data.profile?.bindings?.[role];options(c.select,data.candidates||[],b?.target,'상속');for(let j=0;j<3;j++)options(c.falls[j],data.candidates||[],b?.orderedFallbacks?.[j],'없음');c.budget.value=String(b?.maxExtraFallbackCalls??0);}}
  for(const[role,c]of controls){c.values.replaceChildren();for(const[label,v]of roleValues(data,state.preview,role,state.draft))pair(c.values,label,v);}
  comparison.replaceChildren();comparison.hidden=true;useDraft.hidden=useServer.hidden=true;
  if(needsDecision&&state.kind==='ready'){
   comparison.hidden=false;useDraft.hidden=useServer.hidden=false;
   status.textContent='프로필 revision '+data.profileRevision+' · 내 변경과 최신 서버 값을 비교한 뒤 사용할 값을 선택하세요.';
   const dl=make('dl',undefined,'value-list');
   pair(dl,'내 활성 선택',enabled.checked);pair(dl,'서버 활성 선택',data.profile?.enabled);
   for(const[role,c]of controls){
    const b=data.profile?.bindings?.[role];
    pair(dl,LABELS[ROLES.indexOf(role)]+' · 내 변경',[c.select.value||'상속',...c.falls.map(s=>s.value||'없음'),'추가 호출 '+c.budget.value].join(' / '));
    pair(dl,LABELS[ROLES.indexOf(role)]+' · 최신 서버',[b?.target||'상속',...(b?.orderedFallbacks||[]),'추가 호출 '+(b?.maxExtraFallbackCalls??0)].join(' / '));
   }
   comparison.append(dl);
  }
  execution(data);
 }
 root.replaceChildren(status,form);client.read(identity()).then(state=>show(state,true));
}
return {ROLES,profile,value,roleValues,activeIdentity,createClient,mount};
});
