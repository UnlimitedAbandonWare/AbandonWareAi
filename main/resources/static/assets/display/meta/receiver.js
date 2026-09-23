(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory();else{root.DisplayReceiver=factory();try{root.DisplayReceiver.current=root.DisplayReceiver.mount();}catch(error){if(root.AwxDisplayBoot)root.AwxDisplayBoot.fail('initialization');}}})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  function createClientId(host){
    if(host.AwxDisplayBoot)return host.AwxDisplayBoot.createId();
    if(host.crypto&&typeof host.crypto.randomUUID==='function')return host.crypto.randomUUID().replace(/-/g,'');
    if(host.crypto&&typeof host.crypto.getRandomValues==='function'){const bytes=new Uint8Array(16);host.crypto.getRandomValues(bytes);return Array.from(bytes,n=>n.toString(16).padStart(2,'0')).join('');}
    return Array.from({length:32},()=>Math.floor(Math.random()*16).toString(16)).join('');
  }
  // Developer-tunable lens presentation values. The server validates requests and
  // echoes the applied values; client clamps are a transport guard, not policy.
  const DISPLAY_DEFAULTS={transcriptFontPx:26,hintFontPx:26,transcriptMaxLines:4,hintPageLines:11,transcriptTtlMs:20000,hintTtlMs:20000,autoPageMs:5000,hintTargetChars:1000};
  const LENS_HINT_CHARS_MAX=1180;
  function normDisplay(raw){
    const d=raw&&typeof raw==='object'?raw:{};
    const clamp=(v,lo,hi,f)=>Number.isFinite(v)?Math.min(hi,Math.max(lo,Math.round(v))):f;
    return {transcriptFontPx:clamp(d.transcriptFontPx,20,36,DISPLAY_DEFAULTS.transcriptFontPx),
      hintFontPx:clamp(d.hintFontPx,20,36,DISPLAY_DEFAULTS.hintFontPx),
      transcriptMaxLines:clamp(d.transcriptMaxLines,1,8,DISPLAY_DEFAULTS.transcriptMaxLines),
      hintPageLines:clamp(d.hintPageLines,4,13,DISPLAY_DEFAULTS.hintPageLines),
      transcriptTtlMs:clamp(d.transcriptTtlMs,1000,100000,DISPLAY_DEFAULTS.transcriptTtlMs),
      hintTtlMs:clamp(d.hintTtlMs,1000,100000,DISPLAY_DEFAULTS.hintTtlMs),
      autoPageMs:d.autoPageMs===0?0:clamp(d.autoPageMs,1000,100000,DISPLAY_DEFAULTS.autoPageMs),
      hintTargetChars:clamp(d.hintTargetChars,240,1100,DISPLAY_DEFAULTS.hintTargetChars)};
  }
  function createReceiver(options={}){
    const host=options.host||(typeof globalThis!=='undefined'?globalThis:window);
    const fetchImpl=options.fetchImpl||(typeof host.fetch==='function'?host.fetch.bind(host):null),setTimer=options.setTimer||setTimeout,clearTimer=options.clearTimer||clearTimeout;
    const clientId=options.clientId||createClientId(host),headers={'Content-Type':'application/json','X-Display-Client':'1'};
    const diagnostic=(event,data)=>{try{options.diagnostic?.(event,data);}catch{}};
    if(host.AwxDisplayBoot)headers['X-Display-Runtime']=host.AwxDisplayBoot.id;
    if(options.channel)headers['X-Display-Test-Channel']=options.channel;
    const state={connection:'CONNECTING',caption:'',hint:'',eventId:0,serverId:'',generation:0,reconnects:0,enabled:true,producerConnected:false,errorCode:'',display:{...DISPLAY_DEFAULTS}};
    let active=false,timer,expiry,hintExpiry,shownHintKey='',retry=1000,inflight=false,disposed=false;
    const retiredHintKeys=new Set();
    const notify=()=>options.onChange?.(state);
    async function post(route,body){
      const url='/api/assist/display/relay/'+route,payload=JSON.stringify({clientId,...body});
      if((!fetchImpl||typeof host.AbortController!=='function')&&typeof host.XMLHttpRequest==='function'){
        return new Promise((resolve,reject)=>{const xhr=new host.XMLHttpRequest();xhr.open('POST',url,true);xhr.timeout=4000;
          Object.entries(headers).forEach(([key,value])=>xhr.setRequestHeader(key,value));
          xhr.onload=()=>{if(xhr.status<200||xhr.status>=300){reject(Error('http_'+xhr.status));return;}try{resolve(JSON.parse(xhr.responseText));}catch{reject(Error('contract'));}};
          xhr.onerror=()=>reject(Error('network'));xhr.ontimeout=()=>reject(Error('timeout'));xhr.send(payload);
        });
      }
      if(!fetchImpl||typeof host.AbortController!=='function')throw Error('unsupported');
      const controller=new host.AbortController(),timeout=setTimer(()=>controller.abort(),4000);
      try{const response=await fetchImpl(url,{method:'POST',credentials:'same-origin',headers,body:payload,signal:controller.signal});if(!response.ok)throw Error('http_'+response.status);return await response.json();}finally{clearTimer(timeout);}
    }
    function apply(v){
      if(!v||typeof v.serverId!=='string'||!Number.isSafeInteger(v.eventId)||v.eventId<1||!Number.isSafeInteger(v.generation)||!Number.isFinite(v.sentAt)||typeof v.enabled!=='boolean')throw Error('display_contract');
      for(const [value,max] of [[v.caption,v.caption?.rolling===true?16385:2048],[v.hint,LENS_HINT_CHARS_MAX]])if(value!=null&&(typeof value.text!=='string'||Array.from(value.text).length>max||!Number.isFinite(value.expiresAt)))throw Error('display_contract');
      if(v.display!=null){const next=normDisplay(v.display);if(JSON.stringify(next)!==JSON.stringify(state.display)){state.display=next;diagnostic('display_applied',{...next});}}
      if(state.serverId===v.serverId&&(v.generation<state.generation||v.eventId<state.eventId))return;
      if(state.serverId!==v.serverId||state.generation!==v.generation||!v.enabled){clearTimer(hintExpiry);state.hint='';shownHintKey='';retiredHintKeys.clear();}
      state.serverId=v.serverId;state.eventId=v.eventId;state.generation=v.generation;state.enabled=v.enabled;state.producerConnected=v.producerConnected===true;
      state.caption=v.enabled&&v.caption?.expiresAt>v.sentAt?v.caption.text:'';
      state.focus=v.enabled&&v.producerConnected?v.focus||null:null;
      if(v.enabled&&v.hint?.expiresAt>v.sentAt){const key=v.hint.requestId||String(v.hint.expiresAt);if(!retiredHintKeys.has(key)&&key!==shownHintKey){if(shownHintKey)retiredHintKeys.add(shownHintKey);shownHintKey=key;clearTimer(hintExpiry);state.hint=v.hint.text;hintExpiry=setTimer(()=>{state.hint='';notify();},Math.min(state.display.hintTtlMs,Math.max(0,v.hint.expiresAt-v.sentAt)));}}
      state.connection='CONNECTED';state.errorCode='';clearTimer(expiry);
      if(state.caption&&!v.caption.rolling)expiry=setTimer(()=>{state.caption='';notify();},Math.min(state.display.transcriptTtlMs,v.caption.expiresAt-v.sentAt));notify();
    }
    async function poll(){if(inflight||disposed)return;inflight=true;try{const v=await post('poll',{});if(disposed)return;diagnostic('response_received',{sequence:v?.eventId});apply(v);retry=1000;}catch(error){if(disposed)return;state.connection='RECONNECTING';state.errorCode=/^http_[1-5][0-9][0-9]$/.test(error?.message)?error.message:error?.message==='display_contract'||error?.message==='contract'?'contract':error?.message==='unsupported'?'unsupported':error?.name==='AbortError'||error?.message==='timeout'?'timeout':'network';state.reconnects++;notify();diagnostic('transport_error',{code:state.errorCode});retry=Math.min(30000,retry*2);}finally{inflight=false;if(active)timer=setTimer(poll,retry);}}
    async function acknowledge(eventId){if(eventId!==state.eventId||eventId<1)return;try{await post('ack',{eventId});}catch{}}
    function start(){if(active||disposed)return;active=true;void poll();}
    function pause(){active=false;clearTimer(timer);}
    function dispose(){disposed=true;pause();clearTimer(expiry);clearTimer(hintExpiry);}
    return {state,poll,start,pause,dispose,acknowledge};
  }
  // A #view capability reads text only. It never registers with the relay or ACKs.
  function createLensReceiver(options={}){
    const host=options.host||(typeof globalThis!=='undefined'?globalThis:window);
    const fetchImpl=options.fetchImpl||(typeof host.fetch==='function'?host.fetch.bind(host):null);
    const setTimer=options.setTimer||setTimeout,clearTimer=options.clearTimer||clearTimeout;
    const valid=/^[a-f0-9]{64}$/.test(options.token||'');
    const state={connection:valid?'CONNECTING':'DISCONNECTED',conversation:'',conversationExpiresAt:0,hint:'',hintId:'',hintFirstShownAt:0,hintExpiresAt:0,errorCode:valid?'':options.token?'lens_link_invalid':'lens_link_missing',action:valid?'':'reconnect_from_phone',display:{...DISPLAY_DEFAULTS},focus:null};
    let active=false,disposed=false,terminal=!valid,inflight=false,timer,hintTimer,captionTimer,cancelRequest,retry=1000,lastHintKey='',captionKey='',suppressedCaptionKey='',captionShownAt=0;
    const retiredHintKeys=new Set();
    const nowFn=typeof options.now==='function'?options.now:()=>Date.now();
    const notify=()=>options.onChange?.(state);
    const diagnostic=(event,data)=>{try{options.diagnostic?.(event,data);}catch{}};
    async function read(){
      const url='/api/assist/display/lens/text',body=JSON.stringify({token:options.token});
      const headers={'Content-Type':'application/json','X-Display-Client':'1'};
      let controller,xhr,timeout,rejectCancelled;
      const cancelled=new Promise((_,reject)=>{rejectCancelled=reject;});
      cancelRequest=()=>{controller?.abort();xhr?.abort();rejectCancelled(Error('cancelled'));};
      const work=(async()=>{
        if(fetchImpl&&typeof host.AbortController==='function'){
          controller=new host.AbortController();
          const response=await fetchImpl(url,{method:'POST',credentials:'same-origin',cache:'no-store',headers,body,signal:controller.signal});
          if(!response.ok)throw Object.assign(Error('http'),{status:response.status});
          return response.json();
        }
        if(typeof host.XMLHttpRequest!=='function')throw Error('unsupported');
        return new Promise((resolve,reject)=>{
          xhr=new host.XMLHttpRequest();xhr.open('POST',url,true);xhr.timeout=4000;
          Object.entries(headers).forEach(([key,value])=>xhr.setRequestHeader(key,value));
          xhr.onload=()=>{if(xhr.status<200||xhr.status>=300){reject(Object.assign(Error('http'),{status:xhr.status}));return;}try{resolve(JSON.parse(xhr.responseText));}catch{reject(Error('contract'));}};
          xhr.onerror=()=>reject(Error('network'));xhr.ontimeout=()=>reject(Error('timeout'));xhr.send(body);
        });
      })();
      try{return await Promise.race([work,cancelled,new Promise((_,reject)=>{timeout=setTimer(()=>{controller?.abort();xhr?.abort();reject(Error('timeout'));},4000);})]);}
      finally{clearTimer(timeout);cancelRequest=null;}
    }
    // A hint owns its TTL from first show (default 20s, configurable). Receiving the
    // same generation again never extends that window; the server expiry is the
    // authoritative ceiling so a configured TTL never outlives the delivered card.
    function hintKeyOf(value){const id=typeof value.hintId==='string'?value.hintId:'';return id?'id:'+id:'text:'+value.hint;}
    function retireHint(key){if(key){retiredHintKeys.add(key);if(retiredHintKeys.size>32)retiredHintKeys.delete(retiredHintKeys.values().next().value);}}
    function expireHint(){if(nowFn()>=state.hintExpiresAt){const held=state.hintExpiresAt-state.hintFirstShownAt;retireHint(lastHintKey);lastHintKey='';state.hint='';state.hintId='';state.hintExpiresAt=0;diagnostic('hint_expired',{heldMs:held,ttlMs:state.display.hintTtlMs,at:nowFn()});notify();}}
    function applyHint(value){
      const text=typeof value.hint==='string'?value.hint:'';if(!text.trim())return;
      const key=hintKeyOf(value),now=nowFn();
      if(retiredHintKeys.has(key)){diagnostic('hint_suppressed',{});return;}
      if(key===lastHintKey){if(state.hintExpiresAt>now)state.hint=text;return;}
      retireHint(lastHintKey);lastHintKey=key;
      state.hint=text;state.hintId=typeof value.hintId==='string'?value.hintId:'';
      state.hintFirstShownAt=now;const serverCap=value.hintExpiresAt>0?value.hintExpiresAt:Infinity;
      state.hintExpiresAt=Math.min(now+state.display.hintTtlMs,serverCap);
      clearTimer(hintTimer);hintTimer=setTimer(expireHint,Math.max(1,state.hintExpiresAt-now));diagnostic('hint_shown',{chars:Array.from(text).length,ttlMs:state.hintExpiresAt-now,at:now});
    }
    // The transcript owns its hold from the first show of that exact content: a
    // repeated identical caption never extends it, a changed caption re-anchors
    // it, and an expired caption is not revived while the same text keeps coming.
    function expireConversation(){if(nowFn()>=state.conversationExpiresAt){const held=state.conversationExpiresAt-captionShownAt;suppressedCaptionKey=captionKey;captionKey='';state.conversation='';state.conversationExpiresAt=0;diagnostic('transcript_expired',{heldMs:held,ttlMs:state.display.transcriptTtlMs,at:nowFn()});notify();}}
    function applyConversation(value){
      const text=typeof value.conversation==='string'?value.conversation:'';if(!text.trim()){suppressedCaptionKey='';return;}
      const cap=Number.isFinite(value.conversationExpiresAt)&&value.conversationExpiresAt>0?value.conversationExpiresAt:Infinity;
      const key='text:'+text+'@'+(cap===Infinity?'open':cap),now=nowFn();
      if(key===captionKey)return;
      if(key===suppressedCaptionKey){diagnostic('transcript_suppressed',{at:now});return;}
      suppressedCaptionKey='';captionKey=key;captionShownAt=now;state.conversation=text;
      state.conversationExpiresAt=Math.min(now+state.display.transcriptTtlMs,cap);
      clearTimer(captionTimer);captionTimer=setTimer(expireConversation,Math.max(1,state.conversationExpiresAt-now));
      diagnostic('transcript_shown',{chars:Array.from(text).length,ttlMs:state.conversationExpiresAt-now,at:now});
    }
    async function poll(){
      if(inflight||disposed||terminal)return;inflight=true;
      try{
        const value=await read();if(disposed)return;
        if(!value||typeof value.conversation!=='string'||typeof value.hint!=='string'||value.hintId!=null&&typeof value.hintId!=='string'||value.hintExpiresAt!=null&&!Number.isFinite(value.hintExpiresAt)||value.conversationExpiresAt!=null&&!Number.isFinite(value.conversationExpiresAt)||value.display!=null&&typeof value.display!=='object'||Array.from(value.conversation).length>280||Array.from(value.hint).length>LENS_HINT_CHARS_MAX)throw Error('contract');
        if(value.display){const next=normDisplay(value.display);if(JSON.stringify(next)!==JSON.stringify(state.display)){state.display=next;diagnostic('display_applied',{...next,at:nowFn()});}}
        applyConversation(value);
        applyHint(value);
        state.focus=value.focus||null;
        state.connection='CONNECTED';state.errorCode='';state.action='';retry=1000;notify();
      }catch(error){
        if(disposed||error.message==='cancelled')return;
        terminal=error.status===403||error.status===404;
        state.connection=terminal?'DISCONNECTED':'RECONNECTING';
        state.errorCode=error.status===404?'lens_link_expired':error.status===403?'lens_link_denied':error.message==='contract'||error instanceof SyntaxError?'contract':error.message==='unsupported'?'unsupported':error.message==='timeout'||error.name==='AbortError'?'timeout':error.status?'http_'+error.status:'network';
        state.action=terminal?'reconnect_from_phone':'retrying';
        if(terminal)active=false;else retry=Math.min(30000,retry*2);notify();
      }finally{inflight=false;if(active&&!disposed&&!terminal)timer=setTimer(poll,retry);}
    }
    function start(){if(active||disposed||terminal)return;active=true;void poll();}
    function pause(){active=false;clearTimer(timer);cancelRequest?.();}
    function dispose(){disposed=true;pause();clearTimer(hintTimer);clearTimer(captionTimer);}
    return {state,poll,start,pause,dispose};
  }
  // Lens line budget (FIELD_TESTED): the cue card and the transcript share
  // ~13 measured rendered lines of the 600px stage. The transcript keeps its
  // newest complete units inside whatever band the current cue page leaves.
  const LENS_LINE_BUDGET=13,MIN_CAPTION_LINES=3;
  function dropOldestUnit(text){
    const raw=String(text||'').trim();
    if(!raw)return '';
    const sentence=/^[^.!?。！？]{0,240}?[.!?。！？](?:\s+|$)/.exec(raw);
    if(sentence&&sentence[0].length>0&&sentence[0].length<raw.length){
      return raw.slice(sentence[0].length).trimStart();
    }
    const comma=/^[^,]{1,120}?,\s*/.exec(raw);
    if(comma&&comma[0].length>0&&comma[0].length<raw.length){
      return raw.slice(comma[0].length).trimStart();
    }
    const word=/^\S+\s+/.exec(raw);
    if(word&&word[0].length<raw.length)return raw.slice(word[0].length).trimStart();
    return '';
  }
  function fitCaption(el,text,maxLines){
    const raw=String(text||'').replace(/\s+/g,' ').trim();
    if(!el)return raw;
    if(!raw){el.textContent='';return '';}
    el.textContent=raw;
    const cs=typeof getComputedStyle==='function'?getComputedStyle(el):null;
    let lh=cs?parseFloat(cs.lineHeight):NaN;
    if(!Number.isFinite(lh)||lh<=0)lh=((cs?parseFloat(cs.fontSize):NaN)||26)*1.25;
    const band=Number.isFinite(maxLines)&&maxLines>0?Math.min(LENS_LINE_BUDGET,maxLines):LENS_LINE_BUDGET;
    const fallback=lh*Math.max(MIN_CAPTION_LINES,band);
    const limit=(el.clientHeight>0?Math.min(el.clientHeight,fallback):fallback)+0.75;
    if(!(el.scrollHeight>0)||el.scrollHeight<=limit)return raw;
    let cur=raw;
    for(let i=0;i<500&&cur;i++){
      const next=dropOldestUnit(cur);
      if(!next||next===cur)break;
      cur=next;
      el.textContent=cur;
      if(el.scrollHeight<=limit)break;
    }
    if(el.scrollHeight>limit){
      // No droppable boundary (e.g. one long unbroken token): keep the fitting tail.
      const arr=Array.from(raw);
      let lo=1,hi=arr.length,best=1;
      while(lo<=hi){const mid=(lo+hi)>>1;el.textContent=arr.slice(-mid).join('');if(el.scrollHeight<=limit){best=mid;lo=mid+1;}else hi=mid-1;}
      cur=arr.slice(-best).join('');
      const lead=cur.indexOf(' ');
      if(lead>0&&lead<(cur.length>>1)){const trimmed=cur.slice(lead+1);el.textContent=trimmed;if(el.scrollHeight<=limit)cur=trimmed;else el.textContent=cur;}
      else el.textContent=cur;
    }
    return cur;
  }
  function measuredLines(el){
    if(!el||!(el.scrollHeight>0))return 0;
    let lh=32.5;
    try{
      const cs=typeof getComputedStyle==='function'?getComputedStyle(el):null;
      if(cs){const v=parseFloat(cs.lineHeight),f=parseFloat(cs.fontSize);
        if(Number.isFinite(v)&&v>0)lh=v;else if(Number.isFinite(f)&&f>0)lh=f*1.25;}
    }catch{}
    return Math.max(1,Math.round(el.scrollHeight/lh));
  }
  function mountLens(token){
    function formatCue(text){
      const raw=String(text||'').replace(/\s+/g,' ').trim();
      if(!raw)return '';
      return raw;
    }
    function statusLine(state){
      if(state.connection==='CONNECTED'&&(state.conversation||state.hint))return '';
      if(state.errorCode==='lens_link_missing')return 'Lens link missing. Open the address from Fold6 again.';
      if(state.errorCode==='lens_link_invalid')return 'Lens link invalid.';
      if(state.errorCode==='lens_link_expired')return 'Lens link expired. Create a new glasses address.';
      if(state.errorCode==='lens_link_denied')return 'Lens link denied.';
      if(state.errorCode==='lens_producer_waiting'||state.action==='reconnect_from_phone')return 'Waiting for Fold6 producer\u2026';
      if(state.connection==='CONNECTING'||state.connection==='RECONNECTING')return 'Connecting\u2026';
      if(state.errorCode)return 'Display error: '+state.errorCode;
      return 'Waiting for caption\u2026';
    }
    const stage=document.querySelector('.stage');
    const hint=document.getElementById('hint');
    const transcript=document.getElementById('transcript');
    const status=document.getElementById('status');
    const diagnostic=(event,data)=>{window.AwxDisplayBoot?.note(event,data);try{console.debug('[lens]'+event,JSON.stringify(data||{}));}catch{}};
    const focus=window.NovaFocus&&document.getElementById('nova-focus')?window.NovaFocus.createProjection({host:window,document,target:'lens',panel:document.getElementById('nova-focus'),status:document.getElementById('nova-status'),draft:document.getElementById('nova-draft'),answer:document.getElementById('nova-answer'),receipt:window.NovaFocus.receiptSender(window),diagnostic}):null;
    let hintRenderKey='',lastCueText='',hintPages=[],hintPage=0,hintPageTimer=null,lastState=null;
    const cfg=()=>lastState?.display||DISPLAY_DEFAULTS;
    function paint(){
      const state=lastState||{},c=cfg();
      if(stage)for(const [k,v] of [['--cap-font',c.transcriptFontPx],['--hint-font',c.hintFontPx],['--cap-lines',c.transcriptMaxLines],['--hint-lines',c.hintPageLines]])stage.style.setProperty(k,v+(k.endsWith('font')?'px':''));
      const focused=focus?.update(state.focus,state.connection==='CONNECTED')===true;
      if(focused){if(hint)hint.hidden=true;if(transcript)transcript.hidden=true;if(status)status.hidden=true;return;}
      if(transcript)transcript.hidden=false;
      const hasCue=hintPages.length>0;
      if(stage)stage.classList.toggle('has-cue',hasCue);
      if(hint){hint.textContent=hasCue?hintPages[hintPage]:'';hint.hidden=!hasCue;}
      // Transcript keeps its configured small band; a cue never restyles it.
      let capLines=c.transcriptMaxLines;
      if(hasCue){const lines=measuredLines(hint);if(lines>0)capLines=Math.max(MIN_CAPTION_LINES,Math.min(c.transcriptMaxLines,LENS_LINE_BUDGET-lines));}
      if(transcript){if(transcript.style)transcript.style.maxHeight=(capLines*1.25)+'em';fitCaption(transcript,state.conversation,capLines);}
      if(status){
        const line=statusLine(state)||(hintPages.length>1?'힌트 '+(hintPage+1)+'/'+hintPages.length:'');
        // Keep the row's height reserved (nbsp) so the transcript's bottom edge
        // never jumps when the page indicator or a status message disappears.
        status.hidden=false;status.textContent=line||' ';
      }
    }
    function schedulePages(){
      clearTimeout(hintPageTimer);hintPageTimer=null;
      const ms=cfg().autoPageMs;
      // The configured interval is used exactly as set: a hint that expires
      // before the next page turn simply disappears — the interval is never
      // shortened to beat the hint's own TTL.
      if(hintPages.length>1&&ms>=1000){
        hintPageTimer=setTimeout(()=>{hintPageTimer=null;
          if(hintPages.length>1){hintPage=(hintPage+1)%hintPages.length;paint();diagnostic('hint_page',{page:hintPage+1,pages:hintPages.length,intervalMs:ms,at:Date.now()});}
          schedulePages();},ms);
      }
    }
    function moveHint(delta){if(!hintPages.length)return;hintPage=(hintPage+delta+hintPages.length)%hintPages.length;paint();schedulePages();}
    const receiver=createLensReceiver({token,diagnostic,onChange(state){
      lastState=state;
      const cue=formatCue(state.hint);
      const key=cue?(state.hintId?'id:'+state.hintId:'text:'+cue):'';
      if(!cue){hintRenderKey='';lastCueText='';hintPages=[];hintPage=0;clearTimeout(hintPageTimer);hintPageTimer=null;}
      else if(key!==hintRenderKey){
        hintRenderKey=key;lastCueText=cue;
        if(hint){hint.hidden=false;hintPages=splitHintPages(hint,cue,cfg().hintPageLines);hintPage=0;}else hintPages=[cue];
        diagnostic('lens_hint_render',{pages:hintPages.length,chars:Array.from(cue).length});
        schedulePages();
      }else if(cue!==lastCueText){
        // Same hint identity with a revised body: re-split but keep the page being read.
        lastCueText=cue;const keep=hintPage;
        hintPages=hint?splitHintPages(hint,cue,cfg().hintPageLines):[cue];hintPage=Math.min(keep,hintPages.length-1);
        schedulePages();
      }
      paint();
    }});
    document.addEventListener('keydown',event=>{
      if(focus?.isActive()&&['ArrowLeft','ArrowUp','ArrowRight','ArrowDown','Enter'].includes(event.key)){event.preventDefault();return;}
      if(['ArrowLeft','ArrowUp'].includes(event.key)){event.preventDefault();moveHint(-1);}
      else if(['ArrowRight','ArrowDown','Enter'].includes(event.key)){event.preventDefault();moveHint(1);}
    });
    document.addEventListener('visibilitychange',()=>{focus?.visibility();if(document.hidden)receiver.pause();else receiver.start();});
    window.addEventListener('online',()=>{receiver.pause();receiver.start();});
    window.addEventListener('pagehide',()=>{focus?.dispose();receiver.dispose();});
    window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
    receiver.start();return receiver;
  }
  // A lens hint is split at the element's measured page band (configurable line
  // count, real clientHeight when laid out) so every page stays inside the
  // visible cue area; nothing is truncated or dropped.
  function splitHintPages(el,text,pageLines){
    const raw=String(text||'').replace(/\s+/g,' ').trim();
    if(!raw)return [];
    if(!el)return [raw];
    el.textContent=raw;
    let lh=32.5;
    try{
      const cs=typeof getComputedStyle==='function'?getComputedStyle(el):null;
      if(cs){const v=parseFloat(cs.lineHeight);const f=parseFloat(cs.fontSize);
        if(Number.isFinite(v)&&v>0)lh=v;else if(Number.isFinite(f)&&f>0)lh=f*1.25;}
    }catch{}
    const band=Math.max(lh,lh*(Number.isFinite(pageLines)&&pageLines>0?pageLines:DISPLAY_DEFAULTS.hintPageLines));
    const limit=(el.clientHeight>0?Math.min(el.clientHeight,band):band)+0.75;
    if(!(el.scrollHeight>0)){
      const pts=Array.from(raw),pages=[];
      for(let i=0;i<pts.length;i+=120)pages.push(pts.slice(i,i+120).join(''));
      return pages.length?pages:[raw];
    }
    if(el.scrollHeight<=limit)return [raw];
    const pages=[];let rest=raw,guard=0;
    while(rest&&guard++<24){
      el.textContent=rest;
      if(el.scrollHeight<=limit){pages.push(rest);rest='';break;}
      const arr=Array.from(rest);
      let lo=1,hi=arr.length-1,best=1;
      while(lo<=hi){const mid=(lo+hi)>>1;el.textContent=arr.slice(0,mid).join('');if(el.scrollHeight<=limit){best=mid;lo=mid+1;}else hi=mid-1;}
      const prefix=arr.slice(0,best).join('');let cut=best;
      const space=prefix.lastIndexOf(' ');
      if(space>=(best>>1))cut=space;
      const page=arr.slice(0,cut).join('').trimEnd();
      if(page){pages.push(page);rest=arr.slice(cut).join('').trimStart();}
      else{pages.push(arr.slice(0,best).join(''));rest=arr.slice(best).join('').trimStart();}
    }
    if(rest)pages.push(rest);
    return pages;
  }
  // A single plain text card keeps the same layout as the optically verified page.
  // Paging preserves the entire hint, including a final negation, without scrolling.
  function createPresentation(options={}){
    const setTimer=options.setTimer||setTimeout,clearTimer=options.clearTimer||clearTimeout;
    let snapshot={},key='',pages=[],page=0,timer,disposed=false;
    function render(){
      const hint=!!snapshot.hint;
      options.onChange?.({
        title:pages.length?(hint?'힌트':'전사')+(pages.length>1?' '+(page+1)+'/'+pages.length:''):'HELLO',
        text:pages[page]||'DISPLAY TEST',
        status:snapshot.connection!=='CONNECTED'?'연결 확인 중':!snapshot.enabled?'송출 꺼짐':
          snapshot.producerConnected?'Fold6 연결됨':'Fold6에서 수음을 시작하세요',
        errorCode:snapshot.errorCode||''
      });
    }
    function schedule(){
      clearTimer(timer);
      const ms=snapshot.display?.autoPageMs??DISPLAY_DEFAULTS.autoPageMs;
      if(!disposed&&snapshot.hint&&pages.length>1&&ms>=1000)timer=setTimer(()=>{page=(page+1)%pages.length;render();schedule();},ms);
    }
    function update(value){
      if(disposed)return;snapshot=value;
      const text=(value.hint||value.caption||'').replace(/\s+/g,' ').trim();
      const next=(value.hint?'hint:':'caption:')+text;
      if(next!==key){
        key=next;const points=Array.from(text);pages=[];
        for(let i=0;i<points.length;i+=120)pages.push(points.slice(i,i+120).join(''));
        page=value.hint?0:Math.max(0,pages.length-1);schedule();
      }
      render();
    }
    function move(delta){if(disposed||!pages.length)return;page=(page+delta+pages.length)%pages.length;render();schedule();}
    function dispose(){disposed=true;clearTimer(timer);}
    return {update,move,dispose};
  }
  function mount(){
    const query=new URLSearchParams(location.search);if(query.get('static')==='1')return;
    const hash=new URLSearchParams(location.hash.slice(1));
    if(hash.has('view')||query.get('clientRole')!=='test')return mountLens(hash.get('view')||'');
    const diagnostic=(event,data)=>{window.AwxDisplayBoot?.note(event,data);try{console.debug('[lens]'+event,JSON.stringify(data||{}));}catch{}};
    const channel=query.get('clientRole')==='test'?(query.get('channel')||'test-'+createClientId(window)):null;
    const card=createPresentation({onChange(view){
      document.getElementById('transcript').textContent=receiver?.state.caption||receiver?.state.hint?view.text:'';
      const hint=document.getElementById('hint');hint.textContent='';hint.hidden=true;
    }});
    const focus=window.NovaFocus&&document.getElementById('nova-focus')?window.NovaFocus.createProjection({host:window,document,target:'lens',panel:document.getElementById('nova-focus'),status:document.getElementById('nova-status'),draft:document.getElementById('nova-draft'),answer:document.getElementById('nova-answer'),receipt:window.NovaFocus.receiptSender(window),diagnostic}):null;
    let acked='',receiver;
    receiver=createReceiver({channel,diagnostic,onChange(s){
      const active=focus?.update(s.focus,s.connection==='CONNECTED'&&s.enabled&&s.producerConnected);
      document.getElementById('transcript').hidden=!!active;
      if(!active)card.update(s);
      // This ACK reports a DOM update, not optical visibility on physical glasses.
      const key=s.serverId+':'+s.eventId;
      if(s.connection==='CONNECTED'&&key!==acked&&!document.hidden)(typeof requestAnimationFrame==='function'?requestAnimationFrame:fn=>setTimeout(fn,16))(()=>{
        if(!document.hidden&&key===receiver.state.serverId+':'+receiver.state.eventId){acked=key;void receiver.acknowledge(s.eventId);}
      });
    }});
    document.addEventListener('visibilitychange',()=>{focus?.visibility();if(document.hidden)receiver.pause();else receiver.start();});
    window.addEventListener('online',()=>{receiver.pause();receiver.start();});
    window.addEventListener('pagehide',()=>{focus?.dispose();receiver.dispose();card.dispose();});
    window.addEventListener('pageshow',e=>{if(e.persisted)location.reload();});
    document.addEventListener('keydown',e=>{
      if(focus?.isActive()&&['ArrowLeft','ArrowUp','ArrowRight','ArrowDown','Enter'].includes(e.key)){e.preventDefault();return;}
      if(['ArrowLeft','ArrowUp'].includes(e.key)){e.preventDefault();card.move(-1);}
      else if(['ArrowRight','ArrowDown','Enter'].includes(e.key)){e.preventDefault();card.move(1);}
    });
    receiver.start();return receiver;
  }
  return {createReceiver,createLensReceiver,mount,createClientId,createPresentation,splitHintPages,fitCaption,normDisplay,DISPLAY_DEFAULTS};
});
