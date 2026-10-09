(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory();else root.DisplayVoice=factory();})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  function createCapture({client,env=globalThis,onChange=()=>{},setTimer=setTimeout,clearTimer=clearTimeout,continuous=false,now=()=>performance.now(),deviceId=()=>'',onDeviceFallback=()=>{},segmentSeconds=()=>0,sttPolicy=()=>null,autoVoiceConsent=()=>false}){
    const state={phase:'OFF',message:'음성 시작을 누른 동안만 서버로 전송합니다.',frames:0,bytes:0,level:0,reconnects:0,segments:0,events:[],sttPausedReason:null,droppedAudioMs:0,deviceLabel:'',permission:'not_requested',inputRate:null,audioContext:null,errorCode:null,errorStage:null,lastFrameAt:null,lastSendAt:null};let current=null,completing=null,wanted=false,run=0,recoveryTimer=null;
    const standalone=()=>client.state.role==='STANDALONE';
    const supported=()=>!!(env.isSecureContext&&env.navigator?.mediaDevices?.getUserMedia&&env.AudioContext&&env.AudioWorkletNode);
    function report(phase,message){state.phase=phase;state.message=message;onChange(state);}
    function event(name,fields={}){state.events.push({event:name,at:Math.round(now()),...fields});if(state.events.length>12)state.events.shift();}
    function diagnostic(error,stage,m){
      const errorCode=/^[a-z_][a-z0-9_]{0,63}$/.test(error?.message||'')?error.message:error?.message==='display-timeout'?'display_timeout':state.errorCode||'audio_transport_failed';
      const fields={stage,httpStatus:Number.isInteger(error?.status)?error.status:0,epoch:client.state.epoch||0,
        producerMismatch:error?.message==='event_owner_required'||client.state.testStatus?.relay?.eventOwner==='OTHER CLIENT',
        lastFrameAgeMs:state.frames&&m?Math.max(0,Math.min(600000,Math.round(now()-m.lastFrame))):null,errorCode};
      event('capture_error',fields);try{void client.captureDiagnostic?.(fields)?.catch?.(()=>{});}catch{}
    }
    const apiLimit=error=>/^(?:asr_(?:budget_[a-z_]+|quota_exceeded|rate_limited|auth_failed|audio_format_invalid)|display_rate_limited)$/.test(error?.message||'');
    const apiMessage='수음 유지 · 전사 API 제한으로 전사 대기 중 (대기 구간은 저장하지 않습니다).';
    async function pauseApi(m,error){
      if(current!==m||m.closed||!wanted)return;
      m.apiPaused=true;m.reconnecting=false;m.plannedRenewal=false;m.serverReady=false;wakePump(m);discard(m,'api_paused');clearTimer(m.roll);
      state.sttPausedReason=error.message;event('STT_API_PAUSED',{reason:error.message});
      await client.endVoice({finish:true}).catch(()=>{});m.serverAttempted=false;
      if(current!==m||m.closed||!wanted)return;report('LISTENING',apiMessage);
      if(/rate_limited$/.test(error.message)&&state.reconnects<3)m.retry=setTimer(()=>{if(current===m&&!m.closed){m.apiPaused=false;void renew(m);}},Math.max(60000,error.retryAfterMs||60000));
    }
    // Only unsent PCM lives here. Ambiguous in-flight frames are never replayed.
    function wakePump(m){clearTimer(m.pace);m.pace=null;m.paceResolve?.();m.paceResolve=null;}
    function drop(m,entry,reason){const bytes=new Uint8Array(entry.pcm);state.droppedAudioMs+=bytes.byteLength/32;m.queuedBytes-=bytes.byteLength;bytes.fill(0);event('audio_gap',{reason});}
    function discard(m,reason){while(m.queue.length)drop(m,m.queue.shift(),reason);}
    function trim(m){while(m.queue.length&&(now()-m.queue[0].at>15000||m.queuedBytes>480000))drop(m,m.queue.shift(),'buffer_expired');}
    function bindTracks(m){
      m.media.getAudioTracks().forEach(t=>{
        t.onended=()=>{if(current!==m||m.closed)return;if(continuous)void reacquire(m);else{state.errorCode='microphone_device_ended';void stop('마이크 연결이 종료됐습니다.',true);}};
        t.onmute=()=>{if(current!==m||m.closed)return;m.inputWaiting=true;event('audio_waiting',{reason:'microphone_muted'});report('WAITING','입력 대기 · 마지막 전사를 유지합니다.');};
        t.onunmute=()=>{if(current!==m||m.closed)return;m.lastFrame=now();state.lastFrameAt=Math.round(m.lastFrame);m.inputWaiting=false;state.errorCode=null;event('audio_resumed');report('LISTENING','수음 중 · 입력 재개');};
      });
    }
    async function permissionStatus(m){
      try{const status=await env.navigator.permissions?.query({name:'microphone'});if(status&&current===m&&!m.closed&&!m.permissionStatus){
        m.permissionStatus=status;m.permissionChanged=()=>{if(current===m&&status.state!=='granted'){state.permission=status.state;state.errorCode='microphone_permission_revoked';void stop('마이크 권한이 해제되어 수음을 중지했습니다.',true);}};
        status.addEventListener?.('change',m.permissionChanged);
      }return status;}catch{return null;}
    }
    async function reacquire(m){
      if(m.reacquiring)return m.reacquiring;
      if(current!==m||m.closed||!wanted)return false;
      m.reacquiring=(async()=>{
        // Never prompt for permission automatically after a device/permission loss.
        const permission=await permissionStatus(m);if(current!==m||m.closed||!wanted)return false;
        if(permission?.state!=='granted'){state.permission=permission?.state||'unknown';state.errorCode='microphone_permission_required';await stop('마이크 권한을 확인하고 수음을 다시 켜 주세요.',true);return false;}
        report('WAITING','입력 장치 복구 중 · 수음 설정을 유지합니다.');
        let media;
        try{media=await env.navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true,...(m.requestedDevice?{deviceId:{exact:m.requestedDevice}}:{})},video:false});
          if(current!==m||m.closed||!wanted){media.getTracks().forEach(t=>t.stop());return false;}
          m.source?.disconnect?.();m.media.getTracks().forEach(t=>{t.onended=t.onmute=t.onunmute=null;t.stop();});
          m.media=media;m.source=m.context.createMediaStreamSource(media);m.source.connect(m.node);bindTracks(m);
          state.deviceLabel=media.getAudioTracks()[0]?.label||'장치명 미제공';m.inputWaiting=false;m.lastFrame=now();event('audio_resumed',{reason:'input_reacquired'});
          report(m.waiting?'WAITING':'LISTENING','입력 장치를 복구했습니다.');return true;
        }catch(error){media?.getTracks().forEach(t=>t.stop());if(current===m&&!m.closed){state.errorCode=error?.name==='NotAllowedError'?'microphone_permission_denied':'microphone_device_missing';await stop('입력 장치를 복구하지 못했습니다. 마이크를 확인해 주세요.',true);}return false;}
      })().finally(()=>{m.reacquiring=null;});return m.reacquiring;
    }
    async function deviceChanged(){
      const m=current;if(!m||m.closed)return false;const track=m.media?.getAudioTracks()[0];if(!track)return false;
      if(track.readyState==='ended')return reacquire(m);
      const id=track.getSettings?.().deviceId;if(!id||!env.navigator.mediaDevices.enumerateDevices)return false;
      let devices;try{devices=await env.navigator.mediaDevices.enumerateDevices();}catch{return false;}
      return devices.some(d=>d.kind==='audioinput'&&d.deviceId===id)?false:reacquire(m);
    }
    function renewalMs(){const seconds=Number(segmentSeconds());return standalone()&&seconds>=5&&seconds<=60?seconds*1000:Math.max(5000,Math.min(540000,Number(client.state.audioRenewAfterMs||client.state.testStatus?.asr?.renewAfterMs)||540000));}
    async function stop(message='마이크를 중지했습니다. 진행 중인 답변도 취소됩니다.',failed=false,drain=false,rolling=false){
      if(!rolling){wanted=false;run++;clearTimer(recoveryTimer);}
      const m=current||completing;if(!m||drain&&m.finishing)return;current=null;completing=m;event('MIC_SESSION_STOP',{reason:failed?(state.errorCode||'fatal_capture_error'):'user_stop',...(state.errorStage?{stage:state.errorStage}:{})});m.closed=!drain;clearTimer(m.timer);clearTimer(m.watch);clearTimer(m.roll);clearTimer(m.permission);clearTimer(m.retry);if(!drain){wakePump(m);discard(m,'capture_stopped');}m.permissionStatus?.removeEventListener?.('change',m.permissionChanged);
      m.wake?.release().catch(()=>{});
      if(m.media&&!m.tracksStopped){m.tracksStopped=true;m.media.getTracks().forEach(t=>{t.onended=t.onmute=t.onunmute=null;t.stop();});}
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
        if(m.serverAttempted){await client.endVoice({...drain?{finish:true}:{},autoVoiceStop:!rolling});if(drain&&!client.state.autoVoiceSettings?.modeEnabled&&client.state.audioFinished!==true){message='마이크는 꺼졌지만 마지막 전사 완료는 확인되지 않았습니다.';failed=true;}}
      }catch{state.errorCode='audio_finish_unconfirmed';message='마이크는 꺼졌지만 마지막 전사 완료는 확인되지 않았습니다.';failed=true;await client.endVoice().catch(()=>{});}
      finally{clearTimer(deadline);m.closed=true;wakePump(m);discard(m,'capture_stopped');m.node?.disconnect();if(m.context&&!m.contextClosed){m.contextClosed=true;void m.context.close().catch(()=>{});}if(completing===m)completing=null;}
      report(failed?'ERROR':'OFF',message);
    }
    function watchdog(m){if(m.closed)return;if(now()-m.lastFrame>5000&&!m.inputWaiting){m.inputWaiting=true;state.errorCode=null;event('audio_waiting',{reason:'input_gap'});report('WAITING','입력 대기 · 마지막 전사를 유지합니다.');}m.watch=setTimer(()=>watchdog(m),1000);}
    function waitForAudio(m,error){
      if(m.closed||!wanted)return;m.reconnecting=false;m.plannedRenewal=false;m.serverReady=false;m.waiting=true;trim(m);
      clearTimer(m.roll);clearTimer(m.retry);event('audio_waiting',{reason:/^[a-z_]{1,64}$/.test(error?.message||'')?error.message:'transport'});
      report('WAITING','전사 연결 대기 · 최근 15초 음성만 임시 보관합니다.');
      // Transport recovery depends on intent and backoff, never acoustic silence.
      const delay=Math.min(30000,1000*Math.pow(2,Math.min(state.reconnects,5)))+Math.floor(Math.random()*251);
      m.retry=setTimer(()=>{m.retry=null;if(current!==m||m.closed||!wanted)return;
        if(env.navigator.onLine===false){waitForAudio(m,error);return;}void renew(m);
      },delay);
    }
    const recoverable=error=>!['asr_disabled','paired_phone_required','event_owner_required','display_origin_required','assist_not_found'].includes(error?.message)&&
      (![400,401,403,404,409,413].includes(error?.status)||['capture_not_active','segment_not_ready','capture_active','audio-not-ready','stale_epoch','audio-stopped'].includes(error?.message));
    async function renew(m,planned=false,reconnect=false){
      if(m.closed||m.reconnecting)return;m.reconnecting=true;m.plannedRenewal=planned;m.serverReady=false;wakePump(m);clearTimer(m.roll);clearTimer(m.retry);m.retry=null;
      if(!planned)state.reconnects++;
      let renewalDone;m.renewalDone=new Promise(resolve=>{renewalDone=resolve;});
      report(planned?'LISTENING':'RECONNECTING',planned?'전사 연결 갱신 중 · 이전 음성은 재전송하지 않습니다.':'음성 재연결 중 · 이전 음성은 재전송하지 않습니다.');
      try{
        await(m.pending||Promise.resolve());if(m.closed||!wanted)return;
        await client.endVoice({finish:true});if(m.closed)return;
        if(planned&&client.state.audioFinished!==true)throw Error('audio_finish_unconfirmed');
        if(reconnect)await client.reconnect({preserveSession:true});if(m.closed||!wanted)return;
        m.serverAttempted=true;await client.beginVoice({continuation:true,sttPolicy:m.sttPolicy,autoVoiceConsent:m.autoVoiceConsent});if(m.closed)return;
        m.sequence=0;m.paceAt=now();m.paceCredit=0;m.serverAttempted=true;m.apiPaused=false;m.waiting=false;m.serverReady=true;state.sttPausedReason=null;state.errorCode=null;state.reconnects=0;event('audio_resumed');state.segments++;trim(m);m.reconnecting=false;m.plannedRenewal=false;
        void pump(m).catch(()=>{});if(!m.finishing&&!m.closed){m.roll=setTimer(()=>renew(m,true),renewalMs());report('LISTENING','수음 중 · 즉시 중지할 수 있습니다.');}
      }
      catch(error){if(m.closed||current!==m&&!(completing===m&&m.finishing))return;diagnostic(error,'server_begin',m);if(!m.finishing&&continuous&&apiLimit(error)){await pauseApi(m,error);return;}if(!m.finishing&&continuous&&recoverable(error)){waitForAudio(m,error);return;}state.errorCode=/^[a-z_]{1,64}$/.test(error?.message||'')?error.message:'asr_reconnect_failed';await stop('전사 재연결에 실패했습니다. 오류 코드와 연결을 확인해 주세요.',true);}
      finally{m.renewalDone=null;renewalDone();}
    }
    function pump(m){if(m.busy||m.closed||m.reconnecting||!m.serverReady)return m.pending||Promise.resolve();m.busy=true;m.pending=(async()=>{
      try{while(m.queue.length&&!m.closed&&!m.reconnecting&&m.serverReady){
        trim(m);if(!m.queue.length)break;const batched=typeof client.voiceBatch==='function';
        if(batched){
          const stamp=now();m.paceCredit=Math.min(61440,(m.paceCredit||0)+Math.max(0,stamp-(m.paceAt??stamp))*40);m.paceAt=stamp;
          const needed=m.queue.slice(0,8).reduce((bytes,entry)=>bytes+entry.pcm.byteLength,0);
          if(m.paceCredit<needed){
            await new Promise(resolve=>{m.paceResolve=resolve;m.pace=setTimer(resolve,(needed-m.paceCredit)/40);});m.pace=null;m.paceResolve=null;
            continue;
          }
        }
        const entries=m.queue.splice(0,batched?8:1),buffers=entries.map(entry=>new Uint8Array(entry.pcm));let byteCount=0;
        const frames=buffers.map(bytes=>{byteCount+=bytes.byteLength;m.queuedBytes-=bytes.byteLength;let binary='';for(const b of bytes)binary+=String.fromCharCode(b);return{sequence:m.sequence++,pcm:env.btoa(binary)};});
        // PCM is 32 bytes/ms. At most 1.25x real time can leave this queue.
        if(batched)m.paceCredit-=byteCount;
        try{if(batched)await client.voiceBatch(frames);else await client.voiceChunk(frames[0].sequence,frames[0].pcm);state.lastSendAt=Math.round(now());}
        catch(error){state.droppedAudioMs+=byteCount/32;event('audio_gap',{reason:'delivery_unconfirmed'});throw error;}
        finally{for(const bytes of buffers)bytes.fill(0);}
      }}
      catch(error){diagnostic(error,'transport',m);if(continuous&&apiLimit(error)){await pauseApi(m,error);return;}if(m.finishing||m.plannedRenewal)throw error;if(current===m){state.errorCode=/^[a-z_]{1,64}$/.test(error?.message||'')?error.message:'audio_transport_failed';
        if(continuous&&!m.reconnecting&&recoverable(error)){waitForAudio(m,error);}
        else if(!m.reconnecting)await stop('음성 연결이 끊겼습니다. 상태를 확인하고 다시 시작해 주세요.',true);}}finally{m.busy=false;}})();return m.pending;
    }
    async function start(continuation=false,resuming=false){
      if(current||completing)return false;
      if(client.state.autoVoiceSettings?.modeEnabled&&!autoVoiceConsent()){state.errorCode='auto_voice_consent_required';report('ERROR','먼저 안내와 동의를 확인하고 대화 준비를 눌러 주세요.');return false;}
      if(!resuming){wanted=true;run++;state.segments=0;state.reconnects=0;}else if(!wanted)return false;
      if(!supported()||!client.state.audioAvailable||(continuous&&!standalone()&&(client.state.role!=='PHONE'||!client.state.linked))){state.errorCode=null;state.errorStage='precheck';diagnostic(Error('capture_precheck_failed'),'precheck');report('ERROR','휴대폰 연결, 마이크 지원과 전사 서버 설정을 확인해 주세요.');return false;}
      const m={queue:[],queuedBytes:0,sequence:0,closed:false,busy:false,serverReady:false,starting:true,lastFrame:now()};current=m;state.frames=state.bytes=state.level=0;state.errorCode=null;state.errorStage=null;state.sttPausedReason=null;state.permission='requesting';report('STARTING',resuming?'다음 전사 구간을 시작합니다.':'마이크 권한을 확인하고 있습니다.');
      try{
        const selected=sttPolicy();m.sttPolicy=selected?Object.freeze({...selected,allowedFallbacks:Object.freeze([...(selected.allowedFallbacks||[])])}):null;
        m.autoVoiceConsent=autoVoiceConsent()===true;
        // Permission can remain unanswered. Late permission resolution must release tracks.
        m.permission=setTimer(()=>{state.errorCode='microphone_permission_timeout';stop('마이크 권한 응답을 기다리다 중지했습니다. 권한을 확인하고 다시 시작해 주세요.',true);},15000);
        const requested=deviceId();m.requestedDevice=requested;
        state.errorStage='mic_open';
        try{m.media=await env.navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true,...(requested?{deviceId:{exact:requested}}:{})},video:false});}
        catch(error){
          // 저장된 장치가 사라진 경우에만 기본 입력으로 1회 재시도 — 그 외 오류는 원래 경로로.
          if(!requested||!['NotFoundError','OverconstrainedError'].includes(error?.name))throw error;
          event('MIC_DEVICE_FALLBACK',{reason:'saved_input_missing'});m.requestedDevice='';m.deviceFallback=true;
          m.media=await env.navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true},video:false});
        }
        clearTimer(m.permission);
        if(m.deviceFallback){try{onDeviceFallback();}catch{}}
        if(m.closed){m.media.getTracks().forEach(t=>t.stop());return false;}
        state.permission='granted';state.deviceLabel=m.media.getAudioTracks()[0]?.label||'장치명 미제공';
        state.errorStage='audio_graph';
        m.context=new env.AudioContext();state.audioContext=m.context.state;m.context.onstatechange=()=>{if(m.closed)return;state.audioContext=m.context.state;if(m.context.state==='suspended')event('audio_context_suspended');};state.inputRate=m.context.sampleRate||null;await m.context.resume();await m.context.audioWorklet.addModule('/assets/display/pcm-worklet.js');if(m.closed)return false;
        m.node=new env.AudioWorkletNode(m.context,'conversate-pcm');m.node.onprocessorerror=()=>stop('음성 입력이 중단됐습니다.',true);
        m.node.port.onmessage=e=>{if(e.data?.stopped){m.flushed?.();return;}if(m.closed||!(e.data?.pcm instanceof ArrayBuffer)||!e.data.pcm.byteLength)return;
          m.lastFrame=now();state.lastFrameAt=Math.round(m.lastFrame);if(m.inputWaiting){m.inputWaiting=false;state.errorCode=null;event('audio_resumed');report('LISTENING','수음 중 · 입력 재개');}state.frames++;state.bytes+=e.data.pcm.byteLength;
          const samples=new Int16Array(e.data.pcm);let peak=0;for(let i=0;i<samples.length;i++)peak=Math.max(peak,Math.abs(samples[i]));state.level=Math.round(peak/327.68);
          if(m.apiPaused){state.droppedAudioMs+=e.data.pcm.byteLength/32;new Uint8Array(e.data.pcm).fill(0);if(state.frames%4===0)report('LISTENING',apiMessage);return;}
          if(continuous&&!m.finishing&&state.frames%4===0)report(m.waiting?'WAITING':m.reconnecting?'RECONNECTING':m.serverReady?'LISTENING':'STARTING',m.waiting||!m.serverReady?'전사 연결 대기 · 최근 음성 임시 보관':peak<128?'수음 중 · 무음 (오디오 유입 있음)':'수음 중 · 소리 감지');
          if(e.data.pcm.byteLength>7680||!continuous&&m.queue.length>=32){new Uint8Array(e.data.pcm).fill(0);state.errorCode='audio_transport_backpressure';void stop('오디오 입력을 확인해 주세요.',true);return;}
          m.queue.push({pcm:e.data.pcm,at:now()});m.queuedBytes+=e.data.pcm.byteLength;trim(m);void pump(m).catch(()=>{});};
        bindTracks(m);void permissionStatus(m);
        m.source=m.context.createMediaStreamSource(m.media);const mute=m.context.createGain();mute.gain.value=0;m.source.connect(m.node).connect(mute).connect(m.context.destination);
        state.errorStage='server_begin';
        m.serverAttempted=true;try{await client.beginVoice({continuation,sttPolicy:m.sttPolicy,autoVoiceConsent:m.autoVoiceConsent});m.serverReady=true;m.paceAt=now();m.paceCredit=0;}
        catch(error){diagnostic(error,'server_begin',m);if(continuous&&apiLimit(error))await pauseApi(m,error);
          else if(error?.message==='assist_not_found'&&!client.state.autoVoiceSettings?.modeEnabled&&!m.beginRetried&&typeof client.reconnect==='function'){m.beginRetried=true;event('audio_waiting',{reason:'assist_rebind'});report('RECONNECTING','서버 세션을 다시 연결하고 수음을 다시 시작합니다.');
            try{await client.reconnect({preserveSession:true});if(m.closed)return false;await client.beginVoice({continuation:false,sttPolicy:m.sttPolicy});m.serverReady=true;m.paceAt=now();m.paceCredit=0;}
            catch(again){if(continuous&&apiLimit(again))await pauseApi(m,again);else if(continuous&&recoverable(again))waitForAudio(m,again);else throw again;}}
          else if(continuous&&recoverable(error))waitForAudio(m,error);else throw error;}
        if(m.closed)return false;m.starting=false;void pump(m).catch(()=>{});
        state.segments++;
        const seconds=Number(segmentSeconds());
        if(continuous){m.lastFrame=now();m.watch=setTimer(()=>watchdog(m),1000);if(!m.apiPaused&&!m.waiting&&client.state.testStatus?.asr?.provider!=='whisper')m.roll=setTimer(()=>renew(m,true),renewalMs());
          if(env.navigator.wakeLock)env.navigator.wakeLock.request('screen').then(lock=>{if(m.closed)lock.release();else m.wake=lock;}).catch(()=>{});}
        state.errorStage=null;
        event('MIC_SESSION_START');report(m.waiting?'WAITING':'LISTENING',m.waiting?'전사 연결 대기 · 마이크와 마지막 글자를 유지합니다.':m.apiPaused?apiMessage:continuous?'수음 중 · 전사를 유지하며 필요한 때만 힌트를 표시합니다.':'듣고 있습니다 · 질문이 확정되면 답변합니다.');return true;
      }catch(error){if(error?.name==='NotAllowedError')state.permission='denied';state.errorCode=({NotAllowedError:'microphone_permission_denied',NotFoundError:'microphone_device_missing',NotReadableError:'microphone_device_busy',OverconstrainedError:'microphone_device_changed'})[error?.name]||(/^[a-z_]{1,64}$/.test(error?.message||'')?error.message:'microphone_start_failed');diagnostic(error,state.errorStage||'mic_open',m);if(current===m)await stop(error?.name==='NotAllowedError'?'마이크 권한이 허용되지 않았습니다. 휴대폰 사이트 권한을 확인해 주세요.':'음성 입력을 준비하지 못했습니다. 오류 코드를 확인해 주세요.',true);return false;}
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
      if(m.media?.getAudioTracks().some(t=>t.readyState==='ended'))return reacquire(m);
      if(m.waiting&&!m.retry)void renew(m);
      return m.context?m.context.state!=='suspended':true;
    }
    async function reconnect(){const m=current;if(!m||m.closed||!wanted){await client.reconnect({preserveSession:true});return false;}if(m.starting)return false;if(m.reconnecting)return m.renewalDone;await renew(m,false,true);return !m.closed;}
    return {state,supported,start,stop,resume,reconnect,deviceChanged,finish:()=>stop('마지막 전사를 받고 마이크를 중지했습니다.',false,true),isActive:()=>current!==null};
  }
  return {createCapture};
});
