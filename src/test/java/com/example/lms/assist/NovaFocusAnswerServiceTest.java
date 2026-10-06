package com.example.lms.assist;

import com.example.lms.api.PublicRequestBudgetGuard;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.llm.ModelSelectionException;
import com.example.lms.service.*;
import com.example.lms.service.chat.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusAnswerServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"completed","changed","failed"})
    void publishedFixedOAuthPrefixNeverStartsUnknownWebRetryOrAcceptsRevision(String outcome) throws Exception {
        var chat=mock(ChatService.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,mock(PublicRequestBudgetGuard.class),runs);
        var seen=new java.util.concurrent.CopyOnWriteArrayList<String>();
        when(chat.continueChat(any(),isNull(),any())).thenAnswer(call->{
            ChatRequestDto request=call.getArgument(0);
            assertEquals("chatgpt-oauth:gpt-5.6-luna",request.getModel());assertTrue(request.isStrictModelSelection());
            assertEquals("FACT",request.getMode());assertEquals(Boolean.FALSE,request.getPolish());
            assertFalse(request.isUseWebSearch());assertFalse(request.isUseRag());
            var run=ChatRunExecutionContext.current();
            try(var permit=run.permitFoldStreaming(true);var stage=ChatRunExecutionContext.bindProviderTextStage("chat_draft")){
                ChatRunExecutionContext.providerTextConsumer().accept("모르겠습니다. "+"x".repeat(40));
            }
            if(outcome.equals("failed"))throw new ModelSelectionException("backend_unavailable");
            return ChatResult.of(outcome.equals("changed")?"다른 답변입니다.":"모르겠습니다.","gpt-5.6-luna",false);
        });
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),
            new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"chatgpt-oauth:gpt-5.6-luna"),4,400,false,true);
        try{
            if(outcome.equals("completed"))assertEquals("모르겠습니다.",adapter.answerResult(9755L,"일반 질문",null,null,memory,null,()->true,seen::add).text());
            else assertThrows(RuntimeException.class,()->adapter.answerResult(9755L,"일반 질문",null,null,memory,null,()->true,seen::add));
            assertEquals(List.of("모르겠습니다."),seen);verify(chat,times(1)).continueChat(any(),isNull(),any());
            if(!outcome.equals("failed"))assertEquals(0L,((Number)NovaFocusAnswerService.diagnosticTrace().get("focus.selection.fallbackCount")).longValue());
            assertTrue(((Number)NovaFocusAnswerService.diagnosticTrace().get("focus.stream.publishedChars")).intValue()>0);
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void explicitOnAllowsSelectedModelToChooseSearchWithoutForcingGenericRetrieval() throws Exception {
        var chat=mock(ChatService.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,mock(PublicRequestBudgetGuard.class),runs);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("안녕하세요","gemini-fixture",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),
            new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"gemini-fixture"),4,400,false,true);
        try {
            assertEquals("안녕하세요",adapter.answer(7L,"안녕?",memory));
            var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);var context=org.mockito.ArgumentCaptor.forClass(ChatConversationContext.class);
            verify(chat).continueChat(request.capture(),isNull(),context.capture());
            assertFalse(request.getValue().isUseWebSearch());assertTrue(context.getValue().focusGoogleSearchAllowed());
            assertEquals("gemini-fixture",request.getValue().getModel());
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void selectedGeminiGroundingKeepsOriginalAndPermissionInInternalContext() throws Exception {
        var chat=mock(ChatService.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,mock(PublicRequestBudgetGuard.class),runs);
        String original="원문 그대로";
        var metadata=new com.example.lms.learning.gemini.GeminiGateway.GroundingMetadata(List.of("query"),List.of(
            new com.example.lms.learning.gemini.GeminiGateway.GroundingChunk(new com.example.lms.learning.gemini.GeminiGateway.WebSource("https://example.com/source","Source"))),
            List.of(new com.example.lms.learning.gemini.GeminiGateway.GroundingSupport(new com.example.lms.learning.gemini.GeminiGateway.Segment(0,0,16,original),List.of(0),List.of())),
            new com.example.lms.learning.gemini.GeminiGateway.SearchEntryPoint("<div>Suggestions</div>"));
        var grounding=new com.example.lms.learning.gemini.GeminiGateway.GroundedAnswer(original,"gemini-selected-fixture",metadata,List.of(original),true);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(new ChatResult(original,"gemini-selected-fixture",false,Set.of(),List.of(),grounding));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),
            new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"gemini-selected-fixture"),4,400,false,true);
        try {
            var result=adapter.answerResult(7L,"최신 공식 자료를 웹에서 찾아줘",null,null,memory,null,()->true);
            assertEquals(original,result.text());assertSame(grounding,result.grounding());
            var context=org.mockito.ArgumentCaptor.forClass(ChatConversationContext.class);
            var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);verify(chat).continueChat(request.capture(),isNull(),context.capture());
            assertTrue(context.getValue().focusGoogleSearchAllowed());assertEquals("gemini-selected-fixture",request.getValue().getModel());
            assertTrue(request.getValue().isStrictModelSelection());
            when(chat.continueChat(any(),isNull(),any())).thenReturn(new ChatResult("changed body","gemini-selected-fixture",false,Set.of(),List.of(),grounding));
            var held=assertThrows(IllegalStateException.class,()->adapter.answerResult(7L,"웹에서 찾아줘",null,null,memory,null,()->true));
            assertEquals("focus_grounding_publication_held",held.getMessage());
        } finally {ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void explicitSearchOffBlocksInitialJevAndUnknownRetryWithoutChangingFixedModel() throws Exception {
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var requests=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var advisor=new JevDecisionAdvisor(new MockEnvironment().withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z"),java.time.Clock.systemUTC(),
            req->{calls.incrementAndGet();return new JevDecisionAdvisor.EvalResponse(200,JevDecisionAdvisor.Verdict.WEB,null,null);},name->"fixture-key");
        ReflectionTestUtils.setField(adapter,"jevAdvisor",advisor);ReflectionTestUtils.setField(adapter,"webAggressiveEnabled",true);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("모르겠습니다","pinned-model",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),
            new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"pinned-model"),3,400,false,false);
        try {
            assertEquals("모르겠습니다",adapter.answer(7L,"최신 공식 자료를 웹에서 찾아줘",memory));
            verify(chat,times(1)).continueChat(requests.capture(),isNull(),any());assertEquals(0,calls.get());
            assertFalse(requests.getValue().isUseWebSearch());assertEquals(0,requests.getValue().getWebTopK());
            assertEquals(com.example.lms.gptsearch.dto.SearchMode.OFF,requests.getValue().getSearchMode());
            assertEquals("pinned-model",requests.getValue().getModel());assertTrue(requests.getValue().isStrictModelSelection());
            assertEquals("off",NovaFocusAnswerService.diagnosticTrace().get("focus.search.groundingStatus"));
            clearInvocations(chat);assertThrows(CancellationException.class,()->adapter.answer(7L,"late",memory,()->false));verifyNoInteractions(chat);
        } finally {advisor.close();ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void publicAutoEvidenceAndExactCancellationKeepHistoryOutOfPublicDto() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("합성 응답","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(new NovaFocusHistoryService.Pair(1,"t","COMPLETED","이름은?","별빛")),"",List.of());
        try{
            assertEquals("합성 응답",adapter.answer(7L,"아까 정한 이름은?",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertFalse(request.getValue().isUseWebSearch());assertFalse(request.getValue().isUseRag());
            assertNull(request.getValue().getSessionId());assertEquals("EPHEMERAL",request.getValue().getMemoryMode());
            clearInvocations(chat);
            adapter.answer(7L,"공식 자료를 찾아 확인해줘",memory);
            verify(chat).continueChat(request.capture(),isNull(),any());assertTrue(request.getValue().isUseWebSearch());assertFalse(request.getValue().isUseRag());
            clearInvocations(chat);
            assertThrows(CancellationException.class,()->adapter.answer(7L,"closed",memory,()->false));verifyNoInteractions(chat);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            when(chat.continueChat(any(),isNull(),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));ChatRunExecutionContext.throwIfCancelled();return ChatResult.of("late","recording",false);});
            var executor=Executors.newSingleThreadExecutor();
            try{var future=executor.submit(()->adapter.answer(7L,"질문",memory));assertTrue(entered.await(3,TimeUnit.SECONDS));adapter.cancel(7L);release.countDown();
                assertThrows(ExecutionException.class,()->future.get(3,TimeUnit.SECONDS));assertNull(ChatRunExecutionContext.current());
            }finally{release.countDown();executor.shutdownNow();}
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void jevVerdictReachesFocusTraceAndSteersSearchModeOnly() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        var env=new MockEnvironment().withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var advisor=new JevDecisionAdvisor(env,java.time.Clock.systemUTC(),
            req->{count.incrementAndGet();return new JevDecisionAdvisor.EvalResponse(200,JevDecisionAdvisor.Verdict.RECENT_ONLY,null,null);},name->"fixture-key");
        ReflectionTestUtils.setField(adapter,"jevAdvisor",advisor);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("합성 응답","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            // 공식 자료를 찾아 확인해줘 wants web deterministically; a RECENT_ONLY verdict narrows it off.
            assertEquals("합성 응답",adapter.answer(7L,"공식 자료를 찾아 확인해줘",memory));
            assertEquals(1,count.get(),"one confirmed question = at most one evaluate call");
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertFalse(request.getValue().isUseWebSearch());
            var trace=NovaFocusAnswerService.diagnosticTrace();
            assertEquals("RECENT_ONLY",trace.get("focus.jev.decision"));assertEquals("ok",trace.get("focus.jev.reasonCode"));
            assertEquals(true,trace.get("focus.jev.applied"));assertEquals("on",trace.get("focus.jev.mode"));
            assertNotEquals(trace.get("focus.jev.decision"),trace.get("focus.selection.mode"),"model selection and Jev verdict stay separate");
        }finally{advisor.close();ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void jevShadowLeavesWebDecisionAndMarksDefer() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        var env=new MockEnvironment().withProperty("demo.jev.mode","shadow").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var advisor=new JevDecisionAdvisor(env,java.time.Clock.systemUTC(),
            req->{count.incrementAndGet();return new JevDecisionAdvisor.EvalResponse(200,JevDecisionAdvisor.Verdict.RECENT_ONLY,null,null);},name->"fixture-key");
        ReflectionTestUtils.setField(adapter,"jevAdvisor",advisor);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("합성 응답","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            assertEquals("합성 응답",adapter.answer(7L,"공식 자료를 찾아 확인해줘",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertTrue(request.getValue().isUseWebSearch(),"SHADOW never changes the deterministic route");
            var trace=NovaFocusAnswerService.diagnosticTrace();
            assertEquals("defer",trace.get("focus.jev.decision"));assertEquals("shadow",trace.get("focus.jev.reasonCode"));
            assertEquals(false,trace.get("focus.jev.applied"));assertEquals("shadow",trace.get("focus.jev.mode"));
        }finally{advisor.close();ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void jevOffAndMissingAdvisorBothDefer() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var env=new MockEnvironment();
        var advisor=new JevDecisionAdvisor(env,java.time.Clock.systemUTC(),
            req->{count.incrementAndGet();return new JevDecisionAdvisor.EvalResponse(200,JevDecisionAdvisor.Verdict.WEB,null,null);},name->"fixture-key");
        ReflectionTestUtils.setField(adapter,"jevAdvisor",advisor);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("합성 응답","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            assertEquals("합성 응답",adapter.answer(7L,"공식 자료를 찾아 확인해줘",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertTrue(request.getValue().isUseWebSearch());
            assertEquals(0,count.get(),"OFF mode must not call the Gateway");
            var trace=NovaFocusAnswerService.diagnosticTrace();
            assertEquals("off",trace.get("focus.jev.decision"));assertEquals("disabled",trace.get("focus.jev.reasonCode"));
        }finally{advisor.close();ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void imageQuestionCarriesExactlyOneImageAndSnapshotSource() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("보이는 응답","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            assertEquals("보이는 응답",adapter.answer(7L,"이거 뭐야?","QUJD","image/jpeg",memory,null,()->true));
            verify(chat).continueChat(request.capture(),isNull(),any());
            var dto=request.getValue();
            assertEquals("QUJD",dto.getImageBase64());assertEquals("image/jpeg",dto.getImageMediaType());
            assertEquals("focus_snapshot",dto.getSnapshotSource());
            assertFalse(dto.isUseWebSearch());assertFalse(dto.isUseRag());assertEquals(0,dto.getWebTopK());
            assertEquals("EPHEMERAL",dto.getMemoryMode());assertNull(dto.getSessionId());
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void visionUnavailableFallsBackToSameQuestionWithoutImageOnce() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        when(chat.continueChat(any(),isNull(),any()))
            .thenReturn(ChatResult.of("evidence_needed: vision_model_unavailable","vision:unavailable",false))
            .thenReturn(ChatResult.of("텍스트 응답","text-model",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            var answer=adapter.answer(7L,"이거 뭐야?","QUJD","image/jpeg",memory,null,()->true);
            assertTrue(answer.startsWith("사진 분석 모델이 준비되지 않아"));assertTrue(answer.contains("텍스트 응답"));
            verify(chat,times(2)).continueChat(request.capture(),isNull(),any());
            var calls=request.getAllValues();
            assertEquals("QUJD",calls.get(0).getImageBase64());assertEquals("focus_snapshot",calls.get(0).getSnapshotSource());
            assertNull(calls.get(1).getImageBase64());assertNull(calls.get(1).getSnapshotSource());
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void unknownAnswerTriggersOneBoundedWebRetryOnSameRequest() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        when(chat.continueChat(any(),isNull(),any()))
            .thenReturn(ChatResult.of("충분한 증거를 찾지 못했습니다.","recording",false))
            .thenReturn(ChatResult.of("웹 근거 응답","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            assertEquals("웹 근거 응답",adapter.answer(7L,"2026년 춘계 회의 일정이 언제야?",memory));
            verify(chat,times(2)).continueChat(request.capture(),isNull(),any());
            var calls=request.getAllValues();
            assertFalse(calls.get(0).isUseWebSearch());assertTrue(calls.get(1).isUseWebSearch());
            assertEquals(com.example.lms.gptsearch.dto.SearchMode.AUTO,calls.get(1).getSearchMode());
            assertTrue(calls.get(1).getRetrievalRequestIntent().webSearch());
            assertFalse(calls.get(1).getRetrievalRequestIntent().rag());
            assertNull(calls.get(1).getSessionId());assertEquals("EPHEMERAL",calls.get(1).getMemoryMode());
            var trace=NovaFocusAnswerService.diagnosticTrace();
            assertEquals("EXPLICIT_UNKNOWN",trace.get("focus.unknown.trigger"));
            assertEquals("GENERAL",trace.get("focus.unknown.mode"));
            assertEquals(true,trace.get("focus.unknown.webRetry"));assertEquals("replaced",trace.get("focus.unknown.outcome"));
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void blankAnswerRetriesOnceThenStillThrowsEmpty(){
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            var failure=assertThrows(IllegalStateException.class,()->adapter.answer(7L,"모르는 것을 알려 줘",memory));
            assertEquals("focus_empty_answer",failure.getMessage());
            verify(chat,times(2)).continueChat(any(),isNull(),any());
            var trace=NovaFocusAnswerService.diagnosticTrace();
            assertEquals("EMPTY_ANSWER",trace.get("focus.unknown.trigger"));
            assertEquals(true,trace.get("focus.unknown.webRetry"));
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void unknownWebDisabledNeverRetries(){
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);
        ReflectionTestUtils.setField(adapter,"unknownWebEnabled",false);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("충분한 증거를 찾지 못했습니다.","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            assertEquals("충분한 증거를 찾지 못했습니다.",adapter.answer(7L,"모르는 것을 알려 줘",memory));
            verify(chat,times(1)).continueChat(any(),isNull(),any());
            assertEquals("disabled",NovaFocusAnswerService.diagnosticTrace().get("focus.unknown.reason"));
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void ownerWebOnUnknownNarrowsButNeverWidensTheGlobalSwitch(){
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("충분한 증거를 찾지 못했습니다.","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            // webOnUnknown=false: 전역 스위치가 켜져 있어도 이 프로필에서는 재시도하지 않는다.
            var off=new FocusMemoryScope("a".repeat(64),1,1,1,false,new NovaFocusSettings.Memory(null,null,null,null,false));
            assertEquals("충분한 증거를 찾지 못했습니다.",adapter.answer(7L,"모르는 것을 알려 줘",memory,off,()->true));
            verify(chat,times(1)).continueChat(any(),isNull(),any());
            assertEquals("disabled",NovaFocusAnswerService.diagnosticTrace().get("focus.unknown.reason"));
            // webOnUnknown=true + 전역 OFF: 소유자 플래그가 전역 스위치를 넓히지 못한다.
            ReflectionTestUtils.setField(adapter,"unknownWebEnabled",false);
            var on=new FocusMemoryScope("b".repeat(64),1,1,1,false,new NovaFocusSettings.Memory(null,null,null,null,true));
            assertEquals("충분한 증거를 찾지 못했습니다.",adapter.answer(8L,"모르는 것을 알려 줘",memory,on,()->true));
            verify(chat,times(2)).continueChat(any(),isNull(),any());
            assertEquals("disabled",NovaFocusAnswerService.diagnosticTrace().get("focus.unknown.reason"));
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void webAlreadyAttemptedNeverRetriesAgain(){
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("충분한 증거를 찾지 못했습니다.","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            // 공식 자료를 찾아 확인해줘 turns web on deterministically; an unknown answer stays a single pass.
            assertEquals("충분한 증거를 찾지 못했습니다.",adapter.answer(7L,"공식 자료를 찾아 확인해줘",memory));
            verify(chat,times(1)).continueChat(any(),isNull(),any());
            assertEquals("web_already_attempted",NovaFocusAnswerService.diagnosticTrace().get("focus.unknown.reason"));
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void imageRequestAndRecentOnlyVerdictNeverRetryWeb() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("충분한 증거를 찾지 못했습니다.","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            adapter.answer(7L,"이거 뭐야?","QUJD","image/jpeg",memory,null,()->true);
            verify(chat,times(1)).continueChat(any(),isNull(),any());
            assertEquals("request_web_off",NovaFocusAnswerService.diagnosticTrace().get("focus.unknown.reason"));
            clearInvocations(chat);
            var env=new MockEnvironment().withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
            var advisor=new JevDecisionAdvisor(env,java.time.Clock.systemUTC(),
                req->new JevDecisionAdvisor.EvalResponse(200,JevDecisionAdvisor.Verdict.RECENT_ONLY,null,null),name->"fixture-key");
            ReflectionTestUtils.setField(adapter,"jevAdvisor",advisor);
            try{
                assertEquals("충분한 증거를 찾지 못했습니다.",adapter.answer(7L,"모르는 것을 알려 줘",memory));
                verify(chat,times(1)).continueChat(any(),isNull(),any());
                assertEquals("recent_only_precedence",NovaFocusAnswerService.diagnosticTrace().get("focus.unknown.reason"));
            }finally{advisor.close();}
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void scopedRagVerdictNeedsExplicitFlagForUnknownWeb() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        var env=new MockEnvironment().withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        var advisor=new JevDecisionAdvisor(env,java.time.Clock.systemUTC(),
            req->new JevDecisionAdvisor.EvalResponse(200,JevDecisionAdvisor.Verdict.SCOPED_RAG,null,null),name->"fixture-key");
        ReflectionTestUtils.setField(adapter,"jevAdvisor",advisor);
        when(chat.continueChat(any(),isNull(),any()))
            .thenReturn(ChatResult.of("충분한 증거를 찾지 못했습니다.","recording",false))
            .thenReturn(ChatResult.of("충분한 증거를 찾지 못했습니다.","recording",false))
            .thenReturn(ChatResult.of("범위 내 근거 응답","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            adapter.answer(7L,"모르는 것을 알려 줘",memory);
            verify(chat,times(1)).continueChat(any(),isNull(),any());
            assertEquals("scoped_rag_precedence",NovaFocusAnswerService.diagnosticTrace().get("focus.unknown.reason"));
            clearInvocations(chat);
            ReflectionTestUtils.setField(adapter,"scopedWebEnabled",true);
            assertEquals("범위 내 근거 응답",adapter.answer(7L,"다른 모르는 질문",memory));
            verify(chat,times(2)).continueChat(request.capture(),isNull(),any());
            assertTrue(request.getAllValues().get(1).isUseWebSearch());
            assertEquals("scoped_flag",NovaFocusAnswerService.diagnosticTrace().get("focus.unknown.reason"));
        }finally{advisor.close();ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void aggressiveAutoTurnsGeneralKnowledgeQuestionOnAndPrefersConfiguredWebModel() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        ReflectionTestUtils.setField(adapter,"webAggressiveEnabled",true);
        ReflectionTestUtils.setField(adapter,"webModel","llmrouter.gemini-pro");
        var catalog=mock(ChatModelCatalogService.class);
        when(catalog.resolve("llmrouter.gemini-pro")).thenReturn(Optional.of(
            new ChatModelCatalogService.Choice("llmrouter.gemini-pro","gemini","gemini-pro","gemini-3.8-flash",
                "configured",true,"","release","server_catalog")));
        ReflectionTestUtils.setField(adapter,"modelCatalog",catalog);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("합성 응답","gemini-3.8-flash",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            // 지식 질문: 공격적 AUTO가 웹서치를 추론하고 설정된 API 라우트를 소프트 선호한다.
            assertEquals("합성 응답",adapter.answer(7L,"광합성이 뭐야?",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            var dto=request.getValue();
            assertTrue(dto.isUseWebSearch());
            assertEquals(com.example.lms.gptsearch.dto.SearchMode.AUTO,dto.getSearchMode());
            assertEquals("llmrouter.gemini-pro",dto.getModel());assertFalse(dto.isStrictModelSelection());
            clearInvocations(chat);
            // 순수 인사는 웹서치로 올라가지 않는다.
            assertEquals("합성 응답",adapter.answer(7L,"안녕",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertFalse(request.getValue().isUseWebSearch());assertNull(request.getValue().getModel());
            clearInvocations(chat);
            // 세션 회상 질문은 공격적 AUTO 아래에서도 로컬에 머문다.
            assertEquals("합성 응답",adapter.answer(7L,"방금 내가 뭐라고 했어?",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertFalse(request.getValue().isUseWebSearch(),"session-context questions stay local under aggressive auto");
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void fixedGeneralQuestionWithWebEnabledSkipsAdvisorAndGenericRetrieval() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var env=new MockEnvironment().withProperty("demo.jev.mode","on").withProperty("demo.jev.free-window-end","2999-01-01T00:00:00Z");
        var advisor=new JevDecisionAdvisor(env,java.time.Clock.systemUTC(),
            req->{count.incrementAndGet();return new JevDecisionAdvisor.EvalResponse(200,JevDecisionAdvisor.Verdict.WEB,null,null);},name->"fixture-key");
        ReflectionTestUtils.setField(adapter,"jevAdvisor",advisor);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("광합성은 빛으로 양분을 만드는 과정입니다.","recording",false));
        try{
            for(String id:List.of("gpt-5.6-luna","chatgpt-oauth:gpt-5.6-luna")){
                var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),
                    new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,id),7,400,false,true);
                adapter.answer(7L,"광합성이 뭐야?",memory);
                var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
                var context=org.mockito.ArgumentCaptor.forClass(com.example.lms.service.ChatConversationContext.class);
                verify(chat).continueChat(request.capture(),isNull(),context.capture());
                assertEquals(0,count.get(),"a fixed general answer must not await an auxiliary model");
                assertEquals(id,request.getValue().getModel());assertTrue(request.getValue().isStrictModelSelection());
                assertFalse(request.getValue().isUseWebSearch());assertFalse(request.getValue().isUseRag());
                assertTrue(context.getValue().focusGoogleSearchAllowed(),"preserve the explicit native-search permission contract");
                clearInvocations(chat);
            }
        }finally{advisor.close();ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void defaultPolicyKeepsGeneralQuestionLocalAndUnpinned() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("합성 응답","recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            assertEquals("합성 응답",adapter.answer(7L,"광합성이 뭐야?",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertFalse(request.getValue().isUseWebSearch());assertNull(request.getValue().getModel());
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void webAnswerIsCompactedForTheLensWithASourceSuffix() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);
        var longAnswer="## 요약\n- [공지](https://example.com/a) 기준 **중요** 내용입니다. "
            +"가나다라마바사아자차카타파하".repeat(25)+("\n짧은 줄").repeat(10);
        var meta=new com.example.lms.dto.RagEvidenceMetadata("W1","web","공지","https://www.naver.com/news/1",
            null,null,null,1,0.9,"provider");
        var meta2=new com.example.lms.dto.RagEvidenceMetadata("W2","web","검색","https://google.com/search/2",
            null,null,null,2,0.8,"provider");
        when(chat.continueChat(any(),isNull(),any()))
            .thenReturn(ChatResult.of(longAnswer,"recording",false,Set.of(),List.of(meta,meta2)));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            var answer=adapter.answer(7L,"공식 자료를 찾아 확인해줘",memory);
            assertFalse(answer.contains("표시 길이를 넘었습니다"));assertFalse(answer.contains("전체 답변은 Fold에서"));
            assertTrue(answer.contains("가나다라마바사아자차카타파하"),"overflow keeps the generated answer instead of a notice");
            assertTrue(answer.lines().count()<=6,"lens answer stays inside the six-line budget");
            assertFalse(answer.contains("##"));assertFalse(answer.contains("]("));
            assertTrue(answer.contains("[출처:"));assertTrue(answer.contains("naver.com"));
            clearInvocations(chat);
            // Evidence가 없으면 접미사도 없다; 길이 초과도 안내문이 아닌 원문이 유지된다.
            when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of(longAnswer,"recording",false));
            answer=adapter.answer(8L,"공식 자료를 다시 확인해줘",memory);
            assertTrue(answer.contains("가나다라마바사아자차카타파하"));
            assertFalse(answer.contains("표시 길이를 넘었습니다"));
            assertTrue(answer.lines().count()<=6);
            assertFalse(answer.contains("[출처:"));
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void overBudgetDisplayKeepsTheGeneratedAnswerInsteadOfANotice(){
        // 길이 초과 답변은 원문이 보존된다 — 안경 표시는 receiver.js 페이징과 생성 시 길이 설정이 담당한다.
        String longText="가나다라마바사아자차카타파하".repeat(20);
        assertEquals(longText,NovaFocusAnswerService.boundDisplay(longText,10));
        assertEquals("a\nb",NovaFocusAnswerService.boundDisplay("a\r\nb",1));
        assertNull(NovaFocusAnswerService.boundDisplay(null,5));
        assertFalse(NovaFocusAnswerService.boundDisplay(longText,10).contains("Fold에서 확인"));
    }
    @Test void nonWebAnswersAreNotCompacted() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);
        var longAnswer="가나다라마바사아자차카타파하".repeat(40);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of(longAnswer,"recording",false));
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        try{
            assertEquals(longAnswer,adapter.answer(7L,"아까 정한 이름은?",memory));
            verify(chat,times(1)).continueChat(any(),isNull(),any());
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void autoRoutingPrefersConfiguredFocusDefaultModelWhenSelectable() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        ReflectionTestUtils.setField(adapter,"focusDefaultModel","llmrouter.gemini-pro");
        ReflectionTestUtils.setField(adapter,"defaultModel","gemma4:26b");
        var catalog=mock(ChatModelCatalogService.class);
        when(catalog.resolve("llmrouter.gemini-pro")).thenReturn(Optional.of(
            new ChatModelCatalogService.Choice("llmrouter.gemini-pro","gemini","gemini-pro","gemini-3.8-flash",
                "configured",true,"","release","server_catalog")));
        when(catalog.resolve("gemma4:26b")).thenReturn(Optional.of(
            new ChatModelCatalogService.Choice("gemma4:26b","Ollama","local-default","gemma4:26b",
                "configured",true,"","release","server_catalog")));
        ReflectionTestUtils.setField(adapter,"modelCatalog",catalog);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("합성 응답","gemini-3.8-flash",false));
        var routing=new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.AUTO,false,List.of());
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),
            new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.AUTO,null,routing),0);
        try{
            assertEquals("합성 응답",adapter.answer(7L,"아까 정한 이름은?",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertEquals("llmrouter.gemini-pro",request.getValue().getModel());
            assertTrue(request.getValue().isStrictModelSelection());
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void autoRoutingFallsBackToLocalDefaultWhenFocusDefaultUnavailable() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        ReflectionTestUtils.setField(adapter,"defaultModel","gemma4:26b");
        var catalog=mock(ChatModelCatalogService.class);
        when(catalog.resolve("gemma4:26b")).thenReturn(Optional.of(
            new ChatModelCatalogService.Choice("gemma4:26b","Ollama","local-default","gemma4:26b",
                "configured",true,"","release","server_catalog")));
        ReflectionTestUtils.setField(adapter,"modelCatalog",catalog);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("합성 응답","gemma4:26b",false));
        var routing=new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.AUTO,false,List.of());
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),
            new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.AUTO,null,routing),0);
        try{
            // 알 수 없는 라우트 아이디: 카탈로그 미등록이면 로컬 기본값이 이어 받는다.
            ReflectionTestUtils.setField(adapter,"focusDefaultModel","llmrouter.ghost");
            assertEquals("합성 응답",adapter.answer(7L,"아까 정한 이름은?",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertEquals("gemma4:26b",request.getValue().getModel());
            clearInvocations(chat);
            // 선택 불가 라우트(자격·허용목록 미충족)도 우선순위를 얻지 못한다.
            ReflectionTestUtils.setField(adapter,"focusDefaultModel","llmrouter.gemini-pro");
            when(catalog.resolve("llmrouter.gemini-pro")).thenReturn(Optional.of(
                new ChatModelCatalogService.Choice("llmrouter.gemini-pro","gemini","gemini-pro","gemini-3.8-flash",
                    "remote_selection_disabled",false,"remote_selection_disabled","release","server_catalog")));
            assertEquals("합성 응답",adapter.answer(7L,"아까 정한 이름은?",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertEquals("gemma4:26b",request.getValue().getModel());
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void fixedModeIgnoresFocusDefaultModel() throws Exception{
        var chat=mock(ChatService.class);var budgets=mock(PublicRequestBudgetGuard.class);var runs=new ChatRunRegistry();
        ReflectionTestUtils.setField(runs,"replayCapacity",32);ReflectionTestUtils.setField(runs,"ttlSeconds",60);
        var adapter=new NovaFocusAnswerService(chat,budgets,runs);var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
        ReflectionTestUtils.setField(adapter,"focusDefaultModel","llmrouter.gemini-pro");
        var catalog=mock(ChatModelCatalogService.class);
        when(catalog.resolve("pinned-model")).thenReturn(Optional.of(
            new ChatModelCatalogService.Choice("pinned-model","Ollama","local-default","pinned-model",
                "configured",true,"","release","server_catalog")));
        ReflectionTestUtils.setField(adapter,"modelCatalog",catalog);
        when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("합성 응답","pinned-model",false));
        var routing=new NovaFocusSettings.Routing(NovaFocusSettings.ExecutionTarget.AUTO,false,List.of());
        var memory=new NovaFocusHistoryService.Context(List.of(),"",List.of(),List.of(),
            new NovaFocusSettings.AnswerSelection(NovaFocusSettings.AnswerSelection.Mode.FIXED,"pinned-model",routing),0);
        try{
            assertEquals("합성 응답",adapter.answer(7L,"아까 정한 이름은?",memory));
            verify(chat).continueChat(request.capture(),isNull(),any());
            assertEquals("pinned-model",request.getValue().getModel());
        }finally{ReflectionTestUtils.invokeMethod(runs,"shutdown");}
    }
    @Test void serverDefaultEnabledArmsNewOwnersOnlyWhenFlagged() throws Exception{
        var history=new NovaFocusHistoryService(mock(org.springframework.transaction.PlatformTransactionManager.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),mock(com.example.lms.service.ChatHistoryService.class));
        NovaFocusSettings off=ReflectionTestUtils.invokeMethod(history,"serverDefaults");
        assertFalse(off.enabled());
        ReflectionTestUtils.setField(history,"defaultEnabled",true);
        NovaFocusSettings on=ReflectionTestUtils.invokeMethod(history,"serverDefaults");
        assertTrue(on.enabled());
        assertFalse(on.recallEnabled());assertFalse(on.rememberFactsEnabled());
    }
}
