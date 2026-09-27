(function(){
  'use strict';
  const $=id=>document.getElementById(id),params=new URLSearchParams(location.hash.slice(1));
  const id=params.get('session'),epoch=Number(params.get('epoch'));
  let stream=null,expires=null,pollTimer=null,closed=false,latestVersion=0;
  let pages=[],page=0,cardKind='',renderedVersion=0,captionExpires=null;
  const phaseAcks=new Map(),pendingAcks=new Set();
  function clearCaption(){clearTimeout(captionExpires);$('receiver-caption').textContent='';$('caption-label').textContent='';}
  function showPage(){if(!pages.length)return;$('receiver-kind').textContent=page===0?cardKind:'출처';$('receiver-text').textContent=pages[page];$('page-count').textContent=`${page+1} / ${pages.length}`;}
  function clear(text){clearTimeout(expires);pages=[];page=0;$('receiver-text').textContent='';$('receiver-request').textContent='';$('page-count').textContent='';$('receiver-status').textContent=text;}
  function stop(){closed=true;stream?.close();clearTimeout(pollTimer);clearCaption();clear('출력 종료');}
  $('receiver-stop').addEventListener('click',stop);window.addEventListener('pagehide',stop);
  if(!/^[a-f0-9-]{36}$/i.test(id||'')||!Number.isSafeInteger(epoch)||epoch<1){$('receiver-status').textContent='연결 정보 없음';return;}
  // The URL fragment is never sent in the page request or referrer.
  function render(s){
    if(closed)return;
    if(!s||typeof s!=='object'||!Number.isSafeInteger(s.version)||s.version<1)return;
    if(s.assistId!==id||s.epoch!==epoch||s.state!=='RUNNING'){stop();return;}
    if(s.version<latestVersion)return;
    const card=s.card;
    if(card!=null&&(typeof card!=='object'||typeof card.text!=='string'||
        !Number.isSafeInteger(card.expiresAt)||card.expiresAt<1))return;
    const caption=s.caption;
    if(caption!=null&&(typeof caption!=='object'||typeof caption.text!=='string'||caption.text.length>2048||
        !/^[A-Za-z0-9._:-]{1,80}$/.test(caption.utteranceId||'')||!Number.isSafeInteger(caption.revision)||caption.revision<0||
        typeof caption.isFinal!=='boolean'||!Number.isSafeInteger(caption.expiresAt)||caption.expiresAt<1||
        !Array.isArray(caption.words)||caption.words.length>256))return;
    latestVersion=s.version;
    clearCaption();
    if(caption&&caption.expiresAt>Date.now()){
      $('receiver-caption').textContent=caption.text;
      const speakers=[...new Set(caption.words.map(w=>w&&w.speaker).filter(n=>Number.isSafeInteger(n)&&n>=0&&n<100))];
      $('caption-label').textContent=(caption.isFinal?'확정 자막':'듣는 중')+(speakers.length===1?` · 화자 ${speakers[0]+1}`:speakers.length>1?' · 여러 화자':'');
      captionExpires=setTimeout(clearCaption,Math.max(0,caption.expiresAt-Date.now()));
      acknowledge(s.version,'caption_rendered',caption.expiresAt);
    }
    if(!card||card.expiresAt<=Date.now()){clear('문구 대기');return;}
    clearTimeout(expires);cardKind=({QUESTION:'질문',HINT:'힌트',SUGGESTION:'대화 힌트',ANSWER:'짧은 답변',STATUS:'요청 상태'})[card.kind]||'전달된 문구';
    const titles=(Array.isArray(card.sourceTitles)?card.sourceTitles:[]).filter(title=>typeof title==='string').slice(0,4);
    pages=card.kind==='STATUS'||(card.kind==='SUGGESTION'&&!titles.length)?[card.text]:[card.text,...(titles.length?titles:['첨부된 출처 없음'])];
    if(s.version!==renderedVersion){page=0;renderedVersion=s.version;}showPage();
    $('receiver-request').textContent=typeof card.requestId==='string'&&/^[A-Za-z0-9._:-]{1,128}$/.test(card.requestId)?`요청 ${card.requestId}`:'';
    $('receiver-status').textContent=card.kind==='STATUS'?'요청 상태 표시':'문구 표시';expires=setTimeout(()=>clear('표시 기한 종료'),Math.max(0,card.expiresAt-Date.now()));
    acknowledge(s.version,'rendered',card.expiresAt);
  }
  function acknowledge(version,phase,expiresAt){
    const key=phase+':'+version;
    if(version<=(phaseAcks.get(phase)||0)||pendingAcks.has(key))return;
    pendingAcks.add(key);
    // Only the receiving document acknowledges after a rendering opportunity.
    requestAnimationFrame(()=>requestAnimationFrame(async()=>{
      if(closed||version!==latestVersion||document.visibilityState==='hidden'||expiresAt<=Date.now()){pendingAcks.delete(key);return;}
      try{const res=await fetch(`/api/assist/sessions/${id}/ack`,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify({epoch,version,phase})});if(res.ok)phaseAcks.set(phase,Math.max(phaseAcks.get(phase)||0,version));else $('receiver-status').textContent='수신 확인 실패';}catch(_){$('receiver-status').textContent='수신 확인 전송 실패';}finally{pendingAcks.delete(key);}
    }));
  }
  if(location.protocol==='https:'){
    const client=window.InterviewCore.outputClientId();
    async function poll(){if(closed)return;const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),4000);
      try{const res=await fetch(`/api/assist/sessions/${id}/output/poll?epoch=${epoch}&client=${client}`,{credentials:'same-origin',cache:'no-store',signal:controller.signal});if(!res.ok)throw Error('poll-failed');render(await res.json());}
      catch(_){clearCaption();clear('연결 확인 중');}finally{clearTimeout(timeout);if(!closed)pollTimer=setTimeout(poll,1000);}}
    poll();
  }else{
    stream=new EventSource(`/api/assist/sessions/${id}/output?epoch=${epoch}`);
    stream.addEventListener('assist',event=>{try{render(JSON.parse(event.data));}catch(_){clearCaption();clear('상태 확인 실패');}});
    stream.onerror=()=>{clearCaption();clear('연결 확인 중');};
  }
  document.addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();stop();}else if((e.key==='ArrowLeft'||e.key==='ArrowRight'||(e.key==='Enter'&&document.activeElement===$('card')))&&pages.length){e.preventDefault();page=(page+(e.key==='ArrowLeft'?-1:1)+pages.length)%pages.length;showPage();}else if(e.key.startsWith('Arrow')){e.preventDefault();(document.activeElement===$('card')?$('receiver-stop'):$('card')).focus();}});
  $('card').focus();
})();
