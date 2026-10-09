(function(){
  'use strict';
  const $=id=>document.getElementById(id),core=window.DisplayCore,query=new URLSearchParams(location.search);
  const testChannel=query.get('clientRole')==='test'?(query.get('channel')||'test-'+crypto.randomUUID().replace(/-/g,'')):null;
  const standalone=document.body.hasAttribute('data-fold6-test')||new URLSearchParams(window.location.search).get('mode')==='phone-test';
  let phoneMode=standalone,pages=[],page=0,captionKey='',codeTimer=null,pendingAck=null,acked='',busy=false,voice,focusControls;
  const resumeKey='awx.display.captureResume.'+(testChannel||'live');
  let resumeIntent=null,resumePending=false,resumeAllowed=true,resumeAttempt=0;
  try{
    const saved=JSON.parse(sessionStorage.getItem(resumeKey)||'null'),age=Date.now()-saved?.savedAt;
    if(standalone&&age>=0&&age<60000&&/^[a-f0-9-]{36}$/.test(saved?.assistId||'')&&/^[a-f0-9-]{36}$/.test(saved?.generation||''))resumeIntent=saved;
    else sessionStorage.removeItem(resumeKey);
  }catch{}
  function clearResumeIntent(){resumeIntent=null;resumePending=false;resumeAttempt++;try{sessionStorage.removeItem(resumeKey);}catch{}}
  function saveResumeIntent(){
    if(standalone&&voice?.isActive()&&!client.state.autoVoiceSettings?.modeEnabled&&client.state.assistId){
      try{sessionStorage.setItem(resumeKey,JSON.stringify({assistId:client.state.assistId,generation:crypto.randomUUID(),savedAt:Date.now()}));}catch{}
    }else clearResumeIntent();
  }
  async function resumeCaptureAfterReload(s){
    if(!resumeAllowed||!resumeIntent||!voice||!s.ready||s.connection!=='READY')return;
    if(s.assistId!==resumeIntent.assistId||s.autoVoiceSettings?.modeEnabled||Date.now()-resumeIntent.savedAt>=60000){clearResumeIntent();return;}
    if(!canCapture(s))return;
    clearResumeIntent();resumePending=true;
    $('microphone').textContent='폴드6 수음 재개';
    const attempt=resumeAttempt,assistId=s.assistId;
    let permission;try{permission=await navigator.permissions?.query({name:'microphone'});}catch{}
    if(!resumeAllowed||attempt!==resumeAttempt||voice.isActive()||client.state.assistId!==assistId||client.state.autoVoiceSettings?.modeEnabled||!canCapture(client.state))return;
    if(permission?.state==='granted'){
      const started=await voice.start(true);
      if(started)resumePending=false;
      else $('microphone').textContent='폴드6 수음 재개';
    }
  }
  let autoPrepared=false,autoScope='',autoSettingsKey='';
  function stopAutoVoice(){clearResumeIntent();autoPrepared=false;client.setAutoVoicePresentation(false);return voice?.stop('자동 음성 시작을 중지했습니다.');}
  function fillAutoVoice(s){
    const p=s.autoVoiceSettings,host=$('auto-voice-settings');if(!p||!host||!document.createElement)return;
    const scope=s.assistId;
    if(autoScope&&autoScope!==scope){autoPrepared=false;$('av-informed').checked=false;$('av-consent').checked=false;if(voice?.isActive())void voice.stop('새 세션에서 다시 준비해 주세요.');}
    autoScope=scope;
    const key=JSON.stringify(p);if(autoSettingsKey!==key){autoSettingsKey=key;
      $('av-mode').checked=p.modeEnabled;$('av-hints').checked=p.hintsEnabled;$('av-language').value=p.language;$('av-preset').value=p.preset;$('av-lines').value=p.hintLines;$('av-chars').value=p.hintChars;
      $('av-phrases').replaceChildren();for(const phrase of p.phrases||[])addAutoPhrase(phrase);
    }
    const phase=s.autoVoiceRuntime?.state||'DISARMED';
    $('av-status').textContent=phase+' · '+(p.hintsEnabled?'힌트 켜짐':'힌트 꺼짐 · 수음은 유지')+' · 최대 '+p.hintLines+'줄 / '+p.hintChars+'자';
    $('av-prepare').disabled=!p.modeEnabled||!canCapture(s)||!!voice?.isActive();
    if(autoPrepared&&phase==='DISARMED'&&voice?.state.phase==='LISTENING')void stopAutoVoice();
  }
  function addAutoPhrase(p={}){
    const row=document.createElement('div');row.dataset.phraseId=p.id||'p'+crypto.randomUUID().replace(/-/g,'');
    const language=document.createElement('select');for(const value of ['ko','en']){const option=document.createElement('option');option.value=value;option.textContent=value==='ko'?'한국어':'English';language.append(option);}language.value=p.language||$('av-language').value;
    const text=document.createElement('input');text.value=p.text||'';text.setAttribute('aria-label','등록 문구');text.autocomplete='off';
    const remove=document.createElement('button');remove.type='button';remove.textContent='삭제';remove.onclick=()=>row.remove();row.append(language,text,remove);$('av-phrases').append(row);
  }
  function readAutoVoice(){return {modeEnabled:$('av-mode').checked,hintsEnabled:$('av-hints').checked,language:$('av-language').value,preset:$('av-preset').value,hintLines:Number($('av-lines').value),hintChars:Number($('av-chars').value),phrases:Array.from($('av-phrases').children,row=>({id:row.dataset.phraseId,language:row.querySelector('select').value,text:row.querySelector('input').value}))};}
  const errorMessages={pair_code_invalid:'연결 번호가 만료됐거나 맞지 않습니다.',pair_same_browser:'서로 다른 기기에서 연결해 주세요.',link_exists:'기존 연결을 먼저 해제해 주세요.',pair_expired:'승인 시간이 지났습니다. 새 연결 번호를 만드세요.',link_pending:'안경에서 확인 번호를 승인해 주세요.',asr_capacity:'다른 수음이 진행 중입니다.',asr_disabled:'전사 서버가 준비되지 않았습니다.',asr_unavailable:'전사에 연결하지 못했습니다. 잠시 후 다시 시작해 주세요.',display_rate_limited:'요청 한도에 도달했습니다. 잠시 후 다시 시도하세요.'};
  Object.assign(errorMessages,{phone_test_disabled:'단독 전사 테스트가 서버에서 꺼져 있습니다.',phone_test_transcription_only:'단독 테스트에서는 전사만 표시합니다.',asr_budget_exhausted:'전사 테스트 예산에 도달했습니다. 새 연결을 시작하지 않았습니다.',asr_budget_unavailable:'전사 예산을 확인할 수 없습니다. 서버 상태를 확인해 주세요.',asr_budget_invalid:'전사 예산 설정을 확인해 주세요.',asr_budget_busy:'전사 예산 확인이 진행 중입니다. 잠시 후 다시 시도하세요.',asr_budget_ledger_limit:'전사 예산 기록을 확인해 주세요.'});
  Object.assign(errorMessages,{context_limit:'UTF-8 TXT 파일을 8,000자 이하로 선택해 주세요.',capture_active:'수음을 중지한 후 배경을 바꿔 주세요.',lens_seconds_range:'전사·힌트 유지와 자동 넘김은 1~100초 정수로 입력해 주세요. 자동 넘김만 0으로 끌 수 있습니다.',context_range:'참조 범위는 0 또는 5~300초, 글자는 0 또는 200~8192, 토큰은 0 또는 50~4096으로 입력해 주세요.'});
  Object.assign(errorMessages,{microphone_device_missing:'입력 장치를 찾을 수 없습니다. 연결·입력 설정에서 장치를 다시 확인해 주세요.',microphone_device_changed:'저장된 입력 장치가 바뀌었습니다. 기본 입력으로 다시 시도해 주세요.',microphone_device_busy:'다른 앱이 마이크를 사용 중입니다. 해당 앱을 닫고 다시 시작해 주세요.',microphone_device_ended:'마이크 연결이 종료됐습니다. 다시 시작해 주세요.',microphone_start_failed:'마이크를 시작하지 못했습니다. 다시 연결 후 수음을 시작해 주세요.',microphone_permission_required:'마이크 권한을 확인한 뒤 수음을 다시 시작해 주세요.',microphone_permission_revoked:'마이크 권한이 해제됐습니다. 사이트 권한을 확인해 주세요.',microphone_permission_timeout:'마이크 권한 응답이 없습니다. 권한을 확인하고 다시 시작해 주세요.',paired_phone_required:'휴대폰 연결이 필요합니다. 연결 후 다시 시작해 주세요.',assist_not_found:'서버 세션이 초기화됐습니다. 다시 연결 후 수음을 시작해 주세요.',event_owner_required:'다른 기기가 이벤트 권한을 가지고 있습니다. 다시 연결로 권한을 확인해 주세요.',display_audio_disabled:'서버에서 음성 수음이 꺼져 있습니다. 다시 연결 후 확인해 주세요.',display_http:'서버 요청이 거부됐습니다. 다시 연결해 주세요.',invalid_output_client:'출력 화면 권한이 없습니다. 다시 연결해 주세요.'});
  Object.assign(errorMessages,{auto_voice_consent_required:'이번 세션의 수음 안내와 동의를 확인한 뒤 준비해 주세요.',auto_voice_phrases_required:'선택한 언어의 문구를 등록하고 설정을 저장해 주세요.',auto_voice_reprepare_required:'수음이 중지되거나 세션이 바뀌었습니다. 다시 동의하고 준비해 주세요.',stt_language_unsupported:'영어는 Soniox만 지원합니다. 전사 엔진과 대체 경로를 확인해 주세요.'});
  Object.assign(errorMessages,{asr_timeout:'음성을 받지 못했어요. 전사 서버 응답이 지연됐습니다.',asr_no_fallback:'음성을 받지 못했어요. 대체 전사 경로가 없습니다.',asr_fallback_unavailable:'음성을 받지 못했어요. 대체 전사로 연결하지 못했습니다.',asr_stream_failed:'음성을 받지 못했어요. 전사 연결이 끊겼습니다.',asr_stream_ended:'음성을 받지 못했어요. 전사 연결이 끊겼습니다.',asr_input_unavailable:'음성을 받지 못했어요. 수음 입력을 확인해 주세요.',asr_processing_failed:'음성을 받지 못했어요. 전사 처리를 확인해 주세요.'});
  function reportError(error){const code=error?.message||error?.code||'request_failed',shown=/^[a-z_][a-z0-9_-]{0,63}$/.test(code)?code:'request_failed';$('error').textContent=(errorMessages[shown]||'연결 상태를 확인하고 다시 시도해 주세요.')+' (코드: '+shown+')';}
  function debug(){
    const out=$('debug-state');if(!out)return;const s=client.state,v=voice?.state||{},d=s.testStatus||{};
    out.textContent=JSON.stringify({connection:s.connection,microphone:v.permission,capture:v.phase,audioContext:v.audioContext,inputLevel:v.level,frames:v.frames,bytes:v.bytes,
      sttPausedReason:v.sttPausedReason||null,droppedAudioMs:v.droppedAudioMs||0,captureEvents:v.events||[],lastFrameAt:v.lastFrameAt||null,lastSendAt:v.lastSendAt||null,transcript:d.transcript||{},transcription:s.audioState,path:d.asr||'not_observed',hintsEnabled:s.hintsEnabled,processing:d.processing,relay:d.relay||'not_observed',reconnects:s.reconnects,segments:v.segments||0,lastTranscriptReceivedAt:d.lastTranscriptReceivedAt,lastAudioReceivedAt:d.lastAudioReceivedAt,
      focus:d.focus||'not_observed',displayRuntime:d.displayRuntime||'not_observed',audio:d.audio||'not_observed',asrUsage:d.asrUsage||'not_observed',pipeline:d.pipeline||'not_observed',lensDisplay:d.lensDisplay||'not_observed',roundTripMs:s.roundTripMs,processingMs:d.processingMs,backgroundChars:d.backgroundChars,
      error:v.errorCode||s.error?.code||null,errorStage:v.errorStage||null},null,2);
  }
  const settingsKey='awx.display.settings.'+(testChannel||'live');let settings={},settingsApplied=false;
  try{const saved=JSON.parse(localStorage.getItem(settingsKey)||'{}');if(saved&&typeof saved==='object')settings=saved;}catch{}
  function saveSetting(key,value){settings[key]=value;try{localStorage.setItem(settingsKey,JSON.stringify(settings));}catch{}}
  const sttEngine=$('stt-engine'),sttFallback=$('stt-fallback'),sttBackup=$('stt-backup'),sttApply=$('stt-apply');
  function validStt(value){return value&&['auto','local','soniox','deepgram'].includes(value.engine)&&typeof value.fallbackAllowed==='boolean'&&Array.isArray(value.allowedFallbacks)&&value.allowedFallbacks.length<=2&&new Set(value.allowedFallbacks).size===value.allowedFallbacks.length&&value.allowedFallbacks.every(x=>['soniox','deepgram'].includes(x))&&(value.fallbackAllowed||!value.allowedFallbacks.length)&&(value.engine!=='local'||!value.fallbackAllowed&&!value.allowedFallbacks.length);}
  if(settings.sttPolicy&&!validStt(settings.sttPolicy))delete settings.sttPolicy;
  if(sttEngine&&sttFallback&&sttBackup&&sttApply){
    const saved=settings.sttPolicy;sttEngine.value=saved?.engine||'';sttFallback.checked=!!saved?.fallbackAllowed;sttBackup.value=saved?.allowedFallbacks?.[0]||'';
    const refresh=()=>{const disabled=!sttEngine.value||sttEngine.value==='local';sttFallback.disabled=disabled;if(disabled)sttFallback.checked=false;sttBackup.disabled=disabled||!sttFallback.checked;};
    sttEngine.onchange=refresh;sttFallback.onchange=refresh;refresh();
    sttApply.onclick=()=>{const allowed=sttFallback.checked&&!sttFallback.disabled,backup=sttBackup.value;
      const policy=sttEngine.value?{engine:sttEngine.value,fallbackAllowed:allowed,allowedFallbacks:allowed&&backup?[backup]:[]}:null;
      if(policy&&!validStt(policy))return;saveSetting('sttPolicy',policy);
      $('stt-status').textContent='저장했습니다. 다음 수음 시작부터 적용되며 현재 수음과 재연결은 기존 선택을 유지합니다.';
    };
  }
  function showLensLink(link){
    if(!link||!/^[a-f0-9]{64}$/.test(link.token||''))return;
    const url=new URL('meta/index.html',location.href);url.hash='view='+link.token;
    $('display-url').href=url.href;$('display-url').textContent='저장된 안경 화면 열기';
    $('lens-address').value=url.href;$('lens-address').hidden=false;$('copy-lens').hidden=false;
    $('add-lens').href='fb-viewapp://web_app_deep_link?appName=AWX%20Caption&appUrl='+encodeURIComponent(url.href);
    $('add-lens').hidden=false;$('lens-link-note').textContent='안경에는 이 저장된 연결 주소를 등록하세요. 유효한 동안 같은 주소를 재사용합니다.';
  }
  let lensRestoreFlight=null,lensRestoreTimer=null,lensRestoreAttempts=0,lensReadyKey='',lensWasReady=false,lensRequested=false;
  function restoreLensLink(){
    lensRequested=true;if(lensRestoreFlight)return lensRestoreFlight;
    clearTimeout(lensRestoreTimer);lensRestoreAttempts++;
    lensRestoreFlight=client.lensLink().then(link=>{showLensLink(link);$('error').textContent='';}).catch(error=>{
      reportError(error);
      const transient=!error.status&&['display-timeout','Failed to fetch','network unavailable'].includes(error.message)||error instanceof TypeError||[409,429,500,502,503,504].includes(error.status);
      if(transient&&lensRestoreAttempts<3)lensRestoreTimer=setTimeout(()=>{
        if(client.state.connection==='READY'&&client.state.ready)void restoreLensLink();
      },2000*lensRestoreAttempts);
    }).finally(()=>{lensRestoreFlight=null;});
    return lensRestoreFlight;
  }
  function canCapture(s){return s.ready&&s.audioAvailable&&(!s.testStatus?.relay||s.testStatus.relay.eventOwner==='THIS DEVICE')&&(standalone?s.role==='STANDALONE':s.linked&&s.role==='PHONE');}
  function act(fn){return async event=>{event?.preventDefault();if(busy)return;busy=true;$('error').textContent='';try{await fn();}catch(error){reportError(error);}finally{busy=false;}};}
  function showCaption(){$('caption-text').textContent=pages[page]||'폴드6에서 수음을 시작하면 대화가 표시됩니다.';$('caption-page').textContent=pages.length>1?(page+1)+'/'+pages.length:'';}
  function render(s){
    fillAutoVoice(s);
    const focusFont=s.testStatus?.lensDisplay?.hintFontPx;
    if(standalone&&Number.isInteger(focusFont))$('nova-fold-answer')?.style.setProperty('--hint-font',focusFont+'px');
    focusControls?.update(s);
    const phone=s.role==='PHONE'||phoneMode;
    $('app').classList.toggle('phone',phone);$('phone-controls').hidden=!phone;
    $('pair').hidden=phone||s.linked;$('phone-mode').hidden=phone||s.linked;$('unlink').hidden=!s.linked&&!s.linkPending;
    $('pair').disabled=!s.ready;
    if($('connect-lens'))$('connect-lens').disabled=!s.ready;
    $('reconnect').hidden=!standalone&&!['DISCONNECTED','RECONNECTING','PAUSED'].includes(s.connection)&&!['WAITING','RECONNECTING'].includes(voice?.state.phase);
    $('hints').textContent=s.hintsEnabled?'필요할 때만 자동 힌트':'자동 힌트 꺼짐';$('hints').setAttribute('aria-pressed',String(!!s.hintsEnabled));
    $('hints').hidden=!!s.autoVoiceSettings?.modeEnabled;$('hints').disabled=!s.ready;$('microphone').disabled=voice?.state.phase==='FINISHING'||!voice?.isActive()&&!canCapture(s);
    $('connection').textContent=({READY:'서버 연결됨',PREPARING:'서버 연결 중',RECONNECTING:'재연결 중',DISCONNECTED:'표시 연결 끊김',PAIRING:'승인 대기',PAUSED:'표시 일시 중지'})[s.connection]||s.connection;
    $('link-status').textContent=standalone?'Fold6 컨트롤 · 음성 전사와 힌트를 안경 전용 화면으로 전달합니다.':s.linked?(phone?'안경과 연결됨 · 시작을 눌러 폴드6 수음':'폴드6 연결됨 · 음성 유입 대기'):s.linkPending?'휴대폰 연결 승인 대기':'첫 연결: 안경의 번호를 폴드6에 입력하세요.';
    if(s.linkPending){$('pair-panel').hidden=false;$('approve').hidden=phone;$('pair-message').textContent='확인 번호 '+(s.confirmation||'확인 중')+' · '+(phone?'안경에서 같은 번호를 승인해 주세요.':'휴대폰에도 같은 번호가 보이면 승인하세요.');}
    else if(s.linked){$('pair-panel').hidden=true;clearTimeout(codeTimer);}
    const c=s.caption,key=c?[s.epoch,c.utteranceId,c.revision,c.isFinal].join(':'):'';
    if(key!==captionKey){captionKey=key;pages=c?(c.rolling?[c.text]:core.paginate(c.text.replace(/\s+/g,' '),54)):[];page=Math.max(0,pages.length-1);showCaption();}
    $('caption-label').textContent=c?(c.isFinal?'확정 발화':'듣는 중'):voice?.isActive()||(s.linked||standalone)&&['READY','STREAMING','CAPTURING','FINISHING'].includes(s.audioState)?'전사 대기':'마이크 대기';
    $('hint-text').hidden=!s.hint;$('hint-text').textContent=s.hint?'힌트 · '+s.hint.text:'';
    if(s.error)reportError(s.error);else if(!voice?.state.errorCode)$('error').textContent='';
    if((!phone||standalone)&&c&&key!==acked&&key!==pendingAck&&document.visibilityState==='visible'){
      pendingAck=key;const version=s.version;
      requestAnimationFrame(()=>requestAnimationFrame(()=>{
        if(captionKey!==key||document.visibilityState!=='visible'){pendingAck=null;return;}
        client.acknowledge(version,'caption_rendered').then(()=>{acked=key;}).catch(()=>{}).finally(()=>{pendingAck=null;});
      }));
    }
    if(standalone&&$('owner-status')){
      const relay=s.testStatus?.relay,owned=relay?.eventOwner==='THIS DEVICE';
      $('owner-status').textContent='Event Owner: '+(relay?.eventOwner||'확인 중')+(relay&&!owned?' · 이 기기 READ ONLY':'');
      $('display-status').textContent='Display: '+(relay?.subscribers>0?'CONNECTED ('+relay.subscribers+')':'접속 대기')+' · 수신 ACK: '+(relay?.lastAckEventId||'미확인');
      $('broadcast').textContent=relay?.enabled===false?'Display 송출 꺼짐':'Display 송출 켜짐';$('broadcast').setAttribute('aria-pressed',String(relay?.enabled!==false));
      for(const id of ['broadcast','segment-apply','hints','context-file','clear-context','ld-apply','ld-reset','hc-apply','hc-reset'])$(id).disabled=!owned;
      $('segment-status').textContent=(relay?.segmentSeconds??0)===0?'연속 수음 · 발화가 끝나면 힌트 처리':relay.segmentSeconds+'초 단위 · 다음 수음부터 적용';
      if(voice?.isActive()&&relay&&!owned)void voice.stop('다른 Fold6가 이벤트 권한을 가져갔습니다. 이 기기는 읽기 전용입니다.');
      const ld=s.testStatus?.lensDisplay;
      if(ld&&$('ld-status')){
        const scope=s.testStatus?.lensSettingsScope||'';
        if(!lensPrefsSeen||lensPrefsScope!==scope){lensPrefsSeen=true;lensPrefsScope=scope;fillLensInputs(ld);fillContextInputs(ld);}
        $('ld-status').textContent='적용됨 · 전사 '+ld.transcriptFontPx+'px/'+ld.transcriptMaxLines+'줄·'+Math.round((ld.transcriptTtlMs??20000)/1000)+'초 · 힌트 '+ld.hintFontPx+'px/'+ld.hintPageLines+'줄·'+Math.round(ld.hintTtlMs/1000)+'초'+(ld.autoPageMs>0?'·자동 '+Math.round(ld.autoPageMs/1000)+'초':'·수동 넘김')+' · 조용 '+(ld.triggerQuietMs??2500)/1000+'초 · 쿨다운 '+(ld.cueCooldownMs??10000)/1000+'초 · 강제 '+(ld.forceAfterMs??180000)/1000+'초 · 목표 '+ld.hintTargetChars+'자';
        if($('hc-status')){
          const t=s.testStatus?.transcript||{},h=t.history||{},sel=t.lastContextSelection||{};
          $('hc-status').textContent='적용됨 · 이전 대화 '+(ld.historyEnabled!==false?'참고':'끔')+(ld.historyWindowMs>0?'·최근 '+Math.round(ld.historyWindowMs/1000)+'초':'·기본 범위')+(ld.historyMaxChars>0?'·최대 '+ld.historyMaxChars+'자':'')+(ld.historyMaxTokens>0?'·최대 '+ld.historyMaxTokens+'토큰':'')+(ld.topicResetEnabled===false?'':'·주제 전환 축소')+(sel.turns!=null?' · 직전 입력 '+sel.turns+'개/'+sel.chars+'자/'+sel.estTokens+'토큰':'')+(h.contextEpoch!=null?' · 맥락 '+h.contextEpoch:'');
        }
      }
    }
    if(s.ready&&!settingsApplied&&(!standalone||s.testStatus?.relay?.eventOwner==='THIS DEVICE')){
      settingsApplied=true;
      void (async()=>{
        if(!s.autoVoiceSettings?.modeEnabled&&typeof settings.hints==='boolean'&&settings.hints!==s.hintsEnabled)await client.hints(settings.hints);
        if(standalone&&Number.isFinite(settings.segment)&&settings.segment>=0&&settings.segment<=60)await client.relaySettings(s.testStatus?.relay?.enabled!==false,settings.segment);
        // Legacy owner-less lens cache is preserved locally for manual import only.
        // Server echoes are authoritative; reconnect never auto-posts cached lens preferences.
      })().catch(reportError);
    }
    const lensReady=s.connection==='READY'&&s.ready&&(!standalone||s.testStatus?.relay?.eventOwner==='THIS DEVICE');
    const readyKey=s.assistId+':'+s.epoch;
    if(lensReady&&(!lensWasReady||readyKey!==lensReadyKey)){
      lensWasReady=true;lensReadyKey=readyKey;lensRestoreAttempts=0;clearTimeout(lensRestoreTimer);
      if(lensRequested)void restoreLensLink();
    }else if(!lensReady)lensWasReady=false;
    if(voice){debug();void resumeCaptureAfterReload(s);}
    if(voice?.isActive()&&!s.autoVoiceSettings?.modeEnabled&&s.role==='PHONE'&&!s.linked&&voice.state.phase==='LISTENING')voice.stop('안경 표시 연결이 끊겨 수음을 중지했습니다.',true);
  }
  const client=window.DisplayConversate.createClient({transcription:true,standalone,testChannel,onChange:render});
  focusControls=window.NovaFocusControls?.mount({host:window,document,client});
  voice=window.DisplayVoice.createCapture({client,continuous:true,autoVoiceConsent:()=>autoPrepared,sttPolicy:()=>settings.sttPolicy||null,segmentSeconds:()=>standalone?(client.state.testStatus?.relay?.segmentSeconds??0):0,deviceId:()=>$('input-device').value,onDeviceFallback:()=>{saveSetting('device',undefined);$('input-device').value='';$('input-label').textContent='저장된 입력 장치를 찾을 수 없어 시스템 기본 입력으로 시작했습니다.';},onChange(s){
    $('microphone').textContent=voice.isActive()?'즉시 수음 중지':resumePending?'폴드6 수음 재개':'폴드6 수음 시작';$('microphone').setAttribute('aria-pressed',String(voice.isActive()));
    $('microphone').disabled=s.phase==='FINISHING'||!voice.isActive()&&!canCapture(client.state);
    $('finish').hidden=!voice.isActive();$('finish').disabled=!voice.isActive();
    $('microphone-status').textContent=s.permission==='denied'?'마이크 권한을 확인해 주세요.':({STARTING:'마이크 권한을 확인하고 있습니다.',LISTENING:'녹음 중 · 언제든 중지할 수 있습니다.',RECONNECTING:'연결을 다시 확인하고 있습니다.',FINISHING:'녹음을 중지하고 마지막 전사를 기다립니다.',OFF:'녹음 중지',ERROR:'수음이 중지됐습니다. 권한과 연결을 확인하고 다시 시작해 주세요.',STALLED:'수음이 중단됐습니다. 휴대폰을 확인해 주세요.'})[s.phase];
    if(standalone){$('microphone-status').textContent=s.message+(s.phase==='ERROR'&&/^[a-z_][a-z0-9_-]{0,63}$/.test(s.errorCode||'')?' (코드: '+s.errorCode+')':'');$('input-level').hidden=!voice.isActive();$('input-level').value=s.level;}
    debug();
    if(s.deviceLabel)$('input-label').textContent='선택 장치: '+s.deviceLabel+' · 장치명은 내장 마이크 증명이 아닙니다. 가까이 말하기 비교로 확인하세요.';
    if(s.errorCode){if(s.permission==='denied')$('error').textContent='마이크 권한을 확인해 주세요.';else reportError({code:s.errorCode});}
  }});
  $('microphone').onclick=()=>{const continuation=resumePending;clearResumeIntent();if(voice.isActive()){if(client.state.autoVoiceSettings?.modeEnabled)void stopAutoVoice();else voice.finish();}else if(client.state.autoVoiceSettings?.modeEnabled)$('av-prepare').click();else voice.start(continuation);};
  if($('auto-voice-settings')&&document.createElement){
    $('av-add').onclick=()=>addAutoPhrase();
    $('av-aliases').onclick=()=>{addAutoPhrase({language:'ko',text:'노바'});addAutoPhrase({language:'en',text:'Nova'});};
    $('av-save').onclick=act(async()=>{const next=readAutoVoice(),old=client.state.autoVoiceSettings;
      if(old&&(next.modeEnabled!==old.modeEnabled||next.language!==old.language||next.preset!==old.preset||JSON.stringify(next.phrases)!==JSON.stringify(old.phrases)))await stopAutoVoice();
      client.setAutoVoicePresentation(next.hintsEnabled);await client.lensSettings({autoVoiceTrigger:next},false);
    });
    $('av-hints').onchange=act(async()=>{const enabled=$('av-hints').checked;client.setAutoVoicePresentation(enabled);await client.lensSettings({autoVoiceTrigger:{hintsEnabled:enabled}},false);});
    $('av-mode').onchange=act(async()=>{if(!$('av-mode').checked){await stopAutoVoice();await client.lensSettings({autoVoiceTrigger:{modeEnabled:false}},false);}});
    $('av-reset').onclick=act(async()=>{await stopAutoVoice();await client.lensSettings({autoVoiceTrigger:{restoreDefaults:true}},false);});
    $('av-prepare').onclick=act(async()=>{
      const p=client.state.autoVoiceSettings;if(!p?.modeEnabled||!p.phrases?.some(x=>x.language===p.language))throw Error('auto_voice_phrases_required');
      if(!$('av-informed').checked||!$('av-consent').checked)throw Error('auto_voice_consent_required');
      const policy=settings.sttPolicy;if(p.language==='en'&&(policy?.engine!=='soniox'||policy.allowedFallbacks?.some(x=>x!=='soniox')))throw Error('stt_language_unsupported');
      autoPrepared=true;client.setAutoVoicePresentation(p.hintsEnabled);await voice.start();if(!voice.isActive())autoPrepared=false;
    });
    $('av-stop').onclick=()=>{void stopAutoVoice();};
    $('av-test').onclick=()=>{const p=readAutoVoice(),normalize=x=>String(x).normalize('NFC').trim().replace(/\s+/gu,' ').toLocaleLowerCase('und'),text=normalize($('av-test-input').value);
      const match=p.phrases.filter(x=>x.language===p.language&&normalize(x.text)&&text.includes(normalize(x.text))).sort((a,b)=>Array.from(normalize(b.text)).length-Array.from(normalize(a.text)).length)[0];
      $('av-test-result').textContent=match?'일치 · '+match.text:'일치하는 문구 없음';
    };
  }
  if($('context-file')){
    $('context-file').onchange=act(async()=>{
      const file=$('context-file').files?.[0];if(!file)return;
      if(file.size>32000||!file.name.toLowerCase().endsWith('.txt')){throw Error('context_limit');}
      const text=new TextDecoder('utf-8',{fatal:true}).decode(await file.arrayBuffer());
      if(text.length>8000)throw Error('context_limit');
      if(voice.isActive())await voice.finish();
      await client.background(text);$('context-status').textContent='배경 TXT 준비됨 · '+text.length+'자 · 힌트 켜기를 선택하면 사용합니다.';
      $('context-file').value='';
    });
    $('clear-context').onclick=act(async()=>{if(voice.isActive())await voice.finish();await client.background('');$('context-status').textContent='배경을 지웠습니다.';});
  }
  $('finish').onclick=act(()=>{clearResumeIntent();return voice.finish();});
  showLensLink(client.storedLensLink());
  const preview=$('lens-preview'),previewFrame=$('lens-preview-frame'),previewViewport=$('lens-preview-viewport');
  const previewKey='awx.display.lensPreview.'+(testChannel||'live');let previewObserver=null;
  function scaleLensPreview(){
    if(!previewViewport||previewViewport.hidden)return;
    const size=Math.min(600,previewViewport.clientWidth);
    previewFrame.style.transform='scale('+(size/600)+')';previewViewport.style.height=size+'px';
  }
  function closeLensPreview(){
    if(!previewFrame)return;
    previewObserver?.disconnect();previewObserver=null;
    previewFrame.src='about:blank';previewViewport.hidden=true;
  }
  function lensPreviewToken(value){
    const input=(value||'').trim();if(/^[a-f0-9]{64}$/.test(input))return input;
    try{
      const view=new URLSearchParams(new URL(input,location.href).hash.slice(1)).get('view')||'';
      return /^[a-f0-9]{64}$/.test(view)?view:'';
    }catch{return '';}
  }
  function savedLensPreview(){
    try{const saved=JSON.parse(localStorage.getItem(previewKey)||'null');if(/^[a-f0-9]{64}$/.test(saved?.token||''))return saved.token;}catch{}
    return '';
  }
  function showSavedLensPreview(){
    const savedPreview=savedLensPreview();$('lens-preview-address').placeholder=savedPreview?'저장됨 · …'+savedPreview.slice(-6):'안경 연결 주소 또는 토큰';
    if(!preview.open)$('lens-preview-note').textContent=savedPreview?$('lens-preview-address').placeholder:'';
  }
  function forgetLensPreview(note){
    try{localStorage.removeItem(previewKey);}catch{}
    closeLensPreview();preview.open=false;$('lens-preview-address').value='';showSavedLensPreview();
    $('lens-preview-tab').hidden=true;$('lens-preview-tab').removeAttribute('href');$('lens-preview-note').textContent=note;
  }
  if(preview&&previewFrame&&previewViewport){
    showSavedLensPreview();
    previewFrame.onload=()=>{
      previewObserver?.disconnect();previewObserver=null;if(previewFrame.src==='about:blank')return;
      try{
        const doc=previewFrame.contentDocument,status=doc?.getElementById('status');if(!status)return;
        const check=()=>{if(previewFrame.contentDocument===doc&&/^Lens link (invalid|expired|denied)\./.test(status.textContent||''))forgetLensPreview('주소를 다시 붙여넣으세요');};
        if(typeof MutationObserver==='function'){previewObserver=new MutationObserver(check);previewObserver.observe(status,{childList:true,subtree:true,characterData:true});}check();
      }catch{}
    };
    $('lens-preview-open').onclick=()=>{
      const input=$('lens-preview-address').value.trim(),stored=client.storedLensLink()?.token||'';
      const candidate=input||(/^[a-f0-9]{64}$/.test(stored)?stored:savedLensPreview());
      const token=lensPreviewToken(candidate);let url;
      if(standalone&&testChannel){
        url=new URL('meta/index.html',location.href);url.search=new URLSearchParams({clientRole:'test',channel:testChannel,preview:'1'}).toString();
      }else{
        if(!/^[a-f0-9]{64}$/.test(token)){
          closeLensPreview();$('lens-preview-tab').hidden=true;$('lens-preview-tab').removeAttribute('href');
          $('lens-preview-note').textContent='먼저 안경 연결 주소 만들기를 누르세요';return;
        }
        url=new URL('meta/index.html',location.href);url.hash='view='+token+'&preview=1';
      }
      if(token){try{localStorage.setItem(previewKey,JSON.stringify({token,savedAt:Date.now()}));}catch{}showSavedLensPreview();}
      $('lens-preview-address').value='';$('lens-preview-note').textContent=token?'안경 화면 · …'+token.slice(-6):'테스트 안경 화면';
      $('lens-preview-tab').href=url.href;$('lens-preview-tab').hidden=false;
      preview.open=true;previewViewport.hidden=false;previewFrame.src=url.href;scaleLensPreview();
    };
    $('lens-preview-close').onclick=()=>{closeLensPreview();preview.open=false;};
    if($('lens-preview-clear'))$('lens-preview-clear').onclick=()=>forgetLensPreview('저장된 주소를 지웠습니다.');
    preview.addEventListener('toggle',()=>{if(!preview.open)closeLensPreview();});
    if(window.ResizeObserver)new window.ResizeObserver(scaleLensPreview).observe(previewViewport);
    window.addEventListener('resize',scaleLensPreview);window.addEventListener('pagehide',closeLensPreview);
  }
  if(typeof settings.device==='string'&&settings.device){$('input-device').add(new Option('저장된 입력 장치',settings.device));$('input-device').value=settings.device;}
  if(Number.isFinite(settings.segment)){$('segment-preset').value=['0','5','10','15'].includes(String(settings.segment))?String(settings.segment):'custom';$('segment-custom').value=settings.segment;$('segment-custom').hidden=$('segment-preset').value!=='custom';}
  if($('connect-lens'))$('connect-lens').onclick=act(()=>{lensRestoreAttempts=0;return restoreLensLink();});
  if($('copy-lens'))$('copy-lens').onclick=act(async()=>{await navigator.clipboard.writeText($('lens-address').value);$('lens-link-note').textContent='연결 주소를 복사했습니다. 안경에 이 주소를 등록하세요.';});
  $('pair').onclick=act(async()=>{const p=await client.pairingCode();if(!/^\d{6}$/.test(p.code)||!Number.isFinite(p.validForMs))throw Error('invalid_pair_response');$('pair-panel').hidden=false;$('pair-form').hidden=true;$('pair-message').textContent='연결 번호 '+p.code+' · 폴드6에서 같은 주소를 열고 ‘휴대폰에서 수음’을 선택하세요. 2분 동안 유효합니다.';clearTimeout(codeTimer);codeTimer=setTimeout(()=>{$('pair-message').textContent='연결 번호가 만료됐습니다. 휴대폰 연결을 다시 선택하세요.';},Math.min(120000,p.validForMs));$('pair-close').focus();});
  $('phone-mode').onclick=()=>{phoneMode=true;render(client.state);$('pair-panel').hidden=false;$('pair-form').hidden=false;$('pair-message').textContent='안경의 연결 번호를 입력해 주세요.';$('pair-code').focus();};
  $('pair-form').onsubmit=act(async()=>{await client.join($('pair-code').value);$('pair-code').value='';$('pair-form').hidden=true;});
  $('approve').onclick=act(()=>client.approve());
  $('pair-close').onclick=()=>{$('pair-panel').hidden=true;$('caption-card').focus();};
  $('unlink').onclick=act(async()=>{clearResumeIntent();await voice.stop();await client.unlink();phoneMode=false;client.pause();client.start();});
  $('hints').onclick=act(async()=>{const enabled=!client.state.hintsEnabled;await client.hints(enabled);saveSetting('hints',enabled);});
  $('stop').onclick=act(async()=>{clearResumeIntent();await voice.stop();if(!standalone&&client.state.linked)await client.stopAudio();});
  $('reconnect').hidden=false;$('reconnect').onclick=act(async()=>{await voice.reconnect();});
  if(standalone&&$('broadcast')){
    if(testChannel)$('display-url').href='meta/index.html?clientRole=test&channel='+encodeURIComponent(testChannel);
    $('broadcast').onclick=act(()=>client.relaySettings(client.state.testStatus?.relay?.enabled===false,client.state.testStatus?.relay?.segmentSeconds??0));
    $('segment-preset').onchange=()=>{$('segment-custom').hidden=$('segment-preset').value!=='custom';};
    $('segment-apply').onclick=act(()=>{const seconds=Number($('segment-preset').value==='custom'?$('segment-custom').value:$('segment-preset').value);return client.relaySettings(client.state.testStatus?.relay?.enabled!==false,seconds).then(()=>saveSetting('segment',seconds));});
  }
  let lensPrefsSeen=false,lensPrefsScope='';
  function lensVal(id){const raw=String($(id).value??'').trim();if(!raw||!/^[0-9]+$/.test(raw))throw Error('invalid_lens_settings');const v=Number(raw);if(!Number.isSafeInteger(v))throw Error('invalid_lens_settings');return v;}
  function fontPreview(){const value=String($('ld-hint-font')?.value??'');if(!/^[0-9]+$/.test(value)||Number(value)<20||Number(value)>36)return;
    if($('ld-hint-font-range'))$('ld-hint-font-range').value=value;if($('ld-font-preview'))$('ld-font-preview').style.fontSize=value+'px';}
  if($('ld-hint-font'))$('ld-hint-font').oninput=fontPreview;
  if($('ld-hint-font-range'))$('ld-hint-font-range').oninput=()=>{$('ld-hint-font').value=$('ld-hint-font-range').value;fontPreview();};
  function lensSeconds(id,allowOff,min,max){const raw=(($(id)&&$(id).value)||'').trim();if(!raw)return null;const v=Number(raw);const lo=min??0,hi=max??100;if(!Number.isFinite(v)||v<lo||v>hi||(!allowOff&&v===0))throw Error('lens_seconds_range');if(id!=='ld-quiet'&&!Number.isInteger(v))throw Error('lens_seconds_range');return v;}
  function readLensPatch(){
    const p={},font=lensVal('ld-cap-font'),hint=lensVal('ld-hint-font'),cap=lensVal('ld-cap-lines'),lines=lensVal('ld-hint-lines'),capTtl=lensSeconds('ld-cap-ttl'),ttl=lensSeconds('ld-hint-ttl'),auto=lensSeconds('ld-auto-page',true),chars=lensVal('ld-hint-chars');
    const quiet=lensSeconds('ld-quiet',false,1,30),cool=lensSeconds('ld-cooldown',false,1,120),force=lensSeconds('ld-force',false,1,600);
    if(font!=null)p.transcriptFontPx=font;if(hint!=null)p.hintFontPx=hint;if(cap!=null)p.transcriptMaxLines=cap;if(lines!=null)p.hintPageLines=lines;
    if(capTtl!=null)p.transcriptTtlMs=capTtl*1000;if(ttl!=null)p.hintTtlMs=ttl*1000;if(auto!=null)p.autoPageMs=auto*1000;if(chars!=null)p.hintTargetChars=chars;
    if(quiet!=null)p.triggerQuietMs=Math.round(quiet*1000);if(cool!=null)p.cueCooldownMs=cool*1000;if(force!=null)p.forceAfterMs=force*1000;
    return p;
  }
  function fillLensInputs(d){
    if(!$('ld-apply')||!d)return;
    $('ld-cap-font').value=d.transcriptFontPx??26;$('ld-hint-font').value=d.hintFontPx??26;
    $('ld-cap-lines').value=d.transcriptMaxLines??4;$('ld-cap-ttl').value=(d.transcriptTtlMs??20000)/1000;$('ld-hint-lines').value=d.hintPageLines??11;
    $('ld-hint-ttl').value=(d.hintTtlMs??20000)/1000;$('ld-auto-page').value=(d.autoPageMs??5000)/1000;
    if($('ld-quiet'))$('ld-quiet').value=(d.triggerQuietMs??2500)/1000;
    if($('ld-cooldown'))$('ld-cooldown').value=(d.cueCooldownMs??10000)/1000;
    if($('ld-force'))$('ld-force').value=(d.forceAfterMs??180000)/1000;
    $('ld-hint-chars').value=d.hintTargetChars??1000;
    fontPreview();
  }
  function readContextPatch(){
    const p={historyEnabled:$('hc-history').checked,topicResetEnabled:$('hc-topic').checked};
    const window=lensVal('hc-window'),chars=lensVal('hc-chars'),tokens=lensVal('hc-tokens');
    if(window!=null){if(window!==0&&(window<5||window>300))throw Error('context_range');p.historyWindowMs=window*1000;}
    if(chars!=null){if(chars!==0&&(chars<200||chars>8192))throw Error('context_range');p.historyMaxChars=chars;}
    if(tokens!=null){if(tokens!==0&&(tokens<50||tokens>4096))throw Error('context_range');p.historyMaxTokens=tokens;}
    return p;
  }
  function fillContextInputs(d){
    if(!$('hc-apply')||!d)return;
    $('hc-history').checked=d.historyEnabled!==false;$('hc-topic').checked=d.topicResetEnabled!==false;
    $('hc-window').value=(d.historyWindowMs??0)/1000;$('hc-chars').value=d.historyMaxChars??0;$('hc-tokens').value=d.historyMaxTokens??0;
  }
  if($('ld-apply')){
    $('ld-apply').onclick=act(async()=>{const patch=readLensPatch(),res=await client.lensSettings(patch,false);const applied=res?.testStatus?.lensDisplay;if(applied){fillLensInputs(applied);fontPreview();}});
    if($('ld-hint-font-reset'))$('ld-hint-font-reset').onclick=act(async()=>{const res=await client.lensSettings({hintFontPx:26},false);const applied=res?.testStatus?.lensDisplay;if(applied){fillLensInputs(applied);fontPreview();}});
    $('ld-reset').onclick=act(async()=>{await client.lensSettings(null,true);saveSetting('lensDisplay',undefined);lensPrefsSeen=false;});
    if($('ld-preset-apply'))$('ld-preset-apply').onclick=act(async()=>{
      const preset=$('ld-preset')?.value;if(!preset)return;
      const res=await client.lensSettings({preset},false);
      const applied=res?.testStatus?.lensDisplay;
      if(applied&&typeof applied==='object'){fillLensInputs(applied);saveSetting('lensDisplay',applied);}
      else saveSetting('lensDisplay',{preset});
    });
  }
  if($('hc-apply')){
    $('hc-apply').onclick=act(async()=>{const patch=readContextPatch();await client.lensSettings(patch,false);saveSetting('lensDisplay',{...(settings.lensDisplay||{}),...patch});});
    $('hc-reset').onclick=act(async()=>{await client.contextReset();$('hc-status').textContent='새 맥락 시작 · 이전 대화는 다음 힌트에 들어가지 않습니다. 수음과 전사는 계속됩니다.';});
  }
  $('refresh-devices').onclick=act(async()=>{if(!navigator.mediaDevices?.enumerateDevices)throw Error('microphone_device_missing');const items=await navigator.mediaDevices.enumerateDevices();const selected=$('input-device').value;$('input-device').replaceChildren(new Option('시스템 기본 입력',''));let n=0;for(const d of items)if(d.kind==='audioinput'&&d.deviceId)$('input-device').add(new Option(d.label||'입력 장치 '+(++n),d.deviceId));$('input-device').value=selected;});
  $('input-device').onchange=()=>{saveSetting('device',$('input-device').value);if(voice.isActive())voice.stop('장치를 바꿨습니다. 선택한 장치로 수음을 다시 시작하세요.');};
  navigator.mediaDevices?.addEventListener?.('devicechange',()=>{void voice.deviceChanged();});
  document.addEventListener('keydown',event=>{
    if(event.key==='Escape'){event.preventDefault();if(focusControls?.active()){void focusControls.close();return;}$('pair-panel').hidden=true;$('stop').click();return;}
    if(['INPUT','SELECT'].includes(event.target.tagName))return;
    if(event.target===$('caption-card')&&['ArrowLeft','ArrowRight'].includes(event.key)&&pages.length){event.preventDefault();page=Math.min(pages.length-1,Math.max(0,page+(event.key==='ArrowRight'?1:-1)));showCaption();return;}
    if(event.key.startsWith('Arrow')){event.preventDefault();const scope=$('pair-panel').hidden?document:$('pair-panel');const controls=[...scope.querySelectorAll('.focusable')].filter(e=>!e.disabled&&e.getClientRects().length);const i=controls.indexOf(document.activeElement),step=['ArrowUp','ArrowLeft'].includes(event.key)?-1:1;controls[(i+step+controls.length)%controls.length]?.focus();}
  });
  document.addEventListener('visibilitychange',()=>{
    if(document.hidden){if(!standalone&&client.state.role!=='PHONE')client.pause();if(voice.isActive())$('microphone-status').textContent='수음 중에는 휴대폰 화면을 유지해 주세요. 일시 중단되면 입력을 기다렸다가 이어갑니다.';}
    else{client.pause();client.start();if(voice.isActive())void voice.resume();}
  });
  window.addEventListener('online',()=>{void voice.reconnect();});
  // Only a fresh document can resume manual capture; bfcache restores the connection alone.
  window.addEventListener('pagehide',()=>{saveResumeIntent();resumeAllowed=false;resumeAttempt++;voice.stop();client.dispose();clearTimeout(codeTimer);clearTimeout(lensRestoreTimer);});
  window.addEventListener('pageshow',event=>{if(event.persisted){resumeAllowed=false;client.start();}});
  $('caption-card').focus();client.start();
})();
