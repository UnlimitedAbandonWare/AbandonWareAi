(function(){
  'use strict';
  const $=id=>document.getElementById(id);
  let captionKey='',pending='',acked='';
  const allowed=new Set(['ANSWER','SUGGESTION','TERM','PERSON','CONCEPT','BIO','FACT','RAG','CUE','RAG_CUE','API_DIRECT']);
  function render(s){
    const caption=s.connection==='READY'?s.caption:null;
    const hint=s.connection==='READY'&&allowed.has(s.hint?.kind)?s.hint:null;
    $('caption-text').textContent=caption?.text||'';
    $('hint-text').textContent=hint?.text||'';
    $('evidence-text').textContent=hint?(hint.sourceTitles||[]).slice(0,2).join(' · '):'';
    const key=caption?[s.epoch,caption.utteranceId,caption.revision,caption.isFinal].join(':'):'';
    captionKey=key;
    if(key&&key!==acked&&key!==pending&&document.visibilityState==='visible'){
      pending=key;const version=s.version;
      requestAnimationFrame(()=>requestAnimationFrame(()=>{
        if(captionKey!==key||document.visibilityState!=='visible'){pending='';return;}
        client.acknowledge(version,'caption_rendered').then(()=>{acked=key;}).catch(()=>{}).finally(()=>{pending='';});
      }));
    }
  }
  const client=window.DisplayConversate.createClient({transcription:true,lens:true,onChange:render});
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'){client.pause();client.start();}else client.pause();});
  window.addEventListener('online',()=>{client.pause();client.start();});
  window.addEventListener('pagehide',()=>client.dispose());
  window.addEventListener('pageshow',event=>{if(event.persisted)client.start();});
  client.start();
})();
