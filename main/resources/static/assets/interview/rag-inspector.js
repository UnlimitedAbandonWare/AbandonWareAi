(function(root,factory){
  const api=factory(typeof module==='object'&&module.exports?require('../display/display-core.js'):root.DisplayCore);
  if(typeof module==='object'&&module.exports)module.exports=api;else root.RagInspector=api;
})(typeof globalThis!=='undefined'?globalThis:this,function(core){
  'use strict';
  const count=value=>Number.isSafeInteger(value)&&value>=0&&value<=1000000?value:null;
  const score=value=>typeof value==='number'&&Number.isFinite(value)&&value>=0&&value<=1?value:null;
  const label=value=>typeof value==='string'&&/^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,95}$/.test(value)&&!/(?:sk-|sb_secret_|AIza|Bearer|password|token|secret)/i.test(value)?value:null;
  const text=value=>typeof value==='string'?Array.from(value.replace(/[\u0000-\u001f\u007f]/g,' ')).slice(0,240).join(''):'';
  function sourceUrl(value){const safe=core.safeSourceUrl(value);if(!safe)return null;const url=new URL(safe);for(const key of url.searchParams.keys())if(/token|key|auth|secret|signature|credential|password|cookie/i.test(key))return null;return safe;}
  function projectResponse(dto){
    const base=core.projectResponse(dto), raw=dto.pipelineSnapshot||{};
    const pipeline={};
    for(const key of ['webCount','vectorCount','finalContextCount'])pipeline[key]=count(raw[key]);
    for(const key of ['citationCoverage','finalSigmoid'])pipeline[key]=score(raw[key]);
    for(const key of ['planId','route','answerMode','failureClass','disabledReason'])pipeline[key]=label(raw[key]);
    const mode=label(dto.answerMode)||pipeline.answerMode;
    const fallback=base.fallback||mode==='FALLBACK_EVIDENCE';
    // Recognize only the server's versioned presentation contract, never arbitrary answer wording.
    const marker=base.answer.indexOf('<!-- rag-control-projection:v1 -->');
    const held=(marker>=0&&base.answer.slice(0,marker).trim()==='검증된 근거가 추가로 필요해 응답 본문을 보류했습니다.')
      || ['evidence_needed','evidence_needed: attribution unavailable / verify retrieval evidence'].includes(base.answer.trim());
    // Retrieval route (e.g. hybrid) is not an inference-provider execution route.
    const executionRoute=['local','api'].includes(pipeline.route)?pipeline.route:'unobserved';
    return {...base,answer:base.answer.replace(/<!--\s*rag-control-projection:v1\b[\s\S]*?-->/g,''),fallback,held,sources:(Array.isArray(dto.evidence)?dto.evidence:[]).filter(e=>e&&typeof e==='object').slice(0,100).map(e=>({
      title:text(e.title)||'제목 없는 근거',marker:text(e.marker),url:sourceUrl(e.source),
      kind:label(e.kind),rank:count(e.rank)>0?e.rank:null,confidence:score(e.confidence),confidenceSource:label(e.confidenceSource)
    })),inspection:{model:label(dto.modelUsed),ragUsed:typeof dto.ragUsed==='boolean'?dto.ragUsed:null,answerMode:mode,
      executionRoute,pipeline,retrievalMs:null,llmMs:null,providerAttempt:'unobserved'}};
  }
  function createLedger(){
    const seen=new Set(),rows=[];
    function record(state){
      const id=label(state.metrics?.requestId);
      if(!id||!['RESULT','ERROR'].includes(state.phase)||seen.has(id))return null;
      seen.add(id);if(seen.size>200)seen.delete(seen.values().next().value);
      const m=state.metrics||{},p=state.result?.inspection?.pipeline||{};
      const row={requestId:id,phase:state.phase,roundTripMs:state.error?.code==='outcome-unknown'?null:count(m.roundTripMs),httpStatus:count(m.httpStatus),
        sourceCount:state.phase==='RESULT'?state.result.sources.length:null,
        fallback:state.phase==='RESULT'?state.result.fallback:null,
        held:state.phase==='RESULT'?state.result.held:null,
        outcome:state.phase==='RESULT'?'response':state.error?.code==='outcome-unknown'?'unknown':'error',
        webCount:count(p.webCount),vectorCount:count(p.vectorCount),contextCount:count(p.finalContextCount),
        citationCoverage:score(p.citationCoverage),finalScore:score(p.finalSigmoid)};
      rows.push(row);if(rows.length>50)rows.shift();return {...row};
    }
    function snapshot(){
      const completed=rows.filter(r=>r.phase==='RESULT'),times=completed.map(r=>r.roundTripMs).filter(v=>v!==null);
      return {completedResponses:completed.length,failedRequests:rows.filter(r=>r.outcome==='error').length,
        unknownOutcomes:rows.filter(r=>r.outcome==='unknown').length,fallbackResponses:completed.filter(r=>r.fallback).length,heldResponses:completed.filter(r=>r.held).length,
        meanRoundTripMs:times.length?Math.round(times.reduce((a,b)=>a+b,0)/times.length):null,rows:rows.map(r=>({...r}))};
    }
    return {record,snapshot,clear(){rows.length=0;seen.clear();}};
  }
  return {projectResponse,createLedger};
});
