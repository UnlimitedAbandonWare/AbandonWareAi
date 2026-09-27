package com.example.lms.api;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatHarmonyCitationReleaseTest {
    private static final String ANSWER = "Position and momentum have an uncertainty relation [V2]. "
            + "This is a general concept [W1].";

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void removesMarkersFromExplicitlyReleasedZeroEvidenceRagAnswer() {
        Map<String, Object> meta = releasedWithoutEvidence();

        String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                meta, "Explain the uncertainty principle.", ANSWER);

        assertEquals("Position and momentum have an uncertainty relation. "
                + "This is a general concept.", shaped);
        assertEquals("unverified_release_citation_markers_removed",
                meta.get("chat.harmony.postprocess.shapeReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("uncertainty relation"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"citable", "rendered", "promoted", "retrieved", "required",
            "held", "missing-count", "malformed-count", "missing-release"})
    void preservesMarkersOutsideConfirmedUnverifiedRelease(String boundary) {
        Map<String, Object> meta = releasedWithoutEvidence();
        switch (boundary) {
            case "citable" -> meta.put("prompt.citableEvidenceCount", 1);
            case "rendered" -> meta.put("prompt.citableEvidenceRenderedCount", 1);
            case "promoted" -> meta.put("rag.evidence.promotion.promotedCount", 1);
            case "retrieved" -> meta.put("finalVectorTopKCount", 1);
            case "required" -> meta.put("finalAnswer.evidenceReleaseRequired", true);
            case "held" -> meta.put("finalAnswer.releaseAllowed", false);
            case "missing-count" -> meta.remove("prompt.citableEvidenceCount");
            case "malformed-count" -> meta.put("prompt.citableEvidenceCount", "0");
            case "missing-release" -> meta.remove("finalAnswer.releaseReason");
            default -> throw new IllegalArgumentException(boundary);
        }

        assertEquals(ANSWER, ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                meta, "Explain the uncertainty principle.", ANSWER), boundary);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat.disambiguation.skipReason", "answer.guardRecovery.skipped"})
    void preservesRetrievalOffReasonWhenReleaseMetadataOverlaps(String skipKey) {
        Map<String, Object> meta = releasedWithoutEvidence();
        meta.put(skipKey, "retrieval_off_direct");
        meta.remove("prompt.citableEvidenceCount");

        String shaped = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(
                meta, "Explain the uncertainty principle.", ANSWER);

        assertFalse(shaped.contains("[V2]"));
        assertFalse(shaped.contains("[W1]"));
        assertEquals("retrieval_off_citation_markers_removed",
                meta.get("chat.harmony.postprocess.shapeReason"));
    }

    private static Map<String, Object> releasedWithoutEvidence() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("chatApi.web.prefetch.stream.resolvedUseRag", true);
        meta.put("finalWebTopK", List.of());
        meta.put("finalVectorTopK", List.of());
        meta.put("finalAnswer.releaseAllowed", true);
        meta.put("finalAnswer.evidenceReleaseRequired", false);
        meta.put("finalAnswer.releaseReason", "evidence_unverified_release");
        meta.put("prompt.citableEvidenceCount", 0);
        meta.put("prompt.citableEvidenceRenderedCount", 0L);
        meta.put("rag.evidence.promotion.promotedCount", 0);
        return meta;
    }
}
