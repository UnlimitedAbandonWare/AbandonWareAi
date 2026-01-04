package ai.abandonware.nova.orch.web;

import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import com.example.lms.domain.enums.RerankSourceCredibility;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToLongFunction;

/**
 * Rolling (in-memory) misroute report based on {@code web.failsoft.domainStagePairs}.
 *
 * <p>Goal: make it easy to spot "stage wiring" anomalies such as:
 * <ul>
 *   <li>hosts frequently routed to DEV_COMMUNITY but with cred=UNVERIFIED</li>
 *   <li>hosts that are cred=OFFICIAL/TRUSTED but also appear in devCommunity allow-lists (config conflict)</li>
 * </ul>
 *
 * <p>Only stores host-level information (no query/user content) and is bounded by {@code maxHosts}.</p>
 */
public class WebFailSoftDomainStageReportService {
    private static final Logger log = LoggerFactory.getLogger(WebFailSoftDomainStageReportService.class);

    private final NovaWebFailSoftProperties props;
    @Nullable private final DomainProfileLoader domainProfileLoader;
    @Nullable private final AuthorityScorer authorityScorer;

    private final long startedAtMs = System.currentTimeMillis();

    // Hard bounds to avoid unbounded memory growth.
    private final int maxHosts;
    private final long pruneAfterMs;

    private final ConcurrentHashMap<String, HostStats> hosts = new ConcurrentHashMap<>();
    private final LongAdder droppedNewHosts = new LongAdder();
    private final LongAdder totalEvents = new LongAdder();
    private final LongAdder totalSelectedEvents = new LongAdder();

    public WebFailSoftDomainStageReportService(
            NovaWebFailSoftProperties props,
            @Nullable DomainProfileLoader domainProfileLoader,
            @Nullable AuthorityScorer authorityScorer) {
        this.props = Objects.requireNonNull(props);
        this.domainProfileLoader = domainProfileLoader;
        this.authorityScorer = authorityScorer;

        // Defaults can be overridden by system properties if needed.
        this.maxHosts = clampInt(sysInt("nova.orch.web-failsoft.report.max-hosts", 2000), 200, 50_000);
        this.pruneAfterMs = clampLong(sysLong("nova.orch.web-failsoft.report.prune-after-ms", 6 * 60 * 60 * 1000L), 60_000L, 7L * 24 * 60 * 60 * 1000L);
        log.info("[nova][web-failsoft] domainStageReport enabled (maxHosts={}, pruneAfterMs={})", maxHosts, pruneAfterMs);
    }

    /**
     * Record a single domain-stage event. Expected keys:
     * host, stage, cred, selected, by, classifiedBy, propsDevCommunity, profileDevCommunity, ...
     */
    public void record(Map<String, Object> ev) {
        if (ev == null || ev.isEmpty()) return;
        String host = normHost(ev.get("host"));
        if (host == null || host.isBlank()) return;

        boolean selected = truthy(ev.get("selected"));
        String stage = str(ev.get("stage"), "NA").toUpperCase(Locale.ROOT);
        String cred = str(ev.get("cred"), "UNVERIFIED").toUpperCase(Locale.ROOT);
        String by = str(ev.get("by"), "");
        String classifiedBy = str(ev.get("classifiedBy"), "");

        boolean propsDevCommunity = truthy(ev.get("propsDevCommunity"));
        boolean profileDevCommunity = truthy(ev.get("profileDevCommunity"));

        totalEvents.increment();
        if (selected) totalSelectedEvents.increment();

        HostStats hs = hosts.get(host);
        if (hs == null) {
            if (hosts.size() >= maxHosts) {
                droppedNewHosts.increment();
                return;
            }
            HostStats created = new HostStats(host);
            HostStats existing = hosts.putIfAbsent(host, created);
            hs = existing != null ? existing : created;
        }

        hs.touch();
        hs.rawTotal.increment();
        if (selected) hs.selectedTotal.increment();

        if ("DEV_COMMUNITY".equals(stage)) {
            hs.devCommunityRaw.increment();
            if (selected) hs.devCommunitySelected.increment();

            if ("UNVERIFIED".equals(cred)) {
                hs.devCommunityRawUnverified.increment();
                if (selected) {
                    hs.devCommunitySelectedUnverified.increment();
                    String reason = (!classifiedBy.isBlank()) ? classifiedBy : by;
                    hs.byDevCommunitySelectedUnverified
                            .computeIfAbsent(trimReason(reason), k -> new LongAdder())
                            .increment();
                }
            }
        }

        // Observed config conflict: high authority host is also present in dev_community lists.
        if (("OFFICIAL".equals(cred) || "TRUSTED".equals(cred)) && (propsDevCommunity || profileDevCommunity)) {
            hs.conflictHighAuthorityInDevCommunityLists.increment();
        }

        if (!by.isBlank()) {
            hs.incBy("by:" + trimReason(by));
        }
        if (!classifiedBy.isBlank()) {
            hs.incBy("classifiedBy:" + trimReason(classifiedBy));
        }
    }

