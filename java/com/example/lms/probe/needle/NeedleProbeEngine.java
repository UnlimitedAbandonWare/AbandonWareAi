package com.example.lms.probe.needle;

import com.example.lms.search.TraceStore;
import com.example.lms.search.terms.SelectedTerms;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.rerank.RerankKnobResolver;
import com.example.lms.util.HtmlTextUtil;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * (UAW) Needle probe engine.
 *
 * <p>Runs a tiny 2-pass web detour when pass-1 evidence looks weak. It generates at most
 * 1~2 short site-filtered queries, retrieves a small amount of additional web evidence,
 * filters for high-authority sources, and returns docs to be merged/reranked by the caller.
 */
@Service
public class NeedleProbeEngine {

    private static final Logger log = LoggerFactory.getLogger(NeedleProbeEngine.class);

    private static final Pattern URL_IN_TEXT = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    private static final List<String> LATEST_HINTS = List.of(
            "최신", "최근", "요즘", "이번", "업데이트", "패치", "변경", "20",
            "latest", "recent", "new", "update", "patch", "changed", "current");

    private static final Set<String> STOPWORDS = Set.of(
            "은", "는", "이", "가", "을", "를", "에", "의", "과", "와", "도", "로", "으로", "에서", "부터", "까지",
            "the", "a", "an", "of", "to", "in", "for", "on", "and", "or", "vs");

    private final NeedleProbeProperties props;
    private final WebSearchRetriever webSearchRetriever;
    private final AuthorityScorer authorityScorer;

    public NeedleProbeEngine(NeedleProbeProperties props,
                             WebSearchRetriever webSearchRetriever,
                             AuthorityScorer authorityScorer) {
        this.props = props;
        this.webSearchRetriever = webSearchRetriever;
        this.authorityScorer = authorityScorer;
    }

    public record Quality(
            double authorityAvg,
            double duplicateRatio,
            double coverage,
            int totalDocs,
            int docsWithUrl,
            int uniqueDomains
    ) {
        public static Quality empty() {
            return new Quality(0.0, 1.0, 0.0, 0, 0, 0);
        }
    }

    public record Plan(
            List<String> needleQueries,
            List<String> siteHints,
            List<String> coreKeywords,
            Quality quality,
            String reason
    ) {
    }

    public record Result(
            boolean triggered,
            Plan plan,
            List<Content> needleDocs,
            Set<String> needleUrls
    ) {
        public int countTopDocsHits(List<Content> topDocs) {
            if (topDocs == null || topDocs.isEmpty() || needleUrls == null || needleUrls.isEmpty()) {
                return 0;
            }
            int hits = 0;
            for (Content c : topDocs) {
                String u = extractUrlOrNull(c);
                if (u != null && needleUrls.contains(u)) {
                    hits++;
                }
            }
            return hits;
        }
    }

