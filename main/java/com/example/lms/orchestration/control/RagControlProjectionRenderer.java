package com.example.lms.orchestration.control;

import org.springframework.stereotype.Component;

import java.util.Comparator;

/** Renders the fixed, non-raw seven-stage user projection. */
@Component
public final class RagControlProjectionRenderer {

    public static final String TABLE_MARKER = "<!-- rag-control-projection:v1 -->";
    private static final String HELD_NOTICE =
            "\uac80\uc99d\ub41c \uadfc\uac70\uac00 \ucd94\uac00\ub85c \ud544\uc694\ud574 \uc751\ub2f5 \ubcf8\ubb38\uc744 \ubcf4\ub958\ud588\uc2b5\ub2c8\ub2e4.";

    public static String heldNotice() {
        return HELD_NOTICE;
    }

    public String append(String answer, boolean ragRuntime, RagActionPlan inputPlan) {
        if (!ragRuntime) {
            return answer;
        }
        return renderProjection(answer, inputPlan);
    }

    static String renderProjection(String answer, RagActionPlan inputPlan) {
        RagActionPlan plan = inputPlan == null ? RagActionPlan.observabilityGap() : inputPlan;
        String semanticAnswer = stripExistingProjection(answer);
        StringBuilder out = new StringBuilder();
        out.append(plan.shouldStop() ? HELD_NOTICE : semanticAnswer);
        out.append("\n\n").append(TABLE_MARKER);
        out.append("\n\n### RAG 안전·증거 상태\n\n")
                .append("| 단계 | 상태 | 근거 수준 | 실패 분류 | 적용 조치 | 답변 영향 |\n")
                .append("| --- | --- | --- | --- | --- | --- |\n");
        for (RagControlFinding.Stage stage : RagControlFinding.Stage.values()) {
            RagControlFinding finding = plan.findings().stream()
                    .filter(candidate -> candidate.stage() == stage)
                    .max(Comparator
                            .comparingInt((RagControlFinding candidate) -> decisionRank(candidate, plan))
                            .thenComparingInt(candidate -> authorityRank(candidate.authority()))
                            .thenComparingInt(candidate -> actionRank(candidate.proposedAction())))
                    .orElse(null);
            appendRow(out, stage, finding, plan);
        }
        return out.toString();
    }

    private static void appendRow(
            StringBuilder out,
            RagControlFinding.Stage stage,
            RagControlFinding finding,
            RagActionPlan plan) {
        if (finding == null) {
            out.append("| ").append(stage.name())
                    .append(" | NOT_OBSERVED | EVIDENCE_NEEDED | OBSERVABILITY_GAP | HOLD")
                    .append(" | 검증 전 정상으로 간주하지 않음 |\n");
            return;
        }
        RagActionPlan.Action action = finding.proposedAction();
        boolean observabilityGap = finding.failureClass() == RagControlFinding.FailureClass.OBSERVABILITY_GAP
                && finding.evidenceStatus() == RagControlFinding.EvidenceStatus.EVIDENCE_NEEDED;
        boolean enforced = plan.enforced() || plan.hardGuardLocked();
        boolean terminalApplied = plan.shouldStop()
                && (action == RagActionPlan.Action.BLOCK || action == RagActionPlan.Action.HOLD);
        boolean observationOnly = action != RagActionPlan.Action.CONTINUE && !terminalApplied;
        String applied = !enforced
                ? "SHADOW_ONLY(" + action.name() + ')'
                : observationOnly
                        ? "OBSERVED_ONLY(" + action.name() + ')'
                        : action.name();
        out.append("| ").append(stage.name())
                .append(" | ").append(observabilityGap
                        ? "NOT_OBSERVED"
                        : observationOnly ? "OBSERVED" : status(action))
                .append(" | ").append(finding.evidenceStatus().name())
                .append(" | ").append(finding.failureClass().name())
                .append(" | ").append(applied)
                .append(" | ").append(answerImpact(action, enforced, terminalApplied))
                .append(" |\n");
    }

    private static String stripExistingProjection(String answer) {
        String value = answer == null ? "" : answer;
        int marker = value.indexOf(TABLE_MARKER);
        return marker < 0 ? value : value.substring(0, marker).stripTrailing();
    }

    private static String status(RagActionPlan.Action action) {
        return switch (action) {
            case CONTINUE -> "OBSERVED";
            case DEGRADE -> "DEGRADED";
            case RETRY_ONCE -> "RETRY_PLANNED";
            case ISOLATE_EVIDENCE -> "EVIDENCE_ISOLATED";
            case BLOCK -> "BLOCKED";
            case HOLD -> "HELD";
        };
    }

    private static String answerImpact(
            RagActionPlan.Action action,
            boolean enforced,
            boolean terminalApplied) {
        if (!enforced) {
            return "Shadow 관찰; 핵심 답변 변경 없음";
        }
        if (action != RagActionPlan.Action.CONTINUE && !terminalApplied) {
            return "execution receipt missing; answer unchanged";
        }
        return switch (action) {
            case CONTINUE -> "검증된 경로로 계속";
            case DEGRADE -> "불확실한 근거 사용 축소";
            case RETRY_ONCE -> "동일 공급자 재시도 1회 한정";
            case ISOLATE_EVIDENCE -> "로컬·읽기 전용 근거만 격리 확인";
            case BLOCK -> "핵심 답변 차단";
            case HOLD -> "추가 근거 전 확정 보류";
        };
    }

    private static int authorityRank(RagControlFinding.Authority authority) {
        return switch (authority) {
            case HARD_GUARD -> 40;
            case VERIFICATION -> 30;
            case PROBE -> 20;
            case DIAGNOSTIC -> 10;
        };
    }

    private static int decisionRank(RagControlFinding finding, RagActionPlan plan) {
        return finding != null
                && plan != null
                && finding.proposedAction() == plan.action()
                && finding.reasonCode().equals(plan.reasonCode())
                ? 1
                : 0;
    }

    private static int actionRank(RagActionPlan.Action action) {
        return switch (action) {
            case BLOCK -> 60;
            case HOLD -> 50;
            case ISOLATE_EVIDENCE -> 40;
            case DEGRADE -> 30;
            case RETRY_ONCE -> 20;
            case CONTINUE -> 10;
        };
    }
}
