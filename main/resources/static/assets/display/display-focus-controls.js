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
    let loaded='',loading=false,stored=null,settingsBusy=false,settingsEdits=0,cursor=null,historyBusy=false,pendingInput=null;
    let cacheScope='',scopeEpoch=0,reconcileBusy=false,disposed=false,settingsAttempts=0,wasActive=false,memoryDraft=null,memoryBusy=false,projectionScope='';
    // OFF 의도는 서버 저장 성공과 무관하게 이 기기의 촬영을 즉시 차단한다. ON은 저장 성공 뒤에만 해제된다.
    let snapshotLocalBlocked=false,catalogLoaded=false,catalogBusy=false,catalogRows=[],catalogGeneration=0,catalogAbort=null;
    let fallbackPreference=false; // Stored general-mode preference; strict mode shows effective OFF.
    const modelInputs=['nf-answer-model','nf-answer-backup-1','nf-answer-backup-2','nf-answer-backup-3'];
    const captureJobs=new Map();
    const snapshotter=host.DisplaySnapshot?host.DisplaySnapshot.createSnapshotter({navigator:host.navigator,document}):null;
    $('nova-question').disabled=true;
    const projection=host.NovaFocus.createProjection({host,document,target:'fold',panel,status:$('nova-fold-status'),draft:$('nova-fold-draft'),answer:$('nova-fold-answer'),receipt:host.NovaFocus.receiptSender(host),reasonText:reason=>Object.prototype.hasOwnProperty.call(messages,reason)?messages[reason]:host.NovaFocus.closeReasonText?.(reason),keepClosed:()=>$('device-debug')?.open===true,diagnostic:(event,detail)=>host.console?.debug?.('[AWX][nova-focus]',event,{...detail,epoch:client.state?.epoch||0,connection:client.state?.connection||'UNKNOWN'})});
    const fields={enabled:'nf-enabled',recallEnabled:'nf-recall',rememberFactsEnabled:'nf-remember',wakeWord:'nf-wake',cameraWakeWord:'nf-camera-wake',utteranceQuietMs:'nf-quiet',followupIdleMs:'nf-idle',wakeListenTimeoutMs:'nf-listen',answerLengthChars:'nf-answer-length',quickAnswerEnabled:'nf-quick',answerInstruction:'nf-answer-instruction'};
    const flags=new Set(['enabled','recallEnabled','rememberFactsEnabled','quickAnswerEnabled']);
    const length=$('nf-answer-length'),lengthPreset=$('nf-answer-length-preset');
    function syncLengthPreset(){if(lengthPreset)lengthPreset.value=['320','400','480'].includes(String(length?.value))?String(length.value):'';}
    if(length)length.oninput=syncLengthPreset;
    if(lengthPreset)lengthPreset.onchange=()=>{if(lengthPreset.value&&length)length.value=lengthPreset.value;};
    if($('nf-answer-length-reset'))$('nf-answer-length-reset').onclick=()=>{if(length){length.value=400;settingsEdits++;}syncLengthPreset();};
    const INTERVIEW_INSTRUCTION='너는 면접 중인 사용자를 돕는 안경 보조다. 들린 면접 질문에 대해 사용자가 그대로 말할 수 있는 1인칭 답변을 짧은 문장 2~4개로 준다. 존댓말, 핵심 먼저, 숫자·경험 예시 1개. 확실하지 않은 사실은 지어내지 않는다.';
    const answerPreset=$('nf-answer-preset'),answerInstruction=$('nf-answer-instruction'),instructionCount=$('nf-answer-instruction-count');
    function syncInstructionCount(){if(instructionCount&&answerInstruction)instructionCount.textContent=[...answerInstruction.value].length+'/1200';}
    if(answerPreset)answerPreset.onchange=()=>{if(answerInstruction&&answerPreset.value==='INTERVIEW')answerInstruction.value=INTERVIEW_INSTRUCTION;else if(answerInstruction&&answerPreset.value==='GENERAL')answerInstruction.value='';syncInstructionCount();};
    if(answerInstruction)answerInstruction.oninput=()=>{if(answerPreset&&answerPreset.value!=='CUSTOM'&&answerInstruction.value!==(answerPreset.value==='INTERVIEW'?INTERVIEW_INSTRUCTION:''))answerPreset.value='CUSTOM';syncInstructionCount();};
    if($('nf-answer-instruction-reset'))$('nf-answer-instruction-reset').onclick=()=>{if(answerPreset)answerPreset.value='GENERAL';if(answerInstruction)answerInstruction.value='';syncInstructionCount();settingsEdits++;};
    const presentation={sequentialTextEnabled:'nf-sequential',charIntervalMs:'nf-speed',maxVisibleLines:'nf-lines',autoFadeEnabled:'nf-fade-on',tailHoldMs:'nf-hold',fadeMs:'nf-fade'};
    const messages={focus_settings_conflict:'다른 기기에서 설정이 바뀌었습니다. 서버 설정을 다시 불러와 주세요.',focus_busy:'앞선 질문을 처리하고 있습니다. 잠시 후 다시 시도해 주세요.',focus_unavailable:'노바 응답 경로가 아직 준비되지 않았습니다.',focus_answer_unavailable:'응답을 확인하지 못했습니다. 기록에서 상태를 확인해 주세요.',focus_session_stale:'연결이 바뀌었습니다. 다시 연결된 뒤 시도해 주세요.',event_owner_required:'현재 수음을 보내는 기기에서 사용해 주세요.',display_rate_limited:'잠시 후 다시 시도해 주세요.'};
    Object.assign(messages,host.NovaFocus.CLOSE_REASONS||{});
    messages.focus_model_selection_invalid='기본 모델과 대체 모델을 중복 없이 선택해 주세요.';
    messages.focus_next_question_full='다음 질문 하나가 대기 중입니다. 그 답변 뒤에 다시 질문해 주세요.';
    messages.focus_request_already_accepted='이미 접수된 질문입니다. 대화 기록에서 상태와 답변을 확인해 주세요.';
    messages.focus_request_conflict='이미 사용한 질문 번호입니다. 내용을 확인한 뒤 새 질문으로 보내 주세요.';
    messages.focus_input_limit='질문이 너무 길어 제출하지 못했습니다. 짧게 다시 말씀해 주세요.';
    messages.presentation_unconfirmed='답변 표시 완료를 확인하지 못해 집중창을 닫았습니다. 대화 원문은 저장하지 않습니다.';
    messages.focus_outbox_pending='접수 여부가 확인되지 않은 질문이 이 창에 남아 있습니다. 내용을 확인한 뒤 직접 다시 보내 주세요.';
    messages.focus_outbox_full='아직 접수를 확인하지 못한 질문이 있습니다. 연결 후 대화 기록을 확인해 주세요.';
    messages.snapshot_unsupported='이 브라우저에서 카메라 촬영을 시작할 수 없습니다.';
    messages.focus_grounding_publication_held='검색 답변의 출처 표시를 확인할 수 없어 답변을 보류했습니다.';
    Object.assign(messages,{
      focus_search_off:'웹 검색이 꺼져 있습니다. 검색을 허용하거나 일반 자동 모드로 전환한 뒤 저장해 주세요.',
      focus_search_quick:'빠른 답변에서는 검색하지 않습니다. 빠른 답변을 끄거나 일반 자동 모드로 전환해 주세요.',
      focus_search_image_unsupported:'현재 사진 질문은 전용 검색을 지원하지 않습니다. 사진 설정 또는 실행 경로를 변경해 주세요.',
      focus_search_model_required:'전용 모드에 사용할 Gemini 모델을 직접 선택해 주세요.',
      focus_search_unsupported:'선택 모델의 native Google 검색 지원을 확인하지 못했습니다. 모델 또는 실행 경로를 변경해 주세요.',
      focus_search_not_observed:'검색이 실행된 것을 확인하지 못했습니다. 일반 자동 모드로 전환할 수 있습니다.',
      focus_search_attribution_unavailable:'검색 결과의 출처 표시를 확인하지 못했습니다. 일반 자동 모드로 전환할 수 있습니다.',
      focus_search_model_mismatch:'선택한 모델과 응답의 연결을 확인하지 못했습니다. 실행 경로를 확인해 주세요.',
      focus_search_provider_unauthorized:'선택 Gemini의 접근 권한을 확인하지 못했습니다.',
      focus_search_quota_exceeded:'선택 Gemini의 사용 한도에 도달했습니다.',focus_search_rate_limited:'선택 Gemini의 요청 한도에 도달했습니다.',
      focus_search_backend_timeout:'선택 Gemini의 응답 시간이 지났습니다.',focus_search_backend_unavailable:'선택 Gemini 서비스를 사용할 수 없습니다.',
      focus_search_model_unavailable:'선택 Gemini 모델을 사용할 수 없습니다.',focus_search_provider_not_configured:'선택 Gemini 연결이 준비되지 않았습니다.',
      focus_search_protocol_unsupported:'선택 연결에서 native Google 검색을 사용할 수 없습니다.',focus_search_request_cancelled:'전용 검색 요청이 취소됐습니다.'
    });
    messages.focus_cache_unavailable='임시 질문 상태를 준비하지 못했습니다. 연결을 다시 확인해 주세요.';
    messages.snapshot_timeout='촬영 시간이 지나 사진 없이 답변합니다.';
    messages.snapshot_failed='사진을 확보하지 못해 사진 없이 답변합니다.';
    messages.device_unavailable='선택한 촬영 장치는 아직 연결되지 않았습니다. 사진 없이 답변합니다.';
    messages.permission_denied='카메라 권한이 없어 사진 없이 답변합니다.';
    messages.camera_unavailable='카메라를 사용할 수 없어 사진 없이 답변합니다.';
    messages.camera_timeout='카메라가 응답하지 않아 사진 없이 답변합니다.';
    messages.camera_frame_timeout='카메라가 응답하지 않아 사진 없이 답변합니다.';
    messages.camera_black_frame='유효한 사진을 확보하지 못해 사진 없이 답변합니다.';
    messages.camera_rear_unverified='후면 카메라를 확인하지 못해 사진 없이 답변합니다.';
    messages.cancelled='촬영이 취소되어 사진 없이 답변합니다.';
    messages.capture_busy='이전 촬영을 마무리하는 중입니다.';
    messages.getusermedia_not_supported='이 브라우저에서 카메라를 사용할 수 없습니다.';
    messages.focus_snapshot_conflict='촬영 결과가 현재 질문과 맞지 않아 폐기했습니다.';
    messages.snapshot_stale='이전 촬영 결과는 사용하지 않았습니다.';
    // Jev 사유 문구는 전용 표다 — messages.permission_denied는 카메라 권한 문구로 이미 쓰인다.
    const jevReasonText={auth_invalid:'키 만료·무효 · 키 교체 필요',key_invalid_or_expired:'키 만료·무효 · 키 교체 필요', // jev-vocab: legacy-alias
      auth_blocked:'인증 차단 · 키 교체 후 서버 재시작 필요',plan_gate:'요금제 제한(Pro 전용 기능 요청) · 기존 판단 사용',
      permission_denied:'Jev 접근 거부(권한·지역) · 기존 판단 사용',forbidden:'Jev 접근 거부(권한·지역) · 기존 판단 사용', // jev-vocab: legacy-alias
      budget_skip:'예산 제한(무료 기간·유료 허용·일일 상한) · 호출 안 함',rate_limited:'요청 한도 초과 · 잠시 후 재시도',
      upstream_error:'Jev 서버 오류 · 기존 판단 사용'};
    function jevReasonLabel(code){const key=String(code||'');const text=jevReasonText[key];return text?text+' ('+key+')':(key?'대기('+key+')':'대기');}
    function notice(error){const text=messages[error?.message]||'연결과 입력 범위를 확인해 주세요.';$('nf-status').textContent=text;const alert=$('error');if(alert)alert.textContent=text;}
    function modelStatus(){
      const selectedReasoningModel=$('nf-answer-model')?.value||'';
      const reasoningRow=catalogRows.find(row=>row.id===selectedReasoningModel);
      const reasoningSupported=reasoningRow?.metadata?.focusReasoningSupported===true;
      if($('nf-reasoning'))$('nf-reasoning').disabled=!reasoningSupported;
      if($('nf-reasoning-status'))$('nf-reasoning-status').textContent=reasoningSupported?
        'Fast → low · Standard → medium · Deep → high. 다음 질문부터 적용됩니다.':
        '이 모델의 추론 깊이 지원을 확인하지 못했습니다. 저장한 선호는 유지하며 미지원 옵션은 전송하지 않습니다.';
      const output=$('nf-model-status');if(!output)return;
      const selected=$('nf-answer-model')?.value,row=catalogRows.find(value=>value.id===selected);
      const exclusive=$('nf-answer-target')?.value==='GEMINI_WEBSEARCH_ONLY';
      for(const id of modelInputs.slice(1))if($(id))$(id).disabled=exclusive;
      if($('nf-answer-fallback')){$('nf-answer-fallback').disabled=false;$('nf-answer-fallback').checked=!exclusive&&fallbackPreference;}
      if(exclusive){output.textContent=!selected?messages.focus_search_model_required:row?.metadata?.googleSearchSupported===true?
        '선택 Gemini의 native 검색 지원이 확인됐습니다. 실제 검색·인용·표시는 응답마다 확인합니다.':
        messages.focus_search_unsupported+' ('+(row?.metadata?.googleSearchReason||'capability_unknown')+')';output.textContent+=' 모델 자동 전환 OFF · ON을 선택하면 선택 모델 우선 모드로 전환합니다.';return;}
      output.textContent=!selected?'자동 선택은 기존 서버 정책을 따릅니다.':row?.selectable===false?
        '서버 목록에서 현재 선택 불가입니다. 저장된 선택은 유지합니다. 다른 모델을 선택하세요.':row?.selectable===true?
        '선택 가능한 서버 목록에 있습니다. 실제 실행·세부 지원 기능은 별도 확인이 필요합니다.':
        '저장된 선택의 현재 사용 가능 여부가 미확인입니다. 선택은 유지합니다.';
      output.textContent+=fallbackPreference?' 선택 모델 우선 · 모델 자동 전환 ON (허용 목록만).':' 모델 자동 전환 OFF.';
    }
    function modelOptions(rows,selections){
      for(const id of modelInputs){const input=$(id);if(!input)continue;const selected=selections?.[id]??input.value??'';
        input.replaceChildren();const add=(value,label,disabled=false)=>{const option=document.createElement('option');option.value=value;option.textContent=label;option.disabled=disabled;input.append(option);};
        add('',id==='nf-answer-model'?'자동 선택':'대체 모델 없음');
        for(const row of rows.filter(value=>value.selectable===true))add(row.id,row.provider+' · '+row.id);
        if(selected&&!rows.some(row=>row.id===selected&&row.selectable===true)){
          const unavailable=rows.some(row=>row.id===selected&&row.selectable===false);
          add(selected,'저장된 선택 · '+selected+(unavailable?' (현재 선택 불가)':' (현재 사용 가능 여부 미확인)'),true);
        }
        input.value=selected;
      }
      modelStatus();
    }
    if($('nf-answer-model'))$('nf-answer-model').onchange=modelStatus;
    if($('nf-answer-target'))$('nf-answer-target').onchange=modelStatus;
    if($('nf-answer-fallback'))$('nf-answer-fallback').onchange=()=>{
      fallbackPreference=$('nf-answer-fallback').checked;
      if(fallbackPreference&&$('nf-answer-target').value==='GEMINI_WEBSEARCH_ONLY')$('nf-answer-target').value='AUTO';
      settingsEdits++;modelStatus();$('nf-status').textContent='모델 전환 설정을 저장하면 다음 질문부터 적용합니다.';
    };
    if($('nf-general-mode'))$('nf-general-mode').onclick=()=>{$('nf-answer-target').value='AUTO';settingsEdits++;modelStatus();$('nf-status').textContent='일반 자동 모드로 전환할 설정입니다. 저장 후 다음 질문부터 적용합니다.';};
    // 메인 모델 프리셋: 카탈로그에서 선택 가능한 경로만 사용한다. 클릭은 편집값만 바꾸고 저장 전 전송은 없다.
    const presetSay=text=>{const e=$('nf-preset-status');if(e)e.textContent=text;};
    function presetModel(res){
      for(const re of res){const hit=catalogRows.find(row=>re.test(row.id)&&row.selectable===true);if(hit)return hit.id;}
      return null;
    }
    function presetApply(target,model,webSearch,text){
      if(model===null){presetSay("선택 가능한 모델이 없습니다. 기존 선택을 유지합니다.");return;}
      if($('nf-answer-target'))$('nf-answer-target').value=target;
      if($('nf-answer-model')){const selections={};for(const id of modelInputs)selections[id]=id==='nf-answer-model'?model:($(id)?.value||'');modelOptions(catalogRows,selections);}
      if(webSearch!==null&&$('nf-web-search'))$('nf-web-search').value=webSearch;
      settingsEdits++;modelStatus();presetSay(text);
    }
    if($('nf-preset-luna'))$('nf-preset-luna').onclick=()=>presetApply('API_ONLY',presetModel([/^chatgpt-oauth:gpt-5\.6-luna$/i,/^chatgpt-oauth:.*luna/i]),'false','루나(Luna) 모델이 선택되었습니다. 설정을 저장하면 다음 질문부터 적용됩니다.');
    if($('nf-preset-gemini'))$('nf-preset-gemini').onclick=()=>presetApply('GEMINI_WEBSEARCH_ONLY',presetModel([/gemini-pro/i,/gemini/i]),'true','제미나이(Gemini) 웹검색 전용 모델이 선택되었습니다. 설정을 저장하면 다음 질문부터 적용됩니다.');
    if($('nf-preset-auto'))$('nf-preset-auto').onclick=()=>presetApply('AUTO','',null,'자동 모드로 전환할 설정입니다. 저장하면 다음 질문부터 적용됩니다.');
    async function loadModels(){
      if(catalogLoaded||catalogBusy||!host.fetch||!$('nf-answer-model'))return;catalogBusy=true;
      const generation=catalogGeneration,abort=new AbortController();catalogAbort=abort;const timer=host.setTimeout?.(()=>abort.abort(),4000);
      try{const response=await host.fetch('/api/chat/models',{credentials:'same-origin',cache:'no-store',signal:abort.signal});
        if(!response.ok)throw Error('model_catalog_unavailable');const rows=await response.json();
        if(!Array.isArray(rows)||rows.length>128)throw Error('model_catalog_contract');
        if(disposed||generation!==catalogGeneration)return;catalogRows=rows.filter(row=>row&&typeof row.id==='string'&&/^[a-zA-Z0-9][a-zA-Z0-9._/:+-]{0,179}$/.test(row.id));modelOptions(catalogRows);
        catalogLoaded=true;
      }catch{if(!disposed&&generation===catalogGeneration&&$('nf-model-status'))$('nf-model-status').textContent='모델 목록을 확인하지 못했습니다. 저장된 선택은 유지합니다.';}
      finally{host.clearTimeout?.(timer);if(generation===catalogGeneration){catalogBusy=false;catalogAbort=null;}}
    }
    function paintSettings(value){
      value={...value,settings:{...value.settings,answerLengthChars:value.settings?.answerLengthChars??400,quickAnswerEnabled:value.settings?.quickAnswerEnabled??false,reasoningPreset:value.settings?.reasoningPreset??'STANDARD',answerPreset:value.settings?.answerPreset??null,answerInstruction:value.settings?.answerInstruction??'',cameraWakeWord:value.settings?.cameraWakeWord??'데빈'}};
      if($('nf-reasoning'))$('nf-reasoning').value=value.settings.reasoningPreset;
      stored=value;
      for(const [key,id] of Object.entries(fields)){const e=$(id);if(!e)continue;if(flags.has(key))e.checked=!!value.settings[key];else e.value=value.settings[key];}
      syncLengthPreset();
      if($('nf-answer-preset'))$('nf-answer-preset').value=['GENERAL','INTERVIEW','CUSTOM'].includes(value.settings.answerPreset)?value.settings.answerPreset:(value.settings.answerInstruction?'CUSTOM':'GENERAL');
      syncInstructionCount();
      if($('nf-web-search'))$('nf-web-search').value=value.settings.webSearchEnabled==null?'':String(value.settings.webSearchEnabled);
      for(const [key,id] of Object.entries(presentation)){const e=$(id);if(e.type==='checkbox')e.checked=!!value.settings.presentation[key];else e.value=key==='tailHoldMs'?value.settings.presentation[key]/1000:value.settings.presentation[key];}
      const snap=value.settings.snapshot||{enabled:false,source:'FOLD_REAR'};
      const snapEnabled=$('nf-snapshot-enabled'),snapSource=$('nf-snapshot-source'),camAllowed=$('nf-camera-allowed');
      if(snapEnabled)snapEnabled.checked=!!snap.enabled;if(snapSource)snapSource.value=snap.source||'FOLD_REAR';
      if(camAllowed)camAllowed.checked=snap.cameraAllowed!==false;
      const selection=value.settings.answerSelection||{mode:'AUTO',modelId:''},routing=selection.routing||{executionTarget:'AUTO',fallbackAllowed:false,allowedFallbackIds:[]};
      if($('nf-answer-model')){const selections={'nf-answer-model':selection.mode==='FIXED'?selection.modelId:''};
        for(let i=1;i<=3;i++)selections['nf-answer-backup-'+i]=routing.allowedFallbackIds?.[i-1]||'';
        fallbackPreference=!!routing.fallbackAllowed;
        modelOptions(catalogRows,selections);$('nf-answer-target').value=routing.executionTarget;$('nf-answer-fallback').checked=fallbackPreference;
        modelStatus();
      }
      const recent=value.settings.recentContext||{enabled:true,maxAgeSeconds:180,maxUtterances:12,tokenBudget:2000};
      if($('nf-recent-enabled')){ $('nf-recent-enabled').checked=!!recent.enabled;$('nf-recent-age').value=recent.maxAgeSeconds;$('nf-recent-count').value=recent.maxUtterances;$('nf-recent-budget').value=recent.tokenBudget;}
      const mem=value.settings.memory||{};
      if($('nf-memory-mode')){$('nf-memory-mode').value=mem.mode??'';$('nf-graph-mode').value=mem.graphMode||'OFF';$('nf-max-evidence').value=mem.maxEvidence??4;$('nf-embed-prefer').value=mem.embeddingPrefer||'LOCAL_THEN_CLOUD';$('nf-web-unknown').value=mem.webOnUnknown==null?'':String(!!mem.webOnUnknown);}
      // Jev 모드는 서버 소유 표시값이다. 저장 응답(settings)에는 없으므로 없으면 이전 표시를 유지한다.
      const jev=value.jev;
      if(jev&&typeof jev==='object'){
        const jevMode=['off','shadow','on'].includes(String(jev.mode).toLowerCase())?String(jev.mode).toLowerCase():'off';
        const jevSelect=$('nf-jev'),jevStatus=$('nf-jev-status');
        if(jevSelect){jevSelect.value=jevMode.toUpperCase();for(const option of jevSelect.options)option.disabled=option.value!==jevSelect.value;jevSelect.disabled=true;}
        if(jevStatus)jevStatus.textContent=jev.configured!==true?'Jev 키 미설정 · 기존 검색 판단을 사용합니다.'
          :jevMode==='off'?'Jev: OFF · 기존 검색 판단을 사용합니다.'
          :jevMode==='shadow'?'Jev: SHADOW · 판단은 기록에만 남기고 기존 검색 판단을 사용합니다.'
          :jev.callsAllowed===true?'Jev: ON · Jev 판단을 검색 경로에 반영합니다.'
          :'Jev: ON · '+jevReasonLabel(jev.reason)+' · 기존 검색 판단을 사용합니다.';
        // surface별 표시는 읽기 전용이다. global off·계약 누락은 숨김, 모르는 값은 OFF로 렌더한다.
        const jevSurfaces=$('nf-jev-surfaces'),jevModes=jev.surfaceModes&&typeof jev.surfaceModes==='object'?jev.surfaceModes:null;
        if(jevSurfaces){
          if(jevMode!=='off'&&jevModes){
            const label=v=>['off','shadow','on'].includes(String(v).toLowerCase())?String(v).toUpperCase():'OFF';
            jevSurfaces.textContent='답변(Focus): '+label(jevModes.focus)+' · 힌트(Cue): '+label(jevModes.cue);jevSurfaces.hidden=false;
          }else{jevSurfaces.hidden=true;jevSurfaces.textContent='';}
        }
      }
      void loadModels();
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
    /** 진행 중 촬영 작업을 취소하고 소유 트랙·전송을 즉시 중단한다. 마이크/음성 경로는 건드리지 않는다. */
    function cancelCaptureJobs(){
      for(const job of captureJobs.values()){job.cancelled=true;try{job.abort?.abort();}catch{}}
      captureJobs.clear();snapshotter?.stop?.();
      const preview=$('nf-snapshot-preview');if(preview){preview.hidden=true;preview.removeAttribute('src');}
    }
    async function close(){projection.dismiss?.();scopeEpoch++;clearContent();cancelCaptureJobs();try{await client.focusRequest('close');}catch(error){notice(error);}}
    $('nova-open').onclick=async()=>{try{await client.focusRequest('open',{renderTarget:$('nf-target').value});}catch(error){notice(error);}};
    $('nova-close').onclick=close;
    $('nova-pause').onclick=()=>{$('nova-pause').textContent=projection.togglePause()?'표시 계속':'표시 잠시 멈춤';};
    $('nova-replay').onclick=()=>projection.replay();
    $('nova-question-form').onsubmit=async event=>{
      event.preventDefault();const e=$('nova-question');
      if(!e.value.trim()||e.value.length>2000||e.disabled||!cacheScope)return;
      if(!pendingInput||pendingInput.text!==e.value)pendingInput={requestId:host.crypto.randomUUID(),text:e.value.trim()};
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
    $('nf-reload').onclick=()=>{settingsAttempts=0;catalogGeneration++;catalogAbort?.abort();catalogBusy=false;catalogLoaded=false;return loadSettings();};
    function snapshotErrorCode(raw){
      const text=String(raw||'');
      if(/notallowed|permission|denied/i.test(text))return 'permission_denied';
      if(/notfound|overconstrained|no_video_track|not_supported/i.test(text))return 'camera_unavailable';
      const code=text.toLowerCase().replace(/[^a-z0-9_]/g,'_').replace(/^_+|_+$/g,'').slice(0,40);
      return /^[a-z0-9_]{1,40}$/.test(code)?code:'snapshot_failed';
    }
    /** 확정 질문의 단발 명령은 claim→한 장 촬영→즉시 해제→결과 보고 순서로만 실행된다. */
    async function startSnapshotJob(command){
      const job={requestId:command.requestId,captureId:command.captureId,source:command.source,cancelled:false,capturing:false,abort:new AbortController()};
      captureJobs.set(command.captureId,job);
      const epoch=scopeEpoch,status=$('nova-snapshot-status'),deadline=Date.now()+Math.max(0,command.expiresInMs||0);
      const say=t=>{if(status)status.textContent=t;};
      // 취소·OFF·생산자 교체·폐기는 남은 단계를 모두 무효화한다.
      const stale=()=>job.cancelled||snapshotLocalBlocked||epoch!==scopeEpoch||disposed;
      const post=(route,body)=>client.focusRequest(route,body,{signal:job.abort.signal,timeoutMs:Math.max(0,Math.min(deadline-Date.now(),15000))});
      try{
        if(command.source==='META_GLASSES'){ // 동반 앱 어댑터 부재: 폴드 카메라 대체 없이 연결 필요 보고
          await post('snapshot/result',{requestId:job.requestId,captureId:job.captureId,error:'device_unavailable'});return;
        }
        if(!snapshotter){await post('snapshot/result',{requestId:job.requestId,captureId:job.captureId,error:'snapshot_unsupported'});return;}
        say('카메라를 준비합니다.');
        const claim=await post('snapshot/claim',{requestId:job.requestId,captureId:job.captureId});
        if(stale())return;
        // 서버가 승인한 최초 claim만 촬영한다. 합류/거부/만료 응답은 두 번째 촬영을 시작하지 않는다.
        if(!claim||claim.claimed!==true||claim.granted!==true)return;
        job.capturing=true;
        const result=await snapshotter.captureOnce({facingMode:'environment',timeoutMs:Math.max(0,Math.min(deadline-Date.now(),15000))});
        job.capturing=false;
        if(stale())return;
        if(!result.ok){await post('snapshot/result',{requestId:job.requestId,captureId:job.captureId,error:snapshotErrorCode(result.error)});return;}
        if(deadline-Date.now()<=0){await post('snapshot/result',{requestId:job.requestId,captureId:job.captureId,error:'snapshot_timeout'});return;} // 마감 이후 이미지는 올리지 않는다
        say('사진을 보내는 중.');
        job.image=result.base64;job.mediaType=result.mimeType;
        await post('snapshot/result',{requestId:job.requestId,captureId:job.captureId,imageBase64:job.image,imageMediaType:job.mediaType});
      }catch(error){
        // 업로드 응답 유실 추정: 네트워크/5xx 계열에서만, 마감 내·취소 전·같은 바이트로 한 번 재전송한다. 재촬영은 없다.
        const retryable=job.image&&!stale()&&deadline-Date.now()>0
          &&!(error?.status>=400&&error.status<500)&&error?.message!=='invalid_focus_action';
        if(retryable){try{await post('snapshot/result',{requestId:job.requestId,captureId:job.captureId,imageBase64:job.image,imageMediaType:job.mediaType});}catch{}}
      }finally{
        captureJobs.delete(command.captureId);if(job.capturing)snapshotter?.stop?.();say('');
      }
    }
    $('nova-settings-form').oninput=$('nova-settings-form').onchange=()=>{settingsEdits++;};
    $('nova-settings-form').onsubmit=async event=>{
      event.preventDefault();if(settingsBusy||!stored)return;settingsBusy=true;$('nf-save').disabled=true;
      const saveEpoch=scopeEpoch,saveEdits=settingsEdits;
      const snapEnabled=$('nf-snapshot-enabled'),snapSource=$('nf-snapshot-source');
      const prev=stored.settings.snapshot||{enabled:false,source:'FOLD_REAR'};
      const wantEnabled=!!(snapEnabled&&snapEnabled.checked);
      try{
        const settings={presentation:{}};
        const rawLength=String($('nf-answer-length')?.value??'').trim();
        if(!/^[0-9]+$/.test(rawLength)||!Number.isSafeInteger(Number(rawLength))||Number(rawLength)<80||Number(rawLength)>800)throw Error('invalid_nova_settings');
        for(const [key,id] of Object.entries(fields)){const e=$(id);if(!e)continue;settings[key]=flags.has(key)?!!e.checked:key==='wakeWord'||key==='answerInstruction'||key==='cameraWakeWord'?e.value:Number(e.value);}
        if($('nf-web-search')&&$('nf-web-search').value!=='')settings.webSearchEnabled=$('nf-web-search').value==='true';
        if($('nf-reasoning'))settings.reasoningPreset=$('nf-reasoning').value;
        if(['GENERAL','INTERVIEW','CUSTOM'].includes($('nf-answer-preset')?.value))settings.answerPreset=$('nf-answer-preset').value;
      for(const [key,id] of Object.entries(presentation)){const e=$(id);settings.presentation[key]=e.type==='checkbox'?e.checked:Number(e.value)*(key==='tailHoldMs'?1000:1);}
        // cameraAllowed=false는 사진 호출어·자동 촬영·시험 촬영 모두를 막는다. 구 페이지는 필드를 보내지 않아 서버 저장값을 유지한다.
        if(snapEnabled)settings.snapshot={enabled:wantEnabled,source:(snapSource&&snapSource.value)||'FOLD_REAR',cameraAllowed:$('nf-camera-allowed')?$('nf-camera-allowed').checked:null};
        if($('nf-camera-allowed')&&!$('nf-camera-allowed').checked)cancelCaptureJobs();
        if($('nf-answer-model')){
          const model=$('nf-answer-model').value;
          const exclusive=$('nf-answer-target').value==='GEMINI_WEBSEARCH_ONLY';
          const allowed=exclusive?fallbackPreference:$('nf-answer-fallback').checked;
          if(exclusive&&!model)throw Error('focus_search_model_required');
          const backups=modelInputs.slice(1).map(id=>$(id)?.value||'').filter(Boolean);
          if(allowed&&(new Set(backups).size!==backups.length||backups.includes(model)))throw Error('focus_model_selection_invalid');
          const keepBackups=exclusive||allowed||stored.settings.answerSelection?.routing?.executionTarget==='GEMINI_WEBSEARCH_ONLY';
          settings.answerSelection={mode:model?'FIXED':'AUTO',modelId:model||null,routing:{executionTarget:$('nf-answer-target').value,fallbackAllowed:allowed,allowedFallbackIds:keepBackups?backups:[]}};
        }
        if($('nf-recent-enabled'))settings.recentContext={enabled:$('nf-recent-enabled').checked,maxAgeSeconds:Number($('nf-recent-age').value),maxUtterances:Number($('nf-recent-count').value),tokenBudget:Number($('nf-recent-budget').value)};
        if($('nf-memory-mode'))settings.memory={mode:$('nf-memory-mode').value||null,graphMode:$('nf-graph-mode').value||'OFF',maxEvidence:Number($('nf-max-evidence').value),embeddingPrefer:$('nf-embed-prefer').value||'LOCAL_THEN_CLOUD',webOnUnknown:$('nf-web-unknown').value===''?null:$('nf-web-unknown').value==='true'};
        // OFF 의도는 서버 저장을 기다리지 않는다: 이 기기의 촬영·업로드를 먼저 중단한다.
        if(snapEnabled&&!wantEnabled){snapshotLocalBlocked=true;cancelCaptureJobs();}
        const value=await client.focusRequest('settings',{settingsVersion:stored.settingsVersion,settings});if(disposed||scopeEpoch!==saveEpoch)return;
        if(saveEdits===settingsEdits){paintSettings(value);syncLengthPreset();}else{stored=value;}
        $('nf-status').textContent=saveEdits===settingsEdits?'설정을 저장했습니다.':'설정을 저장했습니다. 새로 편집한 값은 다음 저장에 적용합니다.';
        // ON은 서버 저장 성공 뒤에만 해제한다. 저장된 장치 변경은 진행 중 촬영을 무효화하고 자동 재촬영은 없다.
        if(snapEnabled&&wantEnabled){snapshotLocalBlocked=false;if(settings.snapshot.source!==prev.source)cancelCaptureJobs();}
      }catch(error){
        if(disposed||scopeEpoch!==saveEpoch)return;
        notice(error);
        // 서버 저장 실패 시에도 로컬 OFF는 유지한다 — 사용자의 OFF 의도가 우선이다.
        if(snapEnabled&&!wantEnabled){snapshotLocalBlocked=true;$('nf-status').textContent='이 기기의 촬영은 중지했습니다. 서버 설정 저장은 실패했습니다.';}
      }finally{settingsBusy=false;$('nf-save').disabled=disposed||!stored;}
    };
    // 시험 촬영은 이 기기에서만 확인한다 — 서버 업로드·AI 호출 없이 한 장 찍고 즉시 해제한다.
    const testShot=$('nf-snapshot-test');
    if(testShot)testShot.onclick=async()=>{
      if(!snapshotter){notice({message:'snapshot_unsupported'});return;}
      if($('nf-camera-allowed')&&!$('nf-camera-allowed').checked){if($('nf-snapshot-test-status'))$('nf-snapshot-test-status').textContent='카메라 사용 허용이 꺼져 있습니다.';return;}
      const st=$('nf-snapshot-test-status'),img=$('nf-snapshot-preview');
      if(st)st.textContent='카메라를 준비합니다.';
      const r=await snapshotter.captureOnce({facingMode:'environment'});
      if(r.ok){
        if(img){img.src='data:'+r.mimeType+';base64,'+r.base64;img.hidden=false;}
        if(st)st.textContent='사진 '+r.width+'×'+r.height+' · 이 기기에서만 확인했습니다. AI에는 보내지 않았습니다.';
      }else if(st)st.textContent=messages[snapshotErrorCode(r.error)]||messages[r.error]||('촬영 실패: '+r.error);
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
    function clearMemoryDraft(){
      memoryDraft=null;$('nf-memory-text').value='';$('nf-memory-entities').value='';$('nf-memory-confirm').checked=false;
      $('nf-memory-type').value='USER_REPORTED';$('nf-memory-list').replaceChildren();
    }
    function memoryNotice(text){$('nf-memory-status').textContent=text;}
    async function memoryRead(){
      const epoch=scopeEpoch;
      try{
        const facts=await client.focusRequest('memory/read');if(epoch!==scopeEpoch||disposed)return;
        if(!Array.isArray(facts)||facts.length>256)throw Error('focus_contract');
        const list=$('nf-memory-list');list.replaceChildren();
        for(const fact of facts){
          const row=document.createElement('article'),title=document.createElement('p'),text=document.createElement('p');
          title.textContent='기억 '+fact.sourceId.slice(0,8)+' · 수정 '+fact.revision+' · '+({USER_REPORTED:'사용자 보고',HYPOTHESIS:'가설',ASSISTANT_GENERATED:'AI 생성 · 미검증'}[fact.assertionType]||'미검증');
          text.textContent=fact.text;
          const edit=document.createElement('button'),remove=document.createElement('button');edit.type=remove.type='button';
          edit.className=remove.className='focusable';edit.textContent='정정';remove.textContent='삭제';
          edit.onclick=()=>{memoryDraft={sourceId:fact.sourceId,expectedRevision:fact.revision};$('nf-memory-text').value=fact.text;$('nf-memory-entities').value=fact.entities.join(', ');$('nf-memory-type').value=fact.assertionType;$('nf-memory-confirm').checked=false;};
          remove.onclick=async()=>{if(memoryBusy||!stored)return;memoryBusy=true;
            try{await client.focusRequest('memory/delete',{sourceId:fact.sourceId,revision:fact.revision,consentRevision:stored.settingsVersion});
              if(epoch!==scopeEpoch||disposed)return;clearMemoryDraft();memoryNotice('이 기억을 이후 검색에서 제외했습니다.');await memoryRead();}
            catch{memoryNotice('삭제 상태를 확인하지 못했습니다. 목록을 다시 불러와 주세요.');}finally{memoryBusy=false;}};
          row.append(title,text,edit,remove);list.append(row);
        }
        if(!facts.length)list.textContent='직접 저장한 기억이 없습니다.';
      }catch{memoryNotice('목록을 불러오지 못했습니다. 현재 수음 기기와 연결을 확인해 주세요.');}
    }
    $('nf-memory-read').onclick=memoryRead;$('nf-memory-new').onclick=clearMemoryDraft;
    $('nf-memory-form').onsubmit=async event=>{
      event.preventDefault();if(memoryBusy||!stored)return;
      if(!stored.settings.rememberFactsEnabled){memoryNotice('위 설정에서 선택한 사실 저장을 켜고 저장해 주세요.');return;}
      if(!$('nf-memory-confirm').checked||!$('nf-memory-text').value.trim()){memoryNotice('저장할 내용과 동의 표시를 확인해 주세요.');return;}
      const epoch=scopeEpoch;
      if(!memoryDraft)memoryDraft={sourceId:host.crypto.randomUUID(),expectedRevision:0};
      const edit={...memoryDraft,consentRevision:stored.settingsVersion,text:$('nf-memory-text').value.trim(),
        entities:$('nf-memory-entities').value.split(',').map(x=>x.trim()).filter(Boolean),
        assertionType:$('nf-memory-type').value||'USER_REPORTED',confirmed:true};
      memoryBusy=true;$('nf-memory-save').disabled=true;
      try{await client.focusRequest('memory/save',{edit});if(epoch!==scopeEpoch||disposed)return;
        clearMemoryDraft();memoryNotice('선택한 사실을 저장했습니다. 대화 전체는 저장하지 않습니다.');await memoryRead();}
      catch{memoryNotice('저장을 확인하지 못했습니다. 내용과 최신 설정을 확인한 뒤 다시 시도해 주세요.');}
      finally{memoryBusy=false;$('nf-memory-save').disabled=false;}
    };
    function update(state){
      const owned=state.ready&&state.focusProducer===true;
      $('nova-open').disabled=!owned;$('nova-history-open').disabled=!owned;
      const key=state.assistId+':'+state.epoch;
      if(state.assistId&&Number.isSafeInteger(state.epoch)&&state.epoch>0&&projectionScope!==key){projection.reset?.();projectionScope=key;}
      if((owned&&loaded!==key)||(!owned&&loaded)){
        clearContent();clearMemoryDraft();wasActive=false;
        scopeEpoch++;cacheScope='';pendingInput=null;$('nova-question').value='';$('nova-question').disabled=true;$('nova-history-list').replaceChildren();$('nova-history').hidden=true;
        catalogGeneration++;catalogAbort?.abort();catalogBusy=false;catalogLoaded=false;catalogRows=[];
        loaded=owned?key:'';stored=null;settingsAttempts=0;$('nf-save').disabled=true;
      }
      if(!owned){snapshotLocalBlocked=false;cancelCaptureJobs();}
      if(owned&&!stored&&!loading)void loadSettings();
      // 확정 질문의 단발 촬영 명령은 생산자 폴링 응답으로만 도착한다. 이미 claim된 명령은
      // 이전 구현체의 작업일 수 있으므로 자동 재생 없이 0회로 둔다. 로컬 OFF 의도가 있으면 시작하지 않는다.
      // 사진 호출어(trigger==='camera_wake') 명령은 자동 촬영 OFF·로컬 OFF 의도와 무관하게 실행한다 — 카메라 전체 차단(cameraAllowed)만 막는다.
      const command=state.focusControl,cameraWake=command&&command.trigger==='camera_wake';
      if(command&&command.kind==='snapshot'&&command.captureId&&command.requestId&&owned&&command.expiresInMs>0
        &&(cameraWake||(!snapshotLocalBlocked&&(stored==null||stored.settings?.snapshot?.enabled!==false)))
        &&(stored?.settings?.snapshot?.cameraAllowed!==false)
        &&!captureJobs.has(command.captureId)&&!command.claimed)void startSnapshotJob(command);
      if(wasActive&&!state.focus?.active){scopeEpoch++;clearContent();}
      wasActive=!!state.focus?.active;
      projection.update(state.focus,state.ready&&state.connection==='READY',state.connection);
      if(state.focus?.reason&&messages[state.focus.reason])notice({message:state.focus.reason});
    }
    // 페이지가 숨겨지거나 닫히면 진행 중 촬영과 업로드를 취소한다. 돌아와도 자동 재촬영은 없다.
    const cancelOnHide=()=>{if(document.hidden)cancelCaptureJobs();};
    document.addEventListener('visibilitychange',projection.visibility);
    document.addEventListener('visibilitychange',cancelOnHide);
    host.addEventListener?.('pagehide',cancelCaptureJobs);
    return {update,close,active:projection.isActive,dispose:()=>{disposed=true;catalogGeneration++;catalogAbort?.abort();scopeEpoch++;clearContent();clearMemoryDraft();cancelCaptureJobs();document.removeEventListener('visibilitychange',cancelOnHide);host.removeEventListener?.('pagehide',cancelCaptureJobs);projection.dispose();}};
  }
  return {mount,createCache};
});
