package com.example.lms.service.vector;

import com.example.lms.service.rag.LangChainRAGService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VectorIngestProtectionService
 *
 * <p>
 * "INGEST_PROTECTION" is a fail-soft quarantine mode for vector ingestion.
 * When repeated embedder / vector-writer failures are detected in a short window,
 * we temporarily route new ingests to the quarantine SID to keep the primary SID clean.
 *
 * <p>
 * This service is intentionally simple and self-contained so it can be rebased
 * onto the current architecture (VectorStoreService + FederatedEmbeddingStore + SidRotationAdvisor)
 * without requiring the older hygiene/ingest-run stack.
 */
@Service
@RequiredArgsConstructor
public class VectorIngestProtectionService {

    private static final Logger log = LoggerFactory.getLogger(VectorIngestProtectionService.class);

    @Value("${vector.ingest-protection.enabled:false}")
    private boolean enabled;

    @Value("${vector.ingest-protection.global-only:true}")
    private boolean globalOnly;

    /** Open quarantine for this many ms once threshold is reached. */
    @Value("${vector.ingest-protection.quarantine-ms:900000}")
    private long quarantineMs;

    /** How many matching failures within window-ms trigger quarantine. */
    @Value("${vector.ingest-protection.threshold:3}")
    private int threshold;

    /** Sliding window for threshold evaluation. */
    @Value("${vector.ingest-protection.window-ms:120000}")
    private long windowMs;

    /** If no matching failure is seen for reset-ms, counter resets. */
    @Value("${vector.ingest-protection.reset-ms:300000}")
    private long resetMs;

    /**
     * When quarantine is active and caller provided an explicit/stable id,
     * optionally rewrite the id so it does NOT overwrite existing stable vectors.
     */
    @Value("${vector.ingest-protection.quarantine-rewrite-stable-id:true}")
    private boolean quarantineRewriteStableId;

    private static final class State {
        volatile long firstAtMs;
        volatile long lastAtMs;
        volatile int hits;
        volatile long quarantineUntilMs;
        volatile String lastReason;
        volatile String lastStage;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public boolean quarantineRewriteStableId() {
        return quarantineRewriteStableId;
    }

    public boolean isQuarantineActive(String sid) {
        if (!enabled) return false;
        String base = sidBaseOrDefault(sid);
        if (globalOnly && !LangChainRAGService.GLOBAL_SID.equals(base)) {
            return false;
        }
        State st = states.get(base);
        long now = System.currentTimeMillis();
        return st != null && st.quarantineUntilMs > now;
    }

    public String quarantineReason(String sid) {
        if (!enabled) return "";
        String base = sidBaseOrDefault(sid);
        State st = states.get(base);
        if (st == null) return "";
        return st.lastReason == null ? "" : st.lastReason;
    }

    public void clearQuarantine(String sid) {
        String base = sidBaseOrDefault(sid);
        State st = states.get(base);
        if (st == null) return;
        st.quarantineUntilMs = 0L;
        st.hits = 0;
        st.firstAtMs = 0L;
        st.lastAtMs = 0L;
    }

    /**
     * Record a failure signal.
     *
     * <p>Only signals that match {@link #ingestProtectionMatches(Throwable)} are counted.
     */
    public void recordIfMatches(String sid, Throwable error, String stage) {
        if (!enabled) return;
        if (error == null) return;

        String base = sidBaseOrDefault(sid);
        if (globalOnly && !LangChainRAGService.GLOBAL_SID.equals(base)) {
            return;
        }

        if (!ingestProtectionMatches(error)) {
            return;
        }

        long now = System.currentTimeMillis();
        State st = states.computeIfAbsent(base, k -> new State());

        // Reset if idle for long enough.
        if (st.lastAtMs > 0 && (now - st.lastAtMs) > resetMs) {
            st.hits = 0;
            st.firstAtMs = 0L;
        }

        // Start new window if needed.
        if (st.firstAtMs == 0L || (now - st.firstAtMs) > windowMs) {
            st.hits = 0;
            st.firstAtMs = now;
        }

        st.hits++;
        st.lastAtMs = now;
        st.lastStage = safe(stage);
        st.lastReason = classifyReason(error);

        // Already quarantined.
        if (st.quarantineUntilMs > now) {
            return;
        }

        int th = Math.max(1, threshold);
        if (st.hits >= th) {
            long until = now + Math.max(5_000L, quarantineMs);
            st.quarantineUntilMs = until;
            log.warn("[IngestProtection] OPEN sidBase={} untilMs={} reason={} stage={} hits={}",
                    base, until, st.lastReason, st.lastStage, st.hits);
        }
    }

    /**
     * Small heuristic matcher:
     * - embedder down / invalid vectors
     * - network timeouts
     * - vector store validation/upsert failures
     */
    private static boolean ingestProtectionMatches(Throwable t) {
        String s = (t == null) ? "" : String.valueOf(t);
        if (s.isBlank()) return false;
        String l = s.toLowerCase(java.util.Locale.ROOT);

        // invalid embeddings / corruption
        if (l.contains("invalid vectors") || l.contains("all-zero") || l.contains("empty embedding")) return true;

        // typical network / availability failures
        if (l.contains("connectexception") || l.contains("connection refused")) return true;
        if (l.contains("timed out") || l.contains("timeout")) return true;
        if (l.contains("eofexception")) return true;
        if (l.contains("503") || l.contains("502") || l.contains("504")) return true;

        // vector store specific
        if (l.contains("pinecone") && l.contains("validation")) return true;
        if (l.contains("vector upsert") && l.contains("degraded")) return true;
        if (l.contains("upsert") && l.contains("error")) return true;

        return false;
    }

    private static String classifyReason(Throwable t) {
        if (t == null) return "";
        String s = String.valueOf(t);
        if (s.length() > 240) s = s.substring(0, 240);
        return s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("globalOnly", globalOnly);
        out.put("threshold", threshold);
        out.put("windowMs", windowMs);
        out.put("resetMs", resetMs);
        out.put("quarantineMs", quarantineMs);
        out.put("quarantineRewriteStableId", quarantineRewriteStableId);

        Map<String, Object> st = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, State> e : states.entrySet()) {
            State s = e.getValue();
            if (s == null) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("hits", s.hits);
            one.put("firstAtMs", s.firstAtMs);
            one.put("lastAtMs", s.lastAtMs);
            one.put("quarantineUntilMs", s.quarantineUntilMs);
            one.put("quarantineActive", s.quarantineUntilMs > now);
            one.put("lastStage", s.lastStage);
            one.put("lastReason", s.lastReason);
            st.put(e.getKey(), one);
        }
        out.put("states", st);
        return out;
    }

    @Scheduled(fixedDelayString = "${vector.ingest-protection.gc-interval-ms:60000}")
    public void gc() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        long keepMs = Math.max(windowMs, resetMs) + Math.max(0, quarantineMs) + 60_000L;
        states.entrySet().removeIf(en -> {
            State st = en.getValue();
            if (st == null) return true;
            long last = st.lastAtMs;
            long until = st.quarantineUntilMs;
            long ref = Math.max(last, until);
            return ref > 0 && (now - ref) > keepMs;
        });
    }

    private static String sidBaseOrDefault(String sid) {
        String base = sidBase(sid);
        if (base.isBlank()) {
            return LangChainRAGService.GLOBAL_SID;
        }
        return base;
    }

    public static String sidBase(String sid) {
        if (sid == null) return "";
        String s = sid.trim();
        if (s.isEmpty()) return "";
        int idx = s.indexOf('#');
        if (idx > 0) {
            return s.substring(0, idx);
        }
        return s;
    }
}
