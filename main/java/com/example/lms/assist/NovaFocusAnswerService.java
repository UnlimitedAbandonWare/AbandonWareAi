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
        TraceStore.clear();
        boolean imagePresent=StringUtils.hasText(imageBase64);
        boolean web=!imagePresent&&(decisions.decide(question,SearchMode.AUTO,null,3,false).shouldSearch()
                ||ConversateAnswerPipeline.focusEvidenceRequested(question));
        // Jev signal: one bounded evaluate per confirmed question; the deterministic search
        // decision stays the fail-open path. An image request never regains web search.
        var jev=jevAdvisor==null?JevDecisionAdvisor.Advice.off():jevAdvisor.advise("focus",question,web?"WEB":"RECENT_ONLY");
        boolean jevApplied=false;
        if(jev.usable()&&!imagePresent){
            switch(jev.verdict()){
                case WEB,HYBRID->{if(!web){web=true;jevApplied=true;}}
                case RECENT_ONLY,SCOPED_RAG->{if(web){web=false;jevApplied=true;}}
                case CLARIFY->{}
            }
        }
        var requestBuilder=ChatRequestDto.builder().message(question).sessionId(null).memoryMode("EPHEMERAL")
            .searchMode(web?SearchMode.AUTO:SearchMode.OFF).useWebSearch(web).useRag(false).useVerification(false)
            .retrievalRequestIntent(new ChatRequestDto.RetrievalRequestIntent(web,false)).maxTokens(1024).webTopK(imagePresent?0:3);
        var selection=memory.answerSelection();
        if(selection.mode()==NovaFocusSettings.AnswerSelection.Mode.FIXED)
            requestBuilder.model(selection.modelId()).strictModelSelection(true);
        if(selection.routing()!=null)requestBuilder.model(primaryModel(selection)).strictModelSelection(true);
        if(imagePresent){
            requestBuilder.imageBase64(imageBase64).imageMediaType(imageMediaType).snapshotSource("focus_snapshot");
        }
        var request=requestBuilder.build();
        budgets.validateChatProjected(request,PlanHints.empty("nova-focus"),web,scope!=null&&scope.recallEnabled());
        var started=runs.beginOrJoin(room);
        if(!started.owner())throw new IllegalStateException("focus_busy");
        var run=started.context();active.put(room,run);
        var previousBudget=com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        if(previousBudget==null)com.abandonware.ai.addons.budget.TimeBudgetContext.set(new com.abandonware.ai.addons.budget.TimeBudget(60000));
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
            if(!current.getAsBoolean())throw new java.util.concurrent.CancellationException("focus_closed");
            ChatRunExecutionContext.throwIfCancelled();
            var retrieval=memories==null?FocusMemoryService.Result.empty(FocusMemoryService.Status.OFF,"scope_absent"):memories.retrieve(scope,question,current);
            if(retrieval.status()==FocusMemoryService.Status.BLOCKED_SCOPE)throw new java.util.concurrent.CancellationException("focus_memory_revoked");
            var context=new ChatConversationContext(memory.recent().stream().map(NovaFocusAnswerService::pair).toList(),
                memory.summary(),memory.relevant().stream().map(NovaFocusAnswerService::pair).toList(),true,retrieval.evidence(),memory.transcript());
            var transcriptIds=memory.transcript().stream().map(t->t.sourceId()+":"+t.revision()+":"+t.contextEpoch()).toList();
            // Project bounded extra input into the existing public admission guard; the actual DTO remains unchanged.
            var projected=request.toBuilder().message(question+"\n"+context.memoryText()+"\n"+String.join("\n",context.interpretationHistory())).build();
            budgets.validateChatProjected(projected,PlanHints.empty("nova-focus"),web,scope!=null&&scope.recallEnabled());
            if(!current.getAsBoolean()||(memories!=null&&!memories.valid(scope,retrieval.evidence())))throw new java.util.concurrent.CancellationException("focus_memory_stale");
            ChatRunExecutionContext.capRequestWait(Long.MAX_VALUE);
            var completed=executeModels(request,context,selection,current,scope!=null&&scope.recallEnabled());
            var result=completed.result();String answer=completed.text();
            ChatRunExecutionContext.throwIfCancelled();
            ChatRunExecutionContext.capRequestWait(Long.MAX_VALUE);
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
            com.example.lms.search.TraceStore.put("focus.selection.selectedModelHash",NovaFocusHistoryService.digest(Objects.toString(completed.selectedModel(),"AUTO")));
            com.example.lms.search.TraceStore.put("focus.selection.fallbackCount",completed.fallbackCount());
            com.example.lms.search.TraceStore.put("focus.selection.fallbackReason",completed.fallbackReason());
            if(!current.getAsBoolean()||(memories!=null&&!memories.valid(scope,retrieval.evidence())))throw new java.util.concurrent.CancellationException("focus_memory_stale");
            if(answer==null||answer.isBlank())throw new IllegalStateException("focus_empty_answer");
            terminal="success";terminalModel=result.modelUsed();
            return answer;
        }catch(java.util.concurrent.CancellationException cancelled){terminal="cancelled";throw cancelled;
        }catch(ModelSelectionException failure){if("backend_timeout".equals(failure.code()))terminal="timeout";else if("request_cancelled".equals(failure.code()))terminal="cancelled";throw failure;
        }finally{
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
            "focus.request.attempts","focus.request.attemptCount","focus.request.attemptRowsOmitted","focus.request.evidenceBoundary","focus.request.terminalClass")){
            Object value=TraceStore.get(key);if(value!=null)safe.put(key,value);
        }
        return Map.copyOf(safe);
    }
    private static ChatConversationContext.Turn pair(NovaFocusHistoryService.Pair value){return new ChatConversationContext.Turn(value.question(),value.answer());}
    private enum ProviderKind { LOCAL,API,UNKNOWN }
    private ProviderKind providerKind(ChatModelCatalogService.Choice choice){
        if(choice==null)return ProviderKind.UNKNOWN;
        if("local-default".equals(choice.endpointId())&&"Ollama".equalsIgnoreCase(choice.provider()))return ProviderKind.LOCAL;
        if(!choice.id().startsWith("llmrouter.")||routerConfig==null)return ProviderKind.UNKNOWN;
        var config=routerConfig.getModels().get(choice.id().substring("llmrouter.".length()));
        String provider=config==null?null:config.getProvider();
        if(!StringUtils.hasText(provider))return ProviderKind.UNKNOWN;
        // Same explicit labels as the existing router; a blank label proves neither target.
        return Set.of("local","ollama").contains(provider.strip().toLowerCase(Locale.ROOT))?ProviderKind.LOCAL:ProviderKind.API;
    }
    private boolean allowedTarget(ChatModelCatalogService.Choice choice,NovaFocusSettings.Routing policy){
        return choice!=null&&switch(policy.executionTarget()){
            case AUTO -> true;
            case API_ONLY -> providerKind(choice)==ProviderKind.API;
            case LOCAL_ONLY -> providerKind(choice)==ProviderKind.LOCAL;
        };
    }
    private String primaryModel(NovaFocusSettings.AnswerSelection selection){
        if(modelCatalog==null)throw new ModelSelectionException("model_unavailable");
        var policy=selection.routing();
        if(selection.mode()==NovaFocusSettings.AnswerSelection.Mode.FIXED){
            var choice=modelCatalog.resolve(selection.modelId()).orElse(null);
            if(choice!=null&&!allowedTarget(choice,policy))throw new ModelSelectionException("model_request_invalid");
            return selection.modelId();
        }
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
    private ModelAnswer callModel(ChatRequestDto request,ChatConversationContext context,java.util.function.BooleanSupplier current,int fallbacks,String reason){
        requireCurrent(current);
        var result=chat.continueChat(request,null,context);String answer=result.content();
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
        var policy=selection.routing();
        if(policy==null)return callModel(request,context,current,0,"none");
        var ids=new LinkedHashSet<String>();ids.add(request.getModel());
        if(policy.fallbackAllowed())ids.addAll(policy.allowedFallbackIds());
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
                return callModel(attempt,context,current,fallbacks,reason);
            }catch(ModelSelectionException failure){
                requireCurrent(current);last=failure;
                if("none".equals(reason))reason=failure.code();
                com.example.lms.search.TraceStore.put("focus.selection.fallbackReason",reason);
                com.example.lms.search.TraceStore.put("focus.selection.fallbackCount",fallbacks);
                var budget=com.abandonware.ai.addons.budget.TimeBudgetContext.get();
                if(!policy.fallbackAllowed()||Set.of("request_cancelled","model_request_invalid").contains(failure.code())
                        ||budget!=null&&budget.expired())throw failure;
            }
        }
        throw last==null?new ModelSelectionException("model_unavailable"):last;
    }
    @Override public void cancel(Long room){var run=active.get(room);if(run!=null)runs.cancelExact(room,run.clientToken());}
}
