package com.example.lms.health;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redacted, fail-soft hardware telemetry for the local desktop GPU executor.
 */
public final class GpuHardwareDiagnostics {

    private static final System.Logger LOG = System.getLogger(GpuHardwareDiagnostics.class.getName());

    private static final String STATUS_DISABLED = "disabled_by_config";
    private static final String STATUS_OK = "ok";
    private static final CommandRunner LOCAL_QUERY = GpuHardwareDiagnostics::runNvidiaSmi;
    private static QuerySample lastQuery;

    private record QuerySample(CommandRunner runner, int timeoutMs, long completedNanos,
                               long observedAtMillis, CommandResult result, IOException failure) { }

    private static synchronized QuerySample query(CommandRunner runner, int timeoutMs) throws InterruptedException {
        // Both successful and failed observations have a short bound; concurrent requests share one probe.
        if (lastQuery != null && lastQuery.runner() == runner && lastQuery.timeoutMs() == timeoutMs
                && System.nanoTime() - lastQuery.completedNanos() < TimeUnit.SECONDS.toNanos(2)) return lastQuery;
        CommandResult result = null;
        IOException failure = null;
        try {
            result = runner.run(timeoutMs);
        } catch (IOException unavailable) {
            failure = unavailable;
        }
        lastQuery = new QuerySample(runner, timeoutMs, System.nanoTime(), System.currentTimeMillis(), result, failure);
        return lastQuery;
    }

    private GpuHardwareDiagnostics() {
    }

    public static Map<String, Object> snapshot(Environment env) {
        return snapshot(env, LOCAL_QUERY);
    }

    static Map<String, Object> snapshot(Environment env, CommandRunner runner) {
        boolean enabled = bool(env, "awx.gpu-hardware.telemetry.enabled", false);
        int timeoutMs = intProp(env, "awx.gpu-hardware.telemetry.timeout-ms", 1_000);
        timeoutMs = Math.max(100, Math.min(5_000, timeoutMs));

        Map<String, Object> out = base(enabled, timeoutMs);
        if (!enabled) {
            out.put("status", STATUS_DISABLED);
            out.put("disabledReason", STATUS_DISABLED);
            return finish(env, out);
        }

        try {
            QuerySample sample = query(runner, timeoutMs);
            out.put("observedAtEpochMs", sample.observedAtMillis());
            out.put("observationAgeMs", Math.max(0L, System.currentTimeMillis() - sample.observedAtMillis()));
            if (sample.failure() != null) throw sample.failure();
            CommandResult result = sample.result();
            out.put("queryExitCode", result.exitCode());
            out.put("driverQuerySucceeded", !result.timedOut() && result.exitCode() == 0);
            if (result.timedOut()) {
                out.put("status", "timeout");
                out.put("disabledReason", "timeout");
                return finish(env, out);
            }
            List<Map<String, Object>> devices = parseNvidiaSmiCsv(result.stdout());
            out.put("devices", devices);
            out.put("detectedCount", devices.size());
            out.put("hasRtx3090", hasDevice(devices, "3090"));
            out.put("hasRtx3060", hasDevice(devices, "3060"));
            out.put("heavyLaneReady", result.exitCode() == 0 && Boolean.TRUE.equals(out.get("hasRtx3090"))
                    && Boolean.TRUE.equals(out.get("hasRtx3060")));
            out.put("memoryEvidenceComplete", !devices.isEmpty()
                    && devices.stream().allMatch(device -> device.containsKey("memoryUsedRatio")));
            out.put("maxMemoryUsedRatio", maxDouble(devices, "memoryUsedRatio"));
            out.put("maxUtilizationGpuPct", maxInt(devices, "utilizationGpuPct"));
            out.put("maxTemperatureC", maxInt(devices, "temperatureC"));
            out.put("available", !devices.isEmpty());
            out.put("status", devices.isEmpty() ? "parse_error" : STATUS_OK);
            out.put("disabledReason", devices.isEmpty() ? "no_parseable_devices" : null);
            if (result.exitCode() != 0) {
                out.put("status", devices.isEmpty() ? "error" : "partial");
                out.put("disabledReason", "nvidia_smi_exit_" + result.exitCode());
            }
            return finish(env, out);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            traceSuppressed("gpuHardware.interrupted", ex);
            out.put("status", "interrupted");
            out.put("disabledReason", "interrupted");
        } catch (IOException ex) {
            traceSuppressed("gpuHardware.io", ex);
            String status = commandFailureStatus(ex);
            out.put("status", status);
            out.put("disabledReason", status);
        } catch (Exception ex) {
            traceSuppressed("gpuHardware.snapshot", ex);
            out.put("status", "error");
            out.put("disabledReason", ex.getClass().getSimpleName());
        }
        return finish(env, out);
    }

