package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.integrations.HybridRetriever;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceCoherenceVerifyToolTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void approvesOnlyCompleteConsistentIndependentPrimaryDirectEvidence() {
        ToolResponse response = verify(completeRequest(List.of(
                evidence("e-1", "group-a", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "SOFT", false, null),
                evidence("e-2", "group-b", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "SOFT", false, null))));

        assertEquals("CONSISTENT", response.data().get("coherenceStatus"));
        assertEquals("APPROVE", response.data().get("releaseStatus"));
        assertEquals(true, response.data().get("verificationGatePassed"));
        assertEquals(2, response.data().get("independentSourceCount"));
        assertEquals("MEDIUM..HIGH", response.data().get("confidenceRange"));
        assertFalse(response.data().containsKey("confidenceScore"));
        assertEquals(2, ((List<?>) response.data().get("evidenceRefs")).size());

        String publicPayload = response.data() + TraceStore.getByPrefix("evidenceCoherence.").toString();
        assertFalse(publicPayload.contains("private original claim"), publicPayload);
        assertFalse(publicPayload.contains("group-a"), publicPayload);
    }

    @Test
    void incompleteRowsDeferAndCannotOpenVerificationGate() {
        ToolResponse response = verify(completeRequest(List.of(Map.of(
                "evidenceId", "e-1",
                "relation", "SUPPORTS"))));

        assertEquals("UNDERDETERMINED", response.data().get("coherenceStatus"));
        assertEquals("HOLD", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertEquals(1, response.data().get("invalidRowCount"));
        assertEquals("LOW..LOW", response.data().get("confidenceRange"));
        assertFalse(((List<?>) response.data().get("requiredNextEvidence")).isEmpty());
    }

    @Test
    void comparableIndependentSupportAndConflictRemainHeld() {
        ToolResponse response = verify(completeRequest(List.of(
                evidence("e-1", "group-a", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "SOFT", false, null),
                evidence("e-2", "group-b", "CONFLICTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "SOFT", false, null))));

        assertEquals("UNDERDETERMINED", response.data().get("coherenceStatus"));
        assertEquals("HOLD", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertEquals("LOW..MEDIUM", response.data().get("confidenceRange"));
    }

    @Test
    void applicableOfficialHardConflictRejectsWithoutOpeningConfirmationGate() {
        Map<String, Object> request = new LinkedHashMap<>(completeRequest(List.of(
                evidence("e-hard", "official-a", "CONFLICTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "HARD_APPLICABLE", false, null),
                evidence("e-soft", "source-b", "SUPPORTS", "SECONDARY", "INFERRED", "PARTIAL",
                        "INDEPENDENT", "SOFT", false, null))));
        request.put("officialConstraints", List.of(Map.of(
                "evidenceId", "e-hard",
                "applicable", true,
                "effectiveAt", "2026-07-15T00:00:00Z")));

        ToolResponse response = verify(request);

        assertEquals("CONTRADICTED", response.data().get("coherenceStatus"));
        assertEquals("REJECT", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertEquals("LOW..MEDIUM", response.data().get("confidenceRange"));
        assertEquals(List.of(ref("e-hard")), response.data().get("decisiveEvidenceIds"));
        assertEquals(List.of(), response.data().get("unresolvedConflictIds"));
    }

    @Test
    void packetModeRejectsCallerAuthoredUnboundOfficialConstraintBypass() {
        CounterEvidencePacketStore packetStore = new CounterEvidencePacketStore();
        List<String> queryTraceRefs = List.of(
                "hash:aaaaaaaaaaaa", "hash:bbbbbbbbbbbb", "hash:cccccccccccc");
        String packetRef = packetStore.issue(
                "packet-session",
                com.example.lms.trace.SafeRedactor.hashValue("claim"),
                com.example.lms.trace.SafeRedactor.hashValue("question"),
                queryTraceRefs,
                List.of());
        Map<String, Object> request = new LinkedHashMap<>(completeRequest(List.of(
                evidence("caller-official", "forged-official", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "HARD_APPLICABLE", true, null))));
        request.put("decisionQuestion", "question");
        request.put("originalClaim", "claim");
        request.put("queryTraceRefs", queryTraceRefs);
        request.put("counterEvidencePacketRef", packetRef);
        request.put("officialConstraints", List.of(Map.of(
                "evidenceId", "caller-official",
                "applicable", true,
                "effectiveAt", "2026-07-15T00:00:00Z")));

        ToolResponse response = new EvidenceCoherenceVerifyTool(packetStore).execute(
                new ToolRequest(request, new ToolContext("packet-session", null)));

        assertEquals("UNDERDETERMINED", response.data().get("coherenceStatus"));
        assertEquals("HOLD", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertTrue(String.valueOf(response.data().get("requiredNextEvidence"))
                .contains("trusted_official_constraint_provenance"));
    }

    @Test
    void omittedApplicableOfficialConstraintCannotBeSilentlyIgnored() {
        Map<String, Object> request = new LinkedHashMap<>(completeRequest(List.of(
                evidence("e-1", "group-a", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "SOFT", false, null),
                evidence("e-2", "group-b", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "SOFT", false, null))));
        request.put("officialConstraints", List.of(Map.of(
                "evidenceId", "omitted-hard-constraint",
                "applicable", true,
                "effectiveAt", "2026-07-15T00:00:00Z")));

        ToolResponse response = verify(request);

        assertEquals("UNDERDETERMINED", response.data().get("coherenceStatus"));
        assertEquals("HOLD", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertTrue(String.valueOf(response.data().get("requiredNextEvidence"))
                .contains("applicable_official_constraint_evidence_row"));
    }

    @Test
    void futureOfficialConstraintIsNotAppliedToEarlierEvidenceWindow() {
        Map<String, Object> request = new LinkedHashMap<>(completeRequest(List.of(
                evidence("future-hard", "official-a", "CONFLICTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "HARD_APPLICABLE", false, null))));
        request.put("officialConstraints", List.of(Map.of(
                "evidenceId", "future-hard",
                "applicable", true,
                "effectiveAt", "2026-07-16T00:00:00Z")));

        ToolResponse response = verify(request);

        assertEquals("UNDERDETERMINED", response.data().get("coherenceStatus"));
        assertEquals("HOLD", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertTrue(String.valueOf(response.data().get("requiredNextEvidence"))
                .contains("official_constraint_effective_time_alignment"));
    }

    @Test
    void duplicateEvidenceIdCannotForgeIndependentSupportGroups() {
        ToolResponse response = verify(completeRequest(List.of(
                evidence("same-evidence", "claimed-group-a", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "SOFT", false, null),
                evidence("same-evidence", "claimed-group-b", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "SOFT", false, null))));

        assertEquals("UNDERDETERMINED", response.data().get("coherenceStatus"));
        assertEquals("HOLD", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertTrue(String.valueOf(response.data().get("requiredNextEvidence"))
                .contains("unique_evidence_ids"));
    }

    @Test
    void sameSourceProvenanceCannotBeSplitIntoCallerAuthoredIndependentGroups() {
        Map<String, Object> first = new LinkedHashMap<>(evidence(
                "e-1", "claimed-group-a", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                "INDEPENDENT", "SOFT", false, null));
        Map<String, Object> second = new LinkedHashMap<>(evidence(
                "e-2", "claimed-group-b", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                "INDEPENDENT", "SOFT", false, null));
        first.put("sourceProvenance", "same upstream artifact");
        second.put("sourceProvenance", "same upstream artifact");

        ToolResponse response = verify(completeRequest(List.of(Map.copyOf(first), Map.copyOf(second))));

        assertEquals("UNDERDETERMINED", response.data().get("coherenceStatus"));
        assertEquals("HOLD", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertEquals(1, response.data().get("independentSourceCount"));
        assertTrue(String.valueOf(response.data().get("requiredNextEvidence"))
                .contains("independent_primary_direct_identity_evidence"));
    }

    @Test
    void packetBoundLocalConflictOverridesCallerAuthoredSupportRelation() {
        CounterEvidencePacketStore packetStore = new CounterEvidencePacketStore();
        List<String> queryTraceRefs = List.of(
                "hash:aaaaaaaaaaaa", "hash:bbbbbbbbbbbb", "hash:cccccccccccc");
        String evidenceId = "hash:dddddddddddd";
        String sourceHash = "hash:eeeeeeeeeeee";
        String observedAt = "2026-07-15T00:00:00Z";
        String packetRef = packetStore.issue(
                "packet-session",
                com.example.lms.trace.SafeRedactor.hashValue("claim"),
                com.example.lms.trace.SafeRedactor.hashValue("question"),
                queryTraceRefs,
                List.of(Map.of(
                        "evidenceId", evidenceId,
                        "independenceGroupHash", sourceHash,
                        "queryTraceRef", queryTraceRefs.get(0),
                        "querySlot", "AUTHORITATIVE_CONSTRAINT",
                        "observedAt", observedAt,
                        "retrievalScore", 0.9d,
                        "relation", "CONFLICTS",
                        "crossValidationScore", 0.8d)));
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("evidenceId", evidenceId);
        normalized.put("claim", "caller normalized claim");
        normalized.put("sourceProvenanceHash", sourceHash);
        normalized.put("observedAt", observedAt);
        normalized.put("validAt", observedAt);
        normalized.put("directness", "DIRECT");
        normalized.put("authority", "PRIMARY");
        normalized.put("independenceGroup", "forged-independent-group");
        normalized.put("independence", "INDEPENDENT");
        normalized.put("relation", "SUPPORTS");
        normalized.put("coverage", "COMPLETE");
        normalized.put("constraintStrength", "SOFT");
        normalized.put("uniqueIdentifierMapping", true);
        normalized.put("queryTraceRef", queryTraceRefs.get(0));
        Map<String, Object> request = new LinkedHashMap<>(completeRequest(List.of(Map.copyOf(normalized))));
        request.put("decisionQuestion", "question");
        request.put("originalClaim", "claim");
        request.put("queryTraceRefs", queryTraceRefs);
        request.put("counterEvidencePacketRef", packetRef);

        ToolResponse response = new EvidenceCoherenceVerifyTool(packetStore).execute(
                new ToolRequest(request, new ToolContext("packet-session", null)));

        assertEquals("UNDERDETERMINED", response.data().get("coherenceStatus"));
        assertEquals("HOLD", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertEquals(List.of(evidenceId), response.data().get("unresolvedConflictIds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrievalRowsCannotBeSelfPromotedWithoutPacketBindingAndFullProvenance() {
        CounterEvidencePacketStore packetStore = new CounterEvidencePacketStore();
        HybridRetriever retriever = new HybridRetriever() {
            private int call;

            @Override
            public List<Map<String, Object>> retrieve(String query, Integer topK, String domain) {
                throw new AssertionError("external-capable retrieve path must not be used");
            }

            @Override
            public List<Map<String, Object>> retrieveStrictLocal(String query, Integer topK) {
                call++;
                return List.of(Map.of(
                        "id", "doc-" + call,
                        "source", "independent-source-" + call,
                        "snippet", "private evidence " + call,
                        "score", 0.9d));
            }
        };
        CounterEvidenceRetrieveTool retrieveTool = new CounterEvidenceRetrieveTool(retriever, packetStore);
        ToolResponse retrieved = retrieveTool.execute(new ToolRequest(Map.of(
                "originalClaim", "claim",
                "decisionQuestion", "question",
                "queries", List.of(
                        Map.of("slot", "AUTHORITATIVE_CONSTRAINT", "query", "q1"),
                        Map.of("slot", "ALTERNATIVE_OR_UNKNOWN", "query", "q2"),
                        Map.of("slot", "PROVENANCE_AND_TIME", "query", "q3")),
                "retrievalBudget", Map.of("maxDocuments", 3, "maxMillis", 5_000)),
                new ToolContext("packet-session", null)));

        List<Map<String, Object>> callerPromoted = ((List<Map<String, Object>>) retrieved.data().get("evidenceRows"))
                .stream()
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.put("claim", "normalized local retrieval claim");
                    copy.put("sourceProvenanceHash", row.get("independenceGroupHash"));
                    copy.put("validAt", row.get("observedAt"));
                    copy.put("directness", "DIRECT");
                    copy.put("authority", "PRIMARY");
                    copy.put("independenceGroup", row.get("independenceGroupHash"));
                    copy.put("independence", "INDEPENDENT");
                    copy.put("relation", "SUPPORTS");
                    copy.put("coverage", "COMPLETE");
                    copy.put("constraintStrength", "SOFT");
                    copy.put("uniqueIdentifierMapping", true);
                    return Map.copyOf(copy);
                })
                .toList();
        Map<String, Object> request = new LinkedHashMap<>(completeRequest(callerPromoted));
        request.put("decisionQuestion", "question");
        request.put("originalClaim", "claim");
        request.put("queryTraceRefs", retrieved.data().get("queryTraceRefs"));
        request.put("counterEvidencePacketRef", retrieved.data().get("packetRef"));

        Map<String, Object> mismatched = new LinkedHashMap<>(request);
        mismatched.put("originalClaim", "different claim");
        ToolResponse mismatch = new EvidenceCoherenceVerifyTool(packetStore).execute(
                new ToolRequest(mismatched, new ToolContext("packet-session", null)));
        assertEquals("HOLD", mismatch.data().get("releaseStatus"));
        assertEquals(false, mismatch.data().get("counterEvidencePacketConsumed"));
        assertTrue(String.valueOf(mismatch.data().get("requiredNextEvidence"))
                .contains("counter_evidence_packet_mismatch"));

        ToolResponse response = new EvidenceCoherenceVerifyTool(packetStore).execute(
                new ToolRequest(request, new ToolContext("packet-session", null)));

        assertEquals("UNDERDETERMINED", response.data().get("coherenceStatus"));
        assertEquals("HOLD", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertEquals(true, response.data().get("counterEvidencePacketConsumed"));
        assertEquals(retrieved.data().get("queryTraceRefs"), response.data().get("consumedQueryTraceRefs"));
        assertTrue(String.valueOf(response.data().get("requiredNextEvidence"))
                .contains("independent_primary_direct_identity_evidence"));

        ToolResponse replay = new EvidenceCoherenceVerifyTool(packetStore).execute(
                new ToolRequest(request, new ToolContext("packet-session", null)));
        assertEquals("HOLD", replay.data().get("releaseStatus"));
        assertEquals(false, replay.data().get("verificationGatePassed"));
        assertEquals(false, replay.data().get("counterEvidencePacketConsumed"));
        assertTrue(String.valueOf(replay.data().get("requiredNextEvidence"))
                .contains("counter_evidence_packet_unavailable"));
    }

    @Test
    void nonCanonicalQueryTraceCountDefersBeforeVerdict() {
        Map<String, Object> request = new LinkedHashMap<>(completeRequest(List.of(
                evidence("e-1", "group-a", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "SOFT", false, null),
                evidence("e-2", "group-b", "SUPPORTS", "PRIMARY", "DIRECT", "COMPLETE",
                        "INDEPENDENT", "SOFT", false, null))));
        request.put("queryTraceRefs", List.of("trace-only-one"));

        ToolResponse response = verify(request);

        assertEquals("UNDERDETERMINED", response.data().get("coherenceStatus"));
        assertEquals("HOLD", response.data().get("releaseStatus"));
        assertEquals(false, response.data().get("verificationGatePassed"));
        assertTrue(String.valueOf(response.data().get("requiredNextEvidence"))
                .contains("query_trace_refs_zero_or_three"));
    }

    private static ToolResponse verify(Map<String, Object> input) {
        return new EvidenceCoherenceVerifyTool().execute(new ToolRequest(input, null));
    }

    private static Map<String, Object> completeRequest(List<Map<String, Object>> rows) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("decisionQuestion", "private decision question");
        request.put("originalClaim", "private original claim");
        request.put("evidenceRows", rows);
        request.put("officialConstraints", List.of());
        request.put("allowedReleaseStatuses", List.of("APPROVE", "REJECT", "HOLD"));
        request.put("queryTraceRefs", List.of());
        return request;
    }

    private static Map<String, Object> evidence(String id,
                                                String independenceGroup,
                                                String relation,
                                                String authority,
                                                String directness,
                                                String coverage,
                                                String independence,
                                                String constraintStrength,
                                                boolean uniqueIdentifierMapping,
                                                String traceRef) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("evidenceId", id);
        row.put("claim", "atomic claim " + id);
        row.put("sourceProvenance", "artifact provenance " + id);
        row.put("observedAt", "2026-07-15T00:00:00Z");
        row.put("validAt", "2026-07-15T00:00:00Z");
        row.put("directness", directness);
        row.put("authority", authority);
        row.put("independenceGroup", independenceGroup);
        row.put("independence", independence);
        row.put("relation", relation);
        row.put("coverage", coverage);
        row.put("constraintStrength", constraintStrength);
        row.put("uniqueIdentifierMapping", uniqueIdentifierMapping);
        if (traceRef != null) {
            row.put("traceRef", traceRef);
        }
        return Map.copyOf(row);
    }

    private static String ref(String value) {
        String hash = com.example.lms.trace.SafeRedactor.hashValue(value);
        return hash.substring(0, Math.min(24, hash.length()));
    }
}
