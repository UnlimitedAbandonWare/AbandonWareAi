package com.example.lms.moe;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Produces a minimal RGB soak report and writes it to disk.
 */
@Service
public class RgbSoakReportService {

    private static final Logger log = LoggerFactory.getLogger(RgbSoakReportService.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final UnifiedRagOrchestrator orchestrator;
    private final ObjectMapper om;
    private final RgbMoeProperties props;

    public RgbSoakReportService(UnifiedRagOrchestrator orchestrator,
                               ObjectMapper om,
                               RgbMoeProperties props) {
        this.orchestrator = orchestrator;
        this.om = om;
        this.props = props;
    }

    public RgbSoakReport run(String sessionId,
                             List<String> queries,
                             RgbStrategySelector.Decision decision,
                             int blueCalls,
                             boolean writeFile) {
        return run(sessionId, queries, decision, blueCalls, writeFile, null);
    }

    /**
     * Same as {@link #run(String, List, RgbStrategySelector.Decision, int, boolean)} but allows
     * additional debug payload to be embedded into the report.
     */
    public RgbSoakReport run(String sessionId,
                             List<String> queries,
                             RgbStrategySelector.Decision decision,
                             int blueCalls,
                             boolean writeFile,
                             Map<String, Object> extraDebug) {

        Instant started = Instant.now();

        Map<String, RgbSoakMetrics> metricsByStrategy = new LinkedHashMap<>();
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("blueCalls", blueCalls);

        if (decision != null) {
            debug.put("reasons", decision.reasons());
            if (decision.scoreCard() != null) {
                debug.put("scoreCard", decision.scoreCard());
            }
        }

        if (extraDebug != null && !extraDebug.isEmpty()) {
            debug.putAll(extraDebug);
        }
        debug.put("normalizedMetricSchema", NormalizedRagMetrics.SCHEMA_VERSION);
        debug.put("openclawEvaluator.mode", "offline");
        debug.put("kgVariantEnabled", props != null && props.isKgVariantEnabled());

        // Evaluate primary + up to 2 fallbacks (avoid long runtimes)
        int evaluated = 0;
        if (decision != null) {
            evaluated += evalStrategyWithOptionalKgVariant(metricsByStrategy, queries, decision.primaryStrategy());
            for (RgbStrategySelector.Strategy fb : decision.fallbackStrategies()) {
                if (evaluated >= 3) break;
                evaluated += evalStrategyWithOptionalKgVariant(metricsByStrategy, queries, fb);
            }
        }

        addNeutralDebug(debug, metricsByStrategy);

        Instant ended = Instant.now();

        RgbSoakReport report = new RgbSoakReport(
                sessionId,
                started,
                ended,
                decision == null ? null : String.valueOf(decision.primaryStrategy()),
                decision == null ? List.of() : decision.fallbackStrategies().stream().map(String::valueOf).toList(),
                decision == null ? List.of() : decision.reasons(),
                queries,
                metricsByStrategy,
                debug
        );

        if (writeFile) {
            Path out = writeReportFile(report);
            if (out != null) {
                putReportFileDiagnostics(debug, out);
            }
        }

        log.info("[RGB] soak report saved: strategies={} queries={} blueCalls={}",
                metricsByStrategy.keySet(),
                queries == null ? 0 : queries.size(),
                blueCalls);

        return report;
    }

    private int evalStrategyWithOptionalKgVariant(Map<String, RgbSoakMetrics> out,
                                                  List<String> queries,
                                                  RgbStrategySelector.Strategy strategy) {
        int evaluated = evalStrategy(out, queries, strategy, false, strategy == null ? "" : strategy.name());
        if (evaluated > 0 && props != null && props.isKgVariantEnabled()) {
            evalStrategy(out, queries, strategy, true, strategy.name() + "_KG");
        }
        return evaluated;
    }

    private int evalStrategy(Map<String, RgbSoakMetrics> out,
                             List<String> queries,
                             RgbStrategySelector.Strategy strategy,
                             boolean useKg,
                             String strategyKey) {
        if (strategy == null || out == null || queries == null || queries.isEmpty()) return 0;

        boolean useWeb = true;
        boolean useVector = true;
        boolean enableOnnx = true;
        boolean enableBi = true;

        // conservative deltas (only offline soak):
        switch (strategy) {
            case G_ONLY -> {
                // lighter: reduce heavy reranks
                enableOnnx = false;
                enableBi = false;
            }
            case B_ONLY -> {
                // BLUE isn't used for search itself; keep defaults.
            }
            case RG_ENSEMBLE, GB_FALLBACK, RB_ENSEMBLE, RGB_ENSEMBLE, R_ONLY -> {
                // default
            }
        }

        long totalLatencyMs = 0L;
        int calls = 0;
        int hits = 0;
        int docCount = 0;
        int evidenceCount = 0;
        int fallbackCount = 0;
        int relationThumbnailCandidateCount = 0;
        int relationThumbnailSelectedCount = 0;
        int relationThumbnailDroppedCount = 0;
        int relationThumbnailAnchorSeedUsedCount = 0;
        int relationThumbnailSignalAppliedCount = 0;
        int relationThumbnailSignalSlicedCount = 0;
        int relationThumbnailSignalAnchorSeedCount = 0;
        int relationThumbnailSignalNoSelectionCount = 0;
        int relationThumbnailSliceMapCount = 0;
        java.util.LinkedHashSet<String> relationThumbnailRelationKinds = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> relationThumbnailContextLayers = new java.util.LinkedHashSet<>();
        Map<String, Integer> relationThumbnailContextLayerCounts = new LinkedHashMap<>();
        String relationThumbnailSelectionReason = "";
        int uawRelationThumbnailInputAnchorCount = 0;
        int uawRelationThumbnailSelectedAnchorCount = 0;
        int uawRelationThumbnailAnchorBudget = 0;
        int uawRelationThumbnailPairBudget = 0;
        int uawRelationThumbnailEmittedPairCount = 0;
        boolean uawRelationThumbnailSliced = false;
        Map<String, Integer> sourceCounts = new LinkedHashMap<>();

        for (String q : queries) {
            if (q == null || q.isBlank()) continue;
            UnifiedRagOrchestrator.QueryRequest req = new UnifiedRagOrchestrator.QueryRequest();
            req.query = q;
            req.topK = 8;
            req.useWeb = useWeb;
            req.useVector = useVector;
            req.useKg = useKg;
            req.useBm25 = false;
            req.enableOnnx = enableOnnx;
            req.enableBiEncoder = enableBi;
            req.enableDiversity = true;
            req.aggressive = false;
            req.planId = "rgb.soak." + strategy.name() + (useKg ? ".kg" : "");

            long t0 = System.nanoTime();
            UnifiedRagOrchestrator.QueryResponse r = orchestrator.query(req);
            long dtMs = (System.nanoTime() - t0) / 1_000_000L;
            totalLatencyMs += dtMs;
            calls++;

            if (r != null && r.results != null && !r.results.isEmpty()) {
                hits++;
                for (UnifiedRagOrchestrator.Doc d : r.results) {
                    if (d == null) continue;
                    docCount++;
                    String source = normalizeSource(d.source);
                    sourceCounts.merge(source, 1, Integer::sum);
                    boolean hasSnippet = d.snippet != null && !d.snippet.isBlank();
                    boolean hasUrl = d.meta != null && d.meta.get("url") != null && !String.valueOf(d.meta.get("url")).isBlank();
                    if (hasSnippet || hasUrl) evidenceCount++;
                }
            }

            if (r != null && r.debug != null && r.debug.containsKey("fallback")) {
                fallbackCount++;
            }
            Map<String, Object> kgAxis = relationThumbnailKgAxis(r);
            if (!kgAxis.isEmpty()) {
                relationThumbnailCandidateCount += intMetric(kgAxis, "relationThumbnailCandidateCount");
                relationThumbnailSelectedCount += intMetric(kgAxis, "relationThumbnailSelectedCount");
                relationThumbnailDroppedCount += intMetric(kgAxis, "relationThumbnailDroppedCount");
                if (booleanMetric(kgAxis, "relationThumbnailAnchorSeedUsed")) {
                    relationThumbnailAnchorSeedUsedCount++;
                }
                if (hasSignal(kgAxis, "kg_relation_thumbnail_applied")) {
                    relationThumbnailSignalAppliedCount++;
                }
                if (hasSignal(kgAxis, "kg_relation_thumbnail_sliced")) {
                    relationThumbnailSignalSlicedCount++;
                }
                if (hasSignal(kgAxis, "kg_relation_thumbnail_anchor_seed")) {
                    relationThumbnailSignalAnchorSeedCount++;
                }
                if (hasSignal(kgAxis, "kg_relation_thumbnail_no_selection")) {
                    relationThumbnailSignalNoSelectionCount++;
                }
                String reason = safeMetricLabel(kgAxis.get("relationThumbnailSelectionReason"));
                if (!reason.isBlank()) {
                    relationThumbnailSelectionReason = reason;
                }
            }
            List<Map<String, Object>> sliceMap = relationThumbnailSliceMap(r);
            relationThumbnailSliceMapCount += sliceMap.size();
            for (Map<String, Object> row : sliceMap) {
                String kind = safeMetricLabel(row.get("relationKind"));
                if (!kind.isBlank()) {
                    relationThumbnailRelationKinds.add(kind);
                }
                String layer = safeMetricLabel(row.get("contextLayer"));
                if (!layer.isBlank()) {
                    relationThumbnailContextLayers.add(layer);
                    relationThumbnailContextLayerCounts.merge(layer, 1, Integer::sum);
                }
            }
            if (useKg) {
                String uawPrefix = "uaw.thumbnail.relationThumbnail.";
                uawRelationThumbnailInputAnchorCount = Math.max(uawRelationThumbnailInputAnchorCount,
                        traceInt(uawPrefix + "inputAnchorCount"));
                uawRelationThumbnailSelectedAnchorCount = Math.max(uawRelationThumbnailSelectedAnchorCount,
                        traceInt(uawPrefix + "selectedAnchorCount"));
                uawRelationThumbnailAnchorBudget = Math.max(uawRelationThumbnailAnchorBudget,
                        traceInt(uawPrefix + "anchorBudget"));
                uawRelationThumbnailPairBudget = Math.max(uawRelationThumbnailPairBudget,
                        traceInt(uawPrefix + "pairBudget"));
                uawRelationThumbnailEmittedPairCount = Math.max(uawRelationThumbnailEmittedPairCount,
                        traceInt(uawPrefix + "emittedPairCount"));
                uawRelationThumbnailSliced = uawRelationThumbnailSliced || traceBoolean(uawPrefix + "sliced");
            }
        }

        double hitRate = calls <= 0 ? 0.0 : ((double) hits / (double) calls);
        double evidence = docCount <= 0 ? 0.0 : ((double) evidenceCount / (double) docCount);
        double avgLatency = calls <= 0 ? 0.0 : ((double) totalLatencyMs / (double) calls);
        double fallbackRate = calls <= 0 ? 0.0 : ((double) fallbackCount / (double) calls);
        double avgDocsPerQuery = calls <= 0 ? 0.0 : ((double) docCount / (double) calls);
        NormalizedRagMetrics normalized = NormalizedRagMetrics.from(
                calls,
                hits,
                docCount,
                evidenceCount,
                sourceCounts,
                avgDocsPerQuery,
                8,
                avgLatency,
                fallbackRate);

        // BLUE calls are tracked at the runner (not here)
        RgbSoakMetrics m = new RgbSoakMetrics(
                calls,
                hitRate,
                evidence,
                avgLatency,
                calls,
                0,
                fallbackRate,
                docCount,
                evidenceCount,
                sourceCounts.size(),
                avgDocsPerQuery,
                normalized);
        String safeStrategyKey = strategyKey == null || strategyKey.isBlank() ? strategy.name() : strategyKey;
        out.put(safeStrategyKey, m);
        recordRelationThumbnailSoakMetrics(
                safeStrategyKey,
                relationThumbnailCandidateCount,
                relationThumbnailSelectedCount,
                relationThumbnailDroppedCount,
                relationThumbnailAnchorSeedUsedCount,
                relationThumbnailSignalAppliedCount,
                relationThumbnailSignalSlicedCount,
                relationThumbnailSignalAnchorSeedCount,
                relationThumbnailSignalNoSelectionCount,
                relationThumbnailSelectionReason,
                relationThumbnailSliceMapCount,
                List.copyOf(relationThumbnailRelationKinds));
        recordRelationThumbnailContextLayerMetrics(
                safeStrategyKey,
                List.copyOf(relationThumbnailContextLayers),
                relationThumbnailContextLayerCounts);
        recordUawRelationThumbnailSoakMetrics(
                safeStrategyKey,
                uawRelationThumbnailInputAnchorCount,
                uawRelationThumbnailSelectedAnchorCount,
                uawRelationThumbnailAnchorBudget,
                uawRelationThumbnailPairBudget,
                uawRelationThumbnailEmittedPairCount,
                uawRelationThumbnailSliced);
        return 1;
    }

    private static void addNeutralDebug(Map<String, Object> debug, Map<String, RgbSoakMetrics> metricsByStrategy) {
        Map<String, Object> thresholds = neutralThresholds();
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> weakPoints = new java.util.ArrayList<>();

        TraceStore.put("rgb.soak.schema", NormalizedRagMetrics.SCHEMA_VERSION);

        boolean canonicalPublished = false;
        if (metricsByStrategy != null) {
            for (Map.Entry<String, RgbSoakMetrics> entry : metricsByStrategy.entrySet()) {
                String strategy = entry.getKey();
                RgbSoakMetrics metrics = entry.getValue();
                if (strategy == null || strategy.isBlank() || metrics == null) {
                    continue;
                }
                Map<String, Object> strategySummary = strategySummary(metrics);
                addRelationThumbnailSoakSummary(strategy, strategySummary);
                addUawRelationThumbnailSoakSummary(strategy, strategySummary);
                summary.put(strategy, strategySummary);
                TraceStore.put("rgb.soak.strategy." + strategy + ".balancedScore",
                        metrics.normalized() == null ? 0.0d : metrics.normalized().balancedScore());
                if (!canonicalPublished && metrics.normalized() != null) {
                    metrics.normalized().putTrace();
                    TraceStore.put("rgb.soak.primaryStrategy", safeMetricLabel(strategy));
                    canonicalPublished = true;
                }

                List<Map<String, Object>> strategyWeakPoints = weakPoints(strategy, metrics);
                weakPoints.addAll(strategyWeakPoints);
                for (Map<String, Object> weakPoint : strategyWeakPoints) {
                    TraceStore.append("rgb.soak.weakPoints", weakPoint);
                }
            }
        }

        debug.put("ragNeutralThresholds", thresholds);
        debug.put("ragNeutralSummary", summary);
        debug.put("ragNeutralWeakPoints", weakPoints);
        debug.put("openclawEvaluator.promptPayload", openClawPromptPayload(summary, thresholds, weakPoints));
    }

    private static Map<String, Object> neutralThresholds() {
        return NormalizedRagMetrics.defaultThresholdMap();
    }

    private static Map<String, Object> strategySummary(RgbSoakMetrics metrics) {
        NormalizedRagMetrics normalized = metrics.normalized();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("calls", metrics.calls());
        summary.put("hits", Math.round(metrics.retrievalHitRate() * metrics.calls()));
        summary.put("docCount", metrics.docCount());
        summary.put("evidenceCount", metrics.evidenceCount());
        summary.put("distinctSources", metrics.distinctSources());
        summary.put("avgDocsPerQuery", metrics.avgDocsPerQuery());
        summary.put("avgLatencyMs", metrics.avgLatencyMs());
        summary.put("fallbackRate", metrics.fallbackRate());
        summary.put("balancedScore", normalized == null ? 0.0d : normalized.balancedScore());
        return summary;
    }

    private static void addRelationThumbnailSoakSummary(String strategy, Map<String, Object> summary) {
        if (strategy == null || strategy.isBlank() || summary == null) {
            return;
        }
        String prefix = "rgb.soak.strategy." + strategy + ".";
        int candidateCount = traceInt(prefix + "relationThumbnailCandidateCount");
        int selectedCount = traceInt(prefix + "relationThumbnailSelectedCount");
        int droppedCount = traceInt(prefix + "relationThumbnailDroppedCount");
        int anchorSeedUsedCount = traceInt(prefix + "relationThumbnailAnchorSeedUsedCount");
        if (candidateCount <= 0 && selectedCount <= 0 && droppedCount <= 0 && anchorSeedUsedCount <= 0) {
            return;
        }
        summary.put("relationThumbnailCandidateCount", candidateCount);
        summary.put("relationThumbnailSelectedCount", selectedCount);
        summary.put("relationThumbnailDroppedCount", droppedCount);
        summary.put("relationThumbnailSelectionRate", ratio(selectedCount, candidateCount));
        summary.put("relationThumbnailDropRate", ratio(droppedCount, candidateCount));
        summary.put("relationThumbnailAnchorSeedUsedCount", anchorSeedUsedCount);
        summary.put("relationThumbnailSignalAppliedCount",
                traceInt(prefix + "relationThumbnailSignalAppliedCount"));
        summary.put("relationThumbnailSignalSlicedCount",
                traceInt(prefix + "relationThumbnailSignalSlicedCount"));
        summary.put("relationThumbnailSignalAnchorSeedCount",
                traceInt(prefix + "relationThumbnailSignalAnchorSeedCount"));
        summary.put("relationThumbnailSignalNoSelectionCount",
                traceInt(prefix + "relationThumbnailSignalNoSelectionCount"));
        summary.put("relationThumbnailSelectionReason",
                safeMetricLabel(TraceStore.get(prefix + "relationThumbnailSelectionReason")));
        int sliceMapCount = traceInt(prefix + "relationThumbnailSliceMapCount");
        List<String> relationKinds = traceRelationKindList(prefix + "relationThumbnailRelationKinds");
        List<String> contextLayers = traceMetricLabelList(prefix + "relationThumbnailContextLayers");
        Map<String, Integer> contextLayerCounts = traceMetricCountMap(prefix + "relationThumbnailContextLayerCounts");
        if (sliceMapCount > 0) {
            summary.put("relationThumbnailSliceMapCount", sliceMapCount);
        }
        if (!relationKinds.isEmpty()) {
            summary.put("relationThumbnailRelationKinds", relationKinds);
        }
        if (!contextLayers.isEmpty()) {
            summary.put("relationThumbnailContextLayers", contextLayers);
        }
        if (!contextLayerCounts.isEmpty()) {
            summary.put("relationThumbnailContextLayerCounts", contextLayerCounts);
        }
    }

    private static void addUawRelationThumbnailSoakSummary(String strategy, Map<String, Object> summary) {
        if (strategy == null || strategy.isBlank() || summary == null) {
            return;
        }
        String prefix = "rgb.soak.strategy." + strategy + ".";
        int inputAnchorCount = traceInt(prefix + "uawRelationThumbnailInputAnchorCount");
        int selectedAnchorCount = traceInt(prefix + "uawRelationThumbnailSelectedAnchorCount");
        int anchorBudget = traceInt(prefix + "uawRelationThumbnailAnchorBudget");
        int pairBudget = traceInt(prefix + "uawRelationThumbnailPairBudget");
        int emittedPairCount = traceInt(prefix + "uawRelationThumbnailEmittedPairCount");
        boolean sliced = traceBoolean(prefix + "uawRelationThumbnailSliced");
        if (inputAnchorCount <= 0
                && selectedAnchorCount <= 0
                && anchorBudget <= 0
                && pairBudget <= 0
                && emittedPairCount <= 0
                && !sliced) {
            return;
        }
        summary.put("uawRelationThumbnailInputAnchorCount", inputAnchorCount);
        summary.put("uawRelationThumbnailSelectedAnchorCount", selectedAnchorCount);
        summary.put("uawRelationThumbnailAnchorBudget", anchorBudget);
        summary.put("uawRelationThumbnailPairBudget", pairBudget);
        summary.put("uawRelationThumbnailEmittedPairCount", emittedPairCount);
        summary.put("uawRelationThumbnailSliced", sliced);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> relationThumbnailKgAxis(UnifiedRagOrchestrator.QueryResponse response) {
        if (response == null || response.debug == null || response.debug.isEmpty()) {
            return Map.of();
        }
        Object kgAxis = response.debug.get("rag.eval.kgAxis");
        return kgAxis instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<Map<String, Object>> relationThumbnailSliceMap(UnifiedRagOrchestrator.QueryResponse response) {
        if (response == null || response.debug == null || response.debug.isEmpty()) {
            return List.of();
        }
        Object raw = response.debug.get("retrieval.kg.relationThumbnail.sliceMap");
        if (!(raw instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            copyIfPresent(row, map, "hash");
            copyIfPresent(row, map, "relationKind");
            copyIfPresent(row, map, "selectionReason");
            copyIfPresent(row, map, "contextLayer");
            copyIfPresent(row, map, "overlap");
            if (!row.isEmpty()) {
                out.add(Map.copyOf(row));
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static boolean hasSignal(Map<String, Object> source, String signal) {
        if (source == null || source.isEmpty() || signal == null || signal.isBlank()) {
            return false;
        }
        Object raw = source.get("signals");
        if (!(raw instanceof Iterable<?> iterable)) {
            return signal.equals(safeMetricLabel(raw));
        }
        for (Object item : iterable) {
            if (signal.equals(safeMetricLabel(item))) {
                return true;
            }
        }
        return false;
    }

    private static void copyIfPresent(Map<String, Object> out, Map<?, ?> source, String key) {
        if (out == null || source == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            out.put(key, value);
        }
    }

    private static void recordRelationThumbnailSoakMetrics(String strategy,
                                                           int candidateCount,
                                                           int selectedCount,
                                                           int droppedCount,
                                                           int anchorSeedUsedCount,
                                                           int signalAppliedCount,
                                                           int signalSlicedCount,
                                                           int signalAnchorSeedCount,
                                                           int signalNoSelectionCount,
                                                           String selectionReason,
                                                           int sliceMapCount,
                                                           List<String> relationKinds) {
        if (strategy == null || strategy.isBlank()) {
            return;
        }
        String prefix = "rgb.soak.strategy." + strategy + ".";
        TraceStore.put(prefix + "relationThumbnailCandidateCount", Math.max(0, candidateCount));
        TraceStore.put(prefix + "relationThumbnailSelectedCount", Math.max(0, selectedCount));
        TraceStore.put(prefix + "relationThumbnailDroppedCount", Math.max(0, droppedCount));
        TraceStore.put(prefix + "relationThumbnailSelectionRate", ratio(selectedCount, candidateCount));
        TraceStore.put(prefix + "relationThumbnailDropRate", ratio(droppedCount, candidateCount));
        TraceStore.put(prefix + "relationThumbnailAnchorSeedUsedCount", Math.max(0, anchorSeedUsedCount));
        TraceStore.put(prefix + "relationThumbnailSignalAppliedCount", Math.max(0, signalAppliedCount));
        TraceStore.put(prefix + "relationThumbnailSignalSlicedCount", Math.max(0, signalSlicedCount));
        TraceStore.put(prefix + "relationThumbnailSignalAnchorSeedCount", Math.max(0, signalAnchorSeedCount));
        TraceStore.put(prefix + "relationThumbnailSignalNoSelectionCount", Math.max(0, signalNoSelectionCount));
        TraceStore.put(prefix + "relationThumbnailSelectionReason", safeMetricLabel(selectionReason));
        TraceStore.put(prefix + "relationThumbnailSliceMapCount", Math.max(0, sliceMapCount));
        TraceStore.put(prefix + "relationThumbnailRelationKinds",
                relationKinds == null ? List.of() : List.copyOf(relationKinds));
    }

    private static void recordRelationThumbnailContextLayerMetrics(String strategy,
                                                                   List<String> contextLayers,
                                                                   Map<String, Integer> contextLayerCounts) {
        if (strategy == null || strategy.isBlank()) {
            return;
        }
        String prefix = "rgb.soak.strategy." + strategy + ".";
        TraceStore.put(prefix + "relationThumbnailContextLayers",
                contextLayers == null ? List.of() : List.copyOf(contextLayers));
        TraceStore.put(prefix + "relationThumbnailContextLayerCounts",
                safeMetricCountMap(contextLayerCounts));
    }

    private static void recordUawRelationThumbnailSoakMetrics(String strategy,
                                                              int inputAnchorCount,
                                                              int selectedAnchorCount,
                                                              int anchorBudget,
                                                              int pairBudget,
                                                              int emittedPairCount,
                                                              boolean sliced) {
        if (strategy == null || strategy.isBlank()) {
            return;
        }
        if (inputAnchorCount <= 0
                && selectedAnchorCount <= 0
                && anchorBudget <= 0
                && pairBudget <= 0
                && emittedPairCount <= 0
                && !sliced) {
            return;
        }
        String prefix = "rgb.soak.strategy." + strategy + ".";
        TraceStore.put(prefix + "uawRelationThumbnailInputAnchorCount", Math.max(0, inputAnchorCount));
        TraceStore.put(prefix + "uawRelationThumbnailSelectedAnchorCount", Math.max(0, selectedAnchorCount));
        TraceStore.put(prefix + "uawRelationThumbnailAnchorBudget", Math.max(0, anchorBudget));
        TraceStore.put(prefix + "uawRelationThumbnailPairBudget", Math.max(0, pairBudget));
        TraceStore.put(prefix + "uawRelationThumbnailEmittedPairCount", Math.max(0, emittedPairCount));
        TraceStore.put(prefix + "uawRelationThumbnailSliced", sliced);
    }

    private static int traceInt(String key) {
        Object value = TraceStore.get(key);
        return value == null ? 0 : intMetric(Map.of("value", value), "value");
    }

    private static boolean traceBoolean(String key) {
        Object value = TraceStore.get(key);
        return value != null && booleanMetric(Map.of("value", value), "value");
    }

    private static List<String> traceRelationKindList(String key) {
        return traceMetricLabelList(key);
    }

    private static List<String> traceMetricLabelList(String key) {
        Object value = TraceStore.get(key);
        if (!(value instanceof Iterable<?> iterable)) {
            String label = safeMetricLabel(value);
            return label.isBlank() ? List.of() : List.of(label);
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Object item : iterable) {
            String label = safeMetricLabel(item);
            if (!label.isBlank()) {
                out.add(label);
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static Map<String, Integer> traceMetricCountMap(String key) {
        Object value = TraceStore.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String label = safeMetricLabel(entry.getKey());
            int count = intMetric(Map.of("value", entry.getValue()), "value");
            if (!label.isBlank() && count > 0) {
                out.put(label, count);
            }
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static Map<String, Integer> safeMetricCountMap(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String label = safeMetricLabel(entry.getKey());
            int count = entry.getValue() == null ? 0 : Math.max(0, entry.getValue());
            if (!label.isBlank() && count > 0) {
                out.put(label, count);
            }
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static int intMetric(Map<String, Object> source, String key) {
        if (source == null || key == null) {
            return 0;
        }
        Object value = source.get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            TraceStore.put("rgb.soak.suppressed.stage", "intMetric");
            TraceStore.put("rgb.soak.suppressed.errorType", "invalid_number");
            TraceStore.put("rgb.soak.suppressed.intMetric", true);
            TraceStore.put("rgb.soak.suppressed.intMetric.errorType", "invalid_number");
            return 0;
        }
    }

    private static boolean booleanMetric(Map<String, Object> source, String key) {
        if (source == null || key == null) {
            return false;
        }
        Object value = source.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0.0d : Math.max(0.0d, Math.min(1.0d, numerator / (double) denominator));
    }

    private static String safeMetricLabel(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim().replaceAll("[\\r\\n\\t]+", " ");
        if (text.isBlank()) {
            return "";
        }
        return text.length() <= 48 ? text : text.substring(0, 48);
    }

    private static List<Map<String, Object>> weakPoints(String strategy, RgbSoakMetrics metrics) {
        NormalizedRagMetrics normalized = metrics.normalized();
        if (normalized == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> thresholdBreak : NormalizedRagMetrics.thresholdBreaks(normalized, metrics.docCount())) {
            Map<String, Object> weakPoint = new LinkedHashMap<>(thresholdBreak);
            weakPoint.put("strategy", strategy);
            weakPoint.putIfAbsent("scope", "soak");
            weakPoint.putIfAbsent("sampleCount", metrics.queries());
            weakPoint.putIfAbsent("aggregationWindow", "rgb-soak");
            out.add(weakPoint);
        }
        return out;
    }

    private static Map<String, Object> openClawPromptPayload(Map<String, Object> summary,
                                                             Map<String, Object> thresholds,
                                                             List<Map<String, Object>> weakPoints) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", NormalizedRagMetrics.SCHEMA_VERSION);
        payload.put("advisoryOnly", true);
        payload.put("evaluator", "openclaw-offline");
        payload.put("modelHint", "ollama/" + com.example.lms.llm.ModelCapabilities.DEFAULT_LOCAL_FAST_MODEL);
        payload.put("thresholds", thresholds);
        payload.put("strategySummaries", summary == null ? Map.of() : summary);
        payload.put("weakPoints", weakPoints == null ? List.of() : weakPoints);
        payload.put("nextInspectionCandidates", nextInspectionCandidates(weakPoints));
        return payload;
    }

    private static List<String> nextInspectionCandidates(List<Map<String, Object>> weakPoints) {
        if (weakPoints == null || weakPoints.isEmpty()) {
            return List.of("compare_strategy_balanced_scores", "sample_answer_synthesis_quality");
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Map<String, Object> weakPoint : weakPoints) {
            String label = String.valueOf(weakPoint.get("label"));
            switch (label) {
                case "retrieval_starvation" -> out.add("check_provider_disabled_or_zero_result_path");
                case "weak_evidence" -> out.add("inspect_snippet_or_url_evidence_population");
                case "source_collapse" -> out.add("inspect_domain_filter_and_source_mix");
                case "thin_results" -> out.add("inspect_topk_and_after_filter_counts");
                case "latency_pressure" -> out.add("inspect_rerank_timeout_or_slow_provider");
                case "fallback_dependency" -> out.add("inspect_fallback_reason_distribution");
                default -> out.add("inspect_strategy_trace");
            }
        }
        return List.copyOf(out);
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "UNKNOWN";
        }
        return source.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private Path writeReportFile(RgbSoakReport report) {
        Path staged = null;
        try {
            String dir = props.getSoakReportDir();
            if (dir == null || dir.isBlank()) dir = "./soak_reports";

            Path outDir = Path.of(dir);
            Files.createDirectories(outDir);

            LocalDate d = LocalDate.ofInstant(report.startedAt(), ZoneId.systemDefault());
            String name = YYYYMMDD.format(d) + "_rgb.json";
            Path out = outDir.resolve(name);

            staged = Files.createTempFile(outDir, name + ".", ".tmp");
            om.writerWithDefaultPrettyPrinter().writeValue(staged.toFile(), report);
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            replaceReport(staged, out);
            staged = null;
            return out;
        } catch (Exception e) {
            log.warn("[RGB] failed to write report file. errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return null;
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (Exception cleanupFailure) {
                    log.warn("[RGB] failed to remove staged report. errorHash={} errorLength={}",
                            com.example.lms.trace.SafeRedactor.hashValue(messageOf(cleanupFailure)),
                            messageLength(cleanupFailure));
                }
            }
        }
    }

    private static void replaceReport(Path staged, Path out) throws IOException {
        try {
            Files.move(staged, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staged, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static void putReportFileDiagnostics(Map<String, Object> debug, Path out) {
        if (debug == null || out == null) {
            return;
        }
        String fileName = out.getFileName() == null ? "" : out.getFileName().toString();
        String path = out.toString();
        debug.put("reportFileHash", fileName.isBlank() ? "" : com.example.lms.trace.SafeRedactor.hashValue(fileName));
        debug.put("reportFileLength", fileName.length());
        debug.put("reportPathHash", path.isBlank() ? "" : com.example.lms.trace.SafeRedactor.hashValue(path));
        debug.put("reportPathLength", path.length());
    }
}
