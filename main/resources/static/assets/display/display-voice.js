(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory();else root.DisplayVoice=factory();})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  function createCapture({client,env=globalThis,onChange=()=>{},setTimer=setTimeout,clearTimer=clearTimeout,continuous=false,now=()=>performance.now(),deviceId=()=>'',segmentSeconds=()=>0}){
    const state={phase:'OFF',message:'음성 시작을 누른 동안만 서버로 전송합니다.',frames:0,bytes:0,level:0,reconnects:0,segments:0,events:[],sttPausedReason:null,droppedAudioMs:0,deviceLabel:'',permission:'not_requested',inputRate:null,audioContext:null,errorCode:null,lastFrameAt:null,lastSendAt:null};let current=null,completing=null,wanted=false,run=0,recoveryTimer=null;
    const standalone=()=>client.state.role==='STANDALONE';
    const supported=()=>!!(env.isSecureContext&&env.navigator?.mediaDevices?.getUserMedia&&env.AudioContext&&env.AudioWorkletNode);
    function report(phase,message){state.phase=phase;state.message=message;onChange(state);}
    function event(name,fields={}){state.events.push({event:name,at:Math.round(now()),...fields});if(state.events.length>12)state.events.shift();}
    const apiLimit=error=>/^(?:asr_(?:budget_[a-z_]+|quota_exceeded|rate_limited|auth_failed|audio_format_invalid)|display_rate_limited)$/.test(error?.message||'');
    const apiMessage='수음 유지 · 전사 API 제한으로 전사 대기 중 (대기 구간은 저장하지 않습니다).';
    async function pauseApi(m,error){
      m.apiPaused=true;m.reconnecting=false;m.plannedRenewal=false;m.queue.length=0;if(m.renewalQueue)m.renewalQueue.length=0;clearTimer(m.roll);
      state.sttPausedReason=error.message;event('STT_API_PAUSED',{reason:error.message});
      await client.endVoice({finish:true}).catch(()=>{});m.serverAttempted=false;
      if(m.closed)return;report('LISTENING',apiMessage);
      if(/rate_limited$/.test(error.message)&&state.reconnects<3)m.retry=setTimer(()=>{if(current===m&&!m.closed){m.apiPaused=false;void renew(m);}},Math.max(60000,error.retryAfterMs||60000));
    }
    function renewalMs(){const seconds=Number(segmentSeconds());return standalone()&&seconds>=5&&seconds<=60?seconds*1000:Math.max(5000,Math.min(540000,Number(client.state.audioRenewAfterMs||client.state.testStatus?.asr?.renewAfterMs)||540000));}
    async function stop(message='마이크를 중지했습니다. 진행 중인 답변도 취소됩니다.',failed=false,drain=false,rolling=false){
      if(!rolling){wanted=false;run++;clearTimer(recoveryTimer);}
      const m=current||completing;if(!m||drain&&m.finishing)return;current=null;event('MIC_SESSION_STOP',{reason:failed?(state.errorCode||'fatal_capture_error'):'user_stop'});m.closed=!drain;clearTimer(m.timer);clearTimer(m.watch);clearTimer(m.roll);clearTimer(m.permission);clearTimer(m.retry);if(!drain)m.queue.length=0;
      m.wake?.release().catch(()=>{});
      if(m.media&&!m.tracksStopped){m.tracksStopped=true;m.media.getTracks().forEach(t=>t.stop());}
      // Page suspension can delay AudioContext.close(). Stop the server without
      // waiting for audio graph cleanup; tracks and the worklet are already stopped.
      if(!drain){m.node?.port.postMessage('stop');m.node?.disconnect();if(m.context){m.contextClosed=true;void m.context.close().catch(()=>{});}}
      if(drain){completing=m;m.finishing=true;report('FINISHING','마이크를 끄고 마지막 전사를 기다립니다.');}
      let deadline;
      try{
        if(drain&&m.reconnecting){await m.renewalDone;if(m.closed)return;}
        if(drain&&m.node&&!m.apiPaused){
          const tail=new Promise(resolve=>{m.flushed=resolve;});m.node.port.postMessage('finish');
          await Promise.race([(async()=>{await tail;await pump(m);})(),new Promise((_,reject)=>{deadline=setTimer(()=>reject(Error('audio_drain_timeout')),5000);})]);
          if(m.closed)return;
        }
        if(m.serverAttempted){await client.endVoice(drain?{finish:true}:undefined);if(drain&&client.state.audioFinished!==true){message='마이크는 꺼졌지만 마지막 전사 완료는 확인되지 않았습니다.';failed=true;}}
      }catch{state.errorCode='audio_finish_unconfirmed';message='마이크는 꺼졌지만 마지막 전사 완료는 확인되지 않았습니다.';failed=true;await client.endVoice().catch(()=>{});}
      finally{clearTimer(deadline);m.closed=true;m.queue.length=0;if(m.renewalQueue)m.renewalQueue.length=0;m.node?.disconnect();if(m.context&&!m.contextClosed){m.contextClosed=true;void m.context.close().catch(()=>{});}if(completing===m)completing=null;}
      report(failed?'ERROR':'OFF',message);
    }
    function watchdog(m){if(m.closed)return;if(now()-m.lastFrame>5000&&!m.inputWaiting){m.inputWaiting=true;state.errorCode=null;event('audio_waiting',{reason:'input_gap'});report('WAITING','입력 대기 · 마지막 전사를 유지합니다.');}m.watch=setTimer(()=>watchdog(m),1000);}
    function waitForAudio(m,error){
      if(m.closed||!wanted)return;m.reconnecting=false;m.plannedRenewal=false;m.waiting=true;m.queue.length=0;if(m.renewalQueue)m.renewalQueue.length=0;
      clearTimer(m.roll);clearTimer(m.retry);event('audio_waiting',{reason:/^[a-z_]{1,64}$/.test(error?.message||'')?error.message:'transport'});
      report('WAITING','전사 연결 대기 · 마이크와 마지막 글자를 유지합니다.');
      // Limit automatic stream opens. After three failures, retain the mic and
      // wait for a fresh utterance after quiet/cooldown; never buffer that gap.
      if(state.reconnects>=3){m.retryExhausted=true;m.retryAfter=now()+30000;m.quietSince=null;m.retryOnSpeech=false;return;}
      m.retry=setTimer(()=>{if(current===m&&!m.closed){m.waiting=false;void renew(m);}},Math.min(30000,1000*Math.pow(2,Math.min(state.reconnects,5))));
    }
    const recoverable=error=>!['asr_disabled','paired_phone_required','event_owner_required','display_origin_required','stale_epoch','assist_not_found','audio-stopped'].includes(error?.message)&&
      (![400,401,403,404,409,413].includes(error?.status)||['capture_not_active','segment_not_ready','capture_active','audio-not-ready'].includes(error?.message));
    async function renew(m,planned=false){
      if(m.closed||m.reconnecting)return;m.reconnecting=true;m.plannedRenewal=planned;m.renewalQueue=[];if(!planned)m.queue.length=0;clearTimer(m.roll);
      if(!planned)state.reconnects++;
      let renewalDone;m.renewalDone=new Promise(resolve=>{renewalDone=resolve;});
      report(planned?'LISTENING':'RECONNECTING',planned?'전사 연결 갱신 중 · 이전 음성은 재전송하지 않습니다.':'음성 재연결 중 · 이전 음성은 재전송하지 않습니다.');
      try{
        if(planned)await(m.pending||Promise.resolve());if(m.closed)return;
        await client.endVoice({finish:true});if(m.closed)return;
        if(planned&&client.state.audioFinished!==true)throw Error('audio_finish_unconfirmed');
        await client.beginVoice({continuation:true});if(m.closed){await client.endVoice();return;}
        m.sequence=0;m.serverAttempted=true;m.apiPaused=false;m.waiting=false;m.retryExhausted=false;state.sttPausedReason=null;state.errorCode=null;state.reconnects=0;event('audio_resumed');state.segments++;m.queue.push(...m.renewalQueue);m.renewalQueue=[];m.reconnecting=false;m.plannedRenewal=false;
        await pump(m);if(!m.finishing&&!m.closed){m.roll=setTimer(()=>renew(m,true),renewalMs());report('LISTENING','수음 중 · 즉시 중지할 수 있습니다.');}
      }
      catch(error){if(continuous&&apiLimit(error)){await pauseApi(m,error);return;}if(continuous&&recoverable(error)){waitForAudio(m,error);return;}state.errorCode=/^[a-z_]{1,64}$/.test(error?.message||'')?error.message:'asr_reconnect_failed';await stop('전사 재연결에 실패했습니다. 오류 코드와 연결을 확인해 주세요.',true);}
      finally{m.renewalDone=null;renewalDone();}
    }
    function pump(m){if(m.busy||m.closed||m.reconnecting)return m.pending||Promise.resolve();m.busy=true;m.pending=(async()=>{
      try{while(m.queue.length&&!m.closed){const bytes=new Uint8Array(m.queue.shift());let binary='';for(const b of bytes)binary+=String.fromCharCode(b);
        await client.voiceChunk(m.sequence++,env.btoa(binary));bytes.fill(0);state.lastSendAt=Math.round(now());}}
      catch(error){if(continuous&&apiLimit(error)){await pauseApi(m,error);return;}if(m.finishing||m.plannedRenewal)throw error;if(current===m){state.errorCode=/^[a-z_]{1,64}$/.test(error?.message||'')?error.message:'audio_transport_failed';
        if(continuous&&!m.reconnecting&&recoverable(error)){waitForAudio(m,error);}
        else if(!m.reconnecting)await stop('음성 연결이 끊겼습니다. 상태를 확인하고 다시 시작해 주세요.',true);}}finally{m.busy=false;}})();return m.pending;
    }
    async function start(continuation=false,resuming=false){
      if(current||completing)return false;
      if(!resuming){wanted=true;run++;state.segments=0;state.reconnects=0;}else if(!wanted)return false;
      if(!supported()||!client.state.audioAvailable||(continuous&&!standalone()&&(client.state.role!=='PHONE'||!client.state.linked))){report('ERROR','휴대폰 연결, 마이크 지원과 전사 서버 설정을 확인해 주세요.');return false;}
      const m={queue:[],sequence:0,closed:false,busy:false,lastFrame:now()};current=m;state.frames=state.bytes=state.level=0;state.errorCode=null;state.permission='requesting';report('STARTING',resuming?'다음 전사 구간을 시작합니다.':'마이크 권한을 확인하고 있습니다.');
      try{
        // Permission can remain unanswered. Late permission resolution must release tracks.
        m.permission=setTimer(()=>{state.errorCode='microphone_permission_timeout';stop('마이크 권한 응답을 기다리다 중지했습니다. 권한을 확인하고 다시 시작해 주세요.',true);},15000);
        const requested=deviceId();
        m.media=await env.navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true,...(requested?{deviceId:{exact:requested}}:{})},video:false});clearTimer(m.permission);
        if(m.closed){m.media.getTracks().forEach(t=>t.stop());return false;}
        state.permission='granted';state.deviceLabel=m.media.getAudioTracks()[0]?.label||'장치명 미제공';
        m.context=new env.AudioContext();state.audioContext=m.context.state;m.context.onstatechange=()=>{if(m.closed)return;state.audioContext=m.context.state;if(m.context.state==='suspended')event('audio_context_suspended');};state.inputRate=m.context.sampleRate||null;await m.context.resume();await m.context.audioWorklet.addModule('/assets/display/pcm-worklet.js');if(m.closed)return false;
        m.serverAttempted=true;try{await client.beginVoice({continuation});}catch(error){if(continuous&&apiLimit(error))await pauseApi(m,error);else if(continuous&&recoverable(error))waitForAudio(m,error);else throw error;}if(m.closed){await client.endVoice();return false;}
        m.node=new env.AudioWorkletNode(m.context,'conversate-pcm');m.node.onprocessorerror=()=>stop('음성 입력이 중단됐습니다.',true);
        m.node.port.onmessage=e=>{if(e.data?.stopped){m.flushed?.();return;}if(m.closed||!(e.data?.pcm instanceof ArrayBuffer)||!e.data.pcm.byteLength)return;
          m.lastFrame=now();state.lastFrameAt=Math.round(m.lastFrame);if(m.inputWaiting){m.inputWaiting=false;state.errorCode=null;event('audio_resumed');report('LISTENING','수음 중 · 입력 재개');}state.frames++;state.bytes+=e.data.pcm.byteLength;
          const samples=new Int16Array(e.data.pcm);let peak=0;for(let i=0;i<samples.length;i++)peak=Math.max(peak,Math.abs(samples[i]));state.level=Math.round(peak/327.68);
          if(m.waiting){
            state.droppedAudioMs+=e.data.pcm.byteLength/32;
            if(m.retryExhausted){
              if(peak<128){if(m.quietSince==null)m.quietSince=now();if(now()-m.quietSince>=2000)m.retryOnSpeech=true;}
              else{if(m.retryOnSpeech&&now()>=m.retryAfter){m.retryExhausted=false;state.reconnects=0;m.waiting=false;void renew(m);}m.quietSince=null;m.retryOnSpeech=false;}
            }
            return;
          }
          if(m.apiPaused){state.droppedAudioMs+=e.data.pcm.byteLength/32;if(state.frames%4===0)report('LISTENING',apiMessage);return;}
          if(continuous&&!m.finishing&&state.frames%4===0)report(m.reconnecting&&!m.plannedRenewal?'RECONNECTING':'LISTENING',m.reconnecting?'전사 재연결 중':peak<128?'수음 중 · 무음 (오디오 유입 있음)':'수음 중 · 소리 감지');
          if(m.reconnecting){if(m.plannedRenewal){if(e.data.pcm.byteLength>7680){state.errorCode='audio_renewal_backpressure';void stop('오디오 형식을 확인해 주세요.',true);return;}if(m.renewalQueue.length>=128){state.droppedAudioMs+=m.renewalQueue.shift().byteLength/32;event('audio_waiting',{reason:'renewal_backpressure'});}m.renewalQueue.push(e.data.pcm);}return;}
          // At most 32 frames (7.68 s) absorb bounded transport/finalization latency; never replay sent PCM.
          if(e.data.pcm.byteLength>7680||!continuous&&m.queue.length>=32){state.errorCode='audio_transport_backpressure';stop('오디오 입력을 확인해 주세요.',true);return;}if(m.queue.length>=32){state.droppedAudioMs+=m.queue.shift().byteLength/32;event('audio_waiting',{reason:'transport_backpressure'});}m.queue.push(e.data.pcm);void pump(m).catch(()=>{});};
        m.media.getAudioTracks().forEach(t=>{t.onended=()=>{if(current===m){state.errorCode='microphone_device_ended';stop('마이크 연결이 종료됐습니다.',true);}};t.onmute=()=>{m.inputWaiting=true;state.errorCode=null;event('audio_waiting',{reason:'microphone_muted'});report('WAITING','입력 대기 · 마지막 전사를 유지합니다.');};t.onunmute=()=>{m.lastFrame=now();state.lastFrameAt=Math.round(m.lastFrame);m.inputWaiting=false;state.errorCode=null;event('audio_resumed');report('LISTENING','수음 중 · 입력 재개');};});
        const source=m.context.createMediaStreamSource(m.media),mute=m.context.createGain();mute.gain.value=0;source.connect(m.node).connect(mute).connect(m.context.destination);
        state.segments++;
        const seconds=Number(segmentSeconds());
        if(continuous){m.lastFrame=now();m.watch=setTimer(()=>watchdog(m),1000);if(!m.apiPaused&&!m.waiting&&client.state.testStatus?.asr?.provider!=='whisper')m.roll=setTimer(()=>renew(m,true),renewalMs());
          if(env.navigator.wakeLock)env.navigator.wakeLock.request('screen').then(lock=>{if(m.closed)lock.release();else m.wake=lock;}).catch(()=>{});}
        event('MIC_SESSION_START');report(m.waiting?'WAITING':'LISTENING',m.waiting?'전사 연결 대기 · 마이크와 마지막 글자를 유지합니다.':m.apiPaused?apiMessage:continuous?'수음 중 · 전사를 유지하며 필요한 때만 힌트를 표시합니다.':'듣고 있습니다 · 질문이 확정되면 답변합니다.');return true;
      }catch(error){if(error?.name==='NotAllowedError')state.permission='denied';state.errorCode=({NotAllowedError:'microphone_permission_denied',NotFoundError:'microphone_device_missing',NotReadableError:'microphone_device_busy',OverconstrainedError:'microphone_device_changed'})[error?.name]||(/^[a-z_]{1,64}$/.test(error?.message||'')?error.message:'microphone_start_failed');if(current===m)await stop(error?.name==='NotAllowedError'?'마이크 권한이 허용되지 않았습니다. 휴대폰 사이트 권한을 확인해 주세요.':'음성 입력을 준비하지 못했습니다. 오류 코드를 확인해 주세요.',true);return false;}
    }
    // Page visibility return: thaw a suspended audio graph and re-arm the wake
    // lock without dropping the mic, the queue, or the server segment.
    async function resume(){
      const m=current;if(!m||m.closed)return false;
      m.lastFrame=now();state.lastFrameAt=Math.round(m.lastFrame);
      if(m.context){
        state.audioContext=m.context.state;
        if(m.context.state==='suspended'){try{await m.context.resume();}catch{}state.audioContext=m.context.state;if(m.context.state==='running')event('audio_resumed',{reason:'context_resumed'});}
      }
      if(env.navigator?.wakeLock&&(m.wake==null||m.wake.released))env.navigator.wakeLock.request('screen').then(lock=>{if(m.closed)lock.release();else m.wake=lock;}).catch(()=>{});
      if(m.waiting&&!m.retry&&!m.retryExhausted)void renew(m);
      return m.context?m.context.state!=='suspended':true;
    }
    return {state,supported,start,stop,resume,finish:()=>stop('마지막 전사를 받고 마이크를 중지했습니다.',false,true),isActive:()=>current!==null};
  }
  return {createCapture};
});
