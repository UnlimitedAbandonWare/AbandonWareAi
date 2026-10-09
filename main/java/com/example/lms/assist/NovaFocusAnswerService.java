package com.example.lms.assist;

import com.example.lms.api.PublicRequestBudgetGuard;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.plan.PlanHints;
import com.example.lms.service.ChatConversationContext;
import com.example.lms.service.ChatService;
import com.example.lms.service.ChatModelCatalogService;
import com.example.lms.service.ChatResult;
import com.example.lms.llm.ModelSelectionException;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.chat.ChatRunRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded public answer route: explicit Focus memory, existing retrieval and model workflow. */
@Service
@ConditionalOnProperty(name="conversate.enabled",havingValue="true")
public class NovaFocusAnswerService implements NovaFocusAnswer {
    private final ChatService chat;
    private final PublicRequestBudgetGuard budgets;
    private final ChatRunRegistry runs;
    private final FocusMemoryService memories;
    private final Map<Long,ChatRunExecutionContext> active=new ConcurrentHashMap<>();
    private final SearchDecisionService decisions=new SearchDecisionService();
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ChatModelCatalogService modelCatalog;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private JevDecisionAdvisor jevAdvisor;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private LlmRouterProperties routerConfig;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ModelRuntimeHealthTracker modelHealth;
    @org.springframework.beans.factory.annotation.Value("${llm.chat-model:}") private String defaultModel="";
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.default-model:}") private String focusDefaultModel="";
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.unknown-web-enabled:true}") private boolean unknownWebEnabled=true;
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.unknown-web-scoped-enabled:false}") private boolean scopedWebEnabled=false;
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.web-aggressive-enabled:false}") private boolean webAggressiveEnabled=false;
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.web-model:}") private String webModel="";
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.lens-answer-chars:280}") private int lensAnswerChars=280;
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.lens-answer-lines:6}") private int lensAnswerLines=6;
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.lens-source-suffix:true}") private boolean lensSourceSuffix=true;
    @org.springframework.beans.factory.annotation.Value("${conversate.focus.timeout-ms:8000}") private long focusTimeoutMs=8000;
    public NovaFocusAnswerService(ChatService chat,PublicRequestBudgetGuard budgets,ChatRunRegistry runs){this(chat,budgets,runs,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public NovaFocusAnswerService(ChatService chat,PublicRequestBudgetGuard budgets,ChatRunRegistry runs,FocusMemoryService memories){this.chat=chat;this.budgets=budgets;this.runs=runs;this.memories=memories;}
    @Override public String answer(Long room,String question,NovaFocusHistoryService.Context memory){
        return answer(room,question,memory,()->true);
    }
    @Override public String answer(Long room,String question,NovaFocusHistoryService.Context memory,java.util.function.BooleanSupplier current){
        return answer(room,question,memory,null,current);
    }
    @Override public String answer(Long room,String question,NovaFocusHistoryService.Context memory,FocusMemoryScope scope,java.util.function.BooleanSupplier current){
        return answer(room,question,null,null,memory,scope,current);
    }
    @Override public String answer(Long room,String question,String imageBase64,String imageMediaType,NovaFocusHistoryService.Context memory,FocusMemoryScope scope,java.util.function.BooleanSupplier current){
        return answerResult(room,question,imageBase64,imageMediaType,memory,scope,current).text();
    }
    @Override public Result answerResult(Long room,String question,String imageBase64,String imageMediaType,NovaFocusHistoryService.Context memory,FocusMemoryScope scope,java.util.function.BooleanSupplier current){
        return answerResult(room,question,imageBase64,imageMediaType,memory,scope,current,null);
    }
    @Override public Result answerResult(Long room,String question,String imageBase64,String imageMediaType,NovaFocusHistoryService.Context memory,FocusMemoryScope scope,java.util.function.BooleanSupplier current,java.util.function.Consumer<String> foldPartial){
        TraceStore.clear();
        boolean imagePresent=StringUtils.hasText(imageBase64);
        boolean quick=memory.quickAnswerEnabled();
        var selection=memory.answerSelection();
        boolean exclusive=selection.routing()!=null&&selection.routing().executionTarget()==NovaFocusSettings.ExecutionTarget.GEMINI_WEBSEARCH_ONLY;
        requireCurrent(current);
        if(exclusive&&(Boolean.FALSE.equals(memory.webSearchEnabled())||quick||imagePresent))
            throw new IllegalStateException(Boolean.FALSE.equals(memory.webSearchEnabled())?"focus_search_off":quick?"focus_search_quick":"focus_search_image_unsupported");
        boolean searchAllowed=!Boolean.FALSE.equals(memory.webSearchEnabled())&&!quick&&!imagePresent;
        boolean web=!exclusive&&searchAllowed&&(decisions.decide(question,SearchMode.AUTO,null,3,
                    webAggressiveEnabled&&!casualOnly(question)).shouldSearch()
                ||ConversateAnswerPipeline.focusEvidenceRequested(question));
        // Advice may run only after admission and binding to the current request budget.
        var jev=JevDecisionAdvisor.Advice.off();
        boolean jevApplied=false;
        var requestBuilder=ChatRequestDto.builder().message(question).sessionId(null).memoryMode("EPHEMERAL")
            .searchMode(web?SearchMode.AUTO:SearchMode.OFF).useWebSearch(web).useRag(false).useVerification(false)
            .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(web,false)).maxTokens(memory.answerLengthChars()==null?1024:Math.min(2048,Math.max(256,memory.answerLengthChars()*3))).webTopK(!exclusive&&searchAllowed?3:0);
        if(selection.mode()==NovaFocusSettings.AnswerSelection.Mode.FIXED)
            requestBuilder.model(selection.modelId()).strictModelSelection(true);
        if(selection.routing()!=null)requestBuilder.model(primaryModel(selection)).strictModelSelection(true);
        if(imagePresent){
            requestBuilder.imageBase64(imageBase64).imageMediaType(imageMediaType).snapshotSource("focus_snapshot");
        }
        boolean foldCandidate=foldPartial!=null&&!imagePresent&&!exclusive&&!web
            &&selection.mode()==NovaFocusSettings.AnswerSelection.Mode.FIXED
            &&com.example.lms.llm.ChatGptOAuthRegistration.isRoute(selection.modelId())
            &&(scope==null||!scope.recallEnabled())&&memory.transcript().isEmpty()
            &&(selection.routing()==null||!selection.routing().effectiveFallbackAllowed());
        requestBuilder.focusReasoningEffort((memory.reasoningPreset()==null?NovaFocusSettings.ReasoningPreset.STANDARD:memory.reasoningPreset()).effort());
        if(foldCandidate)requestBuilder.mode("FACT").polish(false);
        var request=requestBuilder.build();
        var admissionContext=new ChatConversationContext(memory.recent().stream().map(NovaFocusAnswerService::pair).toList(),
            memory.summary(),memory.relevant().stream().map(NovaFocusAnswerService::pair).toList(),true,List.of(),memory.transcript());
        var admissionRequest=request.toBuilder().message(question+"\n"+admissionContext.memoryText()+"\n"+String.join("\n",admissionContext.interpretationHistory())).build();
        budgets.validateChatProjected(admissionRequest,PlanHints.empty("nova-focus"),web,scope!=null&&scope.recallEnabled());
        var started=runs.beginOrJoin(room);
        if(!started.owner())throw new IllegalStateException("focus_busy");
        var run=started.context();active.put(room,run);
        if(foldCandidate)run.installFoldTextConsumer(text->{requireCurrent(current);foldPartial.accept(text);});
        var previousBudget=com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        if(previousBudget==null)com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(Math.min(10000L,Math.max(3000L,focusTimeoutMs))));
        String timeline=null,terminal="error",terminalModel=request.getModel();
        try(var binding=ChatRunExecutionContext.bind(run)){
            if(modelHealth!=null){
                try{
                    timeline=modelHealth.beginRequestTimeline(UUID.randomUUID().toString(),String.valueOf(room));
                    TraceStore.putInternal(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY,timeline);
                    modelHealth.recordRequestPhase(timeline,"dispatch",request.getModel(),null,"none");
                    modelHealth.recordRequestPhase(timeline,"pending",null,null,"none");
                }catch(RuntimeException unavailable){timeline=null;TraceStore.clear();}
            }
            requireCurrent(current);
            if(previousBudget!=null&&previousBudget.expired())throw new java.util.concurrent.CancellationException("focus_budget_exhausted");
            // A fixed general answer goes straight to its selected model; web ON is permission to look up facts when needed.
            if(!exclusive&&searchAllowed&&(web||selection.mode()!=NovaFocusSettings.AnswerSelection.Mode.FIXED)&&jevAdvisor!=null)
                jev=jevAdvisor.advise("focus",question,web?"WEB":"RECENT_ONLY");
            requireCurrent(current);
            if(jev.usable()){
                boolean revised=switch(jev.verdict()){
                    case WEB,HYBRID->true;
                    case RECENT_ONLY,SCOPED_RAG->false;
                    case CLARIFY->web;
                };
                jevApplied=revised!=web;web=revised;
                request=request.toBuilder().searchMode(web?SearchMode.AUTO:SearchMode.OFF).useWebSearch(web)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(web,false)).build();
            }
            if(web&&selection.mode()==NovaFocusSettings.AnswerSelection.Mode.AUTO&&selection.routing()==null)
                request=withWebModel(request);
            var mode=UnknownAnswerPolicy.mode(jev);
            boolean memoryEnabled=!exclusive&&scope!=null&&scope.recallEnabled()
                &&mode!=UnknownAnswerPolicy.Mode.RECENT_ONLY&&mode!=UnknownAnswerPolicy.Mode.WEB;
            var effectiveAdmission=request.toBuilder().message(admissionRequest.getMessage()).build();
            budgets.validateChatProjected(effectiveAdmission,PlanHints.empty("nova-focus"),web,memoryEnabled);
            requireCurrent(current);
            var retrieval=memories==null||!memoryEnabled
                ?FocusMemoryService.Result.empty(FocusMemoryService.Status.OFF,mode.name().toLowerCase(Locale.ROOT)+"_precedence")
                :memories.retrieve(scope,question,current);
            if(retrieval.status()==FocusMemoryService.Status.BLOCKED_SCOPE)throw new java.util.concurrent.CancellationException("focus_memory_revoked");
            var context=new ChatConversationContext(memory.recent().stream().map(NovaFocusAnswerService::pair).toList(),
                memory.summary(),memory.relevant().stream().map(NovaFocusAnswerService::pair).toList(),true,retrieval.evidence(),memory.transcript(),memory.answerLengthChars())
                .withFocusGoogleSearch(searchAllowed&&(exclusive||web||Boolean.TRUE.equals(memory.webSearchEnabled())),exclusive);
            var transcriptIds=memory.transcript().stream().map(t->t.sourceId()+":"+t.revision()+":"+t.contextEpoch()).toList();
            // Project bounded extra input into the existing public admission guard; the actual DTO remains unchanged.
            var projected=request.toBuilder().message(question+"\n"+context.memoryText()+"\n"+String.join("\n",context.interpretationHistory())).build();
            budgets.validateChatProjected(projected,PlanHints.empty("nova-focus"),web,memoryEnabled);
            if(!current.getAsBoolean()||(memoryEnabled&&memories!=null&&!memories.valid(scope,retrieval.evidence())))throw new java.util.concurrent.CancellationException("focus_memory_stale");
            ChatRunExecutionContext.capRequestWait(Long.MAX_VALUE);
            var completed=executeModels(request,context,selection,current,memoryEnabled);
            ChatRunExecutionContext.throwIfCancelled();
            ChatRunExecutionContext.capRequestWait(Long.MAX_VALUE);
            // 모름 신호: 같은 요청에서 최대 한 번, UnknownAnswerPolicy 우선순위가
            // 허용할 때만 웹 ON으로 재시도한다. 60초 총 한도·기존 실행 경로를 공유한다.
            var unknownTrigger=UnknownAnswerPolicy.classify(completed.text());
            // Per-profile narrowing only: false disables the retry for this owner; true never widens the global switch.
            boolean ownerWeb=scope==null||!Boolean.FALSE.equals(scope.memoryOrDefault().webOnUnknown());
            var unknown=UnknownAnswerPolicy.decide(UnknownAnswerPolicy.mode(jev),imagePresent,
                    scopedWebEnabled,!exclusive&&unknownWebEnabled&&ownerWeb&&searchAllowed,
                    web||(completed.result().grounding()!=null&&completed.result().grounding().searchObserved()),unknownTrigger);
            com.example.lms.search.TraceStore.put("focus.unknown.trigger",unknownTrigger==null?"none":unknownTrigger.name());
            com.example.lms.search.TraceStore.put("focus.unknown.mode",unknown.mode().name());
            com.example.lms.search.TraceStore.put("focus.unknown.webRetry",unknown.webAllowed());
            com.example.lms.search.TraceStore.put("focus.unknown.reason",unknown.reason());
            if(unknown.webAllowed()&&!run.foldHasPublished()){
                requireCurrent(current);
                var webRequest=withWebModel(request.toBuilder().searchMode(SearchMode.AUTO).useWebSearch(true)
                        .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(true,false)).webTopK(3).build());
                var projectedWeb=webRequest.toBuilder().message(question+"\n"+context.memoryText()+"\n"+String.join("\n",context.interpretationHistory())).build();
                budgets.validateChatProjected(projectedWeb,PlanHints.empty("nova-focus"),true,memoryEnabled);
                var retried=executeModels(webRequest,context.withFocusGoogleSearch(searchAllowed),selection,current,memoryEnabled);
                ChatRunExecutionContext.throwIfCancelled();
                ChatRunExecutionContext.capRequestWait(Long.MAX_VALUE);
                if(StringUtils.hasText(retried.text())){completed=retried;web=true;}
                com.example.lms.search.TraceStore.put("focus.unknown.outcome",completed==retried?"replaced":"kept_first");
            }
            var result=completed.result();String answer=completed.text();
            var grounding=result.grounding();
            boolean grounded=grounding!=null&&grounding.searchObserved();
            if(exclusive){
                requireCurrent(current);
                if(!grounded)throw new IllegalStateException("focus_search_not_observed");
                var choice=modelCatalog.resolve(request.getModel()).orElse(null);
                if(choice==null||!Objects.equals(choice.modelId(),grounding.selectedModel())
                        ||!Objects.equals(result.modelUsed(),grounding.model()))throw new IllegalStateException("focus_search_model_mismatch");
                if(!Objects.equals(answer,grounding.originalText())||!grounding.exclusivePublicationReady())
                    throw new IllegalStateException("focus_search_attribution_unavailable");
            }
            if(grounded&&(!java.util.Objects.equals(answer,grounding.originalText())||!grounding.publicationReady()))
                throw new IllegalStateException("focus_grounding_publication_held");
            // Preserve the generated Fold body. Lens-only cleanup belongs to View.forTarget.
            TraceStore.put("focus.length.generatedGraphemes",graphemes(completed.text()));
            TraceStore.put("focus.length.visibleGraphemes",graphemes(memory.answerLengthChars()==null?answer:boundDisplay(answer,memory.answerLengthChars())));
            TraceStore.put("focus.length.targetChars",memory.answerLengthChars()==null?"legacy":memory.answerLengthChars());
            TraceStore.put("focus.quick",quick);
            TraceStore.put("focus.search.allowed",searchAllowed);
            TraceStore.put("focus.search.webRequested",exclusive||web);
            TraceStore.put("focus.search.groundingStatus",!searchAllowed?"off":grounded?"grounded_phone":grounding!=null?"search_not_observed":"unsupported_display_route");
            // ChatWorkflow clears TraceStore at every attempt; publish only final immutable request metadata here.
            com.example.lms.search.TraceStore.put("focus.context.snapshotId",NovaFocusHistoryService.digest(String.join("\n",transcriptIds)));
            com.example.lms.search.TraceStore.put("focus.context.sourceIds",memory.transcript().stream().map(ChatConversationContext.Transcript::sourceId).toList());
            com.example.lms.search.TraceStore.put("focus.context.transcriptCount",memory.transcript().size());
            com.example.lms.search.TraceStore.put("focus.context.tokenUpperBound",ChatConversationContext.transcriptTokens(memory.transcript()));
            com.example.lms.search.TraceStore.put("focus.memory.status",retrieval.status().name());
            com.example.lms.search.TraceStore.put("focus.memory.mode",retrieval.retrievalMode());
            com.example.lms.search.TraceStore.put("focus.memory.vectorHitCount",retrieval.vectorHits());
            com.example.lms.search.TraceStore.put("focus.memory.graphHitCount",retrieval.graphHits());
            com.example.lms.search.TraceStore.put("focus.memory.evidenceCount",retrieval.evidence().size());
            com.example.lms.search.TraceStore.put("focus.memory.evidenceBytes",retrieval.evidenceBytes());
            com.example.lms.search.TraceStore.put("focus.memory.graphHops",retrieval.graphHops());
            com.example.lms.search.TraceStore.put("focus.memory.tookMs",retrieval.tookMs());
            com.example.lms.search.TraceStore.put("focus.selection.settingsVersion",memory.settingsVersion());
            com.example.lms.search.TraceStore.put("focus.selection.mode",selection.mode().name());
            com.example.lms.search.TraceStore.put("focus.selection.requestedModelHash",NovaFocusHistoryService.digest(Objects.toString(selection.modelId(),"AUTO")));
            com.example.lms.search.TraceStore.put("focus.selection.resultModelHash",NovaFocusHistoryService.digest(Objects.toString(result.modelUsed(),"not_observed")));
            com.example.lms.search.TraceStore.put("focus.selection.strict",request.isStrictModelSelection());
            com.example.lms.search.TraceStore.put("focus.selection.executionTarget",selection.routing()==null?"AUTO":selection.routing().executionTarget().name());
            com.example.lms.search.TraceStore.put("focus.selection.fallbackAllowed",selection.routing()!=null&&selection.routing().fallbackAllowed());
            com.example.lms.search.TraceStore.put("focus.selection.effectiveFallbackAllowed",selection.routing()!=null&&selection.routing().effectiveFallbackAllowed());
            com.example.lms.search.TraceStore.put("focus.selection.selectedModelHash",NovaFocusHistoryService.digest(Objects.toString(completed.selectedModel(),"AUTO")));
            com.example.lms.search.TraceStore.put("focus.selection.fallbackCount",completed.fallbackCount());
            com.example.lms.search.TraceStore.put("focus.selection.fallbackReason",completed.fallbackReason());
            if(!current.getAsBoolean()||(memoryEnabled&&memories!=null&&!memories.valid(scope,retrieval.evidence())))throw new java.util.concurrent.CancellationException("focus_memory_stale");
            if(answer==null||answer.isBlank())throw new IllegalStateException("focus_empty_answer");
            if(run.foldHasPublished())answer=answer.stripLeading();
            run.requireFoldPrefix(answer);
            terminal="success";terminalModel=result.modelUsed();
            return new Result(answer,grounded?grounding:null);
        }catch(java.util.concurrent.CancellationException cancelled){terminal="cancelled";throw cancelled;
        }catch(ModelSelectionException failure){if("backend_timeout".equals(failure.code()))terminal="timeout";else if("request_cancelled".equals(failure.code()))terminal="cancelled";throw failure;
        }finally{
            run.foldTimings().forEach(TraceStore::put);
            try{
                // ChatWorkflow clears TraceStore per attempt; publish the bounded Jev verdict
                // here so it survives into the allowlisted diagnostic snapshot on every path.
                com.example.lms.search.TraceStore.put("focus.jev.mode",jev.mode());
                com.example.lms.search.TraceStore.put("focus.jev.decision",jev.decision());
                com.example.lms.search.TraceStore.put("focus.jev.reasonCode",jev.reasonCode());
                com.example.lms.search.TraceStore.put("focus.jev.applied",jevApplied);
                if(jev.latencyMs()>=0)com.example.lms.search.TraceStore.put("focus.jev.latencyMs",jev.latencyMs());
                publishAttemptEvidence(timeline,terminalModel,terminal);
                var safe=diagnosticTrace();TraceStore.clear();safe.forEach(TraceStore::put);
            }finally{
                active.remove(room,run);runs.markDone(run);
                if(previousBudget==null)com.abandonware.ai.addons.budget.TimeBudgetContext.clear();else com.abandonware.ai.addons.budget.TimeBudgetContext.set(previousBudget);
            }
        }
    }
    private static final Set<String> ATTEMPT_FIELDS=Set.of("sequence","logicalCallOrdinal","attemptOrdinal","role",
        "routeKeyHash","modelHash","protocol","outcome","failureClass","terminalClass","evidenceBoundary",
        "modelAdapterAttemptObserved","clientHttpExchangeObserved","clientHttpResponseObserved",
        "providerAttemptObserved","wireAttemptObserved","responseObserved","providerReceiptObserved");
    private void publishAttemptEvidence(String timeline,String model,String terminal){
        List<Map<String,Object>> rows=List.of();
        try{
            if(modelHealth!=null&&timeline!=null){
                modelHealth.recordRequestPhase(timeline,"terminal",model,null,terminal);
                rows=modelHealth.redactedRequestAttemptLedger(timeline);
            }
        }catch(RuntimeException unavailable){/* Observability cannot change the answer outcome. */}
        var projected=rows.stream().skip(Math.max(0,rows.size()-8)).map(row->{
            var safe=new LinkedHashMap<String,Object>();ATTEMPT_FIELDS.forEach(k->{if(row.containsKey(k))safe.put(k,row.get(k));});
            return Map.copyOf(safe);
        }).toList();
        String boundary=rows.stream().anyMatch(r->Boolean.TRUE.equals(r.get("providerAttemptObserved"))&&Boolean.TRUE.equals(r.get("responseObserved")))?"provider_receive":
            rows.stream().anyMatch(r->Boolean.TRUE.equals(r.get("clientHttpResponseObserved"))&&Boolean.TRUE.equals(r.get("responseObserved")))?"client_http":
            rows.stream().anyMatch(r->Boolean.TRUE.equals(r.get("modelAdapterAttemptObserved"))&&Boolean.TRUE.equals(r.get("responseObserved")))?"model_adapter":"not_observed";
        TraceStore.put("focus.request.attempts",projected);
        TraceStore.put("focus.request.attemptCount",rows.size());
        TraceStore.put("focus.request.attemptRowsOmitted",Math.max(0,rows.size()-projected.size()));
        TraceStore.put("focus.request.evidenceBoundary",boundary);
        TraceStore.put("focus.request.terminalClass",terminal);
    }
    /** Exact internally-produced metadata only; worker/controller never receive the tracker correlation ID or payload hashes. */
    static Map<String,Object> diagnosticTrace(){
        var safe=new LinkedHashMap<String,Object>();
        for(String key:List.of("focus.context.snapshotId","focus.context.sourceIds","focus.context.transcriptCount","focus.context.tokenUpperBound",
            "focus.memory.status","focus.memory.mode","focus.memory.vectorHitCount","focus.memory.graphHitCount","focus.memory.evidenceCount",
            "focus.memory.evidenceBytes","focus.memory.graphHops","focus.memory.tookMs","focus.jev.mode","focus.jev.decision",
            "focus.jev.reasonCode","focus.jev.applied","focus.jev.latencyMs","focus.selection.settingsVersion","focus.selection.mode",
            "focus.selection.requestedModelHash","focus.selection.resultModelHash","focus.selection.strict","focus.selection.executionTarget",
            "focus.selection.selectedModelHash","focus.selection.fallbackCount","focus.selection.fallbackReason",
            "focus.selection.fallbackAllowed","focus.selection.effectiveFallbackAllowed",
            "focus.stream.firstProviderDeltaMs","focus.stream.firstUsefulPublishedMs","focus.stream.finalValidatedMs","focus.stream.publishedChars",
            "focus.unknown.trigger","focus.unknown.mode","focus.unknown.webRetry","focus.unknown.reason","focus.unknown.outcome",
            "focus.request.attempts","focus.request.attemptCount","focus.request.attemptRowsOmitted","focus.request.evidenceBoundary","focus.request.terminalClass",
            "focus.length.generatedGraphemes","focus.length.visibleGraphemes","focus.length.targetChars","focus.quick",
            "focus.search.allowed","focus.search.webRequested","focus.search.groundingStatus","focus.search.parametricRetry")){
            Object value=TraceStore.get(key);if(value!=null)safe.put(key,value);
        }
        return Map.copyOf(safe);
    }
    private static ChatConversationContext.Turn pair(NovaFocusHistoryService.Pair value){return new ChatConversationContext.Turn(value.question(),value.answer());}
    /** Aggressive AUTO never fires on pure greetings; intent cues still govern the decide() call. */
    private static final java.util.regex.Pattern CASUAL_ONLY=java.util.regex.Pattern.compile(
        "(?i)^\\s*(?:안녕(?:하세요)?|하이|헬로|헤이|고마워|감사(?:합니다)?|수고(?:했어)?|잘가|바이|반가워|ㅇㅇ|ㅋㅋ+|ㅎㅎ+|hello|hi|hey|thanks|thank you|bye|good morning|good night|ok(?:ay)?|yes|no|yeah|nope)[\\s!?.~,]*$");
    private static boolean casualOnly(String question){
        return question!=null&&CASUAL_ONLY.matcher(question.strip()).matches();
    }
    /** Soft model preference for web-grounded answers; the route must already be selectable. */
    private String resolveWebModel(){
        if(modelCatalog==null||!StringUtils.hasText(webModel))return null;
        var choice=modelCatalog.resolve(webModel).orElse(null);
        return choice!=null&&choice.selectable()?webModel:null;
    }
    private ChatRequestDto withWebModel(ChatRequestDto base){
        String preferred=resolveWebModel();
        return preferred!=null&&!StringUtils.hasText(base.getModel())?base.toBuilder().model(preferred).build():base;
    }
    /** Lens-facing web answers: markdown stripped, sentence-bounded to the configured budget. */
    private String lensCompact(String text,ChatResult result){
        if(!StringUtils.hasText(text))return text;
        int budget=Math.max(80,Math.min(lensAnswerChars,800));
        String suffix=lensSourceSuffix?sourceSuffix(result):null;
        if(suffix!=null&&graphemes(suffix)>budget-80)suffix=null;
        int textBudget=budget-(suffix==null?0:suffix.codePointCount(0,suffix.length()));
        String cleaned=cleanLensText(text);
        var segs=cleaned.split("\n",-1);
        int lineCap=Math.max(1,lensAnswerLines);
        if(segs.length>lineCap)cleaned=String.join("\n",java.util.Arrays.copyOf(segs,lineCap));
        String out=cleaned.codePointCount(0,cleaned.length())<=textBudget?cleaned:cutAtBoundary(cleaned,textBudget);
        return suffix==null?out:out+suffix;
    }
    private static String cleanLensText(String text){
        return text
            .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*","")
            .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)","")
            .replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)","$1")
            .replaceAll("\\*\\*([^*]*)\\*\\*","$1")
            .replaceAll("(?m)^\\s{0,3}>\\s?","")
            .replaceAll("(?m)^\\s{0,3}[-*+]\\s+","")
            .replaceAll("[ \\t\\x0B\\f\\r]+"," ")
            .replaceAll("\\n{2,}","\\n")
            .strip();
    }
    /** Display-only projection. Fold retains the answer and structured grounding unchanged. */
    static String lensText(String text,boolean complete){
        if(text==null||text.isBlank())return text;
        String candidate=text.replace("\r\n","\n");
        if(!complete){
            // The provider's safe sentence prefix may still contain an unfinished link.
            // Publish complete sentences, without waiting for the full answer.
            var ends=java.util.regex.Pattern.compile("[.!?。！？][*_`]*(?=\\s|$)").matcher(candidate);
            int end=0;while(ends.find())end=ends.end();
            candidate=candidate.substring(0,end);
        }
        var lines=new ArrayList<String>();boolean sources=false;
        for(String line:candidate.split("\n",-1)){
            String node=line.strip();
            if(node.startsWith("```"))continue;
            if(node.matches("(?i)^(?:#{1,6}\\s*)?(?:출처|참고\\s*(?:자료|문헌|링크)|sources?|references?)\\s*[:：]?\\s*$")){sources=true;continue;}
            if(sources){
                if(node.isBlank()||node.matches("(?i)^(?:[-*+]\\s+|[0-9]+[.)]\\s+|https?://|www\\.|\\[[^]]+](?:\\(|:)).*"))continue;
                sources=false; // A following answer paragraph is not part of the reference list.
            }
            if(node.matches("(?i)^\\[[^]]+\\]:\\s*(?:https?://|www\\.).*"))continue;
            if(node.matches("(?i)^(?:검색\\s*(?:중|진행\\s*중|완료)|searching|search\\s+in\\s+progress|tool[_ ](?:call|result)|TRACE_JSON|TRACE_HTML)(?:[ .…:：].*)?$"))continue;
            // Link nodes keep their label; addresses and image nodes are never display text.
            StringBuilder visible=new StringBuilder();
            for(int i=0;i<line.length();){
                boolean image=line.charAt(i)=='!'&&i+1<line.length()&&line.charAt(i+1)=='[';
                int start=image?i+1:i;
                if(line.charAt(start)=='['){
                    int labelEnd=line.indexOf(']',start+1);
                    if(labelEnd<0&&!complete)break;
                    if(labelEnd>=0&&labelEnd+1<line.length()&&line.charAt(labelEnd+1)=='['){
                        int refEnd=line.indexOf(']',labelEnd+2);
                        if(refEnd<0&&!complete)break;
                        if(refEnd>=0){if(!image)visible.append(line,start+1,labelEnd);i=refEnd+1;continue;}
                    }
                    if(labelEnd>=0&&labelEnd+1<line.length()&&line.charAt(labelEnd+1)=='('){
                        int depth=1,j=labelEnd+2;
                        for(;j<line.length()&&depth>0;j++){char c=line.charAt(j);if(c=='(')depth++;else if(c==')')depth--;}
                        if(depth>0)break;
                        if(!image)visible.append(line,start+1,labelEnd);
                        i=j;continue;
                    }
                }
                visible.append(line.charAt(i++));
            }
            String cleaned=cleanLensText(visible.toString())
                .replace("**","").replace("__","")
                .replaceAll("(?i)</?[a-z][a-z0-9]*(?:\\s+[^<>]*)?\\s*/?>|<https?://[^>]+>","")
                .replaceAll("(?i)(?:https?://|www\\.)[^\\s<>]+","")
                .replaceAll("\\[출처:[^]]*]","")
                .replaceAll("\\[(?:[0-9]+[,; ]*)+]","")
                .replaceAll("[ \\t]{2,}"," ").strip();
            if(!cleaned.isBlank()&&!cleaned.matches("(?i)(?:주소|URL|링크)\\s*[:：]?"))lines.add(cleaned);
        }
        String projected=String.join("\n",lines);
        return projected.isBlank()&&complete?"링크와 출처는 휴대폰에서 확인하세요.":projected;
    }
    private static String cutAtBoundary(String text,int budget){
        return boundDisplay(text,budget);
    }
    static int graphemes(String text){return text==null?0:(int)java.util.regex.Pattern.compile("\\X").matcher(text.replace("\r\n","\n")).results().count();}
    /** Full text is returned as-is: display bounds live in the lens renderer and the length setting at generation time. */
    static String boundDisplay(String text,int budget){
        if(text==null)return null;
        return text.replace("\r\n","\n");
    }
    private static String sourceSuffix(ChatResult result){
        var meta=result==null?null:result.evidenceMetadata();
        if(meta==null||meta.isEmpty())return null;
        var hosts=new LinkedHashSet<String>();
        for(var m:meta){
            String host=m==null?null:hostOf(m.source());
            if(host!=null)hosts.add(host);
            if(hosts.size()>=2)break;
        }
        return hosts.isEmpty()?null:" [출처: "+String.join("/",hosts)+"]";
    }
    private static String hostOf(String source){
        if(!StringUtils.hasText(source))return null;
        try{
            String host=java.net.URI.create(source.trim()).getHost();
            if(host==null)return null;
            host=host.toLowerCase(Locale.ROOT);
            for(String prefix:List.of("www.","m.","amp."))if(host.startsWith(prefix))return host.substring(prefix.length());
            return host;
        }catch(Exception ignored){return null;}
    }
    private enum ProviderKind { LOCAL,API,UNKNOWN }
    private ProviderKind providerKind(ChatModelCatalogService.Choice choice){
        if(choice==null)return ProviderKind.UNKNOWN;
        if("local-default".equals(choice.endpointId())&&"Ollama".equalsIgnoreCase(choice.provider()))return ProviderKind.LOCAL;
        if(choice.id().startsWith("chatgpt-oauth:")&&"chatgpt-oauth".equals(choice.endpointId())
            &&com.example.lms.llm.ChatGptOAuthRegistration.PROVIDER.equals(choice.provider()))return ProviderKind.API;
        if(!choice.id().startsWith("llmrouter.")||routerConfig==null)return ProviderKind.UNKNOWN;
        var config=routerConfig.getModels().get(choice.id().substring("llmrouter.".length()));
        String provider=config==null?null:config.getProvider();
        if(!StringUtils.hasText(provider))return ProviderKind.UNKNOWN;
        // Same explicit labels as the existing router; a blank label proves neither target.
        return Set.of("local","ollama").contains(provider.strip().toLowerCase(Locale.ROOT))?ProviderKind.LOCAL:ProviderKind.API;
    }
    private boolean allowedTarget(ChatModelCatalogService.Choice choice,NovaFocusSettings.Routing policy){
        return choice!=null&&switch(policy.executionTarget()){
            case AUTO -> providerKind(choice)!=ProviderKind.LOCAL;
            case API_ONLY -> providerKind(choice)==ProviderKind.API;
            case LOCAL_ONLY -> providerKind(choice)==ProviderKind.LOCAL;
            case GEMINI_WEBSEARCH_ONLY -> "gemini".equalsIgnoreCase(choice.provider())&&choice.id().startsWith("llmrouter.")
                &&Boolean.TRUE.equals(choice.metadata().get("googleSearchSupported"))&&routerConfig!=null
                &&routerConfig.getModels().containsKey(choice.endpointId())
                &&"gemini".equalsIgnoreCase(routerConfig.getModels().get(choice.endpointId()).getProvider())
                &&Objects.equals(choice.modelId(),routerConfig.getModels().get(choice.endpointId()).getName())
                &&choice.id().equals("llmrouter."+choice.endpointId());
        };
    }
    private String primaryModel(NovaFocusSettings.AnswerSelection selection){
        if(modelCatalog==null)throw new ModelSelectionException("model_unavailable");
        var policy=selection.routing();
        if(policy.executionTarget()==NovaFocusSettings.ExecutionTarget.GEMINI_WEBSEARCH_ONLY){
            if(selection.mode()!=NovaFocusSettings.AnswerSelection.Mode.FIXED)throw new IllegalStateException("focus_search_model_required");
            var choice=modelCatalog.resolve(selection.modelId()).orElse(null);
            if(choice==null||!allowedTarget(choice,policy))throw new IllegalStateException("focus_search_unsupported");
            if(!choice.selectable())throw new ModelSelectionException(modelCatalog.failureCode(choice));
            return selection.modelId();
        }
        if(selection.mode()==NovaFocusSettings.AnswerSelection.Mode.FIXED){
            var choice=modelCatalog.resolve(selection.modelId()).orElse(null);
            if(choice!=null&&!allowedTarget(choice,policy))throw new ModelSelectionException("model_request_invalid");
            return selection.modelId();
        }
        // Soft profile default first: like resolveWebModel, the configured focus
        // route wins only when it already resolves to a selectable catalog entry.
        var focusPreferred=StringUtils.hasText(focusDefaultModel)?modelCatalog.resolve(focusDefaultModel).orElse(null):null;
        if(focusPreferred!=null&&focusPreferred.selectable()&&allowedTarget(focusPreferred,policy))return focusDefaultModel;
        var preferred=StringUtils.hasText(defaultModel)?modelCatalog.resolve(defaultModel).orElse(null):null;
        if(allowedTarget(preferred,policy))return defaultModel;
        if(policy.executionTarget()==NovaFocusSettings.ExecutionTarget.API_ONLY&&routerConfig!=null){
            // Reuse configured route preference and catalog admission; never invent or enable a route.
            return routerConfig.getModels().entrySet().stream()
                .filter(e->e.getValue().isEnabled()&&!e.getValue().isFallbackOnly())
                .sorted(Comparator.<Map.Entry<String,LlmRouterProperties.ModelConfig>>comparingDouble(e->e.getValue().getWeight()).reversed().thenComparing(Map.Entry::getKey))
                .map(e->modelCatalog.resolve("llmrouter."+e.getKey()).orElse(null))
                .filter(c->c!=null&&c.selectable()&&allowedTarget(c,policy))
                .map(ChatModelCatalogService.Choice::id).findFirst()
                .orElseThrow(()->new ModelSelectionException("provider_not_configured"));
        }
        if(StringUtils.hasText(defaultModel)&&preferred==null)return defaultModel;
        throw new ModelSelectionException("model_request_invalid");
    }
    private record ModelAnswer(ChatResult result,String text,String selectedModel,int fallbackCount,String fallbackReason){
        @Override public String toString(){return "FocusModelAnswer[redacted]";}
    }
    private static void requireCurrent(java.util.function.BooleanSupplier current){
        if(!current.getAsBoolean())throw new java.util.concurrent.CancellationException("focus_closed");
        ChatRunExecutionContext.throwIfCancelled();ChatRunExecutionContext.capRequestWait(Long.MAX_VALUE);
    }
    private ModelAnswer callModel(ChatRequestDto request,ChatConversationContext context,java.util.function.BooleanSupplier current,int fallbacks,String reason,boolean searchRetryAllowed){
        requireCurrent(current);
        ChatResult result;
        try{
            result=chat.continueChat(request,null,context);
        }catch(ModelSelectionException|java.util.concurrent.CancellationException failure){throw failure;
        }catch(RuntimeException failure){
            // Same-model parametric recovery: a transient search/grounding failure on a
            // web-enabled call must not cascade into model fallback or stall the lens.
            if(ChatRunExecutionContext.current()!=null&&ChatRunExecutionContext.current().foldHasPublished()||!searchRetryAllowed||!Boolean.TRUE.equals(request.isUseWebSearch()))throw failure;
            requireCurrent(current);
            try{result=chat.continueChat(request.toBuilder().useWebSearch(false).searchMode(SearchMode.OFF)
                    .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(false,false)).webTopK(0).build(),null,context);
            }finally{com.example.lms.search.TraceStore.put("focus.search.parametricRetry",true);}
        }
        String answer=result.content();
        if(StringUtils.hasText(request.getImageBase64())&&"vision:unavailable".equals(result.modelUsed())){
            // No vision call occurred; retry text once with the same exact model and policy.
            requireCurrent(current);
            com.example.lms.search.TraceStore.put("focus.vision.unavailable",true);
            result=chat.continueChat(request.toBuilder().imageBase64(null).imageMediaType(null).snapshotSource(null).build(),null,context);
            answer=result.content();
            if(answer!=null&&!answer.isBlank())answer="사진 분석 모델이 준비되지 않아 사진 없이 답변합니다.\n\n"+answer;
        }
        return new ModelAnswer(result,answer,request.getModel(),fallbacks,reason);
    }
    private ModelAnswer executeModels(ChatRequestDto request,ChatConversationContext context,NovaFocusSettings.AnswerSelection selection,
                                      java.util.function.BooleanSupplier current,boolean memoryEnabled){
        var entryBudget=com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        if(entryBudget!=null&&entryBudget.remainingMillis()<2000)throw new java.util.concurrent.CancellationException("focus_budget_exhausted");
        var policy=selection.routing();
        if(policy==null)return callModel(request,context,current,0,"none",true);
        var ids=new LinkedHashSet<String>();ids.add(request.getModel());
        boolean fallbackAllowed=policy.effectiveFallbackAllowed();
        if(fallbackAllowed)ids.addAll(policy.allowedFallbackIds());
        int index=0,fallbacks=0;String reason="none";ModelSelectionException last=null;
        for(String id:ids){
            requireCurrent(current);
            boolean backup=index++>0;
            var choice=modelCatalog.resolve(id).orElse(null);
            if(backup&&(choice==null||!choice.selectable()||!allowedTarget(choice,policy)))continue;
            try{
                if(choice==null||!choice.selectable())throw new ModelSelectionException(
                    Objects.toString(modelCatalog.failureCode(choice),"model_unavailable"));
                if(!allowedTarget(choice,policy))throw new ModelSelectionException("model_request_invalid");
                var attempt=request.toBuilder().model(id).strictModelSelection(true).build();
                var projected=attempt.toBuilder().message(request.getMessage()+"\n"+context.memoryText()+"\n"+String.join("\n",context.interpretationHistory())).build();
                budgets.validateChatProjected(projected,PlanHints.empty("nova-focus"),Boolean.TRUE.equals(request.isUseWebSearch()),memoryEnabled);
                if(backup)fallbacks++;
                return callModel(attempt,context,current,fallbacks,reason,
                        policy.executionTarget()!=NovaFocusSettings.ExecutionTarget.GEMINI_WEBSEARCH_ONLY);
            }catch(ModelSelectionException failure){
                requireCurrent(current);last=failure;
                if("none".equals(reason))reason=failure.code();
                com.example.lms.search.TraceStore.put("focus.selection.fallbackReason",reason);
                com.example.lms.search.TraceStore.put("focus.selection.fallbackCount",fallbacks);
                var budget=com.abandonware.ai.addons.budget.TimeBudgetContext.get();
                if(ChatRunExecutionContext.current()!=null&&ChatRunExecutionContext.current().foldHasPublished()||!fallbackAllowed||Set.of("request_cancelled","model_request_invalid").contains(failure.code())
                        ||budget!=null&&budget.remainingMillis()<2000)throw failure;
            }
        }
        throw last==null?new ModelSelectionException("model_unavailable"):last;
    }
    @Override public void cancel(Long room){var run=active.get(room);if(run!=null)runs.cancelExact(room,run.clientToken());}
}
