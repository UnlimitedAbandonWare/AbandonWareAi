package com.example.lms.assist;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversateQuestionPolicyTest {
    @org.junit.jupiter.api.Test void changedQuantityOrNegationWithNewUtteranceIdInvalidatesPreviousQuestion(){
        var p=new ConversateQuestionPolicy();
        org.junit.jupiter.api.Assertions.assertEquals("QUESTION",p.accept(new ConversateQuestionPolicy.Utterance("a","a",1,true,"가격은 100만원인가요?")).kind());
        org.junit.jupiter.api.Assertions.assertEquals("BACKCHANNEL",p.accept(new ConversateQuestionPolicy.Utterance("b","b",1,true,"아, 네")).kind());
        org.junit.jupiter.api.Assertions.assertEquals("CORRECTION",p.accept(new ConversateQuestionPolicy.Utterance("c","c",1,true,"가격은 100원인가요?")).kind());
        org.junit.jupiter.api.Assertions.assertEquals("QUESTION",p.accept(new ConversateQuestionPolicy.Utterance("d","d",1,true,"침수 손상은 보증하나요?")).kind());
        org.junit.jupiter.api.Assertions.assertEquals("CORRECTION",p.accept(new ConversateQuestionPolicy.Utterance("e","e",1,true,"침수 손상은 보증하지 않나요?")).kind());
        org.junit.jupiter.api.Assertions.assertEquals("QUESTION",p.accept(new ConversateQuestionPolicy.Utterance("f","f",1,true,"안전 검사는 언제인가요?")).kind());
    }
    private ConversateQuestionPolicy.Utterance u(String id,int revision,boolean fin,String text){return new ConversateQuestionPolicy.Utterance(id,id,revision,fin,text);}
    @Test void partialFinalDuplicateBackchannelAndCorrectionHaveDifferentCosts(){
        var p=new ConversateQuestionPolicy();
        assertEquals("PARTIAL",p.accept(u("a",0,false,"보증 기간은")).kind());
        assertEquals("QUESTION",p.accept(u("a",1,true,"보증 기간은 2년인가요?")).kind());
        assertEquals("DUPLICATE",p.accept(u("a",1,true,"보증 기간은 2년인가요?")).kind());
        assertEquals("BACKCHANNEL",p.accept(u("b",0,true,"아, 네")).kind());
        assertEquals("CORRECTION",p.accept(u("a",2,true,"보증 기간은 3년이 아닌가요?")).kind());
        assertEquals("STALE",p.accept(u("a",1,true,"보증 기간은 2년인가요?")).kind());
    }
    @Test void sameRevisionDifferentFinalTextIsConflictAndStateIsBounded(){
        var p=new ConversateQuestionPolicy();p.accept(u("a",1,true,"언제 시작하나요?"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->p.accept(u("a",1,true,"언제 끝나나요?")));
        for(int i=0;i<200;i++)p.accept(u("id"+i,0,true,"아, 네"));assertTrue(p.retainedCount()<=64);
        p.clear();assertEquals(0,p.retainedCount());
    }
    @Test void resolvedAndNewInformationDoNotAutomaticallySearch(){
        var p=new ConversateQuestionPolicy();assertEquals("RESOLVED",p.accept(u("a",0,true,"이 질문은 해결됐습니다.")).kind());
        assertEquals("NEW_INFORMATION",p.accept(u("b",0,true,"회의가 내일 시작합니다.")).kind());
    }
}
