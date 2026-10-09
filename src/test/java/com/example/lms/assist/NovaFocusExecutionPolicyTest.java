package com.example.lms.assist;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.api.PublicRequestBudgetGuard;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.llm.ModelSelectionException;
import com.example.lms.search.TraceStore;
import com.example.lms.service.*;
import com.example.lms.service.chat.ChatRunRegistry;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusExecutionPolicyTest {
    static final String LOCAL="fixture-local:chat",A="llmrouter.api-a",B="llmrouter.api-b";
    static final ObjectMapper JSON=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
    @Test void dedicatedTargetSurvivesJsonWithoutErasingTheGeneralFallbackPolicy() {
        var selected=assertDoesNotThrow(()->selection("FIXED",A,"GEMINI_WEBSEARCH_ONLY",true,List.of(B,LOCAL)));
        assertEquals("GEMINI_WEBSEARCH_ONLY",selected.routing().executionTarget().name());
        assertEquals(A,selected.modelId());assertTrue(selected.routing().fallbackAllowed());
        assertEquals(List.of(B,LOCAL),selected.routing().allowedFallbackIds());
    }
    @Test void dedicatedSettingsExposeEffectiveOffWithoutErasingRawPreference() throws Exception {
        var strict=selection("FIXED",A,"GEMINI_WEBSEARCH_ONLY",true,List.of(B));
        var wire=JSON.valueToTree(strict);
        assertTrue(wire.path("routing").path("fallbackAllowed").asBoolean());
        assertTrue(wire.path("routing").has("effectiveFallbackAllowed"));
        assertFalse(wire.path("routing").path("effectiveFallbackAllowed").asBoolean());
        assertEquals(strict,JSON.treeToValue(wire,NovaFocusSettings.AnswerSelection.class));
        var general=selection("FIXED",A,"AUTO",true,List.of(B));
        assertTrue(JSON.valueToTree(general).path("routing").path("effectiveFallbackAllowed").asBoolean());
    }
    @Test void fallbackPreservesLegacyWebRequirementAndFrozenContext() throws Exception {
        try(var f=new Fixture()){
            f.response=r->{if(A.equals(r.getModel()))throw new ModelSelectionException("backend_timeout");return f.success(r);};
            var s=selection("FIXED",A,"API_ONLY",true,List.of(B));
            var memory=new NovaFocusHistoryService.Context(List.of(new NovaFocusHistoryService.Pair(1,"fixture-turn","COMPLETE","앞 질문","앞 답변")),"",List.of(),List.of(),s,42);
            f.adapter.answer(7L,"최신 환율을 검색하고 출처를 알려줘",memory,()->true);
            assertEquals(List.of(A,B),f.calls.stream().map(ChatRequestDto::getModel).toList());
            for(var r:f.calls){
                assertTrue(r.isStrictModelSelection());assertTrue(r.isUseWebSearch());
                assertEquals(com.example.lms.gptsearch.dto.SearchMode.AUTO,r.getSearchMode());
                assertTrue(r.getRetrievalRequestIntent().webSearch());assertEquals(3,r.getWebTopK());
            }
            assertSame(f.contexts.get(0),f.contexts.get(1));
            assertEquals("앞 답변",f.contexts.get(1).recent().get(0).answer());
            assertTrue(f.contexts.get(1).focusGoogleSearchAllowed());
            assertFalse(f.contexts.get(1).requireNativeGoogleSearch());
            assertEquals(42L,((Number)TraceStore.get("focus.selection.settingsVersion")).longValue());
        }
    }
    @Test void dedicatedModePinsEitherGeminiAndUsesOnlyNativePermissionEvenForGreetings() throws Exception {
        for(String id:List.of(A,B))try(var f=new Fixture()){
            f.geminiChoices();f.response=f::grounded;
            var advisor=mock(JevDecisionAdvisor.class);f.setIfPresent("jevAdvisor",advisor);
            assertEquals("원문",f.answer(selection("FIXED",id,"GEMINI_WEBSEARCH_ONLY",true,List.of(id.equals(A)?B:A,LOCAL))));
            assertEquals(List.of(id),f.calls.stream().map(ChatRequestDto::getModel).toList());
            var request=f.calls.get(0);assertTrue(request.isStrictModelSelection());assertFalse(request.isUseWebSearch());
            assertFalse(request.isUseRag());assertEquals(com.example.lms.gptsearch.dto.SearchMode.OFF,request.getSearchMode());
            assertFalse(request.getRetrievalRequestIntent().webSearch());assertEquals(0,request.getWebTopK());
            assertTrue(f.contexts.get(0).focusGoogleSearchAllowed());assertTrue(f.contexts.get(0).requireNativeGoogleSearch());
            verifyNoInteractions(advisor);assertEquals(0,TraceStore.get("focus.selection.fallbackCount"));
        }
    }
    @Test void dedicatedModeNeverFallsBackForAnyCategoricalProviderFailure() throws Exception {
        for(String reason:List.of("provider_unauthorized","quota_exceeded","rate_limited","backend_timeout","backend_unavailable","model_unavailable","protocol_unsupported"))try(var f=new Fixture()){
            f.geminiChoices();f.response=r->{throw new ModelSelectionException(reason);};
            var error=assertThrows(ModelSelectionException.class,()->f.answer(selection("FIXED",A,"GEMINI_WEBSEARCH_ONLY",true,List.of(B,LOCAL))));
            assertEquals(reason,error.code());assertEquals(List.of(A),f.calls.stream().map(ChatRequestDto::getModel).toList());
        }
    }
    @Test void dedicatedModeRejectsAutomaticNonGeminiUnknownAndMismatchedRoutesWithoutCalls() throws Exception {
        try(var f=new Fixture()){
            for(var s:List.of(selection("AUTO",null,"GEMINI_WEBSEARCH_ONLY",false,List.of()),
                    selection("FIXED",A,"GEMINI_WEBSEARCH_ONLY",false,List.of()),
                    selection("FIXED","llmrouter.absent","GEMINI_WEBSEARCH_ONLY",false,List.of())))
                assertThrows(IllegalStateException.class,()->f.answer(s));
            assertTrue(f.calls.isEmpty());f.geminiChoices();f.config.getModels().get("api-a").setName("different-model");
            assertThrows(IllegalStateException.class,()->f.answer(selection("FIXED",A,"GEMINI_WEBSEARCH_ONLY",false,List.of())));
            assertTrue(f.calls.isEmpty());
        }
    }
    @Test void explicitOffQuickImageAndCancelDominateDedicatedModeWithoutCalls() throws Exception {
        try(var f=new Fixture()){
            f.geminiChoices();var s=selection("FIXED",A,"GEMINI_WEBSEARCH_ONLY",true,List.of(B));
            for(int kind=0;kind<3;kind++){
                var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),s,2,400,kind==1,kind==0?false:true);
                final boolean image=kind==2;
                var error=assertThrows(IllegalStateException.class,()->f.adapter.answer(7L,"synthetic",image?"QUJD":null,image?"image/jpeg":null,memory,null,()->true));
                assertEquals(List.of("focus_search_off","focus_search_quick","focus_search_image_unsupported").get(kind),error.getMessage());
            }
            assertThrows(CancellationException.class,()->f.answer(s,()->false));assertTrue(f.calls.isEmpty());
        }
    }
    @Test void unobservedSearchAndIncompleteAttributionDoNotPublishOrRetry() throws Exception {
        for(boolean observed:List.of(false,true))try(var f=new Fixture()){
            f.geminiChoices();f.response=r->{
                if(!observed)return f.success(r);
                var metadata=new com.example.lms.learning.gemini.GeminiGateway.GroundingMetadata(List.of("query"),List.of(),List.of(),new com.example.lms.learning.gemini.GeminiGateway.SearchEntryPoint("<div>Suggestions</div>"));
                var g=new com.example.lms.learning.gemini.GeminiGateway.GroundedAnswer("원문",f.choices.get(r.getModel()).modelId(),metadata,List.of("원문"),true);
                return new ChatResult("원문",g.model(),false,Set.of(),List.of(),g);
            };
            var error=assertThrows(IllegalStateException.class,()->f.answer(selection("FIXED",A,"GEMINI_WEBSEARCH_ONLY",true,List.of(B))));
            assertEquals(observed?"focus_search_attribution_unavailable":"focus_search_not_observed",error.getMessage());assertEquals(1,f.calls.size());
        }
    }
    @Test void dedicatedResultRejectsDifferentModelOrLateCancellation() throws Exception {
        try(var f=new Fixture()){
            f.geminiChoices();f.response=r->f.grounded(r.toBuilder().model(B).build());
            assertEquals("focus_search_model_mismatch",assertThrows(IllegalStateException.class,()->f.answer(selection("FIXED",A,"GEMINI_WEBSEARCH_ONLY",true,List.of(B)))).getMessage());
        }
        try(var f=new Fixture()){
            f.geminiChoices();var current=new AtomicBoolean(true);f.response=r->{current.set(false);return f.grounded(r);};
            assertThrows(CancellationException.class,()->f.answer(selection("FIXED",A,"GEMINI_WEBSEARCH_ONLY",true,List.of(B)),current::get));assertEquals(1,f.calls.size());
        }
    }
    static NovaFocusSettings.AnswerSelection selection(String mode,String id,String target,boolean fallback,List<String> ids) throws Exception {
        var node=JSON.createObjectNode().put("mode",mode).put("modelId",id);
        var routing=node.putObject("routing").put("executionTarget",target).put("fallbackAllowed",fallback);
        routing.set("allowedFallbackIds",JSON.valueToTree(ids));
        return JSON.treeToValue(node,NovaFocusSettings.AnswerSelection.class);
    }
    @Test void apiOnlyRejectsFixedLocalBeforeAnyCall() throws Exception {
        try(var f=new Fixture()){
            assertThrows(ModelSelectionException.class,()->f.answer(selection("FIXED",LOCAL,"API_ONLY",false,List.of())));
            assertTrue(f.calls.isEmpty());
        }
    }
    @Test void localOnlyRejectsFixedApiBeforeAnyCall() throws Exception {
        try(var f=new Fixture()){
            assertThrows(ModelSelectionException.class,()->f.answer(selection("FIXED",A,"LOCAL_ONLY",false,List.of())));
            assertTrue(f.calls.isEmpty());
        }
    }
    @Test void fallbackIsStrictOrderedAndNeverCrossesApiOnlyBoundary() throws Exception {
        try(var f=new Fixture()){
            f.response=r->{if(A.equals(r.getModel()))throw new ModelSelectionException("backend_timeout");return f.success(r);};
            assertEquals("synthetic answer",assertDoesNotThrow(()->f.answer(selection("FIXED",A,"API_ONLY",true,List.of(LOCAL,B)))));
            assertEquals(List.of(A,B),f.calls.stream().map(ChatRequestDto::getModel).toList());
            assertTrue(f.calls.stream().allMatch(ChatRequestDto::isStrictModelSelection));
            assertEquals("backend_timeout",TraceStore.get("focus.selection.fallbackReason"));
            assertEquals(1,TraceStore.get("focus.selection.fallbackCount"));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={400,401,403,408,429,500,503,504})
    void generalApiHttpFailureUsesOnlyTheDeclaredBackupWhenPolicyAllowsIt(int status) throws Exception {
        String reason=switch(status){case 400->"model_request_invalid";case 401,403->"provider_unauthorized";case 408,504->"backend_timeout";case 429->"rate_limited";default->"backend_unavailable";};
        for(boolean fallback:List.of(false,true))try(var f=new Fixture()){
            var upstream=org.springframework.web.reactive.function.client.WebClientResponseException.create(status,"synthetic",org.springframework.http.HttpHeaders.EMPTY,new byte[0],java.nio.charset.StandardCharsets.UTF_8);
            var failure=ModelSelectionException.failure(upstream);assertEquals(reason,failure.code());
            f.response=r->{if(A.equals(r.getModel()))throw failure;return f.success(r);};
            var selected=selection("FIXED",A,"API_ONLY",fallback,List.of(A,B,B));
            if(fallback&&status!=400){
                assertEquals("synthetic answer",f.answer(selected));
                assertEquals(List.of(A,B),f.calls.stream().map(ChatRequestDto::getModel).toList());
                assertEquals(1,TraceStore.get("focus.selection.fallbackCount"));
            }else{
                assertSame(failure,assertThrows(ModelSelectionException.class,()->f.answer(selected)));
                assertEquals(List.of(A),f.calls.stream().map(ChatRequestDto::getModel).toList());
            }
            assertTrue(f.calls.stream().allMatch(ChatRequestDto::isStrictModelSelection));
        }
    }
    @Test void latePrimarySuccessAfterExactCancellationCannotPublishOrStartFallback() throws Exception {
        try(var f=new Fixture()){
            var published=new ArrayList<String>();
            f.response=r->{f.adapter.cancel(7L);return f.success(r);};
            var selected=selection("FIXED",A,"API_ONLY",true,List.of(B));
            var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),selected,2);
            assertThrows(CancellationException.class,()->f.adapter.answerResult(7L,"synthetic",null,null,memory,null,()->true,published::add));
            assertEquals(List.of(A),f.calls.stream().map(ChatRequestDto::getModel).toList());
            assertTrue(published.isEmpty());
        }
    }
    @Test void disabledFallbackDoesNotTryAnAllowedBackup() throws Exception {
        try(var f=new Fixture()){
            f.response=r->{throw new ModelSelectionException("backend_timeout");};
            assertThrows(ModelSelectionException.class,()->f.answer(selection("FIXED",A,"API_ONLY",false,List.of(B))));
            assertEquals(List.of(A),f.calls.stream().map(ChatRequestDto::getModel).toList());
        }
    }
    @Test void automaticApiChoiceUsesConfiguredWeightAndNeverLocalDefault() throws Exception {
        try(var f=new Fixture()){
            f.answer(selection("AUTO",null,"API_ONLY",false,List.of()));
            assertEquals(B,f.calls.get(0).getModel());assertTrue(f.calls.get(0).isStrictModelSelection());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"API_ONLY","AUTO"})
    void automaticChoiceSkipsNonselectableConfiguredDefaults(String target) throws Exception {
        try(var f=new Fixture()){
            f.setIfPresent("defaultModel",A);f.setIfPresent("focusDefaultModel",A);
            f.choices.put(A,new ChatModelCatalogService.Choice(A,"groq","api-a","fixture-api-a",
                "disabled",false,"provider_disabled","unknown","server_catalog"));
            f.answer(selection("AUTO",null,target,false,List.of()));
            assertEquals(List.of(B),f.calls.stream().map(ChatRequestDto::getModel).toList());
            assertTrue(f.calls.get(0).isStrictModelSelection());
            assertEquals(0,TraceStore.get("focus.selection.fallbackCount"));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"auth401","auth403","missing404","terminal","wrapped_terminal"})
    void nonretryableFailureCannotBecomeSameModelSearchOffRetry(String kind) throws Exception {
        try(var f=new Fixture()){
            var terminal=new com.example.lms.llm.gateway.LlmResponseTerminalException("content_filter",
                com.example.lms.llm.gateway.LlmFailureClass.PROVIDER_ERROR,"",null,"incomplete","content_filter",null);
            RuntimeException failure=switch(kind){
                case "auth401"->new dev.langchain4j.exception.HttpException(401,"synthetic_unauthorized");
                case "auth403"->new dev.langchain4j.exception.HttpException(403,"synthetic_forbidden");
                case "missing404"->new dev.langchain4j.exception.HttpException(404,"model not found");
                case "wrapped_terminal"->new RuntimeException("synthetic_wrapper",terminal);
                default->terminal;
            };
            f.response=r->{throw failure;};
            var context=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),selection("FIXED",A,"API_ONLY",false,List.of()),2);
            assertSame(failure,assertThrows(RuntimeException.class,()->f.adapter.answer(7L,"공식 자료를 찾아 확인해줘",context,()->true)));
            assertEquals(1,f.calls.size());assertTrue(f.calls.get(0).isUseWebSearch());
            assertNull(TraceStore.get("focus.search.parametricRetry"));
        }
    }
    @Test void retryableFailureRetainsBoundedSameModelSearchOffRecovery() throws Exception {
        try(var f=new Fixture()){
            f.response=r->{if(f.calls.size()==1)throw new dev.langchain4j.exception.HttpException(503,"synthetic_search_unavailable");return f.success(r);};
            var context=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),selection("FIXED",A,"API_ONLY",false,List.of()),2);
            assertEquals("synthetic answer",f.adapter.answer(7L,"공식 자료를 찾아 확인해줘",context,()->true));
            assertEquals(List.of(A,A),f.calls.stream().map(ChatRequestDto::getModel).toList());
            assertTrue(f.calls.get(0).isUseWebSearch());assertFalse(f.calls.get(1).isUseWebSearch());
            assertEquals(com.example.lms.gptsearch.dto.SearchMode.OFF,f.calls.get(1).getSearchMode());
            assertEquals(Boolean.TRUE,TraceStore.get("focus.search.parametricRetry"));
        }
    }
    @Test void cancellationAfterFirstFailureStopsTheFallbackChain() throws Exception {
        try(var f=new Fixture()){
            var current=new AtomicBoolean(true);
            f.response=r->{current.set(false);throw new ModelSelectionException("backend_timeout");};
            assertThrows(CancellationException.class,()->f.answer(selection("FIXED",A,"API_ONLY",true,List.of(B)),current::get));
            assertEquals(List.of(A),f.calls.stream().map(ChatRequestDto::getModel).toList());
        }
    }
    @Test void admissionOrValidationFailureIsNotAProviderFallbackSignal() throws Exception {
        try(var f=new Fixture()){
            var failure=new IllegalArgumentException("synthetic_validation");
            f.response=r->{throw failure;};
            assertSame(failure,assertThrows(IllegalArgumentException.class,()->f.answer(selection("FIXED",A,"API_ONLY",true,List.of(B)))));
            assertEquals(List.of(A),f.calls.stream().map(ChatRequestDto::getModel).toList());
        }
    }
    @Test void unknownConfiguredProviderCannotProveApiOnly() throws Exception {
        try(var f=new Fixture()){
            f.config.getModels().get("api-a").setProvider("");
            assertThrows(ModelSelectionException.class,()->f.answer(selection("FIXED",A,"API_ONLY",false,List.of())));
            assertTrue(f.calls.isEmpty());
        }
    }
    @Test void fallbackListIsBoundedAtSettingsBoundary() {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->
            selection("FIXED",A,"API_ONLY",true,List.of("one","two","three","four")));
    }
    @Test void automaticLocalChoicePinsTheConfiguredLocalDefault() throws Exception {
        try(var f=new Fixture()){
            f.answer(selection("AUTO",null,"LOCAL_ONLY",false,List.of()));
            assertEquals(LOCAL,f.calls.get(0).getModel());assertTrue(f.calls.get(0).isStrictModelSelection());
        }
    }
    @Test void automaticApiChoiceDoesNotPromoteFallbackOnlyRoutes() throws Exception {
        try(var f=new Fixture()){
            f.config.getModels().get("api-b").setFallbackOnly(true);
            f.answer(selection("AUTO",null,"API_ONLY",false,List.of()));
            assertEquals(A,f.calls.get(0).getModel());
        }
    }
    @Test void configuredLocalRouteCannotBecomeApiBecauseCatalogLabelSaysCloud() throws Exception {
        try(var f=new Fixture()){
            f.config.getModels().get("api-a").setProvider("ollama");
            assertThrows(ModelSelectionException.class,()->f.answer(selection("FIXED",A,"API_ONLY",false,List.of())));
            assertTrue(f.calls.isEmpty());
        }
    }
    @Test void imageTextRetryRetainsTheSuccessfulFallbackCandidate() throws Exception {
        try(var f=new Fixture()){
            f.response=r->{
                if(A.equals(r.getModel()))throw new ModelSelectionException("protocol_unsupported");
                if(r.getImageBase64()!=null)return ChatResult.of("vision unavailable","vision:unavailable",false);
                return f.success(r);
            };
            var s=selection("FIXED",A,"API_ONLY",true,List.of(B));
            var context=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),s,2);
            var answer=f.adapter.answer(7L,"이거 뭐야?","QUJD","image/jpeg",context,null,()->true);
            assertTrue(answer.contains("synthetic answer"));
            assertEquals(List.of(A,B,B),f.calls.stream().map(ChatRequestDto::getModel).toList());
            assertTrue(f.calls.stream().allMatch(ChatRequestDto::isStrictModelSelection));
            assertNotNull(f.calls.get(1).getImageBase64());assertNull(f.calls.get(2).getImageBase64());
            assertEquals(1,TraceStore.get("focus.selection.fallbackCount"));
        }
    }
    @Test void resultPreservesActualFallbackFailureAndNormalAutoIsNotFallback() throws Exception {
        for(boolean fallback:List.of(true,false))try(var f=new Fixture()){
            f.geminiChoices();f.setIfPresent("focusDefaultModel",A);
            f.response=r->{if(fallback&&A.equals(r.getModel()))throw new ModelSelectionException("backend_timeout");return f.success(r);};
            var selected=selection("AUTO",null,"API_ONLY",true,List.of(B));
            var context=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),selected,2);
            var result=f.adapter.answerResult(7L,"Why are leaves green?",null,null,context,null,()->true);
            var meta=JSON.valueToTree(result).path("modelOutcome");
            assertEquals(fallback,meta.path("isFallback").asBoolean());
            assertEquals(A,meta.path("requestedModel").asText());
            assertEquals(fallback?"gemini-3.5-flash-lite":"gemini-3.8-flash",meta.path("effectiveModel").asText());
            assertEquals(fallback?"backend_timeout":"none",meta.path("reasonCode").asText());
            if(fallback)assertEquals("ModelSelectionException",meta.path("originalError").asText());
            assertEquals(fallback?List.of(A,B):List.of(A),f.calls.stream().map(ChatRequestDto::getModel).toList());
        }
    }
    static final class Fixture implements AutoCloseable {
        final ChatService chat=mock(ChatService.class);
        final ChatModelCatalogService catalog=mock(ChatModelCatalogService.class);
        final LlmRouterProperties config=new LlmRouterProperties();
        final ChatRunRegistry runs=new ChatRunRegistry();
        final NovaFocusAnswerService adapter;
        final List<ChatRequestDto> calls=new ArrayList<>();
        final List<ChatConversationContext> contexts=new ArrayList<>();
        final Map<String,ChatModelCatalogService.Choice> choices=new LinkedHashMap<>();
        Function<ChatRequestDto,ChatResult> response=this::success;
        Fixture(){
            TraceStore.clear();
            choices.put(LOCAL,new ChatModelCatalogService.Choice(LOCAL,"Ollama","local-default",LOCAL,"installed",true,"","unknown","installed"));
            choices.put(A,new ChatModelCatalogService.Choice(A,"groq","api-a","fixture-api-a","configured",true,"","unknown","server_catalog"));
            choices.put(B,new ChatModelCatalogService.Choice(B,"openrouter","api-b","fixture-api-b","configured",true,"","unknown","server_catalog"));
            when(catalog.choices()).thenAnswer(c->List.copyOf(choices.values()));
            when(catalog.resolve(anyString())).thenAnswer(c->Optional.ofNullable(choices.get(c.getArgument(0))));
            when(catalog.failureCode(any())).thenReturn("model_unavailable");when(catalog.failureCode(isNull())).thenReturn("model_unavailable");
            var a=new LlmRouterProperties.ModelConfig();a.setProvider("groq");a.setName("fixture-api-a");a.setWeight(1);
            var b=new LlmRouterProperties.ModelConfig();b.setProvider("openrouter");b.setName("fixture-api-b");b.setWeight(2);
            config.setModels(Map.of("api-a",a,"api-b",b));
            when(chat.continueChat(any(),isNull(),any())).thenAnswer(c->{ChatRequestDto r=c.getArgument(0);calls.add(r);contexts.add(c.getArgument(2));return response.apply(r);});
            ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
            adapter=new NovaFocusAnswerService(chat,mock(PublicRequestBudgetGuard.class),runs);
            setIfPresent("modelCatalog",catalog);setIfPresent("routerConfig",config);setIfPresent("defaultModel",LOCAL);
        }
        void setIfPresent(String name,Object value){if(ReflectionUtils.findField(NovaFocusAnswerService.class,name)!=null)ReflectionTestUtils.setField(adapter,name,value);}
        ChatResult success(ChatRequestDto r){var c=choices.get(r.getModel());return ChatResult.of("synthetic answer",c==null?"not_observed":c.modelId(),false);}
        void geminiChoices(){for(String id:List.of(A,B)){
            var cfg=config.getModels().get(id.substring("llmrouter.".length()));cfg.setProvider("gemini");cfg.setName(id.equals(A)?"gemini-3.8-flash":"gemini-3.5-flash-lite");
            choices.put(id,new ChatModelCatalogService.Choice(id,"gemini",id.substring("llmrouter.".length()),cfg.getName(),"configured",true,"","unknown","server_catalog",false,List.of(),List.of(),true,false,Map.of("googleSearchSupported",true)));
        }}
        ChatResult grounded(ChatRequestDto r){
            var g=new com.example.lms.learning.gemini.GeminiGateway.GroundedAnswer("원문",choices.get(r.getModel()).modelId(),
                new com.example.lms.learning.gemini.GeminiGateway.GroundingMetadata(List.of("query"),
                    List.of(new com.example.lms.learning.gemini.GeminiGateway.GroundingChunk(new com.example.lms.learning.gemini.GeminiGateway.WebSource("https://example.com/source","Source"))),
                    List.of(new com.example.lms.learning.gemini.GeminiGateway.GroundingSupport(new com.example.lms.learning.gemini.GeminiGateway.Segment(0,0,6,"원문"),List.of(0),List.of())),
                    new com.example.lms.learning.gemini.GeminiGateway.SearchEntryPoint("<div>Suggestions</div>")),List.of("원문"),true);
            return new ChatResult("원문",g.model(),false,Set.of(),List.of(),g);
        }
        String answer(NovaFocusSettings.AnswerSelection selection){return answer(selection,()->true);}
        String answer(NovaFocusSettings.AnswerSelection selection,BooleanSupplier current){
            return adapter.answer(7L,"안녕?",new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),selection,2),current);
        }
        public void close(){ReflectionTestUtils.invokeMethod(runs,"shutdown");TraceStore.clear();}
    }
}