    public Result maybeProbe(String userQuery,
                             List<Content> pass1TopDocs,
                             int keepN,
                             Long sessionIdLong,
                             Map<String, Object> baseMetaHints) {

        if (props == null || !props.isEnabled()) {
            return new Result(false, new Plan(List.of(), List.of(), List.of(), Quality.empty(), "disabled"), List.of(), Set.of());
        }

        String q = (userQuery == null) ? "" : userQuery.trim();
        List<Content> topDocs = (pass1TopDocs == null) ? List.of() : pass1TopDocs;

        Quality quality = scoreEvidenceQuality(q, topDocs);
        boolean weak = isEvidenceWeak(quality, topDocs);

        try {
            TraceStore.put("needle.quality.authorityAvg", quality.authorityAvg());
            TraceStore.put("needle.quality.duplicateRatio", quality.duplicateRatio());
            TraceStore.put("needle.quality.coverage", quality.coverage());
            TraceStore.put("needle.quality.uniqueDomains", quality.uniqueDomains());
            TraceStore.put("needle.quality.docsWithUrl", quality.docsWithUrl());
        } catch (Exception ignore) {
        }

        if (!weak) {
            return new Result(false, new Plan(List.of(), List.of(), List.of(), quality, "quality_ok"), List.of(), Set.of());
        }

        Plan plan = buildPlan(q, quality);
        if (plan.needleQueries() == null || plan.needleQueries().isEmpty()) {
            return new Result(false, plan, List.of(), Set.of());
        }

        List<Content> raw = new ArrayList<>();
        for (String nq : plan.needleQueries()) {
            if (nq == null || nq.isBlank()) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            if (baseMetaHints != null) {
                meta.putAll(baseMetaHints);
            }
            meta.put("webTopK", props.getWebTopK());
            meta.put("webBudgetMs", props.getWebBudgetMs());
            meta.put("probe.needle", "true");
            try {
                TraceStore.inc("needle.web.calls");
                List<Content> out = webSearchRetriever.retrieve(QueryUtils.buildQuery(nq, sessionIdLong, null, meta));
                if (out != null && !out.isEmpty()) {
                    raw.addAll(out);
                }
            } catch (Exception e) {
                try {
                    TraceStore.put("needle.retrieve.error", e.getClass().getSimpleName());
                } catch (Exception ignore) {
                }
            }
        }

        List<Content> filtered = filterAndDedupByAuthority(raw, props.getAuthorityMin(), keepN);
        Set<String> urls = collectUrls(filtered);

        if ((filtered == null || filtered.isEmpty()) && raw != null && !raw.isEmpty()) {
            filtered = dedup(raw, Math.max(2, props.getWebTopK()));
            urls = collectUrls(filtered);
            try {
                TraceStore.put("needle.filter.fallback", true);
            } catch (Exception ignore) {
            }
        }

        try {
            TraceStore.put("needle.triggered", true);
            TraceStore.put("needle.plan.reason", plan.reason());
            TraceStore.put("needle.plan.queries", plan.needleQueries());
            TraceStore.put("needle.plan.siteHints", plan.siteHints());
            TraceStore.put("needle.plan.keywords", plan.coreKeywords());
            TraceStore.put("needle.docs.count", (filtered == null ? 0 : filtered.size()));
            TraceStore.put("needle.urls.count", (urls == null ? 0 : urls.size()));
        } catch (Exception ignore) {
        }

        return new Result(true, plan,
                (filtered == null ? List.of() : List.copyOf(filtered)),
                (urls == null ? Set.of() : Set.copyOf(urls)));
    }

    public int maxCandidatePool(int keepN) {
        int cap = props.getMaxCandidatePool();
        if (cap <= 0) {
            cap = Math.max(keepN * 3, keepN);
        }
        cap = Math.max(cap, keepN);
        cap = Math.min(cap, Math.max(keepN * 6, keepN));
        return cap;
    }

    public int secondPassCandidateCap(int keepN, int mergedSize, RerankKnobResolver.Resolved rerankKnobs) {
        int cap = props.getSecondPassCandidateCap();
        if (cap <= 0) {
            cap = Math.max(keepN * 2, keepN);
        }
        if (rerankKnobs != null && rerankKnobs.ceTopK() != null && rerankKnobs.ceTopK() > 0) {
            cap = Math.min(cap, rerankKnobs.ceTopK());
        }
        cap = Math.max(cap, keepN);
        cap = Math.min(cap, mergedSize);
        cap = Math.min(cap, maxCandidatePool(keepN));
        return cap;
    }

