package com.example.lms.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatEvidenceMetadataDtoTest {

    @Test
    void chatResponseDefaultsEvidenceToEmptyList() {
        ChatResponseDto dto = new ChatResponseDto("ok", 1L, "model", true);

        assertTrue(dto.getEvidence().isEmpty());
    }

    @Test
    void chatResponseAndStreamFinalExposeEvidenceMetadata() {
        RagEvidenceMetadata evidence = new RagEvidenceMetadata(
                "W1",
                "WEB",
                "Alpha",
                "https://example.com/a",
                null,
                10,
                12,
                1,
                0.8d,
                "score");

        ChatResponseDto response = new ChatResponseDto(
                "ok",
                2L,
                "model",
                true,
                "FACT",
                LearningContextMetadata.empty(),
                List.of(evidence));
        ChatStreamEvent event = ChatStreamEvent.done(
                "model",
                true,
                2L,
                "FACT",
                7L,
                LearningContextMetadata.empty(),
                List.of(evidence));

        assertEquals("W1", response.getEvidence().get(0).marker());
        assertEquals("W1", event.evidence().get(0).marker());
    }

    @Test
    void ragEvidenceMetadataRedactsSupabaseKeyShapedPublicFields() {
        String rawKey = "sb_secret_" + "evidencepublic123456";
        RagEvidenceMetadata evidence = new RagEvidenceMetadata(
                "W1",
                "WEB",
                "Alpha " + rawKey,
                "https://example.com/a?token=" + rawKey,
                "docs/" + rawKey + ".md",
                10,
                12,
                1,
                0.8d,
                "score " + rawKey);

        String dump = evidence.toTraceMap().toString();

        assertFalse(dump.contains(rawKey), dump);
        assertFalse(dump.contains("sb_secret_"), dump);
    }

    @Test
    void streamFinalCanCarryRedactedPipelineSnapshot() {
        ChatStreamEvent.PipelineSnapshot snapshot = new ChatStreamEvent.PipelineSnapshot(
                "plan-1",
                "web-vector",
                "FACT",
                42L,
                2,
                -1,
                3,
                1.5d,
                -0.1d,
                "provider_disabled",
                "api_key=secret-value");

        ChatStreamEvent event = ChatStreamEvent.done(
                "model",
                true,
                2L,
                "FACT",
                42L,
                LearningContextMetadata.empty(),
                List.of(),
                snapshot);

        assertEquals("plan-1", event.pipelineSnapshot().planId());
        assertEquals(0, event.pipelineSnapshot().vectorCount());
        assertEquals(1.0d, event.pipelineSnapshot().citationCoverage());
        assertEquals(0.0d, event.pipelineSnapshot().finalSigmoid());
        assertTrue(event.pipelineSnapshot().disabledReason().startsWith("hash:"));
    }

    @Test
    void chatResponseCanCarryRedactedPipelineSnapshot() {
        ChatStreamEvent.PipelineSnapshot snapshot = new ChatStreamEvent.PipelineSnapshot(
                "plan-1",
                "web-vector",
                "FACT",
                42L,
                2,
                1,
                3,
                0.75d,
                0.6d,
                "provider_disabled",
                "api_key=secret-value");

        ChatResponseDto response = new ChatResponseDto(
                "ok",
                2L,
                "model",
                true,
                "FACT",
                42L,
                LearningContextMetadata.empty(),
                List.of(),
                snapshot);

        assertEquals("plan-1", response.getPipelineSnapshot().planId());
        assertEquals(2, response.getPipelineSnapshot().webCount());
        assertEquals(1, response.getPipelineSnapshot().vectorCount());
        assertEquals(3, response.getPipelineSnapshot().finalContextCount());
        assertEquals(0.75d, response.getPipelineSnapshot().citationCoverage());
        assertEquals(0.6d, response.getPipelineSnapshot().finalSigmoid());
        assertTrue(response.getPipelineSnapshot().disabledReason().startsWith("hash:"));
    }

    @Test
    void streamScoreDeltaSignalClampsRatiosAndRedactsGuard() {
        ChatStreamEvent event = ChatStreamEvent.scoreDelta(new ChatStreamEvent.ScoreDeltaSignal(
                1.5d,
                -0.2d,
                0.4d,
                0.3d,
                0.9d,
                "bode-tanh-v1",
                "rerank.secondPass",
                "ownerToken=abc123",
                9L));

        assertEquals("scoreDelta", event.type());
        assertEquals(1.0d, event.scoreDelta().scoreDelta());
        assertEquals(0.0d, event.scoreDelta().dropRatio());
        assertEquals(0.4d, event.scoreDelta().maxDrawdown());
        assertEquals(0.3d, event.scoreDelta().expectedDelta());
        assertTrue(event.scoreDelta().guard().contains("<redacted>"));
    }

    @Test
    void streamScoreDeltaSignalRedactsSupabaseKeyShapedGuard() {
        String rawKey = "sb_publishable_" + "streamguard";

        ChatStreamEvent event = ChatStreamEvent.scoreDelta(new ChatStreamEvent.ScoreDeltaSignal(
                0.5d,
                0.1d,
                0.4d,
                0.3d,
                0.9d,
                "bode-tanh-v1",
                "rerank.secondPass",
                rawKey,
                9L));

        assertFalse(event.scoreDelta().guard().contains(rawKey), event.scoreDelta().guard());
        assertTrue(event.scoreDelta().guard().contains("<redacted>"), event.scoreDelta().guard());
    }

    @Test
    void streamTransformerBlocksRedactLabelsAndClampTiming() {
        String rawKey = "sb_secret_" + "transformerstatus";

        ChatStreamEvent event = ChatStreamEvent.transformer(List.of(
                new ChatStreamEvent.TransformerBlockSignal(
                        "recover",
                        "Recover " + rawKey,
                        "resilience",
                        "warn",
                        "api_key=" + rawKey,
                        -4,
                        -9L)));

        assertEquals("transformer", event.type());
        assertEquals(1, event.transformerBlocks().size());
        ChatStreamEvent.TransformerBlockSignal block = event.transformerBlocks().get(0);
        assertEquals("recover", block.id());
        assertEquals(0, block.order());
        assertEquals(0L, block.tookMs());
        assertFalse(block.label().contains(rawKey), block.label());
        assertTrue(block.label().contains("<redacted>"), block.label());
        assertTrue(block.reason().startsWith("hash:"), block.reason());
    }
}
