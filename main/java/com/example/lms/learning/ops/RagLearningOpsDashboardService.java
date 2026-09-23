package com.example.lms.learning.ops;

import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ops.RagOpsLedgerService;
import com.example.lms.service.vector.VectorQuarantineDlqService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class RagLearningOpsDashboardService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final long MAX_TAIL_BYTES = 5L * 1024L * 1024L;
    private static final long CACHE_TTL_MS = 5_000L;

    private final Environment env;
    private final ObjectMapper objectMapper;
    private final UawAutolearnProperties autolearnProperties;
    private final UawAutolearnQualityTracker qualityTracker;
    private final ObjectProvider<RagOpsLedgerService> opsLedgerProvider;
    private final ObjectProvider<VectorQuarantineDlqService> vectorQuarantineProvider;
    private final ObjectProvider<com.example.lms.learning.virtualpoint.VirtualPointService> virtualPointProvider;
    private final ObjectProvider<ModelRuntimeHealthTracker> modelHealthProvider;
    private volatile Cached cached;

    public RagLearningOpsDashboardService(Environment env,
                                          ObjectMapper objectMapper,
                                          UawAutolearnProperties autolearnProperties,
                                          UawAutolearnQualityTracker qualityTracker,
                                          ObjectProvider<RagOpsLedgerService> opsLedgerProvider,
                                          ObjectProvider<VectorQuarantineDlqService> vectorQuarantineProvider,
                                          ObjectProvider<com.example.lms.learning.virtualpoint.VirtualPointService> virtualPointProvider,
                                          ObjectProvider<ModelRuntimeHealthTracker> modelHealthProvider,
                                          ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.env = env;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.autolearnProperties = autolearnProperties == null ? new UawAutolearnProperties() : autolearnProperties;
        this.qualityTracker = qualityTracker;
        this.opsLedgerProvider = opsLedgerProvider;
        this.vectorQuarantineProvider = vectorQuarantineProvider;
        this.virtualPointProvider = virtualPointProvider;
        this.modelHealthProvider = modelHealthProvider;
        registerMeters(meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable());
    }

    public Map<String, Object> overview(int limit) {
        int safeLimit = clampLimit(limit);
        if (safeLimit == DEFAULT_LIMIT) {
            return copyMap(cachedSnapshot().overview());
        }
        Snapshot snapshot = buildSnapshot(safeLimit);
        return snapshot.overview();
    }

    public Map<String, Object> samples(int limit) {
        Snapshot snapshot = buildSnapshot(limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", snapshot.datasetSummary());
        out.put("items", snapshot.samples());
        return out;
    }

    public Map<String, Object> failures(int limit) {
        Snapshot snapshot = buildSnapshot(limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", snapshot.failureSummary());
        out.put("topCauses", snapshot.failureTopCauses());
        out.put("recent", snapshot.failureRecent());
        return out;
    }

    public Map<String, Object> metrics() {
        return cachedSnapshot().metrics().toMap();
    }

    public String prometheus() {
        Cached c = cachedSnapshot();
        MetricsSnapshot metrics = c.metrics();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> modelCards = (List<Map<String, Object>>) c.overview()
                .getOrDefault("modelCards", List.of());
        StringBuilder out = new StringBuilder();
        appendGauge(out, "rag_learning_train_samples_total", "Parsed train_rag JSONL samples in the bounded dashboard tail", metrics.sampleTotal());
        appendGauge(out, "rag_learning_train_samples_quarantined", "Samples currently classified as quarantine or review blocked", metrics.sampleQuarantined());
        appendGauge(out, "rag_learning_failure_patterns_total", "Failure pattern events in the bounded dashboard tail", metrics.failurePatternTotal());
        appendGauge(out, "rag_learning_virtual_points", "Current in-memory Virtual Point count", metrics.virtualPointCount());
        appendGauge(out, "rag_learning_idle_retrain_ingested", "Last IdleTrain retrain ingest count", metrics.idleRetrainIngested());
        appendGauge(out, "rag_learning_model_count", "Model cards exposed by the learning ops dashboard", metrics.modelCount());
        appendGauge(out, "rag_learning_context_contamination_score", "Current AutoLearn context contamination EWMA", metrics.contextContaminationScore());
        appendGauge(out, "rag_learning_evidence_rate", "Evidence-bearing sample ratio in the bounded dashboard tail", metrics.evidenceRate());
        appendHeader(out, "rag_learning_model_success_rate", "Success rate by model or runtime health record", "gauge");
        appendHeader(out, "rag_learning_model_failure_rate", "Failure rate by model or runtime health record", "gauge");
        appendHeader(out, "rag_learning_model_evidence_rate", "Evidence-bearing ratio by model where dataset rows exist", "gauge");
        appendHeader(out, "rag_learning_model_latency_ms_avg", "Average latency by model when available", "gauge");
        for (Map<String, Object> card : modelCards) {
            String model = safeMetricLabel(asString(card.get("model")));
            String source = safeMetricLabel(asString(card.get("source")));
            appendModelSample(out, "rag_learning_model_success_rate", model, source, number(card.get("successRate"), 0.0d));
            appendModelSample(out, "rag_learning_model_failure_rate", model, source, number(card.get("failureRate"), 0.0d));
            appendModelSample(out, "rag_learning_model_evidence_rate", model, source, number(card.get("evidenceRate"), 0.0d));
            Object latency = card.get("avgLatencyMs");
            if (latency instanceof Number n) {
                appendModelSample(out, "rag_learning_model_latency_ms_avg", model, source, n.doubleValue());
            }
        }
        return out.toString();
    }

    private Snapshot buildSnapshot(int requestedLimit) {
        int limit = clampLimit(requestedLimit);
        DatasetSnapshot dataset = datasetSnapshot(limit);
        FailureSnapshot failures = failureSnapshot(limit);
        Map<String, Object> quality = qualitySnapshot();
        Map<String, Object> idleTrain = idleTrainSnapshot();
        Map<String, Object> matrix9 = matrixSnapshot(limit);
        Map<String, Object> orchestrationOverlays = orchestrationOverlaysSnapshot(matrix9);
        Map<String, Object> quarantine = quarantineSnapshot();
        Map<String, Object> opsLedger = opsLedgerSnapshot();
        Map<String, Object> learningOpsCollector = learningOpsCollectorSnapshot();
        Map<String, Object> legacyDevelopmentCandidates = legacyDevelopmentCandidatesSnapshot();
        List<Map<String, Object>> modelCards = modelCards(dataset.modelAggs());
        MetricsSnapshot metrics = metrics(dataset, failures, matrix9, idleTrain, modelCards, quality);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("checkpoint", "[AWX][learning-ops]");
        out.put("dataset", dataset.summary());
        out.put("samples", dataset.samples());
        out.put("failurePatterns", failures.summary());
        out.put("failureTopCauses", failures.topCauses());
        out.put("failureRecent", failures.recent());
        out.put("quality", quality);
        out.put("idleTrain", idleTrain);
        out.put("matrix9", matrix9);
        out.put("orchestrationOverlays", orchestrationOverlays);
        out.put("quarantine", quarantine);
        out.put("modelCards", modelCards);
        out.put("opsLedger", opsLedger);
        out.put("learningOpsCollector", learningOpsCollector);
        out.put("legacyDevelopmentCandidates", legacyDevelopmentCandidates);
        out.put("metrics", metrics.toMap());
        out.put("links", links());
        return new Snapshot(out, dataset.summary(), dataset.samples(), failures.summary(), failures.topCauses(),
                failures.recent(), metrics);
    }

    private DatasetSnapshot datasetSnapshot(int limit) {
        Path path = datasetPath();
        JsonlTail tail = jsonlTail(path, limit);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("configured", path != null);
        summary.put("exists", tail.exists());
        putFileNameDiagnostics(summary, "datasetFile", fileName(path));
        summary.put("datasetPathHash", hashPath(path));
        summary.put("sizeBytes", tail.sizeBytes());
        summary.put("tailLines", tail.lines().size());
        summary.put("tailTruncated", tail.truncated());
        summary.put("parseErrors", tail.parseErrors());

        List<Map<String, Object>> samples = new ArrayList<>();
        Map<String, ModelAgg> byModel = new LinkedHashMap<>();
        Map<String, Integer> legacyFeatureGroups = new LinkedHashMap<>();
        Map<String, Integer> domainCorpusGroups = new LinkedHashMap<>();
        Map<String, Integer> kbDomainGroups = new LinkedHashMap<>();
        int parsed = 0;
        int quarantined = 0;
        int accepted = 0;
        int evidenceBearing = 0;
        List<String> lines = new ArrayList<>(tail.lines());
        Collections.reverse(lines);
        for (String line : lines) {
            Map<String, Object> row = parseJson(line);
            if (row.isEmpty()) {
                continue;
            }
            parsed++;
            Map<String, Object> sample = sample(row, parsed);
            samples.add(sample);
            increment(legacyFeatureGroups, asString(sample.get("legacyFeature")));
            increment(domainCorpusGroups, asString(sample.get("domainCorpus")));
            increment(kbDomainGroups, asString(sample.get("kbDomain")));
            boolean isQuarantined = truthy(sample.get("quarantine"));
            boolean isAccepted = "accepted".equalsIgnoreCase(asString(sample.get("validationDecision")))
                    && !isQuarantined;
            if (isQuarantined) {
                quarantined++;
            }
            if (isAccepted) {
                accepted++;
            }
            if (number(sample.get("evidenceCount"), 0.0d) > 0.0d || !((List<?>) sample.getOrDefault("passages", List.of())).isEmpty()) {
                evidenceBearing++;
            }
            String model = firstNonBlank(asString(sample.get("model")), "unknown");
            ModelAgg agg = byModel.computeIfAbsent(model, ModelAgg::new);
            agg.addSample(sample, isAccepted);
        }
        summary.put("parsedLines", parsed);
        summary.put("sampleCount", samples.size());
        summary.put("acceptedCount", accepted);
        summary.put("quarantineCount", quarantined);
        summary.put("evidenceBearingCount", evidenceBearing);
        summary.put("evidenceRate", ratio(evidenceBearing, Math.max(1, parsed)));
        summary.put("legacyFeatureGroups", legacyFeatureGroups);
        summary.put("domainCorpusGroups", domainCorpusGroups);
        summary.put("kbDomainGroups", kbDomainGroups);
        summary.put("status", !tail.exists() ? "file_missing" : parsed == 0 ? "empty_or_unparsed" : "ready");
        return new DatasetSnapshot(summary, List.copyOf(samples), byModel);
    }

    private Map<String, Object> sample(Map<String, Object> row, int ordinal) {
        Map<String, Object> validation = childMap(row.get("validation"));
        Map<String, Object> runtime = childMap(validation.get("runtime"));
        Map<String, Object> feedback = childMap(validation.get("feedback"));
        Map<String, Object> anomalies = childMap(validation.get("anomalies"));
        Map<String, Object> thresholds = childMap(validation.get("thresholds"));
        Map<String, Object> metadata = childMap(row.get("metadata"));
        String legacyFeature = firstNonBlank(asString(row.get("legacyFeature")), asString(metadata.get("legacyFeature")));
        String domainCorpus = firstNonBlank(asString(row.get("domainCorpus")), asString(metadata.get("domainCorpus")));
        String kbDomain = firstNonBlank(asString(row.get("kbDomain")), asString(metadata.get("kb_domain")));
        String sourceType = firstNonBlank(asString(row.get("sourceType")), asString(metadata.get("sourceType")));

        List<Map<String, Object>> passages = passages(row);
        double qualityScore = firstNumber(
                row.get("qualityScore"),
                validation.get("sampleScore"),
                row.get("score"),
                feedback.get("cfvmReward"));
        Double finalSigmoid = nullableNumber(
                row.get("final_sigmoid"),
                row.get("finalSigmoid"),
                validation.get("final_sigmoid"),
                validation.get("finalSigmoid"));
        boolean finalGate = truthy(firstPresent(row.get("finalGate"), validation.get("finalGate")));
        int evidenceCount = (int) firstNumber(row.get("evidenceCount"), runtime.get("evidenceCount"), passages.size());
        int afterFilterCount = (int) firstNumber(row.get("afterFilterCount"), runtime.get("afterFilterCount"), evidenceCount);
        List<String> rejectReasons = stringList(validation.get("rejectReasons"));
        List<String> anomalyFlags = stringList(anomalies.get("flags"));
        String vectorDecision = firstNonBlank(asString(feedback.get("vectorDecision")), asString(row.get("vectorDecision")));
        String validationDecision = decision(row, validation, finalGate, rejectReasons, vectorDecision);
        boolean quarantine = "QUARANTINE".equalsIgnoreCase(vectorDecision)
                || "rejected".equalsIgnoreCase(validationDecision)
                || !rejectReasons.isEmpty()
                || anomalyFlags.stream().anyMatch(s -> s.toLowerCase(Locale.ROOT).contains("contamination"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ordinal", ordinal);
        out.put("id", diagnosticDatasetId(firstNonBlank(asString(row.get("id")),
                hashText(asString(row.get("question")) + "|" + asString(row.get("answer"))))));
        out.put("ts", safeScalar(row.get("ts"), 64));
        out.put("dataset", safeScalar(row.get("dataset"), 80));
        out.put("source", safeScalar(row.get("source"), 80));
        out.put("sourceType", safeScalar(sourceType, 96));
        out.put("legacyFeature", safeScalar(legacyFeature, 96));
        out.put("domainCorpus", safeScalar(domainCorpus, 96));
        out.put("kbDomain", safeScalar(kbDomain, 96));
        out.put("model", safeScalar(row.get("model"), 120));
        out.put("provider", safeScalar(row.get("provider"), 80));
        out.put("branch", safeScalar(row.get("branch"), 80));
        out.put("questionPreview", preview(row.get("question"), 220));
        out.put("answerPreview", preview(row.get("answer"), 260));
        out.put("documentPreview", preview(firstPresent(row.get("text"), row.get("textPreview")), 260));
        out.put("legacyTrace", legacyTrace(row, metadata, legacyFeature, domainCorpus, kbDomain, sourceType));
        out.put("passages", passages);
        out.put("qualityScore", round4(qualityScore));
        out.put("finalSigmoid", finalSigmoid == null ? null : round4(finalSigmoid));
        out.put("finalGate", finalGate);
        out.put("evidenceCount", Math.max(0, evidenceCount));
        out.put("afterFilterCount", Math.max(0, afterFilterCount));
        out.put("contextDiversity", round4(firstNumber(row.get("contextDiversity"), runtime.get("contextDiversity"), 0.0d)));
        out.put("validationDecision", validationDecision);
        out.put("rejectReasons", rejectReasons);
        out.put("thresholds", sanitizedMap(thresholds));
        out.put("anomalyFlags", anomalyFlags);
        out.put("vectorDecision", firstNonBlank(vectorDecision, ""));
        out.put("contaminationScore", round4(firstNumber(validation.get("contaminationScore"), 0.0d)));
        out.put("contextContaminationScore", round4(firstNumber(validation.get("contextContaminationScore"), validation.get("legacyContextScore"), 0.0d)));
        out.put("riskScore", round4(firstNumber(validation.get("riskScore"), 0.0d)));
        out.put("contradictionScore", round4(firstNumber(validation.get("contradictionScore"), 0.0d)));
        out.put("contradictionCause", safeScalar(validation.get("contradictionCause"), 96));
        out.put("quarantine", quarantine);
        out.put("latencyMs", nullableNumber(row.get("latencyMs"), row.get("tookMs"), row.get("durationMs"), runtime.get("latencyMs")));
        return out;
    }

    private Map<String, Object> legacyTrace(Map<String, Object> row,
                                            Map<String, Object> metadata,
                                            String legacyFeature,
                                            String domainCorpus,
                                            String kbDomain,
                                            String sourceType) {
        Map<String, Object> out = new LinkedHashMap<>();
        putIfPresent(out, "sourceType", sourceType, 96);
        putIfPresent(out, "legacyFeature", legacyFeature, 96);
        putIfPresent(out, "domainCorpus", domainCorpus, 96);
        putIfPresent(out, "kbDomain", kbDomain, 96);
        putIfPresent(out, "legacyPath", diagnosticLegacyPath(firstNonBlank(asString(row.get("legacyPath")),
                asString(metadata.get("legacyPath")))), 180);
        putIfPresent(out, "domainTag", firstNonBlank(asString(row.get("domainTag")), asString(metadata.get("domainTag"))), 120);
        putIfPresent(out, "controllerPaths", metadata.get("controllerPaths"), 260);
        putIfPresent(out, "servicePaths", metadata.get("servicePaths"), 260);
        putIfPresent(out, "repositoryPaths", metadata.get("repositoryPaths"), 260);
        putIfPresent(out, "dtoPaths", metadata.get("dtoPaths"), 260);
        putIfPresent(out, "templatePaths", metadata.get("templatePaths"), 260);
        putIfPresent(out, "ragPipeline", metadata.get("ragPipeline"), 180);
        return out;
    }

    private FailureSnapshot failureSnapshot(int limit) {
        Path path = failurePatternPath();
        JsonlTail tail = jsonlTail(path, limit);
        Map<String, CauseAgg> causes = new LinkedHashMap<>();
        List<Map<String, Object>> recent = new ArrayList<>();
        int parsed = 0;
        List<String> lines = new ArrayList<>(tail.lines());
        Collections.reverse(lines);
        for (String line : lines) {
            Map<String, Object> row = parseJson(line);
            if (row.isEmpty()) {
                continue;
            }
            parsed++;
            String kind = safeLabel(firstNonBlank(asString(row.get("kind")), "unknown"), 80);
            String key = safeLabel(firstNonBlank(asString(row.get("key")), "none"), 120);
            String source = safeLabel(firstNonBlank(asString(row.get("source")), "log"), 80);
            String causeKey = kind + "|" + key + "|" + source;
            causes.computeIfAbsent(causeKey, ignored -> new CauseAgg(kind, key, source)).add(row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tsEpochMillis", row.getOrDefault("tsEpochMillis", 0));
            item.put("kind", kind);
            item.put("key", key);
            item.put("source", source);
            item.put("cooldownMs", row.getOrDefault("cooldownMs", 0));
            item.put("cooldownPolicy", safeScalar(row.get("cooldownPolicy"), 80));
            item.put("logger", safeScalar(row.get("logger"), 120));
            item.put("level", safeScalar(row.get("level"), 40));
            item.put("messageDiagnostic", safeScalar(row.get("message"), 180));
            recent.add(item);
        }
        List<Map<String, Object>> topCauses = causes.values().stream()
                .sorted(Comparator.comparingInt(CauseAgg::count).reversed())
                .limit(20)
                .map(CauseAgg::toMap)
                .toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("configured", path != null);
        summary.put("exists", tail.exists());
        putFileNameDiagnostics(summary, "logFile", fileName(path));
        summary.put("logPathHash", hashPath(path));
        summary.put("sizeBytes", tail.sizeBytes());
        summary.put("tailLines", tail.lines().size());
        summary.put("eventCount", parsed);
        summary.put("causeCount", topCauses.size());
        summary.put("parseErrors", tail.parseErrors());
        summary.put("status", !tail.exists() ? "file_missing" : parsed == 0 ? "empty_or_unparsed" : "ready");
        return new FailureSnapshot(summary, topCauses, List.copyOf(recent));
    }

    private Map<String, Object> qualitySnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (qualityTracker == null) {
            out.put("available", false);
            return out;
        }
        try {
            Object snapshot = qualityTracker.snapshot();
            out.put("available", true);
            out.put("snapshot", objectMapper.convertValue(snapshot, new TypeReference<Map<String, Object>>() {
            }));
            out.put("lastLoopDiagnostics", sanitizedObjectMap(qualityTracker.lastLoopDiagnostics()));
            out.put("lastLoopHotspot", sanitizedObjectMap(qualityTracker.lastLoopHotspot()));
        } catch (Exception e) {
            traceSkipped("quality_snapshot", e);
            out.put("available", false);
            out.put("error", e.getClass().getSimpleName());
        }
        return out;
    }

    private Map<String, Object> idleTrainSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("last", safeTrace("uaw.idle.last"));
        out.put("skipReason", safeTrace("uaw.idle.skip.reason"));
        out.put("retrainDisabledReason", safeTrace("uaw.retrain.disabledReason"));
        out.put("retrainSkipReason", safeTrace("uaw.retrain.skip.reason"));
        out.put("ingestedCount", traceLong("uaw.retrain.ingest.count"));
        out.put("ingestParsed", traceLong("uaw.retrain.ingest.parsed"));
        out.put("ingestQueued", traceLong("uaw.retrain.ingest.queued"));
        out.put("ingestFailed", traceLong("uaw.retrain.ingest.failed"));
        out.put("check", traceMap("uaw.retrain.check"));
        out.put("ingestSummary", traceMap("uaw.retrain.ingest.summary"));
        out.put("loopDiagnostics", traceMap("uaw.idle.loop-diagnostics"));
        return out;
    }

    private Map<String, Object> matrixSnapshot(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blackbox", traceKeys("blackbox.risk.",
                "riskScore", "priorityScore", "dominantFailure", "hotspot", "patternId", "restoreAction",
                "confidence", "decisionReason", "vectorDecision", "highRisk", "historyContaminationScore",
                "historySignalCount", "historyCorrectionAction", "virtualPoint.matched",
                "virtualPoint.similarity", "virtualPoint.priorPatternId", "virtualPoint.applied",
                "virtualPoint.reason"));
        out.put("matrix", traceMap("blackbox.risk.matrix"));
        com.example.lms.learning.virtualpoint.VirtualPointService service =
                virtualPointProvider == null ? null : virtualPointProvider.getIfAvailable();
        Map<String, Object> vp = new LinkedHashMap<>();
        vp.put("available", service != null);
        vp.put("size", service == null ? 0 : service.size());
        vp.put("items", service == null ? List.of() : service.snapshot(Math.min(limit, 50)));
        out.put("virtualPoint", vp);
        return out;
    }

    private Map<String, Object> orchestrationOverlaysSnapshot(Map<String, Object> matrix9) {
        Map<String, Object> anchorCompression = anchorCompressionSnapshot();
        Map<String, Object> cihRag = cihRagSnapshot();
        Map<String, Object> extremeZ = extremeZSnapshot();
        Map<String, Object> routingPlan = routingPlanSnapshot();
        Map<String, Object> hypernova = hypernovaSnapshot();
        Map<String, Object> matryoshka = matryoshkaSnapshot();
        Map<String, Object> localLlm = localLlmSnapshot();
        Map<String, Object> modelGuard = modelGuardSnapshot();
        Map<String, Object> zero100 = zero100Snapshot();
        Map<String, Object> promptPose = promptPoseSnapshot();
        Map<String, Object> virtualMatrix = virtualMatrixSnapshot(matrix9);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkpoint", "[AWX][rag][overlays]");
        out.put("anchorCompression", anchorCompression);
        out.put("cihRag", cihRag);
        out.put("extremeZ", extremeZ);
        out.put("routingPlan", routingPlan);
        out.put("hypernova", hypernova);
        out.put("matryoshka", matryoshka);
        out.put("localLlm", localLlm);
        out.put("modelGuard", modelGuard);
        out.put("zero100", zero100);
        out.put("promptPose", promptPose);
        out.put("virtualMatrix", virtualMatrix);
        out.put("activeCount", overlayActiveCount(anchorCompression, cihRag, extremeZ, routingPlan, hypernova, matryoshka, localLlm, modelGuard, zero100, promptPose, virtualMatrix));
        return out;
    }

    private Map<String, Object> anchorCompressionSnapshot() {
        Map<String, Object> composer = traceOverlayKeys("prompt.context.composer.",
                "version", "enabled", "activated", "reason", "pressureScore", "anchor.hash", "anchor.len",
                "topFactor", "input.webCount", "input.ragCount", "output.webCount", "output.ragCount",
                "dropCounts", "failSoft", "exception");
        Map<String, Object> anchorProbe = traceOverlayKeys("prompt.context.composer.anchorProbe.",
                "enabled", "applied", "failSoft", "reason", "kSchedule", "stageCounts", "finalCap",
                "inputCount", "outputCount", "reductionRatio", "probeMode");
        Map<String, Object> spreadProbe = traceOverlayKeys("prompt.context.composer.spreadProbe.",
                "enabled", "applied", "failSoft", "reason", "kSchedule", "stageCounts", "finalCap",
                "inputCount", "outputCount", "reductionRatio", "anchorDiversity", "authorityAvg", "noveltyAvg",
                "rerankConfidenceAvg", "anchor.hashes");
        Map<String, Object> dynamicGate = traceOverlayKeys("prompt.context.composer.dynamicGate.",
                "enabled", "applied", "reason", "matrixTileCounts", "suppressedCount", "demotedCount");
        Map<String, Object> memoryCompressor = traceOverlayKeys("prompt.memory.compressor.",
                "enabled", "activated", "reason", "inputLen", "outputLen", "lineDropCount",
                "contaminationScore", "anchor.hash", "anchor.len");
        Map<String, Object> traceAnchor = traceOverlayKeys("ablation.traceAnchor.",
                "version", "topHash", "topRouteHint", "topMatrixTile", "maxExpectedDelta", "maxP",
                "drop.max", "routeCorrectionNeed");
        Map<String, Object> blackboxTraceAnchor = traceMap("blackbox.risk.traceAnchor");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", traceBool("prompt.context.composer.activated")
                || traceBool("prompt.context.composer.anchorProbe.applied")
                || traceBool("prompt.context.composer.spreadProbe.applied")
                || traceBool("prompt.memory.compressor.activated"));
        out.put("composer", composer);
        out.put("anchorProbe", anchorProbe);
        out.put("spreadProbe", spreadProbe);
        out.put("dynamicGate", dynamicGate);
        out.put("memoryCompressor", memoryCompressor);
        out.put("traceAnchor", traceAnchor);
        out.put("blackboxTraceAnchor", blackboxTraceAnchor);
        return out;
    }

    private Map<String, Object> cihRagSnapshot() {
        Map<String, Object> out = traceOverlayKeys("cihRag.",
                "activeFileCount", "skippedFileCount", "iqrIterations", "iqrDisabledReason",
                "biEncoderApplied", "biEncoderDisabledReason",
                "onnxRerankApplied", "onnxRerankDisabledReason",
                "dppApplied", "dppDisabledReason",
                "mlaBreadcrumbCount", "breadcrumb.queryRedacted", "implementationStage");
        out.put("active", !out.isEmpty());
        return out;
    }

    private Map<String, Object> extremeZSnapshot() {
        Map<String, Object> out = traceOverlayKeys("extremez.",
                "enabled", "enabled.global", "enabled.plan", "activated", "skipReason", "activation.reason",
                "base.count", "extra.count", "merged.count", "maxSubQueries", "deadline.hit",
                "risk.score", "risk.weightedScore", "risk.threshold", "risk.trigger", "risk.reason",
                "risk.primaryCause", "risk.errorRate", "risk.starvationScore", "risk.retrievalFailureRate",
                "risk.patternId", "query.hash", "query.len");
        out.put("overdrive", traceOverlayKeys("overdrive.",
                "activated", "reason", "skipReason", "score", "sparse.score", "authority.avg",
                "contradiction.mean", "error.rate", "candidates.count", "narrow.input.count",
                "narrow.output.count", "narrow.failSoft", "narrow.reason", "blackbox.riskScore",
                "blackbox.restoreAction", "stagesApplied", "finalCandidateCount",
                "query.hash", "query.len"));
        out.put("active", traceBool("extremez.activated") || traceBool("overdrive.activated"));
        return out;
    }

    private Map<String, Object> routingPlanSnapshot() {
        Map<String, Object> body = new LinkedHashMap<>();
        putOverlayLabel(body, "booster.active", "boosterMode.active");
        putOverlayValue(body, "booster.excludedModes", "boosterMode.excludedModes");
        putOverlayLabel(body, "booster.priority", "boosterMode.priority");
        putOverlayLabel(body, "booster.exclusionReason", "boosterMode.exclusionReason");
        putOverlayLabel(body, "execution.primaryMode", "routing.executionPlan.primaryMode");
        putOverlayValue(body, "execution.triggers", "routing.executionPlan.triggers");
        putOverlayValue(body, "execution.applied", "routing.executionPlan.applied");
        putOverlayLabel(body, "execution.applied.primaryMode", "routing.executionPlan.applied.primaryMode");
        putOverlayValue(body, "execution.applied.triggers", "routing.executionPlan.applied.triggers");
        putOverlayLabel(body, "specialMode.conflict.suppressed", "specialMode.conflict.suppressed");
        putOverlayLabel(body, "retrievalOrder.lastSetBy", "retrievalOrder.lastSetBy");
        putOverlayValue(body, "retrievalOrder.lastOrder", "retrievalOrder.lastOrder");
        putOverlayLabel(body, "retrievalOrder.authority.owner", "retrievalOrder.authority.owner");
        putOverlayLabel(body, "retrievalOrder.authority.suppressedOwner", "retrievalOrder.authority.suppressedOwner");
        putOverlayLabel(body, "retrievalOrder.authority.reason", "retrievalOrder.authority.reason");
        putOverlayLabel(body, "retrievalOrder.authority.suppressedReason",
                "retrievalOrder.authority.suppressedReason");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", !body.isEmpty());
        out.putAll(body);
        return out;
    }

    private static void putOverlayValue(Map<String, Object> out, String targetKey, String traceKey) {
        Object value = TraceStore.get(traceKey);
        if (value != null) {
            out.put(targetKey, overlayDiagnosticValue(value));
        }
    }

    private static void putOverlayLabel(Map<String, Object> out, String targetKey, String traceKey) {
        Object value = TraceStore.get(traceKey);
        if (value == null) {
            return;
        }
        String label = String.valueOf(value)
                .replace('>', '_')
                .replace(',', '_');
        out.put(targetKey, SafeRedactor.traceLabelOrFallback(label, "unknown"));
    }

    private Map<String, Object> hypernovaSnapshot() {
        String planId = traceOverlayScalar("plan.id");
        String retrievalPlan = traceOverlayScalar("retrieval.plan");
        String kallocPlan = traceOverlayScalar("retrieval.kalloc.plan");
        Map<String, Object> kalloc = traceOverlayKeys("cfvm.kalloc.",
                "tile", "key", "arm", "policy", "ctx", "final", "plan", "baseline", "reward",
                "skipReason", "valueScore", "optimismScore", "resourceTier", "optimismDamping",
                "blackbox.riskScore", "blackbox.action", "blackbox.dominantFailure",
                "traceAnchor.routeHint", "traceAnchor.pressure", "traceAnchor.applied");
        Map<String, Object> runtime = traceOverlayKeys("hypernova.",
                "twpmP", "cvarFusedScore", "cvarAlpha", "cvarPhi", "clampApplied",
                "dppApplied", "dppInputCount", "dppOutputCount", "dppDisabledReason",
                "sourceScoreScaleMismatchCount", "sourceScoreScaleMismatchPolicy");
        Map<String, Object> riskK = traceOverlayKeys("nova.hypernova.riskK.",
                "used", "candidateCount", "totalK", "alloc.sum");
        putOverlayValue(riskK, "alloc", "hypernova.riskKAlloc");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", containsIgnoreCase(planId, "hyper")
                || containsIgnoreCase(retrievalPlan, "hyper")
                || containsIgnoreCase(kallocPlan, "hyper")
                || !runtime.isEmpty()
                || TraceStore.get("hypernova.riskKAlloc") != null
                || traceBool("nova.hypernova.riskK.used"));
        out.put("kallocEnabled", boolProp("retrieval.kalloc.enabled", false));
        out.put("kallocMaxSourceShare", number(prop("retrieval.kalloc.max-source-share", "0.65"), 0.65d));
        out.put("kallocHypernovaMaxSourceShare",
                number(prop("retrieval.kalloc.hypernova-max-source-share", "0.75"), 0.75d));
        if (!planId.isBlank()) {
            out.put("planId", planId);
        }
        if (!retrievalPlan.isBlank()) {
            out.put("retrievalPlan", retrievalPlan);
        }
        if (!kallocPlan.isBlank()) {
            out.put("retrievalKallocPlan", kallocPlan);
        }
        out.put("runtime", runtime);
        out.put("riskK", riskK);
        out.put("kalloc", kalloc);
        return out;
    }

    private Map<String, Object> matryoshkaSnapshot() {
        Map<String, Object> trace = traceOverlayKeys("embed.matryoshka.",
                "slice.actual", "slice.target", "slice.reductionRatio",
                "slice.expectedDistanceOpsRatio", "slice.expectedDistanceOpsSpeedup",
                "rawDim", "targetDim", "dimensionReduction", "strategy",
                "config.parse.suppressed.stage", "config.parse.suppressed.errorType",
                "suppressed.stage");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", !trace.isEmpty());
        out.putAll(trace);
        return out;
    }

    private Map<String, Object> localLlmSnapshot() {
        Map<String, Object> startup = traceOverlayKeys("localLlm.startup.",
                "enabled", "autostart", "status", "reason", "hostHash",
                "healthUrlHash", "healthUrlLength", "warmupTargetDim");
        Map<String, Object> warmup = traceOverlayKeys("localLlm.warmup.",
                "enabled", "status", "modelHash", "modelLength",
                "targetDim", "returnedDim", "reason");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", !startup.isEmpty() || !warmup.isEmpty());
        out.put("startup", startup);
        out.put("warmup", warmup);
        return out;
    }

    private Map<String, Object> modelGuardSnapshot() {
        Map<String, Object> out = traceOverlayKeys("llm.modelGuard.",
                "triggered", "mode", "endpoint", "failReason",
                "requestedModelHash", "requestedModelLength",
                "substituteChatModelHash", "substituteChatModelLength");
        out.put("active", !out.isEmpty() || traceBool("llm.modelGuard.triggered"));
        return out;
    }

    private Map<String, Object> zero100Snapshot() {
        Map<String, Object> out = traceOverlayKeys("zero100.",
                "enabled", "phase", "progressPct", "activeLane", "clampMode", "explorationRate",
                "remainingMs", "slice.idx", "slice.ms", "web.timeboxMs", "backoff.hardCapMs",
                "branch.weights", "queryBurst.max", "queryBurst.seedCount", "queryBurst.addedCount",
                "queryBurst.activeLane", "queryBurst.seedHashes", "crossVerify.required", "consensus.enabled",
                "consensus.rrfWeights",
                "webTimebox.applied", "webTimebox.ms", "webTimebox.hit", "webTimebox.timeout.count",
                "mpIntent.present", "mpIntent.lengthBucket", "mpIntent.hash12", "rollover.events");
        out.put("active", traceBool("zero100.enabled"));
        return out;
    }

    private Map<String, Object> promptPoseSnapshot() {
        Map<String, Object> out = traceOverlayKeys("promptPose.",
                "enabled", "route", "arm", "queryHash12", "selfAskCount", "queryBurstCap",
                "laneWeights", "skipReason", "failureClass", "rawIncluded",
                "application.enabled", "application.applied", "application.intentSlot",
                "application.evidenceSlot", "application.failureSlot", "application.feedbackSlot",
                "application.feedbackTile", "application.decisionHash12", "application.reason",
                "application.queryBurstMax", "application.selfAskCount", "application.answerTemperature",
                "application.selfAskTemperature", "application.minCitations", "application.laneWeights",
                "application.callRatios", "application.timeboxRatios",
                "application.riskPenaltyLambda", "application.minLaneCoverage",
                "application.feedbackMean", "application.feedbackCount", "application.compressionMode",
                "reward.arm", "reward.tileKey", "reward.value");
        out.put("active", traceBool("promptPose.enabled") || traceBool("promptPose.application.enabled"));
        return out;
    }

    private Map<String, Object> virtualMatrixSnapshot(Map<String, Object> matrix9) {
        Map<String, Object> matrix = childMap(matrix9 == null ? null : matrix9.get("matrix"));
        Map<String, Object> blackbox = childMap(matrix9 == null ? null : matrix9.get("blackbox"));
        Map<String, Object> virtualPoint = childMap(matrix9 == null ? null : matrix9.get("virtualPoint"));

        Map<String, Object> compactVirtualPoint = new LinkedHashMap<>();
        copyBoolean(compactVirtualPoint, "available", virtualPoint);
        copyNumeric(compactVirtualPoint, "size", virtualPoint);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", !matrix.isEmpty()
                || truthy(blackbox.get("virtualPoint.applied"))
                || number(virtualPoint.get("size"), 0.0d) > 0.0d);
        out.put("matrix", matrix);
        out.put("blackbox", blackbox);
        out.put("virtualPoint", compactVirtualPoint);
        return out;
    }

    private Map<String, Object> quarantineSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        VectorQuarantineDlqService service =
                vectorQuarantineProvider == null ? null : vectorQuarantineProvider.getIfAvailable();
        out.put("vectorDlq", service == null ? Map.of("enabled", false) : service.stats());
        out.put("vectorDlqReasonsPath", "/api/admin/vector/dlq/reasons");
        out.put("translationMemoryQuarantinePath", "/api/admin/vector/quarantine");
        return out;
    }

    private Map<String, Object> opsLedgerSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        RagOpsLedgerService service = opsLedgerProvider == null ? null : opsLedgerProvider.getIfAvailable();
        if (service == null) {
            out.put("enabled", false);
            return out;
        }
        try {
            out.put("enabled", service.isEnabled());
            out.put("summary", service.summary(24));
            List<Map<String, Object>> recent = service.recent(null, null, 10);
            List<Map<String, Object>> recentAutolearn = service.recent("AUTOLEARN_CYCLE", null, 10);
            out.put("recent", recent);
            out.put("recentAutolearn", recentAutolearn);
            out.put("datasetPipelineQueue", opsLedgerDatasetPipelineQueue(service.isEnabled(), recentAutolearn, recent));
        } catch (Exception e) {
            traceSkipped("ops_ledger_snapshot", e);
            out.put("enabled", service.isEnabled());
            out.put("error", e.getClass().getSimpleName());
        }
        return out;
    }

    private Map<String, Object> learningOpsCollectorSnapshot() {
        String outputPath = firstNonBlank(prop("awx.learning-ops.collector.output-path", ""),
                "data/macmini/learning-ops-curation.jsonl");
        boolean enabled = boolProp("awx.learning-ops.collector.enabled", false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bridge", "Dataset Pipeline");
        out.put("checkpoint", "[AWX][learning-ops][collector]");
        out.put("enabled", enabled);
        out.put("status", enabled ? "ready_read_only_curation_queue" : "disabled_by_config");
        out.put("mode", "read_only_curation_candidates");
        out.put("inputSource", "ops-ledger.datasetPipelineQueue");
        out.put("writesDataset", false);
        out.put("requiresReview", true);
        out.put("nodeRole", safeScalar(prop("awx.node.role", ""), 64));
        out.put("executionNode", safeScalar(prop("awx.node.execution-node", ""), 96));
        String outputFile = fileNameFromPath(outputPath);
        out.put("outputFileHash", hashText(outputFile));
        out.put("outputFileLength", outputFile.length());
        out.put("outputPathHash", Objects.toString(SafeRedactor.hashValue(outputPath), ""));
        out.put("intervalMs", number(prop("awx.learning-ops.collector.interval-ms", "300000"), 300000.0d));
        out.put("maxItems", longValue(prop("awx.learning-ops.collector.max-items", "20")));
        return out;
    }

    private Map<String, Object> legacyDevelopmentCandidatesSnapshot() {
        Path report = contextPurityReportPath("legacy-development-candidates.tsv");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkpoint", "[AWX][legacy][development-candidates]");
        out.put("mode", "metadata_filename_path_filter");
        out.put("cutoffUtc", "2026-05-01T00:00:00Z");
        putFileNameDiagnostics(out, "reportFile", fileName(report));
        out.put("reportPathHash", hashPath(report));
        out.put("writesDataset", false);
        out.put("requiresReview", true);
        if (report == null || !Files.exists(report) || !Files.isRegularFile(report)) {
            out.put("status", "report_missing");
            out.put("itemCount", 0);
            out.put("byFeature", Map.of());
            out.put("byAction", Map.of());
            out.put("items", List.of());
            return out;
        }

        try {
            List<Map<String, Object>> items = legacyDevelopmentCandidateRows(report, 200);
            out.put("status", items.isEmpty() ? "empty" : "ready");
            out.put("itemCount", items.size());
            out.put("activeCount", items.stream()
                    .filter(item -> {
                        String role = asString(item.get("sourceSetRole"));
                        return "active-root".equals(role) || "active-app".equals(role);
                    })
                    .count());
            out.put("byFeature", countByString(items, "featureBucket"));
            out.put("byAction", countByString(items, "candidateAction"));
            out.put("items", items.stream().limit(30).toList());
        } catch (Exception e) {
            traceSkipped("source_manifest_snapshot", e);
            out.put("status", "read_error");
            out.put("error", e.getClass().getSimpleName());
            out.put("itemCount", 0);
            out.put("byFeature", Map.of());
            out.put("byAction", Map.of());
            out.put("items", List.of());
        }
        return out;
    }

    private List<Map<String, Object>> legacyDevelopmentCandidateRows(Path report, int maxRows) throws java.io.IOException {
        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        if (lines.size() <= 1) {
            return List.of();
        }
        String[] headers = lines.get(0).split("\\t", -1);
        int limit = Math.max(1, Math.min(maxRows, 2_000));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i < lines.size() && out.size() < limit; i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] cols = line.split("\\t", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < headers.length && j < cols.length; j++) {
                row.put(headers[j], cols[j]);
            }
            out.add(legacyDevelopmentCandidateItem(row));
        }
        return out;
    }

    private Map<String, Object> legacyDevelopmentCandidateItem(Map<String, String> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        putScalarDiagnostics(out, "sourcePath", row.get("source_path"), 180);
        out.put("sourceSetRole", safeLabel(row.get("source_set_role"), 64));
        out.put("creationTime", safeScalar(row.get("creation_time"), 64));
        out.put("lastWriteTime", safeScalar(row.get("last_write_time"), 64));
        out.put("featureBucket", safeLabel(row.get("feature_bucket"), 80));
        out.put("developmentFunction", safeLabel(row.get("development_function"), 96));
        out.put("candidateAction", safeLabel(row.get("candidate_action"), 80));
        out.put("reason", safeLabel(row.get("reason"), 120));
        out.put("fqcn", safeScalar(row.get("fqcn"), 160));
        return out;
    }

    private Map<String, Object> opsLedgerDatasetPipelineQueue(boolean ledgerEnabled,
                                                              List<Map<String, Object>> recentAutolearn,
                                                              List<Map<String, Object>> recent) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bridge", "Dataset Pipeline");
        out.put("source", "ops-ledger");
        out.put("mode", "read_only_curation_candidates");
        out.put("writesDataset", false);
        out.put("requiresReview", true);
        if (!ledgerEnabled) {
            out.put("status", "ledger_disabled");
            out.put("items", List.of());
            out.put("byAction", Map.of());
            return out;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        addCurationItems(items, recentAutolearn, "autolearn");
        addCurationItems(items, recent, "rag");
        List<Map<String, Object>> limited = items.stream()
                .limit(20)
                .toList();
        out.put("status", limited.isEmpty() ? "empty" : "ready");
        out.put("itemCount", limited.size());
        out.put("byAction", countByString(limited, "action"));
        out.put("byHotspot", countByString(limited, "hotspot"));
        out.put("items", limited);
        return out;
    }

    private void addCurationItems(List<Map<String, Object>> out,
                                  List<Map<String, Object>> rows,
                                  String lane) {
        if (out == null || rows == null || rows.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            Map<String, Object> item = curationItem(row, lane);
            if (!item.isEmpty()) {
                out.add(item);
            }
            if (out.size() >= 40) {
                return;
            }
        }
    }

    private Map<String, Object> curationItem(Map<String, Object> row, String lane) {
        String entryType = safeLabel(asString(row.get("entryType")), 32);
        String decision = safeLabel(asString(row.get("decision")), 48);
        String failureClass = safeLabel(SafeRedactor.safeMessage(asString(row.get("failureClass")), 160), 80);
        String hotspot = safeLabel(asString(row.get("hotspot")), 80);
        Map<String, Object> quality = childMap(row.get("quality"));
        Map<String, Object> matrix = childMap(row.get("matrix"));
        Map<String, Object> gpuAdmission = childMap(row.get("gpuAdmission"));
        Map<String, Object> action = curationAction(decision, failureClass, hotspot, quality, matrix, gpuAdmission);
        String actionName = asString(action.get("action"));
        if (actionName == null || actionName.isBlank() || "ignore".equals(actionName)) {
            return Map.of();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lane", safeLabel(lane, 32));
        out.put("entryType", entryType);
        out.put("action", actionName);
        out.put("reason", safeLabel(SafeRedactor.traceLabelOrFallback(asString(action.get("reason")), "unknown"), 120));
        out.put("decision", decision);
        out.put("failureClass", failureClass);
        out.put("hotspot", hotspot);
        out.put("createdAt", safeScalar(row.get("createdAt"), 64));
        out.put("planId", safeScalar(row.get("planId"), 96));
        out.put("strategyName", safeScalar(row.get("strategyName"), 96));
        out.put("resourceTier", safeScalar(row.get("resourceTier"), 48));
        out.put("datasetWriteAllowed", false);
        out.put("reviewRequired", true);
        Map<String, Object> gpu = compactLedgerGpuAdmission(gpuAdmission);
        if (!gpu.isEmpty()) {
            out.put("gpuAdmission", gpu);
        }
        Map<String, Object> counts = childMap(row.get("sourceCounts"));
        if (!counts.isEmpty()) {
            Map<String, Object> compactCounts = new LinkedHashMap<>();
            copyNumeric(compactCounts, "attempted", counts);
            copyNumeric(compactCounts, "accepted", counts);
            copyNumeric(compactCounts, "resultCount", counts);
            copyNumeric(compactCounts, "webAfterFilter", counts);
            copyNumeric(compactCounts, "webReturned", counts);
            if (!compactCounts.isEmpty()) {
                out.put("counts", compactCounts);
            }
        }
        return out;
    }

    private Map<String, Object> curationAction(String decision,
                                               String failureClass,
                                               String hotspot,
                                               Map<String, Object> quality,
                                               Map<String, Object> matrix,
                                               Map<String, Object> gpuAdmission) {
        String trainDecision = safeLabel(asString(quality.get("trainDecision")), 64);
        String vectorDecision = safeLabel(firstNonBlank(asString(quality.get("vectorDecision")),
                asString(matrix.get("blackbox.risk.vectorDecision"))), 64);
        double gpuHardwarePressure = number(firstPresent(matrix.get("q_gpu_hardware_pressure"),
                matrix.get("uaw.gpu-hardware.admission.pressure")), 0.0d);
        double gpuGatewayPressure = number(matrix.get("q_gpu_gateway_pressure"), 0.0d);
        if (gpuHardwarePressure >= 0.50d || failureClass.contains("gpu_hardware")) {
            return Map.of("action", "demote_heavy_work", "reason", "gpu_hardware_pressure");
        }
        if (gpuGatewayPressure >= 0.50d || failureClass.contains("gpu_gateway")) {
            return Map.of("action", "observe_gateway_failure", "reason", "desktop_gpu_gateway_unreachable");
        }
        if ("quarantine".equals(vectorDecision)
                || failureClass.contains("contamination")
                || hotspot.contains("memory_history")) {
            return Map.of("action", "quarantine_candidate", "reason", "learning_contamination_risk");
        }
        if (decision.contains("block") || trainDecision.contains("block")) {
            return Map.of("action", "evaluation_candidate", "reason", "blocked_training_decision");
        }
        if (decision.contains("train") || trainDecision.contains("train") || decision.contains("allow")) {
            return Map.of("action", "curation_candidate", "reason", "accepted_autolearn_signal");
        }
        if (failureClass.isBlank() || "none".equals(failureClass) || "unknown".equals(failureClass)) {
            return Map.of("action", "observe_only", "reason", "no_failure_pattern");
        }
        return Map.of("action", "evaluation_candidate", "reason", failureClass);
    }

    private Map<String, Object> compactLedgerGpuAdmission(Map<String, Object> gpuAdmission) {
        if (gpuAdmission == null || gpuAdmission.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> hardware = childMap(gpuAdmission.get("hardware"));
        Map<String, Object> gateway = childMap(gpuAdmission.get("gateway"));
        copyScalar(out, "hardwareStatus", hardware, "admissionStatus", 48);
        copyScalar(out, "hardwareReason", hardware, "reason", 96);
        copyScalar(out, "pressureLevel", hardware, "pressureLevel", 48);
        copyNumeric(out, "hardwarePressure", hardware, "pressure");
        copyBoolean(out, "retrainAllowed", hardware);
        copyBoolean(out, "rerankAllowed", hardware);
        copyBoolean(out, "embeddingFallbackAllowed", hardware);
        copyScalar(out, "gatewayStatus", gateway, "status", 48);
        copyNumeric(out, "gatewayPressure", gateway, "pressure");
        return out;
    }

    private List<Map<String, Object>> modelCards(Map<String, ModelAgg> datasetModels) {
        Map<String, ModelAgg> byModel = new LinkedHashMap<>(datasetModels);
        ModelRuntimeHealthTracker tracker = modelHealthProvider == null ? null : modelHealthProvider.getIfAvailable();
        if (tracker != null) {
            for (Map<String, Object> runtime : tracker.redactedSnapshots()) {
                String model = firstNonBlank(asString(runtime.get("model")), "unknown");
                ModelAgg agg = byModel.computeIfAbsent(model, ModelAgg::new);
                agg.addRuntime(runtime);
            }
        }
        return byModel.values().stream()
                .sorted(Comparator.comparingLong(ModelAgg::total).reversed().thenComparing(ModelAgg::model))
                .limit(20)
                .map(ModelAgg::toMap)
                .toList();
    }

    private MetricsSnapshot metrics(DatasetSnapshot dataset,
                                    FailureSnapshot failures,
                                    Map<String, Object> matrix9,
                                    Map<String, Object> idleTrain,
                                    List<Map<String, Object>> modelCards,
                                    Map<String, Object> quality) {
        Map<String, Object> datasetSummary = dataset.summary();
        @SuppressWarnings("unchecked")
        Map<String, Object> virtualPoint = (Map<String, Object>) matrix9.getOrDefault("virtualPoint", Map.of());
        Map<String, Object> qualitySnapshot = childMap(quality.get("snapshot"));
        return new MetricsSnapshot(
                Math.max(0L, longValue(datasetSummary.get("parsedLines"))),
                Math.max(0L, longValue(datasetSummary.get("quarantineCount"))),
                Math.max(0L, longValue(failures.summary().get("eventCount"))),
                Math.max(0L, longValue(virtualPoint.get("size"))),
                Math.max(0L, longValue(idleTrain.get("ingestedCount"))),
                modelCards == null ? 0L : modelCards.size(),
                number(qualitySnapshot.get("contextContaminationEwma"), 0.0d),
                number(datasetSummary.get("evidenceRate"), 0.0d));
    }

    private List<Map<String, Object>> passages(Map<String, Object> row) {
        Object value = firstPresent(row.get("passages"), row.get("evidence"), row.get("evidences"), row.get("contexts"));
        if (value == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            int rank = 0;
            for (Object item : collection) {
                if (out.size() >= 8) {
                    break;
                }
                out.add(passage(++rank, item));
            }
        } else {
            out.add(passage(1, value));
        }
        return List.copyOf(out);
    }

    private Map<String, Object> passage(int rank, Object item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rank", rank);
        if (item instanceof Map<?, ?> map) {
            out.put("title", safeScalar(firstPresent(map.get("title"), map.get("name")), 120));
            out.put("source", safeScalar(firstPresent(map.get("source"), map.get("url"), map.get("uri")), 180));
            out.put("score", nullableNumber(map.get("score"), map.get("rankScore"), map.get("confidence")));
            out.put("preview", preview(firstPresent(map.get("text"), map.get("content"), map.get("snippet"), map.get("passage")), 220));
        } else {
            out.put("title", "");
            out.put("source", "");
            out.put("score", null);
            out.put("preview", preview(item, 220));
        }
        return out;
    }

    private JsonlTail jsonlTail(Path path, int limit) {
        if (path == null) {
            return new JsonlTail(false, false, 0L, List.of(), 0);
        }
        try {
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return new JsonlTail(false, false, 0L, List.of(), 0);
            }
            long size = Files.size(path);
            long start = Math.max(0L, size - MAX_TAIL_BYTES);
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path)) {
                if (start > 0L) {
                    input.skipNBytes(start);
                }
                bytes = input.readAllBytes();
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (start > 0L) {
                int firstNewline = text.indexOf('\n');
                text = firstNewline >= 0 && firstNewline + 1 < text.length()
                        ? text.substring(firstNewline + 1)
                        : "";
            }
            String[] split = text.split("\\R");
            ArrayDeque<String> tail = new ArrayDeque<>();
            int parseErrors = 0;
            for (String raw : split) {
                String line = raw == null ? "" : raw.trim();
                if (line.isBlank()) {
                    continue;
                }
                if (!line.startsWith("{")) {
                    parseErrors++;
                    continue;
                }
                tail.addLast(line);
                while (tail.size() > clampLimit(limit)) {
                    tail.removeFirst();
                }
            }
            return new JsonlTail(true, start > 0L, size, List.copyOf(tail), parseErrors);
        } catch (Exception e) {
            traceSkipped("jsonl_tail", e);
            return new JsonlTail(false, false, 0L, List.of(), 1);
        }
    }

    private Map<String, Object> parseJson(String line) {
        try {
            return objectMapper.readValue(line, new TypeReference<>() {
            });
        } catch (Exception e) {
            traceSkipped("json_parse", e);
            return Map.of();
        }
    }

    private Path datasetPath() {
        return path(firstNonBlank(
                prop("uaw.autolearn.dataset.path", ""),
                prop("autolearn.dataset.path", ""),
                prop("dataset.train-file-path", ""),
                autolearnProperties.getDataset() == null ? "" : autolearnProperties.getDataset().getPath(),
                "data/train_rag.jsonl"));
    }

    private Path failurePatternPath() {
        return path(firstNonBlank(prop("nova.orch.failure.jsonl.path", ""), "logs/failure-pattern.jsonl"));
    }

    private Path contextPurityReportPath(String filename) {
        String reportDir = firstNonBlank(prop("awx.context-purity.report-dir", ""), "__reports__");
        Path base = path(reportDir);
        if (base == null || filename == null || filename.isBlank()) {
            return null;
        }
        return base.resolve(filename);
    }

    private Path path(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            return null;
        }
        return Path.of(value);
    }

    private String prop(String key, String fallback) {
        return env == null ? fallback : env.getProperty(key, fallback);
    }

    private boolean boolProp(String key, boolean fallback) {
        String value = prop(key, null);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private Cached cachedSnapshot() {
        long now = System.currentTimeMillis();
        Cached current = cached;
        if (current != null && now - current.loadedAtMs() <= CACHE_TTL_MS) {
            return current;
        }
        synchronized (this) {
            current = cached;
            if (current != null && now - current.loadedAtMs() <= CACHE_TTL_MS) {
                return current;
            }
            Snapshot snapshot = buildSnapshot(DEFAULT_LIMIT);
            Cached next = new Cached(copyMap(snapshot.overview()), snapshot.metrics(), now);
            cached = next;
            return next;
        }
    }

    private void registerMeters(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        Gauge.builder("rag.learning.samples.total", this, s -> s.cachedSnapshot().metrics().sampleTotal())
                .description("Parsed train_rag JSONL samples in the bounded learning ops tail")
                .register(registry);
        Gauge.builder("rag.learning.samples.quarantined", this, s -> s.cachedSnapshot().metrics().sampleQuarantined())
                .description("Samples classified as quarantine or review blocked")
                .register(registry);
        Gauge.builder("rag.learning.failure_patterns.total", this, s -> s.cachedSnapshot().metrics().failurePatternTotal())
                .description("Failure pattern events in the bounded dashboard tail")
                .register(registry);
        Gauge.builder("rag.learning.virtual_points", this, s -> s.cachedSnapshot().metrics().virtualPointCount())
                .description("In-memory Virtual Point count")
                .register(registry);
        Gauge.builder("rag.learning.evidence_rate", this, s -> s.cachedSnapshot().metrics().evidenceRate())
                .description("Evidence-bearing sample ratio in the bounded dashboard tail")
                .register(registry);
    }

    private static Map<String, Object> links() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("samples", "/api/diagnostics/rag/learning-ops/samples");
        out.put("failures", "/api/diagnostics/rag/learning-ops/failures");
        out.put("metricsJson", "/api/diagnostics/rag/learning-ops/metrics");
        out.put("metricsPrometheus", "/api/diagnostics/rag/learning-ops/metrics/prometheus");
        out.put("autolearnLoop", "/api/diagnostics/uaw/autolearn/loop");
        out.put("opsLedger", "/api/diagnostics/rag/ops-ledger/summary");
        out.put("vectorDlq", "/api/admin/vector/dlq");
        out.put("orchestrationOverlays", "/api/diagnostics/rag/learning-ops/overview");
        out.put("legacyDevelopmentCandidates", "/api/diagnostics/rag/learning-ops/overview");
        return out;
    }

    private static void appendGauge(StringBuilder out, String name, String help, double value) {
        appendHeader(out, name, help, "gauge");
        out.append(name).append(' ').append(finite(value)).append('\n');
    }

    private static void appendHeader(StringBuilder out, String name, String help, String type) {
        out.append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void appendModelSample(StringBuilder out, String name, String model, String source, double value) {
        out.append(name)
                .append("{model=\"").append(escapeLabel(model)).append("\",source=\"").append(escapeLabel(source)).append("\"} ")
                .append(finite(value)).append('\n');
    }

    private static String escapeHelp(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static String escapeLabel(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String safeMetricLabel(String value) {
        String safe = firstNonBlank(value, "unknown").trim();
        safe = safe.replaceAll("[^A-Za-z0-9_.:/-]", "_");
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0d;
    }

    private static String decision(Map<String, Object> row,
                                   Map<String, Object> validation,
                                   boolean finalGate,
                                   List<String> rejectReasons,
                                   String vectorDecision) {
        String explicit = firstNonBlank(asString(row.get("validationDecision")), asString(validation.get("decision")));
        if (explicit != null && !explicit.isBlank()) {
            return safeLabel(explicit, 32);
        }
        if (!finalGate || !rejectReasons.isEmpty() || "QUARANTINE".equalsIgnoreCase(vectorDecision)) {
            return "rejected";
        }
        Object accepted = validation.get("accepted");
        if (accepted instanceof Boolean b) {
            return b ? "accepted" : "rejected";
        }
        return "accepted";
    }

    private static Map<String, Object> traceKeys(String prefix, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            String traceKey = prefix + key;
            Object value = TraceStore.get(traceKey);
            if (value != null) {
                out.put(key, SafeRedactor.diagnosticValue(traceKey, value));
            }
        }
        return out;
    }

    private static Map<String, Object> traceOverlayKeys(String prefix, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            String traceKey = prefix + key;
            Object value = TraceStore.get(traceKey);
            if (value != null) {
                out.put(key, overlayDiagnosticValue(value));
            }
        }
        return out;
    }

    private static String traceOverlayScalar(String key) {
        Object value = TraceStore.get(key);
        Object diagnostic = overlayDiagnosticValue(value);
        return diagnostic == null ? "" : safeScalar(diagnostic, 160);
    }

    private static Object overlayDiagnosticValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizedObjectMap(map);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            int count = 0;
            for (Object item : collection) {
                if (count++ >= 20) {
                    break;
                }
                if (item instanceof Number || item instanceof Boolean) {
                    out.add(item);
                } else if (item instanceof Map<?, ?> map) {
                    out.add(sanitizedObjectMap(map));
                } else {
                    out.add(safeScalar(item, 120));
                }
            }
            return List.copyOf(out);
        }
        return safeScalar(value, 160);
    }

    private static Map<String, Object> traceMap(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof Map<?, ?> map) {
            return sanitizedObjectMap(map);
        }
        return Map.of();
    }

    private static String safeTrace(String key) {
        Object value = TraceStore.get(key);
        return value == null ? "" : safeLabel(String.valueOf(SafeRedactor.diagnosticValue(key, value)), 96);
    }

    private static long traceLong(String key) {
        Object value = TraceStore.get(key);
        return longValue(value);
    }

    private static boolean traceBool(String key) {
        return truthy(TraceStore.get(key));
    }

    @SafeVarargs
    private static int overlayActiveCount(Map<String, Object>... overlays) {
        int count = 0;
        if (overlays == null) {
            return count;
        }
        for (Map<String, Object> overlay : overlays) {
            if (overlay != null && truthy(overlay.get("active"))) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null
                && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> sanitizedObjectMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            out.put(safeKey(key), SafeRedactor.diagnosticValue(key, entry.getValue(), 360));
        }
        return out;
    }

    private static Map<String, Object> sanitizedMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                out.put(safeKey(entry.getKey()), value);
            } else {
                out.put(safeKey(entry.getKey()), safeScalar(value, 80));
            }
        }
        return out;
    }

    private static Map<String, Long> countByString(List<Map<String, Object>> rows, String key) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String value = safeLabel(asString(row == null ? null : row.get(key)), 80);
            if (value.isBlank()) {
                value = "unknown";
            }
            out.put(value, out.getOrDefault(value, 0L) + 1L);
        }
        return out;
    }

    private static void copyScalar(Map<String, Object> out,
                                   String targetKey,
                                   Map<String, Object> source,
                                   String sourceKey,
                                   int max) {
        if (out == null || source == null || targetKey == null || sourceKey == null || !source.containsKey(sourceKey)) {
            return;
        }
        String value = safeScalar(source.get(sourceKey), max);
        if (!value.isBlank()) {
            out.put(targetKey, value);
        }
    }

    private static void copyFileNameDiagnostics(Map<String, Object> out,
                                                Map<String, Object> source,
                                                String rawKey,
                                                String hashKey,
                                                String lengthKey) {
        if (out == null || source == null || rawKey == null || hashKey == null || lengthKey == null) {
            return;
        }
        String rawName = safeScalar(source.get(rawKey), 160);
        if (!rawName.isBlank()) {
            putHashLength(out, hashKey, lengthKey, rawName);
            return;
        }
        copyScalar(out, hashKey, source, hashKey, 80);
        copyNumeric(out, lengthKey, source, lengthKey);
    }

    private static void putFileNameDiagnostics(Map<String, Object> out, String prefix, String fileName) {
        putScalarDiagnostics(out, prefix, fileName, 160);
    }

    private static void putScalarDiagnostics(Map<String, Object> out, String prefix, Object value, int max) {
        if (out == null || prefix == null) {
            return;
        }
        String safe = safeScalar(value, max);
        putHashLength(out, prefix + "Hash", prefix + "Length", safe);
    }

    private static void putHashLength(Map<String, Object> out, String hashKey, String lengthKey, String value) {
        if (out == null || hashKey == null || lengthKey == null) {
            return;
        }
        String safe = value == null ? "" : value;
        out.put(hashKey, safe.isBlank() ? "" : hashText(safe));
        out.put(lengthKey, safe.length());
    }

    private static void copyNumeric(Map<String, Object> out, String key, Map<String, Object> source) {
        copyNumeric(out, key, source, key);
    }

    private static void copyNumeric(Map<String, Object> out,
                                    String targetKey,
                                    Map<String, Object> source,
                                    String sourceKey) {
        if (out == null || source == null || targetKey == null || sourceKey == null || !source.containsKey(sourceKey)) {
            return;
        }
        Double parsed = tryNumber(source.get(sourceKey));
        if (parsed != null) {
            out.put(targetKey, round4(parsed));
        }
    }

    private static void copyBoolean(Map<String, Object> out, String key, Map<String, Object> source) {
        if (out == null || source == null || key == null || !source.containsKey(key)) {
            return;
        }
        Object value = source.get(key);
        if (value instanceof Boolean b) {
            out.put(key, b);
        } else if (value instanceof CharSequence seq) {
            String s = seq.toString().trim();
            if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
                out.put(key, Boolean.parseBoolean(s));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> childMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String s = safeLabel(asString(item), 96);
                if (!s.isBlank() && !"none".equals(s)) {
                    out.add(s);
                }
            }
        } else {
            String raw = asString(value);
            for (String part : raw.split(",")) {
                String s = safeLabel(part, 96);
                if (!s.isBlank() && !"none".equals(s)) {
                    out.add(s);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String preview(Object value, int max) {
        String raw = asString(value);
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String masked = SafeRedactor.redact(raw);
        if (masked == null) {
            return "";
        }
        String oneLine = masked.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        int safeMax = Math.max(24, max);
        return oneLine.length() <= safeMax ? oneLine : oneLine.substring(0, safeMax) + "...";
    }

    private static String safeScalar(Object value, int max) {
        if (value == null) {
            return "";
        }
        String masked = SafeRedactor.redact(String.valueOf(value));
        if (masked == null) {
            return "";
        }
        masked = masked.replace('\n', ' ').replace('\r', ' ').trim();
        int safeMax = Math.max(8, max);
        return masked.length() <= safeMax ? masked : masked.substring(0, safeMax);
    }

    private static String safeLabel(String value, int max) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            return "";
        }
        s = s.replaceAll("[^a-z0-9_.:-]+", "_");
        int safeMax = Math.max(8, max);
        return s.length() <= safeMax ? s : s.substring(0, safeMax);
    }

    private static String safeKey(String key) {
        String label = SafeRedactor.traceLabelOrFallback(key, "unknown");
        String value = label.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return value.length() <= 96 ? value : value.substring(0, 96);
    }

    private static Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static double firstNumber(Object... values) {
        if (values == null) {
            return 0.0d;
        }
        for (Object value : values) {
            Double parsed = tryNumber(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return 0.0d;
    }

    private static Double nullableNumber(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            Double parsed = tryNumber(value);
            if (parsed != null) {
                return round4(parsed);
            }
        }
        return null;
    }

    private static Double tryNumber(Object value) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (!Double.isFinite(parsed)) {
                traceSkipped("number_parse", new NumberFormatException("non-finite"));
                return null;
            }
            return parsed;
        }
        if (value == null) {
            return null;
        }
        try {
            String s = String.valueOf(value).trim();
            if (s.isBlank()) {
                return null;
            }
            double parsed = Double.parseDouble(s);
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException("non-finite");
            }
            return parsed;
        } catch (NumberFormatException ignore) {
            traceSkipped("number_parse", ignore);
            return null;
        }
    }

    private static void traceSkipped(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null ? "unknown" : failure.getClass().getSimpleName();
        String safeErrorType = "number_parse".equals(safeStage)
                ? "invalid_number"
                : SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        TraceStore.put("learning.ops.dashboard.suppressed." + safeStage, true);
        TraceStore.put("learning.ops.dashboard.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static double number(Object value, double fallback) {
        Double parsed = tryNumber(value);
        return parsed == null ? fallback : parsed;
    }

    private static long longValue(Object value) {
        Double parsed = tryNumber(value);
        return parsed == null ? 0L : Math.round(parsed);
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0d;
        }
        String s = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0.0d;
        }
        return round4(numerator / (double) denominator);
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int clampLimit(int limit) {
        return Math.min(Math.max(1, limit), MAX_LIMIT);
    }

    private static String fileName(Path path) {
        return path == null || path.getFileName() == null ? "" : safeScalar(path.getFileName().toString(), 160);
    }

    private static String fileNameFromPath(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (p.isEmpty()) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        return safeScalar(idx >= 0 && idx + 1 < p.length() ? p.substring(idx + 1) : p, 160);
    }

    private static String hashPath(Path path) {
        return path == null ? "" : Objects.toString(SafeRedactor.hashValue(path.toString()), "");
    }

    private static String hashText(String text) {
        return Objects.toString(SafeRedactor.hashValue(text), "");
    }

    private static String diagnosticDatasetId(String id) {
        String value = id == null ? "" : id.trim();
        if (value.startsWith("lms:")) {
            String[] parts = value.split(":", 3);
            String kind = parts.length >= 2 && !parts[1].isBlank() ? parts[1] : "private";
            return "lms:" + kind + ":hash:" + SafeRedactor.hashValue(value);
        }
        return safeScalar(value, 160);
    }

    private static String diagnosticLegacyPath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.startsWith("legacy-quarantine/db/lms/")) {
            String prefix = "legacy-quarantine/db/lms/";
            String rest = value.substring(prefix.length());
            int slash = rest.indexOf('/');
            String kind = slash > 0 ? rest.substring(0, slash) : "legacy";
            return prefix + kind + "/hash:" + SafeRedactor.hashValue(value);
        }
        return safeScalar(value, 180);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        return new LinkedHashMap<>(source == null ? Map.of() : source);
    }

    private static void increment(Map<String, Integer> target, String key) {
        String k = key == null ? "" : key.trim();
        if (k.isBlank()) {
            return;
        }
        target.merge(safeScalar(k, 120), 1, Integer::sum);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value, int maxLen) {
        String v = safeScalar(value, maxLen);
        if (!v.isBlank()) {
            target.put(key, v);
        }
    }

    private record JsonlTail(boolean exists, boolean truncated, long sizeBytes, List<String> lines, int parseErrors) {
    }

    private record DatasetSnapshot(Map<String, Object> summary,
                                   List<Map<String, Object>> samples,
                                   Map<String, ModelAgg> modelAggs) {
    }

    private record FailureSnapshot(Map<String, Object> summary,
                                   List<Map<String, Object>> topCauses,
                                   List<Map<String, Object>> recent) {
    }

    private record Snapshot(Map<String, Object> overview,
                            Map<String, Object> datasetSummary,
                            List<Map<String, Object>> samples,
                            Map<String, Object> failureSummary,
                            List<Map<String, Object>> failureTopCauses,
                            List<Map<String, Object>> failureRecent,
                            MetricsSnapshot metrics) {
    }

    private record Cached(Map<String, Object> overview, MetricsSnapshot metrics, long loadedAtMs) {
    }

    private record MetricsSnapshot(long sampleTotal,
                                   long sampleQuarantined,
                                   long failurePatternTotal,
                                   long virtualPointCount,
                                   long idleRetrainIngested,
                                   long modelCount,
                                   double contextContaminationScore,
                                   double evidenceRate) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sampleTotal", sampleTotal);
            out.put("sampleQuarantined", sampleQuarantined);
            out.put("failurePatternTotal", failurePatternTotal);
            out.put("virtualPointCount", virtualPointCount);
            out.put("idleRetrainIngested", idleRetrainIngested);
            out.put("modelCount", modelCount);
            out.put("contextContaminationScore", contextContaminationScore);
            out.put("evidenceRate", evidenceRate);
            out.put("prometheusPath", "/api/diagnostics/rag/learning-ops/metrics/prometheus");
            out.put("micrometerMeters", List.of(
                    "rag.learning.samples.total",
                    "rag.learning.samples.quarantined",
                    "rag.learning.failure_patterns.total",
                    "rag.learning.virtual_points",
                    "rag.learning.evidence_rate"));
            return out;
        }
    }

    private static final class ModelAgg {
        private final String model;
        private long success;
        private long failure;
        private long evidenceBearing;
        private long datasetRows;
        private long latencyCount;
        private double latencyTotal;
        private double qualityTotal;
        private long qualityCount;
        private String provider = "";
        private boolean runtimeHealth;

        private ModelAgg(String model) {
            this.model = firstNonBlank(model, "unknown");
        }

        private String model() {
            return model;
        }

        private long total() {
            return Math.max(0L, success + failure);
        }

        private void addSample(Map<String, Object> sample, boolean accepted) {
            datasetRows++;
            if (accepted) {
                success++;
            } else {
                failure++;
            }
            if (number(sample.get("evidenceCount"), 0.0d) > 0.0d) {
                evidenceBearing++;
            }
            Double latency = tryNumber(sample.get("latencyMs"));
            if (latency != null && latency >= 0.0d) {
                latencyTotal += latency;
                latencyCount++;
            }
            Double quality = tryNumber(sample.get("qualityScore"));
            if (quality != null) {
                qualityTotal += quality;
                qualityCount++;
            }
            provider = firstNonBlank(provider, asString(sample.get("provider")));
        }

        private void addRuntime(Map<String, Object> runtime) {
            runtimeHealth = true;
            success += Math.max(0L, longValue(runtime.get("successCount")));
            failure += Math.max(0L, longValue(runtime.get("failureCount")));
            provider = firstNonBlank(provider, asString(runtime.get("provider")));
        }

        private Map<String, Object> toMap() {
            long total = total();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("model", model);
            out.put("provider", provider);
            out.put("successCount", success);
            out.put("failureCount", failure);
            out.put("totalCount", total);
            out.put("successRate", ratio(success, Math.max(1L, total)));
            out.put("failureRate", ratio(failure, Math.max(1L, total)));
            out.put("avgLatencyMs", latencyCount == 0L ? null : round4(latencyTotal / latencyCount));
            out.put("evidenceRate", datasetRows == 0L ? 0.0d : ratio(evidenceBearing, datasetRows));
            out.put("qualityScoreAvg", qualityCount == 0L ? null : round4(qualityTotal / qualityCount));
            out.put("source", runtimeHealth && datasetRows > 0L ? "dataset+runtime" : runtimeHealth ? "runtime" : "dataset");
            return out;
        }
    }

    private static final class CauseAgg {
        private final String kind;
        private final String key;
        private final String source;
        private int count;
        private long lastTs;

        private CauseAgg(String kind, String key, String source) {
            this.kind = kind;
            this.key = key;
            this.source = source;
        }

        private int count() {
            return count;
        }

        private void add(Map<String, Object> row) {
            count++;
            lastTs = Math.max(lastTs, longValue(row.get("tsEpochMillis")));
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("key", key);
            out.put("source", source);
            out.put("count", count);
            out.put("lastTsEpochMillis", lastTs);
            return out;
        }
    }
}