    private Plan buildPlan(String userQuery, Quality quality) {
        SelectedTerms selected = null;
        try {
            Object o = TraceStore.get("selectedTerms");
            if (o instanceof SelectedTerms st) {
                selected = st;
            }
        } catch (Exception ignore) {
        }

        List<String> core = new ArrayList<>();
        if (selected != null) {
            if (selected.getExact() != null && !selected.getExact().isEmpty()) {
                String exact = selected.getExact().get(0);
                if (exact != null && !exact.isBlank()) {
                    core.add("\"" + exact.trim() + "\"");
                }
            }
            if (selected.getMust() != null) {
                for (String m : selected.getMust()) {
                    if (m != null && !m.isBlank()) {
                        core.add(m.trim());
                    }
                    if (core.size() >= 4) break;
                }
            }
        }
        if (core.isEmpty()) {
            core.addAll(extractTerms(userQuery, 4));
        }

        String profile = normalizeProfile(selected != null ? selected.getDomainProfile() : null, userQuery);

        List<String> sites = new ArrayList<>();
        // 1) dynamic domains (if enabled)
        if (props.isAllowDynamicDomains() && selected != null && selected.getDomains() != null) {
            sites.addAll(selected.getDomains());
        }
        // 2) domain-profile specific site pool
        Map<String, List<String>> byProfile = props.getSiteHintsByDomainProfile();
        if (byProfile != null && profile != null && !profile.isBlank()) {
            List<String> pool = byProfile.get(profile);
            if (pool == null) pool = byProfile.get(profile.toUpperCase(Locale.ROOT));
            if (pool != null) sites.addAll(pool);
        }
        // 3) fallback GENERAL pool
        if (props.getSiteHints() != null) {
            sites.addAll(props.getSiteHints());
        }
        sites = sanitizeDomains(sites);

        int maxQueries = Math.max(1, props.getMaxExtraQueries());
        int maxSites = Math.max(0, maxQueries - 1);
        int nSites = Math.min(maxSites, sites.size());
        nSites = Math.max(0, nSites);
        List<String> pickedSites = pickSitesStable(userQuery + "|" + profile, sites, nSites);

        List<String> queries = new ArrayList<>();
        String languageBoost = looksLikeHangul(userQuery) ? "공식" : "official";
        String year = (props.isAutoYear() && looksLikeLatest(userQuery)) ? String.valueOf(Year.now().getValue()) : "";

        // ✅ Baseline query (no site constraint). Avoids over-constraining to wrong sites.
        String baseline = buildNeedleQuery(core, selected, languageBoost, year, null);
        if (baseline != null && !baseline.isBlank()) {
            queries.add(baseline);
        }

        for (String site : pickedSites) {
            if (queries.size() >= maxQueries) break;
            String nq = buildNeedleQuery(core, selected, languageBoost, year, site);
            if (nq != null && !nq.isBlank()) {
                queries.add(nq);
            }
        }

        if (queries.isEmpty()) {
            String nq = buildNeedleQuery(core, selected, languageBoost, year, "");
            if (nq != null && !nq.isBlank()) {
                queries.add(nq);
            }
        }

        LinkedHashSet<String> uniqQ = new LinkedHashSet<>();
        for (String s : queries) {
            if (s != null && !s.isBlank()) {
                uniqQ.add(s.trim());
            }
        }
        queries = new ArrayList<>(uniqQ);

        String reason = "weak_evidence";
        try {
            if (quality.totalDocs() < props.getTriggerMinTopDocs()) {
                reason = "few_docs";
            } else if (quality.authorityAvg() < props.getTriggerMinAuthorityAvg()) {
                reason = "low_authority";
            } else if (quality.duplicateRatio() > props.getTriggerMaxDuplicateRatio()) {
                reason = "high_dup";
            } else if (quality.coverage() < props.getTriggerMinCoverage()) {
                reason = "low_coverage";
            }
        } catch (Exception ignore) {
        }

        return new Plan(List.copyOf(queries), List.copyOf(pickedSites), List.copyOf(core), quality, reason);
    }

    private static String buildNeedleQuery(List<String> core,
                                          SelectedTerms selected,
                                          String boostWord,
                                          String year,
                                          String site) {
        List<String> parts = new ArrayList<>();
        if (core != null) {
            for (String k : core) {
                if (k == null || k.isBlank()) continue;
                parts.add(k.trim());
                if (parts.size() >= 4) break;
            }
        }

        if (selected != null && selected.getAliases() != null && !selected.getAliases().isEmpty()) {
            String a = selected.getAliases().get(0);
            if (a != null && !a.isBlank() && parts.stream().noneMatch(p -> p.equalsIgnoreCase(a.trim()))) {
                parts.add(a.trim());
            }
        }

        if (boostWord != null && !boostWord.isBlank()) {
            parts.add(boostWord);
        }
        if (year != null && !year.isBlank()) {
            parts.add(year);
        }
        if (site != null && !site.isBlank()) {
            parts.add("site:" + site);
        }

        String q = String.join(" ", parts).trim();
        if (q.length() > 180) {
            q = q.substring(0, 180).trim();
        }
        return q;
    }

