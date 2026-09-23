/* Session, content and credentials stay in memory. No storage, caches, service workers or transcript logging. */
(() => {
  'use strict';
  const $=id=>document.getElementById(id);
  let session=null,stream=null,bootstrap=null,expiryTimer=null,hidden=false,stopped=false,reconnectTimer=null;
  let microphone=null,selection=null,controlPending=null,utterancePending=null,startPending=null,textFallbackPending=null,lastTextFallbackKey=null;
  let lifecycle=0,initializing=null,initializedLifecycle=-1,captureGeneration=0;
  let reconnectAttempts=0,openedAt=0,ackPending=false,lastAck=null;
  let lastDiagnosticAt=0,diagnosticGeneration=0;
  let pinned=false,shown=null,cardPages=[],cardPage=0,lastRendered=null,evidenceShown=false,lastErrorPaintAt=-Infinity;
  const live={frontBuild:'wear-20260915-1',serverBuild:'미관측',inputPath:'direct',requestId:'미관측',api:'idle',httpStatus:'미관측',roundTripMs:'미관측',errors:0,error:'none',feedback:'없음'};
  const safeId=value=>typeof value==='string'&&/^[A-Za-z0-9._:-]{1,128}$/.test(value)?value:'미관측';
  const monotonic=()=>performance.now();
  function paintDiagnostics(){try{const d=session?.diagnostics||{},a=session?.audio||{};
    $('liveDiagnostics').textContent=`프론트 ${live.frontBuild} · 서버 ${live.serverBuild}\n입력 ${safeId(d.inputPath||live.inputPath)} · 요청 ${safeId(d.requestId||live.requestId)} · ${live.api} / HTTP ${live.httpStatus} · 브라우저 왕복 ${live.roundTripMs}ms\n확정 처리 ${safeId(d.inputDecision)} · 서버 시각 ${d.lastFinalAt?new Date(d.lastFinalAt).toISOString():'미관측'} · 서버 단조 경과 ${measured(d.sinceFinalMs)}ms\n오디오 청크 ${measured(a.chunks)} · 출력 ${stream?'연결 요청됨':'미연결'} · cue ${safeId(d.cueId)} · 수신 ${measured(session?.metrics?.outputAcks)} · 화면 반영 신고 ${measured(d.renderedCount)} · 렌즈 읽음 미관측\n오류 ${live.error} / ${live.errors} · 피드백 ${live.feedback}`;
    paintStt();
  }catch{/* Diagnostics cannot interrupt input, transport or rendering. */}}
  function paintStt(status){
    const captured=session?.audio?.runtime?.cloudStatus,refreshed=bootstrap?.stt;
    status=status||((captured?.observedAt||0)>=(refreshed?.observedAt||0)?captured:refreshed)||{};
    const a=session?.audio||{},r=a.runtime||{},b=status.budget,u=b?.reserved;
    const usd=value=>Number.isSafeInteger(value)&&value>=0?'US$'+(value/1000000).toFixed(6):'미관측';
    const remaining=(cap,used)=>Number.isSafeInteger(cap)&&Number.isSafeInteger(used)&&cap>=0&&used>=0?usd(Math.max(0,cap-used)):'미관측';
    const sample=typeof r.vramObservedAt==='number'&&r.vramObservedAt>0?new Date(r.vramObservedAt).toISOString():'미관측';
    const cloudSample=typeof status.observedAt==='number'&&status.observedAt>0?new Date(status.observedAt).toISOString():'미관측';
    $('sttDiagnostics').textContent=`음성 인식 ${safeId(r.provider)} · 실행 ${safeId(r.device)} · GPU ${safeId(r.gpu)} · 사유 ${safeId(r.reason)}\n최근 ASR ${a.chunks>0?measured(a.lastAsrMs):'미관측'}ms · 대기 ${measured(r.queueLength)} · 오류 ${measured(r.errors)} · VRAM 여유 ${measured(r.vramFreeMiB)}MiB (기동·장치 변경 시 표본 ${sample})\n${status.cloud?.provider==='soniox'?'Soniox':'Deepgram'} ${status.cloud?.configured===true?'설정됨 · 실제 연결 별도 확인':'미설정 또는 미관측'} · 최근 차단기 ${safeId(status.cloud?.state)} / 연속 오류 ${measured(status.cloud?.errors)} (관측 ${cloudSample})\n보수적 비용 예약액 · 실제 청구액과 다름 · 무료 혜택 미반영\n검증 누적 ${usd(u?.verificationMicros)} / ${usd(b?.verificationCapMicros)} · 잔여 ${remaining(b?.verificationCapMicros,u?.verificationMicros)}\nUTC 월 누적 ${usd(u?.monthlyMicros)} / ${usd(b?.monthlyCapMicros)} · 잔여 ${remaining(b?.monthlyCapMicros,u?.monthlyMicros)} · 전체 예약 ${usd(u?.totalMicros)} · 호출 ${measured(u?.requests)}회`;
    const cloud=status.cloud||{};
    const rows=['자동 선택 '+safeId(cloud.routing)+' · 최근 선택 '+safeId(cloud.selectedProvider)+' · 집계: 서버 시작 이후'];
    for(const provider of ['deepgram','soniox']){
      const p=cloud.providers?.[provider]||{},rate=typeof p.failureRate==='number'&&p.failureRate>=0&&p.failureRate<=1?(p.failureRate*100).toFixed(1)+'%':'미관측';
      const cost=Number.isSafeInteger(p.estimatedMicros)&&p.estimatedMicros>=0?'US$'+(p.estimatedMicros/1000000).toFixed(6):'미관측';
      rows.push(provider+' · 시도 '+measured(p.attempts)+' · 연결 관측 '+measured(p.handshakes)+' · 성공 '+measured(p.successes)+' · 실패 '+measured(p.failures)+' / '+rate+' · 취소 '+measured(p.cancelled)+'\n로컬 수락 오디오 '+measured(p.acceptedAudioMs)+'ms · 연결 경과 '+measured(p.connectedWallMs)+'ms · 첫 확정 P95 '+measured(p.firstFinalLatencyP95Ms)+'ms · 설정 단가 예상 '+cost);
    }
    rows.push('오디오 수락은 provider 수신 증명이 아닙니다. Soniox 예상액은 무음·keepalive를 포함한 연결 경과도 반영합니다. 지연은 시작→첫 확정이며 정확도·청구액과 다릅니다.');
    rows.push('공급자 확인 청구액 · 현재 시도 미관측 · 예약액 및 예상액과 별도');
    const account=cloud.accounts||{};
    rows.push('계정 조회 '+(account.enabled===true?'사용':'꺼짐')+' · 신선도 '+(account.fresh===true?'5분 이내':'미관측/오래됨')+' · Deepgram '+safeId(account.deepgram?.funds)+' · Soniox 사용량 '+safeId(account.soniox?.state)+' · 무료 크레딧 구분 미관측');
    if(account.soniox?.state==='observed')rows.push('Soniox 프로젝트 전체 · 이달 완료된 UTC 일자 · 요청 '+measured(account.soniox.requests)+'회 · 입력 음성 '+measured(account.soniox.inputAudioMs)+'ms · 비용 보고 '+(account.soniox.costObserved===true?'관측':'미관측')+' · 현재 시도 청구액과 다름');
    $('sttDiagnostics').textContent+='\n\n'+rows.join('\n');
  }
  function clientError(code){live.errors=Math.min(99,live.errors+1);live.error=code;const now=monotonic();if(now-lastErrorPaintAt>=500){lastErrorPaintAt=now;paintDiagnostics();}}
  window.addEventListener('error',()=>clientError('client_exception'));
  window.addEventListener('unhandledrejection',()=>clientError('unhandled_rejection'));
  const labels={RUNNING:'대화 보조 중',PAUSED:'일시정지',STOPPED:'중지됨'};
  if(new URLSearchParams(location.search).has('display'))document.body.classList.add('display');
  async function api(path,body,keepalive=false){const headers={Accept:'application/json'},began=monotonic();if(body!==undefined){headers['Content-Type']='application/json';if(bootstrap?.csrfHeader)headers[bootstrap.csrfHeader]=bootstrap.csrfToken;}
    const diagnosticRequest=!path.endsWith('/audio/chunk')&&!path.endsWith('/ack');
    const generation=diagnosticRequest?++diagnosticGeneration:0,currentDiagnostic=()=>diagnosticRequest&&generation===diagnosticGeneration;
    if(path.endsWith('/utterance')){headers['X-Request-Id']=crypto.randomUUID();live.requestId=headers['X-Request-Id'];}
    if(diagnosticRequest){live.api='pending';live.httpStatus='미관측';live.roundTripMs='미관측';live.error='none';paintDiagnostics();}
    const statusRequest=(body===undefined&&/^\/api\/assist\/sessions\/[^/?]+$/.test(path))||path.endsWith('/ack');
    const controller=new AbortController(),timeoutMs=statusRequest?4250:path.endsWith('/audio/start')?32000:path.endsWith('/audio/chunk')?3000:15000;
    const timer=setTimeout(()=>controller.abort(),timeoutMs);
    try{const r=await fetch(path,{method:body===undefined?'GET':'POST',credentials:'same-origin',cache:'no-store',redirect:'error',headers,keepalive,body:body===undefined?undefined:JSON.stringify(body),...(controller?{signal:controller.signal}:{})});
      if(currentDiagnostic()){live.httpStatus=r.status??'미관측';live.api=r.ok?'response':'error';}
      if(!r.ok){let reason='http_'+(r.status||'error');try{const value=(await r.json()).reason;if(typeof value==='string'&&/^[a-z_]{1,80}$/.test(value))reason=value;}catch{}throw new Error(reason);}return await r.json();
    }catch(e){if(currentDiagnostic()){live.api=controller.signal.aborted?'timeout':'error';live.error=controller.signal.aborted?'request_timeout':'request_failed';}if(controller.signal.aborted)throw new Error(statusRequest?'status_timeout':'request_timeout');throw e;}
    finally{if(timer!==null)clearTimeout(timer);if(currentDiagnostic()){live.roundTripMs=Math.max(0,Math.round(monotonic()-began));paintDiagnostics();}}
  }
  const route=suffix=>'/api/assist/sessions/'+encodeURIComponent(session.assistId)+suffix;
  const measured=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0?value:'미관측';
  function diagnostics(next){try{const m=next.metrics;if(!m)return;const s=m.stages||{},rate=m.generationAttempts>0?Math.round(100*(m.verificationHolds||0)/m.generationAttempts)+'%':'미관측';
    if(next.reason?.startsWith('RAG_'))$('counts').textContent=$('counts').textContent.replace(/ · 생성 .*? · 보류 /,' · 모델 개별 시도 미관측 · 보류 ');
    $('counts').textContent+=' · 자료 문장 '+measured(s.indexed)+' · 검색 결과 '+measured(s.retrieved)+' / '+measured(s.retrievalMs)+'ms · 재정렬 '+measured(s.reranked)+' / '+measured(s.rerankMs)+'ms · 검증 근거 '+measured(s.verified)+' / '+measured(s.verificationMs)+'ms · 생성 비성공률 '+rate+' · 연결시험 '+(m.fixtureRuns||0)+'회 · 제공자 wire 시도 미관측';
    if(Date.now()-lastDiagnosticAt>=5000){lastDiagnosticAt=Date.now();console.info('[conversate-counts]',{retrieved:measured(s.retrieved),reranked:measured(s.reranked),verified:measured(s.verified),retrievalMs:measured(s.retrievalMs),rerankMs:measured(s.rerankMs),verificationMs:measured(s.verificationMs),generationAttempts:measured(m.generationAttempts),generationMs:measured(m.lastGenerationMs),generationNonSuccessRate:rate,queue:measured(m.queueLength),cancelled:measured(m.cancelled),expired:measured(m.expired),outputAcks:measured(m.outputAcks)});}
    }catch{clientError('diagnostic_sink_failed');}finally{paintDiagnostics();}
  }
  function showReadiness(b){live.serverBuild=safeId(b.buildId);paintDiagnostics();const o=b.ollama||{},flag=value=>value===true?'확인':value===false?'아니오':'미관측';
    paintStt(b.stt||{});
    $('readiness').textContent=o.observed?'Ollama 마지막 기동 검사 · 데몬 '+flag(o.serviceResponding)+' · 모델 존재 '+flag(o.modelPresent)+' · 당시 준비 '+flag(o.modelReady)+' · 워밍업 '+(o.warmupStatus||'미관측')+' / '+measured(o.warmupElapsedMs)+'ms · 증거 나이 '+measured(o.warmupEvidenceAgeMs)+'ms · 현재 모델 상주·생성 가능 여부 미관측 (실제 생성 결과는 아래 계수)':'Ollama 매니저 상태 미관측';
  }
  function syncControls(){const busy=!!(selection||controlPending||utterancePending||startPending),running=session?.state==='RUNNING';
    $('start').disabled=busy||running;$('pause').disabled=!running||!!controlPending;$('stop').disabled=!session||session.state==='STOPPED'||controlPending?.action==='stop';
    $('sendUtterance').disabled=busy||!running;$('microphone').disabled=microphone?!!controlPending:busy||!bootstrap?.asrAvailable;
    $('microphone').textContent=microphone?'마이크 중지 · 일시정지':'휴대폰 마이크 시작';
    $('fixtureMaterials').disabled=$('selectMaterials').disabled=busy||!session||session.state==='STOPPED';$('fixture').disabled=busy||!running;
    if(selection)$('status').textContent='준비 자료 적용 중 · 새 입력 대기';else if(session)$('status').textContent=labels[session.state]||session.state;
  }
  function available(){if(startPending)throw new Error('시작 요청을 처리하고 있습니다.');if(utterancePending)throw new Error('발화를 전달하고 있습니다.');if(selection)throw new Error('준비 자료 적용이 끝나면 입력해 주세요.');if(controlPending)throw new Error('제어 요청을 처리하고 있습니다.');}
  function render(next){if(stopped&&next.state!=='STOPPED')return;if(session?.assistId===next.assistId&&(next.epoch<session.epoch||(next.epoch===session.epoch&&next.version<session.version)))return;if(microphone&&(next.state!=='RUNNING'||microphone.epoch!==next.epoch||microphone.id!==next.assistId))stopMicrophone();if(utterancePending&&(utterancePending.assistId!==next.assistId||utterancePending.epoch!==next.epoch||next.state!=='RUNNING'))utterancePending=null;session=next;$('status').textContent=labels[next.state]||next.state;$('start').disabled=next.state==='RUNNING';$('start').textContent=next.state==='PAUSED'?'다시 시작':'시작';$('pause').disabled=next.state!=='RUNNING';$('stop').disabled=next.state==='STOPPED';clearTimeout(expiryTimer);
    $('displayLink').href='/assets/display/index.html#assist='+encodeURIComponent(next.assistId);const m=next.metrics;if(m)$('counts').textContent='처리 '+m.started+' · 중복 '+m.duplicates+' · 억제 '+m.suppressed+' · 대기 '+m.queueLength+' · 취소 '+(m.cancelled||0)+' · 만료 '+(m.expired||0)+' · 최근 '+m.lastProcessingMs+'ms / '+m.samples+'건 · 생성 '+(m.generationAttempts||0)+'회 / '+(m.lastGenerationMs||0)+'ms · 보류 '+(m.verificationHolds||0)+' · 검색 '+(m.searchAttempts||0)+'회 / 보완 '+(m.queryRefinements||0)+'회 · 원인 '+next.reason+' · 출력 연결 '+next.outputConnections+' · 수신 신고 '+(m.outputAcks||0)+'회 / '+(m.lastOutputAckAt?'서버 '+new Date(m.lastOutputAckAt).toISOString():'미확인')+' (실기 표시 확인 아님)';
    diagnostics(next);if(next.state==='STOPPED'){stopped=true;selection=null;controlPending=null;$('transcript').value='';disconnect();stopMicrophone();}
    else if(next.audio?.state==='CAPTURING'&&next.audio.chunks>0)$('microphoneStatus').textContent='오디오 유입 · 확정 '+next.audio.finals+' · 부분 '+next.audio.partials+' · 최근 ASR '+next.audio.lastAsrMs+'ms';
    else if(next.audio?.state==='STOPPED')$('microphoneStatus').textContent='마이크 입력 중지 · 화면 숨김과 별도';
    const card=next.card,keepPinned=pinned&&shown&&shown.epoch===next.epoch&&shown.id===next.assistId&&shown.card.expiresAt>Date.now();
    if(next.state!=='STOPPED'&&(keepPinned||(card&&card.expiresAt>Date.now()))){
      if(!keepPinned){
        const identity=JSON.stringify([card.text,card.expiresAt,card.kind,card.sourceIds,card.sourceTitles,card.detailPages,card.requestId]);
        if(shown?.identity!==identity){shown={identity,card,epoch:next.epoch,id:next.assistId,version:next.version,cueId:next.diagnostics?.cueId};cardPage=0;evidenceShown=false;$('evidence').textContent='출처';cardPages=answerPages(card);}
        showCardPage();acknowledgeRendered(next);
      }
      expiryTimer=setTimeout(()=>{clearCard(false);if(session?.state==='RUNNING'&&session.card?.expiresAt>Date.now())render(session);},Math.max(0,shown.card.expiresAt-Date.now()));
    }else clearCard();syncControls();paintDiagnostics();if(next.state==='PAUSED'&&next.reason?.startsWith('ASR_'))activateTextFallback(next);}
  function splitPages(text){
    // Reserve four rows in the fixed Display card, including explicit line breaks.
    // Twenty codepoints per row is conservative for 24px Korean text; keep the 60-character cap.
    const pages=[];let part=[],row=1,column=0;
    for(const char of Array.from(text)){
      const lineBreak=char==='\n'||char==='\r',wrap=!lineBreak&&column===20;
      if(part.length&&(part.length===60||((lineBreak||wrap)&&row===4))){pages.push(part.join(''));part=[];row=1;column=0;}
      part.push(char);
      if(lineBreak){row++;column=0;}else{if(column===20){row++;column=0;}column++;}
    }
    if(part.length)pages.push(part.join(''));return pages;
  }
  function answerPages(card){return card.detailPages?.length?card.detailPages.flatMap(splitPages):splitPages(card.text);}
  function showCardPage(){if(!shown||!cardPages.length)return;$('kind').textContent=({TEST:'연결 시험 · 추론 없음',FACT:'짧은 답변',RAG:'RAG 답변',FALLBACK:'대체 안내',CONCEPT:'개념 설명',SUGGESTION:'대화 제안'})[shown.card.kind]||'확인 필요';$('answer').textContent=cardPages[cardPage];$('sources').textContent=(cardPage+1)+' / '+cardPages.length+(pinned?' · 고정됨':'');}
  function clearCard(discard=true){shown=null;pinned=false;cardPages=[];cardPage=0;$('pin').textContent='고정';$('answer').textContent=session?.state==='PAUSED'?'일시정지했습니다.\n다시 시작하면 대화를 이어갑니다.':session?.state==='STOPPED'?'대화 보조를 중지했습니다.':'다음 질문을 기다립니다.';$('sources').textContent='';$('kind').textContent='대화 보조';if(discard&&session)session.card=null;}
  function acknowledgeRendered(next){
    if(!new URLSearchParams(location.search).has('display')||hidden||!shown||stopped||next.state!=='RUNNING')return;
    const target=shown,key=target.id+':'+target.epoch+':'+(target.cueId||target.identity);
    if(key===lastRendered||typeof requestAnimationFrame!=='function')return;
    requestAnimationFrame(()=>requestAnimationFrame(()=>{
      if(shown!==target||hidden||stopped||document.visibilityState!=='visible'||target.card.expiresAt<=Date.now()||session?.state!=='RUNNING'||session.assistId!==target.id||session.epoch!==target.epoch||key===lastRendered)return;
      lastRendered=key;
      api('/api/assist/sessions/'+encodeURIComponent(target.id)+'/ack',{epoch:target.epoch,version:target.version,phase:'rendered'})
        .catch(()=>{clientError('render_ack_unconfirmed');});
    }));
  }
  function disconnect(){clearTimeout(reconnectTimer);stream?.close();stream=null;}
  function validSnapshot(next,current){return next&&next.assistId===current&&Number.isSafeInteger(next.epoch)&&next.epoch>0&&Number.isSafeInteger(next.version)&&next.version>=0&&['RUNNING','PAUSED','STOPPED'].includes(next.state)&&(!next.card||(typeof next.card.text==='string'&&[...next.card.text].length<=120&&Number.isFinite(next.card.expiresAt)&&Array.isArray(next.card.sourceIds)&&next.card.sourceIds.length<=4&&next.card.sourceIds.every(id=>typeof id==='string'&&id.length<=256)&&(!next.card.detailPages||(Array.isArray(next.card.detailPages)&&next.card.detailPages.length<=128&&next.card.detailPages.every(page=>typeof page==='string'&&Array.from(page).length<=120)))));}
  function acknowledge(next){if(!new URLSearchParams(location.search).has('display')||ackPending||stopped)return;
    const now=Date.now(),same=lastAck?.id===next.assistId&&lastAck.epoch===next.epoch;
    if(same&&(next.version<=lastAck.version||now-lastAck.at<1000))return;
    lastAck={id:next.assistId,epoch:next.epoch,version:next.version,at:now};ackPending=true;
    api(route('/ack'),{epoch:next.epoch,version:next.version}).catch(()=>{if(session?.assistId===next.assistId)$('error').textContent='output_receipt_unconfirmed';}).finally(()=>{ackPending=false;});
  }
  function connect(retry=false){disconnect();if(!retry)reconnectAttempts=0;if(!session||stopped)return;const current=session.assistId,opened=new EventSource(route('/output?epoch='+session.epoch),{withCredentials:true});stream=opened;openedAt=Date.now();opened.addEventListener('assist',e=>{if(stream!==opened||stopped||session?.assistId!==current)return;try{if(typeof e.data!=='string'||e.data.length>32768)throw new Error();const next=JSON.parse(e.data);if(!validSnapshot(next,current))throw new Error();render(next);if(session===next){if(Date.now()-openedAt>=10000)reconnectAttempts=0;acknowledge(next);}}catch{disconnect();if(microphone)control('transport_pause').catch(()=>{});$('error').textContent='output_payload_invalid';$('status').textContent='출력 연결을 다시 확인해 주세요.';}});opened.onerror=()=>{if(stream!==opened)return;opened.close();stream=null;if(stopped)return;
    if(reconnectAttempts>=3){disconnect();if(microphone)control('transport_pause').catch(()=>{});$('error').textContent='output_reconnect_exhausted';$('status').textContent='출력 재연결 한도 · 연결을 확인하고 새로고침해 주세요.';return;}
    const delay=[750,1500,3000][reconnectAttempts++];$('status').textContent='출력 재연결 '+reconnectAttempts+'/3 · 입력은 출력 단절 5초 후 정지';
    reconnectTimer=setTimeout(async()=>{const epoch=session?.epoch,currentRequest=()=>session?.assistId===current&&session.epoch===epoch&&!stopped&&!stream;
      if(!currentRequest())return;try{const next=await api('/api/assist/sessions/'+encodeURIComponent(current));if(!currentRequest())return;render(next);connect(true);}
      catch(e){if(!currentRequest())return;disconnect();$('status').textContent=e.message==='status_timeout'?'연결 상태 확인 시간이 초과되었습니다.':e.message==='assist_missing'?'연결이 만료되었습니다.':'연결 상태를 확인하지 못했습니다.';}
    },delay);};}
  function act(fn){return async()=>{try{$('error').textContent='';await fn();}catch(e){$('error').textContent=e.message;}};}
  async function startSession(){
    if(startPending)return false;available();
    const op={assistId:session?.assistId,epoch:session?.epoch},resume=session?.state==='PAUSED';
    const path=resume?route('/control'):'/api/assist/sessions',body=resume?{epoch:op.epoch,action:'resume'}:{};
    startPending=op;stopped=false;syncControls();
    const current=()=>startPending===op&&!stopped&&session?.assistId===op.assistId&&session?.epoch===op.epoch;
    try{const next=await api(path,body);if(!current())return false;render(next);connect();return true;}
    catch(e){if(current())throw e;return false;}
    finally{if(startPending===op){startPending=null;syncControls();}}
  }
  $('start').onclick=act(startSession);
  $('refreshDiagnostics').onclick=act(async()=>{const requestedLifecycle=lifecycle,id=session?.assistId,epoch=session?.epoch,b=await api('/api/assist/bootstrap');if(requestedLifecycle!==lifecycle||stopped)return;showReadiness(b);if(id&&session?.assistId===id&&session.epoch===epoch){const next=await api('/api/assist/sessions/'+encodeURIComponent(id));if(requestedLifecycle===lifecycle&&!stopped&&session?.assistId===id&&session.epoch===epoch)render(next);}});
  async function control(action){if(!session)throw new Error('먼저 시작해 주세요.');if(controlPending&&(action!=='stop'||controlPending.action==='stop'))return;
    const op={id:session.assistId,action},path=route(''),epoch=session.epoch;selection=null;utterancePending=null;startPending=null;controlPending=op;if(action==='stop')$('transcript').value='';stopMicrophone();syncControls();
    const current=()=>controlPending===op&&session?.assistId===op.id;
    try{let next;try{next=await api(path+'/control',{epoch,action});}catch(e){if(!current())return;if(e.message!=='stale_epoch')throw e;
        const fresh=await api(path);if(!current())return;next=fresh.state==='STOPPED'?fresh:await api(path+'/control',{epoch:fresh.epoch,action});}
      if(!current())return;render(next);if(next.state==='STOPPED'){session=null;syncControls();}else if(action!=='transport_pause')connect();
    }catch(e){if(current())throw e;}finally{if(controlPending===op){controlPending=null;syncControls();}}
  }
  $('pause').onclick=act(()=>control('pause'));
  $('stop').onclick=act(()=>control('stop'));
  $('fixture').onclick=act(async()=>{if(!session)throw new Error('먼저 시작해 주세요.');await api(route('/fixture'),{epoch:session.epoch});});
  async function selectMaterials(suffix,body={}){available();if(!session||session.state==='STOPPED')throw new Error('먼저 시작해 주세요.');
    const op={id:session.assistId},path=route(suffix),epoch=session.epoch;selection=op;stopMicrophone();syncControls();
    try{const next=await api(path,{epoch,...body});if(selection===op&&session?.assistId===op.id&&!stopped){render(next);connect();}}
    catch(e){if(selection===op&&session?.assistId===op.id)throw e;}finally{if(selection===op){selection=null;syncControls();}}
  }
  $('fixtureMaterials').onclick=act(()=>selectMaterials('/fixture-materials'));
  $('loadMaterials').onclick=act(async()=>{const choices=await api('/api/assist/materials?sessionId='+encodeURIComponent($('preparedSession').value));$('materialChoices').replaceChildren();for(const choice of choices){const label=document.createElement('label'),input=document.createElement('input');input.type='checkbox';input.value=choice.sourceId;label.append(input,document.createTextNode(choice.title));$('materialChoices').append(label);}});
  $('selectMaterials').onclick=act(()=>selectMaterials('/materials',{sessionId:$('preparedSession').value,sourceIds:[...$('materialChoices').querySelectorAll('input:checked')].map(i=>i.value)}));
  $('sendUtterance').onclick=act(async()=>{
    if(utterancePending)return;available();if(session?.state!=='RUNNING')throw new Error('먼저 시작해 주세요.');
    const text=$('transcript').value;if(!text.trim())return;
    const id=crypto.randomUUID(),op={assistId:session.assistId,epoch:session.epoch},path=route('/utterance');
    utterancePending=op;syncControls();
    const current=()=>utterancePending===op&&session?.assistId===op.assistId&&session.epoch===op.epoch&&!stopped;
    try{
      const next=await api(path,{epoch:op.epoch,utterance:{utteranceId:id,questionId:id,revision:1,isFinal:true,text}});
      if(!current())return;render(next);if(current()&&$('transcript').value===text)$('transcript').value='';
    }catch(e){if(current())throw e;}
    finally{if(utterancePending===op){utterancePending=null;syncControls();}}
  });
  $('hide').onclick=()=>{hidden=!hidden;$('card').classList.toggle('is-hidden',hidden);$('hide').textContent=hidden?'표시 보기':'표시 숨김';if(!hidden&&session)acknowledgeRendered(session);};
  $('pin').onclick=()=>{pinned=!pinned;$('pin').textContent=pinned?'고정 해제':'고정';if(!pinned&&session)render(session);else showCardPage();};
  $('previousCard').onclick=()=>{if(cardPages.length){cardPage=(cardPage+cardPages.length-1)%cardPages.length;showCardPage();}};
  $('nextCard').onclick=()=>{if(cardPages.length){cardPage=(cardPage+1)%cardPages.length;showCardPage();}};
  $('evidence').onclick=()=>{if(!shown)return;evidenceShown=!evidenceShown;$('evidence').textContent=evidenceShown?'답변':'출처';const titles=(shown.card.sourceTitles||[]).filter(t=>typeof t==='string').slice(0,4);cardPages=!evidenceShown?answerPages(shown.card):titles.length?titles.flatMap(splitPages):['출처 제목 미제공 · 근거 '+(shown.card.sourceIds?.length||0)+'개'];cardPage=0;showCardPage();};
  for(const [id,label] of [['feedbackLate','늦음'],['feedbackContent','내용 오류'],['feedbackClipped','화면 잘림']])$(id).onclick=()=>{live.feedback=label+' / cue '+safeId(shown?.cueId||session?.diagnostics?.cueId)+' / 요청 '+safeId(shown?shown.card.requestId:session?.diagnostics?.requestId||live.requestId);paintDiagnostics();};
  document.addEventListener('keydown',e=>{if(e.isComposing||e.keyCode===229||/^(INPUT|TEXTAREA|SELECT)$/.test(e.target?.tagName||'')||e.target?.isContentEditable||e.target?.closest?.('[contenteditable]:not([contenteditable="false"])'))return;if(!['ArrowRight','ArrowLeft','ArrowUp','ArrowDown'].includes(e.key))return;
    const buttons=[...document.querySelectorAll('.focusable')].filter(button=>!button.matches(':disabled')&&button.getAttribute('aria-disabled')!=='true'&&!button.closest('[hidden],[inert]')&&button.getClientRects().length>0&&!['hidden','collapse'].includes(getComputedStyle(button).visibility));
    if(!buttons.length)return;const i=buttons.indexOf(document.activeElement),step=e.key==='ArrowLeft'||e.key==='ArrowUp'?-1:1;
    buttons[i<0?(step<0?buttons.length-1:0):(i+step+buttons.length)%buttons.length].focus();e.preventDefault();
  });
  function stopMicrophone(){const m=microphone;microphone=null;if(m){m.closed=true;m.queue.length=0;m.node?.port.postMessage('stop');m.node?.disconnect();m.media?.getTracks().forEach(t=>t.stop());m.context?.close().catch(()=>{});}syncControls();$('microphoneStatus').textContent='마이크 입력 중지 · 화면 숨김과 별도';}
  async function activateTextFallback(next){
    const key=next.assistId+':'+next.epoch;
    if(lastTextFallbackKey===key||textFallbackPending||stopped||controlPending||selection||document.visibilityState==='hidden'||session?.assistId!==next.assistId||session.epoch!==next.epoch)return;
    const op={id:next.assistId,epoch:next.epoch,lifecycle};lastTextFallbackKey=key;textFallbackPending=op;stopMicrophone();
    try{const value=await api('/api/assist/sessions/'+encodeURIComponent(op.id)+'/control',{epoch:op.epoch,action:'text_fallback'});
      if(stopped||controlPending||selection||op.lifecycle!==lifecycle||session?.assistId!==op.id||session.epoch!==op.epoch)return;
      render(value);connect();$('microphoneStatus').textContent='마이크 중지 · 확정 문맥을 유지한 텍스트 입력';$('transcript').focus();
    }catch{if(!stopped&&session?.assistId===op.id)$('error').textContent='text_fallback_unconfirmed';}
    finally{if(textFallbackPending===op)textFallbackPending=null;}
  }
  async function pauseAudioFailure(m){if(microphone!==m)return;stopMicrophone();$('error').textContent='음성 입력을 사용할 수 없어 텍스트 입력으로 전환합니다.';
    try{if(session?.assistId!==m.id||session.epoch!==m.epoch)return;const next=await api(route(''));
      if(session?.assistId!==m.id||session.epoch!==m.epoch||stopped)return;
      render(next);if(next.epoch===m.epoch&&next.state==='RUNNING')await activateTextFallback(next);
    }catch{$('error').textContent='text_fallback_unconfirmed';}}
  async function pumpAudio(m){if(m.busy||m.closed)return;m.busy=true;try{while(m.queue.length&&!m.closed){const bytes=new Uint8Array(m.queue.shift());let binary='';for(const b of bytes)binary+=String.fromCharCode(b);const next=await api('/api/assist/sessions/'+encodeURIComponent(m.id)+'/audio/chunk',{epoch:m.epoch,sequence:m.sequence++,pcm:btoa(binary)});if(!m.closed)render(next);}}catch{await pauseAudioFailure(m);}finally{m.busy=false;}}
  $('microphone').onclick=act(async()=>{
    if(microphone){await control('pause');return;}if(startPending)return;
    available();if(microphone)return;
    if(!bootstrap?.asrAvailable)throw new Error('음성 입력 서버 설정이 필요합니다.');
    if(!window.isSecureContext||!navigator.mediaDevices?.getUserMedia||typeof AudioWorkletNode==='undefined')throw new Error('인증된 HTTPS와 마이크 지원 브라우저가 필요합니다.');
    if(document.visibilityState==='hidden')return;
    const captureRequest=captureGeneration;
    if((!session||session.state!=='RUNNING')&&!(await startSession()))return;
    if(captureRequest!==captureGeneration||document.visibilityState==='hidden')return;
    $('microphone').disabled=true;const m={id:session.assistId,epoch:session.epoch,queue:[],sequence:0,closed:false,busy:false};microphone=m;
    syncControls();$('microphoneStatus').textContent='마이크 권한 확인 중';
    try{m.media=await navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true},video:false});if(m.closed){m.media.getTracks().forEach(t=>t.stop());return;}
      const track=m.media.getAudioTracks()[0],settings=track?.getSettings?.()||{};
      $('inputDevice').textContent=(track?.label||'장치 이름 미제공').replace(/[\u0000-\u001f\u007f]/g,'').slice(0,120)+' · 캡처 '+(settings.sampleRate||'미확인')+'Hz / '+(settings.channelCount||'미확인')+'채널 → PCM16LE 16000Hz mono · 서버에 설정된 한국어 음성 인식';
      m.context=new AudioContext();await m.context.resume();await m.context.audioWorklet.addModule('/conversate/pcm-worklet.js');
      if(m.closed)return;$('microphoneStatus').textContent='음성 입력 서버 준비 대기 중 · 최대 30초';
      for(let attempt=0;attempt<5&&!m.closed;attempt++){try{const ready=await api(route('/audio/start'),{epoch:m.epoch});if(ready.state!=='READY')throw new Error('asr_not_ready');m.ready=true;break;}catch(e){if(e.message!=='asr_capacity'||attempt===4)throw e;await new Promise(resolve=>setTimeout(resolve,200));}}if(m.closed)return;
      m.node=new AudioWorkletNode(m.context,'conversate-pcm');m.node.onprocessorerror=()=>pauseAudioFailure(m);m.node.port.onmessage=e=>{if(m.closed)return;if(!(e.data?.pcm instanceof ArrayBuffer)||!e.data.pcm.byteLength)return;if(m.queue.length>=2){pauseAudioFailure(m);return;}live.inputPath='phone_voice';$('microphoneStatus').textContent='오디오 유입 · 확정 발화 대기 · 앱을 화면에 유지해 주세요.';m.queue.push(e.data.pcm);pumpAudio(m);};
      m.media.getAudioTracks().forEach(t=>t.onended=()=>pauseAudioFailure(m));const source=m.context.createMediaStreamSource(m.media),mute=m.context.createGain();mute.gain.value=0;source.connect(m.node).connect(mute).connect(m.context.destination);await m.context.resume();if(m.closed)return;$('microphoneStatus').textContent='음성 서버 준비됨 · 실제 오디오 유입 대기';
    }catch(e){await pauseAudioFailure(m);if(e?.name==='NotAllowedError')$('error').textContent='microphone_permission_denied';else if(e?.name==='NotFoundError')$('error').textContent='microphone_device_missing';}
  });
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden'){captureGeneration++;if(microphone)control('pause').catch(()=>{$('error').textContent='capture_pause_unconfirmed';});}});
  window.addEventListener('pagehide',()=>{if(microphone&&session&&bootstrap)api(route('/control'),{epoch:session.epoch,action:'pause'},true).catch(()=>{});lifecycle++;initializing=null;bootstrap=null;stopped=true;selection=null;controlPending=null;utterancePending=null;startPending=null;stopMicrophone();disconnect();clearTimeout(expiryTimer);clearCard();$('transcript').value='';$('preparedSession').value='';$('materialChoices').replaceChildren();session=null;});
  async function initialize(){
    if(initializedLifecycle===lifecycle||initializing?.lifecycle===lifecycle)return;
    const op={lifecycle};initializing=op;stopped=false;$('start').disabled=true;let ready=false;
    const current=()=>initializing===op&&op.lifecycle===lifecycle&&!stopped;
    try{const b=await api('/api/assist/bootstrap');if(!current())return;
      bootstrap=b;showReadiness(b);$('fixture').hidden=!b.fixtureEnabled;$('fixtureMaterials').hidden=!b.fixtureEnabled;$('microphone').disabled=!b.asrAvailable;
      $('microphoneStatus').textContent=b.asrAvailable?'음성 입력 경로 설정 확인 · 마이크를 시작하면 서버 준비를 확인합니다.':'음성 입력 서버 설정이 필요합니다. 텍스트 입력은 사용할 수 있습니다.';
      $('error').textContent='';$('status').textContent='준비됨 · 저장 안 함';
      const id=new URLSearchParams(location.hash.slice(1)).get('assist');
      if(id&&/^[a-f0-9-]{36}$/.test(id)){const next=await api('/api/assist/sessions/'+id);if(!current())return;render(next);connect();}else $('start').focus();
      ready=true;initializedLifecycle=lifecycle;
    }catch(e){if(!current())return;$('status').textContent='로그인 또는 세션 확인이 필요합니다.';$('error').textContent=/^[a-z_]{1,80}$/.test(e.message)?e.message:'output_connection_failed';$('start').disabled=true;}
    finally{if(initializing===op){initializing=null;if(ready)syncControls();}}
  }
  window.addEventListener('pageshow',e=>{if(e.persisted)initialize();});
  initialize();
})();
