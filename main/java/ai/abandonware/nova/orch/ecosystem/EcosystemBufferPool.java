package ai.abandonware.nova.orch.ecosystem;

import ai.abandonware.nova.orch.web.WebSnippet;
import com.example.lms.search.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Bounded recirculation buffer for web snippets that survived an earlier pass.
 */
public class EcosystemBufferPool {

    private static final Logger log = LoggerFactory.getLogger(EcosystemBufferPool.class);

    private final int maxPoolSize;
    private final long ttlMs;
    private final int defaultRecirculateMax;
    private final double ammoniaThreshold;
    private final String quarantineTag;
    private final ArrayDeque<PoolEntry> pool = new ArrayDeque<>();
    private final AtomicLong recycledCount = new AtomicLong(0L);

    public EcosystemBufferPool() {
        this(64, 300_000L, 8);
    }

    public EcosystemBufferPool(int maxPoolSize, long ttlMs, int defaultRecirculateMax) {
        this(maxPoolSize, ttlMs, defaultRecirculateMax, 0.5d, "UNVERIFIED");
    }

    public EcosystemBufferPool(
            int maxPoolSize,
            long ttlMs,
            int defaultRecirculateMax,
            double ammoniaThreshold,
            String quarantineTag) {
        this.maxPoolSize = Math.max(1, maxPoolSize);
        this.ttlMs = Math.max(1_000L, ttlMs);
        this.defaultRecirculateMax = Math.max(1, defaultRecirculateMax);
        this.ammoniaThreshold = Math.max(0.0d, Math.min(1.0d, ammoniaThreshold));
        this.quarantineTag = (quarantineTag == null || quarantineTag.isBlank()) ? "UNVERIFIED" : quarantineTag.trim();
    }

    public synchronized void charge(String rid, List<WebSnippet> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        int added = 0;
        for (WebSnippet snippet : snippets) {
            if (snippet == null || snippet.raw() == null || snippet.raw().isBlank()) {
                continue;
            }
            while (pool.size() >= maxPoolSize) {
                pool.removeFirst();
            }
            pool.addLast(new PoolEntry(snippet, now));
            added++;
        }
        if (added > 0) {
            log.debug("[EcosystemBuffer] charged added={} poolSize={}", added, pool.size());
        }
    }

    public synchronized int chargeRawAndTrace(String rid, List<String> rawSnippets) {
        if (rawSnippets == null || rawSnippets.isEmpty()) {
            return 0;
        }
        List<WebSnippet> snippets = new ArrayList<>();
        for (String raw : rawSnippets) {
            if (raw != null && !raw.isBlank()) {
                snippets.add(WebSnippet.parse(raw));
            }
        }
        if (snippets.isEmpty()) {
            return 0;
        }
        charge(rid, snippets);
        TraceStore.put("ecosystem.charged", snippets.size());
        TraceStore.put("ecosystem.pool.size", poolSize());
        return snippets.size();
    }

    public synchronized List<WebSnippet> recirculate(int maxItems) {
        evictExpired(Instant.now().toEpochMilli());
        if (pool.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, maxItems <= 0 ? defaultRecirculateMax : maxItems);
        List<WebSnippet> result = new ArrayList<>(Math.min(limit, pool.size()));
        for (PoolEntry entry : pool) {
            if (result.size() >= limit) {
                break;
            }
            result.add(entry.snippet);
        }
        recycledCount.addAndGet(result.size());
        log.info("[EcosystemBuffer] recirculate maxItems={} returned={} totalRecycled={}",
                limit, result.size(), recycledCount.get());
        return Collections.unmodifiableList(result);
    }

    public Recirculation recirculateSafe(int maxItems, Predicate<WebSnippet> lowTrustPredicate) {
        List<WebSnippet> recycled = recirculate(maxItems);
        if (recycled.isEmpty()) {
            return new Recirculation(recycled, Collections.emptyList(), 0, 0, 0.0d,
                    ammoniaThreshold, false, quarantineTag, poolSize(), recycledCount());
        }
        List<WebSnippet> safe = new ArrayList<>();
        int quarantined = 0;
        for (WebSnippet snippet : recycled) {
            if (snippet == null) {
                continue;
            }
            boolean lowTrust = lowTrustPredicate != null && lowTrustPredicate.test(snippet);
            if (lowTrust) {
                quarantined++;
            } else {
                safe.add(snippet);
            }
        }
        double ammonia = recycled.isEmpty() ? 0.0d : (double) quarantined / (double) recycled.size();
        boolean surgeBlocked = ammonia >= ammoniaThreshold;
        if (surgeBlocked) {
            log.warn("[AmmoniaSurge] ammonia={} threshold={} quarantined={} total={}",
                    score(ammonia), score(ammoniaThreshold), quarantined, recycled.size());
        }
        return new Recirculation(recycled, surgeBlocked ? Collections.emptyList() : safe,
                quarantined, safe.size(), ammonia, ammoniaThreshold, surgeBlocked,
                quarantineTag, poolSize(), recycledCount());
    }

