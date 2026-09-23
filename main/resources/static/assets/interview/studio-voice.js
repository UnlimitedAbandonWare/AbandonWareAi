(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.StudioVoice=api;})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  function createVoice({Recognition,onTranscript=()=>{},onState=()=>{}}={}){
    let current=null,generation=0;
    function cancel(){generation++;const old=current;current=null;if(old){try{old.abort();}catch(_){}onState('idle');}}
    function start(){
      if(!Recognition||current)return false;
      const epoch=++generation;let recognition;
      try{recognition=new Recognition();}catch(_){onState('unavailable');return false;}
      current=recognition;recognition.lang='ko-KR';recognition.continuous=false;recognition.interimResults=false;
      recognition.onresult=event=>{
        if(epoch!==generation||current!==recognition)return;
        const parts=[];for(let i=event.resultIndex||0;i<event.results.length;i++)if(event.results[i].isFinal)parts.push(event.results[i][0].transcript);
        const value=parts.join(' ').trim();if(value)onTranscript(Array.from(value).slice(0,2000).join(''));
      };
      recognition.onerror=event=>{if(epoch!==generation)return;current=null;generation++;onState(['not-allowed','service-not-allowed'].includes(event.error)?'denied':'unavailable');};
      recognition.onend=()=>{if(epoch!==generation)return;current=null;generation++;onState('idle');};
      try{onState('listening');recognition.start();return true;}catch(_){current=null;generation++;onState('unavailable');return false;}
    }
    return {supported:Boolean(Recognition),start,cancel};
  }
  return {createVoice};
});
