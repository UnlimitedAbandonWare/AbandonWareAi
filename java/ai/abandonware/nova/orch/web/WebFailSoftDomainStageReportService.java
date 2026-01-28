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
 * Rolling (in-memory) misroute report based on
 * {@code web.failsoft.domainStagePairs}.
 *
 * <p>
 * Goal: make it easy to spot "stage wiring" anomalies such as:
 * <ul>
 * <li>hosts frequently routed to DEV_COMMUNITY but with cred=UNVERIFIED</li>
 * <li>hosts that are cred=OFFICIAL/TRUSTED but also appear in devCommunity
 * allow-lists (config conflict)</li>
 * </ul>
 *
 * <p>
 * Stores host-level information and (optionally) redacted canonical query
 * aggregates for debugging.
 * It is bounded by {@code maxHosts} and {@code maxCanonicalQueries}.
 * </p>
 */
public class WebFailSoftDomainStageReportService {
    private static final Logger log = LoggerFactory.getLogger(WebFailSoftDomainStageReportService.class);

    private final NovaWebFailSoftProperties props;
    @Nullable
    private final DomainProfileLoader domainProfileLoader;
    @Nullable
    private final AuthorityScorer authorityScorer;

    private final long startedAtMs = System.currentTimeMillis();

    // Hard bounds to avoid unbounded memory growth.
    private final int maxHosts;
    private final long pruneAfterMs;

    private final ConcurrentHashMap<String, HostStats> hosts = new ConcurrentHashMap<>();
    private final LongAdder droppedNewHosts = new LongAdder();
    private final LongAdder totalEvents = new LongAdder();
    private final LongAdder totalSelectedEvents = new LongAdder();

    // Optional – redacted canonical query aggregation for "policy learning loop"
    // hints.
    private final boolean includeCanonicalQueries;
    private final int maxCanonicalQueries;
    private final int maxCanonicalQueriesPerHost;

    private final ConcurrentHashMap<String, QueryStats> canonicalQueries = new ConcurrentHashMap<>();
    private final LongAdder droppedNewCanonicalQueries = new LongAdder();

    public WebFailSoftDomainStageReportService(
            NovaWebFailSoftProperties props,
            @Nullable DomainProfileLoader domainProfileLoader,
            @Nullable AuthorityScorer authorityScorer) {
        this.props = Objects.requireNonNull(props);
        this.domainProfileLoader = domainProfileLoader;
        this.authorityScorer = authorityScorer;

        // Defaults can be overridden by system properties if needed.
        this.maxHosts = clampInt(sysInt("nova.orch.web-failsoft.report.max-hosts", 2000), 200, 50_000);
        this.pruneAfterMs = clampLong(sysLong("nova.orch.web-failsoft.report.prune-after-ms", 6 * 60 * 60 * 1000L),
                60_000L, 7L * 24 * 60 * 60 * 1000L);

        this.includeCanonicalQueries = sysBool("nova.orch.web-failsoft.report.include-canonical-queries", true);
        this.maxCanonicalQueries = clampInt(sysInt("nova.orch.web-failsoft.report.max-canonical-queries", 5000), 200,
                50_000);
        this.maxCanonicalQueriesPerHost = clampInt(
                sysInt("nova.orch.web-failsoft.report.max-canonical-queries-per-host", 32), 4, 512);
        log.info(
                "[nova][web-failsoft] domainStageReport enabled (maxHosts={}, maxCanonicalQueries={}, pruneAfterMs={}, includeCanonicalQueries={})",
                maxHosts, maxCanonicalQueries, pruneAfterMs, includeCanonicalQueries);
    }

