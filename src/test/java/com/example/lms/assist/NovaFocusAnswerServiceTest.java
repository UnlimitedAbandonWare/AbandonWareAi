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
}
