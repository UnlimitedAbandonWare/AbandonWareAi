package ai.abandonware.nova.orch.zero100;

import ai.abandonware.nova.config.Zero100EngineProperties;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.SplittableRandom;

/**
 * Conversation/session registry for Emperor Pro Time (Zero-100).
 *
 * <p>Best-effort only: the registry must never break request handling.
 */
public class Zero100SessionRegistry {

    public enum ClampMode {
        PRECISION_CLAMP,
        RECALL_CLAMP
    }

    public static final class Slice {
        private final String sessionId;
        private final long nowMs;
        private final long startedMs;
        private final long deadlineMs;
        private final long sliceMs;
        private final long sliceIndex;
        private final double explorationRate;
        private final ClampMode clampMode;
        private final long webTimeboxMs;
        private final long backoffHardCapMs;
        private final String mpIntent;

        public Slice(
                String sessionId,
                long nowMs,
                long startedMs,
                long deadlineMs,
                long sliceMs,
                long sliceIndex,
                double explorationRate,
                ClampMode clampMode,
                long webTimeboxMs,
                long backoffHardCapMs,
                String mpIntent) {
            this.sessionId = sessionId;
            this.nowMs = nowMs;
            this.startedMs = startedMs;
            this.deadlineMs = deadlineMs;
            this.sliceMs = sliceMs;
            this.sliceIndex = sliceIndex;
            this.explorationRate = explorationRate;
            this.clampMode = clampMode;
            this.webTimeboxMs = webTimeboxMs;
            this.backoffHardCapMs = backoffHardCapMs;
            this.mpIntent = mpIntent;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getNowMs() {
            return nowMs;
        }

        public long getStartedMs() {
            return startedMs;
        }

        public long getDeadlineMs() {
            return deadlineMs;
        }

        public long getSliceMs() {
            return sliceMs;
        }

        public long getSliceIndex() {
            return sliceIndex;
        }

        public double getExplorationRate() {
            return explorationRate;
        }

        public ClampMode getClampMode() {
            return clampMode;
        }

        public long getWebTimeboxMs() {
            return webTimeboxMs;
        }

        public long getBackoffHardCapMs() {
            return backoffHardCapMs;
        }

        public String getMpIntent() {
            return mpIntent;
        }

        public long remainingSessionMs() {
            return Math.max(0L, deadlineMs - nowMs);
        }
    }

    private static final class State {
        final String sessionId;
        final long createdAtMs;
        final long budgetMs;
        final long seed;
        volatile long lastTouchMs;
        volatile long deadlineMs;
        volatile long sliceMs;
        volatile String mpIntent;

        State(String sessionId, long nowMs, long budgetMs, long sliceMs, String mpIntent) {
            this.sessionId = sessionId;
            this.createdAtMs = nowMs;
            this.budgetMs = budgetMs;
            this.lastTouchMs = nowMs;
            this.deadlineMs = nowMs + budgetMs;
            this.sliceMs = sliceMs;
            this.mpIntent = mpIntent;
            this.seed = stableHash64(sessionId);
        }
    }

    private final Zero100EngineProperties props;
    private final ConcurrentHashMap<String, State> sessions = new ConcurrentHashMap<>();

    public Zero100SessionRegistry(Zero100EngineProperties props) {
        this.props = Objects.requireNonNull(props, "props");
    }

    public Slice touch(
            String sessionId,
            String mpIntentCandidate,
            Long overrideMaxMinutes,
            Long overrideSliceMs,
            Long overrideWebTimeboxMs,
            Long overrideBackoffHardCapMs) {

        String sid = safeSid(sessionId);
        long now = System.currentTimeMillis();

        cleanupIfNeeded(now);

        long maxMinutes = clampLong(firstNonNull(overrideMaxMinutes, props.getMaxMinutes()), 1L, 100L);
        long sliceMs = clampLong(firstNonNull(overrideSliceMs, props.getSliceMs()), 50L, 60_000L);

        long budgetMs = maxMinutes * 60_000L;

        State st = sessions.compute(sid, (k, cur) -> {
            if (cur == null || isExpired(cur, now)) {
                return new State(sid, now, budgetMs, sliceMs, normalizeIntent(mpIntentCandidate));
            }
            cur.lastTouchMs = now;
            // Absolute timebox: do not extend the deadline on touch.
            // The session ends at createdAt + budgetMs.
            cur.deadlineMs = cur.createdAtMs + cur.budgetMs;
            cur.sliceMs = sliceMs;
            if (cur.mpIntent == null || cur.mpIntent.isBlank()) {
                cur.mpIntent = normalizeIntent(mpIntentCandidate);
            }
            return cur;
        });

        long idx = (st.sliceMs > 0L) ? Math.max(0L, (now - st.createdAtMs) / st.sliceMs) : 0L;

        // Deterministic pseudo-random per (sid, sliceIdx).
        SplittableRandom rnd = new SplittableRandom(st.seed ^ (idx * 0x9E3779B97F4A7C15L));
        double exploration = clampDouble(0.10d + rnd.nextDouble() * 0.25d, 0.05d, 0.40d);
        ClampMode clamp = (rnd.nextDouble() < 0.50d) ? ClampMode.PRECISION_CLAMP : ClampMode.RECALL_CLAMP;

        long webBox = clampLong(firstNonNull(overrideWebTimeboxMs, props.getWebCallTimeboxMs()), 100L, 20_000L);
        long hardCap = clampLong(firstNonNull(overrideBackoffHardCapMs, props.getBackoffHardCapMs()), 200L, 10_000L);

        return new Slice(sid, now, st.createdAtMs, st.deadlineMs, st.sliceMs, idx, exploration, clamp, webBox, hardCap, st.mpIntent);
    }

    public boolean isActive(String sessionId) {
        String sid = safeSid(sessionId);
        State st = sessions.get(sid);
        long now = System.currentTimeMillis();
        if (st == null) return false;
        if (isExpired(st, now)) {
            sessions.remove(sid, st);
            return false;
        }
        return true;
    }

    public String mpIntentOrNull(String sessionId) {
        String sid = safeSid(sessionId);
        State st = sessions.get(sid);
        return st == null ? null : st.mpIntent;
    }

    private void cleanupIfNeeded(long nowMs) {
        // Cheap guard: only scan occasionally.
        if ((nowMs % 97) != 0) {
            return;
        }
        sessions.entrySet().removeIf(e -> isExpired(e.getValue(), nowMs));
    }

    private boolean isExpired(State st, long nowMs) {
        if (st == null) return true;
        if (nowMs >= st.deadlineMs) return true;
        long idleTtl = clampLong(props.getSessionIdleTtlMs(), 30_000L, 6L * 60L * 60_000L);
        return (nowMs - st.lastTouchMs) > idleTtl;
    }

    private static String safeSid(String sessionId) {
        String s = (sessionId == null) ? "" : sessionId.trim();
        if (s.isEmpty()) return "unknown";
        if (s.length() > 120) return s.substring(0, 120);
        return s;
    }

    private static String normalizeIntent(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        // Collapse whitespace so cache keys are stable.
        t = t.replaceAll("\\s+", " ");
        if (t.length() > 380) t = t.substring(0, 380);
        return t;
    }

    private static long stableHash64(String s) {
        if (s == null) return 0L;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        long h = 1125899906842597L; // prime
        for (byte v : b) {
            h = 31L * h + (v & 0xff);
        }
        return h;
    }

    private static <T> T firstNonNull(T a, T b) {
        return (a != null) ? a : b;
    }

    private static long clampLong(long v, long min, long max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static double clampDouble(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return min;
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
