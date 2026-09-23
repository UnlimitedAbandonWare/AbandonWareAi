package com.example.lms.orchestration.control;

import com.example.lms.trace.SafeRedactor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A bounded, redacted observation emitted by one guard, probe, verifier, or
 * diagnostic source. Raw prompts, responses, identifiers, and exceptions are
 * deliberately outside this contract.
 */
public record RagControlFinding(
        String sourceId,
        Stage stage,
        FailureClass failureClass,
        EvidenceStatus evidenceStatus,
        Authority authority,
        RagActionPlan.Action proposedAction,
        String reasonCode,
        String lineageKey,
        LineageStatus lineageStatus,
        Map<String, Object> evidence,
        RiskBenefitExceptionGate.RuleContext riskBenefitContext) {

    private static final int MAX_EVIDENCE_ITEMS = 12;
    private static final int MAX_EVIDENCE_TEXT = 160;

    public RagControlFinding(
            String sourceId,
            Stage stage,
            FailureClass failureClass,
            EvidenceStatus evidenceStatus,
            Authority authority,
            RagActionPlan.Action proposedAction,
            String reasonCode,
            String lineageKey,
            LineageStatus lineageStatus,
            Map<String, Object> evidence) {
        this(
                sourceId,
                stage,
                failureClass,
                evidenceStatus,
                authority,
                proposedAction,
                reasonCode,
                lineageKey,
                lineageStatus,
                evidence,
                null);
    }

    public RagControlFinding {
        sourceId = SafeRedactor.traceLabelOrFallback(sourceId, "unknown_source");
        stage = Objects.requireNonNullElse(stage, Stage.ORCHESTRATION);
        failureClass = Objects.requireNonNullElse(failureClass, FailureClass.UNCLASSIFIED);
        evidenceStatus = Objects.requireNonNullElse(evidenceStatus, EvidenceStatus.EVIDENCE_NEEDED);
        authority = Objects.requireNonNullElse(authority, Authority.DIAGNOSTIC);
        proposedAction = Objects.requireNonNullElse(proposedAction, RagActionPlan.Action.HOLD);
        reasonCode = normalizeReasonCode(reasonCode);
        lineageStatus = Objects.requireNonNullElse(lineageStatus, LineageStatus.MISSING);
        lineageKey = sanitizeLineage(lineageKey);
        if (lineageStatus == LineageStatus.COMPLETE && lineageKey == null) {
            lineageStatus = LineageStatus.MISSING;
        }
        evidence = sanitizeEvidence(evidence);
    }

    public String correlationKey() {
        String key = sourceId + '|' + stage + '|' + failureClass + '|' + evidenceStatus + '|'
                + authority + '|' + proposedAction + '|' + reasonCode + '|' + lineageStatus + '|'
                + (lineageKey == null ? "missing" : lineageKey);
        return riskBenefitContext == null
                ? key
                : key + "|riskBenefit:" + riskBenefitContext.stableKey();
    }

    public static RagControlFinding observabilityGap(Stage stage) {
        return new RagControlFinding(
                "rag-control-composer",
                stage,
                FailureClass.OBSERVABILITY_GAP,
                EvidenceStatus.EVIDENCE_NEEDED,
                Authority.VERIFICATION,
                RagActionPlan.Action.HOLD,
                "observability_gap",
                null,
                LineageStatus.MISSING,
                Map.of());
    }

    private static String sanitizeLineage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("hash:[a-fA-F0-9]{12,64}")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return SafeRedactor.hashValue(trimmed);
    }

    private static String normalizeReasonCode(String value) {
        if (value == null || value.isBlank()) {
            return "unclassified";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            return "unclassified";
        }
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    private static Map<String, Object> sanitizeEvidence(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (safe.size() >= MAX_EVIDENCE_ITEMS || entry == null || entry.getKey() == null) {
                break;
            }
            String key = SafeRedactor.traceLabelOrFallback(entry.getKey(), "field");
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (!(value instanceof String || value instanceof Number
                    || value instanceof Boolean || value instanceof Enum<?>)) {
                value = "unsupported";
            }
            Object diagnostic = SafeRedactor.diagnosticValue(key, value, MAX_EVIDENCE_TEXT);
            if (diagnostic != null) {
                safe.put(key, diagnostic);
            }
        }
        return Map.copyOf(safe);
    }

    public enum Stage {
        REQUEST,
        ORCHESTRATION,
        RETRIEVAL,
        PROMPT_EVIDENCE,
        LLM,
        VERIFICATION,
        FINAL
    }

    public enum FailureClass {
        NONE,
        PROVIDER_DISABLED,
        ZERO_RESULT,
        AFTER_FILTER_STARVATION,
        TIMEOUT,
        RATE_LIMIT,
        CIRCUIT_OPEN,
        CITATION_MISS,
        MODEL_BLANK,
        CONTEXT_CONTAMINATION,
        SILENT_FAILURE,
        POLICY_DENIED,
        OBSERVABILITY_GAP,
        UNCLASSIFIED
    }

    public enum EvidenceStatus {
        OBSERVED,
        INFERRED,
        VERIFIED,
        EVIDENCE_NEEDED
    }

    public enum Authority {
        HARD_GUARD,
        VERIFICATION,
        PROBE,
        DIAGNOSTIC
    }

    public enum LineageStatus {
        COMPLETE,
        PARTIAL,
        MISSING,
        NOT_APPLICABLE
    }
}
