package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 확정된 질문당 단발 촬영 계약: 설정 OFF 0회, 확정 1건 1장, 중복 0회, 지연 폐기, 권한 없는 명령 불가. */
class NovaFocusSnapshotStateTest {
    static NovaFocusSettings settings(boolean on,String source){
        var d=NovaFocusSettings.defaults();
        return new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation(),false,false,new NovaFocusSettings.Snapshot(on,source));
    }
    static NovaFocusState state(boolean on){return state(on,"FOLD_REAR");}
    static NovaFocusState state(boolean on,String source){return new NovaFocusState("server",settings(on,source));}
    ConversateQuestionPolicy.Utterance u(String id,String text){return new ConversateQuestionPolicy.Utterance(id,id,0,true,text);}

    @Test void disabledSettingProducesTextOnlyRequest(){
        var s=state(false);s.input(u("a","노바 질문"),0);
        var r=s.tick(1200);assertNotNull(r);assertNull(r.imageBase64());assertNull(r.imageMediaType());
        assertNull(s.pendingCommand(1200,1));assertEquals("THINKING",s.view(1200).phase());
    }
    @Test void confirmedQuestionIssuesOneCommandAndHoldsGeneration(){
        var s=state(true);s.input(u("a","노바 질문"),0);
        assertNull(s.tick(1200));assertEquals("SNAPSHOT",s.view(1200).phase());
        var cmd=s.pendingCommand(1200,7);assertNotNull(cmd);assertEquals("snapshot",cmd.kind());
        assertEquals("server",cmd.serverInstanceId());assertEquals("FOLD_REAR",cmd.source());
        assertFalse(cmd.claimed());assertTrue(cmd.expiresInMs()>0&&cmd.expiresInMs()<=NovaFocusState.SNAPSHOT_TIMEOUT_MS);
        assertEquals(7,cmd.settingsVersion());
        var again=s.pendingCommand(1300,7);assertEquals(cmd.captureId(),again.captureId());assertNull(s.tick(1300));
        assertEquals(NovaFocusState.CLAIM_GRANTED,s.claimSnapshot(cmd.requestId(),cmd.captureId(),1300));
        assertEquals(NovaFocusState.SNAPSHOT_ACCEPTED,s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1400));
        var request=s.tick(1400);assertNotNull(request);assertEquals("QUJD",request.imageBase64());assertEquals("image/jpeg",request.imageMediaType());
        assertEquals("질문",request.question());assertNull(s.pendingCommand(1400,7));
    }
    @Test void wakeOnlyAndInterimNeverCapture(){
        var s=state(true);s.input(u("a","노바"),0);
        assertNull(s.tick(4000));assertNull(s.pendingCommand(4000,1));
        s.input(new ConversateQuestionPolicy.Utterance("a","a",1,false,"노바"),4100);
        assertNull(s.pendingCommand(5000,1));
        s.input(new ConversateQuestionPolicy.Utterance("a","a",2,true,"노바"),5100);
        assertNull(s.tick(6400));assertNull(s.pendingCommand(6400,1));
    }
    @Test void duplicateResultIsIdempotentAndDoesNotRegenerate(){
        var s=state(true);s.input(u("a","노바 질문"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);
        s.claimSnapshot(cmd.requestId(),cmd.captureId(),1250);
        assertEquals(NovaFocusState.SNAPSHOT_ACCEPTED,s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1300));
        assertEquals(NovaFocusState.SNAPSHOT_DUPLICATE,s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1400));
        var request=s.tick(1400);assertNotNull(request);assertEquals("QUJD",request.imageBase64());
        assertNull(s.tick(1500));
        assertEquals(1,s.diagnostics().get("capturesCompleted"));assertEquals(1,s.diagnostics().get("duplicateSuppressed"));
    }
    @Test void conflictingImageForSameCaptureIsRejected(){
        var s=state(true);s.input(u("a","노바 질문"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);
        s.claimSnapshot(cmd.requestId(),cmd.captureId(),1250);
        s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1300);
        assertThrows(IllegalArgumentException.class,()->s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"REVG","image/jpeg",1400));
    }
    @Test void timeoutPreservesQuestionAndRejectsLatePhoto(){
        var s=state(true);s.input(u("a","노바 질문"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);
        // 촬영 마감이 지나면 같은 질문이 사진 없이 한 번 발행된다 — 질문은 버려지지 않는다.
        var request=s.tick(1200+NovaFocusState.SNAPSHOT_TIMEOUT_MS);
        assertNotNull(request);assertNull(request.imageBase64());assertEquals("질문",request.question());
        assertEquals("THINKING",s.view(16300).phase());assertEquals("snapshot_timeout",s.view(16300).reason());
        assertEquals(NovaFocusState.SNAPSHOT_STALE,s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",16300));
        s.accepted(request,"t1");s.answer(request,"t1","답변","tk",16400);
        s.receipt("server",request.activationId(),"t1",1,"tk","first_visible",16500);
        s.receipt("server",request.activationId(),"t1",1,"tk","presentation_done",17000);
        s.input(u("b","다음 질문"),17100);assertNull(s.tick(18300));
        // 음성 후속 질문은 답변 유지시간(tailHold)이 끝난 뒤에만 발행된다 — 17000+5000.
        assertNull(s.tick(22000));
        var next=s.pendingCommand(22000,1);assertNotNull(next);assertNotEquals(cmd.captureId(),next.captureId());
        assertEquals(NovaFocusState.SNAPSHOT_STALE,s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",22100));
    }
    @Test void claimIsIdempotentAndScopedToPendingCapture(){
        var s=state(true);s.input(u("a","노바 질문"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);
        assertEquals(NovaFocusState.CLAIM_REJECTED,s.claimSnapshot(cmd.requestId(),"wrong-capture",1200));
        assertEquals(NovaFocusState.CLAIM_REJECTED,s.claimSnapshot("wrong-request",cmd.captureId(),1200));
        // claim 전에 도착한 결과는 거부된다.
        assertEquals(NovaFocusState.SNAPSHOT_STALE,s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1250));
        assertEquals(NovaFocusState.CLAIM_GRANTED,s.claimSnapshot(cmd.requestId(),cmd.captureId(),1250));
        assertEquals(NovaFocusState.CLAIM_JOINED,s.claimSnapshot(cmd.requestId(),cmd.captureId(),1300));
        assertTrue(s.pendingCommand(1300,1).claimed());
        s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1400);
        assertEquals(NovaFocusState.CLAIM_REJECTED,s.claimSnapshot(cmd.requestId(),cmd.captureId(),1400));
    }
    @Test void claimAfterServerDeadlineIsRejected(){
        var s=state(true);s.input(u("a","노바 질문"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);
        assertEquals(NovaFocusState.CLAIM_REJECTED,s.claimSnapshot(cmd.requestId(),cmd.captureId(),1200+NovaFocusState.SNAPSHOT_TIMEOUT_MS));
    }
    @Test void disableDuringPendingPromotesTextOnlyAndDropsLateImage(){
        var s=state(true);s.input(u("a","노바 질문"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);
        s.configure(settings(false,"FOLD_REAR"),1300);
        assertNull(s.pendingCommand(1300,1));
        var request=s.tick(1300);assertNotNull(request);assertNull(request.imageBase64());
        assertEquals(NovaFocusState.SNAPSHOT_STALE,s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1400));
    }
    @Test void acceptedImageIsDroppedWhenSettingTurnsOff(){
        var s=state(true);s.input(u("a","노바 질문"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);
        s.claimSnapshot(cmd.requestId(),cmd.captureId(),1250);
        assertEquals(NovaFocusState.SNAPSHOT_ACCEPTED,s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1300));
        // 수락 뒤 OFF여도 미전송 이미지는 버리고 같은 질문을 사진 없이 진행한다.
        s.configure(settings(false,"FOLD_REAR"),1350);
        var request=s.tick(1350);assertNotNull(request);assertNull(request.imageBase64());assertNull(request.imageMediaType());
    }
    @Test void sourceChangeInvalidatesCaptureAndContinuesTextOnly(){
        var s=state(true);s.input(u("a","노바 질문"),0);s.tick(1200);
        var first=s.pendingCommand(1200,1);assertEquals("FOLD_REAR",first.source());
        s.configure(settings(true,"META_GLASSES"),1300);
        // 장치 변경은 이전 촬영을 무효화하지만 자동 재촬영은 없다 — 같은 질문이 사진 없이 진행된다.
        assertNull(s.pendingCommand(1300,1));
        var request=s.tick(1300);assertNotNull(request);assertNull(request.imageBase64());assertEquals("질문",request.question());
        assertEquals(NovaFocusState.CLAIM_REJECTED,s.claimSnapshot(first.requestId(),first.captureId(),1300));
        assertEquals(NovaFocusState.SNAPSHOT_STALE,s.acceptSnapshot(first.requestId(),first.captureId(),"QUJD","image/jpeg",1400));
    }
    @Test void typedQuestionAlsoGoesThroughSnapshot(){
        var s=state(true);s.open(0,"fold");s.typed("r1","질문",0);
        assertNull(s.tick(1200));assertEquals("SNAPSHOT",s.view(1200).phase());
        var cmd=s.pendingCommand(1200,1);assertNotNull(cmd);
        s.claimSnapshot(cmd.requestId(),cmd.captureId(),1250);
        s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/png",1300);
        var request=s.tick(1300);assertNotNull(request);assertEquals("image/png",request.imageMediaType());
    }
    @Test void closeInvalidatesPendingCapture(){
        var s=state(true);s.input(u("a","노바 질문"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);s.close("user_closed");
        assertNull(s.pendingCommand(1300,1));
        assertEquals(NovaFocusState.SNAPSHOT_STALE,s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1300));
        assertEquals(NovaFocusState.CLAIM_REJECTED,s.claimSnapshot(cmd.requestId(),cmd.captureId(),1300));
    }
    @Test void failReportFromProducerPreservesQuestion(){
        var s=state(true);s.input(u("a","노바 질문"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);
        assertTrue(s.failSnapshot(cmd.requestId(),cmd.captureId(),"permission_denied",1300));
        assertEquals("permission_denied",s.view(1300).reason());assertEquals("질문",s.view(1300).questionText());
        // 실패 보고 뒤 같은 질문이 사진 없이 한 번 발행된다.
        var request=s.tick(1300);assertNotNull(request);assertNull(request.imageBase64());assertEquals("질문",request.question());
        assertEquals("THINKING",s.view(1300).phase());
        assertFalse(s.failSnapshot(cmd.requestId(),cmd.captureId(),"permission_denied",1400));
    }
}
