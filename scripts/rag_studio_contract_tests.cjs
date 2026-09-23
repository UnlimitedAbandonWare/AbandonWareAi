const {test}=require('node:test');
const assert=require('node:assert/strict');
const core=require('../main/resources/static/assets/display/display-core.js');
const inspector=require('../main/resources/static/assets/interview/rag-inspector.js');
const voice=require('../main/resources/static/assets/interview/studio-voice.js');
const response=(extra={})=>({content:'Synthetic answer.',evidence:[],...extra});

test('web projection preserves answer/session but excludes private paths and arbitrary metadata',()=>{
  const view=inspector.projectResponse(response({sessionId:7,modelUsed:'gpt-4.1-mini',ragUsed:true,
    learningContext:{prompt:'PRIVATE'},evidence:[{title:'Public reference',source:'https://example.org/paper',rank:1,confidence:.72,confidenceSource:'reranker',filePath:'PRIVATE_PATH',snippet:'PRIVATE'}],
    pipelineSnapshot:{route:'hybrid',webCount:2,vectorCount:3,finalContextCount:2,citationCoverage:.5,finalSigmoid:.8,rawPrompt:'PRIVATE'}}));
  assert.equal(view.answer,'Synthetic answer.');assert.equal(view.sessionId,7);
  assert.equal(view.sources[0].confidence,.72);assert.equal(view.sources[0].rank,1);
  assert.equal(view.inspection.pipeline.finalContextCount,2);assert.equal(view.inspection.executionRoute,'unobserved');
  assert.ok(!JSON.stringify(view).includes('PRIVATE'));
});
test('missing, malformed, negative and nonfinite metrics never become zeros or probabilities',()=>{
  const view=inspector.projectResponse(response({modelUsed:'unknown-private-model',evidence:[{source:'http://localhost/private',confidence:NaN,rank:-1}],pipelineSnapshot:{webCount:-1,vectorCount:'4',citationCoverage:2,finalSigmoid:Infinity}}));
  assert.equal(view.sources[0].url,null);assert.equal(view.sources[0].confidence,null);assert.equal(view.sources[0].rank,null);
  assert.equal(view.inspection.pipeline.webCount,null);assert.equal(view.inspection.pipeline.vectorCount,null);
  assert.equal(view.inspection.pipeline.citationCoverage,null);assert.equal(view.inspection.executionRoute,'unobserved');
  assert.equal(view.inspection.retrievalMs,null);assert.equal(view.inspection.llmMs,null);
});
test('scores and URL credentials are bounded and neither error reasons nor model substrings prove fallback/route',()=>{
  const view=inspector.projectResponse(response({modelUsed:'local-looking-model',answerMode:'ANSWER',evidence:[{source:'https://user:secret@example.com',confidence:3}],pipelineSnapshot:{failureClass:'timeout',route:'hybrid',disabledReason:'Bearer PRIVATE'}}));
  assert.equal(view.fallback,false);assert.equal(view.inspection.executionRoute,'unobserved');assert.equal(view.sources[0].url,null);
  assert.equal(view.inspection.pipeline.disabledReason,null);
  assert.equal(inspector.projectResponse(response({answerMode:'FALLBACK_EVIDENCE'})).fallback,true);
});
test('shared transport applies rich projection only when opted in and keeps one request/session semantics',async()=>{
  let calls=0;const fetchImpl=async(_url,options)=>{calls++;assert.equal(options.credentials,'same-origin');assert.equal(JSON.parse(options.body).inputType,'text');return{ok:true,status:200,headers:{get:()=>null},json:async()=>response({sessionId:12,ragUsed:true,modelUsed:'example-model'})};};
  const web=core.createClient({fetchImpl,projectResponse:inspector.projectResponse});web.setMessage('synthetic');await web.submit();await web.submit();
  assert.equal(calls,1);assert.equal(web.state.result.inspection.model,'example-model');assert.equal(web.state.sessionId,12);
  assert.equal(core.projectResponse(response()).inspection,undefined);
});
test('telemetry counts fallback once per completed request; errors and unknown outcomes stay separate',()=>{
  const ledger=inspector.createLedger();
  const state={phase:'RESULT',metrics:{requestId:'r1',roundTripMs:35,httpStatus:200},result:inspector.projectResponse(response({modelUsed:'fallback:evidence'}))};
  ledger.record(state);ledger.record(state);
  ledger.record({phase:'ERROR',metrics:{requestId:'r2',httpStatus:503},error:{code:'admission-unavailable',message:'PRIVATE'}});
  const snapshot=ledger.snapshot();assert.equal(snapshot.fallbackResponses,1);assert.equal(snapshot.completedResponses,1);assert.equal(snapshot.failedRequests,1);assert.equal(snapshot.meanRoundTripMs,35);
  assert.deepEqual(snapshot.rows.map(row=>row.requestId),['r1','r2']);
  assert.equal(ledger.record({phase:'ERROR',metrics:{requestId:'Bearer PRIVATE'},error:{code:'server-error'}}),null);
  assert.ok(!JSON.stringify(snapshot).includes('PRIVATE'));assert.ok(!JSON.stringify(snapshot).includes('Synthetic answer'));
});
test('speech results are draft-only and stale events cannot replace a later draft',()=>{
  let recognizer,transcript='';class Recognition{constructor(){recognizer=this;}start(){}abort(){}}
  const v=voice.createVoice({Recognition,onTranscript:text=>{transcript=text;},onState:()=>{}});
  v.start();const oldResult=recognizer.onresult;oldResult({resultIndex:0,results:[{0:{transcript:'draft'},isFinal:true}]});assert.equal(transcript,'draft');
  v.cancel();oldResult({resultIndex:0,results:[{0:{transcript:'stale'},isFinal:true}]});assert.equal(transcript,'draft');
  assert.equal(voice.createVoice({Recognition:null}).supported,false);
});
test('web answer hides only internal control projection and blocks credential-bearing citation links',()=>{
  const view=inspector.projectResponse(response({content:'Answer.<!-- rag-control-projection:v1 private controls -->\nSafety disclosure.',evidence:[{source:'https://example.org/doc?access_token=PRIVATE'}]}));
  assert.equal(view.answer,'Answer.\nSafety disclosure.');assert.equal(view.sources[0].url,null);
});
test('unknown completion has no measured round-trip and is counted separately from request failure',()=>{
  const ledger=inspector.createLedger();ledger.record({phase:'ERROR',metrics:{requestId:'r-unknown',roundTripMs:0},error:{code:'outcome-unknown'}});
  assert.equal(ledger.snapshot().rows[0].roundTripMs,null);assert.equal(ledger.snapshot().unknownOutcomes,1);assert.equal(ledger.snapshot().failedRequests,0);
});

