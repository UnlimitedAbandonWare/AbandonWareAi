package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic terminal coherence gate for fully normalized evidence. It
 * never derives provenance, relation, or authority from a retrieval score.
 */
@RequiresScopes({ToolScope.INTERNAL_READ})
public final class EvidenceCoherenceVerifyTool implements AgentTool {

    private static final int MAX_TEXT_LENGTH = 4_096;
    private static final int MAX_ROWS = 100;
    private static final Set<String> RELEASE_STATUSES = Set.of("APPROVE", "REJECT", "HOLD");
    private final CounterEvidencePacketStore packetStore;

    public EvidenceCoherenceVerifyTool() {
        this(new CounterEvidencePacketStore());
    }

    public EvidenceCoherenceVerifyTool(CounterEvidencePacketStore packetStore) {
        this.packetStore = Objects.requireNonNull(packetStore, "packetStore");
    }

    @Override
    public String id() {
        return "evidence.coherence.verify";
    }

    @Override
    public String description() {
        return "Verify a complete provenance-aware evidence matrix and fail closed on contradiction or uncertainty.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        LinkedHashSet<String> requiredNextEvidence = new LinkedHashSet<>();

        String decisionQuestion = boundedText(input.get("decisionQuestion"));
        String originalClaim = boundedText(input.get("originalClaim"));
        if (decisionQuestion == null) {
            requiredNextEvidence.add("exact_decision_question");
        }
        if (originalClaim == null || "unknown".equalsIgnoreCase(originalClaim)) {
            requiredNextEvidence.add("exact_original_claim");
        }

        List<?> rawRows = rows(input.get("evidenceRows"), requiredNextEvidence);
        List<?> rawConstraints = list(input.get("officialConstraints"), "official_constraints", requiredNextEvidence);
        Set<String> allowedStatuses = labels(input.get("allowedReleaseStatuses"));
        if (!allowedStatuses.equals(RELEASE_STATUSES)) {
            requiredNextEvidence.add("allowed_release_statuses_approve_reject_hold");
        }
        List<String> queryTraceRefs = queryTraceRefs(input.get("queryTraceRefs"), requiredNextEvidence);
        boolean counterEvidencePacketConsumed = false;
        String counterEvidencePacketRefForCommit = null;
        Map<String, CounterEvidencePacketStore.EvidenceBinding> packetBindings = Map.of();
        if (queryTraceRefs.size() == 3) {
            String counterEvidencePacketRef = boundedText(input.get("counterEvidencePacketRef"));
            if (counterEvidencePacketRef == null) {
                requiredNextEvidence.add("counter_evidence_packet_ref");
            } else {
                Optional<CounterEvidencePacketStore.Packet> consumed = packetStore.peek(
                        counterEvidencePacketRef, sessionId(request));
                if (consumed.isEmpty()) {
                    requiredNextEvidence.add("counter_evidence_packet_unavailable");
                } else {
                    CounterEvidencePacketStore.Packet packet = consumed.get();
                    boolean packetMatches = Objects.equals(packet.claimHash(), SafeRedactor.hashValue(originalClaim))
                            && Objects.equals(packet.decisionQuestionHash(), SafeRedactor.hashValue(decisionQuestion))
                            && packet.queryTraceRefs().equals(queryTraceRefs);
                    if (!packetMatches) {
                        requiredNextEvidence.add("counter_evidence_packet_mismatch");
                    } else {
                        Map<String, CounterEvidencePacketStore.EvidenceBinding> mutable = new LinkedHashMap<>();
                        for (CounterEvidencePacketStore.EvidenceBinding binding : packet.evidenceBindings()) {
                            mutable.put(binding.evidenceId(), binding);
                        }
                        packetBindings = Map.copyOf(mutable);
                        counterEvidencePacketRefForCommit = counterEvidencePacketRef;
                    }
                }
            }
        }

        Map<String, Instant> applicableOfficialEvidence = officialEvidence(rawConstraints, requiredNextEvidence);
        Set<String> applicableOfficialEvidenceIds = applicableOfficialEvidence.keySet();
        List<NormalizedEvidence> validRows = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        Set<String> seenEvidenceIds = new HashSet<>();
        int invalidRows = 0;
        for (Object raw : rawRows) {
            String candidateEvidenceId = evidenceId(raw);
            if (!candidateEvidenceId.isBlank() && !seenEvidenceIds.add(candidateEvidenceId)) {
                requiredNextEvidence.add("unique_evidence_ids");
                invalidRows++;
                continue;
            }
            CounterEvidencePacketStore.EvidenceBinding binding = packetBindings.get(candidateEvidenceId);
            if (queryTraceRefs.size() == 3 && binding == null) {
                requiredNextEvidence.add(applicableOfficialEvidenceIds.contains(candidateEvidenceId)
                        ? "trusted_official_constraint_provenance"
                        : "counter_evidence_packet_binding");
                invalidRows++;
                continue;
            }
            NormalizedEvidence row = normalize(raw, queryTraceRefs, binding);
            if (row == null) {
                invalidRows++;
                continue;
            }
            validRows.add(row);
            evidenceRefs.add(ref(row.evidenceId()));
        }
        if (invalidRows > 0) {
            requiredNextEvidence.add("complete_normalized_evidence_rows");
        }
        Set<String> validEvidenceIds = validRows.stream()
                .map(NormalizedEvidence::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        if (!validEvidenceIds.containsAll(applicableOfficialEvidenceIds)) {
            requiredNextEvidence.add("applicable_official_constraint_evidence_row");
        }
        Map<String, NormalizedEvidence> validEvidenceById = validRows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        NormalizedEvidence::evidenceId,
                        row -> row,
                        (left, right) -> left));
        for (Map.Entry<String, Instant> official : applicableOfficialEvidence.entrySet()) {
            NormalizedEvidence row = validEvidenceById.get(official.getKey());
            if (row != null && official.getValue().isAfter(row.validAt())) {
                requiredNextEvidence.add("official_constraint_effective_time_alignment");
            }
        }
        if (counterEvidencePacketRefForCommit != null
                && invalidRows == 0
                && requiredNextEvidence.isEmpty()) {
            Optional<CounterEvidencePacketStore.Packet> committed = packetStore.consume(
                    counterEvidencePacketRefForCommit, sessionId(request));
            if (committed.isPresent()) {
                counterEvidencePacketConsumed = true;
            } else {
                requiredNextEvidence.add("counter_evidence_packet_unavailable");
            }
        }