    public void recordRecirculationScan(Recirculation scan) {
        int count = scan == null || scan.recycled() == null ? 0 : scan.recycled().size();
        TraceStore.put("ecosystem.recirculate.count", count);
        TraceStore.put("ecosystem.pool.size", scan == null ? poolSize() : scan.poolSize());
        TraceStore.put("ecosystem.recycled.total", scan == null ? recycledCount() : scan.totalRecycled());
        if (scan != null) {
            recordAmmoniaTrace(count, scan.quarantined(), scan.ammoniaThreshold(), scan.quarantineTag());
        }
        if (count <= 0 || scan == null || scan.safe().isEmpty()) {
            TraceStore.put("ecosystem.recirculate.allUnverified", count > 0);
            TraceStore.put("starvationFallback.poolSafeEmpty", true);
            TraceStore.put("web.failsoft.starvationFallback.poolSafeEmpty", true);
            TraceStore.put("poolSafeEmpty", true);
        }
    }

    public void recordRecirculationSelection(int added) {
        TraceStore.put("ecosystem.recirculate.used", added > 0);
        TraceStore.put("ecosystem.recirculate.safe", added);
        TraceStore.put("ecosystem.recirculate.allUnverified", added <= 0);
        TraceStore.put("starvationFallback.poolSafeEmpty", added <= 0);
        TraceStore.put("web.failsoft.starvationFallback.poolSafeEmpty", added <= 0);
        TraceStore.put("poolSafeEmpty", added <= 0);
        if (added > 0) {
            TraceStore.put("web.failsoft.starvationFallback.used", true);
            TraceStore.put("starvationFallback.used", true);
            TraceStore.put("web.failsoft.starvationFallback.added", added);
            TraceStore.put("starvationFallback.added", added);
            TraceStore.put("web.failsoft.starvationFallback", "ecosystem->NOFILTER_SAFE");
            TraceStore.put("starvationFallback.trigger", "ecosystem->NOFILTER_SAFE");
            TraceStore.put("web.failsoft.starvationFallback.trigger", "ecosystem->NOFILTER_SAFE");
        }
    }

    public static void recordAmmoniaTrace(int totalCount, int quarantined, double threshold, String quarantineTag) {
        double ammonia = totalCount <= 0 ? 0.0d : (double) Math.max(0, quarantined) / (double) totalCount;
        TraceStore.put("ecosystem.ammonia.score", score(ammonia));
        TraceStore.put("ecosystem.ammonia.quarantined", Math.max(0, quarantined));
        TraceStore.put("ecosystem.ammonia.safe", Math.max(0, totalCount - quarantined));
        TraceStore.put("ecosystem.ammonia.threshold", score(threshold));
        TraceStore.put("ecosystem.ammonia.surgeBlocked", ammonia >= threshold);
        TraceStore.put("ecosystem.ammonia.quarantineTag",
                quarantineTag == null || quarantineTag.isBlank() ? "UNVERIFIED" : quarantineTag);
    }

    public synchronized int poolSize() {
        evictExpired(Instant.now().toEpochMilli());
        return pool.size();
    }

    public long recycledCount() {
        return recycledCount.get();
    }

    public int defaultRecirculateMax() {
        return defaultRecirculateMax;
    }

    public double ammoniaThreshold() {
        return ammoniaThreshold;
    }

    public String quarantineTag() {
        return quarantineTag;
    }

    public record Recirculation(
            List<WebSnippet> recycled,
            List<WebSnippet> safe,
            int quarantined,
            int safeCount,
            double ammoniaScore,
            double ammoniaThreshold,
            boolean surgeBlocked,
            String quarantineTag,
            int poolSize,
            long totalRecycled) {
    }

    private void evictExpired(long nowMs) {
        pool.removeIf(entry -> nowMs - entry.chargedAtMs > ttlMs);
    }

    private static String score(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record PoolEntry(WebSnippet snippet, long chargedAtMs) {
    }
}
