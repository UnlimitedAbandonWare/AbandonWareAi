package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Writes a small allowlisted TraceStore snapshot as NDJSON for post-request
 * diagnostics. This is intentionally not a general trace dump.
 */
public class TraceSnapshotExporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_VALUE_LEN = 400;

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "boosterMode.active",
            "boosterMode.excludedModes",
            "boosterMode.priority",
            "boosterMode.exclusionReason",
            "retrievalOrder.lastSetBy",
            "retrievalOrder.authority.owner",
            "retrievalOrder.authority.suppressedOwner",
            "retrievalOrder.authority.reason",
            "retrievalOrder.authority.suppressedReason",
            "routing.executionPlan.primaryMode",
            "routing.executionPlan.triggers",
            "routing.executionPlan.extremeZ",
            "routing.executionPlan.overdrive",
            "routing.executionPlan.hypernova",
            "routing.executionPlan.applied",
            "routing.executionPlan.applied.primaryMode",
            "routing.executionPlan.applied.triggers",
            "routing.executionPlan.applied.stages",
            "routing.executionPlan.applied.extremeZ",
            "routing.executionPlan.applied.overdrive",
            "routing.executionPlan.applied.hypernova",
            "routing.executionPlan.applied.dualGearMode",
            "routing.executionPlan.applied.gachaVariantCount",
            "specialMode.conflict.suppressed",
            "specialMode.priority",
            "specialMode.conflict.extremeZ.suppressed",
            "specialMode.conflict.extremeZ.primaryMode",
            "specialMode.conflict.overdrive.suppressed",
            "extremeZ.cancelShieldWrapped",
            "extremeZ.timeBudgetConsumedMs",
            "hypernova.cvarPhi",
            "nova.hypernova.riskK.used",
            "nova.hypernova.riskK.candidateCount",
            "nova.hypernova.riskK.totalK",
            "nova.hypernova.riskK.alloc.sum",
            "cihRag.breadcrumb.queryRedacted",
            "cihRag.mlaBreadcrumbCount",
            "cihRag.implementationStage",
            "moe.evolverPlateRegistered",
            "cfvm.boltzmannTemp",
            "cfvm.snapshot.saved",
            "localLlm.startup.enabled",
            "localLlm.startup.autostart",
            "localLlm.startup.status",
            "localLlm.startup.reason",
            "localLlm.startup.hostHash",
            "localLlm.startup.healthUrlHash",
            "localLlm.startup.healthUrlLength",
            "localLlm.startup.warmupTargetDim",
            "localLlm.warmup.enabled",
            "localLlm.warmup.status",
            "localLlm.warmup.modelHash",
            "localLlm.warmup.modelLength",
            "localLlm.warmup.targetDim",
            "localLlm.warmup.returnedDim",
            "localLlm.warmup.reason",
            "llm.modelGuard.triggered",
            "llm.modelGuard.mode",
            "llm.modelGuard.endpoint",
            "llm.modelGuard.failReason",
            "llm.modelGuard.requestedModelHash",
            "llm.modelGuard.requestedModelLength",
            "llm.modelGuard.substituteChatModelHash",
            "llm.modelGuard.substituteChatModelLength",
            "outCount",
            "stageCountsSelectedFromOut",
            "starvationFallback.poolSafeEmpty",
            "cacheOnly.merged.count",
            "tracePool.size",
            "rescueMerge.used",
            "starvationFallback.trigger",
            "poolSafeEmpty",
            "vectorFallback.used",
            "vectorFallback.reason",
            "vectorFallback.effectiveTopK",
            "web.naver.skipped.reason",
            "web.brave.skipped.reason",
            "web.serpapi.skipped.reason",
            "web.tavily.skipped.reason"
    );

    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "cfvm.",
            "extremeZ.",
            "hypernova.",
            "moe.",
            "boosterMode.",
            "retrievalOrder.",
            "retrieval.order.",
            "queryTransformer.",
            "embed.matryoshka.",
            "starvationFallback.",
            "cacheOnly.",
            "rescueMerge."
    );

    private final Path rootDir;
    private final boolean enabled;

    public TraceSnapshotExporter(Path rootDir, boolean enabled) {
        this.rootDir = rootDir;
        this.enabled = enabled;
    }

    public void exportCurrentTrace(String requestId, String sessionId) {
        if (!enabled || rootDir == null) {
            TraceStore.put("trace.snapshot.export.skipped", "disabled");
            return;
        }

        try {
            Files.createDirectories(rootDir);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("_ts", Instant.now().toString());
            row.put("_requestHash", SafeRedactor.hashValue(requestId));
            row.put("_sessionHash", SafeRedactor.hashValue(sessionId));

            Map<String, Object> trace = TraceStore.getAll();
            for (Map.Entry<String, Object> entry : trace.entrySet()) {
                String key = entry.getKey();
                if (!isAllowedKey(key)) {
                    continue;
                }
                row.put(key, exportValue(key, entry.getValue()));
            }
            putStageCountsFallback(row, trace);
            putRescueKpiFallbacks(row, trace);
            putStarvationKpiFallbacks(row, trace);

            Path file = rootDir.resolve("trace_" + LocalDate.now() + ".ndjson");
            Files.writeString(file, JSON.writeValueAsString(row) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            TraceStore.put("trace.snapshot.exported", true);
        } catch (IOException | RuntimeException e) {
            TraceStore.put("trace.snapshot.export.failed",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"));
        }
    }

    private static boolean isAllowedKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (ALLOWED_KEYS.contains(key)) {
            return true;
        }
        if (SafeRedactor.isRestrictedKey(key)) {
            return false;
        }
        for (String prefix : ALLOWED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void putStageCountsFallback(Map<String, Object> row, Map<String, Object> trace) {
        if (row.containsKey("stageCountsSelectedFromOut")) {
            return;
        }
        Object fallback = firstNonNull(
                trace.get("stageCountsSelectedFromOut.last"),
                trace.get("web.failsoft.stageCountsSelectedFromOut"),
                trace.get("web.failsoft.stageCountsSelectedFromOut.last"));
        if (fallback != null) {
            row.put("stageCountsSelectedFromOut", exportValue("stageCountsSelectedFromOut", fallback));
        }
    }

    private static void putRescueKpiFallbacks(Map<String, Object> row, Map<String, Object> trace) {
        if (!row.containsKey("tracePool.size")) {
            Object fallback = trace.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size");
            if (fallback != null) {
                row.put("tracePool.size", exportValue("tracePool.size", fallback));
            }
        }
        if (!row.containsKey("rescueMerge.used")) {
            Object fallback = trace.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used");
            if (fallback != null) {
                row.put("rescueMerge.used", exportValue("rescueMerge.used", fallback));
            }
        }
    }

    private static void putStarvationKpiFallbacks(Map<String, Object> row, Map<String, Object> trace) {
        putFallback(row, trace, "starvationFallback.used", "web.failsoft.starvationFallback.used");
        putFallback(row, trace, "starvationFallback.trigger",
                "web.failsoft.starvationFallback.trigger",
                "web.failsoft.starvationFallback");
        putFallback(row, trace, "starvationFallback.poolUsed", "web.failsoft.starvationFallback.poolUsed");
        putFallback(row, trace, "starvationFallback.pool.safe.size", "web.failsoft.starvationFallback.pool.safe.size");
        putFallback(row, trace, "starvationFallback.pool.dev.size", "web.failsoft.starvationFallback.pool.dev.size");
        putFallback(row, trace, "starvationFallback.poolSafeEmpty", "web.failsoft.starvationFallback.poolSafeEmpty");
        putFallback(row, trace, "starvationFallback.count", "web.failsoft.starvationFallback.count");
        putFallback(row, trace, "starvationFallback.added", "web.failsoft.starvationFallback.added");
    }

    private static void putFallback(Map<String, Object> row, Map<String, Object> trace, String key, String... fallbackKeys) {
        if (row.containsKey(key)) {
            return;
        }
        for (String fallbackKey : fallbackKeys) {
            Object fallback = trace.get(fallbackKey);
            if (fallback != null) {
                row.put(key, exportValue(key, fallback));
                return;
            }
        }
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Object exportValue(String key, Object value) {
        if ("cihRag.breadcrumb.queryRedacted".equals(key)) {
            return toBoolean(value, false);
        }
        if (("boosterMode.excludedModes".equals(key)
                || "retrievalOrder.lastOrder".equals(key)
                || "routing.executionPlan.triggers".equals(key)
                || "routing.executionPlan.applied.triggers".equals(key)
                || "routing.executionPlan.applied.stages".equals(key))
                && value instanceof java.util.List<?> list) {
            return list.stream()
                    .map(item -> SafeRedactor.traceLabelOrFallback(item, "unknown"))
                    .toList();
        }
        if ("cihRag.implementationStage".equals(key)
                || "boosterMode.active".equals(key)
                || "boosterMode.exclusionReason".equals(key)
                || "retrievalOrder.lastSetBy".equals(key)
                || "retrievalOrder.authority.owner".equals(key)
                || "retrievalOrder.authority.suppressedOwner".equals(key)
                || "retrievalOrder.authority.reason".equals(key)
                || "retrievalOrder.authority.suppressedReason".equals(key)
                || "routing.executionPlan.primaryMode".equals(key)
                || "routing.executionPlan.applied.primaryMode".equals(key)
                || "routing.executionPlan.applied.dualGearMode".equals(key)
                || "specialMode.conflict.extremeZ.primaryMode".equals(key)
                || "starvationFallback.poolUsed".equals(key)
                || "localLlm.startup.status".equals(key)
                || "localLlm.startup.reason".equals(key)
                || "localLlm.warmup.status".equals(key)
                || "localLlm.warmup.reason".equals(key)
                || "llm.modelGuard.mode".equals(key)
                || "llm.modelGuard.failReason".equals(key)) {
            return SafeRedactor.traceLabelOrFallback(value, "unknown");
        }
        if ("llm.modelGuard.endpoint".equals(key)) {
            return safeEndpointPath(value);
        }
        if ("boosterMode.priority".equals(key)
                || "specialMode.conflict.suppressed".equals(key)
                || "specialMode.priority".equals(key)) {
            String label = value == null ? null : String.valueOf(value)
                    .replace('>', '_')
                    .replace(',', '_');
            return SafeRedactor.traceLabelOrFallback(label, "unknown");
        }
        if ("starvationFallback.trigger".equals(key)) {
            String label = value == null ? null : String.valueOf(value)
                    .replace("->", "_")
                    .replace('>', '_')
                    .replace(',', '_');
            return SafeRedactor.traceLabelOrFallback(label, "unknown");
        }
        return SafeRedactor.diagnosticValue(key, value, MAX_VALUE_LEN);
    }

    private static String safeEndpointPath(Object value) {
        if (value == null) {
            return "unknown";
        }
        String text = String.valueOf(value).trim();
        if (text.matches("/v1/[A-Za-z0-9_./:-]{1,120}")) {
            return text;
        }
        return SafeRedactor.traceLabelOrFallback(value, "unknown");
    }

    private static boolean toBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() ? fallback : "true".equalsIgnoreCase(s) || "1".equals(s);
    }
}
