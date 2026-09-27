package com.example.lms.orchestration.control;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugProbeType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Strict allowlist projection for the operator-only failure-signal SSE view. */
@Component
public final class RagControlFailureSignalProjector {

    public static final String SCHEMA = "failure-signal.v1";

    private static final Set<String> STAGES = enumNames(RagControlFinding.Stage.values());
    private static final Set<String> EVIDENCE_STATUSES = enumNames(RagControlFinding.EvidenceStatus.values());
    private static final Set<String> FAILURE_CLASSES = enumNames(RagControlFinding.FailureClass.values());
    private static final Set<String> ACTIONS = enumNames(RagActionPlan.Action.values());
    private static final Set<String> ROLLOUT_MODES = enumNames(RagControlRolloutState.Mode.values());
    private static final Set<String> STATUSES = Set.of(
            "OBSERVED", "DEGRADED", "RETRY_PLANNED", "EVIDENCE_ISOLATED", "BLOCKED", "HELD");
    private static final Set<String> ANSWER_IMPACTS = Set.of(
            "shadow_only",
            "observation_only",
            "continue",
            "evidence_reduced",
            "same_provider_retry_once",
            "local_read_only_isolation",
            "answer_blocked",
            "answer_held");
    private static final Set<String> REASON_CODES = Set.of(
            "unclassified",
            "observability_gap",
            "finding_volume_overflow",
            "runtime_lineage_missing",
            "runtime_lineage_conflict",
            "no_actionable_finding",
            "rag_requested",
            "workflow_final_boundary_reached",
            "zero_result",
            "after_filter_starvation",
            "retrieval_evidence_observed",
            "existing_release_guard_hold",
            "citation_miss",
            "citable_evidence_observed",
            "model_blank",
            "runtime_lineage_verified",
            "verification_outcome_missing",
            "verification_rejected",
            "verification_accepted",
            "silent_failure",
            "final_answer_observed",
            "provider_disabled",
            "provider_timeout",
            "provider_rate_limit",
            "provider_circuit_open",
            "context_contamination",
            "policy_denied");

    public Optional<FailureSignal> project(DebugEvent event) {
        if (event == null || event.probe() != DebugProbeType.ORCHESTRATION
                || event.data() == null || !SCHEMA.equals(event.data().get("schema"))) {
            return Optional.empty();
        }
        Map<String, Object> data = event.data();
        String eventId = strictToken(event.id(), "[A-Za-z0-9._:-]{1,96}");
        String stage = allowed(data.get("stage"), STAGES);
        String status = allowed(data.get("status"), STATUSES);
        String evidenceStatus = allowed(data.get("evidenceStatus"), EVIDENCE_STATUSES);
        String failureClass = allowed(data.get("failureClass"), FAILURE_CLASSES);
        String action = allowed(data.get("action"), ACTIONS);
        String reasonCode = allowed(data.get("reasonCode"), REASON_CODES);
        String answerImpact = allowed(data.get("answerImpact"), ANSWER_IMPACTS);
        String rolloutMode = allowed(data.get("rolloutMode"), ROLLOUT_MODES);
        Boolean hardGuard = data.get("hardGuard") instanceof Boolean value ? value : null;
        if (eventId == null || event.ts() == null || stage == null || status == null
                || evidenceStatus == null || failureClass == null || action == null
                || reasonCode == null || answerImpact == null || rolloutMode == null
                || hardGuard == null) {
            return Optional.empty();
        }
        return Optional.of(new FailureSignal(
                SCHEMA,
                eventId,
                event.ts(),
                safeHash(event.requestId()),
                safeHash(event.traceId()),
                stage,
                status,
                evidenceStatus,
                failureClass,
                action,
                reasonCode,
                answerImpact,
                rolloutMode,
                hardGuard));
    }

    private static String safeHash(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return text.matches("(?i)hash:[a-f0-9]{12,64}") ? text.toLowerCase() : "hash:missing";
    }

    private static String allowed(Object value, Set<String> allowed) {
        String text = value == null ? null : String.valueOf(value);
        return text != null && allowed.contains(text) ? text : null;
    }

    private static String strictToken(Object value, String pattern) {
        String text = value == null ? null : String.valueOf(value);
        return text != null && text.matches(pattern) ? text : null;
    }

    private static Set<String> enumNames(Object[] values) {
        return Arrays.stream(values).map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    public record FailureSignal(
            String schema,
            String eventId,
            Instant ts,
            String requestIdHash,
            String traceIdHash,
            String stage,
            String status,
            String evidenceStatus,
            String failureClass,
            String action,
            String reasonCode,
            String answerImpact,
            String rolloutMode,
            boolean hardGuard) {
    }
}
