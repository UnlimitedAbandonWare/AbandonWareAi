
package com.example.lms.cfvm;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.util.HashUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;




@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 65)
@Component
public class DecisionTraceAspect {

    private static final Logger log = LoggerFactory.getLogger(DecisionTraceAspect.class);
    private static final String RECONSTRUCTION_PREFIX = "decision.reconstruction.";

    @Autowired(required = false)
    private DebugEventStore debugEventStore;

    @Autowired(required = false)
    private ModelRuntimeHealthTracker modelRuntimeHealthTracker;

    @Around("execution(* *..DynamicRetrievalHandlerChain.*(..))")
    public Object trace(ProceedingJoinPoint pjp) throws Throwable {
        long ts = System.currentTimeMillis();
        try {
            Object res = pjp.proceed();
            log("OK", pjp, System.currentTimeMillis()-ts, null);
            return res;
        } catch (Throwable t) {
            log("ERR", pjp, System.currentTimeMillis()-ts, t.getMessage());
            throw t;
        }
    }

    @Around("execution(com.example.lms.service.ChatResult com.example.lms.service.ChatWorkflow.continueChat(..))")
    public Object traceFinalDecision(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        if (result instanceof ChatResult chatResult) {
            try {
                captureFinalDecision(chatResult);
                auditTraceContext();
            } catch (RuntimeException auditFailure) {
                TraceStore.inc(RECONSTRUCTION_PREFIX + "auditFailureCount");
                TraceStore.put(RECONSTRUCTION_PREFIX + "auditFailureReason", "decision_reconstruction_audit_failed");
                log.warn("[DecisionEvidenceReconstruction] audit failed errorHash={} errorLength={}",
                        SafeRedactor.hashValue(auditFailure.getMessage()),
                        auditFailure.getMessage() == null ? 0 : auditFailure.getMessage().length());
            }
        }
        return result;
    }

    @Around("execution(* com.example.lms.service.ChatHistoryService+.appendMessageReturningId(..))")
    public Object tracePersistedFinalResponse(ProceedingJoinPoint pjp) throws Throwable {
        Object persisted = pjp.proceed();
        Object[] args = pjp.getArgs();
        if (args != null
                && args.length >= 3
                && "assistant".equalsIgnoreCase(text(args[1]))) {
            try {
                capturePersistedFinalResponse(args[2] == null ? "" : String.valueOf(args[2]));
                auditTraceContext();
            } catch (RuntimeException auditFailure) {
                TraceStore.inc(RECONSTRUCTION_PREFIX + "finalResponseAuditFailureCount");
                TraceStore.put(RECONSTRUCTION_PREFIX + "finalResponseAuditFailureReason",
                        "final_response_reconstruction_audit_failed");
                log.warn("[DecisionEvidenceReconstruction] final response audit failed errorHash={} errorLength={}",
                        SafeRedactor.hashValue(auditFailure.getMessage()),
                        auditFailure.getMessage() == null ? 0 : auditFailure.getMessage().length());
            }
        }
        return persisted;
    }

    void auditTraceContext() {
        DecisionEvidenceReconstructionValidator.Summary summary =
                DecisionEvidenceReconstructionValidator.audit(
                        TraceStore.get(RECONSTRUCTION_PREFIX + "decisions"),
                        TraceStore.get(RECONSTRUCTION_PREFIX + "evidence"),
                        TraceStore.get(RECONSTRUCTION_PREFIX + "relations"),
                        TraceStore.get(RECONSTRUCTION_PREFIX + "lineage"),
                        TraceStore.get(RECONSTRUCTION_PREFIX + "finalResponses"));
        publishSummary(summary);
    }

