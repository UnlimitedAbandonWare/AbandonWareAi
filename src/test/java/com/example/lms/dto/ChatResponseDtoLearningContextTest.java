package com.example.lms.dto;

import com.example.lms.infra.selection.ReplaySelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyProjection;
import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatResponseDtoLearningContextTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void chatResponseDefaultsToRedactedEmptyLearningContext() {
        ChatResponseDto dto = new ChatResponseDto("ok", 1L, "local", true);

        assertNotNull(dto.getLearningContext());
        assertEquals("ANONYMOUS", dto.getLearningContext().actorRole());
        assertEquals(0, dto.getLearningContext().signalCount());
        assertFalse(dto.getLearningContext().summaryPresent());
        assertTrue(dto.getLearningContext().sourceTags().isEmpty());
        assertFalse(dto.getLearningContext().degraded());
        assertEquals("", dto.getLearningContext().degradedReason());
        assertNull(dto.getTraceTurnId());
    }

    @Test
    void chatResponseCanCarryTraceTurnIdForSyncTraceOpen() {
        ChatResponseDto dto = new ChatResponseDto(
                "ok",
                7L,
                "local",
                true,
                "EVIDENCE_ONLY",
                42L,
                LearningContextMetadata.empty(),
                List.of());

        assertEquals(42L, dto.getTraceTurnId());
    }

    @Test
    void selectionEntropyProjectionIsAdditiveAndNullIsOmittedFromLegacyJson() throws Exception {
        SelectionEntropyProjection projection = replayProjection();
        ChatResponseDto legacy = new ChatResponseDto("ok", 7L, "local", true);
        ChatResponseDto existingTerminal = new ChatResponseDto(
                "ok",
                7L,
                "local",
                true,
                "EVIDENCE_ONLY",
                42L,
                LearningContextMetadata.empty(),
                List.of(),
                null);
        ChatResponseDto replay = new ChatResponseDto(
                "ok",
                7L,
                "local",
                true,
                "EVIDENCE_ONLY",
                42L,
                LearningContextMetadata.empty(),
                List.of(),
                null,
                projection);

        assertNull(legacy.getSelectionEntropy());
        assertNull(existingTerminal.getSelectionEntropy());
        assertEquals(projection, replay.getSelectionEntropy());

        ObjectMapper mapper = new ObjectMapper();
        String legacyJson = mapper.writeValueAsString(legacy);
        String replayJson = mapper.writeValueAsString(replay);
        assertFalse(legacyJson.contains("selectionEntropy"), legacyJson);
        assertTrue(replayJson.contains("\"selectionEntropy\""), replayJson);
        assertTrue(replayJson.contains("\"seedFingerprint\":\"012345abcdef\""), replayJson);
        assertFalse(replayJson.contains("X-AWX-Selection-Seed"), replayJson);
        assertFalse(replayJson.contains("raw-replay-seed"), replayJson);
    }

    @Test
    void chatResponseCanExposeOnlyAllowlistedLearningMetadata() {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("prompt.learningRole", "teacher");
        trace.put("prompt.learningSignalCount", 3);
        trace.put("prompt.learningSummaryPresent", true);
        trace.put("prompt.learningSourceTags", List.of("assignment", "feedback", "ownerToken", "raw query"));
        trace.put("prompt.learningDegraded", true);
        trace.put("prompt.learningDegradedReason", "RepositoryTimeout\nstudent@example.test");

        LearningContextMetadata meta = LearningContextMetadata.fromTrace(trace);
        ChatResponseDto dto = new ChatResponseDto("ok", 7L, "local", true, "EVIDENCE_ONLY", meta);

        assertEquals("TRAINING_SUPPORT", dto.getLearningContext().actorRole());
        assertEquals(3, dto.getLearningContext().signalCount());
        assertTrue(dto.getLearningContext().summaryPresent());
        assertEquals(List.of("TRAINING_TASK", "FEEDBACK"), dto.getLearningContext().sourceTags());
        assertTrue(dto.getLearningContext().degraded());
        assertEquals("RepositoryTimeout", dto.getLearningContext().degradedReason());
        assertFalse(dto.getLearningContext().degradedReason().contains("\n"));
        assertFalse(dto.getLearningContext().degradedReason().contains("@"));
        assertFalse(dto.getLearningContext().degradedReason().contains("student"));
        assertFalse(dto.getLearningContext().sourceTags().contains("ownerToken"));
        assertFalse(dto.getLearningContext().sourceTags().contains("raw query"));
    }

    @Test
    void ragSupportTraceAliasesTakePriorityOverLegacyLearningKeys() {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("prompt.learningRole", "student");
        trace.put("prompt.learningSignalCount", 1);
        trace.put("prompt.learningSummaryPresent", false);
        trace.put("prompt.learningSourceTags", List.of("assignment"));
        trace.put("prompt.learningDegraded", false);

        trace.put("prompt.ragSupport.role", "admin");
        trace.put("prompt.ragSupport.signalCount", 4);
        trace.put("prompt.ragSupport.summaryPresent", true);
        trace.put("prompt.ragSupport.sourceTags", List.of("rag_ops", "autolearn", "ownerToken"));
        trace.put("prompt.ragSupport.degraded", true);
        trace.put("prompt.ragSupport.degradedReason", "RepositoryTimeout");

        LearningContextMetadata meta = LearningContextMetadata.fromTrace(trace);

        assertEquals("RAG_ADMIN", meta.actorRole());
        assertEquals(4, meta.signalCount());
        assertTrue(meta.summaryPresent());
        assertEquals(List.of("RAG_OPS", "AUTOLEARN"), meta.sourceTags());
        assertTrue(meta.degraded());
        assertEquals("RepositoryTimeout", meta.degradedReason());
    }

    @Test
    void degradedReasonRedactsSensitiveFirstToken() {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("prompt.learningDegraded", true);
        trace.put("prompt.learningDegradedReason", "student@example.test Bearer " + "abcdefghijklmnop");

        LearningContextMetadata meta = LearningContextMetadata.fromTrace(trace);

        assertEquals("REDACTED", meta.degradedReason());
    }

    @Test
    void degradedReasonRedactsSupabaseApiKeyPrefix() {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("prompt.learningDegraded", true);
        trace.put("prompt.learningDegradedReason", ("sb_secret_" + "learning0123456789") + " fallback");

        LearningContextMetadata meta = LearningContextMetadata.fromTrace(trace);

        assertEquals("REDACTED", meta.degradedReason());
    }

    @Test
    void malformedLearningSignalCountUsesStableReasonCodeWithoutRawValue() {
        TraceStore.clear();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("prompt.learningSignalCount", "private learning count");

        LearningContextMetadata meta = LearningContextMetadata.fromTrace(trace);

        assertEquals(0, meta.signalCount());
        assertEquals("learningContext.signalCount", TraceStore.get("learning.context.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("learning.context.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("learning.context.suppressed.learningContext.signalCount"));
        assertEquals("invalid_number",
                TraceStore.get("learning.context.suppressed.learningContext.signalCount.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private learning count"));
    }

    @Test
    void malformedLearningSignalCountFallbackLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/dto/LearningContextMetadata.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"learningContext.signalCount\", ex);"));
        assertTrue(source.contains("TraceStore.put(\"learning.context.suppressed.\" + safeStage, true);"));
    }

    private static SelectionEntropyProjection replayProjection() {
        return new SelectionEntropyProjection(
                "awx.selection-entropy.v1",
                "replay",
                ReplaySelectionEntropy.ALGORITHM_VERSION,
                true,
                "matched",
                "012345abcdef",
                "a".repeat(64),
                4,
                3,
                1,
                0,
                1,
                1,
                1,
                false,
                "");
    }
}
