(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require('./display-core.js'));
  else root.DisplayConversate = factory(root.DisplayCore);
})(typeof globalThis !== 'undefined' ? globalThis : this, function (core) {
  'use strict';
  function createCommitter({ setMessage, submit, record = () => {} }) {
    let composing = false, cancelled = false, pending = null;
    function commit(event) {
      record('change'); setMessage(event.target.value);
      if (cancelled) return;
      if (composing || event.isComposing) { pending = event; return; }
      pending = null; submit();
    }
    return {
      input(event) { record('input'); cancelled = false; setMessage(event.target.value); },
      change: commit,
      activate() { record('activate'); cancelled = false; },
      compositionstart() { record('compositionstart'); composing = true; },
      compositionend() { record('compositionend'); composing = false; if (pending && !cancelled) { const event = pending; pending = null; commit({ target: event.target }); } },
      cancel() { record('escape'); cancelled = true; pending = null; },
      isComposing() { return composing; }
    };
  }
  function createClient(options = {}) {
    const transcription = options.transcription === true;
    const fetchImpl = options.fetchImpl || globalThis.fetch.bind(globalThis);
    const setTimer = options.setTimer || setTimeout, clearTimer = options.clearTimer || clearTimeout;
    const now = options.now || (() => performance.now()), uuid = options.uuid || core.requestUuid;
    const notify = () => options.onChange?.(state);
    const clientId = uuid().replace(/-/g, '');let claimPending=options.standalone===true;
    const state = { phase: 'IDLE', connection: 'PREPARING', message: '', result: null, error: null, metrics: null };
    let revision = 0, submittedRevision = -1, generation = 0, pollGeneration = 0, session = null, active = false;
    let timer = null, flight = null, connecting = null, retry = 1000, reconnectAt = 0, restored = true, lastCard = '';
    const requests = new Set(), events = [];
    let voice = null, voiceStopping = false, seenVersion = -1;
    let shownHintKey='',captionTimer=null, hintTimer=null, resultTimer=null, joinRequest=null, errors=0;
    state.clientId=clientId;state.caption=null;state.hint=null;state.role='DISPLAY';state.linked=false;state.linkPending=false;state.reconnects=0;
    function recordEvent(event) { events.push(event); if (events.length > 12) events.shift(); }
    function setMessage(value) { if (String(value) !== state.message) { revision++; state.message = String(value); } notify(); }
    function canSubmit() { return !flight && !voice && !voiceStopping && now() >= reconnectAt && state.message.trim().length > 0 && state.message.length <= 2000 && revision !== submittedRevision; }
    function finish() { if (flight) { clearTimer(flight.timer); flight.resolve(true); flight = null; } }
    function fail(code, message) { state.phase = 'ERROR'; state.error = { code, message }; finish(); notify(); }
    async function post(route, body, opts) {
      if(options.lens===true)route=route==='ack'?'lens/ack':['transcription','poll'].includes(route)?'lens':route;
      const controller = new AbortController(); let timeout;
      // snapshot 업로드 같은 외부 작업 취소 신호를 이 요청의 abort로 연결한다.
      if(opts?.signal){if(opts.signal.aborted)controller.abort();else opts.signal.addEventListener('abort',()=>controller.abort(),{once:true});}
      // Keep the bounded server-stop request alive when pagehide disposes the client.
      if (route !== 'audio/stop') requests.add(controller);
      try {
        const began=now();
        const work = (async () => {
          const response = await fetchImpl('/api/assist/display/' + route, { method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json', 'X-Display-Client': '1',
              ...(['focus/memory/save','focus/memory/search'].includes(route)?{'X-Budget-Ms':'5000'}:{}),
              ...(options.testChannel?{'X-Display-Test-Channel':options.testChannel}:{}) }, body: JSON.stringify(body), signal: controller.signal, keepalive: route === 'audio/stop' });
          if (!response.ok) {
            const seconds = Number(response.headers.get('Retry-After'));
            if (response.status === 429 && Number.isFinite(seconds) && seconds > 0) reconnectAt = now() + Math.min(seconds, 3600) * 1000;
            let detail;try{detail=await response.json();}catch{}
            const error = new Error(/^[a-z_]{1,64}$/.test(detail?.reason||'')?detail.reason:'display_http'); error.status = response.status;if(response.status===429&&Number.isFinite(seconds)&&seconds>0)error.retryAfterMs=Math.min(seconds,3600)*1000;throw error;
          }
          const payload = await response.json(), view=route==='audio/chunk-batch'?payload?.view:payload;
          if(route==='link/code'||route==='link/join'||route==='lens/link'||route==='lens/text'||route.startsWith('focus/'))return view;
          if (!view || !/^[a-f0-9-]{36}$/.test(view.assistId) || !Number.isSafeInteger(view.epoch) || view.epoch < 1 || typeof view.ready !== 'boolean') throw Error('display-contract');
          view.roundTripMs=Math.max(0,Math.round(now()-began));
          if(transcription){
            const c=view.caption;
            if(c!=null&&(!/^[A-Za-z0-9._:-]{1,80}$/.test(c.utteranceId||'')||!Number.isSafeInteger(c.revision)||c.revision<0||typeof c.text!=='string'||c.text.length>(c.rolling===true?16385:2048)||typeof c.isFinal!=='boolean'))throw Error('display-contract');
            if(!Number.isSafeInteger(view.version)||view.version<1||!Number.isFinite(view.captionTtlMs)||!Number.isFinite(view.cardTtlMs))throw Error('display-contract');
          }
          if(route==='audio/chunk-batch'){
            if(payload.acceptedCount!==body.frames.length||payload.acceptedThrough!==body.frames.at(-1).sequence)throw Error('display-contract');
            return {view,acceptedCount:payload.acceptedCount,acceptedThrough:payload.acceptedThrough};
          }
          return view;
        })();
        return await Promise.race([work, new Promise((_, reject) => { timeout = setTimer(() => { controller.abort(); reject(Error('display-timeout')); }, route === 'audio/start' ? 35000 : route === 'audio/chunk-batch' ? 20000 : route === 'audio/stop' && body.finish === true ? 6500 : opts?.timeoutMs || (['bootstrap','transcription','phone-test'].includes(route)?10000:options.requestTimeoutMs || 4000)); })]);
      } finally { clearTimer(timeout); requests.delete(controller); }
    }
    function connection() { return { assistId: session?.assistId || null, epoch: session?.epoch || 0, clientId }; }
    function connect() { if (!connecting) connecting = post(transcription?(options.standalone===true?'phone-test':'transcription'):'bootstrap', {...connection(),activate:claimPending}).then(v=>{claimPending=false;return v;}).finally(() => { connecting = null; }); return connecting; }
    function apply(view) {
      if(transcription&&session?.assistId===view.assistId&&view.epoch<session.epoch)return;
      if (session?.assistId === view.assistId && session.epoch === view.epoch && Number.isSafeInteger(view.version) && view.version < seenVersion) return;
      const currentLens=state.testStatus,incomingLens=view.testStatus;
      const preserveLens=session?.assistId===view.assistId&&session?.epoch===view.epoch&&currentLens?.lensSettingsScope!=null&&currentLens.lensSettingsScope===incomingLens?.lensSettingsScope&&Number.isSafeInteger(currentLens.lensSettingsVersion)&&Number.isSafeInteger(incomingLens.lensSettingsVersion)&&incomingLens.lensSettingsVersion<currentLens.lensSettingsVersion;
      if (session?.assistId !== view.assistId || session.epoch !== view.epoch) seenVersion = -1;
      if (Number.isSafeInteger(view.version)) seenVersion = view.version;
      session = { assistId: view.assistId, epoch: view.epoch };
      state.assistId=view.assistId;
      state.connection = view.ready ? 'READY' : 'PREPARING'; retry = 1000;
      state.audioAvailable = view.audioAvailable === true;
      state.audioState = view.audioState;
      state.audioFinished = view.audioFinished === true;state.audioRenewAfterMs=view.audioRenewAfterMs;
      state.testStatus = preserveLens?{...incomingLens,lensDisplay:currentLens.lensDisplay,lensSettingsVersion:currentLens.lensSettingsVersion,lensSettingsScope:currentLens.lensSettingsScope}:view.testStatus || null;
      state.focusProducer = view.focusProducer === true;
      // 생산자 폴링 응답에만 담기는 단발 촬영 명령 — 비생산자/구독자에게는 서버가 null을 돌려준다.
      state.focusControl = view.focusControl || null;
      applyFocus(view.focus||null);
      if(transcription){
        errors=0;state.reason=view.reason;state.role=view.role;state.linked=view.linked;state.linkPending=view.linkPending;state.confirmation=view.confirmation;
        state.epoch=view.epoch;state.version=view.version;state.hintsEnabled=view.hintsEnabled;
        state.roundTripMs=view.roundTripMs;state.ready=view.ready;
        clearTimer(captionTimer);
        const captionLife=Math.min(120000,Math.max(0,view.captionTtlMs-view.roundTripMs));
        if(view.ready&&(view.caption?.rolling===true||captionLife>0)&&view.caption?.text?.trim())state.caption=view.caption;
        const hintLife=Math.min(120000,Math.max(0,view.cardTtlMs-view.roundTripMs));
        const candidate=view.ready&&(options.lens===true||view.hintsEnabled)&&hintLife>0&&['ANSWER','SUGGESTION','TERM','PERSON','CONCEPT','BIO','FACT','RAG','CUE','RAG_CUE','API_DIRECT'].includes(view.card?.kind)?view.card:null;
        if(view.hintsEnabled===false&&options.lens!==true){clearTimer(hintTimer);state.hint=null;}
        else if(candidate){shownHintKey=candidate.requestId||String(candidate.expiresAt);clearTimer(hintTimer);state.hint=candidate;}
        state.error=null;
        if(flight&&view.requestId===flight.id&&!view.processing&&/^OPENAI_DIRECT_(OK|ERROR)$/.test(view.reason)){
          state.phase=view.reason==='OPENAI_DIRECT_OK'?'RESULT':'ERROR';
          if(state.phase==='ERROR')state.error={code:'openai_direct_error',message:'단일 OpenAI 호출에 실패했습니다. 재호출하지 않았습니다.'};
          finish();
        }
        notify();return;
      }
      const card = view.card;
      const voiceCard = voice && voice.ready && view.epoch === voice.epoch && card?.requestId === view.voiceRequestId && card?.requestId !== voice.baseline;
      const eligible = card && (flight ? card.requestId === flight.id : voice ? voiceCard : restored);
      if (voice?.ready && view.processing && view.voiceRequestId && view.voiceRequestId !== voice.baseline) { state.phase = 'LOADING'; state.error = null; }
      if (eligible && card.requestId !== lastCard) {
        const pages = Array.isArray(card.detailPages) && card.detailPages.length ? card.detailPages : [card.text];
        if (pages.length > 128 || pages.some(text => typeof text !== 'string' || Array.from(text).length > 120)) throw Error('display-card-contract');
        lastCard = card.requestId; restored = false;
        state.result = { answer: pages.join(''), fallback: card.kind === 'FALLBACK',
          sources: (Array.isArray(card.sourceTitles) ? card.sourceTitles : []).slice(0, 4).filter(t => typeof t === 'string').map(title => ({ title, url: null, marker: '' })) };
        state.phase = 'RESULT'; state.error = null;
        clearTimer(resultTimer);
        const shownRequest=card.requestId,shownRevision=revision;
        resultTimer=setTimer(()=>{if(lastCard===shownRequest){state.result=null;if(revision===shownRevision)state.message='';if(!flight)state.phase='IDLE';notify();}},
          Math.min(20000,Math.max(0,(Number.isFinite(view.cardTtlMs)?view.cardTtlMs:15000)-view.roundTripMs)));
        state.metrics = { roundTripMs: flight ? Math.round(now() - flight.started) : 0, processingMs: view.processingMs, duplicates: view.duplicates };
        finish();
      } else if(flight&&view.requestId===flight.id&&!view.processing&&view.reason==='NO_CUE'){
        state.phase='IDLE';state.reason='NO_CUE';state.result=null;state.error=null;finish();
      } else if (flight && view.requestId === flight.id && !view.processing && ['CUE_GATE_UNAVAILABLE','CUE_EVIDENCE_UNAVAILABLE','API_UNAVAILABLE','GENERATION_UNAVAILABLE','GENERATION_INVALID_OUTPUT','GENERATION_TIMEOUT','GENERATION_INPUT_REJECTED','GENERATION_DENIED','GENERATION_RATE_LIMITED','MODEL_NOT_FOUND','RATE_LIMITED','ADMISSION_UNAVAILABLE','PROCESSING_FAILED','RAG_TIMEOUT','QUEUE_LIMIT','DUPLICATE','BACKCHANNEL'].includes(view.reason)) {
        fail(view.reason.toLowerCase(), view.reason === 'DUPLICATE' ? '같은 입력은 한 번만 처리합니다.' : '답변을 완료하지 못했습니다. 잠시 후 새 질문을 입력해 주세요.');
      }
      notify();
    }
    function schedule(delay) { clearTimer(timer); if (active) timer = setTimer(tick, Math.max(delay, reconnectAt - now())); }
    async function tick() {
      if (!active) return;
      const stamp = generation, pollStamp = pollGeneration;
      try {
        const view = await (session ? post('poll', connection()) : connect());
        if (!active || stamp !== generation || pollStamp !== pollGeneration) return;
        apply(view); schedule(1000);
      } catch (error) {
        if (!active || stamp !== generation || pollStamp !== pollGeneration) return;
        if (error.status === 404 || (error.status === 409 && voice == null && !voiceStopping)) session = null;
        if(transcription){
          state.error={code:error.message};recordEvent('transcript_preserved');
          if(error.message==='link_pending'){state.linkPending=true;state.connection='PAIRING';notify();schedule(4000);return;}
          state.reconnects++;errors++;
          if(error.status===403||(errors>=3&&error.message==='display-contract')){state.connection='DISCONNECTED';active=false;notify();return;}
        }
        state.connection = 'RECONNECTING'; notify(); schedule(retry); retry = Math.min(retry * 2, 30000);
      }
    }
    function start() { if (active) return; active = true;errors=0;state.connection = 'PREPARING'; notify(); tick(); }
    function pause() { active = false; pollGeneration++; clearTimer(timer); state.connection = 'PAUSED'; notify(); }
    async function submit(requestOptions = {}) {
      if (!canSubmit()) return false;
      submittedRevision = revision; restored = false; recordEvent('submit');
      clearTimer(resultTimer);
      const stamp = ++generation, id = uuid(), text = state.message;
      let resolve; const completed = new Promise(done => { resolve = done; });
      flight = { id, started: now(), resolve, timer: setTimer(() => cancel(), options.answerTimeoutMs || 90000) };
      state.phase = 'LOADING'; state.error = null; state.result = null; notify(); clearTimer(timer);
      try {
        // Refresh the output epoch after a long native composer interaction; never replay input.
        const ready = await connect();
        if (stamp !== generation) return completed;
        session = { assistId: ready.assistId, epoch: ready.epoch };
        if (!ready.ready) throw Error('display-preparing');
        const view = await post('input', { ...connection(), requestId: id, text, eventOrder: [...events],
          ...(requestOptions.verificationMode==='openai-direct'?{verificationMode:'openai-direct'}:{}) });
        if (stamp !== generation) return completed;
        if (view.reason === 'DUPLICATE') fail('duplicate', '같은 입력은 한 번만 처리합니다.'); else apply(view);
        schedule(1000);
      } catch (error) {
        if (stamp === generation) {
          const limited = error.status === 429;
          fail(limited ? 'rate-limited' : 'outcome-unknown', limited ? '요청이 많습니다. 잠시 기다려 주세요.' : '완료 여부를 확인하지 못했습니다. 같은 질문은 다시 보내지 않았습니다.');
          state.connection = 'RECONNECTING'; schedule(1000);
        }
      }
      return completed;
    }
    function cancel() { if (!flight) return; generation++; for (const request of requests) request.abort(); fail('outcome-unknown','대기를 끝냈습니다. 같은 질문은 다시 보내지 않았습니다.'); schedule(1000); }
    async function beginVoice({continuation=false,sttPolicy=null}={}) {
      if (flight || voice || voiceStopping) throw Error('display-busy');
      const policy=sttPolicy?{...sttPolicy,allowedFallbacks:[...(sttPolicy.allowedFallbacks||[])]}:null;
      const mode = { ready: false }; voice = mode; state.voiceActive = true; restored = false; notify();
      try{
        const ready = await connect(); if (voice !== mode) throw Error('voice-cancelled');
        if(transcription&&!(options.standalone===true&&ready.role==='STANDALONE')&&(ready.role!=='PHONE'||!ready.linked))throw Error('paired_phone_required');
        session = { assistId: ready.assistId, epoch: ready.epoch }; mode.epoch = ready.epoch; mode.baseline = ready.requestId;
        if (!ready.audioAvailable || !ready.ready) throw Error('audio-unavailable');
        // A lost first request leaves the server OFF; no segment exists to renew.
        mode.bound=connection();
        const view = await post('audio/start', {...mode.bound,continuation:continuation&&ready.audioState!=='OFF',...(policy?{sttPolicy:policy}:{})});
        if (voice !== mode) throw Error('voice-cancelled');
        mode.bound={assistId:view.assistId,epoch:view.epoch,clientId};
        if (!view.ready || !['READY','STREAMING'].includes(view.audioState)) throw Error('audio-not-ready');
        mode.epoch = view.epoch; mode.ready = true; apply(view); schedule(1000);
      }catch(error){
        // Roll back only this attempt. A late failure must never clear its successor.
        if(voice===mode){
          if(mode.bound)await post('audio/stop',{...mode.bound,finish:true}).catch(()=>{});
          if(voice===mode){voice=null;state.voiceActive=false;notify();}
        }
        throw error;
      }
    }
    async function voiceChunk(sequence, pcm) {
      const mode = voice;if (!mode?.ready) throw Error('audio-not-ready');
      const view = await post('audio/chunk', { ...connection(), epoch: mode.epoch, sequence, pcm });
      if (voice !== mode) return;
      apply(view);
      if (view.epoch !== mode.epoch || !view.ready) throw Error('audio-stopped');
    }
    async function voiceBatch(frames){
      const mode=voice;if(!mode?.ready)throw Error('audio-not-ready');
      if(!Array.isArray(frames)||frames.length<1||frames.length>8||frames.some((f,i)=>
        !Number.isSafeInteger(f?.sequence)||f.sequence<0||f.sequence!==frames[0].sequence+i||typeof f.pcm!=='string'||f.pcm.length>10240))throw Error('invalid_audio_batch');
      const ack=await post('audio/chunk-batch',{...connection(),epoch:mode.epoch,frames});
      if(voice!==mode)return;apply(ack.view);
      if(ack.view.epoch!==mode.epoch||!ack.view.ready)throw Error('audio-stopped');
      return ack;
    }
    async function endVoice({finish:drain=false}={}) {
      if (!voice) return; const bound = connection(); voice = null; voiceStopping = true; state.voiceActive = false;
      const stamp = ++generation, pollStamp = ++pollGeneration; notify();
      try { const view = await post('audio/stop', drain ? {...bound,finish:true} : bound); if (stamp === generation && pollStamp === pollGeneration) apply(view); return view; }
      catch (error) {
        if (stamp !== generation || pollStamp !== pollGeneration) throw error;
        if (['stale_epoch','assist_paused'].includes(error.message)) {
          try { const ready = await connect();
            if (stamp !== generation || pollStamp !== pollGeneration) throw error;
            session = { assistId: ready.assistId, epoch: ready.epoch };
            const view = await post('audio/stop', drain ? {...connection(),finish:true} : connection());
            if (stamp === generation && pollStamp === pollGeneration) apply(view); return view; } catch {}
        }
        if (stamp === generation && pollStamp === pollGeneration) state.connection = 'RECONNECTING'; throw error; }
      finally { voiceStopping = false; notify(); if (stamp === generation && pollStamp === pollGeneration) schedule(1000); }
    }
    async function action(route,extra={}){const view=await post(route,{...connection(),...extra});apply(view);return view;}
    async function pairingCode(){return post('link/code',connection());}
    const lensStorageKey='awx.display.lens.'+(options.testChannel||'live');
    let lensFlight=null;
    function storedLensLink({includeExpired=false}={}){
      try{const link=JSON.parse((options.storage||globalThis.localStorage)?.getItem(lensStorageKey)||'null');
        return link&&/^[a-f0-9]{64}$/.test(link.token||'')&&Number.isFinite(link.expiresAt)&&(includeExpired||link.expiresAt>Date.now())?link:null;
      }catch{return null;}
    }
    function lensLink(){
      if(lensFlight)return lensFlight;
      lensFlight=(async()=>{
        apply(await connect());
        const saved=storedLensLink({includeExpired:true}),now=Date.now();
        const renew=saved?.sticky===true&&saved.expiresAt<=now+86400000;
        if(saved&&saved.expiresAt>now&&!renew){
          try{await post('lens/text',{token:saved.token});return saved;}
          catch(error){if(error.status!==403&&error.status!==404)throw error;}
        }
        // Renewal selects the grant by its bound producer owner, never a read token.
        const link=await post('lens/link',connection());
        if(!/^[a-f0-9]{64}$/.test(link.token||'')||!Number.isFinite(link.expiresAt)||link.expiresAt<=Date.now())throw Error('invalid_lens_link');
        try{(options.storage||globalThis.localStorage)?.setItem(lensStorageKey,JSON.stringify(link));}catch{}
        return link;
      })().finally(()=>{lensFlight=null;});
      return lensFlight;
    }
    async function join(code){
      if(!joinRequest||joinRequest.code!==code)joinRequest={code,requestId:uuid()};
      const result=await post('link/join',{clientId,...joinRequest});
      if(typeof result.confirmation!=='string'||!/^\d{4}$/.test(result.confirmation))throw Error('display-contract');
      pause();session=null;seenVersion=-1;state.role='PHONE';state.linkPending=result.pending;state.confirmation=result.confirmation;start();return result;
    }
    function approve(){return action('link/approve');}
    function unlink(){return action('link/unlink');}
    function hints(enabled){return action('hints',{enabled});}
    function contextReset(){return action('context-reset');}
    function background(text){return action('context',{text});}
    function relaySettings(enabled,segmentSeconds){return action('relay/settings',{enabled,segmentSeconds});}
    /** patch: LensDisplayPrefs fields; restoreDefaults=true resets the owner's settings server-side. */
    async function lensSettings(patch,restoreDefaults){
      const bound=connection(),scope=state.testStatus?.lensSettingsScope;
      const view=await post('relay/lens-settings',{...bound,...(restoreDefaults===true?{restoreDefaults:true}:{display:patch}),expectedSettingsVersion:state.testStatus?.lensSettingsVersion??null});
      if(session?.assistId!==bound.assistId||session?.epoch!==bound.epoch||state.testStatus?.lensSettingsScope!==scope)throw Error('lens_settings_stale');
      const currentVersion=state.testStatus?.lensSettingsVersion,responseVersion=view.testStatus?.lensSettingsVersion;
      if(Number.isSafeInteger(currentVersion)&&Number.isSafeInteger(responseVersion)&&responseVersion<currentVersion)throw Error('lens_settings_stale');
      apply(view);return view;
    }
    function relayTest(number=1,fromFold=true){return action('relay/test',{number,fromFold});}
    function applyFocus(value){
      if(value&&state.focus?.serverInstanceId===value.serverInstanceId&&value.stateVersion<state.focus.stateVersion)return;
      state.focus=value;
    }
    async function focusRequest(route,body={},opts){
      if(!session)throw Error('focus_session_stale');
      if(!['settings/read','settings','history','open','input','input/status','snapshot/claim','snapshot/result','close','memory/read','memory/save','memory/delete','memory/search'].includes(route))throw Error('invalid_focus_action');
      const view=await post('focus/'+route,{...connection(),...body},opts);
      if(['open','input','close'].includes(route)){applyFocus(view);notify();}
      return view;
    }
    async function reconnect({preserveSession=true}={}){pause();const stamp=pollGeneration;if(!preserveSession){claimPending=options.standalone===true;session=null;}try{const view=await connect();if(stamp===pollGeneration)apply(view);return view;}finally{if(stamp===pollGeneration)start();}}
    function acknowledge(version,phase){return post('ack',{...connection(),version,phase});}
    async function stopAudio(){apply(await connect());return action('audio/stop');}
    function clearDraft() { if (flight) cancel(); generation++; restored = false; revision++; submittedRevision = -1; state.message = ''; state.phase = 'IDLE'; state.result = null; state.error = null; notify(); schedule(0); }
    function dispose() { pause();clearTimer(captionTimer);clearTimer(hintTimer);clearTimer(resultTimer);if (flight) cancel(); for (const request of requests) request.abort(); }
return { state, setMessage, canSubmit, submit, cancel, clearDraft, recordEvent, start, pause, dispose, beginVoice, voiceChunk, voiceBatch, endVoice,pairingCode,lensLink,storedLensLink,join,approve,unlink,hints,contextReset,background,relaySettings,lensSettings,relayTest,reconnect,acknowledge,stopAudio,focusRequest };
  }
  return { createClient, createCommitter };
});