    private void captureFinalDecision(ChatResult result) {
        List<Map<String, Object>> capturedLineage = captureLineage();
        String requestIdHash = safeHash(TraceStore.get("requestId"));
        Set<String> optionsHashes = new LinkedHashSet<>();
        for (Map<String, Object> row : capturedLineage) {
            String optionsHash = text(row.get("optionsHash"));
            if (!optionsHash.isBlank()) {
                optionsHashes.add(optionsHash);
            }
        }

        String contentHash = HashUtil.sha256(result.content() == null ? "" : result.content());
        String decisionId = SafeRedactor.hashValue(
                contentHash + "|"
                        + text(TraceStore.get("finalAnswer.releaseStatus")) + "|"
                        + text(TraceStore.get("finalAnswer.releaseReason")) + "|"
                        + requestIdHash);
        List<Map<String, Object>> lineage = linkLineage(capturedLineage, decisionId);

        List<Map<String, Object>> evidence = new ArrayList<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        List<String> relationIds = new ArrayList<>();
        for (RagEvidenceMetadata item : result.evidenceMetadata()) {
            if (item == null) {
                continue;
            }
            String evidenceId = SafeRedactor.hashValue(String.join("|",
                    text(item.marker()),
                    text(item.kind()),
                    text(item.source()),
                    text(item.filePath()),
                    text(item.lineStart()),
                    text(item.lineEnd())));
            String relationId = SafeRedactor.hashValue(decisionId + "|" + evidenceId);
            evidence.add(Map.of("evidenceId", evidenceId));
            relations.add(Map.of(
                    "relationId", relationId,
                    "decisionId", decisionId,
                    "evidenceId", evidenceId));
            relationIds.add(relationId);
        }

        boolean evidenceRequired = result.ragUsed()
                || Boolean.TRUE.equals(TraceStore.get("finalAnswer.evidenceReleaseRequired"));
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("decisionId", decisionId);
        decision.put("relationIds", List.copyOf(relationIds));
        decision.put("requestIdHash", requestIdHash);
        decision.put("optionsHashes", List.copyOf(optionsHashes));
        decision.put("workflowResponseHash", contentHash);
        decision.put("evidenceRequired", evidenceRequired);
        decision.put("lineageRequired", isLineageRequired(capturedLineage));

        TraceStore.put(RECONSTRUCTION_PREFIX + "decisions", List.of(Map.copyOf(decision)));
        TraceStore.put(RECONSTRUCTION_PREFIX + "evidence", List.copyOf(evidence));
        TraceStore.put(RECONSTRUCTION_PREFIX + "relations", List.copyOf(relations));
        TraceStore.put(RECONSTRUCTION_PREFIX + "lineage", List.copyOf(lineage));
        TraceStore.put(RECONSTRUCTION_PREFIX + "finalResponses", List.of());
        TraceStore.put(RECONSTRUCTION_PREFIX + "evidenceRowCount", evidence.size());
        TraceStore.put(RECONSTRUCTION_PREFIX + "relationRowCount", relations.size());
        TraceStore.put(RECONSTRUCTION_PREFIX + "lineageRowCount", lineage.size());
        TraceStore.put(RECONSTRUCTION_PREFIX + "finalResponseRowCount", 0L);
    }

    private void capturePersistedFinalResponse(String content) {
        Object rawDecisions = TraceStore.get(RECONSTRUCTION_PREFIX + "decisions");
        if (!(rawDecisions instanceof List<?> decisions) || decisions.isEmpty()
                || !(decisions.get(0) instanceof Map<?, ?> decision)) {
            return;
        }
        String decisionId = text(decision.get("decisionId"));
        if (decisionId.isBlank()) {
            return;
        }

        Set<String> evidenceIds = new LinkedHashSet<>();
        Object rawRelations = TraceStore.get(RECONSTRUCTION_PREFIX + "relations");
        if (rawRelations instanceof List<?> relations) {
            for (Object value : relations) {
                if (!(value instanceof Map<?, ?> relation)
                        || !decisionId.equals(text(relation.get("decisionId")))) {
                    continue;
                }
                String evidenceId = text(relation.get("evidenceId"));
                if (!evidenceId.isBlank()) {
                    evidenceIds.add(evidenceId);
                }
            }
        }

        String contentHash = HashUtil.sha256(content == null ? "" : content);
        Map<String, Object> finalResponse = new LinkedHashMap<>();
        finalResponse.put("responseId", SafeRedactor.hashValue(decisionId + "|" + contentHash));
        finalResponse.put("decisionId", decisionId);
        finalResponse.put("contentHash", contentHash);
        finalResponse.put("evidenceIds", List.copyOf(evidenceIds));
        TraceStore.put(RECONSTRUCTION_PREFIX + "finalResponses", List.of(Map.copyOf(finalResponse)));
        TraceStore.put(RECONSTRUCTION_PREFIX + "finalResponseRowCount", 1L);
    }

