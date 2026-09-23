package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NovaFocusInputTest {
    @Test void literalWordBoundariesAndOriginalSuffix(){
        for(String no:new String[]{"슈퍼노바","노바크","노바카인","노 바","x노바","노바_1"})assertTrue(NovaWakeMatcher.find(no,"노바").isEmpty(),no);
        assertEquals("",NovaWakeMatcher.find("노바","노바").orElseThrow().question());
        assertEquals("-5mg가 아니고 노바를 설명해줘 👨‍👩‍👧‍👦",NovaWakeMatcher.find("앞 이야기, 노바: -5mg가 아니고 노바를 설명해줘 👨‍👩‍👧‍👦","노바").orElseThrow().question());
        assertEquals("원문 가",NovaWakeMatcher.find("노바, 원문 가","노바").orElseThrow().question());
        assertEquals("질문",NovaWakeMatcher.find("a.b, 질문","a.b").orElseThrow().question());
        assertTrue(NovaWakeMatcher.find("axb 질문","a.b").isEmpty());
    }
    @Test void interimIsReplacementNeverQuietFinalAndTransportDuplicatesDoNotMoveDeadline(){
        var a=new NovaFocusTurnAssembler();a.update("u1","아까",false,0);a.update("u1","아까 말한 내용을 정리해줘",false,1000);
        assertEquals("아까 말한 내용을 정리해줘",a.text());assertFalse(a.finalReady(10000,1200));
        a.update("u1","아까 말한 내용을 정리해줘",true,1100);assertFalse(a.update("u1",a.text(),true,2000));
        assertFalse(a.finalReady(2299,1200));assertTrue(a.finalReady(2300,1200));
    }
    @Test void segmentsAreRetainedButUnfinishedSegmentBlocksSubmission(){
        var a=new NovaFocusTurnAssembler();a.update("a","첫 문장",true,0);a.update("b","둘째",false,200);
        assertFalse(a.finalReady(5000,1200));a.update("b","둘째 문장",true,300);assertEquals("첫 문장 둘째 문장",a.text());assertTrue(a.finalReady(1500,1200));
        a.clear();assertFalse(a.hasInput());a.update("c","가".repeat(8001),true,1600);assertTrue(a.overLimit());assertFalse(a.finalReady(6000,1200));
        assertEquals("",a.text());a.update("d","short",true,1700);assertEquals("",a.text());
    }
    @Test void sourceDeliveryDedupIsIndependentOfRepeatedQuestionContent(){
        var policy=new ConversateQuestionPolicy();
        var first=new ConversateQuestionPolicy.Utterance("u1","u1",1,true,"노바 같은 질문");
        assertEquals("CUE_PENDING",policy.acceptForCue(first).kind());assertEquals("DUPLICATE",policy.acceptForCue(first).kind());
        assertEquals("CUE_PENDING",policy.acceptForCue(new ConversateQuestionPolicy.Utterance("u2","u2",1,true,first.text())).kind());
        assertEquals("STALE",policy.acceptForCue(new ConversateQuestionPolicy.Utterance("u1","u1",0,false,"노바")).kind());
    }
    @Test void settingsAreSeparateValidatedAndVoiceOffByDefault(){
        var s=NovaFocusSettings.defaults();assertFalse(s.enabled());assertEquals(1200,s.utteranceQuietMs());assertEquals(20000,s.followupIdleMs());assertEquals(80,s.presentation().charIntervalMs());
        assertThrows(IllegalArgumentException.class,()->new NovaFocusSettings(true,"",1200,20000,8000,null));
        assertThrows(IllegalArgumentException.class,()->new NovaFocusSettings.Presentation(true,1,6,true,5000,400));
    }
}
