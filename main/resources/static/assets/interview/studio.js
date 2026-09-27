(function(){
  'use strict';
  const $=id=>document.getElementById(id),inspector=window.RagInspector,ledger=inspector.createLedger();
  let client,voice,lastPhase='IDLE',activeAnswer=null,activeQuestion='',turn=0,listening=false;
  const tabs=Array.from(document.querySelectorAll('[data-tab]'));
  const plain=value=>value===null||value===undefined?'미관측':String(value);
  const ms=value=>value===null||value===undefined?'미관측':value.toLocaleString('ko-KR')+' ms';
  function announce(value){$('announcer').textContent=value;}
  function stage(name,state,detail){$('stage-'+name).dataset.state=state;$('detail-'+name).textContent=detail;}
  function status(value,state=''){$('run-status').textContent=value;$('run-status').dataset.state=state;}
  function fields(id,rows){const container=$(id);container.replaceChildren();for(const [name,value] of rows){const row=document.createElement('div'),dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=name;dd.textContent=value;row.append(dt,dd);container.append(row);}}
  function inspect(result,metrics={}){
    const info=result?.inspection||{},p=info.pipeline||{},sources=result?.sources||[];
    $('source-count').textContent=String(sources.length);const list=$('evidence-list');list.replaceChildren();
    if(!sources.length){const empty=document.createElement('div');empty.className='inspector-empty';const title=document.createElement('b'),note=document.createElement('p');title.textContent=result?'첨부된 문서 근거가 없습니다':'근거를 기다리고 있습니다';note.textContent=result?'검색 성공이나 검색 실패를 출처 개수만으로 단정하지 않습니다.':'질문을 보내면 출처와 검색 점수를 확인할 수 있습니다.';empty.append(title,note);list.append(empty);}
    for(const [i,source] of sources.entries()){
      const card=document.createElement('article');card.className='evidence-card';const label=document.createElement('p');label.className='source-label';label.textContent=`DOCUMENT ${String(i+1).padStart(2,'0')} · ${source.kind||'근거'}`;
      const title=document.createElement('h3');title.textContent=[source.marker,source.title].filter(Boolean).join(' ');card.append(label,title);
      if(source.url){const link=document.createElement('a');link.href=source.url;link.target='_blank';link.rel='noopener noreferrer';link.textContent=new URL(source.url).hostname+' ↗';card.append(link);}
      const scores=document.createElement('div');scores.className='score-row';for(const value of ['순위 '+plain(source.rank),'Confidence '+(source.confidence===null?'미관측':source.confidence.toFixed(3)),source.confidenceSource||'점수 출처 미관측']){const chip=document.createElement('span');chip.textContent=value;scores.append(chip);}card.append(scores);
      if(source.confidence!==null){const track=document.createElement('div'),fill=document.createElement('i');track.className='score-track';fill.style.width=(source.confidence*100)+'%';track.append(fill);card.append(track);}list.append(card);
    }
    fields('routing-values',[['사용된 모델',plain(info.model)],['모델 실행 경로',info.executionRoute==='local'?'로컬 (서버 보고)':info.executionRoute==='api'?'API (서버 보고)':'미관측'],['검색 경로',plain(p.route)],['실행 계획',plain(p.planId)],['RAG 사용',info.ragUsed===true?'서버 보고: 사용':info.ragUsed===false?'서버 보고: 미사용':'미관측'],['답변 모드',plain(info.answerMode)],['Failure class',plain(p.failureClass)],['Disabled reason',plain(p.disabledReason)],['Provider 호출 증거','미관측']]);
    fields('metric-values',[['브라우저 왕복',ms(metrics.roundTripMs)],['웹 검색 문서',plain(p.webCount)],['벡터 검색 문서',plain(p.vectorCount)],['최종 컨텍스트 문서',plain(p.finalContextCount)],['인용 커버리지',plain(p.citationCoverage)],['최종 점수 (sigmoid)',plain(p.finalSigmoid)],['검색 지연시간',ms(info.retrievalMs)],['LLM 지연시간',ms(info.llmMs)],['HTTP 상태',plain(metrics.httpStatus)]]);
    if(result){
      stage('input','done','선택한 응답의 질문');
      stage('retrieval',p.webCount!==null||p.vectorCount!==null?'done':'',`웹 ${plain(p.webCount)} · 벡터 ${plain(p.vectorCount)}`);
      stage('evidence',sources.length?'done':'',sources.length?`근거 문서 ${sources.length}개`:'첨부 근거 없음');
      stage('context',p.finalContextCount!==null?'done':'',p.finalContextCount!==null?`최종 컨텍스트 ${p.finalContextCount}개`:'조합 상세 미관측');
      stage('answer',result.held||result.fallback?'warning':'done',result.held?'검증된 근거 부족 · 답변 보류':result.fallback?'안전 대체 응답 수신':'응답 수신 · 호출 증거 별도');
      status(result.held?'답변 보류':result.fallback?'대체 응답':p.failureClass?'제약 있는 응답':'응답 수신',result.held||result.fallback||p.failureClass?'warning':'done');
      $('snapshot-note').textContent='선택한 답변의 최종 집계입니다. 문서 점수는 검색 단계와 점수 출처에 따라 해석하세요.';
    }
  }
  function updateLedger(state){const row=ledger.record(state);if(row)console.debug('[rag-studio]',row);const totals=ledger.snapshot();$('session-summary').textContent=`응답 ${totals.completedResponses} · fallback ${totals.fallbackResponses} · 보류 ${totals.heldResponses} · 오류 ${totals.failedRequests} · 결과 미확인 ${totals.unknownOutcomes}`;$('metric-log').textContent=totals.rows.length?JSON.stringify(totals,null,2):'아직 요청이 없습니다.';}
  function addMessage(role,text){const article=document.createElement('article');article.className='message '+role;const label=document.createElement('div');label.className='message-label';const avatar=document.createElement('span');avatar.className='avatar';avatar.textContent=role==='user'?'나':'✳';const name=document.createElement('span');name.textContent=role==='user'?'질문':'곁 · RAG';label.append(avatar,name);const body=document.createElement('p');body.className='message-text';body.textContent=text;article.append(label,body);$('messages').append(article);return {article,body};}
  function render(state){
    if(!client)return;$('ask').disabled=!client.canSubmit();$('cancel').hidden=state.phase!=='LOADING';$('voice').disabled=!voice?.supported||state.phase==='LOADING';$('char-count').textContent=state.message.length.toLocaleString('ko-KR')+' / 2,000';document.querySelectorAll('[data-preset]').forEach(b=>b.disabled=state.phase==='LOADING');
    if(lastPhase===state.phase)return;lastPhase=state.phase;$('request-notice').hidden=true;
    if(state.phase==='LOADING'){
      voice?.cancel();turn++;activeQuestion=state.message;$('welcome').hidden=true;
      addMessage('user',activeQuestion);activeAnswer=addMessage('assistant','검색과 답변을 기다리고 있습니다.');activeAnswer.article.id='turn-'+turn;
      const history=$('history');if(turn===1)history.replaceChildren();const li=document.createElement('li'),button=document.createElement('button');button.type='button';button.textContent=Array.from(activeQuestion).slice(0,45).join('');button.addEventListener('click',()=>activeAnswerForHistory?.scrollIntoView({block:'center',behavior:'smooth'}));const activeAnswerForHistory=activeAnswer.article;li.append(button);history.append(li);
      while(history.children.length>30)history.firstChild.remove();while($('messages').children.length>60)$('messages').firstChild.remove();
      inspect(null);status('처리 중');stage('input','done','질문 전송됨');stage('retrieval','pending','서버 최종 응답 대기');stage('evidence','','근거 대기');stage('context','','구성 정보 대기');stage('answer','','응답 대기');
      $('snapshot-note').textContent='처리 중입니다. 단계별 완료 시각은 현재 전송 방식에서 관측되지 않습니다.';announce('질문을 보냈습니다.');
    }else if(state.phase==='RESULT'){
      const result=state.result;activeAnswer.body.textContent=result.answer;
      const meta=document.createElement('div');meta.className='message-meta';meta.textContent=`${result.inspection.model||'모델 미관측'} · ${(state.metrics.roundTripMs/1000).toFixed(2)}초 왕복 · 근거 ${result.sources.length}개${result.fallback?' · 대체 응답':''}`;activeAnswer.article.append(meta);
      const actions=document.createElement('div');actions.className='message-actions';const evidence=document.createElement('button');evidence.type='button';evidence.textContent='이 답변의 근거 보기 ↗';evidence.addEventListener('click',()=>{inspect(result,state.metrics);selectTab('evidence');});actions.append(evidence);
      const copy=document.createElement('button');copy.type='button';copy.textContent='답변 복사';copy.addEventListener('click',async()=>{try{await navigator.clipboard.writeText(result.answer);announce('답변을 복사했습니다.');}catch(_){announce('이 브라우저에서는 복사할 수 없습니다. 답변을 직접 선택해 주세요.');}});actions.append(copy);activeAnswer.article.append(actions);
      inspect(result,state.metrics);
      if(result.held||result.fallback){$('request-notice').hidden=false;$('request-notice').textContent=result.held?'서버가 근거 검증 조건에 따라 답변을 보류했습니다. 아래 안전·증거 상태를 확인하세요.':'서버의 대체 응답입니다. 실제 모델 생성이 확인된 답변으로 집계하지 않습니다.';}
      updateLedger(state);announce(result.held?'근거 검증이 필요해 답변이 보류됐습니다.':result.fallback?'대체 응답이 도착했습니다.':'서버 응답이 도착했습니다. 근거를 확인하세요.');
    }else if(state.phase==='ERROR'){
      if(activeAnswer)activeAnswer.body.textContent=state.error.message;$('request-notice').hidden=false;$('request-notice').textContent=state.error.message;
      status(state.error.code==='outcome-unknown'?'결과 미확인':'요청 오류','warning');for(const name of ['retrieval','evidence','context','answer'])stage(name,'','완료 여부 미관측');inspect(null,{...state.metrics,...(state.error.code==='outcome-unknown'?{roundTripMs:null}:{})});updateLedger(state);announce(state.error.message);
    }else{status('대기');inspect(null);}
    $('messages').scrollTop=$('messages').scrollHeight;
  }
  function selectTab(name){for(const tab of tabs){const selected=tab.dataset.tab===name;tab.setAttribute('aria-selected',String(selected));tab.tabIndex=selected?0:-1;$('panel-'+tab.dataset.tab).hidden=!selected;}}
  tabs.forEach((tab,index)=>{tab.addEventListener('click',()=>selectTab(tab.dataset.tab));tab.addEventListener('keydown',event=>{let i=index;if(event.key==='ArrowRight')i=(index+1)%tabs.length;else if(event.key==='ArrowLeft')i=(index+tabs.length-1)%tabs.length;else if(event.key==='Home')i=0;else if(event.key==='End')i=tabs.length-1;else return;event.preventDefault();selectTab(tabs[i].dataset.tab);tabs[i].focus();});});
  client=window.DisplayCore.createClient({publicWebSearch:true,projectResponse:inspector.projectResponse,onChange:render});
  voice=window.StudioVoice.createVoice({Recognition:window.SpeechRecognition||window.webkitSpeechRecognition,onTranscript:text=>{$('question').value=text;client.setMessage(text);$('voice-status').textContent='전사된 질문을 확인하고 직접 전송하세요.';},onState:state=>{listening=state==='listening';$('voice').setAttribute('aria-pressed',String(listening));$('voice-status').textContent=state==='listening'?'듣고 있습니다. 음성 입력을 다시 누르면 중단합니다.':state==='denied'?'마이크 권한이 허용되지 않았습니다. 텍스트로 질문할 수 있습니다.':state==='unavailable'?'음성 인식을 사용할 수 없습니다. 텍스트로 질문할 수 있습니다.':'질문을 확인한 뒤 전송하세요.';}});
  $('voice').disabled=!voice.supported;if(!voice.supported)$('voice-status').textContent='이 브라우저는 음성 인식을 지원하지 않습니다. 텍스트로 질문해 주세요.';
  $('voice').addEventListener('click',()=>{if(listening)voice.cancel();else voice.start();});
  $('question').addEventListener('input',event=>{voice.cancel();client.setMessage(event.target.value);});
  function submit(){voice.cancel();client.submit();}
  $('question-form').addEventListener('submit',event=>{event.preventDefault();submit();});
  $('question').addEventListener('keydown',event=>{if(event.key==='Enter'&&(event.ctrlKey||event.metaKey)&&!event.isComposing){event.preventDefault();submit();}});
  $('cancel').addEventListener('click',()=>client.cancel());
  $('new-conversation').addEventListener('click',()=>{voice.cancel();client.newConversation();turn=0;activeAnswer=null;$('question').value='';$('messages').replaceChildren();$('history').replaceChildren();$('welcome').hidden=false;for(const name of ['input','retrieval','evidence','context','answer'])stage(name,'','질문 대기');$('question').focus();announce('새 대화를 시작합니다.');});
  document.querySelectorAll('[data-preset]').forEach(button=>button.addEventListener('click',()=>{if(client.state.phase==='LOADING')return;voice.cancel();$('question').value=button.dataset.preset;client.setMessage(button.dataset.preset);submit();}));
  $('clear-metrics').addEventListener('click',()=>{ledger.clear();$('metric-log').textContent='아직 요청이 없습니다.';$('session-summary').textContent='응답 0 · fallback 0';});
  window.addEventListener('pagehide',()=>{voice.cancel();client.cancel();});inspect(null);
})();
