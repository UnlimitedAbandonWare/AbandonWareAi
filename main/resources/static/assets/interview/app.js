(function () {
  'use strict';
  const $ = id => document.getElementById(id), core = window.InterviewCore;
  let client, lastPhase = 'IDLE', answer = '', snapshot = null, sent = null, monitor = null, polling = null;
  let displayBusy = false, displayGeneration = 0, lastDelivery = '', lastVersion = 0, submittedCard = '', uncertainSend = false;
  let publicUrl='',publicTimer=null,pageClosed=false,monitorClient='';
  let answerMetadata={},cardMetadata={};
  let requestSequence=0,lastStatusKey='';
  const usePolling=location.protocol==='https:';
  const labels = {question:'질문',hint:'힌트',answer:'짧은 답변'};
  function debug(stage, data) {
    const row = core.debugEvent(stage, data);
    console.debug('[interview-demo]', row);
    const li = document.createElement('li'); li.textContent = new Date().toLocaleTimeString('ko-KR') + '  ' + JSON.stringify(row);
    $('debug-log').prepend(li);
    while ($('debug-log').children.length > 80) $('debug-log').lastChild.remove();
    $('event-count').textContent = $('debug-log').children.length + ' events';
  }
  function step(id, state, text) { $('step-' + id).dataset.state = state; if (text) $(id === 'display' ? 'flow-display-state' : id + '-state').textContent = text; }
  function announce(text) { $('announcer').textContent = text; }
  function notice(text) { $('rag-notice').textContent = text; $('rag-notice').hidden = !text; }
  function renderRag(state) {
    if (!client) return;
    $('ask').disabled = !client.canSubmit(); $('cancel').hidden = state.phase !== 'LOADING';
    document.querySelectorAll('[data-preset]').forEach(button=>button.disabled=state.phase==='LOADING');
    $('question-count').textContent = state.message.length.toLocaleString('ko-KR') + ' / 2,000';
    if (lastPhase === state.phase) return;
    lastPhase = state.phase;
    if (state.phase === 'LOADING') {
      requestSequence++;
      answerMetadata={};cardMetadata={};
      answer = ''; $('answer').textContent = '질문을 보냈습니다. RAG의 답변과 근거를 기다리고 있습니다.';
      $('source-list').replaceChildren(); $('sources').hidden = true; $('use-answer').disabled = true;$('use-summary').disabled=true;
      $('answer-meta').textContent = '처리 중 · 중복 전송 방지'; notice('');
      step('input','done','요청 전송');step('rag','active','서버 처리 중');step('answer','','응답 대기');step('summary','','답변 대기');step('ack','','수신 대기');
      debug('input.submit',{chars:state.message.length});debug('rag.pending',{});
    } else if (state.phase === 'RESULT') {
      answerMetadata=core.ragCardMetadata(state);
      answer = state.result.answer; $('answer').textContent = answer; $('use-answer').disabled = false;$('use-summary').disabled=false;
      $('answer-meta').textContent = `${(state.metrics.roundTripMs / 1000).toFixed(1)}초 왕복 · 출처 ${state.result.sources.length}개`;
      $('source-list').replaceChildren(); $('sources').hidden = false;
      const sources = state.result.sources;
      if (!sources.length) {const li=document.createElement('li');li.textContent='첨부된 출처 없음';$('source-list').append(li);}
      sources.forEach(source => {const li=document.createElement('li');const el=document.createElement(source.url?'a':'span');el.textContent=[source.marker,source.title].filter(Boolean).join(' · ');if(source.url){el.href=source.url;el.target='_blank';el.rel='noopener noreferrer';}li.append(el);$('source-list').append(li);});
      step('rag',sources.length?'done':'',sources.length?`출처 ${sources.length}개`:'검색 상세 미확인');
      if(state.result.fallback){
        step('answer','','대체 응답');notice('모델 생성이 확인되지 않아 서버의 안전 대체 응답을 표시합니다. HTTP 200만으로 생성 완료를 판단하지 않습니다.');
        $('answer-meta').textContent+=' · 안전 대체 응답';debug('rag.fallback',state.metrics);announce('모델 생성 미확인. 안전 대체 응답이 도착했습니다.');
      }else{step('answer','done','답변 수신');debug('rag.result',state.metrics);announce('RAG 응답이 도착했습니다.');}
    } else if (state.phase === 'ERROR') {
      notice(state.error.message); $('answer').textContent='답변이 확인되지 않았습니다. 아래 디버깅에서 요청 상태를 확인하세요.';
      $('answer-meta').textContent='요청 확인 필요';step('rag','','처리 결과 확인 필요');
      debug('rag.error',{...state.metrics,reason:state.error.code});announce(state.error.message);
    } else {
      answerMetadata={};cardMetadata={};
      answer='';$('answer').textContent='새 질문을 입력하세요.';$('use-answer').disabled=true;$('use-summary').disabled=true;$('sources').hidden=true;$('answer-meta').textContent='질문 대기';notice('');
      step('input','active','질문을 준비하세요');step('rag','','대기 중');step('answer','','응답 대기');step('summary','','가공 대기');
    }
    shareRequestState(state);
  }
  client=window.DisplayCore.createClient({onChange:renderRag});
  $('question').addEventListener('input',e=>client.setMessage(e.target.value));
  $('question-form').addEventListener('submit',e=>{e.preventDefault();client.submit();});
  $('question').addEventListener('keydown',e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)&&!e.isComposing){e.preventDefault();client.submit();}});
  $('cancel').addEventListener('click',()=>client.cancel());
  $('new-conversation').addEventListener('click',()=>{client.newConversation();$('question').value='';$('question').focus();debug('input.new_conversation',{});});
  document.querySelectorAll('[data-preset]').forEach(b=>b.addEventListener('click',()=>{if(lastPhase==='LOADING')return;$('question').value=b.dataset.preset;client.setMessage(b.dataset.preset);client.submit();}));
  function kind(){return document.querySelector('input[name="kind"]:checked').value;}
  function updatePreview(){const text=$('display-text').value;const count=Array.from(text).length;$('preview-text').textContent=text||'전달할 문구를 적어 주세요.';$('preview-kind').textContent=labels[kind()];$('preview-count').textContent=count+'자';$('display-count').textContent=count+' / 120'+(count>120?' · 직접 줄여 주세요':'');$('display-count').style.color=count>120?'#a43828':'';let valid=false;try{core.cardText(text,kind());valid=true;}catch(_){}$('send-display').disabled=!valid||displayBusy||snapshot?.state!=='RUNNING'||submittedCard===JSON.stringify([kind(),text,cardMetadata.requestId||null]);}
  $('display-text').addEventListener('input',()=>{cardMetadata={};updatePreview();});document.querySelectorAll('input[name="kind"]').forEach(el=>el.addEventListener('change',updatePreview));
  function takeText(text,type,metadata={}){cardMetadata=metadata;document.querySelector(`input[name="kind"][value="${type}"]`).checked=true;$('display-text').value=text;updatePreview();if(Array.from(text).length>120)announce('120자를 초과합니다. 의미를 확인하며 직접 줄여 주세요.');$('display-text').focus();}
  $('use-question').addEventListener('click',()=>takeText($('question').value,'question'));
  $('use-answer').addEventListener('click',()=>{if(answer)takeText(answer,'answer',answerMetadata);});
  $('use-summary').addEventListener('click',()=>{if(!answer)return;try{const text=core.shortHint(answer);takeText(text,'hint',answerMetadata);step('summary','done',`${Array.from(text).length}자 · 검토 가능`);debug('display.summary',{chars:Array.from(text).length,reason:'complete-sentence-extracted'});announce('전송할 문장을 추출했습니다. 내용을 확인한 뒤 전송하세요.');}catch(_){step('summary','','직접 가공 필요');announce('조건을 보존한 120자 문장을 찾지 못했습니다. 직접 편집해 주세요.');debug('display.summary',{reason:'summary-needs-edit'});}});
  async function api(path,body){const started=performance.now();const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),10000);try{const res=await fetch(path,{method:body===undefined?'GET':'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:controller.signal});if(!res.ok){const e=new Error('http-'+res.status);e.httpStatus=res.status;throw e;}return {data:await res.json(),roundTripMs:Math.round(performance.now()-started),httpStatus:res.status};}finally{clearTimeout(timeout);}}
  function shareRequestState(state){
    const requestId=state?.metrics?.requestId;
    if(pageClosed||snapshot?.state!=='RUNNING'||requestSequence<1||!state||!['LOADING','RESULT','ERROR'].includes(state.phase)||typeof requestId!=='string'||!/^[A-Za-z0-9._:-]{1,128}$/.test(requestId))return;
    const requestState=state.phase==='RESULT'?(state.result.fallback?'FALLBACK':'RESULT'):state.phase==='ERROR'?(['rate-limited','admission-unavailable','outcome-unknown'].includes(state.error.code)?state.error.code:'server-error'):'LOADING';
    const key=JSON.stringify([displayGeneration,requestSequence,requestId,requestState]);if(key===lastStatusKey)return;lastStatusKey=key;
    const generation=displayGeneration,current=snapshot;
    api(`/api/assist/sessions/${current.assistId}/card`,{epoch:current.epoch,kind:'status',requestId,requestState,requestSequence})
      .then(r=>{if(generation===displayGeneration)setSnapshot(r.data);})
      .catch(e=>{if(generation===displayGeneration)debug('display.request_status_error',{httpStatus:e.httpStatus,reason:'status-unavailable'});});
  }
  function showDelivery(){
    const external=snapshot?{...snapshot,outputConnections:Math.max(0,snapshot.outputConnections-1)}:null;
    const status=core.deliveryState(external,sent);
    const copy={DISCONNECTED:['출력 연결이 끊겼습니다','연결을 종료한 뒤 다시 준비해 주세요.'],CONNECTED:['출력 클라이언트 연결됨','전달할 문구를 확인하고 전송하세요.'],WAITING_CONNECTION:['서버 연결 준비됨','출력 클라이언트를 열어 수신을 준비하세요.'],WAITING_RECEIPT:['서버 접수 · 수신 확인 대기','출력 클라이언트의 ACK를 기다립니다.'],RECEIVER_ACK:['출력 클라이언트 수신 확인','수신 ACK를 받았습니다. 실물 렌즈 표시는 별도 확인이 필요합니다.'],EXPIRED:['문구 표시 시간이 끝났습니다','문구는 60초 뒤 지워집니다. 다시 전송하려면 문구를 수정하거나 새 연결을 준비하세요.']};
    if(snapshot&&!uncertainSend){$('delivery-status').textContent=copy[status][0];$('delivery-detail').textContent=copy[status][1];}
    $('display-dot').dataset.state=['CONNECTED','RECEIVER_ACK'].includes(status)?'ok':'';
    $('check-server').textContent=sent?'● 서버 접수':'○ 서버 접수';$('check-server').dataset.done=String(Boolean(sent));
    const ack=status==='RECEIVER_ACK';$('check-receiver').textContent=ack?'● 수신 ACK':'○ 수신 ACK';$('check-receiver').dataset.done=String(ack);
    if(snapshot)step('display',sent?'done':'active',uncertainSend?'전송 결과 미확인':sent?'서버 접수':copy[status][0]);
    step('ack',ack?'done':'',ack?'출력 수신 확인':status==='EXPIRED'?'표시 만료':'수신 대기');
    if(snapshot&&status!==lastDelivery){lastDelivery=status;debug('display.status',{reason:status,epoch:snapshot.epoch,version:snapshot.version,outputs:external.outputConnections});}
    updatePreview();
  }
  function setSnapshot(next){if(!snapshot||next.assistId!==snapshot.assistId||next.version<lastVersion)return;snapshot=next;lastVersion=next.version;showDelivery();}
  async function poll(generation){if(generation!==displayGeneration||!snapshot)return;try{const path='/api/assist/sessions/'+snapshot.assistId+(usePolling?`/output/poll?epoch=${snapshot.epoch}&client=${monitorClient}`:'');const r=await api(path);if(generation===displayGeneration)setSnapshot(r.data);}catch(e){if(generation===displayGeneration){$('delivery-status').textContent='수신 상태 확인 실패';$('delivery-detail').textContent='전송 완료 여부를 추정하지 않습니다. 연결 상태를 확인하세요.';debug('display.status_error',{httpStatus:e.httpStatus,reason:'status-unavailable'});}}finally{if(generation===displayGeneration&&snapshot)polling=setTimeout(()=>poll(generation),1200);}}
  function updateReceiverLink(){if(!snapshot)return;const url=core.receiverUrl(publicUrl||location.origin,snapshot.assistId,snapshot.epoch);$('receiver-link').href=url;$('receiver-link').hidden=false;$('receiver-url').value=url;$('receiver-url').hidden=false;}
  async function refreshPublicAddress(){try{const r=await api('/api/assist/bootstrap');publicUrl=core.publicOrigin(r.data.publicHttpsUrl);$('public-url').hidden=!publicUrl;$('public-url').href=publicUrl||'#';$('public-url').textContent=publicUrl;$('public-url-status').textContent=publicUrl?'발급된 HTTPS 수신 주소':'터널 실행 대기';updateReceiverLink();}catch(_){$('public-url-status').textContent='주소 확인 필요';}finally{if(!pageClosed)publicTimer=setTimeout(refreshPublicAddress,5000);}}
  $('connect-display').addEventListener('click',async()=>{
    if(displayBusy||snapshot)return;displayBusy=true;$('connect-display').disabled=true;const generation=++displayGeneration;
    try{const r=await api('/api/assist/sessions',{});if(generation!==displayGeneration)return;snapshot=r.data;lastVersion=snapshot.version;sent=null;lastDelivery='';submittedCard='';uncertainSend=false;
      updateReceiverLink();$('disconnect-display').disabled=false;
      if(usePolling){monitorClient=core.outputClientId();}
      else{monitor=new EventSource(`/api/assist/sessions/${snapshot.assistId}/output?epoch=${snapshot.epoch}`);
        monitor.addEventListener('assist',e=>{try{if(generation===displayGeneration)setSnapshot(JSON.parse(e.data));}catch(_){debug('display.stream_error',{reason:'invalid-status'});}});
        monitor.onerror=()=>{if(generation===displayGeneration){$('delivery-detail').textContent='출력 연결을 확인하고 있습니다. 자동으로 문구를 다시 보내지 않습니다.';}};}
      debug('display.connected',{httpStatus:r.httpStatus,roundTripMs:r.roundTripMs});showDelivery();shareRequestState(client.state);poll(generation);document.querySelector('.connection-settings').open=true;
    }catch(e){$('delivery-status').textContent='출력 연결 준비 실패';$('delivery-detail').textContent='서버 상태를 확인하세요. 연결을 자동으로 다시 만들지 않습니다.';debug('display.connect_error',{httpStatus:e.httpStatus,reason:'connect-failed'});$('connect-display').disabled=false;}
    finally{displayBusy=false;updatePreview();}
  });
  $('display-form').addEventListener('submit',async e=>{
    e.preventDefault();if(displayBusy||!snapshot||snapshot.state!=='RUNNING')return;
    let text;try{text=core.cardText($('display-text').value,kind());}catch(_){return;}
    const identity=JSON.stringify([kind(),text,cardMetadata.requestId||null]);if(submittedCard===identity)return;submittedCard=identity;uncertainSend=false;
    displayBusy=true;updatePreview();const generation=displayGeneration;const current=snapshot;debug('display.submit',{chars:Array.from(text).length,kind:kind()});
    try{const r=await api(`/api/assist/sessions/${current.assistId}/card`,{epoch:current.epoch,kind:kind(),text,...cardMetadata});if(generation!==displayGeneration)return;sent={epoch:r.data.epoch,version:r.data.version};setSnapshot(r.data);debug('display.accepted',{...r,epoch:sent.epoch,version:sent.version});announce('서버가 문구를 접수했습니다. 수신 확인을 기다립니다.');}
    catch(err){if(generation===displayGeneration){sent=null;uncertainSend=true;$('delivery-status').textContent='전송 결과 확인 필요';$('delivery-detail').textContent='자동 재전송하지 않았습니다. 수신 상태를 확인한 뒤 진행하세요.';debug('display.send_error',{httpStatus:err.httpStatus,reason:'outcome-unknown'});}}
    finally{displayBusy=false;updatePreview();}
  });
  $('disconnect-display').addEventListener('click',async()=>{
    if(!snapshot||displayBusy)return;displayBusy=true;const current=snapshot;++displayGeneration;clearTimeout(polling);monitor?.close();monitor=null;
    try{await api(`/api/assist/sessions/${current.assistId}/control`,{epoch:current.epoch,action:'stop'});snapshot=null;sent=null;lastVersion=0;$('display-text').value='';$('receiver-link').hidden=true;$('receiver-url').hidden=true;$('receiver-url').value='';$('connect-display').disabled=false;$('disconnect-display').disabled=true;$('delivery-status').textContent='연결 종료 · 문구 삭제';$('delivery-detail').textContent='새 출력 연결을 준비할 수 있습니다.';step('display','','연결 종료');debug('display.stopped',{});showDelivery();}
    catch(e){$('delivery-status').textContent='종료 확인 실패';$('delivery-detail').textContent='서버 종료 결과가 확인되지 않았습니다. 문구는 만료 시 삭제됩니다.';debug('display.stop_error',{httpStatus:e.httpStatus,reason:'stop-unconfirmed'});}
    finally{displayBusy=false;updatePreview();}
  });
  $('clear-debug').addEventListener('click',()=>{$('debug-log').replaceChildren();$('event-count').textContent='0 events';});
  window.addEventListener('pagehide',()=>{pageClosed=true;++displayGeneration;clearTimeout(polling);clearTimeout(publicTimer);monitor?.close();client.cancel();if(snapshot)fetch(`/api/assist/sessions/${snapshot.assistId}/control`,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify({epoch:snapshot.epoch,action:'stop'}),keepalive:true}).catch(()=>{});});
  updatePreview();debug('demo.ready',{});refreshPublicAddress();
})();
