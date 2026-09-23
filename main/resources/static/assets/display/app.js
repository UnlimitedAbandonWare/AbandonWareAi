(function(){
  'use strict';
  const $=id=>document.getElementById(id),core=window.DisplayCore,query=new URLSearchParams(location.search);
  const testChannel=query.get('clientRole')==='test'?(query.get('channel')||'test-'+crypto.randomUUID().replace(/-/g,'')):null;
  const standalone=document.body.hasAttribute('data-fold6-test')||new URLSearchParams(window.location.search).get('mode')==='phone-test';
  let phoneMode=standalone,pages=[],page=0,captionKey='',codeTimer=null,pendingAck=null,acked='',busy=false,voice,focusControls;
  const errorMessages={pair_code_invalid:'연결 번호가 만료됐거나 맞지 않습니다.',pair_same_browser:'서로 다른 기기에서 연결해 주세요.',link_exists:'기존 연결을 먼저 해제해 주세요.',pair_expired:'승인 시간이 지났습니다. 새 연결 번호를 만드세요.',link_pending:'안경에서 확인 번호를 승인해 주세요.',asr_capacity:'다른 수음이 진행 중입니다.',asr_disabled:'전사 서버가 준비되지 않았습니다.',asr_unavailable:'전사에 연결하지 못했습니다. 잠시 후 다시 시작해 주세요.',display_rate_limited:'요청 한도에 도달했습니다. 잠시 후 다시 시도하세요.'};
  Object.assign(errorMessages,{phone_test_disabled:'단독 전사 테스트가 서버에서 꺼져 있습니다.',phone_test_transcription_only:'단독 테스트에서는 전사만 표시합니다.',asr_budget_exhausted:'전사 테스트 예산에 도달했습니다. 새 연결을 시작하지 않았습니다.',asr_budget_unavailable:'전사 예산을 확인할 수 없습니다. 서버 상태를 확인해 주세요.',asr_budget_invalid:'전사 예산 설정을 확인해 주세요.',asr_budget_busy:'전사 예산 확인이 진행 중입니다. 잠시 후 다시 시도하세요.',asr_budget_ledger_limit:'전사 예산 기록을 확인해 주세요.'});
  Object.assign(errorMessages,{context_limit:'UTF-8 TXT 파일을 8,000자 이하로 선택해 주세요.',capture_active:'수음을 중지한 후 배경을 바꿔 주세요.',lens_seconds_range:'전사·힌트 유지와 자동 넘김은 1~100초 정수로 입력해 주세요. 자동 넘김만 0으로 끌 수 있습니다.',context_range:'참조 범위는 0 또는 5~300초, 글자는 0 또는 200~8192, 토큰은 0 또는 50~4096으로 입력해 주세요.'});
  function reportError(error){const code=error?.message||error?.code||'request_failed';$('error').textContent=(errorMessages[code]||'연결 상태를 확인하고 다시 시도해 주세요.');}
  function debug(){
    const out=$('debug-state');if(!out)return;const s=client.state,v=voice?.state||{},d=s.testStatus||{};
    out.textContent=JSON.stringify({connection:s.connection,microphone:v.permission,capture:v.phase,audioContext:v.audioContext,inputLevel:v.level,frames:v.frames,bytes:v.bytes,
      sttPausedReason:v.sttPausedReason||null,droppedAudioMs:v.droppedAudioMs||0,captureEvents:v.events||[],lastFrameAt:v.lastFrameAt||null,lastSendAt:v.lastSendAt||null,transcript:d.transcript||{},transcription:s.audioState,path:d.asr||'not_observed',hintsEnabled:s.hintsEnabled,processing:d.processing,relay:d.relay||'not_observed',reconnects:s.reconnects,segments:v.segments||0,lastTranscriptReceivedAt:d.lastTranscriptReceivedAt,lastAudioReceivedAt:d.lastAudioReceivedAt,
      displayRuntime:d.displayRuntime||'not_observed',audio:d.audio||'not_observed',asrUsage:d.asrUsage||'not_observed',pipeline:d.pipeline||'not_observed',lensDisplay:d.lensDisplay||'not_observed',roundTripMs:s.roundTripMs,processingMs:d.processingMs,backgroundChars:d.backgroundChars,
      error:v.errorCode||s.error?.code||null},null,2);
  }
  const settingsKey='awx.display.settings.'+(testChannel||'live');let settings={},settingsApplied=false;
  try{const saved=JSON.parse(localStorage.getItem(settingsKey)||'{}');if(saved&&typeof saved==='object')settings=saved;}catch{}
  function saveSetting(key,value){settings[key]=value;try{localStorage.setItem(settingsKey,JSON.stringify(settings));}catch{}}
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
    focusControls?.update(s);
    const phone=s.role==='PHONE'||phoneMode;
    $('app').classList.toggle('phone',phone);$('phone-controls').hidden=!phone;
    $('pair').hidden=phone||s.linked;$('phone-mode').hidden=phone||s.linked;$('unlink').hidden=!s.linked&&!s.linkPending;
    $('pair').disabled=!s.ready;
    if($('connect-lens'))$('connect-lens').disabled=!s.ready;
    $('reconnect').hidden=!standalone&&!['DISCONNECTED','RECONNECTING','PAUSED'].includes(s.connection);
    $('hints').textContent=s.hintsEnabled?'필요할 때만 자동 힌트':'자동 힌트 꺼짐';$('hints').setAttribute('aria-pressed',String(!!s.hintsEnabled));
    $('hints').hidden=false;$('hints').disabled=!s.ready;$('microphone').disabled=voice?.state.phase==='FINISHING'||!voice?.isActive()&&!canCapture(s);
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
        if(!lensPrefsSeen){lensPrefsSeen=true;const saved=settings.lensDisplay;const merged=saved&&typeof saved==='object'?{...ld,...saved}:ld;fillLensInputs(merged);fillContextInputs(merged);}
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
        if(typeof settings.hints==='boolean'&&settings.hints!==s.hintsEnabled)await client.hints(settings.hints);
        if(standalone&&Number.isFinite(settings.segment)&&settings.segment>=0&&settings.segment<=60)await client.relaySettings(s.testStatus?.relay?.enabled!==false,settings.segment);
        if(settings.lensDisplay&&typeof settings.lensDisplay==='object')await client.lensSettings(settings.lensDisplay,false);
      })().catch(reportError);
    }
    const lensReady=s.connection==='READY'&&s.ready&&(!standalone||s.testStatus?.relay?.eventOwner==='THIS DEVICE');
    const readyKey=s.assistId+':'+s.epoch;
    if(lensReady&&(!lensWasReady||readyKey!==lensReadyKey)){
      lensWasReady=true;lensReadyKey=readyKey;lensRestoreAttempts=0;clearTimeout(lensRestoreTimer);
      if(lensRequested||client.storedLensLink({includeExpired:true}))void restoreLensLink();
    }else if(!lensReady)lensWasReady=false;
    if(voice)debug();
    if(voice?.isActive()&&s.role==='PHONE'&&!s.linked&&voice.state.phase==='LISTENING')voice.stop('안경 표시 연결이 끊겨 수음을 중지했습니다.',true);
  }
  const client=window.DisplayConversate.createClient({transcription:true,standalone,testChannel,onChange:render});
  focusControls=window.NovaFocusControls?.mount({host:window,document,client});
  voice=window.DisplayVoice.createCapture({client,continuous:true,segmentSeconds:()=>standalone?(client.state.testStatus?.relay?.segmentSeconds??0):0,deviceId:()=>$('input-device').value,onChange(s){
    $('microphone').textContent=voice.isActive()?'즉시 수음 중지':'폴드6 수음 시작';$('microphone').setAttribute('aria-pressed',String(voice.isActive()));
    $('microphone').disabled=s.phase==='FINISHING'||!voice.isActive()&&!canCapture(client.state);
    $('finish').hidden=!voice.isActive();$('finish').disabled=!voice.isActive();
    $('microphone-status').textContent=s.permission==='denied'?'마이크 권한을 확인해 주세요.':({STARTING:'마이크 권한을 확인하고 있습니다.',LISTENING:'녹음 중 · 언제든 중지할 수 있습니다.',RECONNECTING:'연결을 다시 확인하고 있습니다.',FINISHING:'녹음을 중지하고 마지막 전사를 기다립니다.',OFF:'녹음 중지',ERROR:'수음이 중지됐습니다. 권한과 연결을 확인하고 다시 시작해 주세요.',STALLED:'수음이 중단됐습니다. 휴대폰을 확인해 주세요.'})[s.phase];
    if(standalone){$('microphone-status').textContent=s.message;$('input-level').hidden=!voice.isActive();$('input-level').value=s.level;}
    debug();
    if(s.deviceLabel)$('input-label').textContent='선택 장치: '+s.deviceLabel+' · 장치명은 내장 마이크 증명이 아닙니다. 가까이 말하기 비교로 확인하세요.';
    if(s.errorCode){if(s.permission==='denied')$('error').textContent='마이크 권한을 확인해 주세요.';else reportError({code:s.errorCode});}
  }});
  $('microphone').onclick=()=>{if(voice.isActive())voice.finish();else voice.start();};
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
  $('finish').onclick=act(()=>voice.finish());
  showLensLink(client.storedLensLink());
  if(typeof settings.device==='string'&&settings.device){$('input-device').add(new Option('저장된 입력 장치',settings.device));$('input-device').value=settings.device;}
  if(Number.isFinite(settings.segment)){$('segment-preset').value=['0','5','10','15'].includes(String(settings.segment))?String(settings.segment):'custom';$('segment-custom').value=settings.segment;$('segment-custom').hidden=$('segment-preset').value!=='custom';}
  if($('connect-lens'))$('connect-lens').onclick=act(()=>{lensRestoreAttempts=0;return restoreLensLink();});
  if($('copy-lens'))$('copy-lens').onclick=act(async()=>{await navigator.clipboard.writeText($('lens-address').value);$('lens-link-note').textContent='연결 주소를 복사했습니다. 안경에 이 주소를 등록하세요.';});
  $('pair').onclick=act(async()=>{const p=await client.pairingCode();if(!/^\d{6}$/.test(p.code)||!Number.isFinite(p.validForMs))throw Error('invalid_pair_response');$('pair-panel').hidden=false;$('pair-form').hidden=true;$('pair-message').textContent='연결 번호 '+p.code+' · 폴드6에서 같은 주소를 열고 ‘휴대폰에서 수음’을 선택하세요. 2분 동안 유효합니다.';clearTimeout(codeTimer);codeTimer=setTimeout(()=>{$('pair-message').textContent='연결 번호가 만료됐습니다. 휴대폰 연결을 다시 선택하세요.';},Math.min(120000,p.validForMs));$('pair-close').focus();});
  $('phone-mode').onclick=()=>{phoneMode=true;render(client.state);$('pair-panel').hidden=false;$('pair-form').hidden=false;$('pair-message').textContent='안경의 연결 번호를 입력해 주세요.';$('pair-code').focus();};
  $('pair-form').onsubmit=act(async()=>{await client.join($('pair-code').value);$('pair-code').value='';$('pair-form').hidden=true;});
  $('approve').onclick=act(()=>client.approve());
  $('pair-close').onclick=()=>{$('pair-panel').hidden=true;$('caption-card').focus();};
  $('unlink').onclick=act(async()=>{await voice.stop();await client.unlink();phoneMode=false;client.pause();client.start();});
  $('hints').onclick=act(async()=>{const enabled=!client.state.hintsEnabled;await client.hints(enabled);saveSetting('hints',enabled);});
  $('stop').onclick=act(async()=>{await voice.stop();if(!standalone&&client.state.linked)await client.stopAudio();});
  $('reconnect').hidden=false;$('reconnect').onclick=act(async()=>{await voice.stop();client.reconnect();});
  if(standalone&&$('broadcast')){
    if(testChannel)$('display-url').href='meta/index.html?clientRole=test&channel='+encodeURIComponent(testChannel);
    $('broadcast').onclick=act(()=>client.relaySettings(client.state.testStatus?.relay?.enabled===false,client.state.testStatus?.relay?.segmentSeconds??0));
    $('segment-preset').onchange=()=>{$('segment-custom').hidden=$('segment-preset').value!=='custom';};
    $('segment-apply').onclick=act(()=>{const seconds=Number($('segment-preset').value==='custom'?$('segment-custom').value:$('segment-preset').value);return client.relaySettings(client.state.testStatus?.relay?.enabled!==false,seconds).then(()=>saveSetting('segment',seconds));});
  }
  let lensPrefsSeen=false;
  function lensVal(id){const v=Number($(id).value);return Number.isFinite(v)?v:null;}
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
    $('ld-apply').onclick=act(async()=>{const patch=readLensPatch();await client.lensSettings(patch,false);saveSetting('lensDisplay',{...(settings.lensDisplay||{}),...patch});});
    $('ld-reset').onclick=act(async()=>{await client.lensSettings(null,true);saveSetting('lensDisplay',undefined);lensPrefsSeen=false;});
  }
  if($('hc-apply')){
    $('hc-apply').onclick=act(async()=>{const patch=readContextPatch();await client.lensSettings(patch,false);saveSetting('lensDisplay',{...(settings.lensDisplay||{}),...patch});});
    $('hc-reset').onclick=act(async()=>{await client.contextReset();$('hc-status').textContent='새 맥락 시작 · 이전 대화는 다음 힌트에 들어가지 않습니다. 수음과 전사는 계속됩니다.';});
  }
  $('refresh-devices').onclick=act(async()=>{if(!navigator.mediaDevices?.enumerateDevices)throw Error('microphone_device_missing');const items=await navigator.mediaDevices.enumerateDevices();const selected=$('input-device').value;$('input-device').replaceChildren(new Option('시스템 기본 입력',''));let n=0;for(const d of items)if(d.kind==='audioinput'&&d.deviceId)$('input-device').add(new Option(d.label||'입력 장치 '+(++n),d.deviceId));$('input-device').value=selected;});
  $('input-device').onchange=()=>{saveSetting('device',$('input-device').value);if(voice.isActive())voice.stop('장치를 바꿨습니다. 선택한 장치로 수음을 다시 시작하세요.');};
  navigator.mediaDevices?.addEventListener?.('devicechange',()=>{if(voice.isActive())voice.stop('입력 장치가 변경됐습니다. 장치를 확인하고 다시 시작하세요.',true);});
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
  window.addEventListener('online',()=>client.reconnect());
  // bfcache hide is not a user stop: remember an active capture and restart it on restore.
  let frozenCapture=false;
  window.addEventListener('pagehide',()=>{frozenCapture=voice.isActive();voice.stop();client.dispose();clearTimeout(codeTimer);clearTimeout(lensRestoreTimer);});
  window.addEventListener('pageshow',event=>{if(event.persisted){client.start();if(frozenCapture){frozenCapture=false;void voice.start();}}});
  $('caption-card').focus();client.start();
})();
