package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NovaFocusStayAliveTest {
    @Test void queuedFinalCorrectionCannotCreateTwoRequestsForOneSource(){
        var s=state();s.open(0,"fold");s.input(u("q1",0,true,"Q1"),0);var first=s.tick(1200);s.accepted(first,"t1");
        s.input(u("q2",0,true,"Q2"),1300);s.input(u("q3",0,true,"Q3"),2501);
        s.input(u("q2",1,true,"Q2 corrected"),2600);
        assertEquals("focus_question_already_queued",s.view(2600).reason());
        s.answer(first,"t1","A1","ticket",2700);var second=s.tick(3701);assertEquals("Q2",second.question());
        s.accepted(second,"t2");s.answer(second,"t2","A2","ticket",3702);
        var third=s.tick(3703);assertEquals("Q3",third.question());assertNotEquals(second.requestId(),third.requestId());
        s.accepted(third,"t3");s.answer(third,"t3","A3","ticket",3704);assertNull(s.tick(5000));
    }
    @Test void generationDeadlineCancelsRequestWhileKeepingFocusAndAllowsNextQuestion(){
        var s=state();s.open(0,"fold");s.input(u("q1",0,true,"Q1"),0);var first=s.tick(1200);s.accepted(first,"t1");
        assertNull(s.tick(91200));assertTrue(s.active());assertFalse(s.inFlight());assertEquals("generation_timeout",s.view(91200).reason());
        s.answer(first,"t1","late","old",91201);assertEquals("",s.view(91201).answerText());
        s.input(u("q2",0,true,"Q2"),91202);assertNotNull(s.tick(92402));
    }
    private NovaFocusState state(){var d=NovaFocusSettings.defaults();return new NovaFocusState("boot",new NovaFocusSettings(true,d.wakeWord(),1200,20000,12000,d.presentation()));}
    private ConversateQuestionPolicy.Utterance u(String id,int rev,boolean fin,String text){return new ConversateQuestionPolicy.Utterance(id,id,rev,fin,text);}
    @Test void receiptAnimationDoesNotDelayNextQuestionOrEraseCompletedPair(){
        var s=state();s.open(0,"fold");s.input(u("q1",0,true,"Q1"),0);
        var first=s.tick(1200);s.accepted(first,"t1");s.answer(first,"t1","A1","ticket",1300);
        s.input(u("q2",0,false,"Q2 partial"),1400);s.input(u("q2",1,true,"Q2"),2000);
        assertNull(s.tick(3199));var next=s.tick(3200);assertNotNull(next);assertEquals("Q2",next.question());
        assertEquals("A1",s.view(3200).answerText());assertEquals("Q1",s.view(3200).questionText());assertEquals("t1",s.view(3200).turnId());
        s.accepted(next,"t2");s.failed(next,"focus_answer_unavailable");
        assertTrue(s.active());assertEquals("A1",s.view(4000).answerText());assertEquals("Q1",s.view(4000).questionText());
    }
    @Test void silentReceiptAndIdleTimeoutKeepFocusAndAnswer(){
        var s=state();s.open(0,"lens");s.input(u("q1",0,true,"Q1"),0);var r=s.tick(1200);
        s.accepted(r,"t1");s.answer(r,"t1","A1","ticket",1300);s.tick(200000);
        assertTrue(s.active());assertEquals("A1",s.view(200000).answerText());
        s.input(u("q2",0,true,"Q2"),200001);assertNotNull(s.tick(201201));
    }
    @Test void quietFinalInterruptionShortQuestionAndNoFinalNeverAutoSubmit(){
        var s=state();s.open(0,"fold");s.input(u("q",0,true,"short"),0);assertNull(s.tick(1100));
        s.input(u("continued",0,false,"question continued"),1101);assertNull(s.tick(1800));
        s.input(u("continued",1,true,"three second question"),1800);assertNotNull(s.tick(3000));
        var unfinished=state();unfinished.open(0,"fold");unfinished.input(u("never",0,false,"no final"),100);
        assertNull(unfinished.tick(60101));assertTrue(unfinished.active());assertEquals("input_unconfirmed",unfinished.view(60101).reason());
    }
    @Test void onlyStandaloneFinalExitClosesAndLateResultCannotReopen(){
        var s=state();s.open(0,"fold");s.input(u("normal",0,true,"\uD074\uB9B0 \uCF54\uB4DC\uAC00 \uBB50\uC57C"),0);assertTrue(s.active());
        var r=s.tick(1200);s.accepted(r,"t1");
        s.input(u("exit",0,false,"\uD074\uB9B0"),1300);assertTrue(s.active());
        s.input(u("exit",1,true,"\uD074\uB9B0!"),1400);assertFalse(s.active());assertEquals("voice_exit",s.view(1400).reason());
        s.answer(r,"t1","late","ticket",1500);assertFalse(s.active());assertEquals("",s.view(1500).answerText());
    }
    @Test void boundedFinalQuestionsAreNotOverwrittenAndOverflowIsExplicit(){
        var s=state();s.open(0,"fold");s.input(u("q1",0,true,"Q1"),0);var first=s.tick(1200);s.accepted(first,"t1");
        s.input(u("q2",0,true,"Q2"),1300);s.input(u("q3",0,true,"Q3"),2501);
        s.input(u("q4",0,true,"Q4"),3702);s.input(u("overflow",0,true,"Q5"),4903);
        assertEquals(3,s.diagnostics().get("bufferedQuestions"));assertEquals("focus_next_question_full",s.view(5000).reason());
        s.answer(first,"t1","A1","ticket",5100);
        for(String q:new String[]{"Q2","Q3","Q4"}){var r=s.tick(6000);assertNotNull(r);assertEquals(q,r.question());s.accepted(r,q);s.answer(r,q,"A"+q,"ticket",6001);}
        assertNull(s.tick(7000));
    }
    @Test void transportSuspensionFencesOldReceiptAndLateAnswerThenResumesSamePair(){
        var s=state();s.open(0,"fold");s.input(u("q1",0,true,"Q1"),0);var first=s.tick(1200);s.accepted(first,"t1");s.answer(first,"t1","A1","old",1300);
        s.suspend("output_lost");assertTrue(s.active());assertFalse(s.receipt("boot",first.activationId(),"t1",1,"old","first_visible",1400));
        s.resume("new",31300);assertEquals("A1",s.view(31300).answerText());assertTrue(s.receipt("boot",first.activationId(),"t1",1,"new","first_visible",31301));
        s.input(u("q2",0,true,"Q2"),31302);var next=s.tick(32502);s.accepted(next,"t2");s.suspend("producer_reclaimed");
        s.answer(next,"t2","stale","old",33000);assertEquals("A1",s.view(33000).answerText());assertEquals("Q1",s.view(33000).questionText());
        s.close("user_closed");s.resume("late",34000);assertFalse(s.active());
    }
    @Test void customExitRoundTripsJsonAndUnicodeCommandCollisionsAreRejected() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var data=json.valueToTree(NovaFocusSettings.defaults());
        ((com.fasterxml.jackson.databind.node.ObjectNode)data).put("enabled",true).put("exitWord","finish");
        var settings=json.treeToValue(data,NovaFocusSettings.class);assertEquals("finish",json.readValue(json.writeValueAsString(settings),NovaFocusSettings.class).exitWord());
        var s=new NovaFocusState("boot",settings);s.open(0,"fold");s.input(u("exit",0,true,"Finish!"),1);assertFalse(s.active());
        for(String command:new String[]{settings.wakeWord(),settings.cameraWakeWordOrDefault(),"bad\ncontrol","abcdefghijklmnopq",""}){
            ((com.fasterxml.jackson.databind.node.ObjectNode)data).put("exitWord",command);assertThrows(Exception.class,()->json.treeToValue(data,NovaFocusSettings.class));
        }
    }
}
