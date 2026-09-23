package com.example.lms.assist;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.orch.aop.LlmRouterAspect;
import com.example.lms.llm.gateway.*;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversateApiCueServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;
    final LlmRouterProperties props=new LlmRouterProperties();
    final MockEnvironment env=new MockEnvironment().withProperty("FIXTURE_KEY","synthetic-configured-key");
    final LlmRouterAspect router=mock(LlmRouterAspect.class);
    final HybridLlmGatewayProbeService eligibility=mock(HybridLlmGatewayProbeService.class);
    final UnifiedRagOrchestrator retrieval=mock(UnifiedRagOrchestrator.class);
    final List<String> calls=new ArrayList<>();final List<String> prompts=new ArrayList<>();
    ConversateApiCueService service(){return new ConversateApiCueService(props,eligibility,router,retrieval,env);}
    @Test void observationalModeRunsOnlyHintAndReportsTokenUsageWithZeroDollarAllowance(){
        route("cheap",1,true);env.withProperty("conversate.cost.enforce-limits","false")
            .withProperty("conversate.cue.max-request-usd","0").withProperty("conversate.cue.accounts.openai.daily-budget-usd","0");
        var count=new AtomicInteger();
        when(router.apiAttempt(anyString(),anyInt(),anyInt(),any())).thenAnswer(call->{
            ChatModel model=mock(ChatModel.class);when(model.chat(anyList())).thenAnswer(chat->ChatResponse.builder()
                .tokenUsage(new dev.langchain4j.model.output.TokenUsage(20,10,30))
                .aiMessage(AiMessage.from("{\"text\":\"오후 세 시에 시작합니다.\",\"evidenceIds\":[]}")).build());count.incrementAndGet();return model;});
        var result=service().answer("회의 시간을 어떻게 답할까요?",List.of("오후 세 시"),List.of(),true);
        assertNotNull(result.card());assertEquals(1,count.get());
        assertEquals(20L,result.stages().cue().get("observedInputTokens"));
        assertEquals(10L,result.stages().cue().get("observedOutputTokens"));
        assertTrue(((Number)result.stages().cue().get("requestReservedUsd")).doubleValue()>0);
        assertEquals(false,result.stages().cue().get("costLimitsEnforced"));
    }
    @Test void observationalModeDoesNotBlockSingleSearchOnDollarReservation(){
        route("cheap",1,true);env.withProperty("conversate.cost.enforce-limits","false").withProperty("conversate.cue.max-request-usd","0");
        respond(k->gate("RAG_CUE",false));when(retrieval.query(any())).thenReturn(new UnifiedRagOrchestrator.QueryResponse());
        var result=service().answer("공식 자료를 확인해 주세요",List.of(),List.of(),true);
        verify(retrieval,times(1)).query(any());assertNotEquals("SEARCH_COST_BUDGET_EXHAUSTED",result.stages().cue().get("retrievalFailure"));
    }
    @Test void stableDefinitionSkipsRetrievalAndGeneratesAUsefulFastHint(){
        route("cheap",1,true);
        respond(k->"{\"text\":\"위치와 운동량의 불확실성을 동시에 임의로 줄일 수 없어요. 측정기 성능 때문이 아닌 양자 상태의 성질이에요.\",\"evidenceIds\":[]}");
        var result=service().answer("하이젠베르크 불확정성 원리가 뭐야?",List.of(),List.of(),true);
        assertEquals("API_CUE",result.reason());assertTrue(result.card().text().contains("위치와 운동량"));
        assertEquals("FAST",result.stages().cue().get("hintPath"));
        assertEquals("GENERAL_KNOWLEDGE",result.stages().cue().get("decisionReason"));
        assertEquals(1,calls.size());verifyNoInteractions(retrieval);
    }
    @Test void emptySearchKeepsUsefulGeneralHintAndWeakEvidenceDiagnostics(){
        route("cheap",1,true);
        String hint="환율을 비교할 때는 같은 시점과 매매 기준을 맞추세요. 실제 거래에는 수수료도 포함돼요.";
        respond(k->"{\"text\":\""+hint+"\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");
        when(retrieval.query(any())).thenReturn(new UnifiedRagOrchestrator.QueryResponse());
        var result=service().answer("오늘 환율은 얼마야?",List.of(),List.of(),true);
        assertNotNull(result.card());assertEquals(hint,result.card().text());
        assertEquals("CUE_EVIDENCE_INSUFFICIENT",result.reason());
        assertEquals("GENERAL_HINT",result.stages().cue().get("hintPath"));
        assertEquals("EMPTY",result.stages().cue().get("evidenceStatus"));
        assertEquals(true,result.stages().cue().get("evidenceInsufficient"));
        assertEquals(1,calls.size());verify(retrieval,times(1)).query(any());
        assertTrue(result.card().sourceIds().isEmpty());assertFalse(result.stages().cue().toString().contains(hint));
    }
    @Test void fragmentedSearchStillGeneratesWithoutTreatingFragmentsAsCitations(){
        route("cheap",1,true);
        respond(k->"{\"text\":\"조건을 비교할 때는 적용 대상과 예외를 함께 확인하세요.\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");
        var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();
        doc.snippet="[WEB:DOCS|CRED:TRUSTED] 대상은... 다만... 적용 조건은...";response.results=List.of(doc);
        when(retrieval.query(any())).thenReturn(response);
        var result=service().answer("공식 적용 조건을 설명해 줘",List.of(),List.of(),true);
        assertNotNull(result.card());assertTrue(result.card().text().contains("적용 대상과 예외"));
        assertEquals("FRAGMENTED",result.stages().cue().get("evidenceStatus"));
        assertEquals(1,calls.size());assertTrue(result.card().sourceIds().isEmpty());
    }
    @Test void failedSupplementPreservesTheFirstUsefulGeneralHint(){
        route("a",1,true);env.withProperty("conversate.cue.max-attempts-per-stage","2");var passes=new AtomicInteger();
        when(retrieval.query(any())).thenAnswer(call->{
            int pass=passes.incrementAndGet();TraceStore.put("conversate.search.NAVER",Map.of("failureReason","NONE","resultCount",1));
            var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();
            doc.snippet=pass==1?"조건의 이름입니다.":"다른 조건의 이름입니다.";response.results=List.of(doc);return response;
        });
        String hint="반환 조건은 구입 시점과 상품 상태에 따라 달라요. 이 두 조건부터 확인하세요.";
        respond(k->{if(calls.size()>1)throw new IllegalStateException("synthetic_supplement_failure");
            return "{\"text\":\""+hint+"\",\"evidenceIds\":[],\"evidenceInsufficient\":true}";});
        var result=service().answer("반환 조건을 알려 줘",List.of(),List.of(),true);
        assertNotNull(result.card());assertEquals(hint,result.card().text());
        assertEquals(2,calls.size());assertEquals(2,passes.get());
        assertEquals("RETAINED_GENERAL_HINT",result.stages().cue().get("supplementStatus"));
    }
    @Test void supplementalEvidenceNeverReenablesPreviouslyRejectedFragmentCitations(){
        route("a",1,true);env.withProperty("conversate.cue.max-attempts-per-stage","2");
        for(boolean citeFragment:List.of(true,false)){
            reset(router,retrieval);calls.clear();var passes=new AtomicInteger();
            when(retrieval.query(any())).thenAnswer(call->{
                int pass=passes.incrementAndGet();TraceStore.put("conversate.search.NAVER",Map.of("failureReason","NONE","resultCount",1));
                var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();
                doc.snippet=pass==1?"[WEB:DOCS] 대상은... 기한은... 예외는...":"미개봉 상품의 반환 기한은 7일입니다.";
                response.results=List.of(doc);return response;
            });
            String initial="반환 조건은 상품 상태와 구입 시점을 함께 확인하세요.";
            respond(k->calls.size()==1?"{\"text\":\""+initial+"\",\"evidenceIds\":[],\"evidenceInsufficient\":true}":
                    "{\"text\":\"미개봉 상품의 반환 기한은 7일입니다.\",\"evidenceIds\":[\""+(citeFragment?"e0":"e1")+"\"]}");
            var result=service().answer("반환 조건은 무엇인가요?",List.of(),List.of(),true);
            assertEquals(2,calls.size());assertEquals(1,result.stages().cue().get("usableEvidenceCount"));
            if(citeFragment){assertEquals(initial,result.card().text());assertTrue(result.card().sourceIds().isEmpty());
                assertEquals("RETAINED_GENERAL_HINT",result.stages().cue().get("supplementStatus"));}
            else{assertEquals("API_CUE",result.reason());assertEquals(List.of("e1"),result.card().sourceIds());}
        }
    }
    @Test void failedPrimaryStaysRecordedWhenFallbackServesTheHint() {
        route("a",1,true);route("b",1,true);
        var recorder=new com.example.lms.debug.ApiFailureRecorder(null,dir.resolve("cue.json").toString());
        var svc=service();
        org.springframework.test.util.ReflectionTestUtils.setField(svc,"failureRecorder",recorder);
        when(router.apiAttempt(anyString(),anyInt(),anyInt(),any())).thenAnswer(call->{
            String key=call.getArgument(0);calls.add(key);
            ChatModel model=mock(ChatModel.class);
            when(model.chat(anyList())).thenAnswer(chat->{
                if("a".equals(key))throw new IllegalStateException(new java.net.SocketTimeoutException());
                return ChatResponse.builder().aiMessage(AiMessage.from(
                        "{\"text\":\"조건을 비교할 때는 적용 대상과 예외를 함께 확인하세요.\",\"evidenceIds\":[]}")).build();
            });
            return model;
        });
        var result=svc.answer("환불 조건을 알려 줘",List.of(),List.of(),true,true);
        assertNotNull(result.card(),"fallback must still serve the user a normal hint");
        assertEquals(2,calls.size());assertEquals(List.of("a","b"),calls);
        var rows=recorder.snapshot();
        assertEquals(1,rows.size());
        var row=rows.get(0);
        assertEquals("openai",row.provider());assertEquals("model-a",row.model());
        assertEquals("timeout",row.category());assertEquals("SUSPECTED",row.evidence());
        assertEquals(1,row.consecutive());assertEquals(1,row.count());
        assertEquals("openai/model-b",row.maskedBy());assertNotNull(row.maskedAt());
        // Route a cooled down after its failure; a second call stays on route b and keeps the incident.
        var second=svc.answer("환불 조건을 알려 줘",List.of(),List.of(),true,true);
        assertNotNull(second.card());assertEquals(List.of("a","b","b"),calls);
        assertEquals(1,recorder.snapshot().size());
        recorder.close();
    }
    void route(String key,int quality,boolean gate){
        var cfg=new LlmRouterProperties.ModelConfig();cfg.setEnabled(true);cfg.setProvider("openai");cfg.setStage("chat");cfg.setName("model-"+key);cfg.setCredentialEnv("FIXTURE_KEY");cfg.setBaseUrl("https://example.org/v1");props.getModels().put(key,cfg);
        env.withProperty("conversate.cue.routes."+key+".gate",String.valueOf(gate)).withProperty("conversate.cue.routes."+key+".quality",String.valueOf(quality))
                .withProperty("conversate.cue.routes."+key+".input-usd-per-million","0.75")
                .withProperty("conversate.cue.routes."+key+".output-usd-per-million","4.5");
        when(eligibility.evaluate(eq(key),same(cfg),eq("chat"))).thenReturn(RoutingEligibility.eligible(key,"openai",cfg.getName(),"chat",100,false,Map.of()));
    }
    void respond(java.util.function.Function<String,String> response){
        when(router.apiAttempt(anyString(),anyInt(),anyInt(),any())).thenAnswer(call->{String key=call.getArgument(0);calls.add(key);
            ChatModel model=mock(ChatModel.class);when(model.chat(anyList())).thenAnswer(chat->{prompts.add(chat.getArgument(0).toString());return ChatResponse.builder().aiMessage(AiMessage.from(response.apply(key))).build();});return model;});
    }
    static String gate(String decision,boolean complex){return "{\"decision\":\""+decision+"\",\"reason\":\"QUESTION\",\"complex\":"+complex+",\"topicChanged\":false,\"contextRelevant\":true}";}
    @Test void directVerificationMakesOneOfficialOpenAiCallAndRetainsUsage(){
        route("openai",1,true);props.getModels().get("openai").setBaseUrl("https://api.openai.com/v1");
        route("gemini",1,true);props.getModels().get("gemini").setProvider("gemini");
        route("proxy",1,true);
        var usage=new dev.langchain4j.model.output.TokenUsage(20,10,30);
        when(router.apiAttempt(anyString(),anyInt(),anyInt(),any())).thenAnswer(call->{calls.add(call.getArgument(0));
            ChatModel model=mock(ChatModel.class);when(model.chat(anyList())).thenReturn(ChatResponse.builder()
                    .id("chatcmpl-synthetic").modelName("served-model").tokenUsage(usage)
                    .aiMessage(AiMessage.from("{\"text\":\"잠시 생각할 시간을 주시겠어요?\",\"evidenceIds\":[]}")).build());return model;});
        var support=mock(ConversateLocalCardGenerator.class);var service=service();
        org.springframework.test.util.ReflectionTestUtils.setField(service,"localSupport",support);
        var result=service.answerDirectOpenAi("어떻게 부탁할까요?",UUID.randomUUID().toString());
        assertEquals("OPENAI_DIRECT_OK",result.reason());assertEquals(List.of("openai"),calls);
        assertEquals(Map.of("input",20,"output",10,"total",30),result.stages().cue().get("actualTokens"));
        assertEquals("served-model",result.stages().cue().get("responseModel"));
        assertEquals("chatcmpl-synthetic",result.stages().cue().get("providerResponseId"));
        assertEquals(false,result.stages().cue().get("fallback"));verifyNoInteractions(retrieval,support);
    }
    @Test void directAuthenticationRequestAndQuotaErrorsNeverTryAnotherRouteOrLocalSupport(){
        route("a",1,true);route("b",1,true);
        props.getModels().values().forEach(cfg->cfg.setBaseUrl("https://api.openai.com/v1"));
        var support=mock(ConversateLocalCardGenerator.class);
        for(int status:List.of(401,400,429)){
            reset(router);calls.clear();respond(k->{throw new dev.langchain4j.exception.HttpException(status,"{\"error\":{\"code\":\"insufficient_quota\"}}");});
            var service=service();org.springframework.test.util.ReflectionTestUtils.setField(service,"localSupport",support);
            var result=service.answerDirectOpenAi("질문",UUID.randomUUID().toString());
            assertEquals("OPENAI_DIRECT_ERROR",result.reason());assertNull(result.card());assertEquals(1,calls.size());
            assertEquals(false,result.stages().cue().get("retryAllowed"));
        }
        verifyNoInteractions(retrieval,support);
    }
    @Test void spendLimitBlockAndGatewayQuotaCodeClassifyAsExhaustedWithoutSiblingRetry(){
        route("a",1,true);route("b",1,true);
        for(RuntimeException failure:List.of(
                new dev.langchain4j.exception.HttpException(400,"{\"error\":{\"code\":\"blocked_api_access\"}}"),
                new LlmGatewayException("synthetic",LlmFailureClass.PROVIDER_ERROR,"blocked_api_access"))){
            reset(router);calls.clear();
            when(router.apiAttempt(anyString(),anyInt(),anyInt(),any())).thenAnswer(call->{String key=call.getArgument(0);calls.add(key);
                ChatModel model=mock(ChatModel.class);when(model.chat(anyList())).thenThrow(failure);return model;});
            var result=service().answer("\ud558\uc774\uc820\ubca0\ub974\ud06c \ubd88\ud655\uc815\uc131 \uc6d0\ub9ac\uac00 \ubb50\uc57c?",List.of(),List.of(),true);
            assertEquals("API_QUOTA_EXHAUSTED",result.reason());
            assertEquals(List.of("a"),calls,"quota-exhausted account must be blocked before a sibling route retries");
        }
    }
    @Test void nonQuotaHttp400DoesNotClassifyAsExhausted(){
        route("a",1,true);route("b",1,true);
        respond(k->{throw new dev.langchain4j.exception.HttpException(400,"{\"error\":{\"code\":\"invalid_request_error\"}}");});
        var result=service().answer("\ud558\uc774\uc820\ubca0\ub974\ud06c \ubd88\ud655\uc815\uc131 \uc6d0\ub9ac\uac00 \ubb50\uc57c?",List.of(),List.of(),true);
        assertNotEquals("API_QUOTA_EXHAUSTED",result.reason());
    }
    @Test void directModeRejectsProxyEvenWithAnOpenAiProviderLabel(){
        route("proxy",1,true);respond(k->"{}");
        assertEquals("OPENAI_DIRECT_ERROR",service().answerDirectOpenAi("질문",UUID.randomUUID().toString()).reason());
        verifyNoInteractions(router,retrieval);
    }
    @Test void noCueMakesZeroCallsAndRetainsNoRawTelemetry(){
        route("cheap",1,true);respond(k->gate("NO_CUE",false));
        var r=service().answer("고마워요",List.of("직전 대화"),List.of(),true);
        assertEquals("NO_CUE",r.reason());assertNull(r.card());assertEquals(List.of(),calls);verifyNoInteractions(router,retrieval);
        assertEquals(1,r.stages().cue().get("contextUsed"));assertFalse(r.stages().cue().toString().contains("고마워요"));
        assertEquals(0L,r.stages().cue().get("observedInputTokens"));assertEquals(0L,r.stages().cue().get("observedReasoningTokens"));
    }
    @Test void contextualCueSkipsRetrievalAndStrongQuestionPromotesFinalOnly(){
        route("cheap",1,true);route("strong",3,false);respond(k->"{\"text\":\"적용 조건을 먼저 확인해 보세요.\",\"evidenceIds\":[]}");
        var r=service().answer("그러면 여러 조건을 고려해 어떻게 답하면 좋을까요?",List.of("앞선 조건","[assistant cue] 앞선 힌트"),List.of(),true);
        assertEquals("API_CUE",r.reason());assertEquals(List.of("strong"),calls);verifyNoInteractions(retrieval);
        assertTrue(prompts.get(0).contains("앞선 힌트"));assertEquals("model-strong",r.stages().cue().get("selectedModel"));
        verify(router).apiAttempt(eq("strong"),intThat(ms->ms>=6000&&ms<=6500),anyInt(),any());
    }
    @Test void ragCueUsesWebOnlyAndBoundedEvidenceThenApi(){
        route("cheap",1,true);respond(k->"{\"text\":\"공식 조건은 7일입니다.\",\"evidenceIds\":[\"e0\"]}");
        var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();doc.snippet="공식 조건은 7일입니다.";doc.title="공식 자료";response.results=List.of(doc);
        response.debug=Map.of("plan.allowWeb",true,"plan.webTopK",3,"stage.web","success:1","rawQuery","do not expose");when(retrieval.query(any())).thenReturn(response);
        var r=service().answer("그 조건은 무엇인가요?",List.of("반환 규칙"),List.of(new PreparedMaterialReader.Material("private","개인 자료")),true);
        assertEquals("API_CUE",r.reason());var capture=org.mockito.ArgumentCaptor.forClass(UnifiedRagOrchestrator.QueryRequest.class);verify(retrieval).query(capture.capture());
        var request=capture.getValue();assertTrue(request.useWeb);assertFalse(request.useVector);assertFalse(request.useKg);assertFalse(request.useBm25);assertFalse(request.enableQueryAnalysis);assertTrue(request.webQueryAlreadyPlanned);assertFalse(request.enableSelfAsk);assertEquals(8,request.topK);
        assertEquals(1,r.stages().cue().get("ragDocuments"));assertEquals(1,prompts.size());assertFalse(prompts.get(0).contains("개인 자료"));
        assertEquals("success:1",r.stages().cue().get("retrieval.stage.web"));assertEquals(true,r.stages().cue().get("retrieval.plan.allowWeb"));assertFalse(r.stages().cue().containsKey("rawQuery"));
    }
    @Test void retrievalReceiptRejectsCrossFieldTypesAndEnums(){
        route("cheap",1,true);respond(k->gate("RAG_CUE",false));
        var response=new UnifiedRagOrchestrator.QueryResponse();
        response.debug=Map.of("plan.allowWeb",3,"plan.webTopK",true,"stage.web","base","web.retriever","success:1");
        when(retrieval.query(any())).thenReturn(response);
        var cue=service().answer("자료 질문",List.of(),List.of(),true).stages().cue();
        for(String field:response.debug.keySet())assertFalse(cue.containsKey("retrieval."+field),field);
    }
    @Test void retrievalReceiptIsFreshPerCallAndRestoresCallerTrace(){
        route("cheap",1,true);respond(k->gate("RAG_CUE",false));
        var parent=TraceStore.context();TraceStore.put("web.analyze.returnedCount",99);TraceStore.put("web.analyze.providerDisabled",true);
        var runIds=new java.util.HashSet<String>();
        var count=new AtomicInteger();when(retrieval.query(any())).thenAnswer(call->{
            assertNotSame(parent,TraceStore.context());
            assertTrue(runIds.add(UUID.fromString((String)TraceStore.get("trace.runId")).toString()));
            if(count.getAndIncrement()==0){
                TraceStore.put("web.analyze.returnedCount",0);TraceStore.put("web.analyze.skipped.reason","provider-empty");
                TraceStore.put("web.boundedRoute.completed",true);TraceStore.put("web.boundedRoute.providerCycles",1);
                TraceStore.put("web.boundedRoute.terminalReason","budget_exhausted");TraceStore.put("rawQuery","private synthetic text");
            }
            return new UnifiedRagOrchestrator.QueryResponse();
        });
        try{
            var service=service();var first=service.answer("자료가 필요한 질문",List.of(),List.of(),true).stages().cue();
            assertEquals(0,first.get("retrieval.web.analyze.returnedCount"));
            assertEquals("budget_exhausted",first.get("retrieval.web.boundedRoute.terminalReason"));
            assertEquals(true,first.get("retrieval.traceObserved"));
            assertFalse(first.containsKey("retrieval.web.analyze.providerDisabled"));assertFalse(first.toString().contains("private synthetic text"));
            var second=service.answer("새 자료 질문",List.of(),List.of(),true).stages().cue();
            assertEquals(false,second.get("retrieval.traceObserved"));assertFalse(second.containsKey("retrieval.web.analyze.returnedCount"));
            assertSame(parent,TraceStore.context());assertEquals(99,TraceStore.get("web.analyze.returnedCount"));
        }finally{TraceStore.clear();}
    }
    @Test void providerClientAttemptsCountOnlyCompletedUniqueObservedBoundaries(){
        var completed=Map.<String,Object>of("provider","naver","providerAttemptId","hash:123456789abc",
                "clientAttemptObserved",true,"clientAttemptBoundary","webclient_subscription",
                "finishedAtEpochMs",10L,"httpStatus",200,"rawQuery","private synthetic attempt");
        var incomplete=new LinkedHashMap<>(completed);incomplete.remove("finishedAtEpochMs");
        incomplete.put("providerAttemptId","hash:000000000001");
        var unobserved=new LinkedHashMap<>(completed);unobserved.put("clientAttemptObserved",false);
        unobserved.put("providerAttemptId","hash:000000000002");
        var wrongBoundary=new LinkedHashMap<>(completed);wrongBoundary.put("clientAttemptBoundary","cache_lookup");
        wrongBoundary.put("providerAttemptId","hash:000000000003");
        var failed=new LinkedHashMap<>(completed);failed.put("providerAttemptId","hash:000000000004");failed.put("httpStatus","unknown");
        var trace=new LinkedHashMap<String,Object>();
        trace.put("conversate.search.NAVER",Map.of("requestCount",1,"requestCountScope","provider_search_method","resultCount",3));
        trace.put("web.naver.filter.runs",List.of(completed,completed,incomplete,unobserved,wrongBoundary,failed));
        var debug=new LinkedHashMap<String,Object>();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(ConversateApiCueService.class,"captureRetrievalReceipt",debug,trace,null);
        var receipt=(Map<?,?>)debug.get("search.NAVER");
        assertEquals(2,receipt.get("clientAttemptCount"));assertEquals(1,receipt.get("httpResponseCount"));
        assertEquals("observed",receipt.get("clientAttemptCoverage"));
        assertEquals(1L,receipt.get("requestCount"));assertEquals("provider_search_method",receipt.get("requestCountScope"));
        assertFalse(debug.toString().contains("private synthetic"));assertFalse(debug.toString().contains("hash:"));
    }
    @Test void missingAttemptRowsNeverClaimCacheHitOrZeroWireRequests(){
        var debug=new LinkedHashMap<String,Object>();
        var trace=Map.<String,Object>of("conversate.search.BRAVE",Map.of("requestCount",1,"resultCount",2));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(ConversateApiCueService.class,"captureRetrievalReceipt",debug,trace,null);
        var receipt=(Map<?,?>)debug.get("search.BRAVE");
        assertEquals("not_observed",receipt.get("clientAttemptCoverage"));
        assertFalse(receipt.containsKey("clientAttemptCount"));assertFalse(receipt.containsKey("httpResponseCount"));
        assertFalse(receipt.containsKey("cacheHit"));
    }
    @Test void braveAttemptEvidenceIsConfinedToItsFreshRetrievalContext(){
        route("cheap",1,true);respond(k->gate("RAG_CUE",false));
        var attempt=Map.<String,Object>of("provider","brave","providerAttemptId","hash:123456789abc",
                "clientAttemptObserved",true,"clientAttemptBoundary","resttemplate_exchange","finishedAtEpochMs",10L,"httpStatus",429);
        var parent=TraceStore.context();TraceStore.put("web.brave.attempt.runs",List.of(attempt));
        var count=new AtomicInteger();when(retrieval.query(any())).thenAnswer(call->{
            TraceStore.put("conversate.search.BRAVE",Map.of("requestCount",1,"resultCount",0,"failureReason","RATE_LIMIT"));
            if(count.getAndIncrement()==0)TraceStore.put("web.brave.attempt.runs",List.of(attempt));
            return new UnifiedRagOrchestrator.QueryResponse();
        });
        try{
            var service=service();
            var first=(Map<?,?>)service.answer("자료가 필요한 질문",List.of(),List.of(),true).stages().cue().get("search.BRAVE");
            assertEquals(1,first.get("clientAttemptCount"));assertEquals(1,first.get("httpResponseCount"));
            assertEquals("RATE_LIMIT",first.get("failureReason"));
            var second=(Map<?,?>)service.answer("새 자료 질문",List.of(),List.of(),true).stages().cue().get("search.BRAVE");
            assertEquals("not_observed",second.get("clientAttemptCoverage"));assertFalse(second.containsKey("clientAttemptCount"));
            assertSame(parent,TraceStore.context());
        }finally{TraceStore.clear();}
    }
    @Test void retrievalReceiptRejectsInvalidValuesAndRestoresTraceAfterFailure(){
        route("cheap",1,true);respond(k->gate("RAG_CUE",false));var parent=TraceStore.context();
        when(retrieval.query(any())).thenAnswer(call->{
            TraceStore.put("web.analyze.skipped.reason","private synthetic text");TraceStore.put("web.analyze.returnedCount",-1);
            TraceStore.put("web.analyze.requestedCount",Double.NaN);TraceStore.put("web.boundedRoute.providerCycles",2.5);
            TraceStore.put("web.analyze.providerDisabled","true");throw new IllegalStateException("private synthetic error");
        });
        try{
            var cue=service().answer("자료 질문",List.of(),List.of(),true).stages().cue();
            assertEquals("SEARCH_UNAVAILABLE",cue.get("retrievalFailure"));assertEquals(false,cue.get("retrieval.traceObserved"));
            assertFalse(cue.toString().contains("private synthetic"));assertFalse(cue.containsKey("retrieval.web.analyze.providerDisabled"));
            assertSame(parent,TraceStore.context());
        }finally{TraceStore.clear();}
    }
    @Test void noEvidenceStillInvokesFinalAndMalformedHintIsNotNormalNoCue(){
        route("cheap",1,true);respond(k->"{\"text\":\"같은 시점과 기준으로 수치를 비교하세요.\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");when(retrieval.query(any())).thenReturn(new UnifiedRagOrchestrator.QueryResponse());
        var r=service().answer("현재 수치는?",List.of(),List.of(),true);assertEquals("CUE_EVIDENCE_INSUFFICIENT",r.reason());assertEquals(1,calls.size());assertNotNull(r.card());
        calls.clear();respond(k->"{\"decision\":\"NO_CUE\"}");r=service().answer("어떻게 답할까요?",List.of(),List.of(),true);
        assertEquals("GENERATION_INVALID_OUTPUT",r.reason());assertEquals("local_rules",r.stages().cue().get("decisionSource"));assertNull(r.card());
    }
    @Test void hintReservesTimeForAlternativeDespiteSlowCandidateEstimate(){
        route("a",1,true);route("b",1,true);
        env.withProperty("conversate.cue.routes.a.expected-latency-ms","2000")
                .withProperty("conversate.cue.routes.a.input-usd-per-million","0.1")
                .withProperty("conversate.cue.routes.a.output-usd-per-million","0.1");
        respond(k->{if(k.equals("a"))throw new IllegalStateException("synthetic_failure");return "{\"text\":\"잠시 생각할 시간을 부탁해 보세요.\",\"evidenceIds\":[]}";});
        var result=service().answer("어떻게 부탁할까요?",List.of(),List.of(),true);
        assertEquals("API_CUE",result.reason());assertEquals(List.of("a","b"),calls);
        verify(router).apiAttempt(eq("a"),intThat(ms->ms>0&&ms<6500),anyInt(),any());
    }
    @Test void apiFailureUsesOneOtherApiAndCooldownChangesNextSelection(){
        route("a",1,true);route("b",1,true);respond(k->{if(k.equals("a"))throw new IllegalStateException("synthetic_failure");return "{\"text\":\"잠시 생각할 시간을 부탁해 보세요.\",\"evidenceIds\":[]}";});
        var service=service();var first=service.answer("어떻게 부탁할까요?",List.of(),List.of(),true);assertEquals("API_CUE",first.reason());assertEquals(List.of("a","b"),calls);assertEquals(true,first.stages().cue().get("fallback"));
        calls.clear();service.answer("어떻게 답할까요?",List.of(),List.of(),true);assertEquals(List.of("b"),calls);
    }
    @Test void disabledMissingCredentialLocalAndExhaustedRoutesNeverCalled(){
        route("local",4,true);props.getModels().get("local").setProvider("local");route("missing",4,true);props.getModels().get("missing").setCredentialEnv("ABSENT");route("disabled",4,true);props.getModels().get("disabled").setEnabled(false);
        route("quota",4,true);env.withProperty("conversate.cue.routes.quota.daily-request-limit","0");
        var r=service().answer("어떻게 답할까요?",List.of(),List.of(),true);assertEquals("API_UNAVAILABLE",r.reason());verifyNoInteractions(router,retrieval);
    }
    @Test void costLatencyAndExplicitFreeAllowanceChangeOrder(){
        route("a",2,true);route("b",2,true);env.withProperty("conversate.cue.routes.a.input-usd-per-million","0.1").withProperty("conversate.cue.routes.a.output-usd-per-million","0.4");
        assertEquals("a",service().candidates(true,false,Set.of(),1000).get(0));
        props.getModels().get("b").setProvider("gemini");
        env.withProperty("gemini.gateway.purpose.router.enabled","true")
                .withProperty("conversate.cue.accounts.gemini.observed-at-ms",""+System.currentTimeMillis())
                .withProperty("conversate.cue.accounts.gemini.verified-free-requests-remaining","5");assertEquals("b",service().candidates(true,false,Set.of(),1000).get(0));
        env.withProperty("conversate.cue.routes.b.expected-latency-ms","5000");assertEquals("a",service().candidates(true,false,Set.of(),1000).get(0));
    }
    @Test void topicChangeExcludesPreviousContextFromHint(){
        route("cheap",1,true);respond(k->"{\"text\":\"새 주제의 기준부터 정해 보세요.\",\"evidenceIds\":[]}");
        var r=service().answer("다른 주제로 넘어갈게요. 어떻게 답할까요?",List.of("오래된 무관한 주제"),List.of(),true);
        assertEquals("API_CUE",r.reason());assertEquals(0,r.stages().cue().get("contextUsed"));assertFalse(prompts.get(0).contains("오래된 무관한 주제"));
    }
    @Test void hungryAfterBluetoothDropsOldDeviceContextFromTheHintPrompt(){
        route("cheap",1,true);respond(k->"{\"text\":\"근처 식당의 영업시간과 대기부터 확인해 보세요.\",\"evidenceIds\":[]}");
        when(retrieval.query(any())).thenReturn(new UnifiedRagOrchestrator.QueryResponse());
        var r=service().answer("배고픈데 돈가스 맛있는 곳 알려줘",List.of("메타 디스플레이 블루투스 페어링을 점검하세요"),List.of(),true);
        assertEquals(0,r.stages().cue().get("contextUsed"));
        assertEquals(true,r.stages().cue().get("topicChanged"));
        assertFalse(prompts.isEmpty());assertFalse(prompts.get(0).contains("블루투스"));
        assertTrue(prompts.get(0).contains("pre-selected past window")||prompts.get(0).contains("latest transcript"));
    }
    @Test void cancellationDoesNotCallGateOrFinal(){
        route("cheap",1,true);Thread.currentThread().interrupt();try{assertNull(service().answer("질문",List.of(),List.of(),true).card());verifyNoInteractions(router,retrieval);}finally{Thread.interrupted();}
    }
    @Test void oversizedUnbrokenEvidenceIsNotTruncatedIntoAnUnqualifiedClaim(){
        route("cheap",1,true);respond(k->"{\"text\":\"적용 대상과 예외 조건을 함께 비교하세요.\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");
        var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();
        doc.snippet="적용됩니다 ".repeat(400)+"단, 예외 조건에서는 적용되지 않습니다.";response.results=List.of(doc);when(retrieval.query(any())).thenReturn(response);
        var result=service().answer("적용 조건은?",List.of(),List.of(),true);
        assertEquals("CUE_EVIDENCE_INSUFFICIENT",result.reason());assertEquals(1,calls.size());assertNotNull(result.card());
        assertTrue(result.card().sourceIds().isEmpty());assertEquals(0,result.stages().cue().get("evidenceChars"));
    }
    @Test void normalDefaultBudgetAllowsHintAboveTheFormerFiveCentLimit(){
        route("cheap",1,true);
        env.withProperty("conversate.cue.routes.cheap.input-usd-per-million","0")
                .withProperty("conversate.cue.routes.cheap.output-usd-per-million","100");
        respond(k->"{\"text\":\"조건을 확인한 뒤 답하겠습니다.\",\"evidenceIds\":[]}");
        assertEquals("API_CUE",service().answer("어떻게 답할까요?",List.of(),List.of(),true).reason());
        assertEquals(List.of("cheap"),calls);
    }
    @Test void oversizedOverrideCannotSpendBeyondFiftyCents(){
        route("cheap",1,true);
        env.withProperty("conversate.cue.max-request-usd","0.75")
                .withProperty("conversate.cue.routes.cheap.input-usd-per-million","0")
                .withProperty("conversate.cue.routes.cheap.output-usd-per-million","600")
                .withProperty("conversate.cue.accounts.openai.daily-budget-usd","2");
        respond(k->gate("CUE",false));
        var result=service().answer("어떻게 답할까요?",List.of(),List.of(),true);
        assertEquals("API_UNAVAILABLE",result.reason());assertNull(result.card());
        assertEquals(List.of(),calls);
    }
    @Test void normalDisplayProfileDisablesDollarAdmissionWithoutTestOverride(){
        var effective=new org.springframework.core.env.StandardEnvironment();
        effective.setActiveProfiles("local","meta-display");
        org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.applyTo(effective);
        assertEquals(.50,effective.getProperty("conversate.cue.max-request-usd",Double.class));
        var configured=new ConversateApiCueService(props,eligibility,router,retrieval,effective);
        assertEquals(false,effective.getProperty("conversate.cost.enforce-limits",Boolean.class));
        assertEquals(0d,org.springframework.test.util.ReflectionTestUtils.invokeMethod(configured,"requestCostLimit"));
        verifyNoInteractions(router,retrieval);
    }
    @Test void requestCeilingDoesNotPromotePremiumWhenOrdinaryHintCannotFit(){
        route("cheap",1,true);route("premium",4,false);
        var request=ConversateCardPrompt.cueHint("도움이 필요합니다?",List.of(),List.of(),false);
        int input=request.messages().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+64;
        double gateReservation=(input*.75*1.25+1024*4.5)/1_000_000d;
        env.withProperty("conversate.cue.max-request-usd",""+(gateReservation-.000001));respond(k->gate("CUE",false));
        var result=service().answer("도움이 필요합니다?",List.of(),List.of(),true);
        assertEquals("API_UNAVAILABLE",result.reason());assertNull(result.card());assertEquals(List.of(),calls);
    }
    @Test void insufficientSearchReservationPreventsRetrievalAfterSuccessfulGate(){
        route("cheap",1,true);
        env.withProperty("conversate.cue.max-request-usd","0.009");respond(k->"{\"text\":\"적용 범위와 발표 시점을 나누어 확인하세요.\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");
        var result=service().answer("현재 공식 자료는?",List.of(),List.of(),true);
        assertEquals("CUE_EVIDENCE_INSUFFICIENT",result.reason());
        assertEquals("SEARCH_COST_BUDGET_EXHAUSTED",result.stages().cue().get("retrievalFailure"));
        verifyNoInteractions(retrieval);assertEquals(List.of("cheap"),calls);
    }
    @Test void searchReservesTheSameBudgetAndForcesASingleProviderCycle(){
        route("cheap",1,true);respond(k->gate("RAG_CUE",false));
        when(retrieval.query(any())).thenAnswer(call->{
            assertEquals(true,TraceStore.get("conversate.web.singleCycle"));
            return new UnifiedRagOrchestrator.QueryResponse();
        });
        var result=service().answer("현재 공식 자료는?",List.of(),List.of(),true);
        assertEquals(.01,result.stages().cue().get("retrievalEstimatedCostUsd"));
        assertEquals(.5,result.stages().cue().get("maxRequestUsd"));
        assertEquals("not_observed",result.stages().cue().get("billedCostUsd"));
        verify(retrieval).query(any());
    }
    @Test void searchReservationCanPreventHintThatWouldOtherwiseFitTheRequestCap(){
        route("cheap",1,true);
        env.withProperty("conversate.cue.max-request-usd","0.24")
                .withProperty("conversate.cue.routes.cheap.input-usd-per-million","0")
                .withProperty("conversate.cue.routes.cheap.output-usd-per-million","230")
                .withProperty("conversate.cue.accounts.openai.daily-budget-usd","2");
        respond(k->gate("RAG_CUE",false));
        var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();
        doc.snippet="The synthetic retention period is two years.";response.results=List.of(doc);
        when(retrieval.query(any())).thenReturn(response);
        var result=service().answer("자료의 보관 기간은?",List.of(),List.of(),true);
        assertEquals("API_UNAVAILABLE",result.reason());assertNull(result.card());assertEquals(List.of(),calls);
        assertEquals(.01,((Number)result.stages().cue().get("requestReservedUsd")).doubleValue(),.000001);
        verify(retrieval).query(any());
    }
    @Test void expertIsAnExplicitDifficultyDecisionAndNeverAnErrorFallback(){
        route("cheap",1,true);route("reasoning",3,false);route("expert",4,false);
        respond(k->"{\"text\":\"충돌하는 조건을 전문가와 확인해 보세요.\",\"evidenceIds\":[]}");
        var result=service().answer("전문가 수준으로 복잡한 조건을 판단하려면?",List.of(),List.of(),true);
        assertEquals(List.of("expert"),calls);assertEquals("API_CUE",result.reason());
        assertTrue(result.stages().cue().get("hintAttempts").toString().contains("EXPERT_DIFFICULTY"));
    }
    @Test void malformedBilledOutputRetainsObservedTokensCacheAndFallbackReason(){
        route("a",1,true);route("b",1,true);
        var usage=dev.langchain4j.model.openai.OpenAiTokenUsage.builder().inputTokenCount(2048).outputTokenCount(9).totalTokenCount(2057)
                .inputTokensDetails(dev.langchain4j.model.openai.OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(1024).build())
                .outputTokensDetails(dev.langchain4j.model.openai.OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(6).build()).build();
        when(router.apiAttempt(anyString(),anyInt(),anyInt(),any())).thenAnswer(call->{String key=call.getArgument(0);calls.add(key);
            ChatModel model=mock(ChatModel.class);when(model.chat(anyList())).thenReturn(ChatResponse.builder().modelName("served-"+key).tokenUsage(usage)
                    .aiMessage(AiMessage.from(key.equals("a")?"{\"invalid\":true}":"{\"text\":\"잠시 생각할 시간을 부탁해 보세요.\",\"evidenceIds\":[]}")).build());return model;});
        var result=service().answer("어떻게 부탁할까요?",List.of(),List.of(),true);
        assertEquals("API_CUE",result.reason());
        @SuppressWarnings("unchecked") var attempts=(List<Map<String,Object>>)result.stages().cue().get("hintAttempts");
        assertEquals(2,attempts.size());assertEquals("GENERATION_INVALID_OUTPUT",attempts.get(0).get("status"));
        assertEquals(Map.of("input",2048,"output",9,"total",2057),attempts.get(0).get("actualTokens"));
        assertEquals(true,attempts.get(0).get("cacheHit"));assertEquals(1024,attempts.get(0).get("cachedInputTokens"));
        assertEquals(6,attempts.get(0).get("reasoningTokens"));assertEquals(3,attempts.get(0).get("visibleOutputTokens"));
        assertEquals(12L,result.stages().cue().get("observedReasoningTokens"));assertEquals(18L,result.stages().cue().get("observedOutputTokens"));
        assertEquals("GENERATION_INVALID_OUTPUT",attempts.get(1).get("fallbackReason"));
        for(var attempt:attempts)assertTrue(attempt.keySet().containsAll(Set.of("selectedModel","estimatedCost","actualTokens","cacheHit","fallbackReason","latencyMs","escalationReason")));
        assertFalse(result.stages().cue().toString().contains("고마워요"));
    }
    @Test void followupSearchPreservesLatestQuestionAndUsesUserTopicInsteadOfPriorCue(){
        route("cheap",1,true);
        String topic="하이젠베르크 불확정성 원리가 뭐야?";
        String followup="그럼 공식 자료에서 측정기를 더 좋게 만들면 해결돼?";
        respond(k->"{\"text\":\"측정기 성능과 양자 상태의 한계를 구분해 보세요.\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");
        when(retrieval.query(any())).thenAnswer(call->{
            UnifiedRagOrchestrator.QueryRequest query=call.getArgument(0);
            assertTrue(query.query.startsWith(followup));
            assertTrue(query.query.contains(topic));
            assertFalse(query.query.contains("자료 부족"));
            assertFalse(query.query.contains("[assistant cue]"));
            return new UnifiedRagOrchestrator.QueryResponse();
        });
        service().answer(followup,List.of(topic,"[assistant cue] 자료 부족: 확인이 필요합니다."),List.of(),true);
        verify(retrieval).query(any());assertEquals(1,calls.size());
    }
    @Test void malformedOutputReportsOnlyAllowlistedValidationReasonAndCounts(){
        route("cheap",1,true);
        var samples=Map.of("{\"unexpected\":true}","schema_invalid",
                "{\"text\":\"synthetic-marker\",\"evidenceIds\":[\"unknown\"]}","evidence_invalid",
                "{\"text\":\""+"가".repeat(ConversateSessionService.HINT_TEXT_MAX+1)+"\",\"evidenceIds\":[]}","text_limit",
                "not-json-private-marker","json_invalid");
        for(var sample:samples.entrySet()){
            reset(router);respond(k->sample.getKey());
            var result=service().answer("어떻게 부탁할까요?",List.of(),List.of(),true);
            @SuppressWarnings("unchecked") var attempts=(List<Map<String,Object>>)result.stages().cue().get("hintAttempts");
            assertEquals(sample.getValue(),attempts.get(0).get("outputValidation"));
            assertEquals(org.apache.commons.codec.digest.DigestUtils.sha256Hex(sample.getKey()),attempts.get(0).get("outputHash"));
            assertFalse(result.stages().cue().toString().contains("synthetic-marker"));
            assertFalse(result.stages().cue().toString().contains("private-marker"));
        }
    }
    @Test void boundedEvidenceSelectionKeepsSubjectDefinitionsAheadOfAuthorBiographies(){
        var biography=new UnifiedRagOrchestrator.Doc();biography.snippet="<a href='https://example.org/person'>제인 연구자</a>: 이론을 발표했습니다.";
        var broad=new UnifiedRagOrchestrator.Doc();broad.snippet="<a href='https://example.org/overview'>측정 원리 개요</a>: 원리에 관한 소개입니다.";
        var history=new UnifiedRagOrchestrator.Doc();history.snippet="<a href='https://example.org/history'>제인 연구자 생애</a>: 연구 활동입니다.";
        var definition=new UnifiedRagOrchestrator.Doc();definition.snippet="<a href='https://example.org/definition'>제인 측정 원리 뜻</a>: 파장과 주파수의 관계를 설명합니다.";
        var selected=ConversateApiCueService.selectCueDocuments("제인 측정 원리",List.of(biography,broad,history,definition));
        assertEquals(List.of(definition,broad,biography),selected);
        assertEquals("<a href='https://example.org/definition'>제인 측정 원리 뜻</a>: 파장과 주파수의 관계를 설명합니다.",selected.get(0).snippet);
        assertEquals(List.of(biography,broad),ConversateApiCueService.selectCueDocuments("관련 없는 주제",List.of(biography,broad)));
    }
    @Test void definitionSearchRemovesOnlyConversationalEndingAndPreservesTheSpokenQuestion(){
        route("cheap",1,true);respond(k->"{\"text\":\"두 물리량 사이의 관계를 설명합니다.\",\"evidenceIds\":[\"e0\"]}");
        var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();doc.snippet="관계와 제약에 관한 공식 설명입니다.";response.results=List.of(doc);
        when(retrieval.query(any())).thenAnswer(call->{
            UnifiedRagOrchestrator.QueryRequest query=call.getArgument(0);
            assertEquals("공식 자료에서 하이젠베르크 불확정성 원리",query.query);assertEquals(8,query.webTopK);assertEquals(8,query.topK);return response;
        });
        String question="공식 자료에서 하이젠베르크 불확정성 원리가 뭐야?";
        assertEquals("API_CUE",service().answer(question,List.of(),List.of(),true).reason());
        assertTrue(prompts.get(0).contains(question));verify(retrieval,times(1)).query(any());
    }
    @Test void explicitInsufficientEvidenceKeepsUsefulTextWithoutClaimingCitations(){
        route("a",1,true);route("b",1,true);
        var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();doc.snippet="반환 규칙에 관한 자료입니다.";response.results=List.of(doc);
        when(retrieval.query(any())).thenReturn(response);
        String hint="반환 조건은 상품 상태와 구입 시점에 따라 달라요. 이 두 항목을 함께 확인하세요.";
        respond(k->"{\"text\":\""+hint+"\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");
        var result=service().answer("그 조건은 무엇인가요?",List.of("반환 규칙"),List.of(),true);
        assertEquals("CUE_EVIDENCE_INSUFFICIENT",result.reason());assertEquals(List.of("a"),calls);
        assertEquals(1,result.generationAttempts());assertTrue(result.card().sourceIds().isEmpty());
        assertEquals(hint,result.card().text());
        assertFalse(result.stages().cue().toString().contains(hint));assertEquals(true,result.stages().cue().get("evidenceInsufficient"));
        assertEquals(List.of(),result.stages().cue().get("citedEvidenceIds"));
        assertEquals(List.of(Map.of("id","e0","sha256",org.apache.commons.codec.digest.DigestUtils.sha256Hex(doc.snippet))),result.stages().cue().get("evidenceDigests"));
        assertFalse(result.stages().cue().toString().contains(doc.snippet));
    }
    @Test void evidenceFlagDoesNotBypassGroundedCitationsOrTheExactSchema(){
        route("a",1,true);route("b",1,true);
        var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();doc.snippet="반환 기한은 7일입니다.";response.results=List.of(doc);
        when(retrieval.query(any())).thenReturn(response);
        for(String invalid:List.of(
                "{\"text\":\"근거 없는 답\",\"evidenceIds\":[],\"evidenceInsufficient\":false}",
                "{\"text\":\"잘못된 인용\",\"evidenceIds\":[\"unknown\"]}",
                "{\"text\":\"모순된 상태\",\"evidenceIds\":[\"e0\"],\"evidenceInsufficient\":true}",
                "{\"text\":\"잘못된 필드\",\"evidenceIds\":[\"e0\"],\"unexpected\":false}",
                "{\"text\":\"잘못된 타입\",\"evidenceIds\":[],\"evidenceInsufficient\":\"true\"}")){
            reset(router);calls.clear();respond(k->k.equals("a")?invalid:"{\"text\":\"반환 기한은 7일입니다.\",\"evidenceIds\":[\"e0\"]}");
            var result=service().answer("그 조건은 무엇인가요?",List.of("반환 규칙"),List.of(),true);
            assertEquals("API_CUE",result.reason());assertEquals(List.of("a","b"),calls);assertEquals(List.of("e0"),result.card().sourceIds());
        }
    }
    @Test void insufficientEvidenceFlagIsInvalidForContextOnlyOrDirectVerification(){
        route("a",1,true);props.getModels().get("a").setBaseUrl("https://api.openai.com/v1");
        respond(k->"{\"text\":\"확인 필요\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");
        assertNull(service().answer("어떻게 부탁할까요?",List.of(),List.of(),true).card());
        assertNull(service().answerDirectOpenAi("어떻게 부탁할까요?",UUID.randomUUID().toString()).card());
    }
    @Test void ragInsufficiencyRequestsOneSupplementAndUsesItsEvidenceWithoutResettingTheCallBudget(){
        route("a",1,true);route("b",1,true);var queries=new AtomicInteger();
        when(retrieval.query(any())).thenAnswer(call->{
            int number=queries.incrementAndGet();
            assertEquals(number==2,Boolean.TRUE.equals(TraceStore.get("conversate.web.ragSupplement")));
            TraceStore.put("conversate.search.NAVER",Map.of("provider","NAVER","failureReason","NONE","resultCount",3));
            if(number==2)TraceStore.put("conversate.search.BRAVE",Map.of("provider","BRAVE","requestCount",1,"resultCount",1,
                    "failureReason","NONE","fallbackReason","rag_evidence_insufficient"));
            var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();
            doc.snippet=number==1?"조건의 명칭만 설명한 자료입니다.":"추가 근거에 따르면 반환 기한은 7일입니다.";response.results=List.of(doc);return response;
        });
        respond(k->calls.size()==1?"{\"text\":\"확인 필요\",\"evidenceIds\":[],\"evidenceInsufficient\":true}":
                "{\"text\":\"반환 기한은 7일입니다.\",\"evidenceIds\":[\"e1\"],\"evidenceInsufficient\":false}");
        var result=service().answer("그 조건은 무엇인가요?",List.of("반환 규칙"),List.of(),true);
        assertEquals("API_CUE",result.reason());assertEquals(2,queries.get());assertEquals(2,result.generationAttempts());
        assertEquals(List.of("e1"),result.card().sourceIds());assertTrue(prompts.get(1).contains("반환 기한은 7일"));
        assertEquals(List.of("e1"),result.stages().cue().get("citedEvidenceIds"));
        assertEquals(Map.of("id","e1","sha256",org.apache.commons.codec.digest.DigestUtils.sha256Hex("추가 근거에 따르면 반환 기한은 7일입니다.")),((List<?>)result.stages().cue().get("evidenceDigests")).get(1));
        assertEquals(.01,result.stages().cue().get("retrievalEstimatedCostUsd"));
        assertEquals("rag_evidence_insufficient",((Map<?,?>)result.stages().cue().get("search.BRAVE")).get("fallbackReason"));
    }
    @Test void priorBraveAttemptOrNaverErrorPreventsPostRagSearch(){
        route("a",1,true);respond(k->"{\"text\":\"확인 필요\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");
        for(String failure:List.of("NONE","AUTH_OR_CONFIG","RATE_LIMIT","TIMEOUT_OR_BUDGET","PROVIDER_ERROR")){
            reset(retrieval);when(retrieval.query(any())).thenAnswer(call->{
                TraceStore.put("conversate.search.NAVER",Map.of("failureReason",failure,"resultCount",3));
                if(failure.equals("NONE"))TraceStore.put("conversate.search.BRAVE",Map.of("failureReason","AUTH_OR_CONFIG","requestCount",0));
                var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();doc.snippet="조건 이름만 있습니다.";response.results=List.of(doc);return response;
            });
            assertEquals("CUE_EVIDENCE_INSUFFICIENT",service().answer("그 조건은 무엇인가요?",List.of("반환 규칙"),List.of(),true).reason());
            verify(retrieval,times(1)).query(any());
        }
    }
    @Test void validResponseWithinStageDeadlineSurvivesExpiredAttemptSlice(){
        route("a",1,true);route("b",1,true);
        respond(k->{com.abandonware.ai.addons.budget.TimeBudgetContext.set(
                com.abandonware.ai.addons.budget.TimeBudget.untilNanoDeadline(System.nanoTime()-1));
            return "{\"text\":\"잠시 생각할 시간을 요청하세요.\",\"evidenceIds\":[]}";});
        var result=service().answer("어떻게 부탁할까요?",List.of(),List.of(),true);
        assertEquals("API_CUE",result.reason());assertEquals(List.of("a"),calls);
    }
    @Test void responseAfterStageDeadlineIsStillRejected(){
        route("a",1,true);env.withProperty("conversate.cue.hint-timeout-ms","500")
                .withProperty("conversate.cue.routes.a.expected-latency-ms","100");
        respond(k->{try{Thread.sleep(550);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            return "{\"text\":\"늦은 답변\",\"evidenceIds\":[]}";});
        assertEquals("GENERATION_TIMEOUT",service().answer("어떻게 부탁할까요?",List.of(),List.of(),true).reason());
        assertEquals(List.of("a"),calls);
    }
    @Test void exhaustedGenerationCallBudgetCannotStartAnotherSearchOrHint(){
        route("a",1,true);route("b",1,true);env.withProperty("conversate.cue.max-attempts-per-stage","2");
        when(retrieval.query(any())).thenAnswer(call->{
            TraceStore.put("conversate.search.NAVER",Map.of("failureReason","NONE","resultCount",3));
            var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();doc.snippet="명칭만 포함된 근거";response.results=List.of(doc);return response;
        });
        respond(k->k.equals("a")?"invalid":"{\"text\":\"확인 필요\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");
        var result=service().answer("그 조건은 무엇인가요?",List.of("반환 규칙"),List.of(),true);
        assertEquals("CUE_EVIDENCE_INSUFFICIENT",result.reason());assertEquals(2,result.generationAttempts());
        verify(retrieval,times(1)).query(any());
    }
    @Test void stillInsufficientOrFilteredEmptySupplementStopsWithoutAThirdPass(){
        route("a",1,true);
        for(boolean filteredEmpty:List.of(true,false)){
            reset(retrieval);calls.clear();var passes=new AtomicInteger();
            when(retrieval.query(any())).thenAnswer(call->{
                int pass=passes.incrementAndGet();TraceStore.put("conversate.search.NAVER",Map.of("failureReason","NONE","resultCount",3));
                var response=new UnifiedRagOrchestrator.QueryResponse();
                if(pass==2&&filteredEmpty){response.results=List.of();return response;}
                var doc=new UnifiedRagOrchestrator.Doc();doc.snippet=pass==1?"첫 자료 이름만 있습니다.":"다른 자료 이름만 있습니다.";response.results=List.of(doc);return response;
            });
            respond(k->"{\"text\":\"확인 필요\",\"evidenceIds\":[],\"evidenceInsufficient\":true}");
            var result=service().answer("그 조건은 무엇인가요?",List.of("반환 규칙"),List.of(),true);
            assertEquals("CUE_EVIDENCE_INSUFFICIENT",result.reason());assertEquals(2,passes.get());
            assertEquals(filteredEmpty?1:2,result.generationAttempts());assertTrue(result.card().sourceIds().isEmpty());
        }
    }
    @Test void entirelyFragmentedWebEvidenceCannotBeCitedAsGroundedFacts(){
        route("a",1,true);var response=new UnifiedRagOrchestrator.QueryResponse();
        respond(k->"{\"text\":\"조건이 입증되었습니다.\",\"evidenceIds\":[\"e0\"]}");
        var doc=new UnifiedRagOrchestrator.Doc();doc.snippet="[WEB:DOCS|CRED:TRUSTED] 측정할 수 없다는 것이다. 원리에 대한... 사람들이... 조건이...";
        response.results=List.of(doc);when(retrieval.query(any())).thenReturn(response);
        var result=service().answer("공식 자료에서 그 원리의 정의가 뭐야?",List.of(),List.of(),true);
        assertEquals("GENERATION_INVALID_OUTPUT",result.reason());assertNull(result.card());assertEquals(1,calls.size());
    }
    @Test void oneTruncatedTailDoesNotRejectAnOtherwiseCompleteWebStatement(){
        route("a",1,true);respond(k->"{\"text\":\"적용 기간은 7일입니다.\",\"evidenceIds\":[\"e0\"]}");
        var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();
        doc.snippet="[WEB:DOCS|CRED:TRUSTED] 적용 기간은 7일입니다. 추가 설명...";response.results=List.of(doc);when(retrieval.query(any())).thenReturn(response);
        assertEquals("API_CUE",service().answer("적용 기간이 어떻게 되나요?",List.of(),List.of(),true).reason());assertEquals(1,calls.size());
    }
    @Test void definitionRefinementPreservesNounEndingsAndConstraints(){
        route("a",1,true);respond(k->"{\"text\":\"근거를 확인합니다.\",\"evidenceIds\":[\"e0\"]}");
        for(String question:List.of("공식 정의에서 길이 뭐야?","2026년 이전 자료는 제외하고 교차검증해 줘","섭씨 20도에서 파이가 뭐야?")){
            reset(retrieval);when(retrieval.query(any())).thenAnswer(call->{
                UnifiedRagOrchestrator.QueryRequest request=call.getArgument(0);
                assertEquals(question.equals("섭씨 20도에서 파이가 뭐야?")?"섭씨 20도에서 파이":question,request.query);
                var response=new UnifiedRagOrchestrator.QueryResponse();var doc=new UnifiedRagOrchestrator.Doc();doc.snippet="자료의 정의를 설명합니다.";response.results=List.of(doc);return response;
            });
            service().answer(question,List.of(),List.of(),true);verify(retrieval,times(1)).query(any());
        }
    }
    @Test void exhaustedApisMayOnlySelectAFixedLocalSupportSuggestion(){
        var service=service();var support=new ConversateLocalCardGenerator((messages,schema)->"{\"choice\":1}",1000);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"localSupport",support);
        var result=service.answer("도움을 받을 수 있나요?",List.of(),List.of(),true);
        assertEquals("LOCAL_SUPPORT_FALLBACK",result.reason());assertEquals(ConversateCardPrompt.SUGGESTIONS.get(1),result.card().text());
        assertEquals("local_support",result.stages().cue().get("decisionSource"));assertEquals(0,result.stages().cue().get("apiAttempts"));
        verifyNoInteractions(router,retrieval);
        assertNull(service.answer("고마워요",List.of(),List.of(),true).card());
    }
    @Test void repeatedEvidenceAndContextHaveAStablePrefixBeforeTheChangingTranscript(){
        var evidence=List.of(new ConversateCardPrompt.Evidence("e0","e0","공식 조건은 7일입니다."));
        var first=ConversateCardPrompt.cueHint("first",List.of("context"),evidence,true).messages();
        var next=ConversateCardPrompt.cueHint("next",List.of("context"),evidence,true).messages();
        assertEquals(first.get(0),next.get(0));
        String data=((dev.langchain4j.data.message.UserMessage)first.get(1)).singleText();
        assertTrue(data.indexOf("evidence")<data.indexOf("recentContext"));assertTrue(data.indexOf("recentContext")<data.indexOf("transcript"));
    }
    @Test void cuePromptTargetsTheFieldTestedHintLengthInsideTheHardCap(){
        var request=ConversateCardPrompt.cueHint("질문",List.of(),List.of(),false);
        String system=request.messages().get(0).toString();
        assertTrue(system.contains("480-540"));assertTrue(system.contains(String.valueOf(ConversateSessionService.HINT_TEXT_MAX)));
    }
    @Test void wireSchemaCarriesTheStrictShapeForNativeStructuredOutput(){
        var request=ConversateCardPrompt.cueHint("질문",List.of(),List.of(),false);
        var schema=ConversateCardPrompt.wireSchema(request.schema(),"conversate_cue");
        assertEquals("conversate_cue",schema.name());assertNotNull(schema.rootElement());
    }
    @Test void cueAttemptsCarryTheNativeStructuredOutputSchema(){
        route("cheap",1,true);respond(k->"{\"text\":\"조건을 먼저 확인해 보세요.\",\"evidenceIds\":[]}");
        assertEquals("API_CUE",service().answer("어떻게 답할까요?",List.of(),List.of(),true).reason());
        verify(router).apiAttempt(eq("cheap"),anyInt(),anyInt(),argThat(schema->schema!=null&&"conversate_cue".equals(schema.name())));
    }
    @Test void cleanStructuredJsonNeedsNoRecoveryAndReportsStructuredOutput(){
        route("cheap",1,true);respond(k->"{\"text\":\"조건을 먼저 확인해 보세요.\",\"evidenceIds\":[]}");
        var result=service().answer("어떻게 답할까요?",List.of(),List.of(),true);
        assertEquals("API_CUE",result.reason());
        @SuppressWarnings("unchecked") var attempts=(List<Map<String,Object>>)result.stages().cue().get("hintAttempts");
        assertEquals(false,attempts.get(0).get("jsonRecoveryUsed"));
        assertEquals("json_schema",attempts.get(0).get("structuredOutput"));
    }
    @Test void wrappedJsonUsesRecoveryOnlyAndStrictValidationStillApplies(){
        route("cheap",1,true);
        respond(k->"```json\n{\"text\":\"조건을 먼저 확인해 보세요.\",\"evidenceIds\":[]}\n```");
        var result=service().answer("어떻게 답할까요?",List.of(),List.of(),true);
        assertEquals("API_CUE",result.reason());
        @SuppressWarnings("unchecked") var attempts=(List<Map<String,Object>>)result.stages().cue().get("hintAttempts");
        assertEquals(true,attempts.get(0).get("jsonRecoveryUsed"));
        reset(router);calls.clear();respond(k->"```json\n{\"invalid\":true}\n```");
        assertEquals("GENERATION_INVALID_OUTPUT",service().answer("어떻게 답할까요?",List.of(),List.of(),true).reason());
    }
    @Test void cardAcceptsTheFieldTestedHintLengthInsideTheHardCap(){
        route("cheap",1,true);String hint="가".repeat(510);
        respond(k->"{\"text\":\""+hint+"\",\"evidenceIds\":[]}");
        var result=service().answer("어떻게 답할까요?",List.of(),List.of(),true);
        assertEquals("API_CUE",result.reason());assertNotNull(result.card());assertEquals(hint,result.card().text());
        int chars=hint.codePointCount(0,hint.length());assertTrue(chars>=480&&chars<=540);
        assertEquals(chars,result.stages().cue().get("hintTextChars"));assertEquals(chars,result.stages().cue().get("cardTextChars"));
    }
    @Test void cardAcceptsExactlyTheHardCapAndRejectsOnePastIt(){
        route("cheap",1,true);String hint="가".repeat(ConversateSessionService.HINT_TEXT_MAX);
        respond(k->"{\"text\":\""+hint+"\",\"evidenceIds\":[]}");
        var result=service().answer("어떻게 답할까요?",List.of(),List.of(),true);
        assertEquals("API_CUE",result.reason());assertNotNull(result.card());assertEquals(hint,result.card().text());
        reset(router);respond(k->"{\"text\":\""+"가".repeat(ConversateSessionService.HINT_TEXT_MAX+1)+"\",\"evidenceIds\":[]}");
        var over=service().answer("어떻게 답할까요?",List.of(),List.of(),true);
        assertEquals("GENERATION_INVALID_OUTPUT",over.reason());assertNull(over.card());
        @SuppressWarnings("unchecked") var attempts=(List<Map<String,Object>>)over.stages().cue().get("hintAttempts");
        assertEquals("text_limit",attempts.get(0).get("outputValidation"));
    }

}