    static List<Map<String, Object>> parseNvidiaSmiCsv(String stdout) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return out;
        }
        for (String line : stdout.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] columns = line.split(",", -1);
            if (columns.length < 7) {
                continue;
            }
            Integer index = parseInt(columns[0]);
            String name = safeName(columns[1]);
            Integer memoryTotal = parseInt(columns[2]);
            Integer memoryUsed = parseInt(columns[3]);
            Integer utilization = parseInt(columns[4]);
            Integer temperature = parseInt(columns[5]);
            Double powerDraw = parseDouble(columns[6]);
            if (index == null || name.isBlank()) {
                continue;
            }

            Map<String, Object> device = new LinkedHashMap<>();
            device.put("index", index);
            device.put("nameLabel", name);
            device.put("role", roleForName(name));
            device.put("roleEvidence", "model_name_candidate");
            if (columns.length >= 11) {
                String uuid = columns[7].trim();
                if (uuid.matches("GPU-[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")) {
                    device.put("uuidHash", SafeRedactor.hashValue(uuid));
                }
                String pci = columns[8].trim();
                if (pci.matches("[0-9A-Fa-f]{4,8}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}\\.[0-7]")) device.put("pciBusId", pci);
                String driver = columns[9].trim();
                if (driver.matches("[0-9]+(?:\\.[0-9]+){1,3}")) device.put("driverVersion", driver);
                putIfPresent(device, "memoryFreeMiB", parseInt(columns[10]));
            }
            putIfPresent(device, "memoryTotalMiB", memoryTotal);
            putIfPresent(device, "memoryUsedMiB", memoryUsed);
            if (memoryTotal != null && memoryUsed != null && memoryTotal > 0
                    && memoryUsed >= 0 && memoryUsed <= memoryTotal) {
                device.put("memoryUsedRatio", round4(memoryUsed / (double) memoryTotal));
            }
            putIfPresent(device, "utilizationGpuPct", utilization);
            putIfPresent(device, "temperatureC", temperature);
            putIfPresent(device, "powerDrawW", powerDraw);
            out.add(device);
        }
        return out;
    }

    public static Map<String, Object> admission(Environment env, Map<String, Object> snapshot) {
        Map<String, Object> safeSnapshot = snapshot == null ? Map.of() : snapshot;
        boolean telemetryEnabled = boolValue(safeSnapshot.get("enabled"), false);
        boolean enabled = bool(env, "awx.gpu-hardware.admission.enabled", telemetryEnabled);
        double warnThreshold = doubleProp(env, "awx.gpu-hardware.admission.memory-warn-threshold", 0.82d);
        double blockThreshold = doubleProp(env, "awx.gpu-hardware.admission.memory-block-threshold", 0.90d);
        warnThreshold = clamp(warnThreshold, 0.01d, 0.99d);
        blockThreshold = clamp(blockThreshold, warnThreshold, 0.999d);
        boolean requireRtx3090 = bool(env, "awx.gpu-hardware.admission.require-rtx3090", true);
        boolean requireRtx3060 = bool(env, "awx.gpu-hardware.admission.require-rtx3060", true);
        boolean blockWhenUnavailable = bool(env, "awx.gpu-hardware.admission.block-when-unavailable", true);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("warnThreshold", warnThreshold);
        out.put("blockThreshold", blockThreshold);
        out.put("blockWhenUnavailable", blockWhenUnavailable);
        out.put("pressureLevel", "disabled");
        out.put("status", "disabled_by_config");
        out.put("reason", "disabled_by_config");
        out.put("heavyWorkloadsAllowed", true);
        out.put("retrainAllowed", true);
        out.put("rerankAllowed", true);
        out.put("embeddingFallbackAllowed", true);

        if (!enabled) {
            return out;
        }
        if (!telemetryEnabled) {
            out.put("pressureLevel", "observe_only");
            out.put("status", "observe_only");
            out.put("reason", "telemetry_disabled");
            return out;
        }

        String status = String.valueOf(safeSnapshot.getOrDefault("status", ""));
        boolean available = boolValue(safeSnapshot.get("available"), false);
        if (!STATUS_OK.equals(status) || !available) {
            out.put("pressureLevel", blockWhenUnavailable ? "block" : "observe_only");
            out.put("status", blockWhenUnavailable ? "blocked" : "observe_only");
            out.put("reason", "gpu_telemetry_unavailable");
            if (blockWhenUnavailable) {
                blockHeavy(out);
            }
            return out;
        }

        boolean hasRtx3090 = boolValue(safeSnapshot.get("hasRtx3090"), false);
        boolean hasRtx3060 = boolValue(safeSnapshot.get("hasRtx3060"), false);
        if (requireRtx3090 && !hasRtx3090) {
            out.put("pressureLevel", "block");
            out.put("status", "blocked");
            out.put("reason", "missing_rtx3090");
            blockHeavy(out);
            return out;
        }
        if (requireRtx3060 && !hasRtx3060) {
            out.put("pressureLevel", "block");
            out.put("status", "blocked");
            out.put("reason", "missing_rtx3060");
            blockHeavy(out);
            return out;
        }

        if (Boolean.FALSE.equals(safeSnapshot.get("memoryEvidenceComplete"))) {
            out.put("pressureLevel", blockWhenUnavailable ? "block" : "observe_only");
            out.put("status", blockWhenUnavailable ? "blocked" : "observe_only");
            out.put("reason", "gpu_memory_evidence_needed");
            if (blockWhenUnavailable) blockHeavy(out);
            return out;
        }
        double maxMemory = doubleValue(safeSnapshot.get("maxMemoryUsedRatio"), 0.0d);
        out.put("maxMemoryUsedRatio", maxMemory);
        if (maxMemory >= blockThreshold) {
            out.put("pressureLevel", "block");
            out.put("status", "blocked");
            out.put("reason", "gpu_memory_pressure");
            blockHeavy(out);
            return out;
        }
        if (maxMemory >= warnThreshold) {
            out.put("pressureLevel", "warn");
            out.put("status", "degraded");
            out.put("reason", "gpu_memory_pressure_warn");
            out.put("retrainAllowed", false);
            out.put("embeddingFallbackAllowed", false);
            return out;
        }

        out.put("pressureLevel", "nominal");
        out.put("status", STATUS_OK);
        out.put("reason", "ok");
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> admissionFromSnapshot(Map<String, Object> snapshot) {
        Object value = snapshot == null ? null : snapshot.get("admission");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, v) -> out.put(String.valueOf(key), v));
            return out;
        }
        return Map.of();
    }

    private static CommandResult runNvidiaSmi(int timeoutMs) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                "nvidia-smi",
                "--query-gpu=index,name,memory.total,memory.used,utilization.gpu,temperature.gpu,power.draw,uuid,pci.bus_id,driver_version,memory.free",
                "--format=csv,noheader,nounits")
                .start();
        boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            return new CommandResult(-1, "", "", true);
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), stdout, stderr, false);
    }

    private static Map<String, Object> finish(Environment env, Map<String, Object> snapshot) {
        snapshot.put("admission", admission(env, snapshot));
        traceHardware(snapshot);
        return snapshot;
    }

    private static Map<String, Object> base(boolean enabled, int timeoutMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("observedAtEpochMs", System.currentTimeMillis());
        out.put("readinessScope", "hardware_inventory_only");
        out.put("inferenceStatus", "not_observed");
        out.put("driverQuerySucceeded", false);
        out.put("available", false);
        out.put("status", "");
        out.put("disabledReason", null);
        out.put("detectedCount", 0);
        out.put("hasRtx3090", false);
        out.put("hasRtx3060", false);
        out.put("heavyLaneReady", false);
        out.put("timeoutMs", timeoutMs);
        out.put("devices", List.of());
        out.put("admission", Map.of());
        return out;
    }

    private static void traceHardware(Map<String, Object> snapshot) {
        try {
            TraceStore.put("uaw.gpu-hardware.status", snapshot.getOrDefault("status", ""));
            TraceStore.put("uaw.gpu-hardware.available", snapshot.getOrDefault("available", false));
            TraceStore.put("uaw.gpu-hardware.detectedCount", snapshot.getOrDefault("detectedCount", 0));
            TraceStore.put("uaw.gpu-hardware.hasRtx3090", snapshot.getOrDefault("hasRtx3090", false));
            TraceStore.put("uaw.gpu-hardware.hasRtx3060", snapshot.getOrDefault("hasRtx3060", false));
            TraceStore.put("uaw.gpu-hardware.heavyLaneReady", snapshot.getOrDefault("heavyLaneReady", false));
            TraceStore.put("uaw.gpu-hardware.maxMemoryUsedRatio", snapshot.get("maxMemoryUsedRatio"));
            Map<String, Object> admission = admissionFromSnapshot(snapshot);
            TraceStore.put("uaw.gpu-hardware.admission.status", admission.getOrDefault("status", ""));
            TraceStore.put("uaw.gpu-hardware.admission.reason", SafeRedactor.traceLabelOrFallback(String.valueOf(admission.getOrDefault("reason", "")), "unknown"));
            TraceStore.put("uaw.gpu-hardware.admission.pressureLevel", admission.getOrDefault("pressureLevel", ""));
            TraceStore.put("uaw.gpu-hardware.admission.retrainAllowed", admission.getOrDefault("retrainAllowed", true));
            TraceStore.put("uaw.gpu-hardware.admission.rerankAllowed", admission.getOrDefault("rerankAllowed", true));
            TraceStore.put("uaw.gpu-hardware.admission.embeddingFallbackAllowed",
                    admission.getOrDefault("embeddingFallbackAllowed", true));
        } catch (Throwable ex) {
            LOG.log(System.Logger.Level.DEBUG,
                    "GPU hardware diagnostics trace skipped stage=finish_trace errorType="
                            + ex.getClass().getSimpleName());
        }
    }

    private static void blockHeavy(Map<String, Object> admission) {
        admission.put("heavyWorkloadsAllowed", false);
        admission.put("retrainAllowed", false);
        admission.put("rerankAllowed", false);
        admission.put("embeddingFallbackAllowed", false);
    }

    private static boolean hasDevice(List<Map<String, Object>> devices, String token) {
        String needle = token == null ? "" : token.toLowerCase(Locale.ROOT);
        for (Map<String, Object> device : devices) {
            String name = String.valueOf(device.getOrDefault("nameLabel", "")).toLowerCase(Locale.ROOT);
            if (name.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static Double maxDouble(List<Map<String, Object>> devices, String key) {
        Double max = null;
        for (Map<String, Object> device : devices) {
            Object value = device.get(key);
            if (value instanceof Number number) {
                double d = number.doubleValue();
                if (!Double.isFinite(d)) {
                    continue;
                }
                max = max == null ? d : Math.max(max, d);
            }
        }
        return max;
    }

    private static Integer maxInt(List<Map<String, Object>> devices, String key) {
        Integer max = null;
        for (Map<String, Object> device : devices) {
            Object value = device.get(key);
            if (value instanceof Number number) {
                if (!Double.isFinite(number.doubleValue())) {
                    continue;
                }
                int i = number.intValue();
                max = max == null ? i : Math.max(max, i);
            }
        }
        return max;
    }

    private static String commandFailureStatus(IOException ex) {
        String text = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("cannot run program")
                || text.contains("error=2")
                || text.contains("no such file")
                || text.contains("not found")) {
            return "command_not_found";
        }
        return "error";
    }

    private static String roleForName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.contains("3090")) {
            return "primary-chat-rerank-heavy";
        }
        if (lower.contains("3060")) {
            return "fast-helper-embedding";
        }
        return "observe-only";
    }

    private static String safeName(String raw) {
        String redacted = SafeRedactor.redact(raw == null ? "" : raw.trim());
        if (redacted == null) {
            return "";
        }
        String label = redacted.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("[^A-Za-z0-9 ._:-]", "_")
                .trim();
        return label.length() <= 80 ? label : label.substring(0, 80);
    }

    private static Integer parseInt(String raw) {
        String value = numeric(raw, false);
        if (value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                traceSuppressed("gpuHardware.parseInt", new NumberFormatException("non-finite"));
                return null;
            }
            return (int) Math.round(parsed);
        } catch (NumberFormatException ex) {
            traceSuppressed("gpuHardware.parseInt", ex);
            return null;
        }
    }

    private static Double parseDouble(String raw) {
        String value = numeric(raw, true);
        if (value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                traceSuppressed("gpuHardware.parseDouble", new NumberFormatException("non-finite"));
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            traceSuppressed("gpuHardware.parseDouble", ex);
            return null;
        }
    }

    private static String numeric(String raw, boolean decimal) {
        if (raw == null) {
            return "";
        }
        String regex = decimal ? "[^0-9.\\-]" : "[^0-9\\-]";
        return raw.trim().replaceAll(regex, "");
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static boolean bool(Environment env, String key, boolean defaultValue) {
        String value = prop(env, key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static int intProp(Environment env, String key, int defaultValue) {
        String value = prop(env, key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            traceSuppressed("gpuHardware.intProp", ex);
            return defaultValue;
        }
    }

    private static double doubleProp(Environment env, String key, double defaultValue) {
        String value = prop(env, key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed)) {
                traceSuppressed("gpuHardware.doubleProp", new NumberFormatException("non-finite"));
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            traceSuppressed("gpuHardware.doubleProp", ex);
            return defaultValue;
        }
    }

    private static boolean boolValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() ? defaultValue : Boolean.parseBoolean(s);
    }

    private static double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            if (!Double.isFinite(parsed)) {
                traceSuppressed("gpuHardware.doubleValue", new NumberFormatException("non-finite"));
                return defaultValue;
            }
            return parsed;
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            if (!Double.isFinite(parsed)) {
                traceSuppressed("gpuHardware.doubleValue", new NumberFormatException("non-finite"));
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            traceSuppressed("gpuHardware.doubleValue", ex);
            return defaultValue;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        TraceStore.put("uaw.gpu-hardware.suppressed.stage", safeStage);
        TraceStore.put("uaw.gpu-hardware.suppressed.errorType", errorType);
        TraceStore.put("uaw.gpu-hardware.suppressed." + safeStage, true);
        TraceStore.put("uaw.gpu-hardware.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static String prop(Environment env, String key, String defaultValue) {
        return env == null ? defaultValue : env.getProperty(key, defaultValue);
    }

    interface CommandRunner {
        CommandResult run(int timeoutMs) throws IOException, InterruptedException;
    }

    record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
    }
}
