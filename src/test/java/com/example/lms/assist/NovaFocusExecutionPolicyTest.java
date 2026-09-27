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
    static final class Fixture implements AutoCloseable {
        final ChatService chat=mock(ChatService.class);
        final ChatModelCatalogService catalog=mock(ChatModelCatalogService.class);
        final LlmRouterProperties config=new LlmRouterProperties();
        final ChatRunRegistry runs=new ChatRunRegistry();
        final NovaFocusAnswerService adapter;
        final List<ChatRequestDto> calls=new ArrayList<>();
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
            when(chat.continueChat(any(),isNull(),any())).thenAnswer(c->{ChatRequestDto r=c.getArgument(0);calls.add(r);return response.apply(r);});
            ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
            adapter=new NovaFocusAnswerService(chat,mock(PublicRequestBudgetGuard.class),runs);
            setIfPresent("modelCatalog",catalog);setIfPresent("routerConfig",config);setIfPresent("defaultModel",LOCAL);
        }
        void setIfPresent(String name,Object value){if(ReflectionUtils.findField(NovaFocusAnswerService.class,name)!=null)ReflectionTestUtils.setField(adapter,name,value);}
        ChatResult success(ChatRequestDto r){var c=choices.get(r.getModel());return ChatResult.of("synthetic answer",c==null?"not_observed":c.modelId(),false);}
        String answer(NovaFocusSettings.AnswerSelection selection){return answer(selection,()->true);}
        String answer(NovaFocusSettings.AnswerSelection selection,BooleanSupplier current){
            return adapter.answer(7L,"안녕?",new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),selection,2),current);
        }
        public void close(){ReflectionTestUtils.invokeMethod(runs,"shutdown");TraceStore.clear();}
    }
}
