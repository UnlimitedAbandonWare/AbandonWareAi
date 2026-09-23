package com.example.lms.service.soak.metrics;

import com.example.lms.metrics.FaithfulnessMetricSnapshotStore;
import com.example.lms.search.TraceStore;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight per-sid metric registry for Soak runs.
 *
 * <p>Keyed by MDC sid so that async fan-out (via ContextAwareExecutorService)
 * keeps attribution stable even when threads hop.</p>
 */
@Component
public class SoakMetricRegistry {

    private static final class Counters {
        private final AtomicLong fpFilterLegacyBypassCount = new AtomicLong();
        private final AtomicLong webCalls = new AtomicLong();
        private final AtomicLong webCallsWithNaver = new AtomicLong();
        private final AtomicLong webMergedTotal = new AtomicLong();
        private final AtomicLong webMergedFromNaver = new AtomicLong();
    }

    private final ConcurrentMap<String, Counters> bySid = new ConcurrentHashMap<>();

    private static String sidOrNull() {
        return MDC.get("sid");
    }

    private Counters counters(String sid) {
        if (sid == null || sid.isBlank()) {
            // Use a shared bucket only when sid is absent (should be rare)
            sid = "_nosid";
        }
        return bySid.computeIfAbsent(sid, k -> new Counters());
    }

    /** Reset counters for the given sid (provider-scoped runs should call this). */
    public void resetForSid(String sid) {
        if (sid == null || sid.isBlank()) {
            sid = "_nosid";
        }
        bySid.put(sid, new Counters());
    }

    /** Remove a completed provider-scoped run without clearing the shared no-sid bucket. */
    public void removeForSid(String sid) {
        if (sid == null || sid.isBlank()) {
            return;
        }
        bySid.remove(sid);
    }

    /** Increment fp-filter legacy-bypass (metadata missing → writer fallback). */
    public void incFpFilterLegacyBypass() {
        String sid = sidOrNull();
        counters(sid).fpFilterLegacyBypassCount.incrementAndGet();
    }

    /** Record one web search call for the current sid. */
    public void recordWebCall(boolean withNaver) {
        String sid = sidOrNull();
        Counters c = counters(sid);
        c.webCalls.incrementAndGet();
        if (withNaver) {
            c.webCallsWithNaver.incrementAndGet();
        }
        writeTrace(snapshot(sid));
    }

    /** Record merge stats for the current sid. */
    public void recordWebMerge(int mergedTotal, int mergedFromNaver) {
        String sid = sidOrNull();
        Counters c = counters(sid);
        if (mergedTotal > 0) {
            c.webMergedTotal.addAndGet(mergedTotal);
        }
        if (mergedFromNaver > 0) {
            c.webMergedFromNaver.addAndGet(mergedFromNaver);
        }
        writeTrace(snapshot(sid));
    }

    public Snapshot snapshot(String sid) {
        Counters c = counters(sid);

        Snapshot s = new Snapshot();
        s.fpFilterLegacyBypassCount = c.fpFilterLegacyBypassCount.get();
        s.webCalls = c.webCalls.get();
        s.webCallsWithNaver = c.webCallsWithNaver.get();
        s.webMergedTotal = c.webMergedTotal.get();
        s.webMergedFromNaver = c.webMergedFromNaver.get();

        s.naverCallInclusionRate = (s.webCalls == 0L) ? 0.0 : (s.webCallsWithNaver * 1.0 / s.webCalls);
        s.naverMergedShare = (s.webMergedTotal == 0L) ? 0.0 : (s.webMergedFromNaver * 1.0 / s.webMergedTotal);
        return s;
    }

    /** Snapshot for current sid (convenience). */
    public Snapshot snapshotCurrent() {
        return snapshot(sidOrNull());
    }

    private static void writeTrace(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        putMetricTrace("soak.fpFilterLegacyBypassCount", snapshot.fpFilterLegacyBypassCount);
        putMetricTrace("soak.webCalls", snapshot.webCalls);
        putMetricTrace("soak.webCallsWithNaver", snapshot.webCallsWithNaver);
        putMetricTrace("soak.webMergedTotal", snapshot.webMergedTotal);
        putMetricTrace("soak.webMergedFromNaver", snapshot.webMergedFromNaver);
        putMetricTrace("soak.naverCallInclusionRate", snapshot.naverCallInclusionRate);
        putMetricTrace("soak.naverMergedShare", snapshot.naverMergedShare);
    }

    private static void putMetricTrace(String key, Object value) {
        TraceStore.put(key, value);
        FaithfulnessMetricSnapshotStore.put(key, value);
    }

    public static class Snapshot {
        public long fpFilterLegacyBypassCount;
        public long webCalls;
        public long webCallsWithNaver;
        public long webMergedTotal;
        public long webMergedFromNaver;
        public double naverCallInclusionRate;
        public double naverMergedShare;
    }
}
