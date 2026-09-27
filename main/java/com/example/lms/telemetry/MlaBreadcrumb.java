package com.example.lms.telemetry;

import com.example.lms.guard.ConversationFrameV1;
import com.example.lms.guard.InteractionEvidencePolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MlaBreadcrumb {
    private static final Logger log = LoggerFactory.getLogger(MlaBreadcrumb.class);
    private static final Set<String> INTERACTION_CONTAINMENT_STATUSES = Set.of(
            "NOT_REQUIRED",
            "CLEAN",
            "QUARANTINED",
            "INSUFFICIENT_CLEAN_EVIDENCE",
            "ENFORCEMENT_FAILED");

    private MlaBreadcrumb() {
    }

    public static void appendSseEvent(String eventType, Object sanitizedPayload) {
        Map<String, Object> row = baseRow("LoggingSseEventPublisher", "sse_telemetry_event", "sse_emit");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventType", SafeRedactor.traceLabelOrFallback(eventType, "event"));
        String payloadText = sanitizedPayload == null ? "" : String.valueOf(sanitizedPayload);
        data.put("payloadPresent", sanitizedPayload != null);
        putIfPresent(data, "payloadHash", SafeRedactor.hashValue(payloadText));
        data.put("payloadLength", payloadText.length());
        appendTraceFields(data, eventType, 0.0d, "sse_emit");
        row.put("data", data);

        TraceStore.append("ml.breadcrumbs.v1", row);
        TraceStore.put("mla.breadcrumb.step." + traceKeySegment(eventType), row);
        refreshBreadcrumbCount();
    }

    public static void appendLlmReward(String armId, boolean success, long latencyMs, String failureClass) {
        String safeArm = SafeRedactor.traceLabelOrFallback(armId, "unknown");
        Map<String, Object> row = baseRow("LlmRouterBandit", "llm_router_reward", "llm_router_reward");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("llmArm", safeArm);
        data.put("reward", success ? 1.0d : 0.0d);
        data.put("outcome", success ? "success" : "fail");
        data.put("failureClass", SafeRedactor.traceLabelOrFallback(failureClass, "unknown").toLowerCase(Locale.ROOT));
        data.put("latencyMs", Math.max(0L, latencyMs));
        appendTraceFields(data, "llm_router_reward", success ? 1.0d : 0.0d, success ? "success" : "fail");
        row.put("data", data);

        TraceStore.append("ml.breadcrumbs.v1", row);
        TraceStore.put("mla.breadcrumb.llm.reward." + traceKeySegment(safeArm), row);
        refreshBreadcrumbCount();
    }

    /** Record only the bounded, request-scoped interaction-policy decision. */
    public static void appendInteractionPolicyTransition(InteractionEvidencePolicy.Decision decision) {
        InteractionEvidencePolicy.Decision safeDecision = decision == null
                ? InteractionEvidencePolicy.offDecision()
                : decision;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("featureMode", safeDecision.featureMode().name());
        data.put("responseStyle", safeDecision.responseStyle().name());
        data.put("securityStance", safeDecision.securityStance().name());
        data.put("evidenceMode", safeDecision.evidenceMode().name());
        data.put("memoryWriteMode", safeDecision.memoryWriteMode().name());
        data.put("failureMode", safeDecision.failureMode().name());
        data.put("signalCount", safeDecision.signalCount());
        data.put("manipulationKinds", enumNames(safeDecision.manipulationKinds()));
        data.put("proofKinds", enumNames(safeDecision.proofKinds()));
        data.put("detectorRules", enumNames(safeDecision.detectorRules()));
        data.put("sourceSurfaces", enumNames(safeDecision.sourceSurfaces()));
        String containmentStatus = interactionContainmentStatus();
        data.put("containmentStatus", containmentStatus);
        if (!"PENDING".equals(containmentStatus)) {
            data.put("containmentInputCount", TraceStore.getLong("interaction.policy.containment.inputCount"));
            data.put("containmentCleanCount", TraceStore.getLong("interaction.policy.containment.cleanCount"));
            data.put("containmentQuarantinedCount",
                    TraceStore.getLong("interaction.policy.containment.quarantinedCount"));
            data.put("containmentBlockRequired",
                    Boolean.TRUE.equals(TraceStore.get("interaction.policy.containment.blockRequired")));
        }
        data.put("queryRedacted", true);
        TraceStore.put("interaction.policy.featureMode", safeDecision.featureMode().name());
        TraceStore.put("interaction.policy.responseStyle", safeDecision.responseStyle().name());
        TraceStore.put("interaction.policy.securityStance", safeDecision.securityStance().name());
        TraceStore.put("interaction.policy.evidenceMode", safeDecision.evidenceMode().name());
        TraceStore.put("interaction.policy.memoryWriteMode", safeDecision.memoryWriteMode().name());
        TraceStore.put("interaction.policy.failureMode", safeDecision.failureMode().name());
        TraceStore.put("interaction.policy.signalCount", safeDecision.signalCount());
        TraceStore.put("interaction.policy.queryRedacted", true);

        String decisionName = safeDecision.securityStance().name();
        Object previous = TraceStore.get("mla.breadcrumb.step.interaction_policy");
        if (sameInteractionPolicyObservation(previous, decisionName, data)) {
            return;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("seq", TraceStore.nextSequence("ml.breadcrumbs.v1"));
        row.put("component", "InteractionEvidencePolicy");
        row.put("rules", "evidence_neutral_interaction_v1");
        row.put("decision", decisionName);
        row.put("data", data);

        TraceStore.append("ml.breadcrumbs.v1", row);
        TraceStore.put("mla.breadcrumb.step.interaction_policy", row);
        refreshBreadcrumbCount();
    }

    /** Record only allowlisted posture, routing, and count observations for the request frame. */
    public static void appendConversationFrameTransition(ConversationFrameV1 frame) {
        if (frame == null || !frame.shouldTrace()) {
            return;
        }

        boolean wouldSelectVisionRoute = frame.multimodalInputPresent();
        boolean visionRouteSelected = Boolean.TRUE.equals(
                TraceStore.get("conversation.frame.visionRouteSelected"));
        long auxiliaryModelCallCount = Math.max(
                nonNegativeLong(TraceStore.get("conversation.frame.auxiliaryModelCallCount")),
                Math.max(
                        nonNegativeLong(TraceStore.get("ensemble.refiner.modelCallCount")),
                        nonNegativeLong(TraceStore.get("ensemble.sampling.modelCallCount"))));
        long primaryModelCallCount = nonNegativeLong(
                TraceStore.get("conversation.frame.primaryModelCallCount"));
        String wireAttemptCoverage = "observed".equals(
                TraceStore.getString("conversation.frame.wireAttemptCoverage"))
                ? "observed"
                : "not_observed";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", "v1");
        data.put("mode", enumName(frame.mode()));
        data.put("stance", enumName(frame.stance()));
        data.put("reasonCode", enumName(frame.reasonCode()));
        data.put("lightweightRole", enumName(frame.lightweightRole()));
        data.put("multimodalInputPresent", frame.multimodalInputPresent());
        data.put("refinerSuppressed", !frame.allowsOptionalRefinement());
        data.put("optionalExpansionSuppressed", !frame.allowsOptionalExpansion());
        data.put("memoryWriteSuppressed", frame.suppressesMemoryWrites());
        data.put("primaryAuthority", "primary_model");
        data.put("wouldSelectVisionRoute", wouldSelectVisionRoute);
        data.put("visionRouteSelected", visionRouteSelected);
        data.put("auxiliaryModelCallCount", auxiliaryModelCallCount);
        data.put("primaryModelCallCount", primaryModelCallCount);
        data.put("wireAttemptCoverage", wireAttemptCoverage);

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            TraceStore.put("conversation.frame." + entry.getKey(), entry.getValue());
        }

        String decisionName = enumName(frame.stance());
        Object previous = TraceStore.get("mla.breadcrumb.step.conversation_frame");
        if (sameConversationFrameObservation(previous, decisionName, data)) {
            return;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("seq", TraceStore.nextSequence("ml.breadcrumbs.v1"));
        row.put("component", "ConversationFrameV1");
        row.put("rules", "conversation_frame_v1");
        row.put("decision", decisionName);
        row.put("data", data);

        TraceStore.append("ml.breadcrumbs.v1", row);
        TraceStore.put("mla.breadcrumb.step.conversation_frame", row);
        refreshBreadcrumbCount();
        emitConversationFrameTelemetry(
                data,
                nonNegativeLong(TraceStore.get("public.request.budget.imageDecodedBytes")),
                fixedImageMediaType(TraceStore.get("public.request.budget.imageMediaType")),
                hashId(firstNonBlank(
                        TraceStore.getString("requestId"),
                        TraceStore.getString("rid"),
                        TraceStore.getString("x-request-id"),
                        TraceStore.getString("trace.id"))));
    }

    private static void emitConversationFrameTelemetry(
            Map<String, Object> data,
            long decodedImageBytes,
            String imageMediaType,
            String requestCorrelationHash) {
        log.info(
                "[CONVERSATION_FRAME_TELEMETRY] mode={} stance={} reasonCode={} lightweightRole={} "
                        + "imagePresent={} decodedImageBytes={} imageMediaType={} visionRouteSelected={} "
                        + "wouldSelectVisionRoute={} refinerSuppressed={} expansionSuppressed={} "
                        + "memoryWriteSuppressed={} primaryAuthority={} auxiliaryModelCallCount={} "
                        + "primaryModelCallCount={} wireAttemptCoverage={} requestCorrelationHash={}",
                data.get("mode"),
                data.get("stance"),
                data.get("reasonCode"),
                data.get("lightweightRole"),
                data.get("multimodalInputPresent"),
                decodedImageBytes,
                imageMediaType,
                data.get("visionRouteSelected"),
                data.get("wouldSelectVisionRoute"),
                data.get("refinerSuppressed"),
                data.get("optionalExpansionSuppressed"),
                data.get("memoryWriteSuppressed"),
                data.get("primaryAuthority"),
                data.get("auxiliaryModelCallCount"),
                data.get("primaryModelCallCount"),
                data.get("wireAttemptCoverage"),
                requestCorrelationHash == null ? "none" : requestCorrelationHash);
    }

    private static String fixedImageMediaType(Object value) {
        if (!(value instanceof String mediaType)) {
            return "NONE";
        }
        return switch (mediaType) {
            case "PNG", "JPEG", "WEBP" -> mediaType;
            default -> "NONE";
        };
    }

    private static String interactionContainmentStatus() {
        String status = SafeRedactor.traceLabelOrFallback(
                TraceStore.get("interaction.policy.containment.status"),
                "PENDING").toUpperCase(Locale.ROOT);
        return INTERACTION_CONTAINMENT_STATUSES.contains(status) ? status : "PENDING";
    }

    private static boolean sameInteractionPolicyObservation(
            Object previous,
            String decision,
            Map<String, Object> data) {
        if (!(previous instanceof Map<?, ?> previousRow)) {
            return false;
        }
        return decision.equals(String.valueOf(previousRow.get("decision")))
                && data.equals(previousRow.get("data"));
    }

    private static boolean sameConversationFrameObservation(
            Object previous,
            String decision,
            Map<String, Object> data) {
        if (!(previous instanceof Map<?, ?> previousRow)) {
            return false;
        }
        return decision.equals(String.valueOf(previousRow.get("decision")))
                && data.equals(previousRow.get("data"));
    }

    private static String enumName(Enum<?> value) {
        return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT);
    }

    private static long nonNegativeLong(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static <E extends Enum<E>> List<String> enumNames(Set<E> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(Enum::name).toList();
    }

    private static void refreshBreadcrumbCount() {
        Object aggregate = TraceStore.get("ml.breadcrumbs.v1");
        int count = aggregate instanceof java.util.Collection<?> rows ? rows.size() : (aggregate == null ? 0 : 1);
        TraceStore.put("cihRag.mlaBreadcrumbCount", count);
    }

    private static Map<String, Object> baseRow(String component, String rules, String decision) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("seq", TraceStore.nextSequence("ml.breadcrumbs.v1"));
        row.put("ts", Instant.now().toString());
        row.put("component", component);
        row.put("rules", rules);
        row.put("decision", decision);
        putIfPresent(row, "requestId", hashId(firstNonBlank(
                TraceStore.getString("requestId"),
                TraceStore.getString("rid"),
                TraceStore.getString("x-request-id"),
                TraceStore.getString("trace.id"))));
        putIfPresent(row, "sessionId", hashId(firstNonBlank(
                TraceStore.getString("sessionId"),
                TraceStore.getString("sid"),
                TraceStore.getString("conversation.sid"))));
        return row;
    }

    private static void appendTraceFields(Map<String, Object> data,
                                          String stage,
                                          double relevance,
                                          String routeDecision) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeRouteDecision = SafeRedactor.traceLabelOrFallback(routeDecision, "unknown");
        double safeRelevance = Double.isFinite(relevance) ? relevance : 0.0d;
        data.put("queryRedacted", true);
        data.put("stage", safeStage);
        data.put("relevance", safeRelevance);
        data.put("routeDecision", safeRouteDecision);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
        TraceStore.put("cihRag.breadcrumb.stage", safeStage);
        TraceStore.put("cihRag.breadcrumb.relevance", safeRelevance);
        TraceStore.put("cihRag.breadcrumb.routeDecision", safeRouteDecision);
        putIfPresent(data, "planId", SafeRedactor.traceLabel(TraceStore.get("plan.id")));
        putIfPresent(data, "jb", numeric(TraceStore.get("cfvm.jb.score")));
        putIfPresent(data, "cb", numeric(TraceStore.get("cfvm.cb.score")));
        putIfPresent(data, "plateId", SafeRedactor.traceLabel(TraceStore.get("artplate.selected")));
        putIfAbsent(data, "llmArm", SafeRedactor.traceLabel(TraceStore.get("llm.router.arm")));
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static void putIfAbsent(Map<String, Object> out, String key, Object value) {
        if (value != null && !out.containsKey(key)) {
            out.put(key, value);
        }
    }

    private static String traceKeySegment(String value) {
        String safe = SafeRedactor.traceLabelOrFallback(value, "unknown");
        if (safe.startsWith("hash:")) {
            safe = "hash_" + safe.substring("hash:".length());
        }
        return safe.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static String hashId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.startsWith("hash:") ? value : SafeRedactor.hashValue(value);
    }


    private static Double numeric(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        if (value == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(String.valueOf(value));
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException ignored) {
            TraceStore.put("mla.breadcrumb.suppressed.stage", "toDouble");
            TraceStore.put("mla.breadcrumb.suppressed.errorType", "invalid_number");
            TraceStore.put("mla.breadcrumb.suppressed.toDouble", true);
            TraceStore.put("mla.breadcrumb.suppressed.toDouble.errorType", "invalid_number");
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
