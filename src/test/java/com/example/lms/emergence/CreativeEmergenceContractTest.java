package com.example.lms.emergence;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.prompt.pose.PromptPoseApplicationDecision;
import com.example.lms.prompt.pose.PromptPoseApplicationJudge;
import com.example.lms.prompt.pose.PromptPoseInputSanitizer;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreativeEmergenceContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void mixedCreativeAndEvidenceRequestKeepsEvidenceStrictPrecedence() {
        PromptPoseProperties properties = new PromptPoseProperties();
        properties.setEnabled(true);
        PromptPoseApplicationJudge judge = new PromptPoseApplicationJudge(properties, null);
        PromptPoseInputSanitizer.SanitizedInput input = PromptPoseInputSanitizer.sanitize(
                "Brainstorm a creative answer, but verify every claim with official evidence and citations",
                properties);

        PromptPoseApplicationDecision decision = judge.decide(input, 18);

        assertEquals("evidence_strict", decision.intentSlot());
        assertEquals("strict", decision.evidenceSlot());
    }
}
