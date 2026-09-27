package com.example.lms.harmony;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class HarmonyBreakLedger {

    private static final Set<String> NON_EVIDENCE_LABELS = Set.of(
            "false",
            "unknown",
            "missing",
            "not_observed",
            "unavailable",
            "none",
            "null",
            "n/a",
            "na",
            "present",
            "disabled",
            "redacted",
            "(redacted)",
            "[redacted]");

    private final HarmonyTraceReader traceReader;
    private final HarmonyEvidenceContract contract;
    private final String contractFailure;

    public HarmonyBreakLedger() {
        this(new HarmonyTraceReader());
    }

    @Autowired
    public HarmonyBreakLedger(HarmonyTraceReader traceReader) {
        this(traceReader, loadContract());
    }

    public HarmonyBreakLedger(ObjectProvider<TraceSnapshotStore> traceSnapshotStoreProvider) {
        this(new HarmonyTraceReader(traceSnapshotStoreProvider));
    }

    private HarmonyBreakLedger(HarmonyTraceReader traceReader, ContractLoad contractLoad) {
        this.traceReader = traceReader == null ? new HarmonyTraceReader() : traceReader;
        this.contract = contractLoad.contract();
        this.contractFailure = contractLoad.failureClass();
    }

    public List<HarmonyScoreSnapshot.HarmonyBreakEntry> evaluate() {
        try {
            return evaluate(traceReader.readFrame());
        } catch (RuntimeException error) {
            TraceStore.put("harmony.breakLedger.traceRead.failed", Boolean.TRUE);
            TraceStore.put("harmony.breakLedger.traceRead.key", "coherentFrame");
            TraceStore.put("harmony.breakLedger.traceRead.errorType", error.getClass().getSimpleName());
            return blockedEntries(
                    "trace_frame_unavailable type=" + error.getClass().getSimpleName());
        }
    }

    List<HarmonyScoreSnapshot.HarmonyBreakEntry> evaluate(HarmonyTraceReader.TraceFrame frame) {
        if (contract == null) {
            return blockedEntries("contract_integrity_failed type=" + contractFailure);
        }
        HarmonyTraceReader.TraceFrame selected = frame == null
                ? HarmonyTraceReader.TraceFrame.missing()
                : frame;
        return contract.breaks().stream()
                .map(definition -> evaluateOne(definition, selected))
                .toList();
    }

    public double totalPenalty(List<HarmonyScoreSnapshot.HarmonyBreakEntry> breaks) {
        if (breaks == null) {
            return contract == null
                    ? 100.0d
                    : contract.breaks().stream()
                    .mapToDouble(HarmonyEvidenceContract.HarmonyBreakDefinition::weight)
                    .sum();
        }
        return breaks.stream()
                .filter(entry -> !"DONE".equals(entry.status()))
                .mapToDouble(HarmonyScoreSnapshot.HarmonyBreakEntry::penaltyScore)
                .sum();
    }

    private HarmonyScoreSnapshot.HarmonyBreakEntry evaluateOne(
            HarmonyEvidenceContract.HarmonyBreakDefinition definition,
            HarmonyTraceReader.TraceFrame frame) {
        try {
            List<HarmonyEvidenceContract.RuntimeRequirement> missing = definition.runtimeRequirements().stream()
                    .filter(requirement -> !isDoneValue(
                            requirement,
                            frame.read(requirement.traceKey()).value()))
                    .toList();
            if (missing.isEmpty()) {
                String evidence = definition.runtimeRequirements().stream()
                        .map(requirement -> requirement.traceKey() + "="
                                + frame.read(requirement.traceKey()).evidenceSource())
                        .collect(Collectors.joining(","));
                return new HarmonyScoreSnapshot.HarmonyBreakEntry(
                        definition.id(),
                        contract.verifiedStatus(),
                        definition.weight(),
                        evidence);
            }
            boolean hashOnly = missing.stream()
                    .map(requirement -> frame.read(requirement.traceKey()).value())
                    .anyMatch(HarmonyBreakLedger::isHashOnly);
            return new HarmonyScoreSnapshot.HarmonyBreakEntry(
                    definition.id(),
                    contract.blockedStatus(),
                    definition.weight(),
                    (hashOnly
                            ? "hash_only_evidence_rejected keys="
                            : "required_evidence_missing_or_invalid keys=")
                            + missing.stream()
                            .map(HarmonyEvidenceContract.RuntimeRequirement::traceKey)
                            .collect(Collectors.joining(",")));
        } catch (RuntimeException error) {
            TraceStore.put("harmony.breakLedger.traceRead.failed", Boolean.TRUE);
            TraceStore.put("harmony.breakLedger.traceRead.key", definition.id());
            TraceStore.put("harmony.breakLedger.traceRead.errorType", error.getClass().getSimpleName());
            return new HarmonyScoreSnapshot.HarmonyBreakEntry(
                    definition.id(),
                    contract.blockedStatus(),
                    definition.weight(),
                    "trace_evidence_read_failed type=" + error.getClass().getSimpleName());
        }
    }

    private static boolean isDoneValue(
            HarmonyEvidenceContract.RuntimeRequirement requirement,
            Object value) {
        return switch (requirement.rule()) {
            case EMPTY_COLLECTION -> value instanceof Collection<?> collection && collection.isEmpty();
            case NON_BLANK_STRING -> isConcreteAuthorityLabel(requirement.traceKey(), value);
            case FINITE_NUMBER -> value instanceof Number number
                    && Double.isFinite(number.doubleValue());
            case ZERO_NUMBER -> value instanceof Number number
                    && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == 0.0d;
            case NON_NEGATIVE_NUMBER -> value instanceof Number number
                    && Double.isFinite(number.doubleValue())
                    && number.doubleValue() >= 0.0d;
            case TRUE_BOOLEAN -> Boolean.TRUE.equals(value);
        };
    }

    private static boolean isConcreteAuthorityLabel(String traceKey, Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("hash:") || NON_EVIDENCE_LABELS.contains(normalized)) {
            return false;
        }
        Object sanitized = SafeRedactor.diagnosticValue(traceKey, trimmed);
        return sanitized instanceof String safe && trimmed.equals(safe);
    }

    String subsystemFor(String breakId) {
        return contract == null ? null : contract.subsystemFor(breakId);
    }

    private List<HarmonyScoreSnapshot.HarmonyBreakEntry> blockedEntries(String reason) {
        TraceStore.put("harmony.evidenceContract.status", "BLOCKED_EVIDENCE");
        if (contract == null) {
            TraceStore.put("harmony.evidenceContract.errorType", contractFailure);
            return List.of(new HarmonyScoreSnapshot.HarmonyBreakEntry(
                    "HB-CONTRACT",
                    "BLOCKED_EVIDENCE",
                    100.0d,
                    reason));
        }
        return contract.breaks().stream()
                .map(definition -> new HarmonyScoreSnapshot.HarmonyBreakEntry(
                        definition.id(),
                        contract.blockedStatus(),
                        definition.weight(),
                        reason))
                .toList();
    }

    private static boolean isHashOnly(Object value) {
        return value instanceof String text
                && text.trim().toLowerCase(Locale.ROOT).startsWith("hash:");
    }

    private static ContractLoad loadContract() {
        try {
            return new ContractLoad(HarmonyEvidenceContract.loadClasspath(), "");
        } catch (RuntimeException error) {
            TraceStore.put("harmony.evidenceContract.status", "BLOCKED_EVIDENCE");
            TraceStore.put("harmony.evidenceContract.errorType", error.getClass().getSimpleName());
            return new ContractLoad(null, error.getClass().getSimpleName());
        }
    }

    private record ContractLoad(HarmonyEvidenceContract contract, String failureClass) {
    }
}
