package com.example.lms.orchestration.control;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composes independent findings without voting. Authority and fail-closed
 * invariants decide the outcome; correlated duplicates never gain weight.
 */
@Component
public final class RagGuardProbeComposer {

    static final int MAX_FINDINGS = 56;
    private final RiskBenefitExceptionGate riskBenefitExceptionGate =
            new RiskBenefitExceptionGate();

    public RagActionPlan compose(Collection<RagControlFinding> input) {
        List<RagControlFinding> findings = normalize(input);
        if (findings.isEmpty()) {
            return RagActionPlan.observabilityGap();
        }

        RagControlFinding hard = findings.stream()
                .filter(this::isLockingHardGuard)
                .max(selectionComparator())
                .orElse(null);
        LineageAssessment lineage = lineageAssessment(findings);
        boolean lineageComplete = lineage == LineageAssessment.COMPLETE;
        if (hard != null) {
            return plan(hard.proposedAction(), true, lineageComplete, hard.reasonCode(), findings);
        }
        RagControlFinding nonModelRelease = trustedNonModelRelease(findings);
        if (nonModelRelease != null && lineage != LineageAssessment.CONFLICT) {
            return plan(RagActionPlan.Action.DEGRADE, false, false,
                    nonModelRelease.reasonCode(), findings);
        }
        if (!lineageComplete) {
            // lineage 충돌은 언제나 fail-closed. lineage 누락은 검증이 필요한 응답에서만
            // 보류한다 — 검증 불필요 응답(인사·일반 대화)의 lineage 갭은 관측 한계일 뿐
            // 답변 보류 사유가 아니다.
            boolean verificationNotRequired = findings.stream().anyMatch(finding ->
                    finding.stage() == RagControlFinding.Stage.VERIFICATION
                            && "verification_not_required".equals(finding.reasonCode()));
            if (lineage == LineageAssessment.CONFLICT || !verificationNotRequired) {
                return plan(RagActionPlan.Action.HOLD, false, false,
                        lineage == LineageAssessment.CONFLICT
                                ? "runtime_lineage_conflict"
                                : "runtime_lineage_missing",
                        findings);
            }
            RagControlFinding selectedInGap = selectByAuthority(findings);
            if (selectedInGap == null) {
                return plan(RagActionPlan.Action.DEGRADE, false, false,
                        "runtime_lineage_missing", findings);
            }
            RagActionPlan.Action proposed = selectedInGap.proposedAction();
            if (proposed == RagActionPlan.Action.BLOCK || proposed == RagActionPlan.Action.HOLD) {
                return plan(proposed, false, false, selectedInGap.reasonCode(), findings);
            }
            RiskBenefitExceptionGate.Decision gapDecision = riskBenefitExceptionGate.evaluate(
                    proposed,
                    selectedInGap.riskBenefitContext());
            return plan(
                    gapDecision.selectedAction(),
                    false,
                    false,
                    "runtime_lineage_missing",
                    findings,
                    gapDecision);
        }

        RagControlFinding selected = selectByAuthority(findings);
        if (selected == null) {
            return plan(RagActionPlan.Action.CONTINUE, false, true,
                    "no_actionable_finding", findings);
        }
        RiskBenefitExceptionGate.Decision decision = riskBenefitExceptionGate.evaluate(
                selected.proposedAction(),
                selected.riskBenefitContext());
        return plan(
                decision.selectedAction(),
                false,
                true,
                selected.reasonCode(),
                findings,
                decision);
    }

    private RagControlFinding trustedNonModelRelease(List<RagControlFinding> findings) {
        if (findings.stream().anyMatch(finding ->
                finding.proposedAction() == RagActionPlan.Action.HOLD
                        || finding.proposedAction() == RagActionPlan.Action.BLOCK)) return null;
        long completeKeys = findings.stream()
                .filter(finding -> finding.lineageStatus() == RagControlFinding.LineageStatus.COMPLETE)
                .map(RagControlFinding::lineageKey).distinct().count();
        if (completeKeys > 1) return null;
        RagControlFinding approval = findings.stream()
                .filter(finding -> finding.stage() == RagControlFinding.Stage.VERIFICATION)
                .filter(this::isBoundNonModelFinding).findFirst().orElse(null);
        if (approval == null) return null;
        return findings.stream().anyMatch(finding ->
                finding.stage() == RagControlFinding.Stage.LLM
                        && isBoundNonModelFinding(finding)
                        && approval.reasonCode().equals(finding.reasonCode())
                        && approval.evidence().equals(finding.evidence())) ? approval : null;
    }

