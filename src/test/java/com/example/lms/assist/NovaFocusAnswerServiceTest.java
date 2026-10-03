package com.example.lms.assist;

import com.example.lms.api.PublicRequestBudgetGuard;
import com.example.lms.dto.ChatRequestDto;
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
}
