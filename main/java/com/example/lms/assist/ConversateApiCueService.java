package com.example.lms.assist;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.retriever.LocalBm25Retriever;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/** Volatile cue policy over the existing API router and retrieval owners; no transcript/history persistence. */
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public class ConversateApiCueService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ConversateApiCueService.class);
    record Hint(String text,List<String> ids,boolean insufficient){}
    record CallResult<T>(T value,String provider,String model,int attempts,String failure,long elapsedMs,List<Map<String,Object>> diagnostics){}
    // Reserve the possible Brave supplement after Naver; no LLM search re-expansion.
    private static final double SINGLE_SEARCH_RESERVATION_USD=.01;
    private static final class RequestCost {
        double remaining;int attempts;long generationDeadline;
        RequestCost(double remaining){this.remaining=remaining;}
    }
    private final LlmRouterProperties routes;
    private final HybridLlmGatewayProbeService eligibility;
    private final LlmRouterAspect router;
    private final UnifiedRagOrchestrator retrieval;
    private final Environment env;
    private final Clock clock;
    private final ConversateCueRoutingPolicy policy;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ConversateLocalCardGenerator localSupport;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.example.lms.debug.ApiFailureRecorder failureRecorder;
    @org.springframework.beans.factory.annotation.Autowired
    public ConversateApiCueService(LlmRouterProperties routes,HybridLlmGatewayProbeService eligibility,
            LlmRouterAspect router,UnifiedRagOrchestrator retrieval,Environment env){
        this(routes,eligibility,router,retrieval,env,Clock.systemUTC());
    }
    ConversateApiCueService(LlmRouterProperties routes,HybridLlmGatewayProbeService eligibility,
            LlmRouterAspect router,UnifiedRagOrchestrator retrieval,Environment env,Clock clock){
        this.routes=routes;this.eligibility=eligibility;this.router=router;this.retrieval=retrieval;this.env=env;this.clock=clock;
        this.policy=new ConversateCueRoutingPolicy(routes,eligibility,env,clock);
    }
    public ConversateAnswerPipeline.Outcome answer(String question,List<String> recent,List<PreparedMaterialReader.Material> materials,boolean publicDisplay){
        return answer(question,recent,materials,publicDisplay,false);
    }
    public ConversateAnswerPipeline.Outcome answer(String question,List<String> recent,List<PreparedMaterialReader.Material> materials,boolean publicDisplay,boolean forceHint){
        return answer(question,recent,materials,publicDisplay,forceHint,0);
    }
    /** hintTargetChars<=0 resolves to the conversate.cue.hint-target-chars default. */
    public ConversateAnswerPipeline.Outcome answer(String question,List<String> recent,List<PreparedMaterialReader.Material> materials,boolean publicDisplay,boolean forceHint,int hintTargetChars){
        return answer(question,recent,materials,publicDisplay,forceHint,hintTargetChars,null);
    }
    public ConversateAnswerPipeline.Outcome answer(String question,List<String> recent,List<PreparedMaterialReader.Material> materials,boolean publicDisplay,boolean forceHint,int hintTargetChars,LensDisplayPrefs displayPrefs){
        int targetChars=hintTargetChars>0?Math.min(hintTargetChars,LensDisplayPrefs.MAX_TARGET_CHARS):limit("hint-target-chars",540,LensDisplayPrefs.MIN_TARGET_CHARS,LensDisplayPrefs.MAX_TARGET_CHARS);
        long began=System.nanoTime();var prior=TimeBudgetContext.get();
        long budget=prior==null?limit("total-timeout-ms",12000,2000,20000):prior.capWaitMillis(limit("total-timeout-ms",12000,2000,20000));
        TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime()+Math.max(0,budget)*1_000_000));
        var debug=new LinkedHashMap<String,Object>();
        debug.put("transcriptHash",org.apache.commons.codec.digest.DigestUtils.sha256Hex(question));
        debug.put("transcriptChars",question.length());debug.put("cueDecision","UNAVAILABLE");debug.put("decisionReason","GATE_UNAVAILABLE");
        debug.put("contextUsed",0);debug.put("ragNeeded",false);debug.put("searchNeeded",false);debug.put("ragDocuments",0);debug.put("fallback",false);
        debug.put("selectedProvider","none");debug.put("selectedModel","none");debug.put("hintHash","none");
        debug.put("retrievalMs",0L);debug.put("compressionMs",0L);debug.put("refinementMs",0L);debug.put("hintGenerationMs",0L);
        int attempts=0;boolean complex=false;int searchAttempts=0;var cost=new RequestCost(requestCostLimit());
        debug.put("maxRequestUsd",cost.remaining);debug.put("retrievalEstimatedCostUsd",0d);debug.put("billedCostUsd","not_observed");
        try{
            var window=ConversateHintInputWindow.select(boundedContext(recent),question,
                    env.getProperty("conversate.cue.use-past",Boolean.class,true),
                    limit("past-max-turns",4,1,12),limit("past-max-chars",1600,200,8192),displayPrefs);
            debug.put("pastSelectReason",window.reason());debug.put("pastChars",window.pastChars());
            List<String> selected=window.turns();
            debug.put("contextUsed",selected.size());
            long gateBegan=System.nanoTime();
            var gate=ConversateQuestionPolicy.cueDecision(question,selected);complex=gate.complex();
            debug.put("gateMs",elapsed(gateBegan));debug.put("gateProvider","local_rules");debug.put("gateModel","none");
            debug.put("gateAttempts",List.of());
            debug.put("forcedHintTrigger",forceHint);debug.put("normalHintTrigger",!forceHint);
            String cueDecision=gate.decision();
            if(forceHint&&"NO_CUE".equals(cueDecision)){cueDecision="CUE";debug.put("decisionReason","FORCED_TRANSCRIPT_DELTA");}
            else debug.put("decisionReason",gate.reason());
            debug.put("cueDecision",cueDecision);debug.put("decisionSource","local_rules");debug.put("hintGenerated",false);
            debug.put("topicChanged",gate.topicChanged());debug.put("complex",complex);
            debug.put("requiredQuality",gate.quality());
            if(gate.topicChanged()||!gate.contextRelevant())selected=List.of();
            debug.put("contextUsed",selected.size());debug.put("ragNeeded",cueDecision.equals("RAG_CUE"));
            debug.put("hintPath",cueDecision.equals("RAG_CUE")?"RAG":"FAST");
            if("NO_CUE".equals(cueDecision))return outcome("NO_CUE",null,attempts,0,0,debug,began);
            if(cancelled())return outcome("CANCELLED",null,attempts,0,0,debug,began);
            long refinementBegan=System.nanoTime();
            // Strip only a complete definition ending; the model still receives the original spoken question.
            String query=question.strip().replaceAll("\\s+"," ");
            query=query.replaceFirst("^(.{2,100}?)(?:이란|란|가)\\s+(?:뭐야|무엇인가요|뭔가요|뭐예요|뭐에요)[?？]?$","$1");
            if(!selected.isEmpty()&&query.matches("(?s)^(그럼|그러면|그것|그거|그 사람|그 내용|그 용어|그 뜻|이것|이거).*")){
                // A generated cue (including an evidence-gap message) is not the user's topic.
                for(int i=selected.size()-1;i>=0;i--){String priorTurn=selected.get(i);
                    if(priorTurn.startsWith("[assistant cue]")||priorTurn.startsWith("[User-selected TXT background;"))continue;
                    query=query+" "+priorTurn;break;
                }
            }
            debug.put("refinementMs",elapsed(refinementBegan));
            var evidence=new ArrayList<ConversateCardPrompt.Evidence>();var titles=new LinkedHashMap<String,String>();
            var retrievalTrace=new java.util.concurrent.ConcurrentHashMap<String,Object>();
            if(cueDecision.equals("RAG_CUE")){
                long retrievalBegan=System.nanoTime();
                // Local BM25 selects from caller-authorized prepared material, never a global/private corpus.
                var index=new LocalBm25Retriever();var prepared=new LinkedHashMap<String,PreparedMaterialReader.Material>();
                if(!publicDisplay)for(var material:materials){String id="p"+prepared.size();prepared.put(id,material);index.add(new LocalBm25Retriever.Doc(id,material.text()));}
                for(var match:index.topK(query,3)){var material=prepared.get(match.id);addEvidence(evidence,titles,material.text(),material.sourceId());}
                if(evidence.isEmpty()&&retrieval!=null&&!cancelled()&&costLimitsEnforced()&&cost.remaining<SINGLE_SEARCH_RESERVATION_USD)
                    debug.put("retrievalFailure","SEARCH_COST_BUDGET_EXHAUSTED");
                if(evidence.isEmpty()&&retrieval!=null&&!cancelled()&&(!costLimitsEnforced()||cost.remaining>=SINGLE_SEARCH_RESERVATION_USD)){
                    cost.remaining-=SINGLE_SEARCH_RESERVATION_USD;
                    debug.put("retrievalEstimatedCostUsd",SINGLE_SEARCH_RESERVATION_USD);
                    debug.put("searchNeeded",true);
                    searchAttempts=1;
                    for(var doc:selectCueDocuments(query,retrieve(query,retrievalTrace,debug,3000)))
                        if(doc!=null)addEvidence(evidence,titles,doc.snippet,doc.title);
                }
                debug.put("retrievalMs",elapsed(retrievalBegan));debug.put("ragDocuments",evidence.size());
                // Extractive compression preserves whole sentences and all quantities/negation; no local reasoning.
                long compressionBegan=System.nanoTime();evidence.replaceAll(e->new ConversateCardPrompt.Evidence(e.id(),e.sourceId(),compress(e.text())));
                debug.put("compressionMs",elapsed(compressionBegan));
                if(evidence.isEmpty()){debug.put("fallback",true);debug.put("softEvidenceAllow",true);
                    debug.put("fallbackReason",debug.getOrDefault("retrievalFailure","NO_RETRIEVAL_RESULTS"));}
            }
            debug.put("evidenceStatus",!cueDecision.equals("RAG_CUE")?"NOT_REQUIRED":evidence.isEmpty()?"EMPTY":usableEvidence(evidence).isEmpty()?"FRAGMENTED":"AVAILABLE");
            debug.put("usableEvidenceCount",usableEvidence(evidence).size());
            debug.put("contextChars",selected.stream().mapToInt(String::length).sum());
            debug.put("evidenceChars",evidence.stream().mapToInt(e->e.text().length()).sum());
            captureEvidenceDigests(debug,evidence);
            var finalContext=selected;var finalEvidence=List.copyOf(evidence);
            var hint=callGroundedHint(question,finalContext,finalEvidence,cueDecision.equals("RAG_CUE"),gate.quality(),cost,targetChars);
            attempts+=hint.attempts();long generationMs=hint.elapsedMs();
            var hintDiagnostics=new ArrayList<Map<String,Object>>(hint.diagnostics());
            if(hint.value()!=null&&hint.value().insufficient()&&canSupplement(retrievalTrace)&&!cancelled()
                    &&cost.attempts<limit("max-attempts-per-stage",3,1,3)
                    &&(!costLimitsEnforced()||cost.remaining>0)
                    &&cost.generationDeadline-System.nanoTime()>1_500_000_000L){
                retrievalTrace.put("conversate.web.ragSupplement",true);searchAttempts++;
                long supplementBegan=System.nanoTime();
                var additional=retrieve(query,retrievalTrace,debug,
                        Math.min(3000,Math.max(0,(cost.generationDeadline-System.nanoTime())/1_000_000-1500)));
                debug.put("retrievalMs",((Number)debug.get("retrievalMs")).longValue()+elapsed(supplementBegan));
                boolean added=false;
                for(var doc:additional){
                    if(doc==null||doc.snippet==null)continue;
                    String text=compress(doc.snippet);
                    if(text.isBlank()||evidence.stream().anyMatch(e->e.text().equals(text)))continue;
                    if(!added&&evidence.size()==3)evidence.remove(2);
                    if(evidence.size()==3)break;
                    addEvidence(evidence,titles,text,doc.title);added=true;
                }
                debug.put("ragDocuments",evidence.size());debug.put("evidenceChars",evidence.stream().mapToInt(e->e.text().length()).sum());
                if(added){
                    captureEvidenceDigests(debug,evidence);
                    var supplemented=List.copyOf(evidence);
                    int usableCount=usableEvidence(supplemented).size();
                    debug.put("usableEvidenceCount",usableCount);debug.put("evidenceStatus",usableCount==0?"FRAGMENTED":"AVAILABLE");
                    var enriched=callGroundedHint(question,finalContext,supplemented,true,gate.quality(),cost,targetChars);
                    attempts+=enriched.attempts();generationMs+=enriched.elapsedMs();hintDiagnostics.addAll(enriched.diagnostics());
                    if(enriched.value()!=null&&!enriched.value().insufficient()){
                        hint=enriched;debug.put("supplementStatus","GROUNDED");debug.put("evidenceStatus","AVAILABLE");
                    }else{
                        debug.put("supplementStatus","RETAINED_GENERAL_HINT");
                        debug.put("supplementFailure",enriched.value()==null?enriched.failure():"EVIDENCE_INSUFFICIENT");
                    }
                }
            }
            debug.put("selectedProvider",hint.provider());debug.put("selectedModel",hint.model());debug.put("hintGenerationMs",generationMs);
            debug.put("hintAttempts",List.copyOf(hintDiagnostics));
            debug.put("fallback",Boolean.TRUE.equals(debug.get("fallback"))||hint.attempts()>1||hint.value()==null);
            if(hint.value()==null)return supportFallback(hint.failure(),question,selected,attempts,complex?1:0,searchAttempts,debug,began,true);
            if(cancelled())return outcome("CANCELLED",null,attempts,complex?1:0,searchAttempts,debug,began);
            var value=hint.value();debug.put("hintHash",org.apache.commons.codec.digest.DigestUtils.sha256Hex(value.text()));
            debug.put("evidenceInsufficient",value.insufficient());
            if(value.insufficient()){
                debug.put("hintPath","GENERAL_HINT");debug.put("fallback",true);
                if("AVAILABLE".equals(debug.get("evidenceStatus")))debug.put("evidenceStatus","INSUFFICIENT");
                debug.putIfAbsent("fallbackReason","EVIDENCE_INSUFFICIENT");
            }
            debug.put("citedEvidenceIds",value.ids());
            debug.put("hintTextChars",value.text().codePointCount(0,value.text().length()));
            var card=new ConversateSessionService.Card("SHOW",gate.decision(),value.text(),value.ids(),clock.millis()+15000);
            debug.put("cardTextChars",card.text().codePointCount(0,card.text().length()));
            debug.put("hintGenerated",card!=null);if(debug.get("selectedModel")==null)debug.put("selectedModel","unobserved");return outcome(value.insufficient()?"CUE_EVIDENCE_INSUFFICIENT":"API_CUE",card,attempts,complex?1:0,searchAttempts,debug,began);
        }finally{if(prior==null)TimeBudgetContext.clear();else TimeBudgetContext.set(prior);}
    }
    /** One explicitly requested local verification: no gate, retrieval, retry or fallback. */
    public ConversateAnswerPipeline.Outcome answerDirectOpenAi(String question,String requestId){
        long began=System.nanoTime();var prior=TimeBudgetContext.get();
        long budget=prior==null?12000:prior.capWaitMillis(12000);
        TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime()+Math.max(0,budget)*1_000_000));
        var debug=new LinkedHashMap<String,Object>();
        debug.put("requestId",requestId);debug.put("verificationMode","openai-direct");
        debug.put("transcriptHash",org.apache.commons.codec.digest.DigestUtils.sha256Hex(question));
        debug.put("fallback",false);debug.put("retryAllowed",false);debug.put("contextUsed",0);
        debug.put("ragDocuments",0);debug.put("retrievalMs",0L);debug.put("hintGenerationMs",0L);
        try(var receipt=com.example.lms.llm.ModelRuntimeHealthTracker.beginDirectOpenAiReceipt()){
            var result=call(ConversateCardPrompt.cueHint(question,List.of(),List.of(),false,limit("hint-target-chars",540,LensDisplayPrefs.MIN_TARGET_CHARS,LensDisplayPrefs.MAX_TARGET_CHARS)),false,1,
                    new RequestCost(requestCostLimit()),node->parseHint(node,List.of(),false),true);
            debug.put("selectedProvider",result.provider());debug.put("selectedModel",result.model());
            debug.put("hintGenerationMs",result.elapsedMs());debug.put("failureReason",result.failure());
            debug.put("hintAttempts",result.diagnostics());
            if(!result.diagnostics().isEmpty())for(String field:List.of("responseModel","providerResponseId","actualTokens","cachedInputTokens")){
                Object value=result.diagnostics().get(0).get(field);if(value!=null)debug.put(field,value);
            }
            debug.putAll(receipt.snapshot());
            ConversateSessionService.Card card=null;
            if(result.value()!=null){
                String text=result.value().text();debug.put("hintHash",org.apache.commons.codec.digest.DigestUtils.sha256Hex(text));
                debug.put("hintTextChars",text.codePointCount(0,text.length()));
                card=new ConversateSessionService.Card("SHOW","API_DIRECT",text,List.of(),clock.millis()+15000);
            }
            return outcome(card==null?"OPENAI_DIRECT_ERROR":"OPENAI_DIRECT_OK",card,result.attempts(),0,0,debug,began);
        }finally{if(prior==null)TimeBudgetContext.clear();else TimeBudgetContext.set(prior);}
    }
    private static boolean canSupplement(Map<String,Object> trace){
        return !trace.containsKey("conversate.search.BRAVE")
                &&trace.get("conversate.search.NAVER") instanceof Map<?,?> naver
                &&"NONE".equals(naver.get("failureReason"))
                &&naver.get("resultCount") instanceof Number count&&count.intValue()>0;
    }
    private List<UnifiedRagOrchestrator.Doc> retrieve(String query,Map<String,Object> trace,Map<String,Object> debug,long maxWaitMs){
        if(maxWaitMs<=0)return List.of();
        var request=new UnifiedRagOrchestrator.QueryRequest();request.query=query;
        request.useWeb=true;request.useVector=false;request.useKg=false;request.useBm25=false;
        request.enableQueryAnalysis=false;request.webQueryAlreadyPlanned=true;
        request.enableSelfAsk=false;request.enableDiversity=false;request.enableBiEncoder=false;request.enableOnnx=false;
        // One provider request returns a bounded candidate pool. Select subject
        // coverage before the three-evidence cap, so an author's biography does
        // not displace a definition of the requested concept.
        request.memoryProfile="NONE";request.topK=8;request.webTopK=8;
        var inherited=TimeBudgetContext.get();var priorTrace=TraceStore.context();
        trace.putIfAbsent("trace.runId",UUID.randomUUID().toString());trace.put("conversate.web.singleCycle",true);
        TraceStore.installContext(trace);
        TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime()+Math.min(maxWaitMs,Math.max(0,inherited.remainingMillis()-1500))*1_000_000));
        try{
            var result=retrieval.query(request);
            if(result!=null&&result.debug!=null)for(String field:List.of("plan.allowWeb","plan.webTopK","stage.web","web.retriever","analysis.skipped","analysis.elapsedMs")){
                Object value=result.debug.get(field);
                boolean valid=switch(field){
                    case "plan.allowWeb","analysis.skipped" -> value instanceof Boolean;
                    case "analysis.elapsedMs" -> value instanceof Long n&&n>=0&&n<=1_000_000;
                    case "plan.webTopK" -> value instanceof Integer n&&n>=0&&n<=1_000_000;
                    case "stage.web" -> value instanceof String s&&s.matches("disabled|missing_webRetriever|empty_result|success:[0-9]{1,5}|failed:web_retrieval_failed");
                    case "web.retriever" -> value instanceof String s&&Set.of("base","nova","other").contains(s);
                    default -> false;
                };
                if(valid)debug.put("retrieval."+field,value);
            }
            return result==null||result.results==null?List.of():result.results;
        }catch(RuntimeException unavailable){debug.put("retrievalFailure","SEARCH_UNAVAILABLE");return List.of();}
        finally{
            captureRetrievalReceipt(debug,trace,failureRecorder);
            debug.put("retrieval.traceContextPreserved",TraceStore.context()==trace);
            debug.put("retrieval.traceEntryCount",Math.min(1_000_000,TraceStore.context().size()));
            TraceStore.installContext(priorTrace);TimeBudgetContext.set(inherited);
        }
    }
    private static void captureRetrievalReceipt(Map<String,Object> debug,Map<String,Object> trace,com.example.lms.debug.ApiFailureRecorder recorder){
        int observed=0;
        var drops=new LinkedHashMap<String,Integer>();
        var dropReasons=Set.of("tech_spam","duplicate_key","host_duplicate","officialOnly_clamped_fallback_candidate",
                "officialOnly_exclude_devCommunity","officialOnly_stage_excluded","stage_excluded","target_filled","not_considered","not_selected");
        if(trace.get("web.failsoft.runs") instanceof List<?> runs)
            for(Object run:runs)if(run instanceof Map<?,?> row&&row.get("candidates") instanceof List<?> candidates)
                for(Object candidate:candidates)if(candidate instanceof Map<?,?> values&&values.get("dropReason") instanceof String reason&&dropReasons.contains(reason))
                    drops.merge(reason,1,Integer::sum);
        if(!drops.isEmpty())debug.put("retrieval.dropReasonCounts",Map.copyOf(drops));
        if(trace.get("web.failsoft.outCount") instanceof String count&&count.matches("[0-9]{1,6}"))
            debug.put("retrieval.web.failsoft.outCount",Integer.parseInt(count));
        for(String provider:List.of("NAVER","BRAVE")){
            Object receipt=trace.get("conversate.search."+provider);
            if(receipt instanceof Map<?,?> row){
                var safe=new LinkedHashMap<String,Object>();
                safe.put("provider",provider);safe.put("searchNeeded",true);
                if("provider_search_method".equals(row.get("requestCountScope")))safe.put("requestCountScope","provider_search_method");
                for(String field:List.of("requestCount","resultCount","latencyMs"))
                    if(row.get(field) instanceof Number n&&n.longValue()>=0&&n.longValue()<=1_000_000)safe.put(field,n.longValue());
                for(String field:List.of("fallbackReason","failureReason"))
                    if(row.get(field) instanceof String s&&Set.of("none","wider_or_fresh_evidence","insufficient_results","low_relevance_or_coverage","rag_evidence_insufficient",
                            "NONE","TRUE_ZERO","FILTER_ZERO","UNKNOWN","AUTH_OR_CONFIG","RATE_LIMIT","TIMEOUT_OR_BUDGET","PROVIDER_ERROR",
                            "BREAKER_OR_COOLDOWN","PARSE_ERROR","CLIENT_CANCELLED").contains(s))safe.put(field,s);
                // This map belongs to this retrieval invocation. Count completed client observations,
                // never shared scalar counters; absence is not proof of cache reuse or zero requests.
                var clientAttempts=new HashSet<String>();var httpResponses=new HashSet<String>();
                String attemptKey=provider.equals("NAVER")?"web.naver.filter.runs":"web.brave.attempt.runs";
                String boundary=provider.equals("NAVER")?"webclient_subscription":"resttemplate_exchange";
                if(trace.get(attemptKey) instanceof List<?> attempts)for(Object attempt:attempts)
                    if(attempt instanceof Map<?,?> values
                            &&provider.toLowerCase(Locale.ROOT).equals(values.get("provider"))
                            &&Boolean.TRUE.equals(values.get("clientAttemptObserved"))
                            &&boundary.equals(values.get("clientAttemptBoundary"))
                            &&values.get("providerAttemptId") instanceof String id&&id.matches("hash:[a-f0-9]{12}")
                            &&values.get("finishedAtEpochMs") instanceof Number finished&&finished.longValue()>0){
                        clientAttempts.add(id);
                        if(values.get("httpStatus") instanceof Number status&&status.intValue()>=100&&status.intValue()<=599)httpResponses.add(id);
                    }
                safe.put("clientAttemptCoverage",clientAttempts.isEmpty()?"not_observed":"observed");
                if(!clientAttempts.isEmpty()){
                    safe.put("clientAttemptCount",clientAttempts.size());safe.put("httpResponseCount",httpResponses.size());
                }
                // Request/auth/quota/timeout failures are incidents; honest zero-result reasons are not.
                if(recorder!=null&&row.get("failureReason") instanceof String reason)try{
                    recorder.recordSearch(provider.toLowerCase(Locale.ROOT),"not_applicable",reason);
                }catch(RuntimeException ignored){}
                debug.put("search."+provider,Map.copyOf(safe));observed++;
            }
        }
        for(String key:List.of("web.analyze.requestedCount","web.analyze.returnedCount","web.boundedRoute.providerCycles",
                "web.boundedRoute.geminiAttempts","web.boundedRoute.outCount","web.provider.resultCount","web.failsoft.rawInputCount","web.failsoft.outCount")){
            Object value=trace.get(key);
            if(value instanceof Integer n&&n>=0&&n<=1_000_000){debug.put("retrieval."+key,n);observed++;}
        }
        for(String key:List.of("web.analyze.providerDisabled","web.boundedRoute.completed","web.analyze.queryPlanningSkipped")){
            Object value=trace.get(key);if(value instanceof Boolean){debug.put("retrieval."+key,value);observed++;}
        }
        Object preparationMs=trace.get("web.analyze.preparationMs");
        if(preparationMs instanceof Long n&&n>=0&&n<=1_000_000){debug.put("retrieval.web.analyze.preparationMs",n);observed++;}
        var reasons=Set.of("ok","provider-empty","web_provider_missing","disabled","breaker_open","breaker_open_or_half_open",
                "cooldown","submit_failed","hedge_skip","cancelled","budget_exhausted","initial-hit","research-hit","research-empty",
                "gemini-gateway-unavailable","gemini-expansion-failed","gemini-expansion-empty","query-constraints-changed",
                "TRUE_ZERO","UNKNOWN","AUTH_OR_CONFIG","TIMEOUT_OR_BUDGET","RATE_LIMIT","BREAKER_OR_COOLDOWN",
                "PARSE_ERROR","CLIENT_CANCELLED","PROVIDER_ERROR");
        for(String key:List.of("web.analyze.skipped.reason","web.boundedRoute.terminalReason","web.brave.skipped.reason",
                "web.naver.skipped.reason","web.hybrid.execution.stopReason")){
            Object value=trace.get(key);if(value instanceof String s&&reasons.contains(s)){debug.put("retrieval."+key,s);observed++;}
        }
        debug.put("retrieval.traceObserved",observed>0);
    }
    private ConversateAnswerPipeline.Outcome outcome(String reason,ConversateSessionService.Card card,int attempts,int complex,int search,Map<String,Object> debug,long began){
        double reserved=((Number)debug.getOrDefault("retrievalEstimatedCostUsd",0d)).doubleValue();
        long inputTokens=0,outputTokens=0,reasoningTokens=0;int observed=0,tracked=0,reasoningObserved=0;
        double usageEstimate=0;int costObserved=0;
        for(String stage:List.of("gateAttempts","hintAttempts"))
            if(debug.get(stage) instanceof List<?> rows)for(Object row:rows)
                if(row instanceof Map<?,?> values){
                    if("routeSelection".equals(values.get("rowKind")))continue;
                    tracked++;
                    if(values.get("reservedCostUsd") instanceof Number amount)reserved+=amount.doubleValue();
                    if(values.get("actualTokens") instanceof Map<?,?> usage&&usage.get("input") instanceof Number in&&usage.get("output") instanceof Number out){
                        inputTokens+=in.longValue();outputTokens+=out.longValue();observed++;
                    }
                    if(values.get("reasoningTokens") instanceof Number count){reasoningTokens+=count.longValue();reasoningObserved++;}
                    if(values.get("usageEstimatedCostUsd") instanceof Number amount){usageEstimate+=amount.doubleValue();costObserved++;}
                }
        debug.put("observedInputTokens",observed>0||tracked==0?inputTokens:"not_observed");
        debug.put("observedOutputTokens",observed>0||tracked==0?outputTokens:"not_observed");
        debug.put("observedReasoningTokens",reasoningObserved>0||tracked==0?reasoningTokens:"not_observed");
        debug.put("reasoningUsageCoverage",tracked==0?"no_api_calls":reasoningObserved==0?"not_observed":reasoningObserved==tracked?"complete":"partial");
        debug.put("tokenUsageCoverage",tracked==0?"no_api_calls":observed==0?"not_observed":observed==tracked?"complete":"partial");
        debug.put("usageEstimatedCostUsd",costObserved>0||tracked==0?usageEstimate:"not_observed");
        debug.put("usageCostCoverage",tracked==0?"no_api_calls":costObserved==0?"not_observed":costObserved==tracked?"complete":"partial");
        debug.put("costEvidence","configured_model_rates_not_invoice");
        debug.put("stageCalls",Map.of("cueAdmission",0,"retrieval",search,"finalGeneration",attempts,"transcriptPostprocess",0));
        debug.put("costLimitsEnforced",costLimitsEnforced());debug.put("currency","USD");
        debug.put("requestReservedUsd",reserved);
        debug.put("totalLatencyMs",elapsed(began));debug.put("status",reason);debug.put("apiAttempts",attempts);
        LOG.info("conversate.cue {}",debug);
        return new ConversateAnswerPipeline.Outcome(reason,card,attempts,complex,((Number)debug.get("hintGenerationMs")).longValue(),search,0)
                .observed(new ConversateAnswerPipeline.Stages(null,((Number)debug.get("ragDocuments")).intValue(),null,null,null,
                        ((Number)debug.get("retrievalMs")).longValue(),null,null,Map.copyOf(debug)));
    }
    private <T> CallResult<T> call(ConversateCardPrompt.Request request,boolean gate,int quality,RequestCost cost,Function<JsonNode,T> parse){
        return call(request,gate,quality,cost,parse,false);
    }
    private <T> CallResult<T> call(ConversateCardPrompt.Request request,boolean gate,int quality,RequestCost cost,Function<JsonNode,T> parse,boolean direct){
        long began=System.nanoTime();String failure="API_UNAVAILABLE",provider="none",model="none";int attempts=0;
        var diagnostics=new ArrayList<Map<String,Object>>();
        long stageMs=Math.min(TimeBudgetContext.get().remainingMillis(),direct?12000:limit(gate?"gate-timeout-ms":"hint-timeout-ms",gate?3000:6500,500,10000));
        if(cost.generationDeadline==0)cost.generationDeadline=System.nanoTime()+Math.max(0,stageMs)*1_000_000;
        long deadline=cost.generationDeadline;var used=new HashSet<String>();
        var failedRoutes=new ArrayList<String[]>();
        if(direct)routes.getModels().forEach((key,cfg)->{
            if(!"openai".equalsIgnoreCase(cfg.getProvider())||cfg.getBaseUrl()==null||
                    !cfg.getBaseUrl().matches("https://api\\.openai\\.com(?::443)?/v1/?"))used.add(key);
        });
        int maxAttempts=Math.max(0,(direct?1:limit("max-attempts-per-stage",3,1,3))-cost.attempts);
        // UTF-8 bytes plus message framing are a conservative bound, not a claim of provider tokenization.
        int inputTokens=request.messages().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+64;
        int outputTokens=limit("max-output-tokens",1024,128,2048);
        var cueJsonSchema=ConversateCardPrompt.wireSchema(request.schema(),"conversate_cue");
        var skippedRoutes=new ArrayList<Map<String,Object>>();
        while(attempts<maxAttempts&&!cancelled()&&System.nanoTime()<deadline){
            long remaining=(deadline-System.nanoTime())/1_000_000;
            var demand=new ConversateCueRoutingPolicy.Demand(gate,quality,inputTokens,outputTokens,remaining,cost.remaining);
            skippedRoutes.clear();
            var reservation=policy.reserve(demand,used,skippedRoutes);if(reservation==null)break;
            var choice=reservation.choice();String key=choice.key();used.add(key);var cfg=routes.getModels().get(key);
            cost.remaining=Math.max(0,cost.remaining-reservation.reservedUsd());
            provider=safeLabel(cfg.getProvider());model=safeLabel(cfg.getName());attempts++;cost.attempts++;
            long started=System.nanoTime();var inherited=TimeBudgetContext.get();
            boolean alternative=attempts<maxAttempts
                    &&!policy.candidates(new ConversateCueRoutingPolicy.Demand(gate,quality,inputTokens,outputTokens,remaining,cost.remaining),used).isEmpty();
            // Reserve fallback time only when another eligible route can actually use it.
            long attemptMs=!alternative?remaining:gate?Math.max(1,remaining/2):
                    Math.min(remaining,Math.max(remaining/2,choice.expectedLatencyMs()*3/2));
            TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime()+Math.max(0,attemptMs)*1_000_000));
            var attempt=new LinkedHashMap<String,Object>();attempt.put("provider",provider);attempt.put("model",model);
            attempt.put("selectedModel",model);attempt.put("estimatedCost",choice.estimatedCost());attempt.put("currency","USD");
            attempt.put("reservedCostUsd",reservation.reservedUsd());
            attempt.put("actualTokens","not_observed");attempt.put("reasoningTokens","not_observed");attempt.put("visibleOutputTokens","not_observed");
            attempt.put("usageEstimatedCostUsd","not_observed");attempt.put("cacheHit","not_observed");attempt.put("quotaState",choice.quotaState());
            attempt.put("recentSuccessRate",choice.successRate());attempt.put("serviceTier","standard");
            attempt.put("fallbackReason",attempts==1?"none":failure);
            attempt.put("structuredOutput",Set.of("openai","gemini").contains(provider.toLowerCase(Locale.ROOT))?"json_schema":"json_object");
            attempt.put("routesSkipped",List.copyOf(skippedRoutes));
            attempt.put("escalationReason",gate||quality<=2?"none":quality==4?"EXPERT_DIFFICULTY":"REASONING_DIFFICULTY");
            dev.langchain4j.model.output.TokenUsage usage=null;T value=null;boolean success=false;String attemptFailure="none";
            try{
                var response=router.apiAttempt(key,(int)Math.max(1,attemptMs),outputTokens,cueJsonSchema).chat(request.messages());
                usage=response==null?null:response.tokenUsage();recordUsage(attempt,usage);
                if(attempt.get("actualTokens") instanceof Map<?,?>)
                    attempt.put("usageEstimatedCostUsd",policy.estimate(key,usage.inputTokenCount(),usage.outputTokenCount()));
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                // A completed valid response may outlive its advisory slice but never the stage/request deadline.
                if(inherited.expired()||System.nanoTime()>=deadline)throw new java.util.concurrent.TimeoutException();
                String raw=response==null||response.aiMessage()==null?null:response.aiMessage().text();
                attempt.put("outputChars",raw==null?0:raw.length());
                String finishReason=response==null?"none":String.valueOf(response.finishReason());
                attempt.put("finishReason",finishReason);attempt.put("truncatedByTokenBudget","length".equalsIgnoreCase(finishReason));
                attempt.put("responseModel",response==null?"none":safeLabel(response.modelName()));
                if(direct&&response!=null&&response.id()!=null)attempt.put("providerResponseId",safeLabel(response.id()));
                if(raw==null||raw.isBlank()||raw.length()>4096)throw new IllegalArgumentException("cue_invalid_output");
                attempt.put("outputHash",org.apache.commons.codec.digest.DigestUtils.sha256Hex(raw));
                JsonNode node;
                try{node=JSON.readTree(raw);attempt.put("jsonRecoveryUsed",false);}
                catch(com.fasterxml.jackson.core.JsonProcessingException malformed){
                    node=JSON.readTree(extractJsonObject(raw));attempt.put("jsonRecoveryUsed",true);}
                if(node!=null&&node.path("text").isTextual()){
                    String text=node.path("text").asText();
                    attempt.put("outputTextChars",text.codePointCount(0,text.length()));
                }
                if(node!=null&&node.path("evidenceIds").isArray())attempt.put("outputEvidenceCount",node.path("evidenceIds").size());
                value=parse.apply(node);success=true;attempt.put("status","ok");
            }catch(Exception error){
                failure=Thread.currentThread().isInterrupted()||com.example.lms.llm.gateway.LlmGatewayFailureClassifier.isCancellation(error)?"CANCELLED":
                        error instanceof java.util.concurrent.TimeoutException?"GENERATION_TIMEOUT":
                        quotaExhausted(error)?"API_QUOTA_EXHAUSTED":attempt.containsKey("outputChars")?"GENERATION_INVALID_OUTPUT":
                        error instanceof RuntimeException r?ConversateLocalCardGenerator.classify(r):"GENERATION_INPUT_REJECTED";
                attemptFailure=failure;attempt.put("errorType",error.getClass().getSimpleName());attempt.put("status",failure);
                if("GENERATION_INVALID_OUTPUT".equals(failure))attempt.put("outputValidation",outputValidation(error));
                // A later fallback may serve the user; the original failure still goes on record here.
                if(!"CANCELLED".equals(failure))failedRoutes.add(new String[]{provider,model});
                if(failureRecorder!=null)try{
                    if("GENERATION_INVALID_OUTPUT".equals(failure))
                        failureRecorder.recordValidation(provider,model,String.valueOf(attempt.get("outputValidation")));
                    else if("API_QUOTA_EXHAUSTED".equals(failure))failureRecorder.recordFailureClass(provider,model,"API_QUOTA_EXHAUSTED");
                    else if(error instanceof com.example.lms.llm.gateway.LlmGatewayException gateway)
                        failureRecorder.recordFailureClass(provider,model,gateway.failureClass().name());
                    else failureRecorder.record(provider,model,0,null,error);
                }catch(RuntimeException ignored){}
            }finally{
                long latency=elapsed(started);attempt.put("latencyMs",latency);
                policy.complete(reservation,success,latency,attemptFailure,usage);
                diagnostics.add(Map.copyOf(attempt));TimeBudgetContext.set(inherited);
            }
            if(success){
                // Fallback success serves the user; failed primaries keep their incident rows marked masked.
                if(failureRecorder!=null)try{
                    failureRecorder.recordSuccess(provider,model);
                    for(String[] failed:failedRoutes)
                        if(!(failed[0].equals(provider)&&failed[1].equals(model)))
                            failureRecorder.markMasked(failed[0],failed[1],provider,model);
                }catch(RuntimeException ignored){}
                return new CallResult<>(value,provider,model,attempts,"none",elapsed(began),List.copyOf(diagnostics));
            }
            if("CANCELLED".equals(failure))break;
        }
        // With zero attempts the skip reasons are the only evidence of why no route was tried.
        if(diagnostics.isEmpty()&&!skippedRoutes.isEmpty())
            diagnostics.add(Map.of("rowKind","routeSelection","status",failure,"routesSkipped",List.copyOf(skippedRoutes)));
        return new CallResult<>(null,provider,model,attempts,failure,elapsed(began),List.copyOf(diagnostics));
    }
    /** Current cue-route health for the diagnostics view; configured provider/model names only, no secrets. */
    public List<Map<String,Object>> routeHealth(){
        return policy.healthSnapshot();
    }
    List<String> candidates(boolean gate,boolean complex,Set<String> used,int inputChars){
        return policy.candidates(new ConversateCueRoutingPolicy.Demand(gate,complex?3:1,inputChars,1024,
                limit(gate?"gate-timeout-ms":"hint-timeout-ms",gate?3000:6500,500,10000),requestCostLimit()),used)
                .stream().map(ConversateCueRoutingPolicy.Choice::key).toList();
    }
    private static void recordUsage(Map<String,Object> target,dev.langchain4j.model.output.TokenUsage usage){
        if(usage==null||usage.inputTokenCount()==null||usage.inputTokenCount()<=0||usage.outputTokenCount()==null||usage.outputTokenCount()<0)return;
        var counts=new LinkedHashMap<String,Object>();counts.put("input",usage.inputTokenCount());counts.put("output",usage.outputTokenCount());
        if(usage.totalTokenCount()!=null&&usage.totalTokenCount()>=0)counts.put("total",usage.totalTokenCount());
        target.put("actualTokens",Map.copyOf(counts));
        if(usage instanceof dev.langchain4j.model.openai.OpenAiTokenUsage openai&&openai.inputTokensDetails()!=null){
            Integer cached=openai.inputTokensDetails().cachedTokens();
            if(cached!=null&&cached>=0&&cached<=usage.inputTokenCount()){target.put("cacheHit",cached>0);target.put("cachedInputTokens",cached);}
        }
        if(usage instanceof dev.langchain4j.model.openai.OpenAiTokenUsage openai&&openai.outputTokensDetails()!=null){
            Integer reasoning=openai.outputTokensDetails().reasoningTokens();
            if(reasoning!=null&&reasoning>=0&&reasoning<=usage.outputTokenCount()){
                target.put("reasoningTokens",reasoning);target.put("visibleOutputTokens",usage.outputTokenCount()-reasoning);
            }
        }
    }
    private static final Set<String> QUOTA_ERROR_CODES=Set.of("insufficient_quota","credit_balance_exhausted","spend_limit_exceeded","blocked_api_access");
    private static boolean quotaExhausted(Throwable error){
        for(int depth=0;error!=null&&depth<20;depth++,error=error.getCause()){
            if(error instanceof com.example.lms.llm.gateway.LlmGatewayException gateway&&QUOTA_ERROR_CODES.contains(gateway.reasonCode()))return true;
            if(error instanceof dev.langchain4j.exception.HttpException http){
                String body=http.getMessage();if(body==null||body.length()>16384)continue;
                try{String code=JSON.readTree(body).path("error").path("code").asText();
                    if(QUOTA_ERROR_CODES.contains(code))return true;
                }catch(Exception ignored){/* Malformed body is never retained or logged. */}
            }
        }return false;
    }
    private ConversateAnswerPipeline.Outcome supportFallback(String reason,String question,List<String> context,int attempts,int complex,int search,Map<String,Object> debug,long began,boolean useful){
        debug.put("fallbackReason",reason);
        if(useful&&localSupport!=null&&!cancelled()&&env.getProperty("conversate.cue.local-support-enabled",Boolean.class,true)){
            var bounded=boundedContext(context,8,4096);
            var result=localSupport.suggestSupport(question,bounded,clock.millis());
            debug.put("localSupportAttempts",result.attempts());debug.put("localSupportReason",result.reason());
            if(result.attempts()>0)debug.put("localSupportCall",Map.of("selectedModel",safeLabel(env.getProperty("conversate.generation.model","local-support")),
                    "estimatedCost",0,"actualTokens","not_observed","cacheHit","not_observed","fallbackReason",reason,
                    "latencyMs",result.elapsedMs(),"escalationReason","LOCAL_SUPPORT_ONLY"));
            if(result.card()!=null&&"SHOW".equals(result.card().decision())){
                debug.put("fallback",true);debug.put("decisionSource","local_support");
                debug.put("selectedProvider","local");debug.put("selectedModel",safeLabel(env.getProperty("conversate.generation.model","local-support")));
                return outcome("LOCAL_SUPPORT_FALLBACK",result.card(),attempts,complex,search,debug,began);
            }
        }
        return outcome(reason,null,attempts,complex,search,debug,began);
    }
    private boolean costLimitsEnforced(){return env.getProperty("conversate.cost.enforce-limits",Boolean.class,true);}
    private double requestCostLimit(){if(!costLimitsEnforced())return 0;Double value=env.getProperty("conversate.cue.max-request-usd",Double.class,.50);return value!=null&&Double.isFinite(value)&&value>=0?Math.min(value,.50):0;}
    private int limit(String field,int fallback,int min,int max){return Math.max(min,Math.min(max,env.getProperty("conversate.cue."+field,Integer.class,fallback)));}
    private static List<String> boundedContext(List<String> context){return boundedContext(context,12,8192);}
    private static List<String> boundedContext(List<String> context,int turns,int limit){
        var result=new ArrayDeque<String>();int chars=0;String background=null;
        for(int i=0;i<context.size();i++){String turn=context.get(i);if(turn==null||turn.isBlank())continue;
            if(i==0&&turn.startsWith("[User-selected TXT background; untrusted data]\n")&&turn.length()<=8100){background=turn;continue;}
            result.add(turn);chars+=turn.length();while(result.size()>turns||(result.size()>2&&chars>limit))chars-=result.removeFirst().length();
        }
        if(background!=null)result.addFirst(background);return List.copyOf(result);
    }
    /** Providers may wrap the JSON object in markdown fences or prose; strict schema validation still applies to the extracted object. */
    private static String extractJsonObject(String raw){
        int start=raw.indexOf('{');if(start<0)return raw;
        int depth=0;boolean inString=false,escape=false;
        for(int i=start;i<raw.length();i++){
            char c=raw.charAt(i);
            if(escape){escape=false;continue;}
            if(inString){if(c=='\\')escape=true;else if(c=='"')inString=false;continue;}
            if(c=='"')inString=true;else if(c=='{')depth++;else if(c=='}'&&--depth==0)return raw.substring(start,i+1);
        }
        return raw;
    }
    private static String outputValidation(Exception error){
        if(error instanceof com.fasterxml.jackson.core.JsonProcessingException)return "json_invalid";
        if(error instanceof IllegalArgumentException&&error.getMessage()!=null)return switch(error.getMessage()){
            case "cue_invalid_output" -> "output_limit";
            case "cue_hint_invalid" -> "schema_invalid";
            case "cue_hint_limit" -> "text_limit";
            case "cue_evidence_invalid" -> "evidence_invalid";
            case "cue_evidence_missing" -> "evidence_missing";
            default -> "unclassified";
        };
        return "unclassified";
    }
    private static Hint parseHint(JsonNode node,List<ConversateCardPrompt.Evidence> evidence,boolean rag){
        if(node==null||!node.isObject()||!node.path("text").isTextual()||!node.path("evidenceIds").isArray()
                ||!(node.size()==2||node.size()==3&&node.path("evidenceInsufficient").isBoolean()))throw new IllegalArgumentException("cue_hint_invalid");
        String text=node.path("text").asText().strip();if(text.isBlank()||text.codePointCount(0,text.length())>ConversateSessionService.HINT_TEXT_MAX||text.lines().count()>ConversateSessionService.HINT_LINE_MAX)throw new IllegalArgumentException("cue_hint_limit");
        var allowed=evidence.stream().map(ConversateCardPrompt.Evidence::id).toList();var ids=new ArrayList<String>();
        for(var id:node.path("evidenceIds")){if(!id.isTextual()||!allowed.contains(id.asText())||ids.contains(id.asText()))throw new IllegalArgumentException("cue_evidence_invalid");ids.add(id.asText());}
        boolean insufficient=node.path("evidenceInsufficient").asBoolean(false);
        if(insufficient){
            if(!rag||!ids.isEmpty())throw new IllegalArgumentException("cue_evidence_invalid");
            return new Hint(text,List.of(),true);
        }
        if(ids.size()>4||!evidence.isEmpty()&&ids.isEmpty())throw new IllegalArgumentException("cue_evidence_missing");
        return new Hint(text,List.copyOf(ids),rag&&evidence.isEmpty());
    }
    private static List<ConversateCardPrompt.Evidence> usableEvidence(List<ConversateCardPrompt.Evidence> evidence){
        return evidence.stream().filter(e->!e.text().startsWith("[WEB:")
                ||java.util.regex.Pattern.compile("\\.{3,}|…").matcher(e.text()).results().limit(2).count()<2).toList();
    }
    private CallResult<Hint> callGroundedHint(String question,List<String> context,List<ConversateCardPrompt.Evidence> evidence,boolean rag,int quality,RequestCost cost,int targetChars){
        // Fragmented search snippets cannot support exact claims, but must not
        // suppress a useful general cue or restart the shared generation budget.
        var usable=rag?usableEvidence(evidence):evidence;
        return call(ConversateCardPrompt.cueHint(question,context,usable,rag,targetChars),false,quality,cost,node->parseHint(node,usable,rag));
    }
    static List<UnifiedRagOrchestrator.Doc> selectCueDocuments(String query,List<UnifiedRagOrchestrator.Doc> docs){
        if(docs==null)return List.of();
        var terms=Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(s->s.length()>=2).distinct().toList();
        return docs.stream().filter(Objects::nonNull).limit(8)
                .sorted(Comparator.comparingLong((UnifiedRagOrchestrator.Doc doc)->{
                    String title=Objects.toString(doc.title,"");
                    var anchor=java.util.regex.Pattern.compile("(?is)<a\\b[^>]*>(.*?)</a>").matcher(Objects.toString(doc.snippet,""));
                    if(anchor.find())title=org.jsoup.Jsoup.parse(anchor.group(1)).text();
                    String selectedTitle=title.toLowerCase(Locale.ROOT);
                    return terms.stream().filter(selectedTitle::contains).count();
                }).reversed()).limit(3).toList();
    }
    private static void captureEvidenceDigests(Map<String,Object> debug,List<ConversateCardPrompt.Evidence> evidence){
        debug.put("evidenceDigests",evidence.stream().map(e->Map.of("id",e.id(),"sha256",
                org.apache.commons.codec.digest.DigestUtils.sha256Hex(e.text()))).toList());
    }
    private static void addEvidence(List<ConversateCardPrompt.Evidence> list,Map<String,String> titles,String text,String title){
        if(text==null||text.isBlank()||list.size()>=3)return;String compressed=compress(text);if(compressed.isBlank())return;
        String id="e"+list.size();list.add(new ConversateCardPrompt.Evidence(id,id,compressed));titles.put(id,title==null?"근거 "+(list.size()):title);}
    private static String compress(String text){if(text.length()<=2048)return text;StringBuilder out=new StringBuilder();for(String sentence:text.split("(?<=[.!?。])\\s+|\\R")){if(out.length()+sentence.length()+1>2048)break;out.append(sentence).append(' ');}return out.toString().strip();}
    private static String safeLabel(String value){return value!=null&&value.matches("[A-Za-z0-9_.:/-]{1,96}")?value:"unverified";}
    private static boolean cancelled(){return Thread.currentThread().isInterrupted()||TimeBudgetContext.get()!=null&&TimeBudgetContext.get().expired();}
    private static long elapsed(long start){return Math.max(0,(System.nanoTime()-start)/1_000_000);}
}