    private boolean isBoundNonModelFinding(RagControlFinding finding) {
        String kind = switch (finding.reasonCode()) {
            case "verification_unavailable_excerpt" -> "SUPPORTED_EXCERPT";
            case "verification_unavailable_guidance" -> "VERIFICATION_GUIDANCE";
            default -> "";
        };
        return !kind.isEmpty()
                && "chat-workflow-runtime".equals(finding.sourceId())
                && finding.authority() == RagControlFinding.Authority.VERIFICATION
                && finding.evidenceStatus() == RagControlFinding.EvidenceStatus.OBSERVED
                && finding.failureClass() == RagControlFinding.FailureClass.OBSERVABILITY_GAP
                && finding.proposedAction() == RagActionPlan.Action.DEGRADE
                && finding.lineageStatus() == RagControlFinding.LineageStatus.MISSING
                && kind.equals(finding.evidence().get("nonModelReleaseKind"))
                && Boolean.TRUE.equals(finding.evidence().get("nonModelReleaseBound"))
                && String.valueOf(finding.evidence().get("nonModelReleaseBodyHash"))
                        .matches("hash:[a-f0-9]{12}")
                && String.valueOf(finding.evidence().get("nonModelReleaseRequestHash"))
                        .matches("hash:[a-f0-9]{12}");
    }

    private RagActionPlan plan(
            RagActionPlan.Action action,
            boolean hardGuardLocked,
            boolean lineageComplete,
            String reasonCode,
            List<RagControlFinding> findings) {
        return plan(
                action,
                hardGuardLocked,
                lineageComplete,
                reasonCode,
                findings,
                RiskBenefitExceptionGate.Decision.notEvaluated(action));
    }

    private RagActionPlan plan(
            RagActionPlan.Action action,
            boolean hardGuardLocked,
            boolean lineageComplete,
            String reasonCode,
            List<RagControlFinding> findings,
            RiskBenefitExceptionGate.Decision riskBenefitDecision) {
        return new RagActionPlan(
                action,
                hardGuardLocked,
                lineageComplete,
                action == RagActionPlan.Action.ISOLATE_EVIDENCE,
                action == RagActionPlan.Action.RETRY_ONCE,
                reasonCode,
                findings,
                RagControlRolloutState.Mode.SHADOW,
                0L,
                false,
                riskBenefitDecision);
    }

    private List<RagControlFinding> normalize(Collection<RagControlFinding> input) {
        LinkedHashMap<String, RagControlFinding> distinct = new LinkedHashMap<>();
        if (input != null) {
            for (RagControlFinding finding : input) {
                if (finding != null) {
                    distinct.merge(
                            finding.correlationKey(),
                            finding,
                            this::canonicalEquivalentFinding);
                }
            }
        }
        ArrayList<RagControlFinding> ordered = new ArrayList<>(distinct.values());
        EnumSet<RagControlFinding.Stage> present = EnumSet.noneOf(RagControlFinding.Stage.class);
        ordered.forEach(finding -> present.add(finding.stage()));
        for (RagControlFinding.Stage stage : RagControlFinding.Stage.values()) {
            if (!present.contains(stage)) {
                ordered.add(RagControlFinding.observabilityGap(stage));
            }
        }
        if (ordered.size() > MAX_FINDINGS) {
            ordered.sort(safetyPriorityComparator());
            int dropped = ordered.size() - (MAX_FINDINGS - 1);
            ordered = new ArrayList<>(ordered.subList(0, MAX_FINDINGS - 1));
            ordered.add(new RagControlFinding(
                    "rag-control-composer",
                    RagControlFinding.Stage.ORCHESTRATION,
                    RagControlFinding.FailureClass.OBSERVABILITY_GAP,
                    RagControlFinding.EvidenceStatus.EVIDENCE_NEEDED,
                    RagControlFinding.Authority.VERIFICATION,
                    RagActionPlan.Action.HOLD,
                    "finding_volume_overflow",
                    null,
                    RagControlFinding.LineageStatus.MISSING,
                    Map.of("droppedCount", dropped)));
        }
        ordered.sort(Comparator
                .comparing(RagControlFinding::stage)
                .thenComparingInt(finding -> authorityRank(finding.authority()))
                .thenComparing(RagControlFinding::sourceId)
                .thenComparing(RagControlFinding::reasonCode)
                .thenComparing(finding -> finding.proposedAction().name())
                .thenComparing(stableFindingComparator()));
        return List.copyOf(ordered);
    }

    private RagControlFinding canonicalEquivalentFinding(
            RagControlFinding left,
            RagControlFinding right) {
        int evidenceOrder = safeEvidenceKey(left).compareTo(safeEvidenceKey(right));
        if (evidenceOrder != 0) {
            return evidenceOrder < 0 ? left : right;
        }
        return stableFindingComparator().compare(left, right) <= 0 ? left : right;
    }

