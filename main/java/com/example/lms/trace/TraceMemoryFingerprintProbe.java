package com.example.lms.trace;

import com.abandonware.ai.agent.integrations.guard.NovaErrorBreakGuard;
import com.abandonware.ai.agent.orchestrator.recovery.FailureClass;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryAction;
import com.abandonware.ai.agent.orchestrator.recovery.RecoveryPolicy;
import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * CRC-like trace-memory checkpoint probe.
 *
 * <p>The probe reads raw memory only inside the current call, converts it into
 * deterministic fingerprints/counts, and publishes only redacted checkpoint
 * deltas. It gives the debug surface a low-cardinality "eye" for memory
 * mutation without turning TraceStore or DebugEventStore into raw memory dumps.</p>
 */
@Component
public class TraceMemoryFingerprintProbe {

    private static final String PREFIX = "traceMemory.";
    private static final List<String> RECOVERY_STATE_KEYS = List.of(
            PREFIX + "recovery.failureClass",
            PREFIX + "recovery.action",
            PREFIX + "recovery.route",
            PREFIX + "recovery.routeDecision",
            PREFIX + "recovery.failSoft",
            PREFIX + "recovery.retry",
            PREFIX + "recovery.quarantine",
            PREFIX + "recovery.policy.maxRounds",
            PREFIX + "recovery.policy.minCitations",
            PREFIX + "errorBreak.risk",
            PREFIX + "errorBreak.onnxEnabled",
            PREFIX + "errorBreak.webTopK",
            PREFIX + "errorBreak.officialSourcesOnly",
            PREFIX + "errorBreak.cacheOnly",
            PREFIX + "suspectPayload.isolated",
            PREFIX + "suspectPayload.fingerprint",
            PREFIX + "suspectPayload.stage",
            PREFIX + "suspectPayload.reason",
            PREFIX + "suspectPayload.route",
            PREFIX + "suspectPayload.retry",
            PREFIX + "suspectPayload.quarantine",
            PREFIX + "suspectPayload.failSoft",
            PREFIX + "cfvm.patternId",
            PREFIX + "cfvm.skippedReason");

    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;
    private final ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider;
    private final ObjectProvider<RecoveryPolicy> recoveryPolicyProvider;
    private final ThreadLocal<Snapshot> previous = new ThreadLocal<>();

    public TraceMemoryFingerprintProbe(ObjectProvider<DebugEventStore> debugEventStoreProvider,
                                       ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider,
                                       ObjectProvider<RecoveryPolicy> recoveryPolicyProvider) {
        this.debugEventStoreProvider = debugEventStoreProvider;
        this.rawMatrixBufferProvider = rawMatrixBufferProvider;
        this.recoveryPolicyProvider = recoveryPolicyProvider;
    }

