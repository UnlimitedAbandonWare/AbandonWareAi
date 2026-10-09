package com.example.lms.assist;

import com.example.lms.api.PublicChatAdmissionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusServiceTest {
    private static java.util.List<java.util.Map<String,Object>> observe(NovaFocusService service,
            com.example.lms.debug.DebugEventStore sink) {
        var rows=new java.util.concurrent.CopyOnWriteArrayList<java.util.Map<String,Object>>();
        doAnswer(call->{rows.add(new java.util.LinkedHashMap<>(call.getArgument(5)));return null;})
            .when(sink).emit(any(),any(),anyString(),anyString(),anyString(),anyMap(),isNull());
        if(org.springframework.util.ReflectionUtils.findField(NovaFocusService.class,"debugEvents")!=null)
            org.springframework.test.util.ReflectionTestUtils.setField(service,"debugEvents",sink);
        return rows;
    }
    @Test void focusBoundariesJoinOneRequestWithoutTextOrDuplicateReceipts() throws Exception {
        try(var f=new EpochFixture()){
            var rows=observe(f.service,mock(com.example.lms.debug.DebugEventStore.class));
            doReturn(new NovaFocusAnswer.Result("private-answer-fixture",null)).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            f.ask(1,"private-question-fixture");f.awaitAnswer(1);
            var v=f.service.view(f.owner,"assist",1);
            f.presented(1);
            for(String stage:List.of("first_visible","presentation_done"))
                assertTrue(f.service.rendered(new NovaFocusService.Receipt(v.serverInstanceId(),v.activationId(),
                    v.turnId(),v.answerVersion(),v.renderReceiptTicket(),stage)));
            assertFalse(f.service.rendered(new NovaFocusService.Receipt(v.serverInstanceId(),v.activationId(),
                v.turnId(),v.answerVersion()+1,v.renderReceiptTicket(),"first_visible")));
            awaitRows(rows,"focus_presentation_done");
            assertEquals(List.of("focus_question_confirmed","focus_generation_started","focus_model_attempt","focus_terminal",
                "focus_first_visible","focus_presentation_done"),rows.stream().map(r->r.get("stage")).toList());
            for(String key:List.of("ownerHash","sessionHash","activationHash","requestHash","serverInstanceHash")){
                assertEquals(1,rows.stream().map(r->r.get(key)).distinct().count(),key);
                assertTrue(((String)rows.get(0).get(key)).matches("hash:[0-9a-f]{12}"),key);
            }
            assertEquals(1L,rows.get(2).get("epoch"));
            assertNotNull(rows.get(2).get("latencyMs"));
            assertNull(rows.get(0).get("deviceHash"));
            assertNull(rows.get(0).get("connectionGeneration"));
            assertEquals("dom_callback_only",rows.get(4).get("evidenceBoundary"));
            assertEquals(false,rows.get(4).get("hardwareRenderedObserved"));
            String packet=rows.toString();
            for(String value:List.of("private-question-fixture","private-answer-fixture",v.renderReceiptTicket(),f.owner))
                assertFalse(packet.contains(value));
        }
    }
    @Test void blockedDiagnosticSinkDoesNotHoldCaptureStateAndKeepsOriginalEpoch() throws Exception {
        try(var f=new EpochFixture()){
            var sink=mock(com.example.lms.debug.DebugEventStore.class);var rows=observe(f.service,sink);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{
                java.util.Map<String,Object> row=call.getArgument(5);rows.add(new java.util.LinkedHashMap<>(row));
                if("focus_terminal".equals(row.get("stage"))){entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));}
                return null;
            }).when(sink).emit(any(),any(),anyString(),anyString(),anyString(),anyMap(),isNull());
            doReturn(new NovaFocusAnswer.Result("answer",null)).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            try{
                f.ask(1,"question");assertTrue(entered.await(3,TimeUnit.SECONDS));
                var capture=CompletableFuture.runAsync(()->{
                    f.service.attach(f.owner,"live","assist",2);
                    f.service.audio(f.owner,"assist",2,new ConversateQuestionPolicy.Utterance("next","next",1,true,"short"));
                    assertNotNull(f.service.view(f.owner,"assist",2));
                });
                capture.get(1,TimeUnit.SECONDS);
                release.countDown();f.awaitAnswer(2);f.presented(2);awaitRows(rows,"focus_first_visible");
                var render=rows.stream().filter(r->"focus_first_visible".equals(r.get("stage"))).findFirst().orElseThrow();
                assertEquals(1L,render.get("epoch"));assertEquals(2L,render.get("currentEpoch"));
            }finally{release.countDown();}
        }
    }
    @Test void failedDiagnosticSinkCannotChangeAnswerReceiptOrFollowup() throws Exception {
        try(var f=new EpochFixture()){
            var sink=mock(com.example.lms.debug.DebugEventStore.class);observe(f.service,sink);
            doThrow(new IllegalStateException("synthetic_sink_failed"))
                .when(sink).emit(any(),any(),anyString(),anyString(),anyString(),anyMap(),isNull());
            doReturn(new NovaFocusAnswer.Result("answer",null)).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            f.ask(1,"one");f.awaitAnswer(1);f.presented(1);
            assertEquals("PRESENTING",f.service.view(f.owner,"assist",1).phase());
            f.ask(1,"two");f.awaitAnswer(1);assertEquals("answer",f.service.view(f.owner,"assist",1).answerText());
            verify(f.history,times(2)).terminal(any(),any(),any(),eq("COMPLETED"),any());
        }
    }
    private static void awaitRows(List<Map<String,Object>> rows,String stage) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<until){if(rows.stream().anyMatch(r->stage.equals(r.get("stage"))))return;Thread.sleep(5);}
        fail("diagnostic stage missing: "+stage);
    }
    @Test void slowRenderSinkReleasesTheHttpControllerMonitor() throws Exception {
        try(var f=new EpochFixture()){
            var sink=mock(com.example.lms.debug.DebugEventStore.class);observe(f.service,sink);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{if("focus_first_visible".equals(((Map<?,?>)call.getArgument(5)).get("stage"))){
                entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));}return null;})
                .when(sink).emit(any(),any(),anyString(),anyString(),anyString(),anyMap(),isNull());
            doReturn(new NovaFocusAnswer.Result("answer",null)).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            f.ask(1,"short");f.awaitAnswer(1);var v=f.service.view(f.owner,"assist",1);
            var controller=new DisplayConversateController(mock(ConversateSessionService.class),
                mock(com.example.lms.web.ClientOwnerKeyResolver.class),mock(InterviewDemoPublicAddress.class));
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"novaFocus",f.service);
            var http=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/conversate/display/focus/rendered");
            http.setScheme("http");http.setServerName("127.0.0.1");http.setServerPort(18180);http.setRemoteAddr("127.0.0.1");
            http.addHeader("X-Display-Client","1");http.addHeader("Origin","http://127.0.0.1:18180");
            try{
                CompletableFuture.runAsync(()->assertEquals(200,controller.focusRendered(new NovaFocusService.Receipt(
                    v.serverInstanceId(),v.activationId(),v.turnId(),v.answerVersion(),v.renderReceiptTicket(),"first_visible"),http).getStatusCode().value())).get(1,TimeUnit.SECONDS);
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                CompletableFuture.runAsync(()->{synchronized(controller){assertNotNull(f.service.view(f.owner,"assist",1));}}).get(1,TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
    }
    @Test void generatedAnswerAndUnconfirmedPresentationHaveSeparateOutcomes() throws Exception {
        try(var f=new EpochFixture()){
            var rows=observe(f.service,mock(com.example.lms.debug.DebugEventStore.class));
            doReturn(new NovaFocusAnswer.Result("answer",null)).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            f.ask(1,"short");f.awaitAnswer(1);f.time.now+=180000;f.service.maintain();
            awaitRows(rows,"focus_lifecycle_closed");
            assertEquals("success",rows.stream().filter(r->"focus_terminal".equals(r.get("stage"))).findFirst().orElseThrow().get("outcome"));
            var closed=rows.stream().filter(r->"focus_lifecycle_closed".equals(r.get("stage"))).findFirst().orElseThrow();
            assertEquals("presentation_unconfirmed",closed.get("reasonCode"));assertEquals(false,closed.get("hardwareRenderedObserved"));
        }
    }
    @Test void overflowAndShutdownNeverWaitForTheDiagnosticSink() throws Exception {
        try(var f=new EpochFixture()){
            var sink=mock(com.example.lms.debug.DebugEventStore.class);observe(f.service,sink);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;})
                .when(sink).emit(any(),any(),anyString(),anyString(),anyString(),anyMap(),isNull());
            doReturn(new NovaFocusAnswer.Result("answer",null)).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            try{
                for(int i=0;i<20;i++){
                    f.ask(1,"short-"+i);f.awaitAnswer(1);f.presented(1);
                    f.service.close(f.owner,"assist",1,"user_closed");f.service.open(f.owner,"assist",1,"fold");
                }
                assertTrue(entered.await(1,TimeUnit.SECONDS));
                assertTrue(((Number)f.service.diagnostics(f.owner,"assist",1).get("diagnosticDropped")).longValue()>0);
                CompletableFuture.runAsync(()->{
                    f.service.audio(f.owner,"assist",1,new ConversateQuestionPolicy.Utterance("fresh","fresh",1,true,"short"));
                    assertNotNull(f.service.view(f.owner,"assist",1));
                }).get(1,TimeUnit.SECONDS);
                CompletableFuture.runAsync(f.service::close).get(1,TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"backend_timeout","backend_unavailable"})
    void syntheticApiFailureRetainsItsCauseAndObservedElapsed(String code) throws Exception {
        try(var f=new EpochFixture()){
            var rows=observe(f.service,mock(com.example.lms.debug.DebugEventStore.class));
            doThrow(new com.example.lms.llm.ModelSelectionException(code)).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            f.ask(1,"short");awaitRows(rows,"focus_terminal");
            var terminal=rows.stream().filter(r->"focus_terminal".equals(r.get("stage"))).findFirst().orElseThrow();
            assertEquals(code,terminal.get("reasonCode"));assertEquals(code.equals("backend_timeout")?"timeout":"error",terminal.get("outcome"));
            assertTrue(((Number)terminal.get("latencyMs")).longValue()>=0);assertNull(terminal.get("renderLatencyMs"));
        }
    }
    @Test void firstAttemptRuntimeFailureRetriesAndDeliversTheAnswer() throws Exception {
        try(var f=new EpochFixture()){
            var rows=observe(f.service,mock(com.example.lms.debug.DebugEventStore.class));
            f.service.close(f.owner,"assist",1,"user_closed");f.service.open(f.owner,"assist",1,"fold");
            doThrow(new RuntimeException(new java.util.concurrent.TimeoutException("timed out")))
                .doReturn(new NovaFocusAnswer.Result("recovered-answer",null))
                .when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            f.ask(1,"short");f.awaitAnswer(1);
            assertEquals("recovered-answer",f.service.view(f.owner,"assist",1).answerText());
            assertTrue(f.service.view(f.owner,"assist",1).active());
            verify(f.answer,times(2)).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            awaitRows(rows,"focus_terminal");
            assertEquals(List.of("focus_started","focus_question_confirmed","focus_generation_started",
                "focus_model_attempt","focus_model_attempt","focus_model_attempt","focus_terminal"),rows.stream().map(r->r.get("stage")).toList());
            assertEquals("attempted",rows.get(3).get("outcome"));assertEquals(1,rows.get(3).get("attempt"));
            assertEquals("failed",rows.get(4).get("outcome"));assertEquals(1,rows.get(4).get("attempt"));
            assertEquals("llm_timeout",rows.get(4).get("reasonCode"));assertEquals("RuntimeException",rows.get(4).get("exceptionClass"));
            assertEquals("attempted",rows.get(5).get("outcome"));assertEquals(2,rows.get(5).get("attempt"));
            assertEquals("success",rows.get(6).get("outcome"));
        }
    }
    @Test void allAttemptsFailingClosesWithUnavailableAndKeepsTheRealReasonCode() throws Exception {
        try(var f=new EpochFixture()){
            var rows=observe(f.service,mock(com.example.lms.debug.DebugEventStore.class));
            doThrow(new IllegalStateException("synthetic_provider_failed")).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            f.ask(1,"short");awaitRows(rows,"focus_terminal");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(Boolean.TRUE.equals(f.service.diagnostics(f.owner,"assist",1).get("busy"))&&System.nanoTime()<deadline)Thread.sleep(5);
            verify(f.answer,times(2)).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            var terminal=rows.stream().filter(r->"focus_terminal".equals(r.get("stage"))).findFirst().orElseThrow();
            assertEquals("llm_failed_unknown",terminal.get("reasonCode"));assertEquals("error",terminal.get("outcome"));
            assertEquals("IllegalStateException",terminal.get("exceptionClass"));assertEquals("IllegalStateException",terminal.get("exceptionRootClass"));
            var view=f.service.view(f.owner,"assist",1);
            assertFalse(view.active());assertEquals("focus_answer_unavailable",view.reason());assertEquals("",view.answerText());
        }
    }
    @Test void deterministicFocusFailuresDoNotSpendASecondAttempt() throws Exception {
        try(var f=new EpochFixture()){
            doThrow(new com.example.lms.llm.ModelSelectionException("model_unavailable")).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            f.ask(1,"short");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(Boolean.TRUE.equals(f.service.diagnostics(f.owner,"assist",1).get("busy"))&&System.nanoTime()<deadline)Thread.sleep(5);
            verify(f.answer,times(1)).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            assertEquals("focus_answer_unavailable",f.service.view(f.owner,"assist",1).reason());
        }
    }
    private static NovaFocusAnswer typedAdapter(){
        var adapter=mock(NovaFocusAnswer.class);
        doCallRealMethod().when(adapter).answerResult(any(),any(),any(),any(),any(),any(),any());
        doCallRealMethod().when(adapter).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
        return adapter;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"completed","failed","changed","closed"})
    void earlySafeSentenceReachesLensButCannotPersistFailedRevision(String outcome) throws Exception {
        try(var f=new EpochFixture()){
            var first=new CountDownLatch(1);var finish=new CountDownLatch(1);
            doAnswer(call->{
                java.util.function.Consumer<String> partial=call.getArgument(7);
                partial.accept("첫 문장입니다.");first.countDown();assertTrue(finish.await(3,TimeUnit.SECONDS));
                if(outcome.equals("failed"))throw new IllegalStateException("synthetic_provider_failed");
                if(outcome.equals("closed"))partial.accept("첫 문장입니다. 늦은 문장입니다.");
                return new NovaFocusAnswer.Result(outcome.equals("changed")?"최종 본문이 달라졌습니다.":"첫 문장입니다. 최종 문장입니다.",null);
            }).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            try{
                f.ask(1,"early");assertTrue(first.await(3,TimeUnit.SECONDS));
                var early=f.service.view(f.owner,"assist",1);
                assertEquals("THINKING",early.phase());assertEquals("첫 문장입니다.",early.forTarget("fold").answerText());
                assertEquals("첫 문장입니다.",early.forTarget("lens").answerText());
                assertFalse(early.answerComplete());assertTrue(early.answerPrefixStable());
                verify(f.history,never()).terminal(any(),any(),any(),eq("COMPLETED"),any());
                if(outcome.equals("closed"))f.service.close(f.owner,"assist",1,"user_closed");
                finish.countDown();
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(Boolean.TRUE.equals(f.service.diagnostics(f.owner,"assist",1).get("busy"))&&System.nanoTime()<until)Thread.sleep(5);
                assertFalse(Boolean.TRUE.equals(f.service.diagnostics(f.owner,"assist",1).get("busy")));
                if(outcome.equals("completed")){
                    assertEquals(early.answerVersion(),f.service.view(f.owner,"assist",1).answerVersion());
                    verify(f.history).terminal(any(),any(),any(),eq("COMPLETED"),eq("첫 문장입니다. 최종 문장입니다."));
                }else{
                    assertEquals("",f.service.view(f.owner,"assist",1).forTarget("fold").answerText());
                    assertEquals("",f.service.view(f.owner,"assist",1).forTarget("lens").answerText());
                    verify(f.history,never()).terminal(any(),any(),any(),eq("COMPLETED"),any());
                }
            }finally{finish.countDown();}
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,true","true,true","false,false","true,false"})
    void settingsSavePreservesAcceptedSnapshotWhenMemoryScopeIsCurrent(boolean streaming,boolean scopeCurrent) throws Exception {
        try(var f=new EpochFixture()){
            var memory=mock(FocusMemoryService.class);
            var scope=new FocusMemoryScope("c".repeat(64),0,0,1,false);
            when(memory.scope(f.owner,"live")).thenReturn(scope);when(memory.current(scope)).thenReturn(true);
            org.springframework.test.util.ReflectionTestUtils.setField(f.service,"memories",memory);
            var contexts=new java.util.concurrent.CopyOnWriteArrayList<NovaFocusHistoryService.Context>();
            var started=new CountDownLatch(1);var finish=new CountDownLatch(1);
            doAnswer(call->{
                contexts.add(call.getArgument(4));java.util.function.Consumer<String> partial=call.getArgument(7);
                if(streaming)partial.accept("첫 문장입니다.");
                started.countDown();assertTrue(finish.await(3,TimeUnit.SECONDS));
                if(streaming)partial.accept("첫 문장입니다. 두 번째 문장입니다.");
                return new NovaFocusAnswer.Result("첫 문장입니다. 두 번째 문장입니다. 최종 문장입니다.",null);
            }).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            try{
                f.ask(1,"first");assertTrue(started.await(3,TimeUnit.SECONDS));
                var before=f.service.view(f.owner,"assist",1);
                var tree=NovaFocusModelSelectionTest.JSON.valueToTree(NovaFocusSettings.defaults());
                ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("presentation")).put("charIntervalMs",90);
                ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("answerLengthChars",480);
                ((com.fasterxml.jackson.databind.node.ObjectNode)tree).set("answerSelection",NovaFocusModelSelectionTest.JSON.readTree("{\"mode\":\"FIXED\",\"modelId\":\"fixture-model-b\"}"));
                var changed=NovaFocusModelSelectionTest.JSON.treeToValue(tree,NovaFocusSettings.class);
                when(f.history.settings(f.owner,"live",0,changed)).thenReturn(new NovaFocusHistoryService.Settings(1,changed));
                assertEquals(1,f.service.configure(f.owner,"assist",1,0,changed).settingsVersion());
                var saved=f.service.view(f.owner,"assist",1);
                assertEquals(before.activationId(),saved.activationId());assertEquals(before.turnId(),saved.turnId());
                assertEquals(before.presentation(),saved.presentation());assertEquals(before.answerLengthChars(),saved.answerLengthChars());
                if(streaming)assertEquals("첫 문장입니다.",saved.answerText());
                when(memory.current(scope)).thenReturn(scopeCurrent);finish.countDown();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(Boolean.TRUE.equals(f.service.diagnostics(f.owner,"assist",1).get("busy"))&&System.nanoTime()<deadline)Thread.sleep(5);
                assertFalse(Boolean.TRUE.equals(f.service.diagnostics(f.owner,"assist",1).get("busy")));
                if(scopeCurrent){
                    var completed=f.service.view(f.owner,"assist",1);
                    assertEquals("ANSWER_READY",completed.phase());assertEquals(before.presentation(),completed.presentation());
                    assertEquals(before.answerLengthChars(),completed.answerLengthChars());
                    assertEquals("첫 문장입니다. 두 번째 문장입니다. 최종 문장입니다.",completed.answerText());
                    assertEquals(0,contexts.get(0).settingsVersion());
                    f.presented(1);f.ask(1,"second");f.awaitAnswer(1);
                    assertEquals(2,contexts.size());assertEquals(1,contexts.get(1).settingsVersion());
                    assertEquals("fixture-model-b",contexts.get(1).answerSelection().modelId());
                    assertEquals(480,f.service.view(f.owner,"assist",1).answerLengthChars());
                    assertEquals(90,f.service.view(f.owner,"assist",1).presentation().charIntervalMs());
                }else{
                    assertEquals("",f.service.view(f.owner,"assist",1).answerText());
                    verify(f.history,never()).terminal(any(),any(),any(),eq("COMPLETED"),any());
                }
            }finally{finish.countDown();}
        }
    }
    @Test void workerDiagnosticsAreOwnerBoundAllowlistedAndClearedOnClose() throws Exception {
        try(var f=new EpochFixture()){
            when(f.answer.answer(anyLong(),anyString(),any(),any(),any())).thenAnswer(call->{
                com.example.lms.search.TraceStore.put("focus.selection.fallbackCount",1);
                com.example.lms.search.TraceStore.put("focus.request.evidenceBoundary","model_adapter");
                com.example.lms.search.TraceStore.put("focus.privatePayload","synthetic-private");
                return "answer";
            });
            f.ask(1,"first");f.awaitAnswer(1);
            var metadata=(Map<?,?>)f.service.diagnostics(f.owner,"assist",1).get("answerModel");
            assertEquals(Map.of("focus.selection.fallbackCount",1,"focus.request.evidenceBoundary","model_adapter"),metadata);
            assertThrows(IllegalArgumentException.class,()->f.service.diagnostics("b".repeat(64),"assist",1));
            assertThrows(IllegalArgumentException.class,()->f.service.diagnostics(f.owner,"assist",2));
            f.service.attach(f.owner,"live","assist",2);
            assertEquals(metadata,f.service.diagnostics(f.owner,"assist",2).get("answerModel"));
            f.service.close(f.owner,"assist",2,"user_closed");
            assertEquals(Map.of(),f.service.diagnostics(f.owner,"assist",2).get("answerModel"));
        }
    }
    @Test void audioEpochRebindKeepsInFlightAnswerAndRejectsOldAudio() throws Exception {
        try(var f=new EpochFixture()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var current=new java.util.concurrent.atomic.AtomicReference<java.util.function.BooleanSupplier>();
            when(f.answer.answer(anyLong(),anyString(),any(),any(),any())).thenAnswer(call->{
                current.set(call.getArgument(4));entered.countDown();
                assertTrue(release.await(3,TimeUnit.SECONDS));return "answer";
            });
            try{
                f.ask(1,"first");assertTrue(entered.await(3,TimeUnit.SECONDS));
                String activation=f.service.view(f.owner,"assist",1).activationId();
                f.service.attach(f.owner,"live","assist",2);
                assertTrue(current.get().getAsBoolean());
                assertEquals("THINKING",f.service.view(f.owner,"assist",2).phase());
                assertEquals(activation,f.service.view(f.owner,"assist",2).activationId());
                assertFalse(f.service.audio(f.owner,"assist",1,new ConversateQuestionPolicy.Utterance("old","old",1,true,"stale")));
                assertNull(f.service.view(f.owner,"assist",1));verify(f.answer,never()).cancel(anyLong());
                release.countDown();f.awaitAnswer(2);
                assertEquals("answer",f.service.view(f.owner,"assist",2).answerText());
            }finally{release.countDown();}
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"assist_replaced","explicit_reopen"})
    void oldProviderCompletionCannotCloseTheReplacementActivation(String boundary) throws Exception {
        try(var f=new EpochFixture()){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{
                if("first".equals(call.getArgument(1))){entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return new NovaFocusAnswer.Result("old-late",null);}
                return new NovaFocusAnswer.Result("replacement-answer",null);
            }).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            try{
                f.ask(1,"first");assertTrue(entered.await(3,TimeUnit.SECONDS));
                String oldActivation=f.service.view(f.owner,"assist",1).activationId();
                String replacement=boundary.equals("assist_replaced")?"replacement":"assist";
                if(boundary.equals("assist_replaced")){
                    f.service.attach(f.owner,"live",replacement,1);
                    assertNull(f.service.view(f.owner,"assist",1));
                    assertFalse(f.service.audio(f.owner,"assist",1,new ConversateQuestionPolicy.Utterance("old","old",1,true,"stale")));
                }else f.service.close(f.owner,"assist",1,"user_closed");
                f.service.open(f.owner,replacement,1,"fold");
                var reopened=f.service.view(f.owner,replacement,1);
                assertNotEquals(oldActivation,reopened.activationId());assertEquals("LISTENING",reopened.phase());
                verify(f.answer).cancel(7L);
                release.countDown();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(Boolean.TRUE.equals(f.service.diagnostics(f.owner,replacement,1).get("busy"))&&System.nanoTime()<deadline)Thread.sleep(5);
                assertFalse(Boolean.TRUE.equals(f.service.diagnostics(f.owner,replacement,1).get("busy")));
                var after=f.service.view(f.owner,replacement,1);
                assertTrue(after.active(),"old completion must leave the replacement activation active");
                assertEquals(reopened.activationId(),after.activationId());assertEquals(reopened.stateVersion(),after.stateVersion());
                assertEquals("LISTENING",after.phase());assertEquals("",after.answerText());
                verify(f.history,never()).terminal(any(),any(),any(),eq("COMPLETED"),any());
                f.service.input(f.owner,replacement,1,"next","next");f.time.now+=1200;f.service.maintain();
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(!"ANSWER_READY".equals(f.service.view(f.owner,replacement,1).phase())&&System.nanoTime()<deadline)Thread.sleep(5);
                assertEquals("replacement-answer",f.service.view(f.owner,replacement,1).answerText());
                verify(f.history).terminal(any(),any(),any(),eq("COMPLETED"),eq("replacement-answer"));
            }finally{release.countDown();}
        }
    }
    @Test void currentRequestWithChangedMemoryStillClosesActivation() throws Exception {
        try(var f=new EpochFixture()){
            var memories=mock(FocusMemoryService.class);
            var scope=new FocusMemoryScope("c".repeat(64),1,1,1,true);
            when(memories.scope(f.owner,"live")).thenReturn(scope);
            when(memories.current(scope)).thenReturn(false);
            org.springframework.test.util.ReflectionTestUtils.setField(f.service,"memories",memories);
            doReturn(new NovaFocusAnswer.Result("obsolete-memory-answer",null)).when(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            f.ask(1,"current");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(Boolean.TRUE.equals(f.service.diagnostics(f.owner,"assist",1).get("busy"))&&System.nanoTime()<deadline)Thread.sleep(5);
            assertFalse(Boolean.TRUE.equals(f.service.diagnostics(f.owner,"assist",1).get("busy")));
            var after=f.service.view(f.owner,"assist",1);
            assertFalse(after.active());assertEquals("memory_changed",after.reason());assertEquals("",after.answerText());
            verify(f.answer).answerResult(any(),any(),any(),any(),any(),any(),any(),any());
            verify(f.history,never()).terminal(any(),any(),any(),eq("COMPLETED"),any());
        }
    }
    @Test void audioEpochRebindPreservesRecentPairsButExplicitCloseClearsThem() throws Exception {
        try(var f=new EpochFixture()){
            var contexts=new java.util.concurrent.CopyOnWriteArrayList<NovaFocusHistoryService.Context>();
            when(f.answer.answer(anyLong(),anyString(),any(),any(),any())).thenAnswer(call->{contexts.add(call.getArgument(2));return "answer";});
            f.ask(1,"first");f.awaitAnswer(1);f.presented(1);
            f.service.attach(f.owner,"live","assist",2);f.ask(2,"second");f.awaitAnswer(2);
            assertEquals(1,contexts.get(1).recent().size());
            assertEquals("first",contexts.get(1).recent().get(0).question());
            f.service.close(f.owner,"assist",2,"user_closed");f.service.open(f.owner,"assist",2,"fold");
            f.ask(2,"third");f.awaitAnswer(2);assertTrue(contexts.get(2).recent().isEmpty());
        }
    }
    @Test void audioEpochOldAttachCannotRollBindingBack() {
        try(var f=new EpochFixture()){
            f.service.attach(f.owner,"live","assist",2);f.service.attach(f.owner,"live","assist",1);
            assertNotNull(f.service.view(f.owner,"assist",2));assertNull(f.service.view(f.owner,"assist",1));
        }
    }
    private static final class EpochFixture implements AutoCloseable {
        final String owner="a".repeat(64);
        final Time time=new Time();
        final NovaFocusHistoryService history=mock(NovaFocusHistoryService.class);
        final NovaFocusAnswer answer=typedAdapter();
        final NovaFocusService service;
        EpochFixture(){
            @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(answer);
            when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,NovaFocusSettings.defaults()));
            when(history.open(anyString(),anyString())).thenReturn(7L);
            var sequence=new java.util.concurrent.atomic.AtomicInteger();
            when(history.accept(anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(call->
                new NovaFocusHistoryService.Accepted("turn-"+sequence.incrementAndGet(),7L,"ACCEPTED",true));
            when(history.terminal(anyString(),anyString(),anyString(),eq("COMPLETED"),anyString())).thenReturn(true);
            service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time);
            service.attach(owner,"live","assist",1);service.open(owner,"assist",1,"fold");
        }
        void ask(long epoch,String question){service.input(owner,"assist",epoch,question,question);time.now+=1200;service.maintain();}
        void awaitAnswer(long epoch) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline){
                if("ANSWER_READY".equals(service.view(owner,"assist",epoch).phase())&&!Boolean.TRUE.equals(service.diagnostics(owner,"assist",epoch).get("busy")))return;
                Thread.sleep(5);
            }
            fail("answer did not complete");
        }
        void presented(long epoch){
            var v=service.view(owner,"assist",epoch);
            for(String event:List.of("first_visible","presentation_done"))assertTrue(service.rendered(new NovaFocusService.Receipt(v.serverInstanceId(),v.activationId(),v.turnId(),v.answerVersion(),v.renderReceiptTicket(),event)));
        }
        public void close(){service.close();}
    }
    @Test void memorySearchRequiresOwnerEpochAndNeverCallsChat(){
        var history=mock(NovaFocusHistoryService.class);var memory=mock(FocusMemoryService.class);
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,NovaFocusSettings.defaults()));
        var scope=new FocusMemoryScope("c".repeat(64),1,1,1,true);
        when(memory.scope("a".repeat(64),"channel")).thenReturn(scope);
        when(memory.retrieve(eq(scope),eq("query"),any())).thenAnswer(a->{
            assertTrue(((java.util.function.BooleanSupplier)a.getArgument(2)).getAsBoolean());
            return new FocusMemoryService.Result(List.of(),FocusMemoryService.Status.OK,"SCOPED_VECTOR_LOCAL_GRAPH",1,0,0,2,1,false,"");
        });
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),new Time())){
            org.springframework.test.util.ReflectionTestUtils.setField(service,"memories",memory);
            service.attach("a".repeat(64),"channel","assist",1);clearInvocations(provider);
            assertThrows(IllegalArgumentException.class,()->service.memorySearch("b".repeat(64),"assist",1,"query"));
            assertThrows(IllegalArgumentException.class,()->service.memorySearch("a".repeat(64),"assist",2,"query"));
            assertThrows(IllegalArgumentException.class,()->service.memorySearch("a".repeat(64),"assist",1," "));
            assertEquals(1,service.memorySearch("a".repeat(64),"assist",1,"query").vectorHits());
            verifyNoInteractions(provider);verify(memory,times(1)).retrieve(eq(scope),eq("query"),any());
        }
    }
    static class Time extends Clock {
        volatile long now;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    @Test void independentWorkerAndCloseFenceLateProviderCompletion() throws Exception {
        var history=mock(NovaFocusHistoryService.class);var answer=typedAdapter();
        when(answer.answer(anyLong(),anyString(),any(),any())).thenCallRealMethod();
        when(answer.answer(anyLong(),anyString(),any(),any(),any())).thenCallRealMethod();
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(answer);
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,NovaFocusSettings.defaults()));
        when(history.open(anyString(),anyString())).thenReturn(7L);
        when(history.accept(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Accepted("turn",7L,"ACCEPTED",true));
        when(history.context(anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Context(List.of(),"",List.of()));
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
        when(answer.answer(anyLong(),anyString(),any())).thenAnswer(call->{started.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));finished.countDown();return "late";});
        var time=new Time();String owner="a".repeat(64);
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time)){
            service.attach(owner,"live","assist",1);service.open(owner,"assist",1,"fold");
            service.input(owner,"assist",1,"r","question");time.now=1200;service.maintain();assertTrue(started.await(3,TimeUnit.SECONDS));
            assertEquals("THINKING",service.view(owner,"assist",1).phase());
            service.close(owner,"assist",1,"closed");release.countDown();assertTrue(finished.await(3,TimeUnit.SECONDS));
            assertFalse(service.active("assist"));verify(history,never()).terminal(anyString(),anyString(),anyString(),eq("COMPLETED"),anyString());
            verify(answer).cancel(7L);assertNull(service.view(owner,"assist",2));
        }finally{release.countDown();}
    }
    @Test void persistedManualRetryDoesNotQueueAndConflictingPayloadFailsSynchronously(){
        var history=mock(NovaFocusHistoryService.class);var answer=typedAdapter();
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(answer);
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,NovaFocusSettings.defaults()));
        when(history.open(anyString(),anyString())).thenReturn(7L);
        String owner="a".repeat(64),key=NovaFocusState.typedRequestId("retry");
        when(history.knownRequest(owner,"live",key,"question")).thenReturn(true);
        when(history.knownRequest(owner,"live",key,"different")).thenThrow(new IllegalArgumentException("focus_request_conflict"));
        var time=new Time();
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time)){
            service.attach(owner,"live","assist",1);service.open(owner,"assist",1,"fold");
            service.input(owner,"assist",1,"retry"," question ");time.now=1200;service.maintain();
            assertEquals("focus_request_already_accepted",service.view(owner,"assist",1).reason());
            assertEquals("",service.view(owner,"assist",1).draftText());
            var conflict=assertThrows(IllegalArgumentException.class,()->service.input(owner,"assist",1,"retry","different"));
            assertEquals("focus_request_conflict",conflict.getMessage());
            verify(history,never()).accept(anyString(),anyString(),anyString(),anyString(),anyString());
            verifyNoInteractions(answer);
        }
    }
    @Test void settingsAndHistoryRequireExistingOwnerEpoch(){
        var history=mock(NovaFocusHistoryService.class);
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,NovaFocusSettings.defaults()));
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),new Time())){
            service.attach("a".repeat(64),"live","assist",1);
            String cacheScope=service.localStore("a".repeat(64),"assist",1).cacheScope();
            assertTrue(cacheScope.matches("[a-f0-9]{64}"));
            service.attach("b".repeat(64),"live","other",1);
            assertNotEquals(cacheScope,service.localStore("b".repeat(64),"other",1).cacheScope());
            assertThrows(IllegalArgumentException.class,()->service.inputAccepted("b".repeat(64),"assist",1,"r","question"));
            assertThrows(IllegalArgumentException.class,()->service.settings("b".repeat(64),"assist",1));
            assertThrows(IllegalArgumentException.class,()->service.settings("a".repeat(64),"assist",2));
            assertThrows(IllegalStateException.class,()->service.open("a".repeat(64),"assist",1,"lens"));
            assertFalse(service.rendered(new NovaFocusService.Receipt("wrong","wrong","wrong",1,"0".repeat(64),"presentation_done")));
            verify(history,never()).open(anyString(),anyString());
        }
    }
    @Test void snapshotLifecycleClaimsOnceAndFeedsGenerateWithOneImage() throws Exception {
        var history=mock(NovaFocusHistoryService.class);var answer=typedAdapter();
        when(answer.answer(anyLong(),anyString(),any(),any())).thenCallRealMethod();
        when(answer.answer(anyLong(),anyString(),any(),any(),any())).thenCallRealMethod();
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(answer);
        var d=NovaFocusSettings.defaults();
        var snap=new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation(),false,false,new NovaFocusSettings.Snapshot(true,"FOLD_REAR"));
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,snap));
        when(history.open(anyString(),anyString())).thenReturn(7L);
        when(history.accept(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Accepted("turn",7L,"ACCEPTED",true));
        when(history.context(anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Context(List.of(),"",List.of()));
        var entered=new CountDownLatch(1);
        when(answer.answer(anyLong(),anyString(),anyString(),anyString(),any(),any(),any())).thenAnswer(call->{
            entered.countDown();assertEquals("QUJD",call.getArgument(2));assertEquals("image/jpeg",call.getArgument(3));return "seen";});
        var time=new Time();String owner="a".repeat(64);
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time)){
            service.attach(owner,"live","assist",1);service.open(owner,"assist",1,"fold");
            service.input(owner,"assist",1,"r","question");time.now=1200;service.maintain();
            var cmd=service.snapshotCommand(owner,"assist",1,time.now);assertNotNull(cmd);assertEquals("FOLD_REAR",cmd.source());
            assertNull(service.snapshotCommand("b".repeat(64),"assist",1,time.now));
            assertThrows(IllegalArgumentException.class,()->service.snapshotClaim("b".repeat(64),"assist",1,cmd.requestId(),cmd.captureId()));
            assertThrows(IllegalArgumentException.class,()->service.snapshotClaim(owner,"assist",2,cmd.requestId(),cmd.captureId()));
            var claim=service.snapshotClaim(owner,"assist",1,cmd.requestId(),cmd.captureId());
            assertEquals(true,claim.get("claimed"));assertEquals(true,claim.get("granted"));
            // 같은 명령의 두 번째 claim은 합류로만 기록되고 새 촬영을 승인하지 않는다.
            assertEquals(false,service.snapshotClaim(owner,"assist",1,cmd.requestId(),cmd.captureId()).get("granted"));
            var accepted=service.snapshotResult(owner,"assist",1,cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",null);
            assertEquals(true,accepted.get("accepted"));assertEquals(false,accepted.get("duplicate"));
            assertEquals(true,service.snapshotResult(owner,"assist",1,cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",null).get("duplicate"));
            time.now=1400;service.maintain();assertTrue(entered.await(3,TimeUnit.SECONDS));
            verify(history).accept(owner,"live",cmd.activationId(),cmd.requestId(),"question");
        }
    }
    @Test void snapshotErrorReportFailsQuestionButKeepsIt() {
        var history=mock(NovaFocusHistoryService.class);var answer=typedAdapter();
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        var d=NovaFocusSettings.defaults();
        var snap=new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation(),false,false,new NovaFocusSettings.Snapshot(true,"META_GLASSES"));
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,snap));
        when(provider.getIfAvailable()).thenReturn(answer);
        when(history.open(anyString(),anyString())).thenReturn(7L);
        when(history.accept(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Accepted("turn",7L,"ACCEPTED",true));
        var time=new Time();String owner="a".repeat(64);
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time)){
            service.attach(owner,"live","assist",1);service.open(owner,"assist",1,"fold");
            service.input(owner,"assist",1,"r","question");time.now=1200;service.maintain();
            var cmd=service.snapshotCommand(owner,"assist",1,time.now);assertNotNull(cmd);assertEquals("META_GLASSES",cmd.source());
            var failed=service.snapshotResult(owner,"assist",1,cmd.requestId(),cmd.captureId(),null,null,"device_unavailable");
            assertEquals(true,failed.get("failed"));
            var view=service.view(owner,"assist",1);
            assertEquals("device_unavailable",view.reason());assertEquals("question",view.questionText());
            assertNull(service.snapshotCommand(owner,"assist",1,time.now));
            // 촬영 실패 뒤에도 같은 질문이 사진 없이 생성 경로로 진행된다.
            time.now=1400;service.maintain();
            verify(answer,timeout(3000)).answer(anyLong(),eq("question"),any(),isNull(),any());
        }
    }
    @Test void turningVoiceWakeOffCancelsFocusWorkAndPreservesBinding() throws Exception {
        var history=mock(NovaFocusHistoryService.class);var answer=typedAdapter();
        when(answer.answer(anyLong(),anyString(),any(),any())).thenCallRealMethod();
        when(answer.answer(anyLong(),anyString(),any(),any(),any())).thenCallRealMethod();
        @SuppressWarnings("unchecked") ObjectProvider<NovaFocusAnswer> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(answer);
        var d=NovaFocusSettings.defaults();var enabled=new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation());
        when(history.settings(anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Settings(0,enabled));
        when(history.settings(anyString(),anyString(),eq(0L),eq(d))).thenReturn(new NovaFocusHistoryService.Settings(1,d));
        when(history.open(anyString(),anyString())).thenReturn(7L);
        when(history.accept(anyString(),anyString(),anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Accepted("turn",7L,"ACCEPTED",true));
        when(history.context(anyString(),anyString(),anyString())).thenReturn(new NovaFocusHistoryService.Context(List.of(),"",List.of()));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(answer.answer(anyLong(),anyString(),any())).thenAnswer(call->{entered.countDown();release.await(3,TimeUnit.SECONDS);return "late";});
        var time=new Time();String owner="a".repeat(64);
        try(var service=new NovaFocusService(history,provider,new PublicChatAdmissionGuard(),time)){
            service.attach(owner,"live","assist",1);service.open(owner,"assist",1,"fold");service.input(owner,"assist",1,"r","question");
            time.now=1200;service.maintain();assertTrue(entered.await(3,TimeUnit.SECONDS));
            service.configure(owner,"assist",1,0,d);assertFalse(service.active("assist"));verify(answer).cancel(7L);
            assertNotNull(service.view(owner,"assist",1));verify(history).terminal(owner,"live","turn","CANCELLED",null);
            release.countDown();
        }finally{release.countDown();}
    }
}