    private LineageAssessment lineageAssessment(List<RagControlFinding> findings) {
        EnumSet<RagControlFinding.Stage> covered = EnumSet.noneOf(RagControlFinding.Stage.class);
        boolean observedComplete = false;
        Set<String> completeLineageKeys = new HashSet<>();
        for (RagControlFinding finding : findings) {
            if (finding.lineageStatus() == RagControlFinding.LineageStatus.MISSING
                    || finding.lineageStatus() == RagControlFinding.LineageStatus.PARTIAL) {
                return LineageAssessment.MISSING;
            }
            if (finding.lineageStatus() == RagControlFinding.LineageStatus.COMPLETE) {
                observedComplete = true;
                completeLineageKeys.add(finding.lineageKey());
                if (completeLineageKeys.size() > 1) {
                    return LineageAssessment.CONFLICT;
                }
            }
            if (finding.lineageStatus() == RagControlFinding.LineageStatus.COMPLETE
                    || finding.lineageStatus() == RagControlFinding.LineageStatus.NOT_APPLICABLE) {
                covered.add(finding.stage());
            }
        }
        return observedComplete && covered.size() == RagControlFinding.Stage.values().length
                ? LineageAssessment.COMPLETE
                : LineageAssessment.MISSING;
    }

    private enum LineageAssessment {
        COMPLETE,
        MISSING,
        CONFLICT
    }

    private boolean isLockingHardGuard(RagControlFinding finding) {
        return finding.authority() == RagControlFinding.Authority.HARD_GUARD
                && (finding.proposedAction() == RagActionPlan.Action.BLOCK
                || finding.proposedAction() == RagActionPlan.Action.HOLD);
    }

    private RagControlFinding selectByAuthority(List<RagControlFinding> findings) {
        Map<RagControlFinding.Authority, List<RagControlFinding>> byAuthority =
                new EnumMap<>(RagControlFinding.Authority.class);
        for (RagControlFinding finding : findings) {
            byAuthority.computeIfAbsent(finding.authority(), ignored -> new ArrayList<>()).add(finding);
        }
        for (RagControlFinding.Authority authority : List.of(
                RagControlFinding.Authority.VERIFICATION,
                RagControlFinding.Authority.PROBE,
                RagControlFinding.Authority.DIAGNOSTIC,
                RagControlFinding.Authority.HARD_GUARD)) {
            RagControlFinding selected = byAuthority.getOrDefault(authority, List.of()).stream()
                    .max(selectionComparator())
                    .orElse(null);
            if (selected != null) {
                return selected;
            }
        }
        return null;
    }

    private int authorityRank(RagControlFinding.Authority authority) {
        return switch (authority) {
            case HARD_GUARD -> 0;
            case VERIFICATION -> 1;
            case PROBE -> 2;
            case DIAGNOSTIC -> 3;
        };
    }

    private int severity(RagActionPlan.Action action) {
        return switch (action) {
            case BLOCK -> 60;
            case HOLD -> 50;
            case ISOLATE_EVIDENCE -> 40;
            case DEGRADE -> 30;
            case RETRY_ONCE -> 20;
            case CONTINUE -> 10;
        };
    }

    private Comparator<RagControlFinding> selectionComparator() {
        return Comparator
                .comparingInt((RagControlFinding finding) -> severity(finding.proposedAction()))
                .thenComparing(RagControlFinding::reasonCode)
                .thenComparing(RagControlFinding::sourceId)
                .thenComparing(stableFindingComparator());
    }

    private Comparator<RagControlFinding> safetyPriorityComparator() {
        return Comparator
                .comparingInt((RagControlFinding finding) -> isLockingHardGuard(finding) ? 0 : 1)
                .thenComparingInt(finding -> -severity(finding.proposedAction()))
                .thenComparingInt(finding -> authorityRank(finding.authority()))
                .thenComparing(RagControlFinding::stage)
                .thenComparing(RagControlFinding::sourceId)
                .thenComparing(RagControlFinding::reasonCode)
                .thenComparing(stableFindingComparator());
    }

    private Comparator<RagControlFinding> stableFindingComparator() {
        return Comparator
                .comparing(RagControlFinding::sourceId)
                .thenComparing(RagControlFinding::stage)
                .thenComparing(RagControlFinding::failureClass)
                .thenComparing(RagControlFinding::evidenceStatus)
                .thenComparing(RagControlFinding::authority)
                .thenComparing(RagControlFinding::proposedAction)
                .thenComparing(RagControlFinding::reasonCode)
                .thenComparing(
                        RagControlFinding::lineageKey,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(RagControlFinding::lineageStatus)
                .thenComparing(this::riskBenefitContextKey)
                .thenComparing(this::safeEvidenceKey);
    }

    private String riskBenefitContextKey(RagControlFinding finding) {
        return finding.riskBenefitContext() == null
                ? ""
                : finding.riskBenefitContext().stableKey();
    }

    private String safeEvidenceKey(RagControlFinding finding) {
        StringBuilder key = new StringBuilder();
        finding.evidence().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    appendKeyPart(key, entry.getKey());
                    Object value = entry.getValue();
                    appendKeyPart(key, value == null ? "null" : value.getClass().getName());
                    appendKeyPart(key, String.valueOf(value));
                });
        return key.toString();
    }

    private void appendKeyPart(StringBuilder key, String value) {
        String normalized = value == null ? "" : value;
        key.append(normalized
                        .replace("\\", "\\\\")
                        .replace("\u001e", "\\u001e")
                        .replace("\u001f", "\\u001f"))
                .append('\u001f');
    }
}