    public Map<String, Object> snapshot(int topN, int minCount) {
        prune();

        int n = Math.max(1, topN);
        int min = Math.max(1, minCount);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", Instant.now().toString());
        out.put("startedAt", Instant.ofEpochMilli(startedAtMs).toString());
        out.put("uptimeSec", (System.currentTimeMillis() - startedAtMs) / 1000L);
        out.put("hostsTracked", hosts.size());
        out.put("droppedNewHosts", droppedNewHosts.sum());
        out.put("totalEvents", totalEvents.sum());
        out.put("totalSelectedEvents", totalSelectedEvents.sum());

        out.put("topDevCommunityUnverifiedSelected", topHosts(h -> h.devCommunitySelectedUnverified.sum(), n, min, true));
        out.put("topDevCommunityUnverifiedRaw", topHosts(h -> h.devCommunityRawUnverified.sum(), n, min, false));
        out.put("observedConflictsHighAuthorityInDevCommunityLists", topHosts(h -> h.conflictHighAuthorityInDevCommunityLists.sum(), n, min, false));

        out.put("configConflicts", configConflicts(n));

        return out;
    }

    public void reset() {
        hosts.clear();
        droppedNewHosts.reset();
        totalEvents.reset();
        totalSelectedEvents.reset();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Report helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> topHosts(ToLongFunction<HostStats> metric, int topN, int minCount, boolean includeSuggestion) {
        ArrayList<HostStats> list = new ArrayList<>(hosts.values());
        list.removeIf(h -> metric.applyAsLong(h) < minCount);
        list.sort((a, b) -> Long.compare(metric.applyAsLong(b), metric.applyAsLong(a)));

        if (list.size() > topN) {
            list.subList(topN, list.size()).clear();
        }

        ArrayList<Map<String, Object>> out = new ArrayList<>(list.size());
        long now = System.currentTimeMillis();
        for (HostStats hs : list) {
            long v = metric.applyAsLong(hs);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("host", hs.host);
            row.put("count", v);
            row.put("selectedTotal", hs.selectedTotal.sum());
            row.put("devCommunitySelected", hs.devCommunitySelected.sum());
            row.put("devCommunitySelectedUnverified", hs.devCommunitySelectedUnverified.sum());
            row.put("lastSeenSecAgo", Math.max(0L, (now - hs.lastSeenMs.get()) / 1000L));

            if (!hs.byDevCommunitySelectedUnverified.isEmpty()) {
                row.put("topReasons", topReasons(hs.byDevCommunitySelectedUnverified, 5));
            }

            if (includeSuggestion) {
                row.put("suggestion", suggestDevCommunityUnverified(hs));
            }

            out.add(row);
        }
        return out;
    }

    private Map<String, Object> suggestDevCommunityUnverified(HostStats hs) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("action", "review-dev-community-routing");
        s.put("host", hs.host);

        // pick top reason when available
        String topReason = null;
        long topCnt = 0L;
        for (Map.Entry<String, LongAdder> e : hs.byDevCommunitySelectedUnverified.entrySet()) {
            long c = e.getValue().sum();
            if (c > topCnt) {
                topCnt = c;
                topReason = e.getKey();
            }
        }
        if (topReason != null) {
            s.put("topReason", topReason);
            s.put("topReasonCount", topCnt);
        }

        // Heuristic "where to fix": props list vs profile list.
        if (topReason != null && topReason.startsWith("props:devCommunityDomains")) {
            s.put("fixHint", "remove from allow-list or add to deny-list");
            s.put("denyProperty", "nova.orch.web-failsoft.dev-community-deny-domains");
            s.put("denyValue", hs.host);
        } else if (topReason != null && topReason.startsWith("profile:dev_community")) {
            s.put("fixHint", "remove from dev_community profile or add deny override");
            s.put("denyProperty", "domain.allowlist.profile-deny.dev-community");
            s.put("denyValue", hs.host);
        } else {
            s.put("fixHint", "inspect why this host is entering DEV_COMMUNITY with UNVERIFIED cred");
        }
        return s;
    }

    private Map<String, Object> configConflicts(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();

        List<String> official = normalizeDomains(props.getOfficialDomains());
        List<String> dev = normalizeDomains(props.getDevCommunityDomains());

        // overlap: officialDomains ∩ devCommunityDomains
        LinkedHashSet<String> overlap = new LinkedHashSet<>(official);
        overlap.retainAll(new LinkedHashSet<>(dev));
        if (!overlap.isEmpty()) {
            out.put("propsOverlap_official_vs_devCommunityDomains", new ArrayList<>(overlap));
        }

        // high authority domains living inside devCommunityDomains
        if (authorityScorer != null) {
            ArrayList<Map<String, Object>> highAuth = new ArrayList<>();
            for (String d : dev) {
                if (d == null || d.isBlank()) continue;
                RerankSourceCredibility c = safeCred("https://" + d);
                if (c == RerankSourceCredibility.OFFICIAL || c == RerankSourceCredibility.TRUSTED) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("domain", d);
                    row.put("cred", c.name());
                    highAuth.add(row);
                }
                if (highAuth.size() >= limit) break;
            }
            if (!highAuth.isEmpty()) {
                out.put("propsDevCommunityDomains_highAuthority", highAuth);
            }
        }

