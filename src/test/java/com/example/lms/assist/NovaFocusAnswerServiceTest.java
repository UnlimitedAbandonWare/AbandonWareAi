package com.example.lms.assist;

import com.example.lms.api.PublicRequestBudgetGuard;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.*;
import com.example.lms.service.chat.*;
import org.junit.jupiter.api.Test;
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
}
