package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NovaFocusStateTest {
    NovaFocusState state(boolean enabled){var d=NovaFocusSettings.defaults();return new NovaFocusState("server",new NovaFocusSettings(enabled,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation()));}
    ConversateQuestionPolicy.Utterance u(String id,int revision,boolean fin,String text){return new ConversateQuestionPolicy.Utterance(id,id,revision,fin,text);}
    @Test void disabledVoiceDoesNotActivateButManualOpenWorks(){
        var s=state(false);assertFalse(s.input(u("a",0,true,"노바 설명해줘"),0));assertFalse(s.active());s.open(1,"fold");assertTrue(s.active());
        s.input(u("b",0,true,"두 번째 질문"),2);assertNotNull(s.tick(1202));
    }
    @Test void interimWakeCanRetractAndQuietNeverFinalizesInterim(){
        var s=state(true);s.input(u("a",0,false,"노바"),0);assertEquals("WAKE_PREVIEW",s.view(0).phase());
        s.input(u("a",1,false,"노바카인"),100);assertFalse(s.active());assertNull(s.tick(4000));
        s.input(u("b",0,false,"노바 원리를 설명해줘"),5000);assertNull(s.tick(7000));
        s.input(u("b",1,true,"노바 원리를 설명해줘"),7100);assertNull(s.tick(8200));assertEquals("원리를 설명해줘",s.tick(8300).question());
        assertNull(s.tick(9000));
    }
    @Test void finalCharacterReceiptStartsIdleOnceAndLongAnswersSurviveTwentySeconds(){
        var s=state(true);s.input(u("a",0,true,"노바 질문"),0);var r=s.tick(1200);s.accepted(r,"turn");s.answer(r,"turn","가".repeat(600),"receipt",2000);
        assertTrue(s.receipt("server",r.activationId(),"turn",1,"receipt","first_visible",2200));
        assertNull(s.tick(24000));assertTrue(s.active());assertEquals(0,s.view(24000).idleRemainingMs());
        assertTrue(s.receipt("server",r.activationId(),"turn",1,"receipt","presentation_done",51000));
        assertEquals(20000,s.view(51000).idleRemainingMs());
        s.receipt("server",r.activationId(),"turn",1,"receipt","presentation_done",60000);assertEquals(11000,s.view(60000).idleRemainingMs());
        s.tick(71000);assertFalse(s.active());
    }
    @Test void clippedProjectionPreservesUnicodeAndPointsToFullDurableHistory(){
        var s=state(false);s.open(0,"fold");s.typed("r","question",0);var request=s.tick(1200);s.accepted(request,"turn");
        s.answer(request,"turn","😀".repeat(8001),"ticket",1300);
        var view=s.view(1300);assertEquals(8000,view.answerText().codePointCount(0,view.answerText().length()));
        assertTrue(view.answerTruncated());assertTrue(view.hasMoreOnFold());assertTrue(view.forTarget("lens").hasMoreOnFold());
    }
    @Test void lateCompletionAndStaleReceiptsCannotReopen(){
        var s=state(true);s.input(u("a",0,true,"노바 질문"),0);var r=s.tick(1200);s.accepted(r,"turn");s.close("closed");
        s.answer(r,"turn","late","ticket",2000);assertFalse(s.active());assertFalse(s.receipt("server",r.activationId(),"turn",1,"ticket","presentation_done",3000));
        assertFalse(s.input(u("a",0,true,"노바 질문"),4000));assertFalse(s.active());
    }
    @Test void nextQuestionWaitsForPresentationAndNewPositionCanRepeatText(){
        var s=state(true);s.input(u("a",0,true,"노바 질문"),0);var r=s.tick(1200);s.accepted(r,"turn");
        s.input(u("b",0,false,"질문"),1500);s.input(u("b",1,true,"질문"),1600);assertNull(s.tick(3000));
        s.answer(r,"turn","답변","ticket",3100);s.receipt("server",r.activationId(),"turn",1,"ticket","first_visible",3200);
        s.receipt("server",r.activationId(),"turn",1,"ticket","presentation_done",4000);assertEquals("질문",s.tick(4001).question());
    }
    @Test void typedRequestIdentitySurvivesServerAndActivationChange(){
        var first=state(false);first.sourceNamespace("old-capture");first.open(0,"fold");first.typed("request-1","question",0);var a=first.tick(1200);
        var second=state(false);second.sourceNamespace("new-capture");second.open(0,"fold");second.typed("request-1","question",0);var b=second.tick(1200);
        assertEquals(a.requestId(),b.requestId());assertNotEquals(a.activationId(),b.activationId());
        assertEquals(NovaFocusState.typedRequestId("request-1"),a.requestId());
    }
    @Test void rejectedManualOverflowDoesNotConsumeItsRetryIdentity(){
        var s=state(false);s.open(0,"fold");s.typed("first","first question",0);
        var full=assertThrows(IllegalArgumentException.class,()->s.typed("retry","next question",10));
        assertEquals("focus_next_question_full",full.getMessage());
        var first=s.tick(1200);assertEquals("first question",first.question());s.accepted(first,"turn");
        s.typed("retry","next question",1300);assertEquals("next question",s.view(1300).draftText());
        s.answer(first,"turn","answer","ticket",1400);
        s.receipt("server",first.activationId(),"turn",1,"ticket","first_visible",1500);
        s.receipt("server",first.activationId(),"turn",1,"ticket","presentation_done",2600);
        var next=s.tick(2601);assertNotNull(next);assertEquals(NovaFocusState.typedRequestId("retry"),next.requestId());
    }
    @Test void completedNextQuestionIsBoundedAndLaterQuestionIsExplicitlyDeferred(){
        var s=state(true);s.input(u("a",0,true,"노바 질문"),0);var r=s.tick(1200);s.accepted(r,"turn");
        s.input(u("b",0,true,"다음 질문"),1300);s.input(u("c",0,true,"추가 질문"),2600);
        assertEquals("다음 질문",s.view(2600).draftText());assertEquals("focus_next_question_full",s.view(2600).reason());
        assertEquals(java.util.Set.of("active","phase","stateVersion","answerVersion","bufferedQuestions"),s.diagnostics().keySet());
        s.answer(r,"turn","답변","ticket",2700);s.receipt("server",r.activationId(),"turn",1,"ticket","first_visible",2800);
        s.receipt("server",r.activationId(),"turn",1,"ticket","presentation_done",3000);assertEquals("다음 질문",s.tick(3001).question());
    }
    @Test void sameCaptureFinalReplayIsStableButNewCaptureCanReuseAsrCounter(){
        var a=state(true);a.sourceNamespace("capture:1");a.input(u("asr-1",0,true,"노바 질문"),0);var first=a.tick(1200);
        var b=state(true);b.sourceNamespace("capture:1");b.input(u("asr-1",0,true,"노바 질문"),0);assertEquals(first.requestId(),b.tick(1200).requestId());
        var c=state(true);c.sourceNamespace("capture:2");c.input(u("asr-1",0,true,"노바 질문"),0);assertNotEquals(first.requestId(),c.tick(1200).requestId());
        a.close("capture_changed");a.sourceNamespace("capture:1");
        assertFalse(a.input(u("asr-1",0,true,"노바 질문"),2000));
        a.sourceNamespace("capture:2");assertTrue(a.input(u("asr-1",0,true,"노바 새로운 질문"),3000));
        var next=a.tick(4200);assertNotNull(next);assertNotEquals(first.requestId(),next.requestId());
        assertEquals("새로운 질문",next.question());
    }
}
