(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory();else root.NovaFocusControls=factory();})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  // Ephemeral only. Legacy IndexedDB data is preserved but never read or replayed.
  function createCache(host){
    let current='',row=null,timer=null,expires=0;
    const now=()=>host.Date?.now?.()??Date.now();
    function clear(){if(timer!==null)host.clearTimeout?.(timer);timer=null;row=null;current='';expires=0;}
    async function access(scope,change){
      if(!/^[a-f0-9]{64}$/.test(scope||''))return null;
      if(current!==scope||now()>=expires)clear();
      if(!row){current=scope;row={pages:{},outbox:[]};}
      if(change)change(row);
      expires=now()+300000;
      if(timer!==null)host.clearTimeout?.(timer);
      timer=host.setTimeout?.(clear,300000)??null;
      return JSON.parse(JSON.stringify(row));
    }
    return {read:scope=>access(scope),change:(scope,fn)=>access(scope,fn),clear};
  }
  function mount({host=globalThis,document=host.document,client,cache=createCache(host)}){
    const $=id=>document.getElementById(id),panel=$('nova-fold');
    if(!panel||!host.NovaFocus)return null;
    let loaded='',loading=false,stored=null,settingsBusy=false,cursor=null,historyBusy=false,pendingInput=null;
    let cacheScope='',scopeEpoch=0,reconcileBusy=false,disposed=false,settingsAttempts=0;
    $('nova-question').disabled=true;
    const projection=host.NovaFocus.createProjection({host,document,target:'fold',panel,status:$('nova-fold-status'),draft:$('nova-fold-draft'),answer:$('nova-fold-answer'),receipt:host.NovaFocus.receiptSender(host)});
    const fields={enabled:'nf-enabled',wakeWord:'nf-wake',utteranceQuietMs:'nf-quiet',followupIdleMs:'nf-idle',wakeListenTimeoutMs:'nf-listen'};
    const presentation={sequentialTextEnabled:'nf-sequential',charIntervalMs:'nf-speed',maxVisibleLines:'nf-lines',autoFadeEnabled:'nf-fade-on',tailHoldMs:'nf-hold',fadeMs:'nf-fade'};
    const messages={focus_settings_conflict:'다른 기기에서 설정이 바뀌었습니다. 서버 설정을 다시 불러와 주세요.',focus_busy:'앞선 질문을 처리하고 있습니다. 잠시 후 다시 시도해 주세요.',focus_unavailable:'노바 응답 경로가 아직 준비되지 않았습니다.',focus_answer_unavailable:'응답을 확인하지 못했습니다. 기록에서 상태를 확인해 주세요.',focus_session_stale:'연결이 바뀌었습니다. 다시 연결된 뒤 시도해 주세요.',event_owner_required:'현재 수음을 보내는 기기에서 사용해 주세요.',display_rate_limited:'잠시 후 다시 시도해 주세요.'};
    messages.focus_next_question_full='다음 질문 하나가 대기 중입니다. 그 답변 뒤에 다시 질문해 주세요.';
    messages.focus_request_already_accepted='이미 접수된 질문입니다. 대화 기록에서 상태와 답변을 확인해 주세요.';
    messages.focus_request_conflict='이미 사용한 질문 번호입니다. 내용을 확인한 뒤 새 질문으로 보내 주세요.';
    messages.focus_input_limit='질문이 너무 길어 제출하지 못했습니다. 짧게 다시 말씀해 주세요.';
    messages.presentation_unconfirmed='답변 표시 완료를 확인하지 못해 집중창을 닫았습니다. 대화 원문은 저장하지 않습니다.';
    messages.focus_outbox_pending='접수 여부가 확인되지 않은 질문이 이 창에 남아 있습니다. 내용을 확인한 뒤 직접 다시 보내 주세요.';
    messages.focus_outbox_full='아직 접수를 확인하지 못한 질문이 있습니다. 연결 후 대화 기록을 확인해 주세요.';
    messages.focus_cache_unavailable='임시 질문 상태를 준비하지 못했습니다. 연결을 다시 확인해 주세요.';
    function notice(error){const text=messages[error?.message]||'연결과 입력 범위를 확인해 주세요.';$('nf-status').textContent=text;const alert=$('error');if(alert)alert.textContent=text;}
    function paintSettings(value){
      stored=value;
      for(const [key,id] of Object.entries(fields)){const e=$(id);if(e.type==='checkbox')e.checked=!!value.settings[key];else e.value=value.settings[key];}
      for(const [key,id] of Object.entries(presentation)){const e=$(id);if(e.type==='checkbox')e.checked=!!value.settings.presentation[key];else e.value=value.settings.presentation[key];}
      $('nf-status').textContent='저장된 설정을 불러왔습니다.';$('nf-save').disabled=false;
    }
    async function loadSettings(){
      if(loading||settingsAttempts>=3)return;loading=true;settingsAttempts++;
      const epoch=scopeEpoch;
      try{const value=await client.focusRequest('settings/read');if(epoch!==scopeEpoch||disposed)return;if(!Number.isSafeInteger(value.settingsVersion)||!value.settings?.presentation)throw Error('focus_contract');paintSettings(value);
        cacheScope=/^[a-f0-9]{64}$/.test(value.cacheScope||'')?value.cacheScope:'';$('nova-question').disabled=!cacheScope;await reconcile(true);}
      catch(error){notice(error);}finally{loading=false;}
    }
    async function reconcile(restore=false){
      if(reconcileBusy||!cacheScope)return;
      reconcileBusy=true;const scope=cacheScope,epoch=scopeEpoch;
      try{
        const row=await cache.read(scope);if(!row||epoch!==scopeEpoch||disposed)return;
        const remaining=[];
        for(const input of row.outbox){
          if(epoch!==scopeEpoch||disposed)return;
          try{const status=await client.focusRequest('input/status',input);if(status.accepted!==true)remaining.push(input);}
          catch{remaining.push(input);}
        }
        if(epoch!==scopeEpoch||disposed)return;
        const checked=new Set(row.outbox.map(input=>input.requestId)),kept=new Set(remaining.map(input=>input.requestId));
        await cache.change(scope,value=>{value.outbox=value.outbox.filter(input=>!checked.has(input.requestId)||kept.has(input.requestId));});
        if(restore&&epoch===scopeEpoch&&!disposed&&remaining.length&&!$('nova-question').value){
          pendingInput=remaining[0];$('nova-question').value=pendingInput.text;notice({message:'focus_outbox_pending'});
        }
      }catch{/* Server history remains authoritative when browser storage is unavailable. */}
      finally{reconcileBusy=false;}
    }
    function clearContent(){cache.clear?.();pendingInput=null;$('nova-question').value='';$('nova-history-list').replaceChildren();$('nova-history').hidden=true;}
    async function close(){scopeEpoch++;clearContent();try{await client.focusRequest('close');}catch(error){notice(error);}}
    $('nova-open').onclick=async()=>{try{await client.focusRequest('open',{renderTarget:$('nf-target').value});}catch(error){notice(error);}};
    $('nova-close').onclick=close;
    $('nova-pause').onclick=()=>{$('nova-pause').textContent=projection.togglePause()?'표시 계속':'표시 잠시 멈춤';};
    $('nova-replay').onclick=()=>projection.replay();
    $('nova-question-form').onsubmit=async event=>{
      event.preventDefault();const e=$('nova-question');if(!e.value.trim()||e.value.length>2000||e.disabled||!cacheScope)return;
      if(!pendingInput||pendingInput.text!==e.value)pendingInput={requestId:host.crypto.randomUUID(),text:e.value};
      e.disabled=true;const scope=cacheScope,epoch=scopeEpoch,input={...pendingInput};
      try{
        await reconcile();
        const row=await cache.read(scope);if(!row)throw Error('focus_cache_unavailable');
        if(row&&!row.outbox.some(item=>item.requestId===input.requestId)&&row.outbox.length>=8)throw Error('focus_outbox_full');
        const saved=await cache.change(scope,value=>{if(!value.outbox.some(item=>item.requestId===input.requestId))value.outbox.push(input);});
        if(!saved)throw Error('focus_cache_unavailable');
        if(epoch!==scopeEpoch||disposed)return;
        await client.focusRequest('input',input);
        if(epoch!==scopeEpoch||disposed)return;
        pendingInput=null;e.value='';await reconcile();
      }
      catch(error){notice(error);}finally{e.disabled=!cacheScope;}
    };
    $('nf-reload').onclick=()=>{settingsAttempts=0;return loadSettings();};
    $('nova-settings-form').onsubmit=async event=>{
      event.preventDefault();if(settingsBusy||!stored)return;settingsBusy=true;$('nf-save').disabled=true;
      try{
        const settings={presentation:{}};
        for(const [key,id] of Object.entries(fields)){const e=$(id);settings[key]=e.type==='checkbox'?e.checked:key==='wakeWord'?e.value:Number(e.value);}
        for(const [key,id] of Object.entries(presentation)){const e=$(id);settings.presentation[key]=e.type==='checkbox'?e.checked:Number(e.value);}
        const value=await client.focusRequest('settings',{settingsVersion:stored.settingsVersion,settings});paintSettings(value);$('nf-status').textContent='설정을 저장했습니다.';
      }catch(error){notice(error);}finally{settingsBusy=false;$('nf-save').disabled=!stored;}
    };
    async function history(before=null){
      if(historyBusy)return;historyBusy=true;
      const scope=cacheScope,epoch=scopeEpoch,key=String(before??'latest');
      try{
        let value,cached=false;
        try{value=await client.focusRequest('history',{beforeSequence:before,limit:10});}
        catch(error){value=(await cache.read(scope).catch(()=>null))?.pages?.[key];if(!value)throw error;cached=true;}
        if(epoch!==scopeEpoch||disposed)return;
        if(!Array.isArray(value.turns)||value.turns.length>10)throw Error('focus_contract');
        if(!cached)await cache.change(scope,row=>{delete row.pages[key];row.pages[key]=value;while(Object.keys(row.pages).length>3)delete row.pages[Object.keys(row.pages)[0]];}).catch(()=>null);
        if(epoch!==scopeEpoch||disposed)return;
        const list=$('nova-history-list');list.replaceChildren();
        for(const turn of value.turns){
          const article=document.createElement('article'),question=document.createElement('p'),answer=document.createElement('p');
          question.textContent=turn.question?'나 · '+turn.question:'대화 원문 비저장';
          answer.textContent=turn.answer?'노바 · '+turn.answer:turn.state==='COMPLETED'?'응답 완료':turn.state==='CANCELLED'?'취소된 질문':turn.state==='OUTCOME_UNKNOWN'?'응답 결과를 확인하지 못한 질문':'응답 처리 중';
          article.append(question,answer);list.append(article);
        }
        if(!value.turns.length)list.textContent='저장된 대화가 없습니다.';
        cursor=value.beforeSequence;$('nova-history-older').disabled=cursor==null;$('nova-history').hidden=false;
        if(cached)$('nf-status').textContent='저장된 임시 기록입니다. 연결 후 서버 기록을 다시 확인해 주세요.';
        else await reconcile();
      }catch(error){notice(error);}finally{historyBusy=false;}
    }
    $('nova-history-open').onclick=()=>history();$('nova-history-older').onclick=()=>history(cursor);$('nova-history-close').onclick=()=>{$('nova-history').hidden=true;};
    function update(state){
      const owned=state.ready&&state.focusProducer===true;
      $('nova-open').disabled=!owned;$('nova-history-open').disabled=!owned;
      const key=state.assistId+':'+state.epoch;
      if((owned&&loaded!==key)||(!owned&&loaded)){
        clearContent();
        scopeEpoch++;cacheScope='';pendingInput=null;$('nova-question').value='';$('nova-question').disabled=true;$('nova-history-list').replaceChildren();$('nova-history').hidden=true;
        loaded=owned?key:'';stored=null;settingsAttempts=0;$('nf-save').disabled=true;
      }
      if(owned&&!stored&&!loading)void loadSettings();
      projection.update(state.focus,state.ready&&state.connection==='READY');
      if(state.focus?.reason&&messages[state.focus.reason])notice({message:state.focus.reason});
    }
    document.addEventListener('visibilitychange',projection.visibility);
    return {update,close,active:projection.isActive,dispose:()=>{disposed=true;scopeEpoch++;clearContent();projection.dispose();}};
  }
  return {mount,createCache};
});