    /**
     * Record a single domain-stage event. Expected keys:
     * host, stage, cred, selected, by, classifiedBy, propsDevCommunity,
     * profileDevCommunity, ...
     */
    public void record(Map<String, Object> ev) {
        if (ev == null || ev.isEmpty())
            return;
        String host = normHost(ev.get("host"));
        if (host == null || host.isBlank())
            return;

        boolean selected = truthy(ev.get("selected"));
        String stage = str(ev.get("stage"), "NA").toUpperCase(Locale.ROOT);
        String cred = str(ev.get("cred"), "UNVERIFIED").toUpperCase(Locale.ROOT);
        String by = str(ev.get("by"), "");
        String classifiedBy = str(ev.get("classifiedBy"), "");

        // Optional: query-level debug aggregates (redacted).
        String canonicalQuery = includeCanonicalQueries ? sanitizeQuery(str(ev.get("canonicalQuery"), "")) : "";
        String intent = str(ev.get("intent"), "");
        String overridePath = str(ev.get("overridePath"), "");

        boolean propsDevCommunity = truthy(ev.get("propsDevCommunity"));
        boolean profileDevCommunity = truthy(ev.get("profileDevCommunity"));

        totalEvents.increment();
        if (selected)
            totalSelectedEvents.increment();

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
        if (selected)
            hs.selectedTotal.increment();

        if ("DEV_COMMUNITY".equals(stage)) {
            hs.devCommunityRaw.increment();
            if (selected)
                hs.devCommunitySelected.increment();

            if ("UNVERIFIED".equals(cred)) {
                hs.devCommunityRawUnverified.increment();
                if (selected) {
                    hs.devCommunitySelectedUnverified.increment();
                    String reason = (!classifiedBy.isBlank()) ? classifiedBy : by;
                    hs.byDevCommunitySelectedUnverified
                            .computeIfAbsent(trimReason(reason), k -> new LongAdder())
                            .increment();

                    if (!canonicalQuery.isBlank()) {
                        hs.incBounded(hs.byCanonicalQueryDevCommunityUnverifiedSelected, canonicalQuery,
                                maxCanonicalQueriesPerHost);
                    }
                    if (!intent.isBlank()) {
                        hs.incBounded(hs.byIntentDevCommunityUnverifiedSelected, intent, 8);
                    }

                }
            }
        }

        // Observed config conflict: high authority host is also present in
        // dev_community lists.
        if (("OFFICIAL".equals(cred) || "TRUSTED".equals(cred)) && (propsDevCommunity || profileDevCommunity)) {
            hs.conflictHighAuthorityInDevCommunityLists.increment();
        }

        // Global query aggregates for "policy learning loop" hints.
        // We only record on selected events to keep volume manageable.
        boolean isDevCommunityUnverifiedSelected = selected && "DEV_COMMUNITY".equals(stage)
                && "UNVERIFIED".equals(cred);
        boolean isStarvationFallbackSelected = selected && "starvationFallback".equalsIgnoreCase(overridePath);
        boolean isStarvationFallbackUnverifiedSelected = isStarvationFallbackSelected && "UNVERIFIED".equals(cred);

        if (!canonicalQuery.isBlank() && (isDevCommunityUnverifiedSelected || isStarvationFallbackSelected)) {
            String intentNorm = intent == null ? "" : intent.trim().toUpperCase(Locale.ROOT);
            String qKey = intentNorm + "\u001F" + canonicalQuery;

            QueryStats qs = canonicalQueries.get(qKey);
            if (qs == null) {
                if (canonicalQueries.size() >= maxCanonicalQueries) {
                    droppedNewCanonicalQueries.increment();
                } else {
                    QueryStats created = new QueryStats(canonicalQuery, intentNorm);
                    QueryStats existing = canonicalQueries.putIfAbsent(qKey, created);
                    qs = existing != null ? existing : created;
                }
            }
            if (qs != null) {
                qs.touch();
                qs.totalSelected.increment();

                if (isDevCommunityUnverifiedSelected) {
                    qs.devCommunityUnverifiedSelected.increment();
                    qs.incHostDevCommunityUnverified(host);
                }
                if (isStarvationFallbackSelected) {
                    qs.starvationFallbackSelected.increment();
                    qs.incHostStarvationFallback(host);
                }
                if (isStarvationFallbackUnverifiedSelected) {
                    qs.starvationFallbackUnverifiedSelected.increment();
                    qs.incHostStarvationFallbackUnverified(host);
                }
            }
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

        if (includeCanonicalQueries) {
            out.put("canonicalQueriesTracked", canonicalQueries.size());
            out.put("droppedNewCanonicalQueries", droppedNewCanonicalQueries.sum());
        }

        out.put("topDevCommunityUnverifiedSelected",
                topHosts(h -> h.devCommunitySelectedUnverified.sum(), n, min, true));
        out.put("topDevCommunityUnverifiedRaw", topHosts(h -> h.devCommunityRawUnverified.sum(), n, min, false));
        out.put("observedConflictsHighAuthorityInDevCommunityLists",
                topHosts(h -> h.conflictHighAuthorityInDevCommunityLists.sum(), n, min, false));

        if (includeCanonicalQueries) {
            out.put("officialDocsRescueQueries", officialDocsRescueQueries(n, min));
        }

        out.put("configConflicts", configConflicts(n));

        return out;
    }

    public void reset() {
        hosts.clear();
        droppedNewHosts.reset();
        totalEvents.reset();
        totalSelectedEvents.reset();
        canonicalQueries.clear();
        droppedNewCanonicalQueries.reset();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Report helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> topHosts(ToLongFunction<HostStats> metric, int topN, int minCount,
            boolean includeSuggestion) {
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

            if (includeCanonicalQueries) {
                if (!hs.byCanonicalQueryDevCommunityUnverifiedSelected.isEmpty()) {
                    row.put("topCanonicalQueries",
                            topCounts(hs.byCanonicalQueryDevCommunityUnverifiedSelected, "canonicalQuery", 3));
                }
                if (!hs.byIntentDevCommunityUnverifiedSelected.isEmpty()) {
                    row.put("intentCounts", topCounts(hs.byIntentDevCommunityUnverifiedSelected, "intent", 3));
                }
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

        if (includeCanonicalQueries) {
            Map.Entry<String, LongAdder> tq = topEntry(hs.byCanonicalQueryDevCommunityUnverifiedSelected);
            if (tq != null) {
                s.put("topCanonicalQuery", tq.getKey());
                s.put("topCanonicalQueryCount", tq.getValue().sum());
            }
            Map.Entry<String, LongAdder> ti = topEntry(hs.byIntentDevCommunityUnverifiedSelected);
            if (ti != null) {
                s.put("topIntent", ti.getKey());
                s.put("topIntentCount", ti.getValue().sum());
            }
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
                if (d == null || d.isBlank())
                    continue;
                RerankSourceCredibility c = safeCred("https://" + d);
                if (c == RerankSourceCredibility.OFFICIAL || c == RerankSourceCredibility.TRUSTED) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("domain", d);
                    row.put("cred", c.name());
                    highAuth.add(row);
                }
                if (highAuth.size() >= limit)
                    break;
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
                if (highAuth.size() >= limit)
                    break;
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

    private Map<String, Object> officialDocsRescueQueries(int topN, int minCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentConfig.officialDocsRescueQueries", props.getOfficialDocsRescueQueries());

        // Report knobs (system properties): keep diagnostics flexible without config
        // churn.
        int siteNegMinCount = clampInt(sysInt("nova.orch.web-failsoft.report.site-negative.min-count", 2), 1, 50);
        int siteNegMax = clampInt(sysInt("nova.orch.web-failsoft.report.site-negative.max", 3), 1, 10);
        int rescueExamplesMax = clampInt(sysInt("nova.orch.web-failsoft.report.rescue-example.max", 3), 1, 10);
        int unverifiedSkewMinPermil = clampInt(sysInt("nova.orch.web-failsoft.report.unverified-skew.min-permil", 800),
                0, 1000);

        // OFFICIAL/DOCS domains should never be suggested as "-site:" negatives.
        LinkedHashSet<String> safeDomains = new LinkedHashSet<>();
        safeDomains.addAll(normalizeDomains(props.getOfficialDomains()));
        safeDomains.addAll(normalizeDomains(props.getDocsDomains()));

        // ── A) Starvation fallback (officialOnly clamp starved → NOFILTER_SAFE top-up)
        // ──
        List<QueryStats> topStarvation = topQueries(q -> q.starvationFallbackSelected.sum(), topN, minCount);
        ArrayList<Map<String, Object>> starvationRows = new ArrayList<>(topStarvation.size());

        // Aggregate tokens across top starvation queries (very lightweight).
        ConcurrentHashMap<String, LongAdder> tokenCounts = new ConcurrentHashMap<>();

        for (QueryStats qs : topStarvation) {
            long sf = qs.starvationFallbackSelected.sum();
            long sfUnv = qs.starvationFallbackUnverifiedSelected.sum();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("intent", qs.intent);
            row.put("canonicalQuery", qs.canonicalQuery);
            row.put("starvationFallbackSelected", sf);
            row.put("starvationFallbackUnverifiedSelected", sfUnv);
            row.put("starvationFallbackUnverifiedPermil", permil(sfUnv, sf));
            row.put("devCommunityUnverifiedSelected", qs.devCommunityUnverifiedSelected.sum());
            row.put("totalSelected", qs.totalSelected.sum());

            if (!qs.byHostStarvationFallback.isEmpty()) {
                row.put("topHosts", topCounts(qs.byHostStarvationFallback, "host", 5));
            }

            List<String> positives = defaultPositiveTokens(qs.intent, qs.canonicalQuery);
            List<String> negatives = defaultNegativeTokens(qs.intent);
            List<String> templates = defaultRescueTemplates(qs.intent);

            row.put("suggestedPositiveTokens", positives);
            row.put("suggestedNegativeTokens", negatives);
            row.put("suggestedRescueQueryTemplates", templates);
            row.put("suggestedRescueQueryExamples",
                    buildRescueQueryExamples(qs.canonicalQuery, templates, positives, negatives, List.of(),
                            rescueExamplesMax));

            // tokens for global aggregation
            for (String t : tokenize(qs.canonicalQuery)) {
                tokenCounts.computeIfAbsent(t, k -> new LongAdder()).increment();
            }

            starvationRows.add(row);
        }

        out.put("topStarvationFallbackCanonicalQueries", starvationRows);
        out.put("topCanonicalTokens", topCounts(tokenCounts, "token", 12));

        // ── B) UNVERIFIED skew within starvation fallback (quality signal) ──
        // This surfaces "fallback worked but the added evidence is mostly UNVERIFIED".
        List<QueryStats> topUnverified = topQueries(q -> q.starvationFallbackUnverifiedSelected.sum(), topN, minCount);
        ArrayList<Map<String, Object>> unverifiedRows = new ArrayList<>(topUnverified.size());

        for (QueryStats qs : topUnverified) {
            long sf = qs.starvationFallbackSelected.sum();
            long sfUnv = qs.starvationFallbackUnverifiedSelected.sum();
            int p = permil(sfUnv, sf);
            if (p < unverifiedSkewMinPermil) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("intent", qs.intent);
            row.put("canonicalQuery", qs.canonicalQuery);
            row.put("starvationFallbackUnverifiedSelected", sfUnv);
            row.put("starvationFallbackSelected", sf);
            row.put("unverifiedSkewPermil", p);
            row.put("unverifiedSkewRatio", (sf <= 0) ? 0.0 : (double) sfUnv / (double) sf);
            row.put("totalSelected", qs.totalSelected.sum());

            if (!qs.byHostStarvationFallbackUnverified.isEmpty()) {
                row.put("topHosts", topCounts(qs.byHostStarvationFallbackUnverified, "host", 6));
            }

            List<String> siteNegatives = suggestNegativeSiteTokens(
                    qs.byHostStarvationFallbackUnverified,
                    safeDomains,
                    siteNegMax,
                    siteNegMinCount);

            row.put("suggestedNegativeSiteTokens", siteNegatives);

            List<String> positives = defaultPositiveTokens(qs.intent, qs.canonicalQuery);
            List<String> negatives = defaultNegativeTokens(qs.intent);
            List<String> templates = defaultRescueTemplates(qs.intent);

            row.put("suggestedPositiveTokens", positives);
            row.put("suggestedNegativeTokens", negatives);
            row.put("suggestedRescueQueryTemplates", templates);

            // "query-host learning loop": propose query examples with per-canonicalQuery
            // -site:host tokens.
            row.put("suggestedRescueQueryExamples",
                    buildRescueQueryExamples(qs.canonicalQuery, templates, positives, negatives, siteNegatives,
                            rescueExamplesMax));

            unverifiedRows.add(row);
        }

        out.put("topUnverifiedSkewCanonicalQueries", unverifiedRows);

        out.put("reportKnobs", Map.of(
                "siteNegative.minCount", siteNegMinCount,
                "siteNegative.max", siteNegMax,
                "unverifiedSkew.minPermil", unverifiedSkewMinPermil,
                "rescueExamples.max", rescueExamplesMax));

        // "policy learning loop" hint: if starvation is frequent, suggest adding small
        // templates.
        out.put("suggestedConfigKey", "nova.orch.web-failsoft.official-docs-rescue-queries");

        return out;
    }

    private static int permil(long numerator, long denominator) {
        if (denominator <= 0L)
            return 0;
        if (numerator <= 0L)
            return 0;
        double v = ((double) numerator) / ((double) denominator);
        int p = (int) Math.round(v * 1000.0);
        if (p < 0)
            return 0;
        if (p > 1000)
            return 1000;
        return p;
    }

    private List<String> suggestNegativeSiteTokens(
            Map<String, LongAdder> byHost,
            Set<String> safeDomains,
            int maxTokens,
            int minCount) {
        if (byHost == null || byHost.isEmpty())
            return List.of();

        int max = Math.max(1, maxTokens);
        int min = Math.max(1, minCount);

        ArrayList<Map.Entry<String, LongAdder>> entries = new ArrayList<>(byHost.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));

        ArrayList<String> out = new ArrayList<>();
        for (Map.Entry<String, LongAdder> e : entries) {
            if (out.size() >= max)
                break;

            long c = e.getValue() == null ? 0L : e.getValue().sum();
            if (c < min)
                break;

            String host = normalizeDomainToken(e.getKey());
            if (host == null || host.isBlank())
                continue;
            if (isSafeDomain(host, safeDomains))
                continue;

            out.add("-site:" + host);
        }
        return out;
    }

    private static boolean isSafeDomain(String host, Set<String> safeDomains) {
        if (host == null || host.isBlank())
            return false;
        if (safeDomains == null || safeDomains.isEmpty())
            return false;
        for (String d : safeDomains) {
            if (d == null || d.isBlank())
                continue;
            if (host.equals(d) || host.endsWith("." + d))
                return true;
        }
        return false;
    }

    private static List<String> buildRescueQueryExamples(
            String canonicalQuery,
            List<String> templates,
            List<String> positiveTokens,
            List<String> negativeTokens,
            List<String> negativeSiteTokens,
            int maxExamples) {

        String cq = canonicalQuery == null ? "" : canonicalQuery.trim();
        if (cq.isBlank())
            return List.of();
        if (templates == null || templates.isEmpty())
            return List.of();

        int max = Math.max(1, maxExamples);

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String tmpl : templates) {
            if (tmpl == null || tmpl.isBlank())
                continue;

            String q = fillTemplate(tmpl, cq);

            // Keep examples short: 1~2 positive tokens, 0~3 negatives, plus any -site:host.
            q = appendTokens(q, positiveTokens, 2, false);
            q = appendTokens(q, negativeTokens, 3, true);

            if (negativeSiteTokens != null) {
                for (String s : negativeSiteTokens) {
                    if (s == null || s.isBlank())
                        continue;
                    q = q + " " + s.trim();
                }
            }

            q = q.replaceAll("\\s+", " ").trim();
            if (!q.isBlank()) {
                out.add(q);
            }
            if (out.size() >= max)
                break;
        }

        return new ArrayList<>(out);
    }

    private static String fillTemplate(String template, String canonicalQuery) {
        if (template == null)
            return canonicalQuery;
        String out = template;
        out = out.replace("{canonical}", canonicalQuery);
        out = out.replace("${canonical}", canonicalQuery);
        out = out.replace("{entity}", canonicalQuery);
        out = out.replace("${entity}", canonicalQuery);
        return out;
    }

    private static String appendTokens(String base, List<String> tokens, int maxTokens, boolean negative) {
        if (base == null)
            base = "";
        if (tokens == null || tokens.isEmpty() || maxTokens <= 0)
            return base;

        int added = 0;
        String out = base;
        for (String t : tokens) {
            if (t == null || t.isBlank())
                continue;
            String tok = t.trim();
            if (negative && !tok.startsWith("-")) {
                tok = "-" + tok;
            }
            out = out + " " + tok;
            if (++added >= maxTokens)
                break;
        }
        return out;
    }

    private List<QueryStats> topQueries(ToLongFunction<QueryStats> metric, int topN, int minCount) {
        ArrayList<QueryStats> list = new ArrayList<>(canonicalQueries.values());
        list.removeIf(q -> metric.applyAsLong(q) < minCount);
        list.sort((a, b) -> Long.compare(metric.applyAsLong(b), metric.applyAsLong(a)));
        if (list.size() > topN) {
            list.subList(topN, list.size()).clear();
        }
        return list;
    }

    private static List<Map<String, Object>> topCounts(Map<String, LongAdder> m, String keyField, int topN) {
        ArrayList<Map.Entry<String, LongAdder>> entries = new ArrayList<>(m.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        if (entries.size() > topN) {
            entries.subList(topN, entries.size()).clear();
        }
        ArrayList<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, LongAdder> e : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(keyField, e.getKey());
            row.put("count", e.getValue().sum());
            out.add(row);
        }
        return out;
    }

    private static List<String> defaultPositiveTokens(String intent, String canonicalQuery) {
        String i = intent == null ? "" : intent.trim().toUpperCase(Locale.ROOT);
        String q = canonicalQuery == null ? "" : canonicalQuery.toLowerCase(Locale.ROOT);

        if ("TECH_API".equals(i)) {
            ArrayList<String> out = new ArrayList<>(
                    List.of("official", "docs", "documentation", "api", "pricing", "quota", "rate limit"));
            if (q.contains("gemini")) {
                out.add("ai.google.dev");
            }
            return out;
        }
        if ("FINANCE".equals(i)) {
            return List.of("금융감독원", "금융위원회", "한국은행");
        }
        // GENERAL (or unknown): entity/profile lookups
        return List.of("공식", "공식 홈페이지", "공식 사이트", "프로필");
    }

    private List<String> defaultNegativeTokens(String intent) {
        String i = intent == null ? "" : intent.trim().toUpperCase(Locale.ROOT);
        if ("TECH_API".equals(i)) {
            // suggest a small subset (operator can copy/paste into techSpamKeywords or
            // rescue templates)
            List<String> src = props.getTechSpamKeywords();
            if (src == null || src.isEmpty()) {
                return List.of("대출", "금리", "신용", "카드");
            }
            return src.size() > 8 ? src.subList(0, 8) : src;
        }
        if ("GENERAL".equals(i) || i.isBlank()) {
            return List.of("나무위키", "위키", "블로그", "카페", "커뮤니티");
        }
        return List.of("카페", "커뮤니티");
    }

    private static List<String> defaultRescueTemplates(String intent) {
        String i = intent == null ? "" : intent.trim().toUpperCase(Locale.ROOT);
        if ("TECH_API".equals(i)) {
            return List.of("{canonical} official docs", "{canonical} documentation", "{canonical} pricing quota");
        }
        if ("FINANCE".equals(i)) {
            return List.of("{canonical} 금융감독원", "{canonical} 금융위원회", "{canonical} 한국은행");
        }
        // GENERAL (entity / company)
        return List.of("{entity} 공식", "{entity} 공식 사이트", "{entity} 공식 홈페이지", "{canonical} 프로필");
    }

    private static String sanitizeQuery(String q) {
        if (q == null)
            return "";
        String s = q.replaceAll("[\r\n\t]+", " ").trim();
        if (s.isBlank())
            return "";

        // redact common PII-ish patterns (best-effort, not perfect)
        s = s.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "<email>");
        s = s.replaceAll("\\b\\d{2,4}[- ]?\\d{3,4}[- ]?\\d{4}\\b", "<phone>");
        s = s.replaceAll("\\b\\d{5,}\\b", "<num>");

        // normalize whitespace + clamp
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 140) {
            s = s.substring(0, 140) + "…";
        }
        return s;
    }

