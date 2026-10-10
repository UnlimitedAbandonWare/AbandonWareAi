(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory(require('./display-focus-flow.js'));else root.NovaFocus=factory(root.NovaFocusFlow);})(typeof globalThis!=='undefined'?globalThis:this,function(Flow){
  'use strict';
  const PHASES=new Set(['OFF','ARMED','WAKE_PREVIEW','LISTENING','SNAPSHOT','THINKING','ANSWER_READY','PRESENTING','WAITING','SUSPENDED']);
  const LABELS={WAKE_PREVIEW:'호출 확인 중',LISTENING:'듣는 중',SNAPSHOT:'사진 촬영 중',THINKING:'응답 준비 중',ANSWER_READY:'답변 표시 중',PRESENTING:'답변 표시 중',WAITING:'후속 질문 대기',SUSPENDED:'연결 확인 필요'};
  const CLOSE_REASONS={wake_no_question:'질문이 들리지 않아 종료했습니다.',wake_unconfirmed:'호출이 확정되지 않아 종료했습니다.',wake_retracted:'노바 호출이 취소됐습니다.',idle_timeout:'후속 질문 대기 시간이 지났습니다.',generation_timeout:'응답 대기 시간이 지났습니다.',presentation_unconfirmed:'답변 표시 완료를 확인하지 못했습니다.',input_unconfirmed:'말씀하신 질문이 확정되지 않았습니다.',capture_changed:'수음 연결이 바뀌었습니다.',memory_changed:'참고할 메모리가 바뀌었습니다.',focus_outcome_unknown:'질문 처리 결과를 확인하지 못했습니다. 같은 질문을 자동으로 다시 보내지 않습니다.',focus_request_already_accepted:'이미 접수된 질문입니다.',focus_grounding_publication_held:'검색 답변의 출처 표시를 확인하지 못했습니다.',focus_stream_final_mismatch:'최종 응답을 확인하지 못했습니다.',server_shutdown:'서버가 재시작되거나 종료됐습니다.',user_closed:'사용자가 집중창을 닫았습니다.',focus_answer_unavailable:'답을 만들지 못했어요. 잠시 후 다시 질문해 주세요.',focus_busy:'앞선 질문을 처리하고 있습니다.'};
  CLOSE_REASONS.session_changed='연결 세션이 바뀌었습니다.';
  const reasonCode=value=>/^[a-z][a-z0-9_]{0,63}$/.test(value||'')?value:'unknown';
  const closeReasonText=value=>Object.prototype.hasOwnProperty.call(CLOSE_REASONS,reasonCode(value))?CLOSE_REASONS[reasonCode(value)]:'노바가 종료됐습니다 ('+reasonCode(value)+').';
  function decode(raw){
    if(raw==null)return null;
    if(typeof raw!=='object'||typeof raw.active!=='boolean'||!PHASES.has(raw.phase)||!Number.isSafeInteger(raw.stateVersion)||raw.stateVersion<0)throw Error('invalid_focus');
    for(const field of ['serverInstanceId','activationId','turnId'])if(typeof raw[field]!=='string'||raw[field].length>128)throw Error('invalid_focus');
    for(const [field,max] of [['draftText',2000],['questionText',2000],['answerText',8000]])if(typeof raw[field]!=='string'||Array.from(raw[field]).length>max)throw Error('invalid_focus');
    if(!Number.isSafeInteger(raw.answerVersion)||raw.answerVersion<0||!Number.isFinite(raw.idleRemainingMs)||raw.idleRemainingMs<0)throw Error('invalid_focus');
    if(raw.renderTarget!=null&&!['lens','fold'].includes(raw.renderTarget))throw Error('invalid_focus');
    if(raw.renderReceiptTicket!=null&&raw.renderReceiptTicket!==''&&!/^[a-f0-9]{64}$/.test(raw.renderReceiptTicket))throw Error('invalid_focus');
    for(const field of ['answerTruncated','hasMoreOnFold'])if(raw[field]!=null&&typeof raw[field]!=='boolean')throw Error('invalid_focus');
    if(raw.isFallback!=null&&typeof raw.isFallback!=='boolean')throw Error('invalid_focus');
    if(raw.isFallback===true){
      for(const field of ['requestedModel','effectiveModel'])if(typeof raw[field]!=='string'||raw[field].length>180||! /^[a-zA-Z0-9][a-zA-Z0-9._/:+-]*$/.test(raw[field]))throw Error('invalid_focus');
      if(!/^[a-z][a-z0-9_]{0,63}$/.test(raw.fallbackReasonCode||'')||! /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(raw.originalError||''))throw Error('invalid_focus');
    }
    if(raw.grounding!=null){
      const value=raw.grounding;
      if(raw.renderTarget!=='fold'||typeof value.originalText!=='string'||!value.originalText||typeof value.model!=='string'
        ||!value.metadata||typeof value.metadata.searchEntryPoint?.renderedContent!=='string')throw Error('invalid_focus_grounding');
    }
    return {...raw,renderReceiptTicket:raw.renderReceiptTicket||null,presentation:Flow.settings(raw.presentation)};
  }
  function createProjection(options){
    const host=options.host||globalThis,doc=options.document||host.document,panel=options.panel;
    let current=null,server='',version=-1,paused=false,awaitingFresh=false,disposed=false;
    let groundedKey='',groundedHost=null;
    const keepalive=options.target==='fold';
    let closeTimer=null,terminalKey='',terminalVisible=false,dismissedActivation='',lastDiagnostic='',scopeEndedActive=false;
    let fallbackStatus=options.fallbackStatus||null;
    const waitingReasons={output_lost:'출력 연결을 기다립니다.',producer_reclaimed:'기기를 다시 연결하고 있습니다.',presentation_unconfirmed:'마지막 답변을 유지하며 표시 확인을 기다립니다.',idle_timeout:'다음 질문을 기다립니다.',wake_no_question:'질문을 말씀해 주세요.',input_unconfirmed:'발화를 확정하지 못했습니다. 다시 말씀해 주세요.',generation_timeout:'답변 요청 시간이 끝났습니다. 다시 질문해 주세요.',focus_next_question_full:'대기 질문이 가득 찼습니다. 답변 뒤 다시 말씀해 주세요.',focus_question_already_queued:'이미 접수된 질문은 대기 순서대로 처리합니다.'};
    function clearQuestion(){if(options.question){options.question.textContent='';if(options.question.dataset)delete options.question.dataset.turnId;}}
    function renderFallback(next){
      const visible=keepalive&&next?.active&&next.isFallback===true&&!!next.answerText&&next.answerComplete!==false;
      if(visible&&!fallbackStatus&&doc?.createElement&&panel?.append){
        fallbackStatus=doc.createElement('div');fallbackStatus.setAttribute?.('role','status');
        Object.assign(fallbackStatus.style,{padding:'8px',border:'1px solid #d69e2e',background:'#44351c',color:'#fff3cd'});panel.append(fallbackStatus);
      }
      if(fallbackStatus){fallbackStatus.hidden=!visible;fallbackStatus.textContent=visible?'요청 모델 '+next.requestedModel+' 실패 ('+next.fallbackReasonCode+') · 대체 모델 '+next.effectiveModel+'로 응답했습니다.':'';}
    }
    function cancelClose(){if(closeTimer!==null)host.clearTimeout?.(closeTimer);closeTimer=null;terminalVisible=false;}
    function diagnostic(cause,hidden=false){
      const key=cause+':'+hidden;if(key===lastDiagnostic)return;lastDiagnostic=key;
      options.diagnostic?.(hidden?'hide_cause':'focus_visibility',{cause,hidden,stateVersion:current?.stateVersion||0,phase:current?.phase||'OFF'});
    }
    function dismiss(){
      clearQuestion();
      renderFallback(null);dismissedActivation=current?.activationId||'';scopeEndedActive=false;cancelClose();clearGrounding();flow.accept(null);receipts.clear();
      if(panel)panel.hidden=true;
    }
    function contractError(){
      options.diagnostic?.('focus_contract',{});diagnostic('contract');awaitingFresh=true;flow.pause();
      if(keepalive&&terminalKey){if(panel)panel.hidden=!terminalVisible;return false;}
      if(panel)panel.hidden=!current?.active||dismissedActivation===current.activationId||(!keepalive&&!connectedState);
      if(keepalive&&current?.active&&options.status)options.status.textContent='노바 · 표시 오류 · 다시 시도';
      return !!current?.active;
    }
    let connectedState=true;
    const receipts=new Map();
    async function flush(){
      for(const [key,entry] of receipts){
        if(entry.sending||entry.done||entry.attempts>=2||doc?.hidden||!current||entry.detail.activationId!==current.activationId||entry.detail.turnId!==current.turnId)continue;
        if(entry.name==='presentation_done'&&[...receipts.values()].some(first=>first.name==='first_visible'&&!first.done&&(first.sending||first.attempts<2)&&first.detail.turnId===entry.detail.turnId&&first.detail.answerVersion===entry.detail.answerVersion))continue;
        entry.sending=true;entry.attempts++;
        try{await options.receipt?.(entry.name,entry.detail);entry.done=true;}catch{}finally{entry.sending=false;}
      }
    }
    function makeFlow(){return Flow.createFlow({host,element:options.answer,retainAfterPresentation:true,requestFrame:options.requestFrame,cancelFrame:options.cancelFrame,fitsLine:options.fitsLine,onEvent(name,detail){
      // The ticket is passed only to the receipt request, never to diagnostics.
      options.diagnostic?.(name,{answerVersion:detail.answerVersion,mode:detail.mode});
      if((name==='first_visible'||name==='presentation_done')&&current?.renderTarget===options.target&&detail.renderReceiptTicket){
        const key=[detail.activationId,detail.turnId,detail.answerVersion,name].join('/');
        if(!receipts.has(key))receipts.set(key,{name,detail,attempts:0,sending:false,done:false});
        while(receipts.size>4)receipts.delete(receipts.keys().next().value);void flush();
      }
    }});}
    let flow=makeFlow();
    function reset(){
      clearQuestion();
      scopeEndedActive=scopeEndedActive||!!current?.active&&dismissedActivation!==current.activationId;
      renderFallback(null);cancelClose();clearGrounding();receipts.clear();flow.dispose();flow=makeFlow();
      current=null;server='';version=-1;terminalKey='';dismissedActivation='';lastDiagnostic='';awaitingFresh=true;
      if(panel)panel.hidden=true;if(options.draft)options.draft.textContent='';if(options.status)options.status.textContent='';
    }
    function clearGrounding(){if(groundedHost)groundedHost.remove();groundedHost=null;groundedKey='';if(options.answer?.style)options.answer.style.overflow='';}
    function renderGrounding(next){
      const key=[next.serverInstanceId,next.activationId,next.turnId,next.answerVersion].join('/');
      if(groundedKey===key){groundingReceipts(next);return true;}
      if(!options.answer||!doc?.createElement)return false;
      const html=next.grounding.metadata.searchEntryPoint.renderedContent;
      const template=doc.createElement('template');template.innerHTML=html;
      const allowed=new Set(['DIV','SPAN','STYLE','A','SVG','PATH','P','BR','G','CIRCLE']);
      for(const element of template.content.querySelectorAll('*')){
        if(!allowed.has(element.tagName.toUpperCase()))return false;
        for(const attr of element.attributes){
          const name=attr.name.toLowerCase();if(name.startsWith('on')||['src','srcdoc','action','formaction','xlink:href'].includes(name))return false;
          if(name==='href')try{const url=new URL(attr.value);if(element.tagName.toUpperCase()!=='A'||url.protocol!=='https:'||url.username||url.password)return false;}catch{return false;}
          if(name==='style'&&unsafeStyle(attr.value))return false;
        }
        if(element.tagName.toUpperCase()==='STYLE'&&unsafeStyle(element.textContent))return false;
      }
      clearGrounding();flow.accept(null);
      const block=doc.createElement('div'),text=doc.createElement('div'),suggestions=doc.createElement('div');
      text.textContent=next.grounding.originalText;
      const shadow=suggestions.attachShadow?.({mode:'closed'});if(!shadow)return false;
      // Validated provider HTML is kept intact and styles are confined to this host.
      shadow.innerHTML=html;suggestions.style.contain='content';suggestions.style.position='relative';
      const sources=doc.createElement('div'),seen=new Set();
      for(const support of next.grounding.metadata.groundingSupports||[])for(const index of support.groundingChunkIndices||[]){
        if(seen.has(index))continue;seen.add(index);
        const source=next.grounding.metadata.groundingChunks?.[index]?.web;
        if(!source||typeof source.uri!=='string')return false;
        let url;try{url=new URL(source.uri);}catch{return false;}
        if(url.protocol!=='https:'||url.username||url.password)return false;
        const link=doc.createElement('a');link.href=source.uri;link.textContent=source.title||source.uri;link.target='_blank';link.rel='noopener noreferrer';
        sources.append(link,doc.createElement('br'));
      }
      block.append(text,sources,suggestions);options.answer.replaceChildren(block);options.answer.style.overflow='auto';
      groundedHost=block;groundedKey=key;
      groundingReceipts(next);return true;
    }
    function groundingReceipts(next){
      if(next.renderReceiptTicket&&!doc.hidden){
        for(const name of ['first_visible','presentation_done']){
          const receiptKey=[next.activationId,next.turnId,next.answerVersion,name].join('/');
          if(!receipts.has(receiptKey))receipts.set(receiptKey,{name,detail:next,attempts:0,sending:false,done:false});
        }
      }
    }
    function unsafeStyle(value){return /\\|@import|url\s*\(|expression\s*\(|behavior\s*:|position\s*:\s*fixed/i.test(value);}
    function update(raw,connected=true,connection='RECONNECTING'){
      if(disposed)return false;
      connectedState=connected;
      let next;try{next=decode(raw);}catch{return contractError();}
      if(keepalive&&!next&&current?.active){
        if(connected)return contractError();
        next=current;
      }
      if(next&&server===next.serverInstanceId&&next.stateVersion<version)return !!current?.active;
      if(next){server=next.serverInstanceId;version=next.stateVersion;}
      if(keepalive&&!next?.active&&!next?.reason&&terminalKey){current=next||current;if(panel)panel.hidden=!terminalVisible;return false;}
      const wasActive=!!current?.active||scopeEndedActive;if(next)scopeEndedActive=false;
      if(current?.renderReceiptTicket!==next?.renderReceiptTicket)receipts.clear();
      current=next;renderFallback(next);
      if(!next?.active)clearQuestion();
      if(keepalive&&next?.activationId&&dismissedActivation===next.activationId){if(panel)panel.hidden=true;return false;}
      if(keepalive&&!next?.active&&next?.reason&&(wasActive||terminalKey)){
        clearGrounding();flow.accept(null);receipts.clear();
        const cause='server_inactive:'+reasonCode(next.reason),key=[server,next.activationId,cause].join('/');
        if(key!==terminalKey){
          cancelClose();terminalKey=key;terminalVisible=true;
          if(options.status)options.status.textContent='노바 종료됨 · '+(options.reasonText?.(reasonCode(next.reason))||closeReasonText(next.reason));
          diagnostic(cause);
          if(!options.keepClosed?.())closeTimer=host.setTimeout?.(()=>{closeTimer=null;terminalVisible=false;if(panel)panel.hidden=true;diagnostic(cause,true);},4000)??null;
        }
        if(panel)panel.hidden=!terminalVisible;
        return false;
      }
      if(next?.active){cancelClose();terminalKey='';dismissedActivation='';}
      if(panel)panel.hidden=!next?.active||!connected;
      if(!next?.active){clearQuestion();clearGrounding();flow.accept(null);receipts.clear();return false;}
      if(options.status)options.status.textContent='노바 · '+(LABELS[next.phase]||'대화 중')+(next.grounding?' · 검색 근거':next.hasMoreOnFold?' · 긴 응답 일부 표시':'');
      if(options.question){options.question.textContent=next.questionText;options.question.dataset&&(options.question.dataset.turnId=next.turnId);}
      if(options.draft){options.draft.textContent=options.question?next.draftText:next.draftText||next.questionText;options.draft.scrollTop=options.draft.scrollHeight;}
      if(next.reason&&options.status)options.status.textContent+=' · '+(waitingReasons[reasonCode(next.reason)]||options.reasonText?.(reasonCode(next.reason))||'요청을 완료하지 못했습니다. 다시 질문해 주세요.');
      if(!connected){awaitingFresh=true;flow.pause();diagnostic('not_connected:'+(/^[A-Z_]{1,32}$/.test(connection)?connection:'UNKNOWN'),!keepalive);if(keepalive){if(panel)panel.hidden=false;if(options.status)options.status.textContent='노바 · 재연결 중';}return true;}
      awaitingFresh=false;
      if(options.target==='fold'&&next.grounding){
        if(!renderGrounding(next)){clearGrounding();flow.accept(null);if(options.answer)options.answer.textContent='검색 답변의 출처 표시를 확인할 수 없습니다.';options.diagnostic?.('focus_grounding_held',{});return true;}
        void flush();return true;
      }
      clearGrounding();
      try{flow.accept(next);}catch{return contractError();}
      flow.pause(paused||!!doc?.hidden);void flush();return true;
    }
    function visibility(){if(doc?.hidden){awaitingFresh=true;flow.pause();}else if(!awaitingFresh)flow.pause(paused);}
    function togglePause(){paused=!paused;flow.pause(paused||awaitingFresh||!!doc?.hidden);return paused;}
    function dispose(){disposed=true;clearQuestion();renderFallback(null);cancelClose();clearGrounding();receipts.clear();flow.dispose();if(panel)panel.hidden=true;}
    return {update,visibility,togglePause,replay:()=>flow.replay(),dismiss,reset,dispose,isActive:()=>!!current?.active&&dismissedActivation!==current.activationId||terminalVisible};
  }
  function receiptSender(host=globalThis){
    return async function(name,detail){
      const controller=new host.AbortController(),timer=host.setTimeout(()=>controller.abort(),4000);
      try{
        const response=await host.fetch('/api/assist/display/focus/rendered',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json','X-Display-Client':'1'},signal:controller.signal,
          body:JSON.stringify({event:name,serverInstanceId:detail.serverInstanceId,activationId:detail.activationId,turnId:detail.turnId,answerVersion:detail.answerVersion,renderReceiptTicket:detail.renderReceiptTicket})});
        if(!response.ok)throw Error('focus_receipt_unconfirmed');
      }finally{host.clearTimeout(timer);}
    };
  }
  return {decode,createProjection,receiptSender,CLOSE_REASONS,closeReasonText};
});