    public Checkpoint checkpoint(String stage, String source, Map<String, Object> rawMemory) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeSource = SafeRedactor.traceLabelOrFallback(source, "unknown");
        try {
            Snapshot current = snapshot(rawMemory);
            Snapshot before = "raw_snapshot".equals(safeStage) ? null : previous.get();
            Delta delta = Delta.between(before, current);
            Trigger trigger = detectTrigger(safeStage, rawMemory, current, before, delta);

            publishBaseTrace(safeStage, safeSource, rawMemory, current, before, delta, trigger);
            if (trigger.triggered()) {
                recover(safeStage, safeSource, current, trigger);
            } else {
                clearRecoveryState();
            }

            previous.set(current);
            String recoveryAction = trigger.triggered()
                    ? String.valueOf(TraceStore.get(PREFIX + "recovery.action"))
                    : "";
            return new Checkpoint(
                    safeStage,
                    safeSource,
                    current.fingerprint(),
                    before == null ? null : before.fingerprint(),
                    delta.addedCount(),
                    delta.removedCount(),
                    delta.changedCount(),
                    delta.droppedBreadcrumbCount(),
                    trigger.triggered(),
                    trigger.reason(),
                    recoveryAction);
        } catch (RuntimeException ex) {
            TraceStore.put(PREFIX + "probe.suppressed", true);
            TraceStore.put(PREFIX + "probe.suppressed.stage", safeStage);
            TraceStore.put(PREFIX + "probe.suppressed.errorHash", SafeRedactor.hashValue(messageOf(ex)));
            return new Checkpoint(safeStage, safeSource, "", null, 0, 0, 0, 0, false,
                    "probe_exception", "");
        }
    }

    private Snapshot snapshot(Map<String, Object> rawMemory) {
        Map<String, Object> trace = TraceStore.getAll();
        DebugEventStore debugStore = providerValue(debugEventStoreProvider, "debugEventStore");
        RawMatrixBuffer rawMatrixBuffer = providerValue(rawMatrixBufferProvider, "rawMatrixBuffer");

        Map<String, String> signatures = new TreeMap<>();
        int memoryCount = appendSignatures("memory", rawMemory, signatures);
        int traceCount = appendSignatures("trace", trace, signatures);
        int debugCount = appendDebugSignatures(debugStore, signatures);
        int cfvmCount = appendCfvmSignatures(rawMatrixBuffer, signatures);
        int supabaseShadowCount = appendSupabaseShadowSignatures(rawMemory, trace, signatures);
        SupabaseShadowStatus supabaseShadowStatus = supabaseShadowStatus(rawMemory, trace, supabaseShadowCount);

        String canonical = canonical(signatures);
        String fingerprint = SafeRedactor.hashValue(canonical + "|crc:" + crc32(canonical));
        return new Snapshot(
                fingerprint == null ? "hash:empty" : fingerprint,
                signatures,
                memoryPayloadCount(rawMemory),
                traceCount,
                debugCount,
                cfvmCount,
                supabaseShadowCount,
                supabaseShadowStatus);
    }

    private static int appendSignatures(String prefix, Object value, Map<String, String> out) {
        if (value == null) {
            out.put(prefix, "null");
            return 0;
        }
        int before = out.size();
        flatten(prefix, value, out, 0);
        return out.size() - before;
    }

    private static void flatten(String key, Object value, Map<String, String> out, int depth) {
        String safeKey = SafeRedactor.traceLabelOrFallback(key, "field");
        if (depth > 5) {
            out.put(safeKey, "depth-limit");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.put(safeKey, "map:size:" + map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                flatten(safeKey + "." + entry.getKey(), entry.getValue(), out, depth + 1);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            out.put(safeKey, "collection:size:" + collection.size());
            int i = 0;
            for (Object item : collection) {
                if (i >= 40) {
                    out.put(safeKey + "._truncated", "true");
                    break;
                }
                flatten(safeKey + "." + i, item, out, depth + 1);
                i++;
            }
            return;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            out.put(safeKey, value.getClass().getSimpleName() + ":" + value);
            return;
        }
        String raw = String.valueOf(value);
        out.put(safeKey, "str:len:" + raw.length() + ":hash:" + SafeRedactor.hash12(raw));
    }

    private static int appendDebugSignatures(DebugEventStore debugStore, Map<String, String> out) {
        if (debugStore == null) {
            out.put("debug.events", "missing");
            return 0;
        }
        List<DebugEvent> events = debugStore.list(40);
        int i = 0;
        for (DebugEvent event : events) {
            if (event == null) {
                continue;
            }
            String key = "debug.event." + i++;
            out.put(key + ".probe", String.valueOf(event.probe()));
            out.put(key + ".level", String.valueOf(event.level()));
            out.put(key + ".fingerprint", SafeRedactor.hashValue(event.fingerprint()));
            out.put(key + ".where", SafeRedactor.traceLabelOrFallback(event.where(), "unknown"));
        }
        out.put("debug.events.count", String.valueOf(i));
        return i;
    }

    private static int appendCfvmSignatures(RawMatrixBuffer rawMatrixBuffer, Map<String, String> out) {
        if (rawMatrixBuffer == null) {
            out.put("cfvm.rawMatrixBuffer", "missing");
            return 0;
        }
        List<RawMatrixBuffer.Entry> entries = rawMatrixBuffer.snapshot();
        int i = 0;
        for (RawMatrixBuffer.Entry entry : entries) {
            if (entry == null) {
                continue;
            }
            String key = "cfvm.rawMatrixBuffer." + i++;
            out.put(key + ".patternId", String.valueOf(entry.patternId()));
            out.put(key + ".traceSize", String.valueOf(entry.traceSize()));
            out.put(key + ".signatureLength", String.valueOf(entry.signatureLength()));
        }
        out.put("cfvm.rawMatrixBuffer.count", String.valueOf(i));
        return i;
    }

    private static int appendSupabaseShadowSignatures(Map<String, Object> rawMemory,
                                                      Map<String, Object> trace,
                                                      Map<String, String> out) {
        Map<String, Object> shadow = new LinkedHashMap<>();
        collectSupabaseShadow("memory", rawMemory, shadow);
        collectSupabaseShadow("trace", trace, shadow);
        if (shadow.isEmpty()) {
            out.put("supabaseShadow.count", "0");
            return 0;
        }
        flatten("supabaseShadow", shadow, out, 0);
        out.put("supabaseShadow.count", String.valueOf(shadow.size()));
        return shadow.size();
    }

    private static void collectSupabaseShadow(String prefix, Object value, Map<String, Object> out) {
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey());
            String lower = key.toLowerCase(Locale.ROOT);
            String context = (String.valueOf(prefix) + "." + key).toLowerCase(Locale.ROOT);
            if (context.contains("supabase") || context.contains("shadow") || lower.contains("dlq")) {
                out.put(prefix + "." + SafeRedactor.traceLabelOrFallback(key, "field"), entry.getValue());
            }
            collectSupabaseShadow(prefix + "." + key, entry.getValue(), out);
        }
    }

    private static SupabaseShadowStatus supabaseShadowStatus(Map<String, Object> rawMemory,
                                                             Map<String, Object> trace,
                                                             int supabaseShadowCount) {
        Map<String, Object> shadow = new LinkedHashMap<>();
        collectSupabaseShadow("memory", rawMemory, shadow);
        collectSupabaseShadow("trace", trace, shadow);
        String artifact = firstLabelBySuffix(shadow, "schemaSnapshotArtifact", "snapshotArtifact");
        if (artifact.isBlank()) {
            artifact = supabaseShadowCount > 0 ? "present" : "absent";
        }
        String projectScope = firstLabelBySuffix(shadow,
                "projectScopeStatus",
                "projectScope.status",
                "projectScopeSource");
        if (projectScope.isBlank()) {
            projectScope = supabaseShadowCount > 0 ? "unknown" : "absent";
        }
        boolean available = firstBooleanBySuffix(shadow, false, "schemaSnapshotAvailable", "snapshotAvailable");
        boolean complete = firstBooleanBySuffix(shadow, false, "schemaSnapshotComplete", "snapshotComplete");
        boolean readOnly = firstBooleanBySuffix(shadow, false, "readOnly");
        boolean mutationAllowed = firstBooleanBySuffix(shadow, false, "mutationAllowed");
        int evidenceNeededCount = firstIntBySuffix(shadow, 0, "evidenceNeededCount", "evidence_needed.count");
        String nextAction = firstLabelBySuffix(shadow, "nextAction", "projectScope.nextAction");
        if (nextAction.isBlank()) {
            nextAction = supabaseShadowCount > 0 ? "inspect_supabase_shadow_snapshot" : "collect_supabase_shadow_snapshot";
        }
        return new SupabaseShadowStatus(
                artifact,
                projectScope,
                available,
                complete,
                readOnly,
                mutationAllowed,
                evidenceNeededCount,
                nextAction);
    }

    private static String firstLabelBySuffix(Map<String, Object> values, String... suffixes) {
        Object value = firstValueBySuffix(values, suffixes);
        if (value == null) {
            return "";
        }
        return SafeRedactor.traceLabelOrFallback(value, "");
    }

    private static boolean firstBooleanBySuffix(Map<String, Object> values, boolean fallback, String... suffixes) {
        Object value = firstValueBySuffix(values, suffixes);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof CharSequence s) {
            String text = s.toString().trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return fallback;
    }

    private static int firstIntBySuffix(Map<String, Object> values, int fallback, String... suffixes) {
        Object value = firstValueBySuffix(values, suffixes);
        long parsed = toLong(value);
        if (parsed <= 0L) {
            return fallback;
        }
        return (int) Math.min(Integer.MAX_VALUE, parsed);
    }

    private static Object firstValueBySuffix(Map<String, Object> values, String... suffixes) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String suffix : suffixes) {
            String normalizedSuffix = suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
            if (normalizedSuffix.isBlank()) {
                continue;
            }
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                if (key.equals(normalizedSuffix) || key.endsWith("." + normalizedSuffix)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private Trigger detectTrigger(String stage,
                                  Map<String, Object> rawMemory,
                                  Snapshot current,
                                  Snapshot before,
                                  Delta delta) {
        Map<String, Object> trace = TraceStore.getAll();
        String reason = lowerTrace("prompt.memory.compressor.reason", trace);
        String composerSkipped = lowerTrace("prompt.memory.composer.skippedReason", trace);
        boolean rawStage = "raw_snapshot".equals(stage);

        if (containsContamination(rawMemory) || (!rawStage && reason.contains("contamination"))
                || lowerTrace("context.contamination", trace).contains("true")) {
            return new Trigger(true, "context_contamination", FailureClass.POLICY, NovaErrorBreakGuard.Risk.BREAK);
        }
        if (rawStage) {
            return new Trigger(false, "", FailureClass.NONE, NovaErrorBreakGuard.Risk.OK);
        }
        if (reason.contains("all_lines_dropped")
                || (before != null && before.memoryCount() > 0 && current.memoryCount() == 0
                && isRefinementStage(stage))) {
            return new Trigger(true, "loader_starvation", FailureClass.DATA, NovaErrorBreakGuard.Risk.WARN);
        }
        if (afterFilterStarved(trace)) {
            return new Trigger(true, "after_filter_starvation", FailureClass.DATA, NovaErrorBreakGuard.Risk.WARN);
        }
        if (delta.droppedBreadcrumbCount() > 0 || lowerTrace("trace.snapshot.capture.skipped", trace).contains("true")) {
            return new Trigger(true, "dropped_breadcrumb", FailureClass.DATA, NovaErrorBreakGuard.Risk.WARN);
        }
        if (composerSkipped.contains("exception_fail_soft")
                || lowerTrace("silent.failure", trace).contains("true")
                || lowerTrace("fault.masked", trace).contains("true")) {
            return new Trigger(true, "silent_failure", FailureClass.LOGIC, NovaErrorBreakGuard.Risk.WARN);
        }
        return new Trigger(false, "", FailureClass.NONE, NovaErrorBreakGuard.Risk.OK);
    }

    private static boolean isRefinementStage(String stage) {
        String s = stage == null ? "" : stage.toLowerCase(Locale.ROOT);
        return s.contains("refinement") || s.contains("load");
    }

    private static boolean afterFilterStarved(Map<String, Object> trace) {
        long raw = firstPositive(trace, "context.candidates.raw", "outCount", "returnedCount",
                "web.naver.returnedCount", "web.brave.returnedCount", "web.serpapi.returnedCount");
        long after = firstLong(trace, "context.candidates.afterFilter", "afterFilterCount",
                "stageCountsSelectedFromOut", "merged");
        return raw > 0 && after == 0;
    }

    private static long firstPositive(Map<String, Object> trace, String... keys) {
        long best = 0;
        for (String key : keys) {
            best = Math.max(best, toLong(trace.get(key)));
        }
        return best;
    }

    private static long firstLong(Map<String, Object> trace, String... keys) {
        for (String key : keys) {
            Object value = trace.get(key);
            if (value != null) {
                return toLong(value);
            }
        }
        return 0L;
    }

    private void publishBaseTrace(String stage,
                                  String source,
                                  Map<String, Object> rawMemory,
                                  Snapshot current,
                                  Snapshot before,
                                  Delta delta,
                                  Trigger trigger) {
        String checkpointPhase = checkpointPhase(rawMemory, source);
        long checkpointIndex = TraceStore.nextSequence("traceMemory.checkpoint");
        TraceStore.put(PREFIX + "checkpoint.stage", stage);
        TraceStore.put(PREFIX + "checkpoint.source", source);
        TraceStore.put(PREFIX + "checkpoint.phase", checkpointPhase);
        TraceStore.put(PREFIX + "checkpoint.index", checkpointIndex);
        TraceStore.put(PREFIX + "fingerprint.current", current.fingerprint());
        TraceStore.put(PREFIX + "fingerprint.previous", before == null ? "" : before.fingerprint());
        TraceStore.put(PREFIX + "delta.addedCount", delta.addedCount());
        TraceStore.put(PREFIX + "delta.removedCount", delta.removedCount());
        TraceStore.put(PREFIX + "delta.changedCount", delta.changedCount());
        TraceStore.put(PREFIX + "delta.changed",
                delta.addedCount() > 0 || delta.removedCount() > 0 || delta.changedCount() > 0);
        TraceStore.put(PREFIX + "delta.droppedBreadcrumbCount", delta.droppedBreadcrumbCount());
        TraceStore.put(PREFIX + "rawSnapshot.memoryCount", current.memoryCount());
        TraceStore.put(PREFIX + "rawSnapshot.traceCount", current.traceCount());
        TraceStore.put(PREFIX + "rawSnapshot.debugCount", current.debugCount());
        TraceStore.put(PREFIX + "rawSnapshot.cfvmCount", current.cfvmCount());
        TraceStore.put(PREFIX + "rawSnapshot.supabaseShadowCount", current.supabaseShadowCount());
        publishSupabaseShadowStatus(PREFIX + "rawSnapshot.", current.supabaseShadowStatus());
        TraceStore.put(PREFIX + "triggered", trigger.triggered());
        TraceStore.put(PREFIX + "trigger.reason", trigger.reason());
        TraceStore.append(PREFIX + "checkpoint.history",
                checkpointHistory(checkpointIndex, stage, source, checkpointPhase, current, before, delta, trigger));
        TraceStore.put(PREFIX + "checkpoint.historySize", checkpointIndex);
        publishVirtualCheckpoint(checkpointIndex, stage, source, checkpointPhase, current, before, delta, trigger);
    }

    private static void publishVirtualCheckpoint(long index,
                                                 String stage,
                                                 String source,
                                                 String checkpointPhase,
                                                 Snapshot current,
                                                 Snapshot before,
                                                 Delta delta,
                                                 Trigger trigger) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String baseKey = PREFIX + "virtualCheckpoint." + safeStage;
        TraceStore.put(PREFIX + "virtualCheckpoint.latestKey", baseKey);
        TraceStore.put(PREFIX + "virtualCheckpoint.latestStage", safeStage);
        TraceStore.put(PREFIX + "virtualCheckpoint.latestPhase", checkpointPhase);
        TraceStore.put(baseKey + ".index", index);
        TraceStore.put(baseKey + ".stage", safeStage);
        TraceStore.put(baseKey + ".source", SafeRedactor.traceLabelOrFallback(source, "unknown"));
        TraceStore.put(baseKey + ".phase", SafeRedactor.traceLabelOrFallback(checkpointPhase, "unknown"));
        TraceStore.put(baseKey + ".fingerprint", current.fingerprint());
        TraceStore.put(baseKey + ".previousFingerprint", before == null ? "" : before.fingerprint());
        TraceStore.put(baseKey + ".delta.addedCount", delta.addedCount());
        TraceStore.put(baseKey + ".delta.removedCount", delta.removedCount());
        TraceStore.put(baseKey + ".delta.changedCount", delta.changedCount());
        TraceStore.put(baseKey + ".delta.changed",
                delta.addedCount() > 0 || delta.removedCount() > 0 || delta.changedCount() > 0);
        TraceStore.put(baseKey + ".delta.droppedBreadcrumbCount", delta.droppedBreadcrumbCount());
        TraceStore.put(baseKey + ".memoryCount", current.memoryCount());
        TraceStore.put(baseKey + ".traceCount", current.traceCount());
        TraceStore.put(baseKey + ".debugCount", current.debugCount());
        TraceStore.put(baseKey + ".cfvmCount", current.cfvmCount());
        TraceStore.put(baseKey + ".supabaseShadowCount", current.supabaseShadowCount());
        publishSupabaseShadowStatus(baseKey + ".", current.supabaseShadowStatus());
        TraceStore.put(baseKey + ".triggered", trigger.triggered());
        TraceStore.put(baseKey + ".trigger.reason", SafeRedactor.traceLabelOrFallback(trigger.reason(), ""));
    }

    private static void publishSupabaseShadowStatus(String prefix, SupabaseShadowStatus status) {
        if (status == null) {
            return;
        }
        TraceStore.put(prefix + "supabaseShadowArtifact", status.artifact());
        TraceStore.put(prefix + "supabaseShadowProjectScope", status.projectScope());
        TraceStore.put(prefix + "supabaseShadowAvailable", status.available());
        TraceStore.put(prefix + "supabaseShadowComplete", status.complete());
        TraceStore.put(prefix + "supabaseShadowReadOnly", status.readOnly());
        TraceStore.put(prefix + "supabaseShadowMutationAllowed", status.mutationAllowed());
        TraceStore.put(prefix + "supabaseShadowEvidenceNeededCount", status.evidenceNeededCount());
        TraceStore.put(prefix + "supabaseShadowNextAction", status.nextAction());
    }

    private static Map<String, Object> checkpointHistory(long index,
                                                         String stage,
                                                         String source,
                                                         String checkpointPhase,
                                                         Snapshot current,
                                                         Snapshot before,
                                                         Delta delta,
                                                         Trigger trigger) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", index);
        out.put("stage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        out.put("source", SafeRedactor.traceLabelOrFallback(source, "unknown"));
        out.put("phase", SafeRedactor.traceLabelOrFallback(checkpointPhase, "unknown"));
        out.put("fingerprint", current.fingerprint());
        out.put("previousFingerprint", before == null ? "" : before.fingerprint());
        out.put("deltaAddedCount", delta.addedCount());
        out.put("deltaRemovedCount", delta.removedCount());
        out.put("deltaChangedCount", delta.changedCount());
        out.put("droppedBreadcrumbCount", delta.droppedBreadcrumbCount());
        out.put("memoryCount", current.memoryCount());
        out.put("traceCount", current.traceCount());
        out.put("debugCount", current.debugCount());
        out.put("cfvmCount", current.cfvmCount());
        out.put("supabaseShadowCount", current.supabaseShadowCount());
        out.put("triggered", trigger.triggered());
        out.put("triggerReason", SafeRedactor.traceLabelOrFallback(trigger.reason(), ""));
        return out;
    }

    private void recover(String stage, String source, Snapshot current, Trigger trigger) {
        RecoveryPolicy recoveryPolicy = providerValue(recoveryPolicyProvider, "recoveryPolicy");
        if (recoveryPolicy == null) {
            recoveryPolicy = new RecoveryPolicy();
        }
        RecoveryAction action = recoveryPolicy.resolve(trigger.failureClass());
        NovaErrorBreakGuard.Policy guardPolicy = new NovaErrorBreakGuard().decide(trigger.risk());
        boolean quarantine = "context_contamination".equals(trigger.reason()) || trigger.risk() == NovaErrorBreakGuard.Risk.BREAK;
        boolean retry = !quarantine && action != RecoveryAction.ESCALATE;
        String route = recoveryRoute(quarantine, retry);
        String routeDecision = recoveryRouteDecision(trigger, action, guardPolicy, route);

        TraceStore.put(PREFIX + "recoveryMode", true);
        TraceStore.put(PREFIX + "recovery.failureClass", trigger.failureClass().name());
        TraceStore.put(PREFIX + "recovery.action", action.name());
        TraceStore.put(PREFIX + "recovery.route", route);
        TraceStore.put(PREFIX + "recovery.routeDecision", routeDecision);
        TraceStore.put(PREFIX + "recovery.failSoft", true);
        TraceStore.put(PREFIX + "recovery.retry", retry);
        TraceStore.put(PREFIX + "recovery.quarantine", quarantine);
        TraceStore.put(PREFIX + "recovery.policy.maxRounds", recoveryPolicy.maxRounds());
        TraceStore.put(PREFIX + "recovery.policy.minCitations", recoveryPolicy.minCitations());
        TraceStore.put(PREFIX + "errorBreak.risk", trigger.risk().name());
        TraceStore.put(PREFIX + "errorBreak.onnxEnabled", guardPolicy.onnxEnabled);
        TraceStore.put(PREFIX + "errorBreak.webTopK", guardPolicy.webTopK);
        TraceStore.put(PREFIX + "errorBreak.officialSourcesOnly", guardPolicy.officialSourcesOnly);
        TraceStore.put(PREFIX + "errorBreak.cacheOnly", guardPolicy.cacheOnly);
        TraceStore.put(PREFIX + "suspectPayload.isolated", true);
        TraceStore.put(PREFIX + "suspectPayload.fingerprint", current.fingerprint());
        TraceStore.put(PREFIX + "suspectPayload.stage", stage);
        TraceStore.put(PREFIX + "suspectPayload.reason", trigger.reason());
        TraceStore.put(PREFIX + "suspectPayload.route", route);
        TraceStore.put(PREFIX + "suspectPayload.retry", retry);
        TraceStore.put(PREFIX + "suspectPayload.quarantine", quarantine);
        TraceStore.put(PREFIX + "suspectPayload.failSoft", true);

        offerCfvm(current, trigger);
        emitDebugEvent(stage, source, current, trigger, action, guardPolicy, quarantine, retry, route, routeDecision);
    }

    private static void clearRecoveryState() {
        TraceStore.put(PREFIX + "recoveryMode", false);
        TraceStore.put(PREFIX + "cfvm.offered", false);
        for (String key : RECOVERY_STATE_KEYS) {
            TraceStore.put(key, null);
        }
    }

    private static String recoveryRoute(boolean quarantine, boolean retry) {
        if (quarantine) {
            return "quarantine_retry_failsoft";
        }
        return retry ? "retry_failsoft" : "failsoft_only";
    }

    private static String recoveryRouteDecision(Trigger trigger,
                                                RecoveryAction action,
                                                NovaErrorBreakGuard.Policy guardPolicy,
                                                String route) {
        String risk = trigger == null || trigger.risk() == null ? "unknown" : trigger.risk().name().toLowerCase(Locale.ROOT);
        String recovery = action == null ? "unknown" : action.name().toLowerCase(Locale.ROOT);
        String mode = guardPolicy != null && guardPolicy.cacheOnly ? "cache_only" : "live_failsoft";
        return SafeRedactor.traceLabelOrFallback(route + "_" + recovery + "_" + risk + "_" + mode,
                "trace_memory_recovery");
    }

    private void offerCfvm(Snapshot current, Trigger trigger) {
        RawMatrixBuffer rawMatrixBuffer = providerValue(rawMatrixBufferProvider, "rawMatrixBuffer");
        if (rawMatrixBuffer == null) {
            TraceStore.put(PREFIX + "cfvm.offered", false);
            TraceStore.put(PREFIX + "cfvm.skippedReason", "raw_matrix_buffer_missing");
            return;
        }
        long patternId = crc32(trigger.reason() + "|" + current.fingerprint());
        rawMatrixBuffer.offer(new RawMatrixBuffer.Entry(
                patternId,
                current.signatures().size(),
                current.fingerprint().length()));
        TraceStore.put(PREFIX + "cfvm.offered", true);
        TraceStore.put(PREFIX + "cfvm.patternId", patternId);
    }

    private void emitDebugEvent(String stage,
                                String source,
                                Snapshot current,
                                Trigger trigger,
                                RecoveryAction action,
                                NovaErrorBreakGuard.Policy guardPolicy,
                                boolean quarantine,
                                boolean retry,
                                String route,
                                String routeDecision) {
        DebugEventStore debugStore = providerValue(debugEventStoreProvider, "debugEventStore");
        if (debugStore == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stage", stage);
        data.put("source", source);
        data.put("triggerReason", trigger.reason());
        data.put("recoveryAction", action.name());
        data.put("recoveryRoute", route);
        data.put("routeDecision", routeDecision);
        data.put("failureClass", trigger.failureClass().name());
        data.put("risk", trigger.risk().name());
        data.put("fingerprint", current.fingerprint());
        data.put("memoryCount", current.memoryCount());
        data.put("traceCount", current.traceCount());
        data.put("supabaseShadowCount", current.supabaseShadowCount());
        data.put("suspectPayloadIsolated", true);
        data.put("quarantine", quarantine);
        data.put("retry", retry);
        data.put("cacheOnly", guardPolicy.cacheOnly);
        debugStore.emit(
                DebugProbeType.TRACE_MEMORY,
                DebugEventLevel.WARN,
                "trace_memory:" + trigger.reason() + ":" + action.name(),
                "trace-memory checkpoint triggered",
                "TraceMemoryFingerprintProbe",
                data,
                null);
    }

    private static boolean containsContamination(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry != null && (containsContamination(entry.getKey()) || containsContamination(entry.getValue()))) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (containsContamination(item)) {
                    return true;
                }
            }
            return false;
        }
        String text = String.valueOf(value).toLowerCase(Locale.ROOT);
        return text.contains("history_context_contamination")
                || text.contains("context contamination")
                || text.contains("build log")
                || text.contains(".gradle")
                || text.contains("node_modules")
                || text.contains("__patch_drop__")
                || text.contains("legacy duplicate");
    }

    private static String checkpointPhase(Map<String, Object> rawMemory, String fallback) {
        Object value = rawMemory == null ? null : rawMemory.get("phase");
        if (value == null) {
            return SafeRedactor.traceLabelOrFallback(fallback, "unknown");
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        String normalized = switch (text) {
            case "before_memory_compression" -> "before.memory.compression";
            case "after_memory_compression" -> "after.memory.compression";
            case "memory_only_no_candidates" -> "memory.only.no.candidates";
            case "empty_candidates" -> "empty.candidates";
            case "after_context_filter" -> "after.context.filter";
            case "prompt_builder" -> "prompt.builder";
            case "agent_visible_debug_evidence" -> "agent.visible.debug.evidence";
            case "trace_memory_self_probe" -> "trace.memory.self.probe";
            case "memory_loader_raw" -> "memory.loader.raw";
            case "memory_loader_after_recent_filter" -> "memory.loader.after_recent_filter";
            case "memory_loader_after_section_assembly" -> "memory.loader.after_section_assembly";
            case "memory_loader_assembled" -> "memory.loader.assembled";
            default -> "";
        };
        if (!normalized.isBlank()) {
            return normalized;
        }
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }

    private static int memoryPayloadCount(Map<String, Object> rawMemory) {
        if (rawMemory == null || rawMemory.isEmpty()) {
            return 0;
        }
        int count = 0;
        Object memoryCtx = rawMemory.get("memoryCtx");
        if (memoryCtx != null && !String.valueOf(memoryCtx).isBlank()) {
            count++;
        }
        Object supabaseShadow = rawMemory.get("supabaseShadow");
        if (supabaseShadow instanceof Map<?, ?> map) {
            count += mapPositiveCount(map);
        }
        return count;
    }

    private static int mapPositiveCount(Map<?, ?> map) {
        int count = 0;
        for (Object value : map.values()) {
            if (value instanceof Number n && n.longValue() > 0) {
                count++;
            } else if (value instanceof CharSequence s && !s.toString().isBlank() && !"0".equals(s.toString().trim())) {
                count++;
            } else if (value instanceof Map<?, ?> child) {
                count += mapPositiveCount(child);
            }
        }
        return count;
    }

    private static String lowerTrace(String key, Map<String, Object> trace) {
        Object value = trace.get(key);
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    private static String canonical(Map<String, String> signatures) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : signatures.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return out.toString();
    }

    private static long crc32(String value) {
        CRC32 crc = new CRC32();
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return crc.getValue();
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().mapToLong(TraceMemoryFingerprintProbe::toLong).sum();
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof CharSequence s) {
            try {
                return Long.parseLong(s.toString().trim());
            } catch (NumberFormatException ignored) {
                traceNumberSuppressed("toLong", ignored);
                return 0L;
            }
        }
        return 0L;
    }

    private static void traceNumberSuppressed(String stage, RuntimeException ex) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = ex == null ? "RuntimeException" : ex.getClass().getSimpleName();
        String errorHash = SafeRedactor.hashValue(messageOf(ex));
        TraceStore.put(PREFIX + "number.suppressed", true);
        TraceStore.put(PREFIX + "number.suppressed.stage", safeStage);
        TraceStore.put(PREFIX + "number.suppressed." + safeStage, true);
        TraceStore.put(PREFIX + "number.suppressed." + safeStage + ".errorType", errorType);
        TraceStore.put(PREFIX + "number.suppressed." + safeStage + ".errorHash", errorHash);
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static <T> T providerValue(ObjectProvider<T> provider, String stage) {
        if (provider == null) {
            return null;
        }
        try {
            return provider.getIfAvailable();
        } catch (RuntimeException ex) {
            traceProviderSuppressed(stage, ex);
            return null;
        }
    }

    private static void traceProviderSuppressed(String stage, RuntimeException ex) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = ex == null ? "RuntimeException" : ex.getClass().getSimpleName();
        String errorHash = SafeRedactor.hashValue(messageOf(ex));
        TraceStore.put(PREFIX + "provider.suppressed", true);
        TraceStore.put(PREFIX + "provider.suppressed.stage", safeStage);
        TraceStore.put(PREFIX + "provider.suppressed." + safeStage, true);
        TraceStore.put(PREFIX + "provider.suppressed." + safeStage + ".errorType", errorType);
        TraceStore.put(PREFIX + "provider.suppressed." + safeStage + ".errorHash", errorHash);
        TraceStore.append(PREFIX + "provider.suppressed.history", Map.of(
                "stage", safeStage,
                "errorType", errorType,
                "errorHash", errorHash == null ? "" : errorHash));
    }

    public record Checkpoint(String stage,
                             String source,
                             String fingerprint,
                             String previousFingerprint,
                             int addedCount,
                             int removedCount,
                             int changedCount,
                             int droppedBreadcrumbCount,
                             boolean triggered,
                             String triggerReason,
                             String recoveryAction) {
    }

    private record Snapshot(String fingerprint,
                            Map<String, String> signatures,
                            int memoryCount,
                            int traceCount,
                            int debugCount,
                            int cfvmCount,
                            int supabaseShadowCount,
                            SupabaseShadowStatus supabaseShadowStatus) {
    }

    private record SupabaseShadowStatus(String artifact,
                                        String projectScope,
                                        boolean available,
                                        boolean complete,
                                        boolean readOnly,
                                        boolean mutationAllowed,
                                        int evidenceNeededCount,
                                        String nextAction) {
    }

    private record Trigger(boolean triggered,
                           String reason,
                           FailureClass failureClass,
                           NovaErrorBreakGuard.Risk risk) {
    }

    private record Delta(int addedCount,
                         int removedCount,
                         int changedCount,
                         int droppedBreadcrumbCount) {
        static Delta between(Snapshot before, Snapshot current) {
            if (before == null || current == null) {
                return new Delta(0, 0, 0, 0);
            }
            Set<String> beforeKeys = new TreeSet<>(before.signatures().keySet());
            Set<String> currentKeys = new TreeSet<>(current.signatures().keySet());
            int added = 0;
            int removed = 0;
            int changed = 0;
            int droppedBreadcrumb = 0;

            for (String key : currentKeys) {
                if (!beforeKeys.contains(key)) {
                    added++;
                } else if (!String.valueOf(before.signatures().get(key)).equals(String.valueOf(current.signatures().get(key)))) {
                    changed++;
                }
            }
            for (String key : beforeKeys) {
                if (!currentKeys.contains(key)) {
                    removed++;
                    if (isBreadcrumbKey(key)) {
                        droppedBreadcrumb++;
                    }
                }
            }
            return new Delta(added, removed, changed, droppedBreadcrumb);
        }

        private static boolean isBreadcrumbKey(String key) {
            String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
            return lower.contains("breadcrumb");
        }
    }
}