    private static List<String> tokenize(String canonicalQuery) {
        if (canonicalQuery == null || canonicalQuery.isBlank())
            return List.of();
        String s = canonicalQuery.toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^0-9a-zA-Z가-힣]+", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isBlank())
            return List.of();

        Set<String> stop = STOPWORDS;
        String[] parts = s.split(" ");
        ArrayList<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null || p.isBlank())
                continue;
            if (p.length() < 2)
                continue;
            if (stop.contains(p))
                continue;
            out.add(p);
        }
        return out;
    }

    private static final Set<String> STOPWORDS = new HashSet<>(List.of(
            "뭐야", "뭐냐", "뭔데", "누구", "정체", "회사", "기업", "소개", "어떤", "알려줘", "해주세요",
            "what", "is", "are", "the", "a", "an", "of", "to", "in", "on"));

    private RerankSourceCredibility safeCred(String url) {
        try {
            return authorityScorer == null ? RerankSourceCredibility.UNVERIFIED
                    : authorityScorer.getSourceCredibility(url);
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
        if (hosts.isEmpty())
            return;
        if (pruneAfterMs <= 0)
            return;

        for (Map.Entry<String, HostStats> e : hosts.entrySet()) {
            HostStats hs = e.getValue();
            if (hs == null)
                continue;
            long age = now - hs.lastSeenMs.get();
            if (age > pruneAfterMs) {
                hosts.remove(e.getKey(), hs);
            }
        }

        // prune canonical query aggregates too (same TTL as host stats)
        for (Map.Entry<String, QueryStats> e : canonicalQueries.entrySet()) {
            QueryStats qs = e.getValue();
            if (qs == null)
                continue;
            long age = now - qs.lastSeenMs.get();
            if (age > pruneAfterMs) {
                canonicalQueries.remove(e.getKey(), qs);
            }
        }

    }

    // ─────────────────────────────────────────────────────────────────────────
    // Small helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final class QueryStats {
        final String canonicalQuery;
        final String intent;

        final AtomicLong lastSeenMs = new AtomicLong(System.currentTimeMillis());

        // Counts are over *selected* events for which we recorded query aggregates.
        final LongAdder totalSelected = new LongAdder();

        // Signals (per selected snippet)
        final LongAdder starvationFallbackSelected = new LongAdder();
        final LongAdder starvationFallbackUnverifiedSelected = new LongAdder();
        final LongAdder devCommunityUnverifiedSelected = new LongAdder();

        // Host aggregates are bounded per query to avoid unbounded growth.
        final ConcurrentHashMap<String, LongAdder> byHostStarvationFallback = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> byHostStarvationFallbackUnverified = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> byHostDevCommunityUnverified = new ConcurrentHashMap<>();

        QueryStats(String canonicalQuery, String intent) {
            this.canonicalQuery = canonicalQuery;
            this.intent = intent == null ? "" : intent;
        }

        void touch() {
            lastSeenMs.set(System.currentTimeMillis());
        }

        void incHostStarvationFallback(String host) {
            incHostBounded(byHostStarvationFallback, host, 48);
        }

        void incHostStarvationFallbackUnverified(String host) {
            incHostBounded(byHostStarvationFallbackUnverified, host, 48);
        }

        void incHostDevCommunityUnverified(String host) {
            incHostBounded(byHostDevCommunityUnverified, host, 48);
        }

        private static void incHostBounded(ConcurrentHashMap<String, LongAdder> m, String host, int maxKeys) {
            if (m == null)
                return;
            if (host == null || host.isBlank())
                return;
            if (m.size() > maxKeys && !m.containsKey(host))
                return;
            m.computeIfAbsent(host, k -> new LongAdder()).increment();
        }
    }

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

        // query/intent aggregates (redacted, bounded)
        final ConcurrentHashMap<String, LongAdder> byCanonicalQueryDevCommunityUnverifiedSelected = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> byIntentDevCommunityUnverifiedSelected = new ConcurrentHashMap<>();

        // small general counters (bounded)
        final ConcurrentHashMap<String, LongAdder> byAny = new ConcurrentHashMap<>();

        HostStats(String host) {
            this.host = host;
        }

        void touch() {
            lastSeenMs.set(System.currentTimeMillis());
        }

        void incBounded(ConcurrentHashMap<String, LongAdder> m, String key, int maxKeys) {
            if (m == null)
                return;
            if (key == null || key.isBlank())
                return;
            if (m.size() >= maxKeys && !m.containsKey(key))
                return;
            m.computeIfAbsent(key, k -> new LongAdder()).increment();
        }

        void incBy(String key) {
            if (key == null || key.isBlank())
                return;
            if (byAny.size() > 64 && !byAny.containsKey(key))
                return;
            byAny.computeIfAbsent(key, k -> new LongAdder()).increment();
        }
    }

    private static boolean truthy(Object o) {
        if (o == null)
            return false;
        if (o instanceof Boolean b)
            return b;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("y") || s.equals("yes");
    }

    private static String str(Object o, String def) {
        if (o == null)
            return def;
        String s = String.valueOf(o);
        if (s == null)
            return def;
        s = s.trim();
        return s.isBlank() ? def : s;
    }

    private static String trimReason(String s) {
        if (s == null)
            return "";
        String t = s.trim();
        if (t.length() > 120)
            t = t.substring(0, 120) + "...";
        return t;
    }

    private static String normHost(Object o) {
        if (o == null)
            return null;
        String h = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty())
            return null;
        if (h.startsWith("www."))
            h = h.substring(4);
        // strip trailing dots
        while (h.endsWith("."))
            h = h.substring(0, h.length() - 1);
        return h;
    }

    private static List<String> normalizeDomains(List<String> in) {
        if (in == null || in.isEmpty())
            return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            String n = normalizeDomainToken(s);
            if (n != null)
                out.add(n);
        }
        return new ArrayList<>(out);
    }

    private static String normalizeDomainToken(String token) {
        if (token == null)
            return null;
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty())
            return null;
        if (t.startsWith("http://"))
            t = t.substring(7);
        if (t.startsWith("https://"))
            t = t.substring(8);
        while (t.startsWith("."))
            t = t.substring(1);
        int slash = t.indexOf('/');
        if (slash >= 0)
            t = t.substring(0, slash);
        int colon = t.indexOf(':');
        if (colon >= 0)
            t = t.substring(0, colon);
        while (t.endsWith("."))
            t = t.substring(0, t.length() - 1);
        if (t.startsWith("www."))
            t = t.substring(4);
        return t.isEmpty() ? null : t;
    }

    private static int sysInt(String key, int def) {
        try {
            String v = System.getProperty(key);
            if (v == null || v.isBlank())
                return def;
            return Integer.parseInt(v.trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static long sysLong(String key, long def) {
        try {
            String v = System.getProperty(key);
            if (v == null || v.isBlank())
                return def;
            return Long.parseLong(v.trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static boolean sysBool(String key, boolean def) {
        try {
            String v = System.getProperty(key);
            if (v == null || v.isBlank())
                return def;
            String s = v.trim().toLowerCase(Locale.ROOT);
            return s.equals("true") || s.equals("1") || s.equals("y") || s.equals("yes") || s.equals("on");
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

    /**
     * Returns the entry with the highest LongAdder sum.
     */
    private static <K> Map.Entry<K, LongAdder> topEntry(Map<K, LongAdder> map) {
        if (map == null || map.isEmpty())
            return null;
        Map.Entry<K, LongAdder> top = null;
        long max = -1;
        for (Map.Entry<K, LongAdder> e : map.entrySet()) {
            long val = e.getValue().sum();
            if (val > max) {
                max = val;
                top = e;
            }
        }
        return top;
    }
}
