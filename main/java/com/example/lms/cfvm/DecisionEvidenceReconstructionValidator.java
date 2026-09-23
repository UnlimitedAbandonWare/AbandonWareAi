package com.example.lms.cfvm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates one bounded, redacted decision/evidence relation packet.
 *
 * <p>The validator owns no storage. Callers pass count/hash-only rows already
 * held by {@code TraceStore}; the returned summary is safe for operational
 * diagnostics.</p>
 */
final class DecisionEvidenceReconstructionValidator {

    private DecisionEvidenceReconstructionValidator() {
    }

    static Summary audit(
            Object rawDecisions,
            Object rawEvidence,
            Object rawRelations,
            Object rawLineage,
            Object rawFinalResponses) {
        List<Map<String, Object>> decisions = rows(rawDecisions);
        List<Map<String, Object>> evidence = rows(rawEvidence);
        List<Map<String, Object>> relations = rows(rawRelations);
        List<Map<String, Object>> lineage = rows(rawLineage);
        List<Map<String, Object>> finalResponses = rows(rawFinalResponses);

        Set<String> evidenceIds = new LinkedHashSet<>();
        for (Map<String, Object> row : evidence) {
            addIfPresent(evidenceIds, text(row.get("evidenceId")));
        }

        Map<String, Map<String, Object>> relationsById = new LinkedHashMap<>();
        Set<String> duplicateRelationIds = new LinkedHashSet<>();
        for (Map<String, Object> row : relations) {
            String relationId = text(row.get("relationId"));
            if (relationId.isBlank()) {
                continue;
            }
            if (relationsById.putIfAbsent(relationId, row) != null) {
                duplicateRelationIds.add(relationId);
            }
        }

        Set<String> knownDecisionIds = new LinkedHashSet<>();
        for (Map<String, Object> row : decisions) {
            addIfPresent(knownDecisionIds, text(row.get("decisionId")));
        }

        long reconstructableCount = 0L;
        long missingEvidenceCount = 0L;
        long orphanRelationCount = 0L;
        long lineageMismatchCount = 0L;
        long providerAttemptCount = 0L;
        long providerResponseCount = 0L;
        long linkedDecisionCount = 0L;
        long providerResponseWithoutAttemptCount = 0L;
        long providerAttemptWithoutResponseCount = 0L;
        long requestHashMismatchCount = 0L;
        long optionsHashMismatchCount = 0L;
        long decisionLineageMissingCount = 0L;
        long linkedFinalResponseCount = 0L;
        long missingFinalResponseCount = 0L;
        long finalResponseMismatchCount = 0L;
        long runtimeLineagePassCount = 0L;
        Set<String> reasonCodes = new LinkedHashSet<>();
        boolean hasUnlinkedLineageRows = false;
        for (Map<String, Object> row : lineage) {
            boolean providerAttemptObserved = bool(row.get("providerAttemptObserved"));
            boolean responseObserved = bool(row.get("responseObserved"));
            if (providerAttemptObserved) {
                providerAttemptCount++;
            }
            if (responseObserved) {
                providerResponseCount++;
            }
            if (responseObserved && !providerAttemptObserved) {
                providerResponseWithoutAttemptCount++;
                reasonCodes.add("provider_response_without_attempt");
            }
            if (providerAttemptObserved && !responseObserved) {
                providerAttemptWithoutResponseCount++;
                reasonCodes.add("provider_attempt_without_response");
            }
            String rowDecisionId = text(row.get("decisionId"));
            if (rowDecisionId.isBlank() || !knownDecisionIds.contains(rowDecisionId)) {
                hasUnlinkedLineageRows = true;
            }
        }

        for (Map<String, Object> decision : decisions) {
            String decisionId = text(decision.get("decisionId"));
            Set<String> relationIds = strings(decision.get("relationIds"));
            String requestIdHash = text(decision.get("requestIdHash"));
            Set<String> optionsHashes = strings(decision.get("optionsHashes"));
            boolean evidenceRequired = bool(decision.get("evidenceRequired"));
            boolean lineageRequired = bool(decision.get("lineageRequired"));

            boolean missingEvidence = false;
            boolean orphanRelation = decisionId.isBlank();
            boolean lineageMismatch = false;
            boolean finalResponseMismatch = false;
            boolean runtimeLineagePass = !lineageRequired;
            Set<String> linkedEvidenceIds = new LinkedHashSet<>();

            if (evidenceRequired && relationIds.isEmpty()) {
                orphanRelation = true;
                reasonCodes.add("orphan_decision");
            }

            for (String relationId : relationIds) {
                Map<String, Object> relation = relationsById.get(relationId);
                if (relation == null
                        || duplicateRelationIds.contains(relationId)
                        || !decisionId.equals(text(relation.get("decisionId")))) {
                    orphanRelation = true;
                    reasonCodes.add("orphan_relation");
                    continue;
                }
                String evidenceId = text(relation.get("evidenceId"));
                if (evidenceId.isBlank() || !evidenceIds.contains(evidenceId)) {
                    missingEvidence = true;
                    reasonCodes.add("missing_evidence");
                } else {
                    linkedEvidenceIds.add(evidenceId);
                }
            }

            for (Map<String, Object> relation : relations) {
                if (decisionId.equals(text(relation.get("decisionId")))
                        && !relationIds.contains(text(relation.get("relationId")))) {
                    orphanRelation = true;
                    reasonCodes.add("orphan_relation");
                }
            }

            if (lineageRequired) {
                if (requestIdHash.isBlank()) {
                    lineageMismatch = true;
                    reasonCodes.add("request_hash_missing");
                }
                if (optionsHashes.isEmpty()) {
                    lineageMismatch = true;
                    reasonCodes.add("options_hash_missing");
                }
                List<Map<String, Object>> decisionLineage = lineageForDecision(lineage, decisionId);
                if (decisionLineage.isEmpty()) {
                    decisionLineageMissingCount++;
                    lineageMismatch = true;
                    reasonCodes.add(lineage.isEmpty()
                            ? "decision_lineage_missing"
                            : "decision_lineage_unlinked");
                }
                Set<String> lineageTuples = new LinkedHashSet<>();
                Set<Long> attemptOrdinals = new LinkedHashSet<>();
                boolean providerAttemptObserved = false;
                boolean providerResponseObserved = false;
                for (Map<String, Object> row : decisionLineage) {
                    String lineageRequestHash = text(row.get("requestIdHash"));
                    String lineageOptionsHash = text(row.get("optionsHash"));
                    long attemptOrdinal = number(row.get("attemptOrdinal"));
                    String tuple = lineageRequestHash + "|" + lineageOptionsHash + "|" + attemptOrdinal;
                    boolean duplicateTuple = !lineageTuples.add(tuple);
                    boolean duplicateOrdinal = attemptOrdinal >= 0L && !attemptOrdinals.add(attemptOrdinal);
                    if (duplicateTuple || duplicateOrdinal) {
                        lineageMismatch = true;
                        reasonCodes.add("duplicate_lineage");
                    }
                    if (!requestIdHash.isBlank() && !requestIdHash.equals(lineageRequestHash)) {
                        requestHashMismatchCount++;
                        lineageMismatch = true;
                        reasonCodes.add("request_hash_mismatch");
                    }
                    if (!optionsHashes.isEmpty() && !optionsHashes.contains(lineageOptionsHash)) {
                        optionsHashMismatchCount++;
                        lineageMismatch = true;
                        reasonCodes.add("options_hash_mismatch");
                    }
                    boolean rowProviderAttemptObserved = bool(row.get("providerAttemptObserved"));
                    boolean rowResponseObserved = bool(row.get("responseObserved"));
                    providerAttemptObserved |= rowProviderAttemptObserved;
                    providerResponseObserved |= rowResponseObserved;
                    if (rowResponseObserved && !rowProviderAttemptObserved) {
                        lineageMismatch = true;
                        reasonCodes.add("provider_response_without_attempt");
                    }
                    if (rowProviderAttemptObserved && !rowResponseObserved) {
                        lineageMismatch = true;
                        reasonCodes.add("provider_attempt_without_response");
                    }
                }
                if (hasUnlinkedLineageRows) {
                    lineageMismatch = true;
                    reasonCodes.add("decision_lineage_unlinked");
                }
                if (!providerAttemptObserved) {
                    lineageMismatch = true;
                    reasonCodes.add("provider_attempt_missing");
                }
                if (!providerResponseObserved) {
                    lineageMismatch = true;
                    reasonCodes.add("provider_response_missing");
                }
                runtimeLineagePass = !lineageMismatch;
                if (runtimeLineagePass && !decisionLineage.isEmpty()) {
                    linkedDecisionCount++;
                }
            }

            List<Map<String, Object>> decisionFinalResponses = finalResponsesForDecision(
                    finalResponses, decisionId);
            if (decisionFinalResponses.isEmpty()) {
                missingFinalResponseCount++;
                finalResponseMismatch = true;
                reasonCodes.add("final_response_missing");
            } else if (decisionFinalResponses.size() != 1) {
                finalResponseMismatch = true;
                reasonCodes.add("duplicate_final_response");
            } else {
                Map<String, Object> finalResponse = decisionFinalResponses.get(0);
                String responseId = text(finalResponse.get("responseId"));
                String contentHash = text(finalResponse.get("contentHash"));
                Set<String> responseEvidenceIds = strings(finalResponse.get("evidenceIds"));
                if (responseId.isBlank() || contentHash.isBlank()) {
                    finalResponseMismatch = true;
                    reasonCodes.add("final_response_hash_missing");
                }
                if (!responseEvidenceIds.equals(linkedEvidenceIds)) {
                    finalResponseMismatch = true;
                    reasonCodes.add("final_response_evidence_mismatch");
                }
                if (!finalResponseMismatch) {
                    linkedFinalResponseCount++;
                }
            }

            boolean evidenceReconstructable = !missingEvidence && !orphanRelation;
            if (evidenceReconstructable != runtimeLineagePass) {
                reasonCodes.add("decision_evidence_lineage_disagreement");
            }
            if (runtimeLineagePass) {
                runtimeLineagePassCount++;
            }

            if (missingEvidence) {
                missingEvidenceCount++;
            }
            if (orphanRelation) {
                orphanRelationCount++;
            }
            if (lineageMismatch) {
                lineageMismatchCount++;
            }
            if (finalResponseMismatch) {
                finalResponseMismatchCount++;
            }
            if (!missingEvidence && !orphanRelation && !lineageMismatch && !finalResponseMismatch) {
                reconstructableCount++;
                reasonCodes.add("reconstructable");
            }
        }

        for (Map<String, Object> relation : relations) {
            String relationDecisionId = text(relation.get("decisionId"));
            if (relationDecisionId.isBlank() || !knownDecisionIds.contains(relationDecisionId)) {
                orphanRelationCount++;
                reasonCodes.add("orphan_relation");
            }
        }

        long checkedDecisionCount = decisions.size();
        if (checkedDecisionCount == 0L) {
            reasonCodes.add("no_decisions");
        }
        double reconstructionRate = checkedDecisionCount == 0L
                ? 0.0d
                : (double) reconstructableCount / (double) checkedDecisionCount;
        double runtimeLineagePassRate = checkedDecisionCount == 0L
                ? 0.0d
                : (double) runtimeLineagePassCount / (double) checkedDecisionCount;
        return new Summary(
                checkedDecisionCount,
                reconstructableCount,
                missingEvidenceCount,
                orphanRelationCount,
                lineageMismatchCount,
                providerAttemptCount,
                providerResponseCount,
                linkedDecisionCount,
                providerResponseWithoutAttemptCount,
                providerAttemptWithoutResponseCount,
                requestHashMismatchCount,
                optionsHashMismatchCount,
                decisionLineageMissingCount,
                finalResponses.size(),
                linkedFinalResponseCount,
                missingFinalResponseCount,
                finalResponseMismatchCount,
                reconstructionRate,
                runtimeLineagePassRate,
                List.copyOf(reasonCodes));
    }

