package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NovaFocusStateTest {
    @Test void recentPartialInputSurvivesListeningDeadlineWithoutSubmittingUnconfirmedText() {
        var s=state(true);s.open(0,"fold");
        s.input(u("ongoing",0,false,"부분 질문"),7000);
        assertNull(s.tick(8001));assertTrue(s.active());assertEquals("LISTENING",s.view(8001).phase());
        s.input(u("ongoing",1,false,"부분 질문 계속"),9000);
        assertNull(s.tick(16001));assertTrue(s.active());
        assertNull(s.tick(69001));assertFalse(s.active());assertEquals("input_unconfirmed",s.view(69001).reason());
    }
    @Test void closeReasonIsPublishedInStateJsonAndContainsNoDraft() {
        var s=state(true);s.open(0,"fold");s.close("wake_no_question");
        var wire=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(s.view(1));
        assertFalse(wire.path("active").asBoolean());assertEquals("wake_no_question",wire.path("reason").asText());
        assertEquals("",wire.path("draftText").asText());assertEquals("",wire.path("answerText").asText());
    }
    @Test void userCloseAndProviderFailurePublishDistinctReasons() {
        var user=state(true);user.open(0,"fold");user.close("user_closed");
        var error=state(true);error.input(u("failure",0,true,"노바 질문"),0);var request=error.tick(1200);
        error.accepted(request,"turn");error.failed(request,"focus_answer_unavailable");
        assertEquals("user_closed",user.view(1300).reason());assertEquals("focus_answer_unavailable",error.view(1300).reason());
        assertFalse(error.active());assertNotEquals(user.view(1300).reason(),error.view(1300).reason());
    }
    @Test void reasoningFreezesWithModelBeforeSnapshotAndNextQuestionUsesNewSetting() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(NovaFocusSettings.defaults());
        node.put("enabled",true);node.put("reasoningPreset","FAST");
        node.set("snapshot",mapper.valueToTree(new NovaFocusSettings.Snapshot(true,"FOLD_REAR")));
        var s=new NovaFocusState("server",mapper.treeToValue(node,NovaFocusSettings.class));s.open(0,"lens");
        s.input(u("freeze",0,true,"질문"),0);assertNull(s.tick(1200,true,1));
        node.put("reasoningPreset","DEEP");s.configure(mapper.treeToValue(node,NovaFocusSettings.class),1201);
        var frozen=s.tick(20000,true,2);
        assertEquals("FAST",mapper.valueToTree(frozen).path("reasoningPreset").asText());
        assertEquals(1,frozen.settingsVersion());
        s.close("user_closed");s.open(20001,"lens");s.input(u("next",0,true,"다음 질문"),20002);
        assertNull(s.tick(21202,true,2));var next=s.tick(40000,true,2);
        assertEquals("DEEP",mapper.valueToTree(next).path("reasoningPreset").asText());
        assertEquals(frozen.answerSelection(),next.answerSelection());
    }
    @Test void safePartialReachesLensAndFinalVersionStaysStable() {
        var s=state(true);s.input(u("partial",0,true,"노바 질문"),0);var r=s.tick(1200);s.accepted(r,"turn");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(s,"foldPartial",r,"turn","첫 문장입니다.");
        var early=s.view(1300);assertEquals("THINKING",early.phase());
        assertEquals("첫 문장입니다.",early.forTarget("fold").answerText());
        assertEquals("첫 문장입니다.",early.forTarget("lens").answerText());assertEquals("",early.renderReceiptTicket());
        var wire=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(early.forTarget("fold"));
        assertFalse(wire.path("answerComplete").booleanValue());assertTrue(wire.path("answerPrefixStable").booleanValue());
        assertEquals("첫 문장입니다.",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(early.forTarget("lens")).path("answerText").asText());
        long reserved=early.answerVersion();
        s.answer(r,"turn","첫 문장입니다. 다음 문장입니다.","ticket",1400);
        assertEquals(reserved,s.view(1400).answerVersion());
        assertEquals("첫 문장입니다. 다음 문장입니다.",s.view(1400).forTarget("lens").answerText());
    }
    @Test void lensProjectsUsefulLabelsAndKeepsOriginalOnFold() {
        var s=state(true);s.open(0,"lens");s.input(u("links",0,true,"노바 공식 사이트는?"),0);var r=s.tick(1200);s.accepted(r,"turn");
        String original="검색 중...\n공식 안내는 [스프링 문서](https://docs.spring.io/security)입니다.\n주소: https://example.org/help\n출처:\n- https://example.org/a\n- https://example.org/b";
        s.answer(r,"turn",original,"ticket",1300);
        assertEquals(original,s.view(1300).forTarget("fold").answerText());
        String lens=s.view(1300).forTarget("lens").answerText();
        assertTrue(lens.contains("공식 안내는 스프링 문서입니다."));
        assertFalse(lens.contains("https"));assertFalse(lens.contains("출처"));assertFalse(lens.contains("검색 중"));
        assertEquals("ticket",s.view(1300).forTarget("lens").renderReceiptTicket());
    }
    @Test void splitLinkPartialNeverFlashesAndProjectionOnlyAppends() {
        var s=state(true);s.input(u("split",0,true,"노바 질문"),0);var r=s.tick(1200);s.accepted(r,"turn");
        String previous="";
        for(String raw:java.util.List.of("첫 답입니다. [공식", "첫 답입니다. [공식 문서](htt", "첫 답입니다. [공식 문서](https://example.org)", "첫 답입니다. [공식 문서](https://example.org) 참고하세요.")) {
            s.foldPartial(r,"turn",raw);
            String visible=s.view(1300).forTarget("lens").answerText();
            assertTrue(visible.startsWith(previous),visible);assertFalse(visible.contains("htt"));assertFalse(visible.contains("["));
            previous=visible;
        }
        s.answer(r,"turn","첫 답입니다. [공식 문서](https://example.org) 참고하세요.","ticket",1400);
        assertTrue(s.view(1400).forTarget("lens").answerText().startsWith(previous));
        assertTrue(s.view(1400).forTarget("lens").answerText().contains("공식 문서 참고하세요."));
        s.close("user_closed");assertEquals("",s.view(1500).forTarget("lens").answerText());
    }
    @Test void urlOnlyFinalHasShortSafeNotice() {
        var s=state(true);s.input(u("url-only",0,true,"노바 주소는?"),0);var r=s.tick(1200);s.accepted(r,"turn");
        s.answer(r,"turn","https://example.org","ticket",1300);
        String lens=s.view(1300).forTarget("lens").answerText();assertFalse(lens.isBlank());assertFalse(lens.contains("https"));
    }
    @Test void markdownPartialsKeepThePublishedPrefixWhenFormattingCloses() {
        var s=state(true);s.input(u("bold",0,true,"노바 질문"),0);var r=s.tick(1200);s.accepted(r,"turn");
        s.foldPartial(r,"turn","**중요합니다.");
        String first=s.view(1300).forTarget("lens").answerText();assertEquals("중요합니다.",first);
        s.foldPartial(r,"turn","**중요합니다.** 다음");
        assertEquals(first,s.view(1350).forTarget("lens").answerText());
        s.answer(r,"turn","**중요합니다.** 다음입니다.","ticket",1400);
        assertTrue(s.view(1400).forTarget("lens").answerText().startsWith(first));
    }
    @Test void literalBracketAndProseAfterSourceListRemainUseful() {
        String original="권장 입력은 [0, 1 범위입니다. 끝값 1은 제외하세요.\n출처:\n- [공식 문서](https://example.org)\n다음 단계는 설정을 저장하세요.";
        String lens=NovaFocusAnswerService.lensText(original,true);
        assertTrue(lens.contains("[0, 1 범위입니다. 끝값 1은 제외하세요."));
        assertTrue(lens.contains("다음 단계는 설정을 저장하세요."));
        assertFalse(lens.contains("https"));assertFalse(lens.contains("공식 문서"));
        assertEquals("출처가 없는 주장은 믿지 마세요.",NovaFocusAnswerService.lensText("출처가 없는 주장은 믿지 마세요.",true));
        assertEquals("공식 문서를 참고하세요.",NovaFocusAnswerService.lensText("[공식 문서][docs]를 참고하세요.\n[docs]: https://example.org",true));
    }
    @Test void comparisonsRemainProseWhileActualHtmlAndAutolinksDisappear() {
        assertEquals("조건은 x < 5이고 y > 2입니다.",NovaFocusAnswerService.lensText("조건은 x < 5이고 y > 2입니다.",true));
        assertEquals("공식 안내를 확인하세요.",NovaFocusAnswerService.lensText("<b>공식 안내</b>를 확인하세요. <https://example.org>",true));
    }
    @Test void stalePartialAndFailedStreamCannotPublishOrComplete() {
        var s=state(true);s.input(u("partial-fail",0,true,"노바 질문"),0);var r=s.tick(1200);s.accepted(r,"turn");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(s,"foldPartial",r,"wrong-turn","잘못된 문장입니다.");
        assertEquals("",s.view(1300).forTarget("fold").answerText());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(s,"foldPartial",r,"turn","첫 문장입니다.");
        s.failed(r,"focus_stream_failed");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(s,"foldPartial",r,"turn","늦게 온 문장입니다.");
        assertEquals("",s.view(1400).forTarget("fold").answerText());assertFalse(s.active());
        assertFalse(s.receipt("server",r.activationId(),"turn",s.view(1400).answerVersion(),"ticket","presentation_done",1500));
    }
    @Test void groundedOriginalIsPhoneOnlyAndLateCompletionPublishesNeitherBodyNorMetadata(){
        var s=state(true);s.input(u("ground",0,true,"노바 질문"),0);var request=s.tick(1200);s.accepted(request,"turn");
        String original="원문".repeat(5000);
        var metadata=new com.example.lms.learning.gemini.GeminiGateway.GroundingMetadata(java.util.List.of("query"),java.util.List.of(),java.util.List.of(),
            new com.example.lms.learning.gemini.GeminiGateway.SearchEntryPoint("<div>Suggestions</div>"));
        var grounded=new com.example.lms.learning.gemini.GeminiGateway.GroundedAnswer(original,"gemini-fixture",metadata,java.util.List.of(original),true);
        s.answer(request,"turn",original,"receipt",1300,grounded);
        var phone=s.view(1300).forTarget("fold");assertSame(grounded,phone.grounding());assertEquals(original,phone.grounding().originalText());
        assertEquals("fold",phone.renderTarget());assertEquals("receipt",phone.renderReceiptTicket());
        var lens=s.view(1300).forTarget("lens");assertNull(lens.grounding());assertNull(lens.renderReceiptTicket());assertFalse(lens.answerText().contains("원문"));
        s.close("user_closed");s.answer(request,"turn",original,"late",1400,grounded);
        assertNull(s.view(1400).grounding());assertEquals("",s.view(1400).answerText());assertFalse(s.active());
    }
    @Test void acceptedQuestionFreezesSearchSettingUntilNextQuestion() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(NovaFocusSettings.defaults());
        node.put("enabled",true);node.put("webSearchEnabled",false);
        var s=new NovaFocusState("server",mapper.treeToValue(node,NovaFocusSettings.class));
        s.input(u("search",0,true,"노바 공식 자료를 찾아줘"),0);var request=s.tick(1200);
        node.put("webSearchEnabled",true);s.settings=mapper.treeToValue(node,NovaFocusSettings.class);
        assertFalse(mapper.valueToTree(request).path("webSearchEnabled").booleanValue());
        assertTrue(mapper.valueToTree(request).hasNonNull("webSearchEnabled"));
    }
    @Test void audioEpochDropsOnlyUnconfirmedPartsAndSeparatesReusedAsrIds(){
        var s=state(true);s.sourceNamespace("assist:1");
        s.input(u("a",0,true,"노바 first"),0);s.input(u("b",0,false,"unfinished"),10);
        s.sourceNamespace("assist:2");assertEquals("first",s.view(20).draftText());
        s.input(u("a",0,true,"second"),30);
        var request=s.tick(1230);assertNotNull(request);assertEquals("first second",request.question());
    }
    @Test void audioEpochCannotCarryAnUnconfirmedWakeIntoNextSegment(){
        var s=state(true);s.sourceNamespace("assist:1");s.input(u("a",0,false,"노바 draft"),0);
        s.sourceNamespace("assist:2");assertFalse(s.active());
        assertFalse(s.input(u("a",0,true,"unrelated"),100));assertNull(s.tick(5000));
    }
    @Test void audioEpochOldRequestCannotCancelNextTurnInSameActivation(){
        var s=state(true);s.sourceNamespace("assist:1");s.input(u("a",0,true,"노바 first"),0);
        var first=s.tick(1200);s.accepted(first,"first");s.answer(first,"first","answer","ticket",1300);
        s.receipt("server",first.activationId(),"first",1,"ticket","first_visible",1400);
        s.receipt("server",first.activationId(),"first",1,"ticket","presentation_done",1500);
        s.sourceNamespace("assist:2");s.input(u("a",0,true,"second"),1600);var second=s.tick(2800);
        assertNotNull(second);assertTrue(s.accepts(second));assertFalse(s.accepts(first));
        s.failed(first,"late_failure");assertTrue(s.accepts(second));
    }
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
        assertEquals(new java.util.HashSet<>(java.util.List.of("active","phase","stateVersion","answerVersion","bufferedQuestions","snapshotPending","captureAttempts","capturesCompleted","duplicateSuppressed","captureGrants","textFallbacks")),s.diagnostics().keySet());
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