        Set<String> independentGroups = new HashSet<>();
        Set<String> supportingPrimaryGroups = new HashSet<>();
        List<String> hardConflictIds = new ArrayList<>();
        List<String> hardSupportIds = new ArrayList<>();
        List<String> unresolvedConflictIds = new ArrayList<>();
        List<String> consistentEvidenceIds = new ArrayList<>();
        boolean uniqueIdentifierMapping = false;
        boolean unresolvedHardConstraint = false;

        for (NormalizedEvidence row : validRows) {
            if (row.independence() == Independence.INDEPENDENT) {
                independentGroups.add(row.independenceGroup());
            }
            boolean comparable = row.timeComparable()
                    && row.relation() != Relation.NOT_COMPARABLE;
            boolean primaryDirectComplete = row.authority() == Authority.PRIMARY
                    && row.directness() == Directness.DIRECT
                    && row.coverage() == Coverage.COMPLETE
                    && row.independence() == Independence.INDEPENDENT
                    && comparable;
            if (row.relation() == Relation.SUPPORTS && primaryDirectComplete) {
                supportingPrimaryGroups.add(row.independenceGroup());
                consistentEvidenceIds.add(ref(row.evidenceId()));
                uniqueIdentifierMapping |= row.uniqueIdentifierMapping();
            }
            boolean hardApplicable = row.constraintStrength() == ConstraintStrength.HARD_APPLICABLE;
            boolean official = applicableOfficialEvidenceIds.contains(row.evidenceId());
            boolean decisiveHardConstraint = hardApplicable && official && primaryDirectComplete;
            if (row.relation() == Relation.CONFLICTS && comparable) {
                if (decisiveHardConstraint) {
                    hardConflictIds.add(ref(row.evidenceId()));
                } else {
                    unresolvedConflictIds.add(ref(row.evidenceId()));
                }
            }
            if (hardApplicable) {
                boolean decisive = decisiveHardConstraint;
                if (!decisive) {
                    unresolvedHardConstraint = true;
                } else if (row.relation() == Relation.SUPPORTS) {
                    hardSupportIds.add(ref(row.evidenceId()));
                } else if (row.relation() != Relation.CONFLICTS) {
                    unresolvedHardConstraint = true;
                }
            }
        }

