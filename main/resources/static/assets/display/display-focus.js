(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory(require('./display-focus-flow.js'));else root.NovaFocus=factory(root.NovaFocusFlow);})(typeof globalThis!=='undefined'?globalThis:this,function(Flow){
  'use strict';
  const PHASES=new Set(['OFF','ARMED','WAKE_PREVIEW','LISTENING','SNAPSHOT','THINKING','ANSWER_READY','PRESENTING','WAITING','SUSPENDED']);
  const LABELS={WAKE_PREVIEW:'호출 확인 중',LISTENING:'듣는 중',SNAPSHOT:'사진 촬영 중',THINKING:'응답 준비 중',ANSWER_READY:'답변 표시 중',PRESENTING:'답변 표시 중',WAITING:'후속 질문 대기',SUSPENDED:'연결 확인 필요'};
  function decode(raw){
    if(raw==null)return null;
    if(typeof raw!=='object'||typeof raw.active!=='boolean'||!PHASES.has(raw.phase)||!Number.isSafeInteger(raw.stateVersion)||raw.stateVersion<0)throw Error('invalid_focus');
    for(const field of ['serverInstanceId','activationId','turnId'])if(typeof raw[field]!=='string'||raw[field].length>128)throw Error('invalid_focus');
    for(const [field,max] of [['draftText',2000],['questionText',2000],['answerText',8000]])if(typeof raw[field]!=='string'||Array.from(raw[field]).length>max)throw Error('invalid_focus');
    if(!Number.isSafeInteger(raw.answerVersion)||raw.answerVersion<0||!Number.isFinite(raw.idleRemainingMs)||raw.idleRemainingMs<0)throw Error('invalid_focus');
    if(raw.renderTarget!=null&&!['lens','fold'].includes(raw.renderTarget))throw Error('invalid_focus');
    if(raw.renderReceiptTicket!=null&&raw.renderReceiptTicket!==''&&!/^[a-f0-9]{64}$/.test(raw.renderReceiptTicket))throw Error('invalid_focus');
    for(const field of ['answerTruncated','hasMoreOnFold'])if(raw[field]!=null&&typeof raw[field]!=='boolean')throw Error('invalid_focus');
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
    const receipts=new Map();
    async function flush(){
      for(const [key,entry] of receipts){
        if(entry.sending||entry.done||entry.attempts>=2||doc?.hidden||!current||entry.detail.activationId!==current.activationId||entry.detail.turnId!==current.turnId)continue;
        if(entry.name==='presentation_done'&&![...receipts.values()].some(first=>first.name==='first_visible'&&first.done&&first.detail.turnId===entry.detail.turnId&&first.detail.answerVersion===entry.detail.answerVersion))continue;
        entry.sending=true;entry.attempts++;
        try{await options.receipt?.(entry.name,entry.detail);entry.done=true;}catch{}finally{entry.sending=false;}
      }
    }
    const flow=Flow.createFlow({host,element:options.answer,retainAfterPresentation:options.target==='lens',requestFrame:options.requestFrame,cancelFrame:options.cancelFrame,fitsLine:options.fitsLine,onEvent(name,detail){
      // The ticket is passed only to the receipt request, never to diagnostics.
      options.diagnostic?.(name,{answerVersion:detail.answerVersion,mode:detail.mode});
      if((name==='first_visible'||name==='presentation_done')&&current?.renderTarget===options.target&&detail.renderReceiptTicket){
        const key=[detail.activationId,detail.turnId,detail.answerVersion,name].join('/');
        if(!receipts.has(key))receipts.set(key,{name,detail,attempts:0,sending:false,done:false});
        while(receipts.size>4)receipts.delete(receipts.keys().next().value);void flush();
      }
    }});
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
    function update(raw,connected=true){
      if(disposed)return false;
      let next;try{next=decode(raw);}catch{
        clearGrounding();
        options.diagnostic?.('focus_contract',{});awaitingFresh=true;flow.pause();
        if(panel)panel.hidden=!current?.active||!connected;
        return !!current?.active;
      }
      if(next&&server===next.serverInstanceId&&next.stateVersion<version)return !!current?.active;
      if(next){server=next.serverInstanceId;version=next.stateVersion;}
      current=next;
      if(panel)panel.hidden=!next?.active||!connected;
      if(!next?.active){clearGrounding();flow.accept(null);receipts.clear();return false;}
      if(options.status)options.status.textContent='노바 · '+(LABELS[next.phase]||'대화 중')+(next.grounding?' · 검색 근거':next.hasMoreOnFold?' · 긴 응답 일부 표시':'');
      if(options.draft)options.draft.textContent=next.draftText||next.questionText;
      if(!connected){awaitingFresh=true;flow.pause();return true;}
      awaitingFresh=false;
      if(options.target==='fold'&&next.grounding){
        if(!renderGrounding(next)){clearGrounding();flow.accept(null);if(options.answer)options.answer.textContent='검색 답변의 출처 표시를 확인할 수 없습니다.';options.diagnostic?.('focus_grounding_held',{});return true;}
        void flush();return true;
      }
      clearGrounding();
      try{flow.accept(next);}catch{flow.pause();if(panel)panel.hidden=true;options.diagnostic?.('focus_contract',{});return false;}
      flow.pause(paused||!!doc?.hidden);void flush();return true;
    }
    function visibility(){if(doc?.hidden){awaitingFresh=true;flow.pause();}else if(!awaitingFresh)flow.pause(paused);}
    function togglePause(){paused=!paused;flow.pause(paused||awaitingFresh||!!doc?.hidden);return paused;}
    function dispose(){disposed=true;clearGrounding();receipts.clear();flow.dispose();if(panel)panel.hidden=true;}
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
