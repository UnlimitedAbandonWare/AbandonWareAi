package com.example.lms.infra.resilience;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized helper to record "aux stage blocked" decisions into TraceStore.
 *
 * <p>Goals:
 * <ul>
 *   <li>Use stable keys for downstream analysis (TraceStore key-based assertions, dashboards, etc.)</li>
 *   <li>Record the FIRST blocked reason once (putIfAbsent), while appending detailed events over time</li>
 *   <li>Keep reason selection deterministic via an explicit priority policy ({@link AuxBlockedReason#priority()})</li>
 * </ul>
 */
public final class AuxBlockTracker {

    private AuxBlockTracker() {
        // util
    }

    // ---- Global keys (first occurrence is sticky) ----

    public static final String ANY_BLOCKED_KEY = "aux.blocked";
    public static final String ANY_BLOCKED_STAGE_KEY = "aux.blocked.stage";
    public static final String ANY_BLOCKED_REASON_KEY = "aux.blocked.reason";
    public static final String ANY_BLOCKED_ID_KEY = "aux.blocked.id";
    public static final String ANY_BLOCKED_FIRST_KEY = "aux.blocked.first";
    public static final String ANY_BLOCKED_LAST_KEY = "aux.blocked.last";
    public static final String ANY_BLOCKED_EVENTS_KEY = "aux.blocked.events";

    // ---- Noise override keys (sticky) ----

    public static final String ANY_NOISE_OVERRIDE = "aux.noiseOverride";
    public static final String ANY_NOISE_OVERRIDE_STAGE_KEY = "aux.noiseOverride.stage";
    public static final String ANY_NOISE_OVERRIDE_KEEP_PROB_KEY = "aux.noiseOverride.keepProb";
    public static final String ANY_NOISE_OVERRIDE_FIRST_KEY = "aux.noiseOverride.first";
    public static final String ANY_NOISE_OVERRIDE_LAST_KEY = "aux.noiseOverride.last";
    public static final String ANY_NOISE_EVENTS_KEY = "aux.noiseOverride.events";


    // ---- Event schema ----

    /**
     * Event type value for entries appended to {@link #ANY_BLOCKED_EVENTS_KEY}.
     * Stored as a string code from {@link AuxBlockEventType} so downstream tooling
     * can rely on a stable identifier.
     */
    public static final String EVENT_TYPE_STAGE_BLOCKED = AuxBlockEventType.STAGE_BLOCKED.code();

    public static final String EVENT_TYPE_STAGE_NOISE_OVERRIDE = AuxBlockEventType.STAGE_NOISE_OVERRIDE.code();

    // ---- Snapshot policy (all currently OPEN breakers) ----

    /**
     * Policy: when to attach the (potentially larger) "allOpenBreakersSnapshot" object
     * into each aux.blocked event.
     *
     * <p>Supported values: NEVER, BREAKER_OPEN_ONLY, MULTI_OPEN_ONLY (default), ALWAYS</p>
     */
    public static final String ALL_OPEN_SNAPSHOT_POLICY_PROP = "aux.blocked.allOpenSnapshot.policy";

    /** Backward-compatible alias (older drafts used a longer key). */
    public static final String ALL_OPEN_SNAPSHOT_POLICY_PROP_ALIAS = "aux.blocked.allOpenBreakersSnapshot.policy";

    // ---- Keys policy (all currently OPEN breakers: keys/count) ----

    /**
     * UAW: Keys are ALWAYS attached in the event payload.
     *
     * <p>
     * This is intentionally fixed at code-level to prevent operational misconfiguration
     * from disabling the lightweight "which breakers are open" breadcrumbs.
     * </p>
     *
     * <p>
     * The legacy system properties are kept for backward compatibility with older
     * configs/tests but are ignored.
     * </p>
     */
    @Deprecated
    public static final String ALL_OPEN_KEYS_POLICY_PROP = "aux.blocked.allOpenKeys.policy";

    /** Backward-compatible alias (older drafts used a longer key). Ignored. */
    @Deprecated
    public static final String ALL_OPEN_KEYS_POLICY_PROP_ALIAS = "aux.blocked.allOpenBreakersKeys.policy";

    // Event fields
    /** Lightweight: number of breakers currently OPEN (always included; may be 0). */
    public static final String EVENT_ALL_OPEN_BREAKERS_COUNT = "allOpenBreakersCount";
    /** Lightweight: keys of breakers currently OPEN (always included; may be empty). */
    public static final String EVENT_ALL_OPEN_BREAKERS_KEYS = "allOpenBreakersKeys";
    /** Optional: full snapshot map of all OPEN breakers (policy-controlled). */
    public static final String EVENT_ALL_OPEN_BREAKERS_SNAPSHOT = "allOpenBreakersSnapshot";

    // ---- Per-stage keys ----

    private static final String STAGE_BLOCKED_KEY_FMT = "aux.%s.blocked";
    private static final String STAGE_BLOCKED_REASON_KEY_FMT = "aux.%s.blocked.reason";
    private static final String STAGE_BLOCKED_BREAKER_KEY_FMT = "aux.%s.blocked.breakerKey";
    private static final String STAGE_BLOCKED_EVENTTYPE_KEY_FMT = "aux.%s.blocked.eventType";
    private static final String STAGE_BLOCKED_ID_KEY_FMT = "aux.%s.blocked.id";

    private static final String STAGE_NOISE_OVERRIDE_KEY_FMT = "aux.%s.noiseOverride";
    private static final String STAGE_NOISE_OVERRIDE_KEEP_PROB_KEY_FMT = "aux.%s.noiseOverride.keepProb";
    private static final String STAGE_NOISE_OVERRIDE_NOTE_KEY_FMT = "aux.%s.noiseOverride.note";
    private static final String STAGE_NOISE_OVERRIDE_META_KEY_FMT = "aux.%s.noiseOverride.meta";


    /**
     * Resolve the "best" (highest priority) reason from breaker and GuardContext signals.
     */
    public static AuxBlockedReason resolveReason(boolean breakerOpen, GuardContext ctx) {
        AuxBlockedReason ctxReason = AuxBlockedReason.fromContext(ctx);
        if (!breakerOpen) {
            return ctxReason;
        }
        return AuxBlockedReason.bestOf(AuxBlockedReason.BREAKER_OPEN, ctxReason);
    }

    public static void markStageBlocked(String stage, AuxBlockedReason reason) {
        markStageBlocked(stage, reason, null, null, null);
    }

    public static void markStageBlocked(String stage, AuxBlockedReason reason, String note) {
        markStageBlocked(stage, reason, note, null, null);
    }

    public static void markStageBlocked(String stage, AuxBlockedReason reason, String note, Throwable err) {
        markStageBlocked(stage, reason, note, null, err);
    }

    public static void markStageBlocked(String stage, AuxBlockedReason reason, String note, String breakerKey) {
        markStageBlocked(stage, reason, note, breakerKey, null);
    }

    /**
     * Mark a stage as blocked.
     *
     * @param stage logical stage name (ex: keywordSelection, disambiguation, queryTransformer)
     * @param reason standardized reason
     * @param note optional note/callsite hint
     * @param breakerKey optional circuit-breaker key that contributed to the decision
     * @param err optional captured error (if blocking was due to a caught exception)
     */
    public static void markStageBlocked(String stage, AuxBlockedReason reason, String note, String breakerKey, Throwable err) {
        markStageBlockedInternal(stage, reason, note, breakerKey, err, null, null);
    }

    private static void markStageBlockedInternal(
            String stage,
            AuxBlockedReason reason,
            String note,
            String breakerKey,
            Throwable err,
            GuardContext ctxSnapshot,
            Boolean breakerOpenHint
    ) {
        String safeStage = sanitizeStage(stage);
        AuxBlockedReason safeReason = (reason == null ? AuxBlockedReason.UNKNOWN : reason);
        String reasonCode = safeReason.code();

        // Prefer an explicit ctx snapshot (caller-provided), otherwise use the current holder context.
        GuardContext ctx = (ctxSnapshot != null ? ctxSnapshot : GuardContextHolder.getOrDefault());

        // If caller provided a breaker-open signal, record it; otherwise infer from the resolved reason.
        boolean breakerOpen = (breakerOpenHint != null ? breakerOpenHint : safeReason == AuxBlockedReason.BREAKER_OPEN);

        long eventId = TraceStore.nextSequence(ANY_BLOCKED_KEY);

        Instant now = Instant.now();
        long nowMs = now.toEpochMilli();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("ts", now.toString());
        event.put("eventType", EVENT_TYPE_STAGE_BLOCKED);
        event.put("stage", safeStage);
        event.put("reason", reasonCode);
        event.put("breakerOpen", breakerOpen);

        // If we're blocking due to breaker-open, capture a dedicated timestamp as well.
        // (ts = "when we decided to block"; breakerOpenAt = "when breaker-open was observed/tripped" (best-effort)).
        if (breakerOpen) {
                Long openSinceMs = resolveBreakerOpenAtMs(breakerKey);
                if (openSinceMs == null) {
                    openSinceMs = nowMs;
                }

                // Backward-compatible fields (kept):
                event.put("breakerOpenAtMs", openSinceMs);
                event.put("breakerOpenAt", Instant.ofEpochMilli(openSinceMs).toString());

                // Analysis-friendly aliases:
                event.put("breakerOpenSinceMs", openSinceMs);
                event.put("breakerOpenSince", Instant.ofEpochMilli(openSinceMs).toString());

                Long openUntilMs = resolveBreakerOpenUntilMs(breakerKey);
                if (openUntilMs != null && openUntilMs > 0) {
                    event.put("breakerOpenUntilMs", openUntilMs);
                    event.put("breakerOpenUntil", Instant.ofEpochMilli(openUntilMs).toString());
                    event.put("remainingOpenMs", Math.max(0L, openUntilMs - nowMs));
                    event.put("openWindowMs", Math.max(0L, openUntilMs - openSinceMs));
                }
        }

        if (safeReason.priority() > 0) {
            event.put("reasonPriority", safeReason.priority());
        }

        if (breakerKey != null && !breakerKey.isBlank()) {
            event.put("breakerKey", breakerKey);
        }
        if (note != null && !note.isBlank()) {
            event.put("note", safeShort(note, 300));
        }

        // Snapshot flags at the moment we decided to block (helps root-cause analysis).
        event.put("ctxFlags", snapshotCtxFlags(ctx));

        // Snapshot of breakers currently OPEN concurrently.
        // - Keys/count are ALWAYS included (misconfiguration-proof)
        // - Snapshot (key -> dto) is policy-driven, BUT forced for BREAKER_OPEN events
        event.put(EVENT_ALL_OPEN_BREAKERS_COUNT, 0);
        event.put(EVENT_ALL_OPEN_BREAKERS_KEYS, List.of());
        try {
            Map<String, AuxBreakerOpenSnapshot> allOpen = buildAllOpenBreakersSnapshotMap(nowMs, breakerOpen, breakerKey);
            int openCount = allOpen.size();

            ArrayList<String> keys = new ArrayList<>(allOpen.keySet());
            keys.sort(String::compareTo);

            event.put(EVENT_ALL_OPEN_BREAKERS_COUNT, openCount);
            event.put(EVENT_ALL_OPEN_BREAKERS_KEYS, keys);

            AllOpenBreakersSnapshotPolicy snapPol = resolveAllOpenBreakersSnapshotPolicy();
            boolean includeSnapshot = snapPol.includeSnapshot(breakerOpen, openCount);

            // Strong guarantee: if this event's reason is BREAKER_OPEN, we always attach the full snapshot
            // (so "why did we open" is traceable even if snapshot policy was misconfigured).
            if (breakerOpen) {
                includeSnapshot = true;
            }

            if (includeSnapshot) {
                event.put(EVENT_ALL_OPEN_BREAKERS_SNAPSHOT, allOpen);
            }
        } catch (Throwable ignore) {
            // best-effort only
        }


        if (err != null) {
            event.put("error.class", err.getClass().getName());
            if (err.getMessage() != null) {
                event.put("error.msg", safeShort(err.getMessage(), 200));
            }
        }

        Object pipe = TraceStore.get("pipe");
        if (pipe != null) {
            event.put("pipe", pipe);
        }

        Object qtCallsite = TraceStore.get("qt.callsite");
        if (qtCallsite != null) {
            event.put("qt.callsite", qtCallsite);
        }

        // ---- Global sticky keys ----
        TraceStore.putIfAbsent(ANY_BLOCKED_KEY, true);
        TraceStore.putIfAbsent(ANY_BLOCKED_STAGE_KEY, safeStage);
        TraceStore.putIfAbsent(ANY_BLOCKED_REASON_KEY, reasonCode);
        TraceStore.putIfAbsent(ANY_BLOCKED_ID_KEY, eventId);
        TraceStore.putIfAbsent(ANY_BLOCKED_FIRST_KEY, event);
        TraceStore.put(ANY_BLOCKED_LAST_KEY, event);

        // ---- Per-stage sticky keys ----
        TraceStore.putIfAbsent(String.format(STAGE_BLOCKED_KEY_FMT, safeStage), true);
        TraceStore.putIfAbsent(String.format(STAGE_BLOCKED_REASON_KEY_FMT, safeStage), reasonCode);
        TraceStore.putIfAbsent(String.format(STAGE_BLOCKED_EVENTTYPE_KEY_FMT, safeStage), EVENT_TYPE_STAGE_BLOCKED);
        TraceStore.putIfAbsent(String.format(STAGE_BLOCKED_ID_KEY_FMT, safeStage), eventId);
        if (breakerKey != null && !breakerKey.isBlank()) {
            TraceStore.putIfAbsent(String.format(STAGE_BLOCKED_BREAKER_KEY_FMT, safeStage), breakerKey);
        }

        // ---- Appendable event log (analysis-friendly) ----
        TraceStore.append(ANY_BLOCKED_EVENTS_KEY, event);
    }

    public static void markStageBlocked(String stage, boolean breakerOpen, GuardContext ctx, String note) {
        markStageBlockedInternal(stage, resolveReason(breakerOpen, ctx), note, null, null, ctx, breakerOpen);
    }

    public static void markStageBlocked(String stage, boolean breakerOpen, GuardContext ctx, String note, Throwable err) {
        markStageBlockedInternal(stage, resolveReason(breakerOpen, ctx), note, null, err, ctx, breakerOpen);
    }

    public static void markStageBlocked(String stage, boolean breakerOpen, String breakerKey, GuardContext ctx, String note) {
        markStageBlockedInternal(stage, resolveReason(breakerOpen, ctx), note, breakerKey, null, ctx, breakerOpen);
    }

    public static void markStageBlocked(String stage, boolean breakerOpen, String breakerKey, GuardContext ctx, String note, Throwable err) {
        markStageBlockedInternal(stage, resolveReason(breakerOpen, ctx), note, breakerKey, err, ctx, breakerOpen);
    }


    public static void markStageNoiseOverride(String stage, String note, double keepProb) {
        markStageNoiseOverride(stage, note, keepProb, null);
    }

    public static void markStageNoiseOverride(String stage, String note, double keepProb, Map<String, Object> meta) {
        String safeStage = sanitizeStage(stage);
        GuardContext ctx = GuardContextHolder.getOrDefault();

        long eventId = TraceStore.nextSequence(ANY_NOISE_OVERRIDE);

        Instant now = Instant.now();
        long nowMs = now.toEpochMilli();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("ts", now.toString());
        event.put("eventType", EVENT_TYPE_STAGE_NOISE_OVERRIDE);
        event.put("stage", safeStage);
        event.put("keepProb", round4(keepProb));

        if (note != null && !note.isBlank()) {
            event.put("note", safeShort(note, 300));
        }
        if (meta != null && !meta.isEmpty()) {
            event.put("meta", meta);
        }

        // Snapshot flags and open-breaker breadcrumbs (best-effort).
        event.put("ctxFlags", snapshotCtxFlags(ctx));
        event.put(EVENT_ALL_OPEN_BREAKERS_COUNT, 0);
        event.put(EVENT_ALL_OPEN_BREAKERS_KEYS, List.of());
        try {
            Map<String, AuxBreakerOpenSnapshot> allOpen = buildAllOpenBreakersSnapshotMap(nowMs, false, null);
            int openCount = allOpen.size();

            ArrayList<String> keys = new ArrayList<>(allOpen.keySet());
            keys.sort(String::compareTo);

            event.put(EVENT_ALL_OPEN_BREAKERS_COUNT, openCount);
            event.put(EVENT_ALL_OPEN_BREAKERS_KEYS, keys);

            AllOpenBreakersSnapshotPolicy snapPol = resolveAllOpenBreakersSnapshotPolicy();
            boolean includeSnapshot = snapPol.includeSnapshot(false, openCount);
            if (includeSnapshot) {
                event.put(EVENT_ALL_OPEN_BREAKERS_SNAPSHOT, allOpen);
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        Object pipe = TraceStore.get("pipe");
        if (pipe != null) {
            event.put("pipe", pipe);
        }

        Object qtCallsite = TraceStore.get("qt.callsite");
        if (qtCallsite != null) {
            event.put("qt.callsite", qtCallsite);
        }

        // ---- Global sticky keys ----
        TraceStore.putIfAbsent(ANY_NOISE_OVERRIDE, true);
        TraceStore.putIfAbsent(ANY_NOISE_OVERRIDE_STAGE_KEY, safeStage);
        TraceStore.putIfAbsent(ANY_NOISE_OVERRIDE_KEEP_PROB_KEY, round4(keepProb));
        TraceStore.putIfAbsent(ANY_NOISE_OVERRIDE_FIRST_KEY, event);
        TraceStore.put(ANY_NOISE_OVERRIDE_LAST_KEY, event);

        // ---- Per-stage sticky keys ----
        TraceStore.putIfAbsent(String.format(STAGE_NOISE_OVERRIDE_KEY_FMT, safeStage), true);
        TraceStore.putIfAbsent(String.format(STAGE_NOISE_OVERRIDE_KEEP_PROB_KEY_FMT, safeStage), round4(keepProb));
        if (note != null && !note.isBlank()) {
            TraceStore.putIfAbsent(String.format(STAGE_NOISE_OVERRIDE_NOTE_KEY_FMT, safeStage), safeShort(note, 300));
        }
        if (meta != null && !meta.isEmpty()) {
            TraceStore.putIfAbsent(String.format(STAGE_NOISE_OVERRIDE_META_KEY_FMT, safeStage), meta);
        }

        // ---- Appendable event log ----
        TraceStore.append(ANY_NOISE_EVENTS_KEY, event);
    }

    /**
     * Fixed-schema snapshot of GuardContext flags for trace/debug.
     */
    private static AuxCtxFlagsSnapshot snapshotCtxFlags(GuardContext ctx) {
        return AuxCtxFlagsSnapshot.from(ctx);
    }


    private static double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        return Math.round(v * 10000.0d) / 10000.0d;
    }

    private static String safeShort(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        String trimmed = s.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (maxLen <= 0) {
            return "";
        }
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        // keep it stable/readable in logs
        return trimmed.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    /**
     * Best-effort: if NightmareBreaker가 동일 request TraceStore에 openAtMs 를 심어둔 경우 사용.
     * 없으면 null.
     */
    private static Long resolveBreakerOpenAtMs(String breakerKey) {
        if (breakerKey == null || breakerKey.isBlank()) {
            return null;
        }
        try {
            Object v = TraceStore.get(NightmareBreaker.TRACE_OPEN_AT_MS_KEY);
            if (v instanceof Map<?, ?> map) {
                Object at = map.get(breakerKey);
                if (at instanceof Number n) {
                    return n.longValue();
                }
                if (at instanceof String s) {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException ignore) {
                        return null;
                    }
                }
            }
        } catch (Throwable ignore) {
            // noop
        }
        return null;
    }

    /**
     * Best-effort: if NightmareBreaker가 동일 request TraceStore에 openUntilMs 를 심어둔 경우 사용.
     * 없으면 null.
     */
    private static Long resolveBreakerOpenUntilMs(String breakerKey) {
        if (breakerKey == null || breakerKey.isBlank()) {
            return null;
        }
        try {
            Object v = TraceStore.get(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY);
            if (v instanceof Number n) {
                // legacy (혹은 단일값) 형태
                return n.longValue();
            }
            if (v instanceof Map<?, ?> map) {
                Object until = map.get(breakerKey);
                Long parsed = toLongOrNull(until);
                if (parsed != null) {
                    return parsed;
                }
            }

            // optional scalar fall-back (nightmare.breaker.openUntilMs.last)
            Object last = TraceStore.get(NightmareBreaker.TRACE_OPEN_UNTIL_MS_LAST_KEY);
            Long parsedLast = toLongOrNull(last);
            if (parsedLast != null) {
                return parsedLast;
            }
        } catch (Throwable ignore) {
            // noop
        }
        return null;
    }

    private static Map<String, AuxBreakerOpenSnapshot> buildAllOpenBreakersSnapshotMap(
            long nowMs,
            boolean breakerOpen,
            String currentBreakerKey
    ) {
        Map<String, Long> openUntilByKey = readLongMapFromTrace(NightmareBreaker.TRACE_OPEN_UNTIL_MS_KEY);
        Map<String, Long> openSinceByKey = readLongMapFromTrace(NightmareBreaker.TRACE_OPEN_AT_MS_KEY);
        Map<String, String> openKindByKey = readStringMapFromTrace(NightmareBreaker.TRACE_OPEN_KIND_KEY);
        Map<String, String> openErrMsgByKey = readStringMapFromTrace(NightmareBreaker.TRACE_OPEN_ERRMSG_KEY);

        // Only include breakers that are OPEN at this point in time.
        Map<String, AuxBreakerOpenSnapshot> out = new LinkedHashMap<>();
        openUntilByKey.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                .filter(e -> e.getValue() != null && e.getValue() > nowMs)
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    String key = e.getKey();
                    long openUntilMs = e.getValue();
                    long openSinceMs = openSinceByKey.getOrDefault(key, 0L);
                    out.put(key, AuxBreakerOpenSnapshot.of(key, openSinceMs, openUntilMs, nowMs, openKindByKey.get(key), openErrMsgByKey.get(key)));
                });

        // Fallback: ensure the currently blocking breaker is present if we're in a breakerOpen path.
        // (Some call paths may not have populated the per-key maps yet.)
        if (breakerOpen && currentBreakerKey != null && !currentBreakerKey.isBlank() && !out.containsKey(currentBreakerKey)) {
            Long openSinceMaybe = resolveBreakerOpenAtMs(currentBreakerKey);
            Long openUntilMaybe = resolveBreakerOpenUntilMs(currentBreakerKey);

            // We prefer the global breaker state (openSince/openUntil). If missing, at least pin openSince to now
            // so the record still carries a meaningful "observed" value instead of 0.
            long openSinceMs = (openSinceMaybe != null) ? openSinceMaybe : nowMs;
            long openUntilMs = (openUntilMaybe != null) ? openUntilMaybe : 0L;

            out.put(currentBreakerKey, AuxBreakerOpenSnapshot.of(currentBreakerKey, openSinceMs, openUntilMs, nowMs, openKindByKey.get(currentBreakerKey), openErrMsgByKey.get(currentBreakerKey)));
        }

        return out;
    }

    /**
     * Keys are intentionally always included in {@link #EVENT_ALL_OPEN_BREAKERS_KEYS}.
     *
     * <p>We keep this helper to document intent, but it no longer reads system properties.
     */
    @Deprecated
    private static AllOpenBreakersKeysPolicy resolveAllOpenBreakersKeysPolicy() {
        return AllOpenBreakersKeysPolicy.ALWAYS;
    }

    private static AllOpenBreakersSnapshotPolicy resolveAllOpenBreakersSnapshotPolicy() {
        String v = System.getProperty(ALL_OPEN_SNAPSHOT_POLICY_PROP);
        if (v == null || v.isBlank()) {
            v = System.getProperty(ALL_OPEN_SNAPSHOT_POLICY_PROP_ALIAS);
        }
        return AllOpenBreakersSnapshotPolicy.from(v);
    }

    private static Map<String, Long> readLongMapFromTrace(String traceKey) {
        try {
            Object raw = TraceStore.get(traceKey);
            if (!(raw instanceof Map<?, ?> m)) {
                return Map.of();
            }

            Map<String, Long> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                String key = e.getKey().toString();
                if (key.isBlank()) {
                    continue;
                }

                Long val = toLongOrNull(e.getValue());
                if (val != null) {
                    out.put(key, val);
                }
            }
            return out;
        } catch (Throwable ignore) {
            return Map.of();
        }
    }



    @SuppressWarnings("unchecked")
    private static Map<String, String> readStringMapFromTrace(String traceKey) {
        Object val = TraceStore.get(traceKey);
        if (val instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(k.toString(), v.toString());
                }
            });
            return out;
        }
        return Map.of();
    }
    private static Long toLongOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private static String sanitizeStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return "unknown";
        }
        // Be conservative: keep keys stable and filesystem/JSON-safe
        return stage.trim().replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
