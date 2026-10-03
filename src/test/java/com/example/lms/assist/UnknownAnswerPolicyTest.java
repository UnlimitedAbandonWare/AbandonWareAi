package com.example.lms.assist;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** P0-B precedence: the same fixed table drives cue supplement and Focus retry. */
class UnknownAnswerPolicyTest {
    @Test void generalWebHybridAllowUnknownWebRetryOnce(){
        for(var mode:List.of(UnknownAnswerPolicy.Mode.GENERAL,UnknownAnswerPolicy.Mode.WEB,UnknownAnswerPolicy.Mode.HYBRID))
            for(var trigger:UnknownAnswerPolicy.Trigger.values()){
                var d=UnknownAnswerPolicy.decide(mode,false,false,true,false,trigger);
                assertTrue(d.webAllowed(),mode+" "+trigger);
                assertEquals("allowed",d.reason());
            }
    }
    @Test void recentOnlyNeverSearchesAndScopedRagNeedsExplicitFlag(){
        for(var trigger:UnknownAnswerPolicy.Trigger.values()){
            assertEquals("recent_only_precedence",
                UnknownAnswerPolicy.decide(UnknownAnswerPolicy.Mode.RECENT_ONLY,false,false,true,false,trigger).reason());
            assertEquals("recent_only_precedence",
                UnknownAnswerPolicy.decide(UnknownAnswerPolicy.Mode.RECENT_ONLY,false,true,true,false,trigger).reason());
            var scoped=UnknownAnswerPolicy.decide(UnknownAnswerPolicy.Mode.SCOPED_RAG,false,false,true,false,trigger);
            assertFalse(scoped.webAllowed());assertEquals("scoped_rag_precedence",scoped.reason());
            var scopedAllowed=UnknownAnswerPolicy.decide(UnknownAnswerPolicy.Mode.SCOPED_RAG,false,true,true,false,trigger);
            assertTrue(scopedAllowed.webAllowed());assertEquals("scoped_flag",scopedAllowed.reason());
        }
    }
    @Test void requestWebOffAndSecondAttemptsNeverPass(){
        for(var mode:UnknownAnswerPolicy.Mode.values()){
            assertEquals("request_web_off",
                UnknownAnswerPolicy.decide(mode,true,true,true,false,UnknownAnswerPolicy.Trigger.EMPTY_ANSWER).reason());
            if(mode!=UnknownAnswerPolicy.Mode.SCOPED_RAG&&mode!=UnknownAnswerPolicy.Mode.RECENT_ONLY){
                assertEquals("disabled",
                    UnknownAnswerPolicy.decide(mode,false,false,false,false,UnknownAnswerPolicy.Trigger.EXPLICIT_UNKNOWN).reason());
                assertEquals("web_already_attempted",
                    UnknownAnswerPolicy.decide(mode,false,false,true,true,UnknownAnswerPolicy.Trigger.EXPLICIT_UNKNOWN).reason());
            }
        }
    }
    @Test void classifyBindsEmptyAndNoEvidenceTemplatesOnly(){
        assertEquals(UnknownAnswerPolicy.Trigger.EMPTY_ANSWER,UnknownAnswerPolicy.classify(null));
        assertEquals(UnknownAnswerPolicy.Trigger.EMPTY_ANSWER,UnknownAnswerPolicy.classify("  "));
        assertEquals(UnknownAnswerPolicy.Trigger.EXPLICIT_UNKNOWN,
            UnknownAnswerPolicy.classify("충분한 증거를 찾지 못했습니다."));
        assertEquals(UnknownAnswerPolicy.Trigger.EXPLICIT_UNKNOWN,
            UnknownAnswerPolicy.classify("제공된 자료가 부족하여 답변하기 어렵습니다."));
        assertNull(UnknownAnswerPolicy.classify("위치와 운동량을 동시에 정밀 측정할 수 없다는 원리입니다."));
        assertNull(UnknownAnswerPolicy.classify("회의는 오후 세 시입니다."));
    }
    @Test void jevVerdictMapsToPrecedenceModeAndFailOpenStaysGeneral(){
        assertEquals(UnknownAnswerPolicy.Mode.GENERAL,UnknownAnswerPolicy.mode(null));
        assertEquals(UnknownAnswerPolicy.Mode.GENERAL,UnknownAnswerPolicy.mode(JevDecisionAdvisor.Advice.off()));
        assertEquals(UnknownAnswerPolicy.Mode.GENERAL,UnknownAnswerPolicy.mode(JevDecisionAdvisor.Advice.defer("shadow","shadow")));
        assertEquals(UnknownAnswerPolicy.Mode.WEB,UnknownAnswerPolicy.mode(JevDecisionAdvisor.Advice.of(JevDecisionAdvisor.Verdict.WEB,"on",5)));
        assertEquals(UnknownAnswerPolicy.Mode.HYBRID,UnknownAnswerPolicy.mode(JevDecisionAdvisor.Advice.of(JevDecisionAdvisor.Verdict.HYBRID,"on",5)));
        assertEquals(UnknownAnswerPolicy.Mode.SCOPED_RAG,UnknownAnswerPolicy.mode(JevDecisionAdvisor.Advice.of(JevDecisionAdvisor.Verdict.SCOPED_RAG,"on",5)));
        assertEquals(UnknownAnswerPolicy.Mode.RECENT_ONLY,UnknownAnswerPolicy.mode(JevDecisionAdvisor.Advice.of(JevDecisionAdvisor.Verdict.RECENT_ONLY,"on",5)));
        assertEquals(UnknownAnswerPolicy.Mode.GENERAL,UnknownAnswerPolicy.mode(JevDecisionAdvisor.Advice.of(JevDecisionAdvisor.Verdict.CLARIFY,"on",5)));
    }
    @Test void lowAsrConfidenceIsAPolicyHookAndFollowsTheSameTable(){
        var d=UnknownAnswerPolicy.decide(UnknownAnswerPolicy.Mode.GENERAL,false,false,true,false,
            UnknownAnswerPolicy.Trigger.LOW_ASR_CONFIDENCE);
        assertTrue(d.webAllowed());
        assertFalse(UnknownAnswerPolicy.decide(UnknownAnswerPolicy.Mode.RECENT_ONLY,false,true,true,false,
            UnknownAnswerPolicy.Trigger.LOW_ASR_CONFIDENCE).webAllowed());
    }
}
