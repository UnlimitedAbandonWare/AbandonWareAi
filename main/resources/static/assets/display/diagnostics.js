(function(root,factory){
  const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;
  else{root.DisplayDiagnostics=api;api.mount(root.document);}
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  function createObserver({fetchImpl=globalThis.fetch.bind(globalThis),setTimer=setTimeout,clearTimer=clearTimeout,onData=()=>{},onState=()=>{}}={}){
    let active=false,timer,request,generation=0;
    function stop(){active=false;generation++;clearTimer(timer);request?.abort();request=null;onData(null);onState('OFF');}
    async function tick(stamp){
      if(!active||stamp!==generation)return;
      const own=new AbortController();request=own;
      const deadline=setTimer(()=>own.abort(),5000);
      try{
        const response=await fetchImpl('/api/diagnostics/display?enabled=true',{credentials:'same-origin',cache:'no-store',headers:{'X-Display-Client':'1'},signal:own.signal});
        if(!response.ok)throw Error(response.status===401||response.status===403?'DENIED':response.status===404?'NO_SESSION':'UNAVAILABLE');
        const data=await response.json();if(!active||stamp!==generation)return;
        onData(data);onState('ON');timer=setTimer(()=>tick(stamp),2000);
      }catch(error){if(active&&stamp===generation){stop();onState(['DENIED','NO_SESSION'].includes(error.message)?error.message:'UNAVAILABLE');}}
      finally{clearTimer(deadline);if(request===own)request=null;}
    }
    function start(){if(active)return;active=true;onState('STARTING');void tick(++generation);}
    return{start,stop,isActive:()=>active};
  }
  function mount(document){
    const button=document.getElementById('observe'),output=document.getElementById('diagnostic-data');
    const labels={audioState:'수음 상태',audioChunks:'서버 수신 오디오 청크',audioBytes:'서버 수신 오디오 바이트',lastAudioReceivedAt:'마지막 오디오 수신',lastTranscriptReceivedAt:'마지막 전사 수신',reconnects:'전사 재연결',decision:'표시 판단',reason:'결과 코드',processingMs:'처리 시간 ms',searchResults:'검색 결과 수',embeddingModel:'임베딩 모델',observedAt:'관측 시각',audio:'STT',pipeline:'RAG / LLM'};
    const observer=createObserver({onData(data){output.replaceChildren();if(!data)return;
      for(const [key,label]of Object.entries(labels)){const value=data[key];if(value===undefined)continue;const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=label;dd.textContent=typeof value==='object'?JSON.stringify(value):String(value);output.append(dt,dd);}},
      onState(state){document.getElementById('diagnostic-status').textContent=({OFF:'진단 꺼짐',STARTING:'권한 확인 중',ON:'진단 조회 중',DENIED:'개발자 권한을 확인해 주세요.',NO_SESSION:'이 브라우저에 허가된 세션이 없습니다.',UNAVAILABLE:'진단을 조회하지 못했습니다.'})[state];button.textContent=['STARTING','ON'].includes(state)?'진단 끄기':'진단 켜기';}});
    button.onclick=()=>observer.isActive()?observer.stop():observer.start();
    globalThis.addEventListener('pagehide',()=>observer.stop());
  }
  return{createObserver,mount};
});