    private boolean isEvidenceWeak(Quality quality, List<Content> topDocs) {
        final int totalDocs = (quality == null) ? (topDocs == null ? 0 : topDocs.size()) : quality.totalDocs();

        // Persist thresholds + evaluation to trace for ops-log tuning.
        TraceStore.put("needle.trigger.pass1.count", totalDocs);
        TraceStore.put("needle.trigger.th.minTopDocs", props.getTriggerMinTopDocs());
        TraceStore.put("needle.trigger.th.minAuthorityAvg", props.getTriggerMinAuthorityAvg());
        TraceStore.put("needle.trigger.th.maxDuplicateRatio", props.getTriggerMaxDuplicateRatio());
        TraceStore.put("needle.trigger.th.minCoverage", props.getTriggerMinCoverage());

        boolean noQuality = (quality == null);
        boolean emptyDocs = (topDocs == null || topDocs.isEmpty());
        boolean insufficientDocs = (!emptyDocs) && (totalDocs < props.getTriggerMinTopDocs());

        boolean lowAuthority = (!noQuality) && (quality.authorityAvg() < props.getTriggerMinAuthorityAvg());
        boolean highDuplicate = (!noQuality) && (quality.duplicateRatio() > props.getTriggerMaxDuplicateRatio());
        boolean lowCoverage = (!noQuality) && (quality.coverage() < props.getTriggerMinCoverage());

        TraceStore.put("needle.trigger.eval.noQuality", noQuality);
        TraceStore.put("needle.trigger.eval.emptyDocs", emptyDocs);
        TraceStore.put("needle.trigger.eval.insufficientTopDocs", insufficientDocs);
        TraceStore.put("needle.trigger.eval.lowAuthorityAvg", lowAuthority);
        TraceStore.put("needle.trigger.eval.highDuplicateRatio", highDuplicate);
        TraceStore.put("needle.trigger.eval.lowCoverage", lowCoverage);

        List<String> reasons = new ArrayList<>();
        if (noQuality) reasons.add("noQuality");
        if (emptyDocs) reasons.add("emptyDocs");
        if (insufficientDocs) reasons.add("insufficientTopDocs");
        if (lowAuthority) reasons.add("lowAuthorityAvg");
        if (highDuplicate) reasons.add("highDuplicateRatio");
        if (lowCoverage) reasons.add("lowCoverage");
        TraceStore.put("needle.trigger.reasons", String.join(",", reasons));

        return noQuality || emptyDocs || insufficientDocs || lowAuthority || highDuplicate || lowCoverage;
    }

    private Quality scoreEvidenceQuality(String query, List<Content> topDocs) {
        if (topDocs == null || topDocs.isEmpty()) {
            return Quality.empty();
        }

        List<String> urls = new ArrayList<>();
        List<String> domains = new ArrayList<>();
        for (Content c : topDocs) {
            String u = extractUrlOrNull(c);
            if (u != null && !u.isBlank()) {
                urls.add(u);
                String host = hostOf(u);
                if (host != null && !host.isBlank()) {
                    domains.add(host);
                }
            }
        }

        int total = topDocs.size();
        int withUrl = urls.size();

        double authorityAvg = 0.0;
        if (!urls.isEmpty()) {
            double sum = 0.0;
            int n = 0;
            for (String u : new LinkedHashSet<>(urls)) {
                double s = authorityScore(u);
                sum += s;
                n++;
            }
            authorityAvg = (n == 0) ? 0.0 : (sum / n);
        }

        double duplicateRatio = 1.0;
        int uniqueDomains = 0;
        if (!domains.isEmpty()) {
            uniqueDomains = new LinkedHashSet<>(domains).size();
            duplicateRatio = 1.0 - (uniqueDomains / (double) domains.size());
        }

        double coverage = 0.0;
        List<String> terms = extractTerms(query, 8);
        if (!terms.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Content c : topDocs) {
                try {
                    String t = (c.textSegment() != null) ? c.textSegment().text() : null;
                    if (t != null) sb.append(t).append('\n');
                } catch (Exception ignore) {
                }
            }
            String evidence = sb.toString().toLowerCase(Locale.ROOT);
            int hit = 0;
            for (String term : terms) {
                if (term == null || term.isBlank()) continue;
                if (evidence.contains(term.toLowerCase(Locale.ROOT))) {
                    hit++;
                }
            }
            coverage = hit / (double) terms.size();
        }

