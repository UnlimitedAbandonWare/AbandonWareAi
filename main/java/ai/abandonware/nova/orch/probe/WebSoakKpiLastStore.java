package ai.abandonware.nova.orch.probe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory holder for the most recent SOAK_WEB_KPI JSON line.
 *
 * <p>This exists to support tight (1~5s) polling without having to parse mixed application logs.
 * Keep the payload small and safe: the KPI map is already designed to avoid query text and secrets.</p>
 *
 * <p>Thread-safe, bounded. Designed for best-effort debugging only.</p>
 */
public class WebSoakKpiLastStore {

    public static final int DEFAULT_MAX_RECENT = 180; // ~3 minutes @ 1s polling

    public static final class Snapshot {
        private final long capturedAtMs;
        private final Map<String, Object> kpi;
        private final String jsonLine;

        public Snapshot(long capturedAtMs, Map<String, Object> kpi, String jsonLine) {
            this.capturedAtMs = capturedAtMs;
            this.kpi = kpi;
            this.jsonLine = jsonLine;
        }

        public long getCapturedAtMs() {
            return capturedAtMs;
        }

        public Map<String, Object> getKpi() {
            return kpi;
        }

        public String getJsonLine() {
            return jsonLine;
        }
    }

    private final AtomicReference<Snapshot> last = new AtomicReference<>();
    private final int maxRecent;
    private final Object lock = new Object();
    private final Deque<Snapshot> recent = new ArrayDeque<>();

    public WebSoakKpiLastStore() {
        this(DEFAULT_MAX_RECENT);
    }

    public WebSoakKpiLastStore(int maxRecent) {
        this.maxRecent = Math.max(1, maxRecent);
    }

    public void update(Map<String, Object> kpi, String jsonLine) {
        if (kpi == null || kpi.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();

        // Shallow copy is enough; values are primitives / small maps.
        Map<String, Object> copy = new LinkedHashMap<>(kpi);

        String line = jsonLine;
        // Defensive bound: should never be huge, but avoid surprising memory growth.
        if (line != null && line.length() > 32_768) {
            line = line.substring(0, 32_768);
        }

        Snapshot s = new Snapshot(now, copy, line);
        last.set(s);

        synchronized (lock) {
            recent.addLast(s);
            while (recent.size() > maxRecent) {
                recent.removeFirst();
            }
        }
    }

    public Snapshot last() {
        return last.get();
    }

    public List<Snapshot> recent(int limit) {
        int lim = Math.max(0, limit);
        if (lim == 0) {
            return Collections.emptyList();
        }
        synchronized (lock) {
            if (recent.isEmpty()) {
                return Collections.emptyList();
            }
            int size = recent.size();
            int skip = Math.max(0, size - lim);

            List<Snapshot> out = new ArrayList<>(Math.min(lim, size));
            int i = 0;
            for (Snapshot s : recent) {
                if (i++ < skip) {
                    continue;
                }
                out.add(s);
            }
            return out;
        }
    }
}