        boolean topLevelComplete = requiredNextEvidence.isEmpty()
                && invalidRows == 0
                && !validRows.isEmpty();
        CoherenceStatus coherenceStatus;
        if (!topLevelComplete) {
            coherenceStatus = CoherenceStatus.UNDERDETERMINED;
        } else if (!hardConflictIds.isEmpty() && !hardSupportIds.isEmpty()) {
            coherenceStatus = CoherenceStatus.UNDERDETERMINED;
            requiredNextEvidence.add("reconcile_applicable_official_constraints");
        } else if (!hardConflictIds.isEmpty()) {
            coherenceStatus = CoherenceStatus.CONTRADICTED;
        } else if (unresolvedHardConstraint || !unresolvedConflictIds.isEmpty()) {
            coherenceStatus = CoherenceStatus.UNDERDETERMINED;
            requiredNextEvidence.add("reconcile_conflicting_provenance_and_time");
        } else if (uniqueIdentifierMapping || supportingPrimaryGroups.size() >= 2) {
            coherenceStatus = CoherenceStatus.CONSISTENT;
        } else {
            coherenceStatus = CoherenceStatus.UNDERDETERMINED;
            requiredNextEvidence.add("independent_primary_direct_identity_evidence");
        }

        String releaseStatus = switch (coherenceStatus) {
            case CONSISTENT -> "APPROVE";
            case CONTRADICTED -> "REJECT";
            case UNDERDETERMINED -> "HOLD";
        };
        boolean gatePassed = coherenceStatus == CoherenceStatus.CONSISTENT;
        String confidenceRange = switch (coherenceStatus) {
            case CONSISTENT -> uniqueIdentifierMapping ? "HIGH..HIGH" : "MEDIUM..HIGH";
            case CONTRADICTED, UNDERDETERMINED -> invalidRows > 0 || originalClaim == null
                    ? "LOW..LOW"
                    : "LOW..MEDIUM";
        };
        List<String> decisiveEvidenceIds = switch (coherenceStatus) {
            case CONTRADICTED -> List.copyOf(hardConflictIds);
            case CONSISTENT -> List.copyOf(consistentEvidenceIds);
            case UNDERDETERMINED -> List.of();
        };
        String packetRef = ref((originalClaim == null ? "missing" : originalClaim)
                + "|" + coherenceStatus + "|" + evidenceRefs);