test('public web retrieval opts in without private vector retrieval or changing default Display requests',async()=>{
  const bodies=[];const fetchImpl=async(_url,options)=>{bodies.push(JSON.parse(options.body));return{ok:true,status:200,headers:{get:()=>null},json:async()=>response()};};
  const display=core.createClient({fetchImpl}),web=core.createClient({fetchImpl,publicWebSearch:true});
  display.setMessage('display');await display.submit();web.setMessage('web');await web.submit();
  assert.equal(bodies[0].useWebSearch,undefined);assert.equal(bodies[0].useRag,undefined);
  assert.equal(bodies[1].useWebSearch,true);assert.equal(bodies[1].useRag,false);assert.equal(bodies[1].searchMode,'FORCE_LIGHT');
});

test('only the exact versioned server hold projection counts as a withheld answer',()=>{
  const held=inspector.projectResponse(response({content:'검증된 근거가 추가로 필요해 응답 본문을 보류했습니다.\n\n<!-- rag-control-projection:v1 -->\nSafety disclosure.'}));
  assert.equal(held.held,true);assert.equal(held.fallback,false);
  assert.equal(inspector.projectResponse(response({content:'evidence_needed: attribution unavailable / verify retrieval evidence'})).held,true);
  assert.equal(inspector.projectResponse(response({content:'evidence_needed'})).held,true);
  assert.equal(inspector.projectResponse(response({content:'검증된 근거가 추가로 필요해 응답 본문을 보류했습니다.'})).held,false);
  assert.equal(inspector.projectResponse(response({content:'Released answer.<!-- rag-control-projection:v1 -->\nSafety disclosure.'})).held,false);
  const ledger=inspector.createLedger();ledger.record({phase:'RESULT',metrics:{requestId:'held-1',roundTripMs:20},result:held});
  assert.equal(ledger.snapshot().heldResponses,1);assert.equal(ledger.snapshot().fallbackResponses,0);
});
