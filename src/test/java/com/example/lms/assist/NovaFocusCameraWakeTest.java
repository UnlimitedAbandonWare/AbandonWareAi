package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 두 번째 호출어(사진 호출어, 기본 "데빈") 계약: 그 질문 1건만 FOLD_REAR 1장, 노바 불변, cameraAllowed 최우선. */
class NovaFocusCameraWakeTest {
    static NovaFocusSettings settings(boolean snapshotOn,String source,boolean cameraAllowed){
        var d=NovaFocusSettings.defaults();
        return new NovaFocusSettings(true,d.wakeWord(),d.utteranceQuietMs(),d.followupIdleMs(),d.wakeListenTimeoutMs(),d.presentation(),false,false,new NovaFocusSettings.Snapshot(snapshotOn,source,cameraAllowed),d.cameraWakeWord());
    }
    static NovaFocusState state(boolean snapshotOn){return new NovaFocusState("server",settings(snapshotOn,"FOLD_REAR",true));}
    ConversateQuestionPolicy.Utterance u(String id,String text){return new ConversateQuestionPolicy.Utterance(id,id,0,true,text);}

    // a) 노바 + 자동 OFF → 촬영 0, THINKING (기존 동작 불변)
    @Test void novaWithAutoOffCapturesNothing(){
        var s=state(false);s.input(u("a","노바 질문"),0);
        var r=s.tick(1200);assertNotNull(r);assertNull(r.imageBase64());
        assertNull(s.pendingCommand(1200,1));assertEquals("THINKING",s.view(1200).phase());
        assertEquals("none",s.diagnostics().get("snapshot.trigger"));
    }
    // b) 데빈 + 자동 OFF → SNAPSHOT 1회, 질문+사진 전달, 소스는 FOLD_REAR 고정
    @Test void cameraWakeCapturesOnceWithAutoOff(){
        var s=state(false);s.input(u("a","데빈 이게 뭐야"),0);
        assertNull(s.tick(1200));assertEquals("SNAPSHOT",s.view(1200).phase());
        var cmd=s.pendingCommand(1200,1);assertNotNull(cmd);assertEquals("FOLD_REAR",cmd.source());
        assertEquals("camera_wake",s.diagnostics().get("snapshot.trigger"));
        assertEquals(NovaFocusState.CLAIM_GRANTED,s.claimSnapshot(cmd.requestId(),cmd.captureId(),1250));
        assertEquals(NovaFocusState.SNAPSHOT_ACCEPTED,s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1300));
        var r=s.tick(1300);assertNotNull(r);assertEquals("이게 뭐야",r.question());assertEquals("QUJD",r.imageBase64());
        assertEquals("captured",s.diagnostics().get("snapshot.outcome"));
    }
    // b-보강) 자동 설정이 META_GLASSES여도 데빈 호출은 FOLD_REAR로 고정
    @Test void cameraWakePinsFoldRearEvenWhenGlassesConfigured(){
        var s=new NovaFocusState("server",settings(true,"META_GLASSES",true));
        s.input(u("a","데빈 질문"),0);assertNull(s.tick(1200));
        var cmd=s.pendingCommand(1200,1);assertNotNull(cmd);assertEquals("FOLD_REAR",cmd.source());
    }
    // c) 데빈 활성화의 후속 질문 → 촬영 0
    @Test void followupInCameraActivationDoesNotRecapture(){
        var s=state(false);s.input(u("a","데빈 첫 질문"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);
        s.claimSnapshot(cmd.requestId(),cmd.captureId(),1250);
        s.acceptSnapshot(cmd.requestId(),cmd.captureId(),"QUJD","image/jpeg",1300);
        var first=s.tick(1300);assertNotNull(first);
        s.accepted(first,"t1");s.answer(first,"t1","답변","tk",1400);
        s.receipt("server",first.activationId(),"t1",1,"tk","first_visible",1500);
        s.receipt("server",first.activationId(),"t1",1,"tk","presentation_done",1600);
        // 같은 활성화의 후속 질문은 답변 유지시간(tailHold)이 끝난 뒤 사진 없이 발행된다.
        s.input(u("b","두 번째 질문"),1700);
        assertNull(s.tick(2900));
        var second=s.tick(6600);assertNotNull(second);assertNull(second.imageBase64());
        assertNull(s.pendingCommand(6600,1));assertEquals("none",s.diagnostics().get("snapshot.trigger"));
    }
    // d) cameraAllowed=false + 데빈 → 촬영 0, 정상 답변
    @Test void cameraDeniedSuppressesCameraWake(){
        var s=new NovaFocusState("server",settings(false,"FOLD_REAR",false));
        s.input(u("a","데빈 질문"),0);
        var r=s.tick(1200);assertNotNull(r);assertNull(r.imageBase64());assertEquals("질문",r.question());
        assertNull(s.pendingCommand(1200,1));assertEquals("THINKING",s.view(1200).phase());
    }
    // e) 데빈 + 촬영 실패 → 사진 없이 답변 1건, 질문 유실 0
    @Test void cameraWakeFailurePreservesQuestion(){
        var s=state(false);s.input(u("a","데빈 이게 뭐야"),0);s.tick(1200);
        var cmd=s.pendingCommand(1200,1);assertNotNull(cmd);
        assertTrue(s.failSnapshot(cmd.requestId(),cmd.captureId(),"permission_denied",1300));
        assertEquals("permission_denied",s.diagnostics().get("snapshot.outcome"));
        var r=s.tick(1300);assertNotNull(r);assertNull(r.imageBase64());assertEquals("이게 뭐야",r.question());
        assertEquals("THINKING",s.view(1300).phase());
        // 시간 초과 경로도 동일하게 질문을 보존한다.
        var s2=state(false);s2.input(u("a","데빈 다른 질문"),0);s2.tick(1200);
        var r2=s2.tick(1200+NovaFocusState.SNAPSHOT_TIMEOUT_MS);
        assertNotNull(r2);assertNull(r2.imageBase64());assertEquals("다른 질문",r2.question());
    }
    // f) 문장 중간 "데빈"은 기존 매처 규칙(조사 붙음 거부) 그대로 — "데빈한테"는 호출어가 아니다
    @Test void midSentenceCameraWakeFollowsExistingMatcherRules(){
        var s=state(false);assertFalse(s.input(u("a","어제 데빈한테 물어봤어"),0));
        assertFalse(s.active());assertEquals("ARMED",s.view(0).phase());
        // 단독 토큰으로 문장 중간에 오면 호출어로 인정되고 뒤 텍스트가 질문이다.
        var s2=state(false);assertTrue(s2.input(u("a","어제 데빈 물어봤어"),0));
        assertEquals("CAMERA",s2.diagnostics().get("wakeKind"));
        assertEquals("물어봤어",s2.view(0).draftText());
    }
    // g) 옛 settingsJson(필드 없음) 호환 + cameraWakeWord==wakeWord invalid
    @Test void legacyJsonCompatibleAndDuplicateWakeWordRejected(){
        var d=NovaFocusSettings.defaults();
        assertNull(d.cameraWakeWord());assertEquals("데빈",d.cameraWakeWordOrDefault());assertTrue(d.snapshotOrDefault().cameraAllowedOrDefault());
        // 필드 없는 생성자 경로(옛 JSON 역직렬화와 동일한 null 위임)
        var legacy=new NovaFocusSettings(true,d.wakeWord(),1200,20000,8000,d.presentation(),false,false);
        assertNull(legacy.cameraWakeWord());assertEquals("데빈",legacy.cameraWakeWordOrDefault());assertNull(legacy.snapshot());
        var legacySnapshot=new NovaFocusSettings.Snapshot(true,"FOLD_REAR");
        assertTrue(legacySnapshot.cameraAllowedOrDefault());
        assertThrows(IllegalArgumentException.class,()->new NovaFocusSettings(
            true,"데빈",1200,20000,8000,d.presentation(),false,false,null,"데빈"));
        assertThrows(IllegalArgumentException.class,()->new NovaFocusSettings(
            true,d.wakeWord(),1200,20000,8000,d.presentation(),false,false,null,"x".repeat(17)));
    }
    // h) 자동 ON + 노바 → 기존처럼 1장 (불변)
    @Test void novaWithAutoOnKeepsExistingSingleCapture(){
        var s=state(true);s.input(u("a","노바 질문"),0);
        assertNull(s.tick(1200));assertEquals("SNAPSHOT",s.view(1200).phase());
        var cmd=s.pendingCommand(1200,1);assertNotNull(cmd);assertEquals("FOLD_REAR",cmd.source());
        assertEquals("auto",s.diagnostics().get("snapshot.trigger"));
    }
    // cameraAllowed=false는 자동 촬영도 눌러야 한다
    @Test void cameraDeniedAlsoSuppressesAutoSnapshot(){
        var s=new NovaFocusState("server",settings(true,"FOLD_REAR",false));
        s.input(u("a","노바 질문"),0);
        var r=s.tick(1200);assertNotNull(r);assertNull(r.imageBase64());assertNull(s.pendingCommand(1200,1));
        assertEquals("auto",s.diagnostics().get("snapshot.trigger"));
    }
}
