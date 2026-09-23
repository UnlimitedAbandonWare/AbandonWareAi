package com.example.lms.resilience;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RagFailureBlackboxServiceAttachmentSignalTest {

    @Test
    void ocrRequiredAttachmentSignalAloneIsObserveOnlyNotQuarantine() {
        RagFailureBlackboxService.Snapshot snapshot = RagFailureBlackboxService.analyze(Map.of(
                "attachment.text.emptyReason", "text_layer_empty_ocr_required",
                "attachment.localDocs.count", 0
        ));

        assertEquals("text_layer_empty_ocr_required", snapshot.dominantFailure());
        assertEquals("observe_only", snapshot.restoreAction());
        assertEquals("SHADOW_REVIEW", snapshot.vectorDecision());
        assertFalse(snapshot.highRisk());
    }
}
