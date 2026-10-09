package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** WAKE_PREVIEW 호출 창 회귀: 말하는 중에는 8초 창이 미끄러지고, 멈추면 기존 데드라인이 유지된다. */
class NovaFocusWakeWindowTest {
    // T1 호출어+질문 한 발화: 부분 전사가 12초 동안 계속 바뀌면 창이 닫히지 않아야 한다.
    @Test void wakePreviewExtendsDeadlineWhilePartialKeepsChanging() {
        var s=state();
        s.input(u("t1",0,false,"노바"),0);
        assertEquals("WAKE_PREVIEW",s.view(0).phase());
        for(int sec=1;sec<=12;sec++){
            s.input(u("t1",sec,false,"노바 "+"가".repeat(sec)),sec*1000);
            assertNull(s.tick(sec*1000+1));
            assertTrue(s.active(),"closed at sec="+sec);
        }
        s.input(u("t1",13,true,"노바 "+"가".repeat(12)),13000);
        assertEquals("LISTENING",s.view(13000).phase());
        var request=s.tick(14201);
        assertNotNull(request);assertEquals("가".repeat(12),request.question());
    }
    // T2 짧은 질문: 기존 조용시간 1200ms 뒤 바로 접수.
    @Test void shortWakeQuestionAcceptedAfterQuiet() {
        var s=state();
        s.input(u("t2",0,true,"노바 지금 몇 시야"),0);
        assertEquals("LISTENING",s.view(0).phase());
        var request=s.tick(1200);
        assertNotNull(request);assertEquals("지금 몇 시야",request.question());
    }
    // T3 호출어 → 3초 쉼 → 3초짜리 질문.
    @Test void wakeWordThenQuestionAfterPauseAccepted() {
        var s=state();
        s.input(u("t3a",0,true,"노바"),0);
        assertEquals("LISTENING",s.view(0).phase());
        s.input(u("t3b",0,true,"지금 몇 시야"),3000);
        assertNull(s.tick(4199));
        var request=s.tick(4200);
        assertNotNull(request);assertEquals("지금 몇 시야",request.question());
    }
    // T4 호출어만 말하고 침묵: 기존 wake_no_question 유지.
    @Test void wakeOnlyStillClosesAsNoQuestion() {
        var s=state();
        s.input(u("t4",0,false,"노바"),0);
        s.input(u("t4",1,true,"노바"),400);
        assertEquals("LISTENING",s.view(400).phase());
        assertNull(s.tick(8400));
        assertFalse(s.active());assertEquals("wake_no_question",s.view(8400).reason());
    }
    // T5 부분 전사에서 호출어가 사라지면 wake_retracted 유지.
    @Test void retractedWakeStillClosesAsRetracted() {
        var s=state();
        s.input(u("t5",0,false,"노바"),0);
        assertEquals("WAKE_PREVIEW",s.view(0).phase());
        s.input(u("t5",1,false,"노바카인"),100);
        assertFalse(s.active());assertEquals("wake_retracted",s.view(100).reason());
    }
    // T6 부분 전사가 새 글자 없이 멈추면 기존 8초 데드라인 그대로 닫힌다.
    @Test void unchangedWakePartialStillClosesAtDeadline() {
        var s=state();
        s.input(u("t6",0,false,"노바 질문을"),0);
        assertEquals("WAKE_PREVIEW",s.view(0).phase());
        s.input(u("t6",1,false,"노바 질문을"),2000);
        s.input(u("t6",2,false,"노바 질문을"),5000);
        assertNull(s.tick(8000));
        assertFalse(s.active());assertEquals("wake_unconfirmed",s.view(8000).reason());
    }
    // T6-상한: 부분 전사가 계속 바뀌어도 전체 60초 상한을 넘지 않는다.
    @Test void continuouslyChangingWakePartialIsCappedAtSixtySeconds() {
        var s=state();
        s.input(u("cap",0,false,"노바"),0);
        for(int sec=1;sec<60;sec++){
            s.input(u("cap",sec,false,"노바 "+"가".repeat(sec)),sec*1000);
            assertNull(s.tick(sec*1000+1));
            assertTrue(s.active(),"closed at sec="+sec);
        }
        assertNull(s.tick(59999));assertTrue(s.active());
        assertNull(s.tick(60000));
        assertFalse(s.active());assertEquals("wake_unconfirmed",s.view(60000).reason());
    }
    NovaFocusState state(){return new NovaFocusState("server",new NovaFocusSettings(true,"노바",1200,20000,8000,NovaFocusSettings.Presentation.defaults()));}
    ConversateQuestionPolicy.Utterance u(String id,int revision,boolean fin,String text){return new ConversateQuestionPolicy.Utterance(id,id,revision,fin,text);}
}
