package com.example.lms.assist;

import com.example.lms.api.PublicRequestBudgetGuard;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NovaFocusJevAdmissionTest {
    static final class Fixture implements AutoCloseable {
        final ChatService chat=mock(ChatService.class);
        final PublicRequestBudgetGuard budgets=mock(PublicRequestBudgetGuard.class);
        final ChatRunRegistry runs=new ChatRunRegistry();
        final FocusMemoryService memories=mock(FocusMemoryService.class);
        final JevDecisionAdvisor advisor=mock(JevDecisionAdvisor.class);
        final NovaFocusAnswerService service=new NovaFocusAnswerService(chat,budgets,runs,memories);
        final FocusMemoryScope scope=new FocusMemoryScope("a".repeat(64),1,1,1,true);
        final NovaFocusHistoryService.Context memory=new NovaFocusHistoryService.Context(List.of(),"",List.of());
        Fixture(){
            ReflectionTestUtils.setField(runs,"replayCapacity",32);
            ReflectionTestUtils.setField(runs,"ttlSeconds",60);
            ReflectionTestUtils.setField(service,"jevAdvisor",advisor);
            verdict(JevDecisionAdvisor.Verdict.RECENT_ONLY);
            when(memories.retrieve(any(),anyString(),any())).thenReturn(
                FocusMemoryService.Result.empty(FocusMemoryService.Status.OK,"fixture"));
            when(memories.valid(any(),anyList())).thenReturn(true);
            when(chat.continueChat(any(),isNull(),any())).thenReturn(ChatResult.of("fixture answer","fixture",false));
        }
        void verdict(JevDecisionAdvisor.Verdict verdict){
            when(advisor.advise(anyString(),anyString(),anyString())).thenReturn(JevDecisionAdvisor.Advice.of(verdict,"on",1));
        }
        String answer(){return service.answer(77L,"공식 자료를 찾아 줘",memory,scope,()->true);}
        public void close(){ReflectionTestUtils.invokeMethod(runs,"shutdown");TimeBudgetContext.clear();}
    }
    @Test void closedRequestNeverEvaluates(){
        try(var f=new Fixture()){
            assertThrows(CancellationException.class,()->f.service.answer(77L,"질문",f.memory,f.scope,()->false));
            verify(f.advisor,never()).advise(anyString(),anyString(),anyString());verifyNoInteractions(f.memories,f.chat);
        }
    }
    @Test void rejectedAdmissionNeverEvaluates(){
        try(var f=new Fixture()){
            doThrow(new IllegalArgumentException("fixture_budget")).when(f.budgets)
                .validateChatProjected(any(),any(),anyBoolean(),anyBoolean());
            assertThrows(IllegalArgumentException.class,f::answer);
            verify(f.advisor,never()).advise(anyString(),anyString(),anyString());verifyNoInteractions(f.chat);
        }
    }
    @Test void busyRoomNeverEvaluates(){
        try(var f=new Fixture()){
            var owned=f.runs.beginOrJoin(77L);
            try {assertThrows(IllegalStateException.class,f::answer);
                verify(f.advisor,never()).advise(anyString(),anyString(),anyString());
            } finally {f.runs.markDone(owned.context());}
        }
    }
    @Test void exhaustedParentDeadlineNeverEvaluates(){
        try(var f=new Fixture()){
            TimeBudgetContext.set(TimeBudget.untilNanoDeadline(System.nanoTime()-1));
            assertThrows(RuntimeException.class,f::answer);
            verify(f.advisor,never()).advise(anyString(),anyString(),anyString());
        }
    }
    @Test void imageBranchNeverEvaluates(){
        try(var f=new Fixture()){
            f.service.answer(77L,"사진을 설명해 줘","QUJD","image/jpeg",f.memory,f.scope,()->true);
            verify(f.advisor,never()).advise(anyString(),anyString(),anyString());
        }
    }
    @Test void retrievalVerdictsPreserveTheirDistinctScopes(){
        for(var verdict:List.of(JevDecisionAdvisor.Verdict.RECENT_ONLY,JevDecisionAdvisor.Verdict.WEB,JevDecisionAdvisor.Verdict.SCOPED_RAG)){
            try(var f=new Fixture()){
                f.verdict(verdict);assertEquals("fixture answer",f.answer());
                if(verdict==JevDecisionAdvisor.Verdict.SCOPED_RAG)verify(f.memories).retrieve(eq(f.scope),anyString(),any());
                else verify(f.memories,never()).retrieve(any(),anyString(),any());
                var request=org.mockito.ArgumentCaptor.forClass(ChatRequestDto.class);
                verify(f.chat).continueChat(request.capture(),isNull(),any());
                assertEquals(verdict==JevDecisionAdvisor.Verdict.WEB,request.getValue().isUseWebSearch());
            }
        }
    }
}