        trace(originalClaim, decisionQuestion, rawRows.size(), validRows.size(), invalidRows,
                independentGroups.size(), coherenceStatus, releaseStatus, confidenceRange, gatePassed,
                queryTraceRefs.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "demo1.verdict-packet.v1");
        out.put("packetRef", packetRef);
        out.put("originalClaimRef", ref(originalClaim));
        out.put("decisionQuestionRef", ref(decisionQuestion));
        out.put("coherenceStatus", coherenceStatus.name());
        out.put("releaseStatus", releaseStatus);
        out.put("verificationGatePassed", gatePassed);
        out.put("confidenceRange", confidenceRange);
        out.put("decisionAuthority", "verification_only");
        out.put("validRowCount", validRows.size());
        out.put("invalidRowCount", invalidRows);
        out.put("independentSourceCount", independentGroups.size());
        out.put("evidenceRefs", List.copyOf(evidenceRefs));
        out.put("consumedQueryTraceRefs", queryTraceRefs.stream().map(EvidenceCoherenceVerifyTool::ref).toList());
        out.put("counterEvidencePacketConsumed", counterEvidencePacketConsumed);
        out.put("decisiveEvidenceIds", decisiveEvidenceIds);
        out.put("unresolvedConflictIds", List.copyOf(unresolvedConflictIds));
        out.put("requiredNextEvidence", List.copyOf(requiredNextEvidence));
        return response(out);
    }

    private static ToolResponse response(Map<String, Object> values) {
        ToolResponse response = ToolResponse.ok();
        values.forEach(response::put);
        return response;
    }

    private static NormalizedEvidence normalize(
            Object raw,
            List<String> queryTraceRefs,
            CounterEvidencePacketStore.EvidenceBinding binding) {
        if (!(raw instanceof Map<?, ?> row)) {
            return null;
        }
        try {
            String evidenceId = required(row.get("evidenceId"));
            required(row.get("claim"));
            String sourceProvenance = required(firstPresent(
                    row.get("sourceProvenanceHash"),
                    firstPresent(row.get("sourceProvenance"),
                            firstPresent(row.get("source/provenance"), row.get("independenceGroupHash")))));
            Instant observedAt = Instant.parse(required(row.get("observedAt")));
            Instant validAt = Instant.parse(required(row.get("validAt")));
            Directness directness = enumValue(Directness.class, row.get("directness"));
            Authority authority = enumValue(Authority.class, row.get("authority"));
            required(firstPresent(
                    row.get("independenceGroup"), row.get("independenceGroupHash")));
            String independenceGroup = ref(sourceProvenance);
            Independence independence = enumValue(Independence.class, row.get("independence"));
            Relation relation = enumValue(Relation.class, row.get("relation"));
            Coverage coverage = enumValue(Coverage.class, row.get("coverage"));
            ConstraintStrength constraintStrength = enumValue(
                    ConstraintStrength.class, row.get("constraintStrength"));
            if (!row.containsKey("uniqueIdentifierMapping")) {
                return null;
            }
            boolean uniqueIdentifierMapping = booleanValue(row.get("uniqueIdentifierMapping"));
            String traceRef = optional(firstPresent(row.get("traceRef"), row.get("queryTraceRef")));
            if (traceRef != null && !queryTraceRefs.contains(traceRef)) {
                return null;
            }
            if (binding != null) {
                if (!binding.evidenceId().equals(evidenceId)
                        || !binding.sourceProvenanceHash().equals(sourceProvenance)
                        || !binding.queryTraceRef().equals(traceRef)
                        || !binding.observedAt().equals(observedAt.toString())) {
                    return null;
                }
                independenceGroup = binding.sourceProvenanceHash();
                independence = Independence.SAME_LINEAGE;
                authority = Authority.SECONDARY;
                directness = Directness.INFERRED;
                coverage = Coverage.PARTIAL;
                constraintStrength = ConstraintStrength.SOFT;
                uniqueIdentifierMapping = false;
                relation = Relation.valueOf(binding.relationHint());
            }
            boolean timeComparable = !validAt.isAfter(observedAt);
            return new NormalizedEvidence(evidenceId, independenceGroup, independence, relation,
                    authority, directness, coverage, constraintStrength, uniqueIdentifierMapping,
                    timeComparable, validAt);
        } catch (IllegalArgumentException | DateTimeException error) {
            return null;
        }
    }

    private static String evidenceId(Object raw) {
        if (!(raw instanceof Map<?, ?> row)) {
            return "";
        }
        String value = optional(row.get("evidenceId"));
        return value == null ? "" : value;
    }

    private static String sessionId(ToolRequest request) {
        return request == null || request.context() == null
                ? "internal-agent"
                : request.context().sessionId();
    }

    private static List<?> rows(Object raw, Set<String> missing) {
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > MAX_ROWS) {
            missing.add("evidence_rows_1_to_100");
            return List.of();
        }
        return values;
    }

    private static List<?> list(Object raw, String missingLabel, Set<String> missing) {
        if (!(raw instanceof List<?> values)) {
            missing.add(missingLabel);
            return List.of();
        }
        return values;
    }

    private static Set<String> labels(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object value : values) {
            String text = optional(value);
            if (text != null) {
                out.add(text.toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    private static List<String> queryTraceRefs(Object raw, Set<String> missing) {
        if (!(raw instanceof List<?> values) || (values.size() != 0 && values.size() != 3)) {
            missing.add("query_trace_refs_zero_or_three");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object value : values) {
            String ref = boundedText(value);
            if (ref == null || out.contains(ref)) {
                missing.add("query_trace_refs_zero_or_three");
                return List.of();
            }
            out.add(ref);
        }
        return List.copyOf(out);
    }

    private static Map<String, Instant> officialEvidence(List<?> rows, Set<String> missing) {
        Map<String, Instant> out = new LinkedHashMap<>();
        for (Object raw : rows) {
            if (!(raw instanceof Map<?, ?> row)) {
                missing.add("valid_official_constraints");
                continue;
            }
            try {
                String evidenceId = required(row.get("evidenceId"));
                boolean applicable = booleanValue(row.get("applicable"));
                Instant effectiveAt = Instant.parse(required(row.get("effectiveAt")));
                if (applicable) {
                    out.put(evidenceId, effectiveAt);
                }
            } catch (RuntimeException error) {
                missing.add("valid_official_constraints");
            }
        }
        return out;
    }

    private static String boundedText(Object raw) {
        String value = optional(raw);
        return value == null || value.length() > MAX_TEXT_LENGTH ? null : value;
    }

    private static String required(Object raw) {
        String value = optional(raw);
        if (value == null || value.length() > 512) {
            throw new IllegalArgumentException("missing normalized field");
        }
        return value;
    }

    private static String optional(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private static Object firstPresent(Object preferred, Object fallback) {
        return optional(preferred) == null ? fallback : preferred;
    }

    private static boolean booleanValue(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw != null && ("true".equalsIgnoreCase(String.valueOf(raw))
                || "false".equalsIgnoreCase(String.valueOf(raw)))) {
            return Boolean.parseBoolean(String.valueOf(raw));
        }
        throw new IllegalArgumentException("invalid boolean");
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object raw) {
        return Enum.valueOf(type, required(raw).toUpperCase(Locale.ROOT));
    }

    private static String ref(String value) {
        if (value != null && value.matches("hash:[0-9a-f]{12}")) {
            return value;
        }
        String hash = SafeRedactor.hashValue(value == null ? "missing" : value);
        return hash.substring(0, Math.min(24, hash.length()));
    }

    private static void trace(String originalClaim,
                              String decisionQuestion,
                              int rowCount,
                              int validRowCount,
                              int invalidRowCount,
                              int independentSourceCount,
                              CoherenceStatus status,
                              String releaseStatus,
                              String confidenceRange,
                              boolean gatePassed,
                              int queryTraceCount) {
        TraceStore.put("evidenceCoherence.claimHash", SafeRedactor.hashValue(originalClaim));
        TraceStore.put("evidenceCoherence.decisionQuestionHash", SafeRedactor.hashValue(decisionQuestion));
        TraceStore.put("evidenceCoherence.rowCount", rowCount);
        TraceStore.put("evidenceCoherence.validRowCount", validRowCount);
        TraceStore.put("evidenceCoherence.invalidRowCount", invalidRowCount);
        TraceStore.put("evidenceCoherence.independentSourceCount", independentSourceCount);
        TraceStore.put("evidenceCoherence.coherenceStatus", status.name());
        TraceStore.put("evidenceCoherence.releaseStatus", releaseStatus);
        TraceStore.put("evidenceCoherence.confidenceRange", confidenceRange);
        TraceStore.put("evidenceCoherence.queryTraceCount", queryTraceCount);
        TraceStore.put("evidenceCoherence.verificationGatePassed", gatePassed);
    }

    private enum CoherenceStatus {
        CONSISTENT,
        CONTRADICTED,
        UNDERDETERMINED
    }

    private enum Relation {
        SUPPORTS,
        CONFLICTS,
        NEUTRAL,
        NOT_COMPARABLE
    }

    private enum Directness {
        DIRECT,
        INFERRED,
        UNKNOWN
    }

    private enum Authority {
        PRIMARY,
        SECONDARY,
        SELF_ASSERTED
    }

    private enum Independence {
        INDEPENDENT,
        SAME_LINEAGE,
        COPY
    }

    private enum Coverage {
        COMPLETE,
        PARTIAL,
        UNKNOWN
    }

    private enum ConstraintStrength {
        HARD_APPLICABLE,
        SOFT,
        UNKNOWN
    }

    private record NormalizedEvidence(String evidenceId,
                                      String independenceGroup,
                                      Independence independence,
                                      Relation relation,
                                      Authority authority,
                                      Directness directness,
                                      Coverage coverage,
                                      ConstraintStrength constraintStrength,
                                      boolean uniqueIdentifierMapping,
                                      boolean timeComparable,
                                      Instant validAt) {
    }
}
