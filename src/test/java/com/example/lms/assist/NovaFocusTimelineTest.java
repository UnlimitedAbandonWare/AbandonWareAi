package com.example.lms.assist;

import com.example.lms.api.PublicRequestBudgetGuard;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.ModelSelectionException;
import com.example.lms.search.TraceStore;
import com.example.lms.service.*;
import com.example.lms.service.chat.ChatRunRegistry;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusTimelineTest {
    @Test void controlledLoopbackReceiptSurvivesTheFocusBoundaryWithoutPayloads() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/chat",exchange->{
            byte[] request=exchange.getRequestBody().readAllBytes();
            byte[] response="{\"message\":{\"role\":\"assistant\",\"content\":\"synthetic receipt answer\"},\"done\":true,\"done_reason\":\"stop\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var headers=exchange.getResponseHeaders();
            headers.add("Content-Type","application/json; charset=utf-8");
            headers.add("X-AWX-Controlled-Provider-Receipt","v1;source=controlled_http_server");
            headers.add("X-AWX-Provider-Request-SHA256",sha256(request));
            headers.add("X-AWX-Provider-Request-Bytes",Integer.toString(request.length));
            headers.add("X-AWX-Provider-Response-SHA256",sha256(response));
            headers.add("X-AWX-Provider-Response-Bytes",Integer.toString(response.length));
            exchange.sendResponseHeaders(200,response.length);
            try(var body=exchange.getResponseBody()){body.write(response);}
        });
        server.start();
        try(var f=new Fixture()){
            f.nativeBase="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            assertEquals("synthetic receipt answer",f.answer());
            var rows=attempts();assertEquals(1,rows.size());var row=rows.get(0);
            for(String field:List.of("clientHttpExchangeObserved","clientHttpResponseObserved","providerReceiptObserved","providerAttemptObserved","wireAttemptObserved"))
                assertEquals(true,row.get(field),field);
            assertEquals("provider_receive",row.get("evidenceBoundary"));
            assertEquals("provider_receive",TraceStore.get("focus.request.evidenceBoundary"));
            for(String field:List.of("timelineId","requestIdHash","sessionIdHash","promptHash","responseHash","requestBodyHash","responseBodyHash","optionsHash","endpointLabel"))
                assertFalse(row.containsKey(field),field);
            String trace=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(TraceStore.context());
            for(String privateValue:List.of("synthetic receipt answer","private question",f.nativeBase,f.timeline.get()))
                assertFalse(trace.contains(privateValue),privateValue);
        }finally{server.stop(0);}
    }
    private static String sha256(byte[] value){
        try{return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
        catch(java.security.NoSuchAlgorithmException failure){throw new AssertionError(failure);}
    }
    @Test void unavailableObservabilityNeverReplacesASuccessfulAnswer(){
        try(var f=new Fixture()){
            var unavailable=mock(ModelRuntimeHealthTracker.class);
            when(unavailable.beginRequestTimeline(anyString(),anyString())).thenThrow(new IllegalStateException("synthetic unavailable"));
            ReflectionTestUtils.setField(f.adapter,"modelHealth",unavailable);
            assertEquals("synthetic answer",f.answer());assertTrue(attempts().isEmpty());
            assertNull(TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY));
            var unavailableRead=spy(f.tracker);
            doThrow(new IllegalStateException("synthetic ledger unavailable")).when(unavailableRead).redactedRequestAttemptLedger(anyString());
            ReflectionTestUtils.setField(f.adapter,"modelHealth",unavailableRead);
            assertEquals("synthetic answer",f.answer());assertTrue(attempts().isEmpty());
            assertEquals("success",TraceStore.get("focus.request.terminalClass"));
        }
    }
    @Test void adapterResponseHasBoundedEvidenceWithoutClaimingProviderDelivery() throws Exception {
        try(var f=new Fixture()){
            TraceStore.put("unsafe.synthetic","must-not-survive");
            assertEquals("synthetic answer",f.answer());
            assertNotNull(f.timeline.get(),"Focus must bind an existing tracker timeline");
            var rows=attempts();
            assertEquals(1,rows.size());var row=rows.get(0);
            assertEquals(true,row.get("modelAdapterAttemptObserved"));
            assertEquals(true,row.get("responseObserved"));
            assertEquals(false,row.get("providerAttemptObserved"));
            assertEquals(false,row.get("wireAttemptObserved"));
            assertEquals("model_adapter",row.get("evidenceBoundary"));
            assertFalse(row.containsKey("timelineId"));assertFalse(row.containsKey("promptHash"));
            assertFalse(row.containsKey("responseHash"));assertFalse(row.containsKey("endpointLabel"));
            assertNull(TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY));
            assertNull(TraceStore.get("unsafe.synthetic"));
            String rendered=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rows);
            assertFalse(rendered.contains("synthetic answer"));assertFalse(rendered.contains("private question"));
            String first=f.timeline.get();f.answer();assertNotEquals(first,f.timeline.get());
            assertEquals(1,attempts().size(),"worker reuse cannot inherit old attempts");
        }
    }
    @Test void routeSelectionAloneLeavesAttemptEvidenceEmpty() {
        try(var f=new Fixture()){
            f.selectionOnly=true;f.answer();
            assertNotNull(f.timeline.get());
            assertTrue(attempts().isEmpty(),"selected route is not a completed transport");
            assertEquals("not_observed",TraceStore.get("focus.request.evidenceBoundary"));
        }
    }
    @Test void failureRecordsTerminalAndDropsTheInternalBinding() {
        try(var f=new Fixture()){
            f.fail=true;
            assertThrows(ModelSelectionException.class,f::answer);
            assertNotNull(f.timeline.get());
            var rows=f.tracker.redactedRequestTimeline(f.timeline.get());
            assertTrue(rows.stream().anyMatch(r->"terminal".equals(r.get("phase"))&&"timeout".equals(r.get("terminalClass"))));
            assertNull(TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY));
            assertEquals("timeout",TraceStore.get("focus.request.terminalClass"));
        }
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> attempts(){
        Object value=TraceStore.get("focus.request.attempts");
        assertInstanceOf(List.class,value);return (List<Map<String,Object>>)value;
    }
    static final class Fixture implements AutoCloseable {
        final ModelRuntimeHealthTracker tracker=new ModelRuntimeHealthTracker();
        final ChatRunRegistry runs=new ChatRunRegistry();
        final NovaFocusAnswerService adapter;
        final AtomicReference<String> timeline=new AtomicReference<>();
        boolean selectionOnly,fail;
        String nativeBase;
        Fixture(){
            var chat=mock(ChatService.class);
            when(chat.continueChat(any(),isNull(),any())).thenAnswer(c->{
                timeline.set((String)TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY));
                if(fail)throw new ModelSelectionException("backend_timeout");
                if(nativeBase!=null){
                    var model=new com.example.lms.llm.OllamaNativeChatModel(nativeBase,"fixture-model",java.time.Duration.ofSeconds(5),32,0.0d,null,tracker,false);
                    var response=model.chat(List.of(UserMessage.from("private question")));
                    return ChatResult.of(response.aiMessage().text(),"fixture-model",false);
                }
                if(selectionOnly){
                    if(timeline.get()!=null)tracker.recordRequestSelection(timeline.get(),"router","ollama","fixture-route","fixture-model","localhost","ollama_native",false,false);
                }else{
                    ChatModel delegate=new ChatModel(){@Override public ChatResponse chat(List<ChatMessage> messages){
                        return ChatResponse.builder().aiMessage(AiMessage.from("synthetic answer")).build();
                    }};
                    var route=tracker.redactedRequestAttemptRoute("fixture-route","fixture-model","localhost","ollama_native");
                    tracker.decorateRequestAttempt(delegate,"primary",route,Map.of("maxTokens",32))
                        .chat(List.of(UserMessage.from("private question")));
                }
                return ChatResult.of("synthetic answer","fixture-model",false);
            });
            ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
            adapter=new NovaFocusAnswerService(chat,mock(PublicRequestBudgetGuard.class),runs);
            if(ReflectionUtils.findField(NovaFocusAnswerService.class,"modelHealth")!=null)
                ReflectionTestUtils.setField(adapter,"modelHealth",tracker);
        }
        String answer(){return adapter.answer(7L,"안녕?",new NovaFocusHistoryService.Context(List.of(),"",List.of()));}
        public void close(){ReflectionTestUtils.invokeMethod(runs,"shutdown");TraceStore.clear();}
    }
}