        // high authority domains inside dev_community profile (DomainProfileLoader)
        if (authorityScorer != null && domainProfileLoader != null) {
            List<String> prof = domainProfileLoader.getProfileEntries("dev_community");
            ArrayList<Map<String, Object>> highAuth = new ArrayList<>();
            for (String d : normalizeDomains(prof)) {
                RerankSourceCredibility c = safeCred("https://" + d);
                if (c == RerankSourceCredibility.OFFICIAL || c == RerankSourceCredibility.TRUSTED) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("domain", d);
                    row.put("cred", c.name());
                    highAuth.add(row);
                }
                if (highAuth.size() >= limit) break;
            }
            if (!highAuth.isEmpty()) {
                out.put("profileDevCommunity_highAuthority", highAuth);
            }
        }

        // show deny list as-is (useful while "reconnecting")
        try {
            List<String> deny = props.getDevCommunityDenyDomains();
            if (deny != null && !deny.isEmpty()) {
                out.put("devCommunityDenyDomains", new ArrayList<>(deny));
            }
        } catch (Exception ignore) {
        }

        return out;
    }

    private RerankSourceCredibility safeCred(String url) {
        try {
            return authorityScorer == null ? RerankSourceCredibility.UNVERIFIED : authorityScorer.getSourceCredibility(url);
        } catch (Exception e) {
            return RerankSourceCredibility.UNVERIFIED;
        }
    }

    private static List<Map<String, Object>> topReasons(Map<String, LongAdder> m, int topN) {
        ArrayList<Map.Entry<String, LongAdder>> entries = new ArrayList<>(m.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        if (entries.size() > topN) {
            entries.subList(topN, entries.size()).clear();
        }
        ArrayList<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, LongAdder> e : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reason", e.getKey());
            row.put("count", e.getValue().sum());
            out.add(row);
        }
        return out;
    }

    private void prune() {
        long now = System.currentTimeMillis();
        if (hosts.isEmpty()) return;
        if (pruneAfterMs <= 0) return;

        for (Map.Entry<String, HostStats> e : hosts.entrySet()) {
            HostStats hs = e.getValue();
            if (hs == null) continue;
            long age = now - hs.lastSeenMs.get();
            if (age > pruneAfterMs) {
                hosts.remove(e.getKey(), hs);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Small helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final class HostStats {
        final String host;
        final AtomicLong lastSeenMs = new AtomicLong(System.currentTimeMillis());

        final LongAdder rawTotal = new LongAdder();
        final LongAdder selectedTotal = new LongAdder();

        final LongAdder devCommunityRaw = new LongAdder();
        final LongAdder devCommunitySelected = new LongAdder();
        final LongAdder devCommunityRawUnverified = new LongAdder();
        final LongAdder devCommunitySelectedUnverified = new LongAdder();

        final LongAdder conflictHighAuthorityInDevCommunityLists = new LongAdder();

        // only for the misroute we care about: DEV_COMMUNITY ∩ UNVERIFIED (selected)
        final ConcurrentHashMap<String, LongAdder> byDevCommunitySelectedUnverified = new ConcurrentHashMap<>();

        // small general counters (bounded)
        final ConcurrentHashMap<String, LongAdder> byAny = new ConcurrentHashMap<>();

        HostStats(String host) {
            this.host = host;
        }

        void touch() {
            lastSeenMs.set(System.currentTimeMillis());
        }

        void incBy(String key) {
            if (key == null || key.isBlank()) return;
            if (byAny.size() > 64 && !byAny.containsKey(key)) return;
            byAny.computeIfAbsent(key, k -> new LongAdder()).increment();
        }
    }

    private static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("y") || s.equals("yes");
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        if (s == null) return def;
        s = s.trim();
        return s.isBlank() ? def : s;
    }

    private static String trimReason(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() > 120) t = t.substring(0, 120) + "...";
        return t;
    }

    private static String normHost(Object o) {
        if (o == null) return null;
        String h = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return null;
        if (h.startsWith("www.")) h = h.substring(4);
        // strip trailing dots
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static List<String> normalizeDomains(List<String> in) {
        if (in == null || in.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            String n = normalizeDomainToken(s);
            if (n != null) out.add(n);
        }
        return new ArrayList<>(out);
    }

    private static String normalizeDomainToken(String token) {
        if (token == null) return null;
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        if (t.startsWith("http://")) t = t.substring(7);
        if (t.startsWith("https://")) t = t.substring(8);
        while (t.startsWith(".")) t = t.substring(1);
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        int colon = t.indexOf(':');
        if (colon >= 0) t = t.substring(0, colon);
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        if (t.startsWith("www.")) t = t.substring(4);
        return t.isEmpty() ? null : t;
    }

    private static int sysInt(String key, int def) {
        try {
            String v = System.getProperty(key);
            if (v == null || v.isBlank()) return def;
            return Integer.parseInt(v.trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static long sysLong(String key, long def) {
        try {
            String v = System.getProperty(key);
            if (v == null || v.isBlank()) return def;
            return Long.parseLong(v.trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clampLong(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }
}
