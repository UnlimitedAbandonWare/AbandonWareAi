package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPlanPipelineSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void contextUsageNamesEstimatesAndPreservesUnknownLimitAndConsumption() {
        var snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(Map.of(
                "llm.call.approxInputTokens", 120,
                "prompt.memory.compressor.inputLen", 800,
                "prompt.memory.compressor.outputLen", 300,
                "prompt.memory.compressor.activated", true,
                "prompt.memory.compressor.reason", "overflow",
                "prompt.memory.raw", "private-memory"), null, null, null);
        assertNotNull(snapshot);
        JsonNode usage = mapper.valueToTree(snapshot).path("contextUsage");
        assertEquals(120, usage.path("inputTokens").asInt());
        assertEquals("char_estimate", usage.path("countMethod").asText());
        assertTrue(usage.path("contextLimitTokens").isNull());
        assertEquals(800, usage.path("memoryBeforeChars").asInt());
        assertEquals(300, usage.path("memoryAfterChars").asInt());
        assertTrue(usage.path("memoryIncluded").isNull());
        assertFalse(usage.toString().contains("private-memory"));
        assertEquals(usage, mapper.valueToTree(ChatStreamSignalBuilder.withTraceTurnId(
                snapshot, "FACT", 42L)).path("contextUsage"));
    }

    @Test
    void contextUsageRejectsMalformedCountsAndRequiresObservedModelLimitSource() {
        JsonNode usage = mapper.valueToTree(ChatStreamSignalBuilder.buildPipelineSnapshot(Map.of(
                "llm.call.approxInputTokens", 0,
                "llm.call.contextLimitTokens", 4096,
                "llm.call.contextLimitSource", "model_spec_snapshot",
                "prompt.memory.compressor.inputLen", -1,
                "prompt.memory.compressor.outputLen", "300",
                "prompt.memory.compressor.reason", "private-unapproved-reason",
                "llm.call.memoryIncluded", false), null, null, null)).path("contextUsage");
        assertEquals(0, usage.path("inputTokens").asInt());
        assertEquals(4096, usage.path("contextLimitTokens").asInt());
        assertEquals("model_spec_snapshot", usage.path("limitSource").asText());
        assertTrue(usage.path("memoryBeforeChars").isNull());
        assertTrue(usage.path("memoryAfterChars").isNull());
        assertTrue(usage.path("memoryCompressionReason").isNull());
        assertFalse(usage.path("memoryIncluded").asBoolean());
        JsonNode unverified = mapper.valueToTree(ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of("llm.call.approxInputTokens", 100, "llm.call.contextLimitTokens", 4096),
                null, null, null)).path("contextUsage");
        assertTrue(unverified.path("contextLimitTokens").isNull());
    }

    @Test
    void projectsOnlyTypedPlanStatesAndKnownStageRows() {
        Map<String, Object> meta = Map.of(
                "plan.when", "unknown",
                "plan.when.present", true,
                "plan.when.post", "true",
                "plan.when.lateActivation", true,
                "plan.when.conditions", List.of(Map.of("expr", "private-condition")),
                "plan.stageLedger", List.of(
                        Map.of("stage", "analyze.selfAsk", "status", "skipped_when_inactive",
                                "detail", "when_not_true", "evidence", "private-evidence"),
                        Map.of("stage", "untrusted.foo", "status", "executed",
                                "detail", "private-stage")));

        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                meta, null, null, null);
        assertNotNull(snapshot);
        JsonNode json = mapper.valueToTree(snapshot);
        assertEquals("unknown", json.path("planWhen").asText());
        assertTrue(json.path("planWhenPresent").asBoolean());
        assertEquals("true", json.path("planWhenPost").asText());
        assertTrue(json.path("planLateActivation").asBoolean());
        assertEquals(1, json.path("planStages").size());
        assertEquals("analyze.selfAsk", json.path("planStages").get(0).path("stage").asText());
        assertEquals("skipped_when_inactive", json.path("planStages").get(0).path("status").asText());
        assertEquals("when_not_true", json.path("planStages").get(0).path("reason").asText());
        assertFalse(json.toString().contains("private-"));

        JsonNode copied = mapper.valueToTree(ChatStreamSignalBuilder.withTraceTurnId(snapshot, "FACT", 42L));
        assertEquals(json.path("planStages"), copied.path("planStages"));
        assertEquals("unknown", copied.path("planWhen").asText());
    }

    @Test
    void absentAndEmptyPlanLedgerRemainDistinct() {
        JsonNode absent = mapper.valueToTree(ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of("plan.when", "unknown", "plan.when.present", true), null, null, null));
        JsonNode empty = mapper.valueToTree(ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of("plan.when", "true", "plan.when.present", false,
                        "plan.stageLedger", List.of()), null, null, null));

        assertTrue(absent.path("planStages").isNull());
        assertTrue(empty.path("planStages").isArray());
        assertEquals(0, empty.path("planStages").size());
        assertFalse(empty.path("planWhenPresent").asBoolean());
    }

    @Test
    void derivedContextCountNamesItsEstimateSource() {
        JsonNode estimated = mapper.valueToTree(ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of("webCount", 2, "vectorCount", 3), null, null, null));
        JsonNode reported = mapper.valueToTree(ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of("finalContextCount", 4, "webCount", 2), null, null, null));

        assertEquals(5, estimated.path("finalContextCount").asInt());
        assertEquals("web_vector_estimate", estimated.path("finalContextCountSource").asText());
        assertEquals(4, reported.path("finalContextCount").asInt());
        assertEquals("reported", reported.path("finalContextCountSource").asText());
    }
}
