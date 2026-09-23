(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory();else root.NovaFocusFlow=factory();})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const DEFAULTS=Object.freeze({sequentialTextEnabled:true,charIntervalMs:80,maxVisibleLines:6,autoFadeEnabled:true,tailHoldMs:5000,fadeMs:400});
  function settings(raw={}){
    const out={...DEFAULTS,...raw};
    for(const [key,lo,hi] of [['charIntervalMs',50,160],['maxVisibleLines',4,8],['tailHoldMs',2000,15000],['fadeMs',200,1000]])
      if(!Number.isInteger(out[key])||out[key]<lo||out[key]>hi)throw Error('invalid_focus_presentation');
    for(const key of ['sequentialTextEnabled','autoFadeEnabled'])if(typeof out[key]!=='boolean')throw Error('invalid_focus_presentation');
    return out;
  }
  function segment(text,Segmenter=typeof Intl!=='undefined'?Intl.Segmenter:null){
    if(typeof Segmenter==='function')return {units:Array.from(new Segmenter('ko',{granularity:'grapheme'}).segment(text),s=>s.segment),mode:'grapheme'};
    // A complete sentence is the safe degradation unit. Never split UTF-16 or
    // pretend a code point is a grapheme on a browser without a segmenter.
    return {units:text.match(/[^.!?。！？\n]+(?:[.!?。！？\n]+|$)|[.!?。！？\n]+/gu)||[],mode:'sentence'};
  }
  function createFlow(options={}){
    const host=options.host||globalThis,element=options.element;
    const raf=options.requestFrame||host.requestAnimationFrame.bind(host),caf=options.cancelFrame||host.cancelAnimationFrame.bind(host);
    let cfg=settings(options.settings),frame=null,disposed=false,paused=false,previous=null,elapsed=0,credit=0;
    let key='',units=[],index=0,lines=[''],doneAt=null,pendingDone=false,firstPending=false,firstSent=false,shift=null,sentenceScroll=null,mode='grapheme',identity=null;
    const retired=new Set();let highestVersion=-1,server='',activation='';
    const emit=(event,extra={})=>{try{options.onEvent?.(event,{...identity,...extra});}catch{}};
    const view=()=>({key,index,total:units.length,remaining:units.length-index,lines:[...lines],mode,done:doneAt!==null,paused,elapsed});
    function lineHeight(){try{const style=host.getComputedStyle(element);return parseFloat(style.lineHeight)||parseFloat(style.fontSize)*1.25||32.5;}catch{return 32.5;}}
    function lineLimit(){const height=element?.clientHeight;return height>0?Math.max(1,Math.min(cfg.maxVisibleLines,Math.floor(height/lineHeight()))):cfg.maxVisibleLines;}
    function paint(opacity=1,offset=0){
      if(element){
        element.style.opacity=String(opacity);element.style.overflow='hidden';
        const doc=element.ownerDocument;
        while(element.children.length>lines.length)element.lastElementChild.remove();
        while(element.children.length<lines.length){const row=doc.createElement('div');row.style.whiteSpace='pre-wrap';row.style.overflowWrap='anywhere';row.style.minHeight='1.25em';element.appendChild(row);}
        for(let i=0;i<lines.length;i++){const row=element.children[i];if(row.textContent!==lines[i])row.textContent=lines[i];row.style.transform='translateY('+(-offset)+'px)';}
      }
      options.onPaint?.({...view(),opacity,offset});
    }
    function fits(text){
      if(options.fitsLine)return options.fitsLine(text);
      if(!element)return true;
      const row=element.lastElementChild;if(!row)return true;
      const old=row.textContent;row.textContent=text;const ok=row.scrollHeight<=lineHeight()+1;row.textContent=old;return ok;
    }
    function schedule(){if(frame===null&&!disposed&&!paused&&key&&(doneAt===null||cfg.autoFadeEnabled&&elapsed<doneAt+cfg.tailHoldMs+cfg.fadeMs))frame=raf(tick);}
    function reset(){if(frame!==null)caf(frame);frame=null;previous=null;elapsed=credit=0;units=[];index=0;lines=[''];doneAt=null;pendingDone=firstPending=firstSent=false;shift=sentenceScroll=null;}
    function retire(){if(key){retired.add(key);if(retired.size>32)retired.delete(retired.values().next().value);}}
    function append(unit){
      const last=lines.length-1;
      if(mode==='sentence'){
        // Without Segmenter, disclose a complete sentence and automatically
        // move through its measured wrapped lines before sending completion.
        lines=[unit];paint();
        const height=element?.lastElementChild?.scrollHeight||lineHeight(),visible=element?.clientHeight||height;
        sentenceScroll={start:elapsed,offset:Math.max(0,height-visible),duration:Math.max(1200,Array.from(unit).length*cfg.charIntervalMs)};
        return;
      }
      if(unit==='\n'||unit==='\r\n')lines.push('');
      else if(lines[last]&&!fits(lines[last]+unit))lines.push(unit);
      else lines[last]+=unit;
      if(lines.length>lineLimit())shift={start:elapsed,height:lineHeight()};
      paint();
    }
    function tick(timestamp){
      frame=null;if(disposed||paused||!key)return;
      const delta=previous===null?0:Math.max(0,timestamp-previous);previous=timestamp;
      // A suspended browser never pays back a long animation backlog.
      const step=delta>1000?0:delta;elapsed+=step;credit+=step;
      if(firstPending){firstPending=false;firstSent=true;emit('first_visible',{mode});}
      if(sentenceScroll){
        const ratio=Math.min(1,(elapsed-sentenceScroll.start)/sentenceScroll.duration);paint(1,ratio*sentenceScroll.offset);
        if(ratio>=1){sentenceScroll=null;credit=0;}schedule();return;
      }
      if(shift){const ratio=Math.min(1,(elapsed-shift.start)/200);paint(1,ratio*shift.height);if(ratio>=1){lines.shift();shift=null;paint();}schedule();return;}
      if(pendingDone){pendingDone=false;doneAt=elapsed;emit('presentation_done',{mode});}
      if(doneAt!==null){
        const fade=cfg.autoFadeEnabled?Math.max(0,elapsed-doneAt-cfg.tailHoldMs)/cfg.fadeMs:0;
        if(fade>0)paint(Math.max(0,1-fade));schedule();return;
      }
      if(index<units.length){
        const wait=mode==='sentence'?1200:cfg.sequentialTextEnabled?cfg.charIntervalMs:Math.max(1200,cfg.charIntervalMs*24);
        if(credit>=wait){
          credit=0;
          if(mode==='sentence'||cfg.sequentialTextEnabled){append(units[index++]);}
          else{
            // Non-typing mode releases one measured line, then lets it be read.
            do{const unit=units[index];if(lines[lines.length-1]&&!fits(lines[lines.length-1]+unit))break;append(unit);index++;}while(index<units.length&&!shift&&units[index-1]!=='\n');
            if(index<units.length&&!shift){lines.push('');if(lines.length>lineLimit())shift={start:elapsed,height:lineHeight()};paint();}
          }
          if(!firstSent)firstPending=true;
          if(index===units.length)pendingDone=true;
        }
      }
      schedule();
    }
    function accept(focus){
      if(disposed)return false;
      if(!focus||focus.active!==true){retire();reset();key='';identity=null;paint();return false;}
      if(typeof focus.serverInstanceId!=='string'||typeof focus.activationId!=='string'||!Number.isSafeInteger(focus.stateVersion))return false;
      if(server===focus.serverInstanceId&&focus.stateVersion<highestVersion)return false;
      if(server!==focus.serverInstanceId||activation!==focus.activationId){retire();reset();key='';highestVersion=-1;}
      server=focus.serverInstanceId;activation=focus.activationId;highestVersion=focus.stateVersion;
      cfg=settings(focus.presentation||cfg);
      if(!focus.answerText){if(key){retire();reset();key='';paint();}return true;}
      if(typeof focus.answerText!=='string'||Array.from(focus.answerText).length>8000||!Number.isSafeInteger(focus.answerVersion)||typeof focus.turnId!=='string')return false;
      const next=[server,activation,focus.turnId,focus.answerVersion].join('/');
      if(next===key){schedule();return true;}if(retired.has(next))return false;
      retire();reset();key=next;identity={serverInstanceId:server,activationId:activation,turnId:focus.turnId,answerVersion:focus.answerVersion,renderReceiptTicket:focus.renderReceiptTicket};
      const parts=segment(focus.answerText,options.Segmenter===undefined?Intl.Segmenter:options.Segmenter);units=parts.units;mode=parts.mode;
      if(mode!=='grapheme')emit('presentation_degraded',{mode});
      paint();schedule();return true;
    }
    function pause(value=true){if(paused===value)return;paused=value;previous=null;credit=0;if(frame!==null)caf(frame);frame=null;if(!paused)schedule();}
    function replay(){if(!key||disposed)return;const saved=units;reset();units=saved;paint();schedule();}
    function dispose(){retire();reset();key='';disposed=true;identity=null;paint();}
    return {accept,pause,replay,dispose,state:view};
  }
  return {DEFAULTS,settings,segment,createFlow};
});
