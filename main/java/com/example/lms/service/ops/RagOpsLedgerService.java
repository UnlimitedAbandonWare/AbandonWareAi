package com.example.lms.service.ops;

import com.example.lms.entity.RagOpsLedgerEntry;
import com.example.lms.entity.VectorQuarantineDlq;
import com.example.lms.entity.VectorShadowMergeDlq;
import com.example.lms.cfvm.RawSlotExtractor;
import com.example.lms.repository.RagOpsLedgerRepository;
import com.example.lms.repository.VectorQuarantineDlqRepository;
import com.example.lms.repository.VectorShadowMergeDlqRepository;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker.CycleDiagnostics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class RagOpsLedgerService {

    private static final Logger log = LoggerFactory.getLogger(RagOpsLedgerService.class);
    private static final int RECENT_SUMMARY_LIMIT = 10;
    private static final int SUMMARY_SCAN_LIMIT = 1_000;

    private final RagOpsLedgerRepository repository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<VectorQuarantineDlqRepository> quarantineDlqRepository;
    private final ObjectProvider<VectorShadowMergeDlqRepository> shadowMergeDlqRepository;
    private final ObjectProvider<RagFailureBlackboxService> blackboxProvider;

    @Value("${rag.ops-ledger.enabled:false}")
    private boolean enabled;

    @Value("${rag.ops-ledger.capture-rag:true}")
    private boolean captureRag;

    @Value("${rag.ops-ledger.capture-autolearn:true}")
    private boolean captureAutolearn;

    @Value("${rag.ops-ledger.max-json-chars:4000}")
    private int maxJsonChars;

    public RagOpsLedgerService(RagOpsLedgerRepository repository,
                               ObjectMapper objectMapper,
                               ObjectProvider<VectorQuarantineDlqRepository> quarantineDlqRepository,
                               ObjectProvider<VectorShadowMergeDlqRepository> shadowMergeDlqRepository) {
        this(repository, objectMapper, quarantineDlqRepository, shadowMergeDlqRepository, null);
    }

    @Autowired
    public RagOpsLedgerService(RagOpsLedgerRepository repository,
                               ObjectMapper objectMapper,
                               ObjectProvider<VectorQuarantineDlqRepository> quarantineDlqRepository,
                               ObjectProvider<VectorShadowMergeDlqRepository> shadowMergeDlqRepository,
                               ObjectProvider<RagFailureBlackboxService> blackboxProvider) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.quarantineDlqRepository = quarantineDlqRepository;
        this.shadowMergeDlqRepository = shadowMergeDlqRepository;
        this.blackboxProvider = blackboxProvider;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void recordRagRun(QueryRequest request, QueryResponse response, long latencyMs) {
        if (!enabled || !captureRag) {
            return;
        }
        try {
            Map<String, Object> trace = TraceStore.getAll();
            refreshBlackboxMatrixIfMissing(trace, "RagOpsLedgerService.recordRagRun");
            trace = TraceStore.getAll();
            RagOpsLedgerEntry entry = new RagOpsLedgerEntry();
            entry.setRunId(firstNonBlank(hash(asString(trace.get("trace.id"))), hash(response == null ? null : response.requestId), UUID.randomUUID().toString()));
            entry.setEntryType("RAG_RUN");
            entry.setSessionHash(hash(firstNonBlank(asString(trace.get("sid")), request == null ? null : request.threadId)));
            entry.setRequestHash(hash(firstNonBlank(safe(response == null ? null : response.requestId), asString(trace.get("request.id")), asString(trace.get("x-request-id")))));
            entry.setQueryHash(hash(request == null ? null : request.query));
            entry.setQueryLength(request == null || request.query == null ? null : request.query.length());
            entry.setPlanId(clip(firstNonBlank(request == null ? null : request.planId, response == null ? null : response.planApplied, asString(trace.get("plan.id.preSearch")), asString(trace.get("plan.id"))), 128));
            entry.setStrategyName(clip(strategyName(request, response, trace), 128));
            entry.setResourceTier(clip(firstNonBlank(asString(trace.get("cfvm.kalloc.resourceTier")), asString(trace.get("rag.resourceTier")), "standard"), 32));
            entry.setSourceCountsJson(toJson(sourceCounts(request, response, trace)));
            entry.setQualityJson(toJson(selectByPrefix(response == null ? Map.of() : response.debug, "rag.eval.", "langgraph.", "final", "gate.", "citation.", "retrieval.")));
            entry.setVectorJson(toJson(withVectorDlq(selectByPrefix(trace, "vector.", "vectorstore.", "webSearch.", "web.naver.", "web.brave.", "web.serpapi.", "web.tavily.", "embed."))));
            entry.setKgJson(toJson(selectByPrefix(trace, "kg.", "retrieval.kg", "rag.kg")));
            entry.setMatrixJson(toJson(selectByPrefix(trace, "blackbox.risk.", "overdrive.", "extremez.", "ml.risk.",
                    "learning.", "selfask.3way.", "cfvm.", "uaw.gpu-hardware.",
                    "rerank.ce.gpuHardwareAdmission.")));
            Decision decision = ragDecision(response, trace);
            entry.setDecision(decision.decision());
            entry.setFailureClass(decision.failureClass());
            entry.setHotspot(clip(firstNonBlank(asString(trace.get("blackbox.risk.hotspot")),
                    asString(trace.get("blackbox.risk.dominantFailure")),
                    asString(trace.get("learning.error.hotspot")),
                    resolveRagHotspot(response == null ? null : response.debug),
                    asString(trace.get("rag.eval.bottleneck")),
                    asString(trace.get("extremez.risk.primaryCause")),
                    "none"), 64));
            entry.setLatencyMs(Math.max(0L, latencyMs));
            repository.save(entry);
        } catch (Exception e) {
            log.debug("[AWX2AF2][ops-ledger] RAG ledger write skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    private void refreshBlackboxMatrixIfMissing(Map<String, Object> trace, String where) {
        if (trace != null && trace.get("blackbox.risk.matrix") instanceof Map<?, ?> matrix && !matrix.isEmpty()) {
            return;
        }
        try {
            RagFailureBlackboxService service = blackboxProvider == null ? null : blackboxProvider.getIfAvailable();
            if (service != null) {
                service.refresh(where);
            }
        } catch (Throwable ignored) {
            log.debug("[AWX2AF2][ops-ledger] fail-soft stage={}", "refreshBlackboxMatrixIfMissing");
        }
    }

    public void recordAutolearnCycle(String sessionId,
                                     String datasetPath,
                                     int attempted,
                                     int accepted,
                                     boolean aborted,
                                     CycleDiagnostics diagnostics) {
        if (!enabled || !captureAutolearn) {
            return;
        }
        try {
            Map<String, Object> trace = TraceStore.getAll();
            RagOpsLedgerEntry entry = new RagOpsLedgerEntry();
            entry.setRunId(firstNonBlank(hash(asString(trace.get("trace.id"))), UUID.randomUUID().toString()));
            entry.setEntryType("AUTOLEARN_CYCLE");
            entry.setSessionHash(hash(firstNonBlank(asString(trace.get("sid")), sessionId)));
            entry.setRequestHash(hash(asString(trace.get("request.id"))));
            entry.setPlanId("autolearn");
            entry.setStrategyName("uaw-autolearn");
            entry.setResourceTier(clip(firstNonBlank(asString(trace.get("cfvm.kalloc.resourceTier")), "background"), 32));
            entry.setSourceCountsJson(toJson(Map.of("attempted", Math.max(0, attempted), "accepted", Math.max(0, accepted), "aborted", aborted)));
            entry.setQualityJson(toJson(autolearnQuality(attempted, accepted, aborted, diagnostics, trace)));
            entry.setVectorJson(toJson(withVectorDlq(Map.of("vectorDecision", firstNonBlank(asString(trace.get("learning.feedback.vectorDecision")), "unknown")))));
            entry.setKgJson(toJson(Map.of()));
            entry.setMatrixJson(toJson(selectByPrefix(trace, "blackbox.risk.", "learning.", "uaw.autolearn.",
                    "uaw.idle.", "uaw.gpu-gateway.", "uaw.gpu-hardware.", "cfvm.")));
            entry.setDecision(clip(diagnostics == null ? (aborted ? "ABORTED" : "UNKNOWN") : diagnostics.trainDecision(), 32));
            entry.setFailureClass(clip(firstNonBlank(nonNone(asString(trace.get("blackbox.risk.dominantFailure"))),
                    diagnostics == null ? "unknown" : (diagnostics.trainAllowed() ? "none" : diagnostics.topProblem())), 64));
            entry.setHotspot(clip(firstNonBlank(asString(trace.get("blackbox.risk.hotspot")), asString(trace.get("learning.error.hotspot")), hotspotFromTrace(trace), "none"), 64));
            entry.setLatencyMs(null);
            repository.save(entry);
        } catch (Exception e) {
            log.debug("[AWX2AF2][ops-ledger] AutoLearn ledger write skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    public void recordAutolearnDiagnostic(String section, Map<String, Object> diagnostics) {
        if (!enabled || !captureAutolearn) {
            return;
        }
        try {
            Map<String, Object> safeDiagnostics = diagnostics == null ? Map.of() : diagnostics;
            Map<String, Object> trace = TraceStore.getAll();
            refreshBlackboxMatrixIfMissing(trace, "RagOpsLedgerService.recordAutolearnDiagnostic");
            trace = TraceStore.getAll();
            String safeSection = safeKey(firstNonBlank(section, "diagnostic"));

            RagOpsLedgerEntry entry = new RagOpsLedgerEntry();
            entry.setRunId(firstNonBlank(hash(asString(trace.get("trace.id"))), UUID.randomUUID().toString()));
            entry.setEntryType("AUTOLEARN_DIAGNOSTIC");
            entry.setSessionHash(hash(firstNonBlank(asString(trace.get("sid")), asString(safeDiagnostics.get("sessionHash")))));
            entry.setRequestHash(hash(asString(trace.get("request.id"))));
            entry.setPlanId("autolearn");
            entry.setStrategyName("uaw-autolearn-diagnostic");
            entry.setResourceTier(clip(firstNonBlank(asString(trace.get("cfvm.kalloc.resourceTier")), "background"), 32));
            entry.setSourceCountsJson(toJson(Map.of("reason", safeSection)));
            entry.setQualityJson(toJson(autolearnDiagnosticQuality(safeSection, safeDiagnostics, trace)));
            entry.setVectorJson(toJson(withVectorDlq(Map.of("vectorDecision", firstNonBlank(
                    asString(trace.get("blackbox.risk.vectorDecision")),
                    asString(trace.get("learning.feedback.vectorDecision")),
                    "unknown")))));
            entry.setKgJson(toJson(Map.of()));
            entry.setMatrixJson(toJson(selectByPrefix(trace,
                    "blackbox.risk.", "learning.", "uaw.autolearn.", "uaw.idle.", "uaw.gpu-gateway.",
                    "uaw.gpu-hardware.", "cfvm.")));
            entry.setDecision(clip(firstNonBlank(asString(safeDiagnostics.get("trainDecision")), "DIAGNOSTIC"), 32));
            entry.setFailureClass(clip(firstNonBlank(nonNone(asString(trace.get("blackbox.risk.dominantFailure"))),
                    nonNone(asString(safeDiagnostics.get("reason"))),
                    nonNone(asString(safeDiagnostics.get("diagnosis"))),
                    safeSection), 64));
            entry.setHotspot(clip(firstNonBlank(asString(trace.get("blackbox.risk.hotspot")),
                    asString(trace.get("learning.error.hotspot")),
                    hotspotFromTrace(trace),
                    "provider"), 64));
            entry.setLatencyMs(null);
            repository.save(entry);
        } catch (Exception e) {
            log.debug("[AWX2AF2][ops-ledger] AutoLearn diagnostic ledger write skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    public void recordPatchRationale(Map<String, Object> rationale) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> payload = rationale == null ? Map.of() : rationale;
            Map<String, Object> trace = TraceStore.getAll();
            Map<String, Object> affectedSourceSet = section(payload, "affectedSourceSet");
            Map<String, Object> triggerTrace = section(payload, "triggerTrace");
            Map<String, Object> callChainHint = section(payload, "callChainHint");
            Map<String, Object> memoryDecision = section(payload, "memoryReinforcementDecision");

            String failureClass = clip(SafeRedactor.traceLabelOrFallback(firstNonBlank(
                    asString(triggerTrace.get("failureClass")),
                    asString(payload.get("failureClass")),
                    asString(trace.get("failureClass")),
                    asString(trace.get("yaml.failureClass")),
                    nonNone(asString(trace.get("blackbox.risk.dominantFailure"))),
                    "none"), "none"), 64);
            String hotspot = clip(firstNonBlank(
                    normalizeHotspot(asString(triggerTrace.get("hotspot"))),
                    normalizeHotspot(asString(payload.get("hotspot"))),
                    normalizeHotspot(asString(trace.get("blackbox.risk.hotspot"))),
                    normalizeHotspot(asString(trace.get("learning.error.hotspot"))),
                    inferSourceSetHotspot(affectedSourceSet),
                    "patch"), 64);
            String decision = patchMemoryDecision(memoryDecision);

            RagOpsLedgerEntry entry = new RagOpsLedgerEntry();
            entry.setRunId(firstNonBlank(hash(asString(trace.get("trace.id"))),
                    hash(asString(payload.get("patchId"))),
                    UUID.randomUUID().toString()));
            entry.setEntryType("PATCH_RATIONALE");
            entry.setSessionHash(hash(firstNonBlank(asString(trace.get("sid")), asString(payload.get("sessionId")))));
            entry.setRequestHash(hash(firstNonBlank(asString(trace.get("request.id")),
                    asString(trace.get("x-request-id")),
                    asString(payload.get("requestId")),
                    asString(payload.get("patchId")))));
            entry.setQueryHash(hash(firstNonBlank(asString(trace.get("queryHash")), asString(triggerTrace.get("queryHash")))));
            entry.setQueryLength(toNullableInt(firstNonBlank(asString(trace.get("queryLength")), asString(triggerTrace.get("queryLength")))));
            entry.setPlanId(clip(SafeRedactor.traceLabelOrFallback(
                    firstNonBlank(asString(payload.get("planId")), asString(trace.get("plan.id")), "why-patch"),
                    "why-patch"), 128));
            entry.setStrategyName("why-patch-rationale-ledger");
            entry.setResourceTier(clip(firstNonBlank(asString(trace.get("cfvm.kalloc.resourceTier")), "ops"), 32));
            entry.setSourceCountsJson(toJson(patchSourceCounts(affectedSourceSet, trace)));
            entry.setQualityJson(toJson(patchQuality(payload)));
            entry.setVectorJson(toJson(Map.of("memoryReinforcementDecision", memoryDecision)));
            entry.setKgJson(toJson(patchCallChain(callChainHint, trace)));
            entry.setMatrixJson(toJson(patchTriggerMatrix(triggerTrace, trace, failureClass, hotspot)));
            entry.setDecision(decision);
            entry.setFailureClass(failureClass);
            entry.setHotspot(hotspot);
            entry.setLatencyMs(null);
            repository.save(entry);
        } catch (Exception e) {
            log.debug("[AWX2AF2][ops-ledger] Patch rationale ledger write skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
    }

    public Map<String, Object> summary(int hours) {
        int clampedHours = Math.max(1, Math.min(168, hours));
        LocalDateTime since = LocalDateTime.now().minusHours(clampedHours);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("windowHours", clampedHours);
        out.put("since", since.toString());
        try {
            List<RagOpsLedgerEntry> rows = repository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                    since,
                    PageRequest.of(0, SUMMARY_SCAN_LIMIT));
            out.put("total", rows.size());
            out.put("byEntryType", countBy(rows, RagOpsLedgerEntry::getEntryType));
            out.put("byDecision", countBy(rows, RagOpsLedgerEntry::getDecision));
            out.put("byHotspot", countBy(rows, RagOpsLedgerEntry::getHotspot));
            out.put("vectorDlq", vectorDlqSummary());
            out.put("recent", rows.stream().limit(RECENT_SUMMARY_LIMIT).map(this::toPublicMap).toList());
        } catch (Exception e) {
            out.put("error", "ledger_summary_unavailable");
            out.put("vectorDlq", vectorDlqSummary());
            log.debug("[AWX2AF2][ops-ledger] summary unavailable. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
        return out;
    }

    public List<Map<String, Object>> recent(String entryType, String decision, int limit) {
        int clampedLimit = Math.max(1, Math.min(200, limit));
        String safeEntryType = blankToNull(entryType);
        String safeDecision = blankToNull(decision);
        try {
            return repository.findRecent(safeEntryType, safeDecision, PageRequest.of(0, clampedLimit))
                    .stream()
                    .map(this::toPublicMap)
                    .toList();
        } catch (Exception e) {
            log.debug("[AWX2AF2][ops-ledger] recent unavailable. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return List.of();
        }
    }

    private Map<String, Object> sourceCounts(QueryRequest request, QueryResponse response, Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Doc> results = response == null || response.results == null ? List.of() : response.results;
        Map<String, Integer> bySource = new LinkedHashMap<>();
        for (Doc doc : results) {
            String source = normalizeSource(doc == null ? null : doc.source);
            bySource.put(source, bySource.getOrDefault(source, 0) + 1);
        }
        out.put("resultCount", results.size());
        out.put("bySource", bySource);
        out.put("useWeb", request != null && request.useWeb);
        out.put("useVector", request != null && request.useVector);
        out.put("useKg", request != null && request.useKg);
        out.put("useBm25", request != null && request.useBm25);
        out.put("topK", request == null ? 0 : request.topK);
        addIfPresent(out, "webReturned", trace, "webSearch.returnedCount");
        addIfPresent(out, "webAfterFilter", trace, "webSearch.afterFilterCount");
        addIfPresent(out, "naverReturned", trace, "web.naver.returnedCount");
        addIfPresent(out, "naverAfterFilter", trace, "web.naver.afterFilterCount");
        addIfPresent(out, "braveReturned", trace, "web.brave.returnedCount");
        addIfPresent(out, "braveAfterFilter", trace, "web.brave.afterFilterCount");
        addIfPresent(out, "serpapiReturned", trace, "web.serpapi.returnedCount");
        addIfPresent(out, "serpapiAfterFilter", trace, "web.serpapi.afterFilterCount");
        addIfPresent(out, "tavilyReturned", trace, "web.tavily.returnedCount");
        addIfPresent(out, "tavilyAfterFilter", trace, "web.tavily.afterFilterCount");
        return out;
    }

    private Map<String, Object> autolearnQuality(int attempted,
                                                 int accepted,
                                                 boolean aborted,
                                                 CycleDiagnostics diagnostics,
                                                 Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attempted", Math.max(0, attempted));
        out.put("accepted", Math.max(0, accepted));
        out.put("aborted", aborted);
        if (diagnostics != null) {
            out.put("trainDecision", diagnostics.trainDecision());
            out.put("topProblem", diagnostics.topProblem());
            out.put("trainAllowed", diagnostics.trainAllowed());
            out.put("acceptanceRate", diagnostics.acceptanceRate());
            out.put("reasonCounts", diagnostics.reasonCounts());
            out.put("flags", diagnostics.flags());
        }
        out.put("vectorDecision", firstNonBlank(asString(trace.get("learning.feedback.vectorDecision")), "unknown"));
        return out;
    }

    private Map<String, Object> autolearnDiagnosticQuality(String section,
                                                           Map<String, Object> diagnostics,
                                                           Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", section);
        out.put("diagnostics", diagnostics == null ? Map.of() : diagnostics);
        out.put("dominantFailure", firstNonBlank(asString(trace.get("blackbox.risk.dominantFailure")), "none"));
        out.put("hotspot", firstNonBlank(asString(trace.get("blackbox.risk.hotspot")), "none"));
        out.put("restoreAction", firstNonBlank(asString(trace.get("blackbox.risk.restoreAction")), "observe_only"));
        out.put("vectorDecision", firstNonBlank(asString(trace.get("blackbox.risk.vectorDecision")),
                asString(trace.get("learning.feedback.vectorDecision")),
                "unknown"));
        return out;
    }

    private Map<String, Object> withVectorDlq(Map<String, Object> base) {
        Map<String, Object> out = new LinkedHashMap<>(base == null ? Map.of() : base);
        out.put("dlq", vectorDlqSummary());
        return out;
    }

    private Map<String, Object> patchSourceCounts(Map<String, Object> affectedSourceSet,
                                                   Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("affectedSourceSet", affectedSourceSet);
        out.put("active", truthy(affectedSourceSet.get("active")) || truthy(trace.get("sourceSet.active")));
        out.put("traceKeyCount", trace == null ? 0 : trace.size());
        Object path = affectedSourceSet.get("path");
        if (path != null) {
            String pathText = asString(path);
            out.put("pathHash", SafeRedactor.hashValue(pathText));
            out.put("pathLength", pathText == null ? 0 : pathText.length());
        }
        return out;
    }

    private Map<String, Object> patchQuality(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patchReason", section(payload, "patchReason"));
        out.put("verificationEvidence", section(payload, "verificationEvidence"));
        out.put("rollbackHint", section(payload, "rollbackHint"));
        return out;
    }

    private Map<String, Object> patchCallChain(Map<String, Object> callChainHint,
                                               Map<String, Object> trace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("callChainHint", callChainHint);
        String signature = RawSlotExtractor.signature(trace);
        if (signature != null && !signature.isBlank()) {
            out.put("compactSignatureHash", SafeRedactor.hashValue(signature));
            out.put("compactSignatureLength", signature.length());
        }
        out.put("traceDepth", trace == null ? 0 : trace.size());
        return out;
    }

    private Map<String, Object> patchTriggerMatrix(Map<String, Object> triggerTrace,
                                                   Map<String, Object> trace,
                                                   String failureClass,
                                                   String hotspot) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("triggerTrace", triggerTrace);
        out.put("failureClass", firstNonBlank(failureClass, "none"));
        out.put("hotspot", firstNonBlank(hotspot, "patch"));
        out.put("traceFrameKeyCount", trace == null ? 0 : trace.size());
        String signature = RawSlotExtractor.signature(trace);
        if (signature != null && !signature.isBlank()) {
            out.put("callChainSignatureHash", SafeRedactor.hashValue(signature));
            out.put("callChainSignatureLength", signature.length());
            out.put("patternId", RawSlotExtractor.patternIdFromTrace(trace));
        }
        return out;
    }

    private static Map<String, Object> section(Map<String, Object> payload, String key) {
        if (payload == null || key == null) {
            return Map.of();
        }
        Object value = payload.get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry != null && entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        if (value == null) {
            return Map.of();
        }
        return Map.of("value", value);
    }

    private static String patchMemoryDecision(Map<String, Object> memoryDecision) {
        String raw = firstNonBlank(asString(memoryDecision.get("decision")), "SKIP");
        String safe = SafeRedactor.traceLabelOrFallback(raw, "SKIP")
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        if ("STORE".equals(safe)) {
            return "STORE";
        }
        if ("QUARANTINE".equals(safe)) {
            return "QUARANTINE";
        }
        return "SKIP";
    }

    private static String inferSourceSetHotspot(Map<String, Object> affectedSourceSet) {
        if (affectedSourceSet == null || affectedSourceSet.isEmpty()) {
            return null;
        }
        String value = firstNonBlank(
                asString(affectedSourceSet.get("path")),
                asString(affectedSourceSet.get("sourceSetLabel")),
                asString(affectedSourceSet.get("sourceSet")));
        String safe = safe(value);
        if (safe == null) {
            return null;
        }
        String lower = safe.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (lower.contains("resources")) {
            return "main-resources";
        }
        if (lower.contains("java_clean")) {
            return "app-java-clean";
        }
        if (lower.contains("main/java") || lower.contains("main-java")) {
            return "main-java";
        }
        if (lower.contains("test/java") || lower.contains("test-java")) {
            return "test-java";
        }
        return "source-set";
    }

    private static Integer toNullableInt(String value) {
        String safe = safe(value);
        if (safe == null || !safe.matches("-?\\d{1,9}")) {
            return null;
        }
        return Integer.parseInt(safe);
    }

    private Map<String, Object> vectorDlqSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quarantine", dlqCounts(quarantineDlqRepository == null ? null : quarantineDlqRepository.getIfAvailable()));
        out.put("shadowMerge", shadowDlqCounts(shadowMergeDlqRepository == null ? null : shadowMergeDlqRepository.getIfAvailable()));
        return out;
    }

    private Map<String, Long> dlqCounts(VectorQuarantineDlqRepository repo) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("pending", countQuietly(() -> repo == null ? 0L : repo.countByStatus(VectorQuarantineDlq.Status.PENDING)));
        out.put("blocked", countQuietly(() -> repo == null ? 0L : repo.countByStatus(VectorQuarantineDlq.Status.BLOCKED)));
        out.put("failed", countQuietly(() -> repo == null ? 0L : repo.countByStatus(VectorQuarantineDlq.Status.FAILED)));
        return out;
    }

    private Map<String, Long> shadowDlqCounts(VectorShadowMergeDlqRepository repo) {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("pending", countQuietly(() -> repo == null ? 0L : repo.countByStatus(VectorShadowMergeDlq.Status.PENDING)));
        out.put("blocked", countQuietly(() -> repo == null ? 0L : repo.countByStatus(VectorShadowMergeDlq.Status.BLOCKED)));
        out.put("failed", countQuietly(() -> repo == null ? 0L : repo.countByStatus(VectorShadowMergeDlq.Status.FAILED)));
        return out;
    }

    private long countQuietly(CountSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception ignored) {
            log.debug("[AWX2AF2][ops-ledger] fail-soft stage={}", "countQuietly");
            return 0L;
        }
    }

    private Decision ragDecision(QueryResponse response, Map<String, Object> trace) {
        double blackboxRisk = asDouble(trace.get("blackbox.risk.riskScore"), 0.0d);
        String blackboxFailure = firstNonBlank(asString(trace.get("blackbox.risk.dominantFailure")), "none");
        if (blackboxRisk > 0.0d && !"none".equalsIgnoreCase(blackboxFailure)) {
            return new Decision(blackboxRisk >= 0.65d ? "DEGRADED" : "OBSERVED",
                    blackboxFailure.replace('_', '-'));
        }
        Map<String, Object> debug = response == null || response.debug == null ? Map.of() : response.debug;
        if (truthyAny(trace, "langgraph.invoke.fallbackTriggered")
                || truthyAny(debug, "langgraph.invoke.fallbackTriggered")
                || "sequential".equalsIgnoreCase(asString(debug.get("langgraph.fallback")))) {
            return new Decision("DEGRADED", "langgraph-invoke-fallback");
        }
        if (falsey(debug.get("langgraph.primary.promotionEligible"))
                || falsey(debug.get("langgraph.shadow.promotionEligible"))) {
            return new Decision("DEGRADED", "langgraph-promotion-blocked");
        }
        int resultCount = response == null || response.results == null ? 0 : response.results.size();
        if (resultCount == 0) {
            return new Decision("ZERO_RESULT", "zero-result");
        }
        if (truthyAny(trace, "web.naver.providerDisabled", "web.brave.providerDisabled", "web.serpapi.providerDisabled", "web.tavily.providerDisabled")) {
            return new Decision("DEGRADED", "provider-disabled");
        }
        if (truthyAny(trace, "webSearch.afterFilterStarved", "web.naver.afterFilterStarved", "web.brave.afterFilterStarved", "web.serpapi.afterFilterStarved", "web.tavily.afterFilterStarved")) {
            return new Decision("DEGRADED", "after-filter-starvation");
        }
        if (truthyAny(trace, "web.naver.cancelled", "web.brave.cancelled", "web.serpapi.cancelled", "web.tavily.cancelled")) {
            return new Decision("DEGRADED", "cancelled");
        }
        if (truthyAny(trace, "web.timeout", "web.naver.timeout", "web.brave.timeout", "web.serpapi.timeout", "web.tavily.timeout")) {
            return new Decision("DEGRADED", "timeout");
        }
        return new Decision("OK", "none");
    }

    private Map<String, Object> selectByPrefix(Map<?, ?> source, String... prefixes) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (matchesAny(key, prefixes)) {
                Object sanitized = sanitizeValue(key, entry.getValue(), 0);
                if (sanitized != null) {
                    out.put(safeKey(key), sanitized);
                }
            }
        }
        return out;
    }

    private Object sanitizeValue(String key, Object value, int depth) {
        if (value == null || isDeniedKey(key) || depth > 4) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence seq) {
            return safeTraceText(seq, 240);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                Object sanitized = sanitizeValue(childKey, entry.getValue(), depth + 1);
                if (sanitized != null) {
                    out.put(safeKey(childKey), sanitized);
                }
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            for (Object item : collection) {
                Object sanitized = sanitizeValue(key, item, depth + 1);
                if (sanitized != null) {
                    out.add(sanitized);
                }
                if (out.size() >= 50) {
                    break;
                }
            }
            return out;
        }
        return safeTraceText(value, 240);
    }

    private String toJson(Map<String, Object> value) {
        Map<String, Object> sanitized = sanitizeMap(value);
        try {
            return clip(objectMapper.writeValueAsString(sanitized), Math.max(256, maxJsonChars));
        } catch (JsonProcessingException e) {
            log.debug("[AWX2AF2][ops-ledger] fail-soft stage={}", "toJson");
            return "{}";
        }
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Object sanitized = sanitizeValue(entry.getKey(), entry.getValue(), 0);
            if (sanitized != null) {
                out.put(safeKey(entry.getKey()), sanitized);
            }
        }
        return out;
    }

    private Map<String, Object> toPublicMap(RagOpsLedgerEntry entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entry.getId());
        out.put("runId", publicLabel(entry.getRunId(), 96));
        out.put("entryType", publicLabel(entry.getEntryType(), 64));
        out.put("sessionHash", publicLabel(entry.getSessionHash(), 96));
        out.put("requestHash", publicLabel(entry.getRequestHash(), 96));
        out.put("queryHash", publicLabel(entry.getQueryHash(), 96));
        out.put("queryLength", entry.getQueryLength());
        out.put("planId", publicLabel(entry.getPlanId(), 128));
        out.put("strategyName", publicLabel(entry.getStrategyName(), 128));
        out.put("resourceTier", publicLabel(entry.getResourceTier(), 64));
        out.put("sourceCounts", fromJsonMap(entry.getSourceCountsJson()));
        out.put("quality", fromJsonMap(entry.getQualityJson()));
        out.put("vector", fromJsonMap(entry.getVectorJson()));
        out.put("kg", fromJsonMap(entry.getKgJson()));
        Map<String, Object> matrix = fromJsonMap(entry.getMatrixJson());
        out.put("matrix", matrix);
        Map<String, Object> gpuAdmission = gpuAdmissionFromMatrix(matrix);
        if (!gpuAdmission.isEmpty()) {
            out.put("gpuAdmission", gpuAdmission);
        }
        out.put("decision", publicLabel(entry.getDecision(), 64));
        out.put("failureClass", publicLabel(entry.getFailureClass(), 96));
        out.put("hotspot", publicLabel(entry.getHotspot(), 96));
        out.put("latencyMs", entry.getLatencyMs());
        out.put("createdAt", entry.getCreatedAt() == null ? null : entry.getCreatedAt().toString());
        return out;
    }

    private Map<String, Object> gpuAdmissionFromMatrix(Map<String, Object> matrix) {
        if (matrix == null || matrix.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> hardware = new LinkedHashMap<>();
        copyIfPresent(hardware, "status", matrix, "uaw.gpu-hardware.status");
        copyIfPresent(hardware, "available", matrix, "uaw.gpu-hardware.available");
        copyIfPresent(hardware, "detectedCount", matrix, "uaw.gpu-hardware.detectedCount");
        copyIfPresent(hardware, "hasRtx3090", matrix, "uaw.gpu-hardware.hasRtx3090");
        copyIfPresent(hardware, "hasRtx3060", matrix, "uaw.gpu-hardware.hasRtx3060");
        copyIfPresent(hardware, "heavyLaneReady", matrix, "uaw.gpu-hardware.heavyLaneReady");
        copyIfPresent(hardware, "maxMemoryUsedRatio", matrix, "uaw.gpu-hardware.maxMemoryUsedRatio");
        copyIfPresent(hardware, "admissionStatus", matrix, "uaw.gpu-hardware.admission.status");
        copyIfPresent(hardware, "reason", matrix, "uaw.gpu-hardware.admission.reason");
        copyIfPresent(hardware, "pressureLevel", matrix, "uaw.gpu-hardware.admission.pressureLevel");
        copyIfPresent(hardware, "retrainAllowed", matrix, "uaw.gpu-hardware.admission.retrainAllowed");
        copyIfPresent(hardware, "rerankAllowed", matrix, "uaw.gpu-hardware.admission.rerankAllowed");
        copyIfPresent(hardware, "embeddingFallbackAllowed", matrix,
                "uaw.gpu-hardware.admission.embeddingFallbackAllowed");
        copyIfPresent(hardware, "retrainBlocked", matrix, "uaw.gpu-hardware.admission.retrainBlocked");
        copyIfPresent(hardware, "pressure", matrix, "q_gpu_hardware_pressure");
        copyIfPresent(hardware, "pressure", matrix, "uaw.gpu-hardware.admission.pressure");
        if (!hardware.isEmpty()) {
            out.put("hardware", hardware);
        }

        Map<String, Object> gateway = new LinkedHashMap<>();
        copyIfPresent(gateway, "status", matrix, "uaw.gpu-gateway.admission.status");
        copyIfPresent(gateway, "blocked", matrix, "uaw.gpu-gateway.admission.blocked");
        copyIfPresent(gateway, "blockedCount", matrix, "uaw.gpu-gateway.admission.blocked.count");
        copyIfPresent(gateway, "pressure", matrix, "q_gpu_gateway_pressure");
        if (!gateway.isEmpty()) {
            out.put("gateway", gateway);
        }
        return out;
    }

    private Map<String, Object> fromJsonMap(String json) {
        String safeJson = safe(json);
        if (safeJson == null) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(safeJson, new TypeReference<>() {
            });
            return sanitizeMap(parsed);
        } catch (Exception e) {
            log.debug("[AWX2AF2][ops-ledger] fail-soft stage={}", "fromJsonMap");
            return Map.of();
        }
    }

    private String strategyName(QueryRequest request, QueryResponse response, Map<String, Object> trace) {
        Object debugStrategy = response == null || response.debug == null ? null : response.debug.get("strategyName");
        return firstNonBlank(
                asString(debugStrategy),
                asString(trace.get("rgb.strategy")),
                asString(trace.get("moe.strategy")),
                asString(trace.get("langgraph.mode")),
                request != null && request.deepResearch ? "deep-research" : null,
                request != null && request.aggressive ? "aggressive" : null,
                "default");
    }

    private String hotspotFromTrace(Map<String, Object> trace) {
        Object hotspot = trace.get("uaw.autolearn.loop.hotspot");
        if (hotspot instanceof Map<?, ?> map) {
            return asString(map.get("hotspot"));
        }
        return null;
    }

    private String resolveRagHotspot(Map<String, Object> debug) {
        if (debug == null || debug.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Object> entry : debug.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey();
            if (!key.startsWith("stage.") || nonNone(asString(entry.getValue())) == null) {
                continue;
            }
            String[] parts = key.split("\\.");
            if (parts.length >= 3 && ("failureClass".equals(parts[2])
                    || "timeout".equals(parts[2])
                    || "error".equals(parts[2]))) {
                return normalizeHotspot(parts[1]);
            }
        }
        if (truthyAny(debug, "webSearch.afterFilterStarved", "web.naver.afterFilterStarved",
                "web.brave.afterFilterStarved", "web.serpapi.afterFilterStarved",
                "web.tavily.afterFilterStarved")) {
            return "webSearch";
        }
        if (truthyAny(debug, "vector.empty", "vector.failed", "rag.eval.vectorEmpty")) {
            return "vector";
        }
        if (truthyAny(debug, "kg.empty", "kg.failed", "rag.eval.kgEmpty")) {
            return "kg";
        }
        if (truthyAny(debug, "rag.eval.emptyResult") && toIntSafe(debug.get("outCount"), 0) == 0) {
            return "webSearch";
        }
        return null;
    }

    private static String normalizeHotspot(String value) {
        String safe = safe(value);
        if (safe == null) {
            return null;
        }
        String normalized = safe.toLowerCase(Locale.ROOT);
        if (normalized.contains("onnx")) {
            return "onnx";
        }
        if (normalized.contains("rerank")) {
            return "rerank";
        }
        if (normalized.contains("web")) {
            return "webSearch";
        }
        if (normalized.contains("vector")) {
            return "vector";
        }
        if (normalized.contains("kg") || normalized.contains("graph")) {
            return "kg";
        }
        if (normalized.contains("bm25")) {
            return "bm25";
        }
        if (normalized.contains("langgraph")) {
            return "langgraph";
        }
        return safeKey(safe);
    }

    private static Map<String, Long> countBy(List<RagOpsLedgerEntry> rows, FieldReader reader) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (RagOpsLedgerEntry row : rows) {
            String key = publicLabelOrFallback(reader.read(row), "unknown", 96);
            out.put(key, out.getOrDefault(key, 0L) + 1L);
        }
        return out;
    }

    private static void addIfPresent(Map<String, Object> out, String targetKey, Map<String, Object> source, String sourceKey) {
        Object value = source.get(sourceKey);
        if (value != null) {
            out.put(targetKey, value);
        }
    }

    private static void copyIfPresent(Map<String, Object> out,
                                      String targetKey,
                                      Map<String, Object> source,
                                      String sourceKey) {
        if (out == null || source == null || targetKey == null || sourceKey == null || !source.containsKey(sourceKey)) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value != null && !out.containsKey(targetKey)) {
            out.put(targetKey, value);
        }
    }

    private static boolean matchesAny(String key, String... prefixes) {
        if (key == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix != null && key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean truthyAny(Map<String, Object> trace, String... keys) {
        for (String key : keys) {
            if (truthy(trace.get(key))) {
                return true;
            }
        }
        return false;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0d;
        }
        if (value instanceof CharSequence seq) {
            String s = seq.toString().trim();
            return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
        }
        return false;
    }

    private static boolean falsey(Object value) {
        if (value instanceof Boolean b) {
            return !b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() == 0.0d;
        }
        if (value instanceof CharSequence seq) {
            String s = seq.toString().trim();
            return "false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s);
        }
        return false;
    }

    private static boolean isDeniedKey(String key) {
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (k.contains("hash") || k.endsWith("length") || k.endsWith("count") || k.contains("fingerprint")) {
            return false;
        }
        return k.contains("query")
                || k.contains("prompt")
                || k.contains("answer")
                || k.contains("snippet")
                || k.contains("content")
                || k.contains("text")
                || k.contains("raw")
                || k.contains("token")
                || k.contains("key")
                || k.contains("secret")
                || k.contains("password")
                || k.contains("owner")
                || k.contains("payload");
    }

    private static String normalizeSource(String source) {
        String s = source == null ? "" : source.toLowerCase(Locale.ROOT);
        if (s.contains("web") || s.contains("naver") || s.contains("brave")
                || s.contains("serp") || s.contains("tavily")) {
            return "WEB";
        }
        if (s.contains("vector") || s.contains("pinecone") || s.contains("upstash")) {
            return "VECTOR";
        }
        if (s.contains("kg") || s.contains("graph") || s.contains("neo4j")) {
            return "KG";
        }
        if (s.contains("bm25")) {
            return "BM25";
        }
        return "OTHER";
    }

    private static String hash(String value) {
        String safe = safe(value);
        return safe == null ? null : DigestUtils.sha256Hex(safe);
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return numeric;
            }
            traceAsDoubleSuppressed();
            log.debug("[AWX2AF2][ops-ledger] fail-soft stage={}", "asDouble");
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            traceAsDoubleSuppressed();
            log.debug("[AWX2AF2][ops-ledger] fail-soft stage={}", "asDouble");
            return fallback;
        } catch (NumberFormatException ignore) {
            traceAsDoubleSuppressed();
            log.debug("[AWX2AF2][ops-ledger] fail-soft stage={}", "asDouble");
            return fallback;
        }
    }

    private static void traceAsDoubleSuppressed() {
        TraceStore.put("rag.opsLedger.suppressed.asDouble", true);
        TraceStore.put("rag.opsLedger.suppressed.asDouble.errorType", "invalid_number");
    }

    private static int toIntSafe(Object value, int fallback) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? n.intValue() : fallback;
        }
        String text = String.valueOf(value).trim();
        return text.matches("-?\\d{1,9}") ? Integer.parseInt(text) : fallback;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String safe = safe(value);
            if (safe != null) {
                return safe;
            }
        }
        return null;
    }

    private static String nonNone(String value) {
        String safe = safe(value);
        if (safe == null || "none".equalsIgnoreCase(safe)) {
            return null;
        }
        return safe;
    }

    private static String blankToNull(String value) {
        return safe(value);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        int safeMax = Math.max(1, max);
        return value.length() <= safeMax ? value : value.substring(0, safeMax);
    }

    private static String safeTraceText(Object value, int max) {
        return clip(SafeRedactor.traceLabel(value), max);
    }

    private static String publicLabel(String value, int max) {
        return value == null ? null : clip(SafeRedactor.traceLabel(value), max);
    }

    private static String publicLabelOrFallback(String value, String fallback, int max) {
        String label = publicLabel(value, max);
        return label == null || label.isBlank() ? fallback : label;
    }

    private static String safeKey(String key) {
        return clip(key == null ? "unknown" : key.replaceAll("[^A-Za-z0-9_.-]", "_"), 96);
    }

    private record Decision(String decision, String failureClass) {
    }

    private interface CountSupplier {
        long get();
    }

    private interface FieldReader {
        String read(RagOpsLedgerEntry entry);
    }
}
