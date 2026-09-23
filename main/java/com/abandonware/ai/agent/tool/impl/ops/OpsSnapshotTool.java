package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.abandonware.ai.agent.contract.ToolManifestSnapshot;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class OpsSnapshotTool implements AgentTool {
    private final ToolRegistry registry;
    private final ToolManifestCatalog catalog;
    private final Environment environment;
    private final ObjectProvider<DebugEventStore> debugEvents;
    private final ObjectProvider<DebugAiMetricsService> debugAiMetrics;

    public OpsSnapshotTool(ToolRegistry registry,
                           ToolManifestCatalog catalog,
                           Environment environment,
                           ObjectProvider<DebugEventStore> debugEvents,
                           ObjectProvider<DebugAiMetricsService> debugAiMetrics) {
        this.registry = registry;
        this.catalog = catalog;
        this.environment = environment;
        this.debugEvents = debugEvents;
        this.debugAiMetrics = debugAiMetrics;
    }

    @Override
    public String id() {
        return "ops.snapshot";
    }

    @Override
    public String description() {
        return "Summarize runtime tool, manifest, debug, and trace state without secret values.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        ToolManifestSnapshot manifest = catalog.load();
        DebugEventStore store = debugEvents == null ? null : debugEvents.getIfAvailable();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("manifestResourceHash", SafeRedactor.hashValue(manifest.resourcePath()));
        snapshot.put("manifestResourceLength", manifest.resourcePath() == null ? 0 : manifest.resourcePath().length());
        snapshot.put("manifestToolCount", manifest.entries().size());
        snapshot.put("registeredToolCount", registry.all().size());
        snapshot.put("duplicateToolIds", registry.duplicateToolIdCounts());
        snapshot.put("activeProfiles", environment == null ? java.util.List.of() : Arrays.asList(environment.getActiveProfiles()));
        snapshot.put("defaultProfiles", environment == null ? java.util.List.of() : Arrays.asList(environment.getDefaultProfiles()));
        snapshot.put("debugEventStoreAvailable", store != null);
        snapshot.put("recentDebugEventCount", store == null ? 0 : store.list(50).size());
        snapshot.put("traceKeyCount", TraceStore.getAll().size());
        DebugAiMetricsService metrics = debugAiMetrics == null ? null : debugAiMetrics.getIfAvailable();
        snapshot.put("debugAiMetricsAvailable", metrics != null);
        if (metrics != null) {
            Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
            int debugAiLimit = boundedInt(
                    firstPresent(input.get("debugAiLimit"), input.get("limit")),
                    30,
                    1,
                    500,
                    "debugAiLimit");
            long debugAiWindowMs = boundedLong(firstPresent(input.get("debugAiWindowMs"), input.get("windowMs")),
                    60_000L,
                    1_000L,
                    24L * 60L * 60L * 1000L,
                    "debugAiWindowMs");
            snapshot.put("debugAi", metrics.compactSnapshot(debugAiLimit, debugAiWindowMs));
        }
        snapshot.put("manifestIssueCount", manifest.issues().size());
        snapshot.put("manifestWarningCount", manifest.warnings().size());
        return ToolResponse.ok().put("snapshot", snapshot);
    }

    private static Object firstPresent(Object first, Object second) {
        return first != null ? first : second;
    }

    private static int boundedInt(Object value, int fallback, int min, int max, String stage) {
        if (value instanceof Number number) {
            return Math.max(min, Math.min(max, number.intValue()));
        }
        if (value != null) {
            try {
                return Math.max(min, Math.min(max, Integer.parseInt(String.valueOf(value).trim())));
            } catch (NumberFormatException ignore) {
                traceSuppressed(stage, value, ignore);
                return fallback;
            }
        }
        return fallback;
    }

    private static long boundedLong(Object value, long fallback, long min, long max, String stage) {
        if (value instanceof Number number) {
            return Math.max(min, Math.min(max, number.longValue()));
        }
        if (value != null) {
            try {
                return Math.max(min, Math.min(max, Long.parseLong(String.valueOf(value).trim())));
            } catch (NumberFormatException ignore) {
                traceSuppressed(stage, value, ignore);
                return fallback;
            }
        }
        return fallback;
    }

    private static void traceSuppressed(String stage, Object value, Throwable error) {
        String raw = value == null ? null : String.valueOf(value);
        TraceStore.put("agent.ops.snapshot.suppressed", true);
        TraceStore.put("agent.ops.snapshot.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.ops.snapshot.suppressed.errorType", "invalid_number");
        TraceStore.put("agent.ops.snapshot.suppressed.valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put("agent.ops.snapshot.suppressed.valueLength", raw == null ? 0 : raw.length());
    }
}