    private List<Map<String, Object>> captureLineage() {
        if (modelRuntimeHealthTracker == null) {
            return List.of();
        }
        Object timelineValue = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
        if (timelineValue == null || String.valueOf(timelineValue).isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : modelRuntimeHealthTracker.redactedRequestAttemptLedger(
                String.valueOf(timelineValue))) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("requestIdHash", safeHash(row.get("requestHash")));
            normalized.put("optionsHash", safeHash(row.get("optionsHash")));
            normalized.put("attemptOrdinal", number(row.get("sequence")));
            normalized.put("providerAttemptObserved", Boolean.TRUE.equals(row.get("providerAttemptObserved")));
            normalized.put("responseObserved", Boolean.TRUE.equals(row.get("responseObserved")));
            out.add(Map.copyOf(normalized));
        }
        return List.copyOf(out);
    }

    private static List<Map<String, Object>> linkLineage(
            List<Map<String, Object>> lineage,
            String decisionId) {
        List<Map<String, Object>> linked = new ArrayList<>(lineage.size());
        for (Map<String, Object> row : lineage) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("decisionId", decisionId);
            linked.add(Map.copyOf(copy));
        }
        return List.copyOf(linked);
    }

    private boolean isLineageRequired(List<Map<String, Object>> capturedLineage) {
        if (!capturedLineage.isEmpty()) {
            return true;
        }
        if (modelRuntimeHealthTracker == null) {
            return false;
        }
        Object timelineValue = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
        if (timelineValue == null || String.valueOf(timelineValue).isBlank()) {
            return false;
        }
        for (Map<String, Object> row : modelRuntimeHealthTracker.redactedRequestTimeline(
                String.valueOf(timelineValue))) {
            if ("pending".equals(text(row.get("phase")))) {
                return true;
            }
        }
        return false;
    }

    private void publishSummary(DecisionEvidenceReconstructionValidator.Summary summary) {
        Map<String, Object> metrics = summary.toTraceMap();
        metrics.forEach((key, value) -> TraceStore.put(RECONSTRUCTION_PREFIX + key, value));

        boolean complete = summary.checkedDecisionCount() > 0
                && summary.checkedDecisionCount() == summary.reconstructableCount();
        if (debugEventStore != null) {
            debugEventStore.emit(
                    DebugProbeType.ORCHESTRATION,
                    complete ? DebugEventLevel.INFO : DebugEventLevel.WARN,
                    "decision-evidence-reconstruction",
                    "decision_evidence_reconstruction",
                    "DecisionTraceAspect.auditTraceContext",
                    metrics,
                    null);
        }
        if (complete) {
            log.info("[DecisionEvidenceReconstruction] checked={} attempts={} responses={} linked={} "
                            + "lineageMismatch={} passRate={} reconstructable={} missingEvidence={} "
                            + "orphanRelation={} reasonCodes={} providerAttemptCoverage={}",
                    summary.checkedDecisionCount(),
                    summary.providerAttemptCount(),
                    summary.providerResponseCount(),
                    summary.linkedDecisionCount(),
                    summary.lineageMismatchCount(),
                    summary.runtimeLineagePassRate(),
                    summary.reconstructableCount(),
                    summary.missingEvidenceCount(),
                    summary.orphanRelationCount(),
                    summary.reasonCodes(),
                    summary.providerAttemptCount() == 0 ? "not_observed" : "observed_partial");
        } else {
            log.warn("[DecisionEvidenceReconstruction] checked={} attempts={} responses={} linked={} "
                            + "lineageMismatch={} passRate={} reconstructable={} missingEvidence={} "
                            + "orphanRelation={} reasonCodes={} providerAttemptCoverage={}",
                    summary.checkedDecisionCount(),
                    summary.providerAttemptCount(),
                    summary.providerResponseCount(),
                    summary.linkedDecisionCount(),
                    summary.lineageMismatchCount(),
                    summary.runtimeLineagePassRate(),
                    summary.reconstructableCount(),
                    summary.missingEvidenceCount(),
                    summary.orphanRelationCount(),
                    summary.reasonCodes(),
                    summary.providerAttemptCount() == 0 ? "not_observed" : "observed_partial");
        }
    }

    private static String safeHash(Object value) {
        String text = text(value);
        if (text.isBlank() || "hash:unknown".equals(text)) {
            return "";
        }
        return text.startsWith("hash:") ? text : SafeRedactor.hashValue(text);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static long number(Object value) {
        return value instanceof Number numeric ? numeric.longValue() : -1L;
    }

    private void log(String status, ProceedingJoinPoint pjp, long ms, String err) {
        String signature = pjp == null || pjp.getSignature() == null
                ? ""
                : pjp.getSignature().toShortString();
        String safeError = SafeRedactor.safeMessage(err, 180);
        String line = new StringBuilder(160)
                .append("{\"ts\":").append(Instant.now().toEpochMilli())
                .append(",\"status\":").append(json(SafeRedactor.traceLabelOrFallback(status, "unknown")))
                .append(",\"sigHash\":").append(json(SafeRedactor.hashValue(signature)))
                .append(",\"sigLength\":").append(signature.length())
                .append(",\"ms\":").append(ms)
                .append(",\"errPresent\":").append(safeError != null && !safeError.isBlank())
                .append(",\"errHash\":").append(json(SafeRedactor.hashValue(safeError)))
                .append(",\"errLength\":").append(safeError == null ? 0 : safeError.length())
                .append("}\n")
                .toString();
        try {
            Path out = Path.of("cfvm-raw", "records", "trace.ndjson");
            Files.createDirectories(out.getParent());
            Files.writeString(out, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            TraceStore.inc("cfvm.decisionTrace.writeFailure.count");
            TraceStore.put("cfvm.decisionTrace.writeFailure.errorType", "decision_trace_write_failed");
        }
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }
}
