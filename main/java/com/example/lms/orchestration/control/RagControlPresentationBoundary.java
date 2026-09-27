package com.example.lms.orchestration.control;

import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Applies the control plan once, only at the user-visible chat boundary. */
@Component
public final class RagControlPresentationBoundary {

    static final String CONTROL_FAILURE_NOTICE =
            "응답 처리 상태를 확인할 수 없어 답변 표시를 일시 중단했습니다. 잠시 후 다시 시도해 주세요.";
    static final String MODEL_FAILURE_NOTICE =
            "모델 응답을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    private final RagControlCoordinator coordinator;
    private final RagControlRuntimeAdapter runtimeAdapter;
    private final RagControlProjectionRenderer renderer;
    private final ModelRuntimeHealthTracker runtimeHealthTracker;

    @Autowired
    public RagControlPresentationBoundary(
            RagControlCoordinator coordinator,
            RagControlRuntimeAdapter runtimeAdapter,
            RagControlProjectionRenderer renderer,
            ObjectProvider<ModelRuntimeHealthTracker> runtimeHealthTrackerProvider) {
        this(
                coordinator,
                runtimeAdapter,
                renderer,
                runtimeHealthTrackerProvider == null
                        ? null
                        : runtimeHealthTrackerProvider.getIfAvailable());
    }

    RagControlPresentationBoundary(
            RagControlCoordinator coordinator,
            RagControlRuntimeAdapter runtimeAdapter,
            RagControlProjectionRenderer renderer,
            ModelRuntimeHealthTracker runtimeHealthTracker) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.runtimeAdapter = Objects.requireNonNull(runtimeAdapter, "runtimeAdapter");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.runtimeHealthTracker = runtimeHealthTracker;
    }

    public String project(String semanticAnswer, boolean ragRequested) {
        return projectResult(semanticAnswer, ragRequested).visibleAnswer();
    }

    public Projection projectResult(String semanticAnswer, boolean ragRequested) {
        if (!ragRequested) {
            return Projection.passThrough(semanticAnswer);
        }
        long startedNanos = System.nanoTime();
        RagControlRuntimeAdapter.RuntimeInput input =
                RagControlRuntimeAdapter.currentPresentationInput();
        try {
            if (input == null) {
                // 워크플로 입력 미수집은 제어-계층 갭 — 근거 부족 상태로 위장하지 않는다.
                input = RagControlRuntimeAdapter.RuntimeInput.controlGap();
            } else if (!input.ragRequested()) {
                // 워크플로가 RAG 경로를 타지 않은 응답(인사 스킵, 명시적 OFF)은
                // UI 요청 토글만으로 다시 RAG 미완료로 판정하지 않는다.
                return Projection.passThrough(semanticAnswer);
            }
            List<RagControlFinding> findings = runtimeAdapter.collect(input, runtimeHealthTracker);
            return coordinator.applyCurrentPlan(findings, false, plan -> {
                String rendererInput = plan.shouldStop() ? "" : semanticAnswer;
                String visible = renderer.append(rendererInput, true, plan);
                boolean hardGuardInversion = plan.hardGuardLocked() && !plan.shouldStop();
                boolean disclosureLeak = plan.shouldStop()
                        && !safeHeldProjection(visible);
                coordinator.observe(
                        plan,
                        System.nanoTime() - startedNanos,
                        hardGuardInversion,
                        disclosureLeak);
                if (hardGuardInversion || disclosureLeak) {
                    return Projection.failClosed(plan);
                }
                return Projection.from(semanticAnswer, plan);
            });
        } catch (RuntimeException failure) {
            TraceStore.put("ragControl.presentation.failureClass", "control_projection_failed");
            TraceStore.put("ragControl.presentation.errorType", SafeRedactor.traceLabelOrFallback(
                    failure.getClass().getSimpleName(), "runtime_exception"));
            RagActionPlan failSafe = coordinator.failSafePlan(input != null && input.hardGuardHeld());
            coordinator.observeFailure(failSafe, System.nanoTime() - startedNanos, false, false);
            return Projection.failClosed(failSafe);
        }
    }

    static boolean safeHeldProjection(String visible) {
        if (visible == null) {
            return false;
        }
        int marker = visible.indexOf(RagControlProjectionRenderer.TABLE_MARKER);
        if (marker < 0) {
            return false;
        }
        String normalizedPreamble = normalizeDisclosureText(visible.substring(0, marker));
        String normalizedNotice = normalizeDisclosureText(RagControlProjectionRenderer.heldNotice());
        return !normalizedPreamble.isBlank() && normalizedPreamble.equals(normalizedNotice);
    }

    private static String normalizeDisclosureText(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    public record Projection(
            String visibleAnswer,
            String persistableAnswer,
            boolean held,
            RagActionPlan plan) {

        public Projection {
            visibleAnswer = visibleAnswer == null ? "" : visibleAnswer;
            persistableAnswer = persistableAnswer == null ? "" : persistableAnswer;
        }

        public static Projection passThrough(String semanticAnswer) {
            String value = semanticAnswer == null ? "" : semanticAnswer;
            return new Projection(value, value, false, null);
        }

        public static Projection failClosed(RagActionPlan plan) {
            RagActionPlan safePlan = stoppingPlanOrEnforcedHold(plan);
            String notice = heldNoticeFor(safePlan);
            return new Projection(notice, notice, true, safePlan);
        }

        private static RagActionPlan stoppingPlanOrEnforcedHold(RagActionPlan plan) {
            if (plan != null && plan.shouldStop()) {
                return plan;
            }
            RagActionPlan source = plan == null ? RagActionPlan.observabilityGap() : plan;
            return new RagActionPlan(
                    RagActionPlan.Action.HOLD,
                    plan != null && plan.hardGuardLocked(),
                    plan != null && plan.lineageComplete(),
                    false,
                    false,
                    "control_projection_failed",
                    source.findings(),
                    source.rolloutMode(),
                    source.rolloutGeneration(),
                    true);
        }

        private static Projection from(
                String semanticAnswer,
                RagActionPlan plan) {
            if (plan != null && plan.shouldStop()) {
                String notice = heldNoticeFor(plan);
                return new Projection(notice, notice, true, plan);
            }
            // Keep the checked plan for diagnostics; never append its internal table to the answer.
            return new Projection(semanticAnswer, semanticAnswer, false, plan);
        }

        /**
         * 보류 사유 구분: 제어-계층 장애(입력 미수집·투영 실패·관측 갭)는 근거 부족과
         * 다른 문구로 보여 사용자가 원인을 구별할 수 있게 한다.
         */
        static String heldNoticeFor(RagActionPlan plan) {
            String reason = plan == null ? "" : plan.reasonCode();
            return switch (reason) {
                case "control_projection_failed", "observability_gap", "control_gap",
                        "runtime_lineage_missing", "runtime_lineage_conflict" ->
                        RagControlPresentationBoundary.CONTROL_FAILURE_NOTICE;
                case "model_blank", "silent_failure" ->
                        RagControlPresentationBoundary.MODEL_FAILURE_NOTICE;
                default -> RagControlProjectionRenderer.heldNotice();
            };
        }
    }
}
