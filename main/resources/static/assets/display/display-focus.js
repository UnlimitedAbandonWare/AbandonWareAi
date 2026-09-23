(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory(require('./display-focus-flow.js'));else root.NovaFocus=factory(root.NovaFocusFlow);})(typeof globalThis!=='undefined'?globalThis:this,function(Flow){
  'use strict';
  const PHASES=new Set(['OFF','ARMED','WAKE_PREVIEW','LISTENING','THINKING','ANSWER_READY','PRESENTING','WAITING','SUSPENDED']);
  const LABELS={WAKE_PREVIEW:'호출 확인 중',LISTENING:'듣는 중',THINKING:'응답 준비 중',ANSWER_READY:'답변 표시 중',PRESENTING:'답변 표시 중',WAITING:'후속 질문 대기',SUSPENDED:'연결 확인 필요'};
  function decode(raw){
    if(raw==null)return null;
    if(typeof raw!=='object'||typeof raw.active!=='boolean'||!PHASES.has(raw.phase)||!Number.isSafeInteger(raw.stateVersion)||raw.stateVersion<0)throw Error('invalid_focus');
    for(const field of ['serverInstanceId','activationId','turnId'])if(typeof raw[field]!=='string'||raw[field].length>128)throw Error('invalid_focus');
    for(const [field,max] of [['draftText',2000],['questionText',2000],['answerText',8000]])if(typeof raw[field]!=='string'||Array.from(raw[field]).length>max)throw Error('invalid_focus');
    if(!Number.isSafeInteger(raw.answerVersion)||raw.answerVersion<0||!Number.isFinite(raw.idleRemainingMs)||raw.idleRemainingMs<0)throw Error('invalid_focus');
    if(raw.renderTarget!=null&&!['lens','fold'].includes(raw.renderTarget))throw Error('invalid_focus');
    if(raw.renderReceiptTicket!=null&&raw.renderReceiptTicket!==''&&!/^[a-f0-9]{64}$/.test(raw.renderReceiptTicket))throw Error('invalid_focus');
    for(const field of ['answerTruncated','hasMoreOnFold'])if(raw[field]!=null&&typeof raw[field]!=='boolean')throw Error('invalid_focus');
    return {...raw,renderReceiptTicket:raw.renderReceiptTicket||null,presentation:Flow.settings(raw.presentation)};
  }
  function createProjection(options){
    const host=options.host||globalThis,doc=options.document||host.document,panel=options.panel;
    let current=null,server='',version=-1,paused=false,awaitingFresh=false,disposed=false;
    const receipts=new Map();
    async function flush(){
      for(const [key,entry] of receipts){
        if(entry.sending||entry.done||entry.attempts>=2||doc?.hidden||!current||entry.detail.activationId!==current.activationId||entry.detail.turnId!==current.turnId)continue;
        if(entry.name==='presentation_done'&&![...receipts.values()].some(first=>first.name==='first_visible'&&first.done&&first.detail.turnId===entry.detail.turnId&&first.detail.answerVersion===entry.detail.answerVersion))continue;
        entry.sending=true;entry.attempts++;
        try{await options.receipt?.(entry.name,entry.detail);entry.done=true;}catch{}finally{entry.sending=false;}
      }
    }
    const flow=Flow.createFlow({host,element:options.answer,requestFrame:options.requestFrame,cancelFrame:options.cancelFrame,fitsLine:options.fitsLine,onEvent(name,detail){
      // The ticket is passed only to the receipt request, never to diagnostics.
      options.diagnostic?.(name,{answerVersion:detail.answerVersion,mode:detail.mode});
      if((name==='first_visible'||name==='presentation_done')&&current?.renderTarget===options.target&&detail.renderReceiptTicket){
        const key=[detail.activationId,detail.turnId,detail.answerVersion,name].join('/');
        if(!receipts.has(key))receipts.set(key,{name,detail,attempts:0,sending:false,done:false});
        while(receipts.size>4)receipts.delete(receipts.keys().next().value);void flush();
      }
    }});
    function update(raw,connected=true){
      if(disposed)return false;
      let next;try{next=decode(raw);}catch{
        options.diagnostic?.('focus_contract',{});awaitingFresh=true;flow.pause();
        if(panel)panel.hidden=!current?.active||!connected;
        return !!current?.active;
      }
      if(next&&server===next.serverInstanceId&&next.stateVersion<version)return !!current?.active;
      if(next){server=next.serverInstanceId;version=next.stateVersion;}
      current=next;
      if(panel)panel.hidden=!next?.active||!connected;
      if(!next?.active){flow.accept(null);receipts.clear();return false;}
      if(options.status)options.status.textContent='노바 · '+(LABELS[next.phase]||'대화 중')+(next.hasMoreOnFold?' · 전문은 폴드 기록에서':'');
      if(options.draft)options.draft.textContent=next.draftText||next.questionText;
      if(!connected){awaitingFresh=true;flow.pause();return true;}
      awaitingFresh=false;
      try{flow.accept(next);}catch{flow.pause();if(panel)panel.hidden=true;options.diagnostic?.('focus_contract',{});return false;}
      flow.pause(paused||!!doc?.hidden);void flush();return true;
    }
    function visibility(){if(doc?.hidden){awaitingFresh=true;flow.pause();}else if(!awaitingFresh)flow.pause(paused);}
    function togglePause(){paused=!paused;flow.pause(paused||awaitingFresh||!!doc?.hidden);return paused;}
    function dispose(){disposed=true;receipts.clear();flow.dispose();if(panel)panel.hidden=true;}
    return {update,visibility,togglePause,replay:()=>flow.replay(),dispose,isActive:()=>!!current?.active};
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
  return {decode,createProjection,receiptSender};
});