        return new Quality(authorityAvg, duplicateRatio, coverage, total, withUrl, uniqueDomains);
    }

    private double authorityScore(String url) {
        if (url == null || url.isBlank()) return 0.0;
        try {
            return authorityScorer.weightFor(url);
        } catch (Exception ignore) {
            return 0.0;
        }
    }

    private List<Content> filterAndDedupByAuthority(List<Content> raw, double authorityMin, int keepN) {
        try {
            TraceStore.put("needle.authorityFilter.threshold", authorityMin);
            TraceStore.put("needle.authorityFilter.rawCount", (raw == null ? 0 : raw.size()));
        } catch (Exception ignore) {
        }

        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        final int cap = maxCandidatePool(Math.max(1, keepN));
        try {
            TraceStore.put("needle.authorityFilter.cap", cap);
        } catch (Exception ignore) {
        }

        // Domain-level authority filtering: keep the best (highest authority) doc per domain,
        // then sort by authority so later stages see the most trustworthy candidates first.
        final Map<String, Content> bestByDomain = new LinkedHashMap<>();
        final Map<String, Double> bestScore = new HashMap<>();

        for (Content c : raw) {
            if (c == null || c.textSegment() == null || c.textSegment().text() == null) {
                continue;
            }
            String text = c.textSegment().text();
            if (text.isBlank()) continue;

            String url = extractUrlOrNull(c);
            if (url == null || url.isBlank()) {
                continue;
            }

            String host = hostOf(url);
            if (host == null || host.isBlank()) {
                continue;
            }

            double score = authorityScore(url);
            if (score + 1e-9 < authorityMin) {
                continue;
            }

            Double prev = bestScore.get(host);
            if (prev == null || score > prev) {
                bestByDomain.put(host, c);
                bestScore.put(host, score);
            }
        }

        List<Content> filtered = new ArrayList<>(bestByDomain.values());
        filtered.sort(Comparator.comparingDouble((Content c) -> {
            String u = extractUrlOrNull(c);
            return (u == null ? 0.0 : authorityScore(u));
        }).reversed());

        if (filtered.size() > cap) {
            filtered = new ArrayList<>(filtered.subList(0, cap));
        }

        try {
            TraceStore.put("needle.authorityFilter.uniqueDomains", bestByDomain.size());
            TraceStore.put("needle.authorityFilter.keptCount", filtered.size());
            TraceStore.put("needle.authorityFilter.keptRatio", ((double) filtered.size()) / Math.max(1, raw.size()));
            if (filtered.isEmpty()) {
                TraceStore.put("needle.authorityFilteredEmpty", true);
            }
        } catch (Exception ignore) {
        }

        return filtered;
    }

    private static List<Content> dedup(List<Content> docs, int max) {
        if (docs == null || docs.isEmpty()) return List.of();
        LinkedHashMap<String, Content> uniq = new LinkedHashMap<>();
        for (Content c : docs) {
            if (c == null || c.textSegment() == null) continue;
            String t = c.textSegment().text();
            if (t == null || t.isBlank()) continue;
            String url = extractUrlOrNull(c);
            String key = (url != null && !url.isBlank()) ? ("url:" + url) : ("txt:" + t.strip());
            uniq.putIfAbsent(key, c);
            if (max > 0 && uniq.size() >= max) break;
        }
        return new ArrayList<>(uniq.values());
    }

    private static Set<String> collectUrls(List<Content> docs) {
        if (docs == null || docs.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Content c : docs) {
            String u = extractUrlOrNull(c);
            if (u != null && !u.isBlank()) {
                out.add(u);
            }
        }
        return out;
    }

    private static String extractUrlOrNull(Content c) {
        if (c == null || c.textSegment() == null) return null;
        try {
            try {
                var meta = c.textSegment().metadata();
                if (meta != null) {
                    String url = meta.getString("url");
                    if (url == null || url.isBlank()) {
                        url = meta.getString("source");
                    }
                    if (url != null && !url.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(url);
                    }
                }
            } catch (Exception ignore) {
            }

            String text = c.textSegment().text();
            if (text == null || text.isBlank()) return null;

            try {
                String href = HtmlTextUtil.extractFirstHref(text);
                if (href != null && !href.isBlank()) {
                    return HtmlTextUtil.normalizeUrl(href);
                }
            } catch (Exception ignore) {
            }

            Matcher m = URL_IN_TEXT.matcher(text);
            if (m.find()) {
                return HtmlTextUtil.normalizeUrl(m.group(1));
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url);
            String h = uri.getHost();
            if (h == null || h.isBlank()) return null;
            String host = h.toLowerCase(Locale.ROOT).trim();

            // Normalize common prefixes so that www./m./amp. don't inflate unique-domain counts
            // and so the authority filter can deduplicate more effectively.
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            // Only strip the following prefixes when this is clearly a subdomain (3+ labels).
            int dotCount = (int) host.chars().filter(ch -> ch == '.').count();
            if (dotCount >= 2) {
                if (host.startsWith("m.")) {
                    host = host.substring(2);
                }
                if (host.startsWith("amp.")) {
                    host = host.substring(4);
                }
            }
            return host;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static boolean looksLikeHangul(String s) {
        if (s == null || s.isBlank()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '가' && ch <= '힣') {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeLatest(String query) {
        if (query == null) return false;
        String lower = query.toLowerCase(Locale.ROOT);
        for (String h : LATEST_HINTS) {
            if (h == null || h.isBlank()) continue;
            if (lower.contains(h.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * Normalize SelectedTerms.domainProfile for use in domain-specific probe pools.
     *
     * <p>If no profile is provided, we use a tiny heuristic so TECH/PRODUCT queries
     * avoid being constrained to unrelated sites (e.g., health authorities) and vice versa.</p>
     */
    private static String normalizeProfile(String profile, String userQuery) {
        if (profile == null || profile.isBlank()) {
            String q = (userQuery == null) ? "" : userQuery.toLowerCase(Locale.ROOT);
            // very light heuristics
            if (q.contains("사양") || q.contains("스펙") || q.contains("가격") || q.contains("출시") || q.contains("spec")) {
                return "PRODUCT";
            }
            if (q.contains("원리") || q.contains("정의") || q.contains("유도") || q.contains("explain") || q.contains("what is")) {
                return "EDUCATION";
            }
            return "GENERAL";
        }
        return profile.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> extractTerms(String query, int maxTerms) {
        if (query == null) return List.of();
        String cleaned = query
                .replaceAll("[\\p{Punct}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) return List.of();
        String[] toks = cleaned.split(" ");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : toks) {
            if (t == null) continue;
            t = t.trim();
            if (t.isBlank()) continue;
            if (t.length() <= 1) continue;
            if (STOPWORDS.contains(t.toLowerCase(Locale.ROOT))) continue;
            out.add(t);
            if (out.size() >= maxTerms) break;
        }
        return new ArrayList<>(out);
    }

    private static List<String> sanitizeDomains(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isBlank()) continue;
            v = v.replace("site:", "").trim();
            try {
                if (v.startsWith("http://") || v.startsWith("https://")) {
                    URI u = URI.create(v);
                    if (u.getHost() != null) v = u.getHost();
                } else if (v.contains("/")) {
                    v = v.split("/")[0];
                }
            } catch (Exception ignore) {
            }
            v = v.toLowerCase(Locale.ROOT);
            if (v.length() > 80) continue;
            if (!v.contains(".")) continue;
            if (!v.matches("^[a-z0-9.-]+$")) continue;
            out.add(v);
            if (out.size() >= 40) break;
        }
        return new ArrayList<>(out);
    }

    private static List<String> pickSitesStable(String userQuery, List<String> sites, int n) {
        if (sites == null || sites.isEmpty() || n <= 0) return List.of();
        int seed = Objects.hash(userQuery, Objects.toString(TraceStore.get("trace.runId"), ""));
        Random rnd = new Random(seed);
        List<String> copy = new ArrayList<>(sites);
        Collections.shuffle(copy, rnd);
        if (n >= copy.size()) return copy;
        return copy.subList(0, n);
    }
}