    private static List<Map<String, Object>> lineageForDecision(
            List<Map<String, Object>> lineage,
            String decisionId) {
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> row : lineage) {
            String rowDecisionId = text(row.get("decisionId"));
            if (!rowDecisionId.isBlank() && rowDecisionId.equals(decisionId)) {
                selected.add(row);
            }
        }
        return selected;
    }

    private static List<Map<String, Object>> finalResponsesForDecision(
            List<Map<String, Object>> finalResponses,
            String decisionId) {
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> row : finalResponses) {
            String rowDecisionId = text(row.get("decisionId"));
            if (!rowDecisionId.isBlank() && rowDecisionId.equals(decisionId)) {
                selected.add(row);
            }
        }
        return selected;
    }

    private static List<Map<String, Object>> rows(Object raw) {
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> source)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (entry.getKey() != null) {
                    row.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            rows.add(Map.copyOf(row));
        }
        return List.copyOf(rows);
    }

    private static Set<String> strings(Object raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            for (Object value : collection) {
                addIfPresent(values, text(value));
            }
        } else {
            addIfPresent(values, text(raw));
        }
        return values;
    }

    private static void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean flag && flag;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }

    record Summary(
            long checkedDecisionCount,
            long reconstructableCount,
            long missingEvidenceCount,
            long orphanRelationCount,
            long lineageMismatchCount,
            long providerAttemptCount,
            long providerResponseCount,
            long linkedDecisionCount,
            long providerResponseWithoutAttemptCount,
            long providerAttemptWithoutResponseCount,
            long requestHashMismatchCount,
            long optionsHashMismatchCount,
            long decisionLineageMissingCount,
            long finalResponseCount,
            long linkedFinalResponseCount,
            long missingFinalResponseCount,
            long finalResponseMismatchCount,
            double reconstructionRate,
            double runtimeLineagePassRate,
            List<String> reasonCodes) {

        Map<String, Object> toTraceMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("checkedDecisionCount", checkedDecisionCount);
            out.put("reconstructableCount", reconstructableCount);
            out.put("missingEvidenceCount", missingEvidenceCount);
            out.put("orphanRelationCount", orphanRelationCount);
            out.put("lineageMismatchCount", lineageMismatchCount);
            out.put("providerAttemptCount", providerAttemptCount);
            out.put("providerAttemptCountMeaning", "observed_provider_lineage_rows");
            out.put("providerAttemptCoverage", providerAttemptCount == 0 ? "not_observed" : "observed_partial");
            out.put("providerResponseCount", providerResponseCount);
            out.put("linkedDecisionCount", linkedDecisionCount);
            out.put("providerResponseWithoutAttemptCount", providerResponseWithoutAttemptCount);
            out.put("providerAttemptWithoutResponseCount", providerAttemptWithoutResponseCount);
            out.put("requestHashMismatchCount", requestHashMismatchCount);
            out.put("optionsHashMismatchCount", optionsHashMismatchCount);
            out.put("decisionLineageMissingCount", decisionLineageMissingCount);
            out.put("finalResponseCount", finalResponseCount);
            out.put("linkedFinalResponseCount", linkedFinalResponseCount);
            out.put("missingFinalResponseCount", missingFinalResponseCount);
            out.put("finalResponseMismatchCount", finalResponseMismatchCount);
            out.put("reconstructionRate", reconstructionRate);
            out.put("runtimeLineagePassRate", runtimeLineagePassRate);
            out.put("reasonCodes", reasonCodes);
            return Map.copyOf(out);
        }
    }
}
