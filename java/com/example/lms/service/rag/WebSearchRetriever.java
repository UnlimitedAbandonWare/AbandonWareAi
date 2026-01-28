package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.util.HtmlTextUtil;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import java.util.List;
import com.example.lms.service.rag.filter.GenericDocClassifier;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.filter.EducationDocClassifier;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.regex.Pattern; /* 🔴 NEW */

@org.springframework.stereotype.Component
public class WebSearchRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(WebSearchRetriever.class);
    private final WebSearchProvider webSearchProvider;
    /**
     * Aggregate web search across multiple providers. This component fans
     * out to the configured {@link com.acme.aicore.domain.ports.WebSearchProvider}
     * implementations (Bing/Naver/Brave) in priority order and merges the
     * results. When present it allows the web retrieval stage to fall
     * back to additional providers when the primary Naver results are
     * insufficient. It is optional and may be null when no providers
     * are configured.
     */
    private final com.acme.aicore.adapters.search.CachedWebSearch multiSearch;
    // 스프링 프로퍼티로 주입(생성자 주입의 int 빈 문제 회피)
    @org.springframework.beans.factory.annotation.Value("${rag.search.top-k:5}")
    private int topK;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QueryContextPreprocessor preprocessor;

    @org.springframework.beans.factory.annotation.Value("${privacy.boundary.block-web-search:false}")
    private boolean blockWebSearch;

    @org.springframework.beans.factory.annotation.Value("${privacy.boundary.block-web-search-on-sensitive:false}")
    private boolean blockWebSearchOnSensitive;

    private final com.example.lms.service.rag.extract.PageContentScraper pageScraper;
    // 최소 3개 이상의 스니펫을 유지해 LLM 컨텍스트를 풍부하게 한다.
    private static final int MIN_SNIPPETS = 3;
    // 도메인 신뢰도 점수로 정렬 가중
    private final com.example.lms.service.rag.auth.AuthorityScorer authorityScorer;
    // 범용 판정기는 주입받아 도메인별로 동작하도록 한다.
    private final GenericDocClassifier genericClassifier;
    // 질의 도메인 추정기
    private final com.example.lms.service.rag.detector.GameDomainDetector domainDetector;
    // 교육 토픽 분류기: 교육 도메인일 때 스니펫 필터링에 사용된다.
    private final com.example.lms.service.rag.filter.EducationDocClassifier educationClassifier;
    private static final Pattern META_TAG = Pattern.compile("\\[[^\\]]+\\]");
    private static final Pattern TIME_TAG = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");
    /* 🔵 봇/캡차 페이지 힌트 */
    /* 등에서 반환되는 캡차/봇 차단 힌트 제거용 */
    private static final Pattern CAPTCHA_HINT = Pattern.compile(
            "(?i)(captcha|are you (a )?robot|unusual\\s*traffic|verify you are human|\\.com/captcha|bots\\s*use\\s*)");

    // Extract site: filters from a query (e.g., "site:wikipedia.org").
    // We reuse these for "cheap retry" where we want to filter already-prefetched
    // SERP
    // results instead of doing additional external calls.
    private static final Pattern SITE_FILTER = Pattern.compile("(?i)\\bsite:([^\\s\\)]+)");

    private static final String SERP_CACHE_TRACE_KEY = "webSearch.serpCache";
    private static final int SERP_CACHE_MAX = 32;

    public WebSearchRetriever(
            WebSearchProvider webSearchProvider,
            com.acme.aicore.adapters.search.CachedWebSearch multiSearch,
            com.example.lms.service.rag.extract.PageContentScraper pageScraper,
            com.example.lms.service.rag.auth.AuthorityScorer authorityScorer,
            GenericDocClassifier genericClassifier,
            com.example.lms.service.rag.detector.GameDomainDetector domainDetector,
            com.example.lms.service.rag.filter.EducationDocClassifier educationClassifier) {
        this.webSearchProvider = webSearchProvider;
        this.multiSearch = multiSearch;
        this.pageScraper = pageScraper;
        this.authorityScorer = authorityScorer;
        this.genericClassifier = genericClassifier;
        this.domainDetector = domainDetector;
        this.educationClassifier = educationClassifier;
    }

    private static String normalize(String raw) { /* 🔴 NEW */
        if (raw == null)
            return "";

        String s = META_TAG.matcher(raw).replaceAll("");
        s = TIME_TAG.matcher(s).replaceAll("");
        return s.replace("\n", " ").trim();
    }

    /**
     * Extract a version token from the query string. A version is defined
     * as two numeric components separated by a dot or middot character. If
     * no such token is present, {@code null} is returned.
     *
     * @param q the query text
     * @return the extracted version (e.g. "5.8") or null
     */
    private static String extractVersion(String q) {
        if (q == null)
            return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)[\\.·](\\d+)").matcher(q);
        return m.find() ? (m.group(1) + "." + m.group(2)) : null;
    }

    /**
     * Build a regex that matches the exact version token in text. Dots in
     * the version are replaced with a character class that matches dot or
     * middot to handle variations in punctuation. Anchors ensure that
     * longer numbers containing the version as a substring are not falsely
     * matched.
     *
     * @param v the version string (e.g. "5.8")
     * @return a compiled regex pattern matching the exact token
     */
    private static java.util.regex.Pattern versionRegex(String v) {
        String core = v.replace(".", "[\\.·\\s]");
        return java.util.regex.Pattern.compile("(?<!\\d)" + core + "(?!\\d)");
    }

    /* ✅ 선호 도메인: 제거가 아닌 '우선 정렬'만 수행 */
    private static final List<String> PREFERRED = List.of(
            // 공식/권위
            "genshin.hoyoverse.com", "hoyoverse.com", "hoyolab.com",
            "wikipedia.org", "eulji.ac.kr", "ac.kr", "go.kr",
            // 한국 커뮤니티·블로그(삭제 X, 단지 후순위)
            "namu.wiki", "blog.naver.com");

    private static boolean containsPreferred(String s) {
        return PREFERRED.stream().anyMatch(s::contains);
    }

    @Override
    public List<Content> retrieve(Query query) {
        String normalized = normalize(query != null ? query.text() : "");

        java.util.Map<String, Object> meta = toMetaMap(query);
        meta.putIfAbsent("purpose", "WEB_SEARCH");

        var gctx = GuardContextHolder.get();
        boolean sensitive = gctx != null && gctx.isSensitiveTopic();
        boolean planBlockAll = gctx != null && gctx.planBool("privacy.boundary.block-web-search", false);
        boolean planBlockOnSensitive = gctx != null
                && gctx.planBool("privacy.boundary.block-web-search-on-sensitive", false);
        if (blockWebSearch || planBlockAll || (sensitive && (blockWebSearchOnSensitive || planBlockOnSensitive))) {
            TraceStore.put("privacy.web.blocked", true);
            return java.util.Collections.emptyList();
        }

        if (preprocessor != null) {
            try {
                normalized = preprocessor.enrich(normalized, meta);
            } catch (Exception e) {
                log.debug("[WebSearchRetriever] preprocessor failed: {}", e.toString());
            }
        }

        // If the caller appended "site:" filters (e.g., detour/cheap-retry or
        // user-specified),
        // try to reuse the base SERP snippets we already have (prefetch/trace cache)
        // instead of triggering a new
        // external search call. This helps prevent "web=0" starvation loops when a base
        // SERP was already fetched.
        final java.util.List<String> requestedSites = extractSiteFilters(normalized);
        final boolean hasSiteFilters = requestedSites != null && !requestedSites.isEmpty();
        final String baseQueryKey = canonicalBaseQuery(normalized);
        // 쿼리 도메인 추정: null 가능성을 고려하여 GENERAL 기본값 사용
        String domain = domainDetector != null ? domainDetector.detect(normalized) : "GENERAL";
        boolean isGeneral = "GENERAL".equalsIgnoreCase(domain);

        int reqTopK = metaInt(meta, "webTopK", this.topK);
        long webBudgetMs = metaLong(meta, "webBudgetMs", -1L);
        boolean allowWeb = metaBool(meta, "allowWeb", true);
        if (!allowWeb) {
            return java.util.Collections.emptyList();
        }

        int k = Math.max(reqTopK, MIN_SNIPPETS);
        int maxAttempts = (webBudgetMs > 0 ? (webBudgetMs <= 1500 ? 1 : (webBudgetMs <= 3000 ? 2 : 3)) : 3);

        // Extract a version token from the query. When present, enforce that
        // each snippet contains the exact version. This helps prevent
        // contamination from neighbouring versions (e.g. 5.7 or 5.9) when the
        // user asks about a specific patch.
        String ver = extractVersion(normalized);
        java.util.regex.Pattern must = (ver != null) ? versionRegex(ver) : null;
        // 1) 1차 수집: (prefetch가 있으면 재사용) → 없으면 topK*2 → 중복/정렬 후 topK
        //
        // Additionally: when the caller added site: filters, try to reuse the same base
        // SERP snippets
        // (prefetch or request-scoped cache) by filtering them by the requested sites.
        boolean usedPrefetched = false;
        boolean reusedBaseSerp = false;
        String reusedBaseSerpSource = null;
        List<String> first = null;

        Object pq = meta.get("prefetch.web.query");
        Object ps = meta.get("prefetch.web.snippets");
        java.util.List<String> prefetchSnips = java.util.Collections.emptyList();
        String prefetchKeyNorm = null;

        if (pq != null && ps instanceof java.util.List<?> raw) {
            prefetchKeyNorm = normalize(String.valueOf(pq));
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object o : raw) {
                if (o == null)
                    continue;
                String s = String.valueOf(o).trim();
                if (!s.isBlank())
                    out.add(s);
            }
            prefetchSnips = out;

            // Exact-match prefetch (unchanged behaviour)
            if (!prefetchKeyNorm.isBlank() && prefetchKeyNorm.equalsIgnoreCase(normalized) && !out.isEmpty()) {
                usedPrefetched = true;
                first = out;
                reusedBaseSerpSource = "prefetch.exact";
            }
        }

        // Base-key prefetch/cache reuse for "site:" filters:
        // - Filter the already-fetched base SERP (prefetch or request-scoped cache)
        // - If the filtered set is large enough, skip a new external call
        // - Otherwise, keep it as a seed and fall back to a real site-filtered search
        java.util.List<String> siteReuseSeed = null;
        final int minSiteFilteredDocsToSkipSearch = computeSiteFilterMinDocsToSkipSearch(meta, k);
        com.example.lms.search.TraceStore.put("webSearch.siteFilterMinDocsToSkipSearch",
                minSiteFilteredDocsToSkipSearch);

        // Base-key prefetch reuse for site: filters
        if (first == null && hasSiteFilters && prefetchKeyNorm != null && !prefetchKeyNorm.isBlank()
                && !prefetchSnips.isEmpty()) {
            String prefetchBaseKey = canonicalBaseQuery(prefetchKeyNorm);
            if (!prefetchBaseKey.isBlank() && prefetchBaseKey.equalsIgnoreCase(baseQueryKey)) {
                java.util.List<String> filtered = filterSnippetsBySites(prefetchSnips, requestedSites, k * 2);
                if (!filtered.isEmpty()) {
                    reusedBaseSerp = true;
                    if (filtered.size() >= minSiteFilteredDocsToSkipSearch) {
                        usedPrefetched = true;
                        reusedBaseSerpSource = "prefetch.base+siteFilter:skip";
                        com.example.lms.search.TraceStore.put("webSearch.siteFilterReuseMode", "skip");
                        first = filtered;
                    } else {
                        reusedBaseSerpSource = "prefetch.base+siteFilter:seed";
                        com.example.lms.search.TraceStore.put("webSearch.siteFilterReuseMode", "seed");
                        siteReuseSeed = filtered;
                    }
                }
            }
        }

        // Request-scoped SERP cache reuse for site: filters
        if (first == null && hasSiteFilters && !baseQueryKey.isBlank()) {
            java.util.List<String> cachedBase = serpCacheGet(baseQueryKey);
            if (cachedBase != null && !cachedBase.isEmpty()) {
                java.util.List<String> filtered = filterSnippetsBySites(cachedBase, requestedSites, k * 2);
                if (!filtered.isEmpty()) {
                    reusedBaseSerp = true;
                    if (filtered.size() >= minSiteFilteredDocsToSkipSearch) {
                        usedPrefetched = true;
                        reusedBaseSerpSource = "trace.base+siteFilter:skip";
                        com.example.lms.search.TraceStore.put("webSearch.siteFilterReuseMode", "skip");
                        first = filtered;
                    } else {
                        reusedBaseSerpSource = "trace.base+siteFilter:seed";
                        com.example.lms.search.TraceStore.put("webSearch.siteFilterReuseMode", "seed");
                        if (siteReuseSeed == null || siteReuseSeed.isEmpty()) {
                            siteReuseSeed = filtered;
                        } else {
                            java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(siteReuseSeed);
                            merged.addAll(filtered);
                            siteReuseSeed = merged.stream().limit(k * 2L).toList();
                        }
                    }
                }
            }
        }

        if (!usedPrefetched) {
            java.util.List<String> searched = searchWithAggressiveRetry(
                    normalized,
                    k * 2,
                    must,
                    maxAttempts,
                    webBudgetMs);

            if (siteReuseSeed != null && !siteReuseSeed.isEmpty()) {
                com.example.lms.search.TraceStore.put("webSearch.siteFilterReuseSeedCount", siteReuseSeed.size());
                if (searched == null || searched.isEmpty()) {
                    first = siteReuseSeed;
                } else {
                    java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
                    merged.addAll(siteReuseSeed);
                    merged.addAll(searched);
                    first = new java.util.ArrayList<>(merged);
                    com.example.lms.search.TraceStore.put("webSearch.siteFilterReuseMerged", true);
                }
            } else {
                first = searched;
            }
        }

        // Persist the base SERP snippets for later site-filter reuse within the same
        // request.
        // Never cache "site:"-filtered queries to avoid polluting the base cache.
        if (!hasSiteFilters && first != null && !first.isEmpty() && !baseQueryKey.isBlank()) {
            serpCachePut(baseQueryKey, first);
        }

        com.example.lms.search.TraceStore.put("webSearch.reusedBaseSerp", reusedBaseSerp);
        com.example.lms.search.TraceStore.put("webSearch.reusedBaseSerpSource", reusedBaseSerpSource);

        // 🚀 Fan-out to additional providers via CachedWebSearch. When the
        // primary Naver results are fewer than the desired count, fetch
        // supplementary snippets from other providers (e.g. Bing/Brave). The
        // CachedWebSearch component merges provider responses according to
        // provider priorities and caches the result. Failures are
        // intentionally swallowed to avoid impacting the main retrieval.
        List<String> supplemental = java.util.Collections.emptyList();
        // Only fan-out when the primary provider returned fewer than the desired count.
        if (multiSearch != null && (first == null || first.size() < k)
                && (!usedPrefetched
                        || (hasSiteFilters && reusedBaseSerpSource != null
                                && reusedBaseSerpSource.contains("siteFilter:skip"))
                        || metaBool(meta, "web.multiSearch.allowWhenPrefetched", false))) {
            try {
                var q = new com.acme.aicore.domain.model.WebSearchQuery(normalized);
                // [Patch] Limit fanout to two providers (Naver, Brave) and
                // allow up to 5 seconds to account for network variability.
                var bundle = multiSearch.searchMulti(q, 2)
                        .block(java.time.Duration
                                .ofMillis(webBudgetMs > 0 ? Math.min(5000L, Math.max(600L, webBudgetMs)) : 5000L));
                if (bundle != null && bundle.docs() != null) {
                    supplemental = bundle.docs().stream()
                            .map(d -> {
                                String core = (d.title() + " - " + d.snippet()).trim();
                                return core.isBlank() ? d.url() : core;
                            })
                            .filter(s -> s != null && !s.isBlank())
                            .toList();
                }
            } catch (Exception e) {
                // ignore errors; supplemental remains empty
            }
        }

        // Prepend the supplemental results to the primary list, ensuring
        // duplicates are removed while preserving order. This prioritises
        // provider results before applying ranking heuristics below. Only
        // the first (topK*2) snippets are considered to limit memory usage.
        if (supplemental != null && !supplemental.isEmpty()) {
            java.util.LinkedHashSet<String> combined = new java.util.LinkedHashSet<>();
            combined.addAll(supplemental);
            combined.addAll(first);
            first = combined.stream().limit(k * 2).toList();
        }

        // Refresh the request-scoped SERP cache with the widest (supplemental+first)
        // list.
        // This gives the "cheap retry" site-filter path more chances to find host
        // matches
        // without triggering a new search.
        if (!hasSiteFilters && first != null && !first.isEmpty() && !baseQueryKey.isBlank()) {
            serpCachePut(baseQueryKey, first);
        }
        if (log.isDebugEnabled()) {
            log.debug("[WebSearchRetriever] first raw={} (q='{}')", first.size(), normalized);
        }
        // 선호+ 도메인 Authority 가중 정렬(삭제 아님). 범용 페널티는 GENERAL/EDUCATION 도메인에서는 제거
        List<String> ranked = first.stream()
                .distinct()
                .sorted((a, b) -> {
                    double aw = authorityScorer.weightFor(extractUrl(a))
                            - (isGeneral ? 0.0 : genericClassifier.penalty(a, domain));
                    double bw = authorityScorer.weightFor(extractUrl(b))
                            - (isGeneral ? 0.0 : genericClassifier.penalty(b, domain));
                    int cmp = Double.compare(bw, aw); // high first (penalty 반영)
                    if (cmp != 0)
                        return cmp;
                    // 동률이면 선호 도메인 우선
                    return Boolean.compare(containsPreferred(b), containsPreferred(a));
                })
                .limit(k)
                .toList();
        // 범용 스니펫 컷: 도메인 특화(예: GENSHIN/EDU)에서만 적용
        // - 단, 필터 이후 결과가 0개가 되면("starved") 결선이 끊길 수 있으므로 FAIL-SOFT로 복구
        if (!isGeneral && genericClassifier != null) {
            List<String> beforeGenericCut = ranked;
            List<String> afterGenericCut = ranked.stream()
                    .filter(s -> !genericClassifier.isGenericSnippet(s, domain))
                    .limit(k)
                    .toList();
            if (afterGenericCut.isEmpty() && !beforeGenericCut.isEmpty()) {
                TraceStore.put("webSearch.genericCut.starved", true);
                ranked = beforeGenericCut;
            } else {
                ranked = afterGenericCut;
            }
        }

        // 2) 폴백: 지나친 공손어/호칭 정리
        List<String> fallback = (usedPrefetched || ranked.size() >= MIN_SNIPPETS) ? List.of()
                : webSearchProvider.search(normalized.replace("교수님", "교수").replace("님", ""), k);

        List<String> finalSnippets = java.util.stream.Stream.of(ranked, fallback)
                .flatMap(java.util.Collection::stream)
                .distinct()
                .limit(k)
                .toList();

        // If the detected domain is EDUCATION, apply an education topic filter
        // to remove snippets that are unrelated to education/academy topics. This
        // leverages the EducationDocClassifier to detect whether a snippet is
        // genuinely about education. Without this filter generic or noise
        // snippets from pet or automotive sites may contaminate the retrieval.
        if ("EDUCATION".equalsIgnoreCase(domain) && educationClassifier != null) {
            List<String> beforeEduCut = finalSnippets;
            List<String> afterEduCut = finalSnippets.stream()
                    .filter(s -> {
                        try {
                            return educationClassifier.isEducation(s);
                        } catch (Exception e) {
                            return true;
                        }
                    })
                    .limit(k)
                    .toList();

            // Fail-soft: if the classifier ends up filtering out everything,
            // keep the original list to avoid "fused=0" starvation.
            if (afterEduCut.isEmpty() && !beforeEduCut.isEmpty()) {
                TraceStore.put("webSearch.eduCut.starved", true);
                finalSnippets = beforeEduCut;
            } else {
                finalSnippets = afterEduCut;
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("[WebSearchRetriever] selected={} (topK={})", finalSnippets.size(), topK);
        }
        // 3) 각 결과의 URL 본문을 읽어 ‘질문-유사도’로 핵심 문단 추출
        String providerName = null;
        try {
            providerName = (webSearchProvider != null) ? webSearchProvider.getName() : null;
        } catch (Throwable ignore) {
            // ignore
        }
        if (providerName == null || providerName.isBlank()) {
            providerName = "web";
        }

        java.util.List<Content> out = new java.util.ArrayList<>();
        for (String s : finalSnippets) {
            String url = extractUrl(s); // ⬅️ 없던 util 메서드 추가(아래)
            if (url == null || CAPTCHA_HINT.matcher(s).find()) { // 🔒 의심 라인 스킵
                out.add(toWebContent(s, url, providerName)); // URL 없음 → 기존 스니펫 사용
                continue;
            }
            try {
                String body = pageScraper.fetchText(url, /* timeoutMs */6000);
                // SnippetPruner는 (String, String) 시그니처만 존재 → 단일 결과로 처리
                // 🔵 우리 쪽 간단 딥 스니펫 추출(임베딩 없이 키워드/길이 기반)
                String picked = pickByHeuristic(query.text(), body, 480);
                if (picked == null || picked.isBlank()) {
                    out.add(toWebContent(s, url, providerName));
                } else {
                    out.add(toWebContent(picked + "\n\n[출처] " + url, url, providerName));
                }
            } catch (Exception e) {
                log.debug("[WebSearchRetriever] scrape fail {} → fallback snippet", url);
                out.add(toWebContent(s, url, providerName));
            }
        }

        // Optional authorityMin filter (used by needle/probe stage). This is
        // fail-soft unless strict=true.
        // meta already defined at line 144, reuse the existing variable
        double authorityMin = metaDouble(meta, "web.authorityMin", -1.0);
        boolean strict = metaBool(meta, "web.authorityMin.strict", false);
        if (authorityMin > 0.0d) {
            int before = out.size();
            java.util.List<Content> filtered = out.stream()
                    .filter(c -> {
                        try {
                            String u = extractUrl(c.textSegment().text());
                            if (u == null || u.isBlank())
                                return false;
                            return authorityScorer.weightFor(u) >= authorityMin;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .toList();

            // If strict OR we still have a meaningful number of snippets, keep the filtered
            // set.
            if (strict || filtered.size() >= Math.min(MIN_SNIPPETS, before)) {
                out = new java.util.ArrayList<>(filtered);
                TraceStore.put("webSearch.authorityMin", authorityMin);
                TraceStore.put("webSearch.authorityMin.strict", strict);
                TraceStore.put("webSearch.authorityMin.before", before);
                TraceStore.put("webSearch.authorityMin.after", out.size());
            } else {
                TraceStore.put("webSearch.authorityMin.skipped", Boolean.TRUE);
            }
        }
        return out.stream().limit(k).toList();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> toMetaMap(Query query) {
        if (query == null || query.metadata() == null)
            return java.util.Collections.emptyMap();
        Object meta = query.metadata();
        if (meta instanceof java.util.Map<?, ?> raw) {
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            for (java.util.Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() != null)
                    out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        try {
            java.lang.reflect.Method m = meta.getClass().getMethod("asMap");
            Object v = m.invoke(meta);
            if (v instanceof java.util.Map<?, ?> m2) {
                java.util.Map<String, Object> out = new java.util.HashMap<>();
                for (java.util.Map.Entry<?, ?> e : m2.entrySet()) {
                    if (e.getKey() != null)
                        out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            }
        } catch (NoSuchMethodException ignore) {
            try {
                java.lang.reflect.Method m = meta.getClass().getMethod("map");
                Object v = m.invoke(meta);
                if (v instanceof java.util.Map<?, ?> m2) {
                    java.util.Map<String, Object> out = new java.util.HashMap<>();
                    for (java.util.Map.Entry<?, ?> e : m2.entrySet()) {
                        if (e.getKey() != null)
                            out.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    return out;
                }
            } catch (Exception ignore2) {
                return java.util.Collections.emptyMap();
            }
        } catch (Exception ignore) {
            return java.util.Collections.emptyMap();
        }
        return java.util.Collections.emptyMap();
    }

    /**
     * Computes the minimum number of docs required to skip an actual
     * (site-filtered) search when
     * we can instead reuse a cached/base SERP and filter it by host.
     *
     * Policy goal:
     * - Avoid the "0-doc / low-doc" loops when site filters are present by only
     * skipping when
     * we already have enough documents to satisfy both citation needs and rerank
     * needs.
     * - Still allow callers (e.g. guard detour cheap-retry) to override the
     * threshold via
     * {@code siteFilter.minDocsToSkipSearch}.
     */
    private static int computeSiteFilterMinDocsToSkipSearch(java.util.Map<String, Object> meta, int k) {
        int explicit = metaInt(meta, "siteFilter.minDocsToSkipSearch", -1);
        if (explicit > 0) {
            return Math.min(k, Math.max(1, explicit));
        }

        // The guard layer may pass the required citation count through metadata.
        int minCitations = metaInt(meta, "minCitations",
                metaInt(meta, "citationMin", metaInt(meta, "gate.citation.min", 0)));

        // Rerankers typically want a reasonably-sized candidate pool; use rerankTopK
        // when available.
        int rerankTopK = metaInt(meta, "rerank.topK",
                metaInt(meta, "rerankTopK", metaInt(meta, "rerank_top_k", 0)));
        // Default policy: only skip if we can still return a reasonably sized pool.
        // Start from k (requested docs), then relax to rerankTopK when present (we only
        // need as many as
        // downstream rerank keeps), and finally ensure citations / minimum snippet
        // floor are satisfied.
        int desired = k;
        if (rerankTopK > 0)
            desired = Math.min(desired, rerankTopK);

        desired = Math.max(desired, MIN_SNIPPETS);
        if (minCitations > 0)
            desired = Math.max(desired, minCitations);

        // Never demand more than k for a skip decision.
        desired = Math.min(k, desired);
        return Math.max(1, desired);
    }

    private static int metaInt(java.util.Map<String, Object> meta, String key, int def) {
        if (meta == null)
            return def;
        Object v = meta.get(key);
        if (v instanceof Number n)
            return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception ignore) {
            }
        }
        return def;
    }

    private static long metaLong(java.util.Map<String, Object> meta, String key, long def) {
        if (meta == null)
            return def;
        Object v = meta.get(key);
        if (v instanceof Number n)
            return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (Exception ignore) {
            }
        }
        return def;
    }

    private static double metaDouble(java.util.Map<String, Object> meta, String key, double def) {
        if (meta == null)
            return def;
        Object v = meta.get(key);
        if (v instanceof Number n)
            return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception ignore) {
            }
        }
        return def;
    }

    private static boolean metaBool(java.util.Map<String, Object> meta, String key, boolean def) {
        if (meta == null)
            return def;
        Object v = meta.get(key);
        if (v instanceof Boolean b)
            return b;
        if (v instanceof Number n)
            return n.intValue() != 0;
        if (v instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("true") || t.equals("1") || t.equals("yes"))
                return true;
            if (t.equals("false") || t.equals("0") || t.equals("no"))
                return false;
        }
        return def;
    }

    /**
     * TraceStore에서 boolean 값을 읽어오는 도우미 메서드.
     * 기존 metaBool 로직을 재사용하여 타입 변환 및 기본값을 처리합니다.
     */
    private static boolean traceBool(String key, boolean def) {
        return metaBool(com.example.lms.search.TraceStore.context(), key, def);
    }

    /**
     * [ECO-FIX v3.0] Aggressive Persistence Loop
     * 네이버/외부 검색이 타임아웃(3초) 또는 일시적 장애로 0건을 줄 때,
     * 포기하지 않고 최대 3회까지 재시도하여 결과를 확보하는 루프입니다.
     * 캡차/버전/태그 필터를 미리 적용해 "실질 유효 결과" 기준으로 성공을 판정합니다.
     */
    private List<String> searchWithAggressiveRetry(String query, int k, java.util.regex.Pattern mustVersion,
            int maxAttempts, long budgetMs) {
        int attempts = Math.max(1, maxAttempts);

        // Backoff policy: short exponential backoff (bounded by overall budget).
        final long baseBackoffMs = 250L;
        final long maxBackoffMs = 1500L;

        final long deadlineMs = (budgetMs > 0 ? System.currentTimeMillis() + budgetMs : Long.MAX_VALUE);

        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (System.currentTimeMillis() > deadlineMs) {
                log.warn("⚠️ [WebSearch] budget exhausted ({}ms). Stop retrying. query='{}'", budgetMs, query);
                break;
            }

            long start = System.currentTimeMillis();
            boolean hadRaw = false;

            try {
                List<String> rawResults = webSearchProvider.search(query, k);

                if (rawResults != null && !rawResults.isEmpty()) {
                    hadRaw = true;

                    List<String> valid = rawResults.stream()
                            .filter(s -> !CAPTCHA_HINT.matcher(s).find())
                            .filter(s -> mustVersion == null || mustVersion.matcher(s).find())
                            .filter(s -> {
                                String url = extractUrl(s);
                                if (url == null)
                                    return true;
                                String lower = url.toLowerCase();
                                return !(lower.contains("/tag/") || lower.contains("?tag="));
                            })
                            .toList();

                    if (!valid.isEmpty()) {
                        if (attempt > 1) {
                            log.info("✅ [WebSearch] Retry success on attempt {}/{} ({}ms). Found {} valid items.",
                                    attempt, attempts, System.currentTimeMillis() - start, valid.size());
                        }
                        return valid;
                    }

                    log.warn(
                            "⚠️ [WebSearch] Attempt {}/{} had results but all filtered out (Captcha/Version/Tag).",
                            attempt, attempts);
                } else {
                    log.warn("⚠️ [WebSearch] Attempt {}/{} returned 0 results.", attempt, attempts);
                }
            } catch (Exception e) {
                log.warn("🔥 [WebSearch] Attempt {}/{} error: {}.", attempt, attempts, e.getMessage());
            }

            // Decide whether a retry is worthwhile.
            if (attempt >= attempts) {
                break;
            }

            // If web is effectively down (both providers down / hybrid down), retries are
            // wasted.
            boolean effectiveDown = traceBool("orch.webRateLimited.effective", false)
                    || traceBool("orch.webRateLimited", false)
                    || traceBool("orch.webRateLimited.allDown", false)
                    || traceBool("orch.webRateLimited.hybridDown", false);

            long skippedCount = 0L;
            try {
                skippedCount = TraceStore.getLong("web.await.skipped.count");
            } catch (Exception ignore) {
            }

            if (effectiveDown || skippedCount >= 2L) {
                log.warn(
                        "⛔ [WebSearch] Stop retrying: web effectively down (effectiveDown={}, skippedCount={}). query='{}'",
                        effectiveDown, skippedCount, query);
                break;
            }

            // Retry only when we saw transient signals (timeouts/nonOk) or partial-down.
            long timeoutCount = 0L;
            long timeoutHardCount = 0L;
            long nonOkCount = 0L;
            try {
                timeoutCount = TraceStore.getLong("web.await.events.timeout.count");
                timeoutHardCount = TraceStore.getLong("web.await.events.timeoutHard.count");
                nonOkCount = TraceStore.getLong("web.await.events.nonOk.count");
            } catch (Exception ignore) {
            }

            boolean transientSignal = timeoutCount > 0 || timeoutHardCount > 0 || nonOkCount > 0;
            boolean partialDown = traceBool("orch.webPartialDown", false)
                    || traceBool("orch.webRateLimited.anyDown", false);

            if (!transientSignal && !partialDown && hadRaw) {
                // All filtered out but no transient signals → additional retries rarely help.
                log.warn("⛔ [WebSearch] Stop retrying: filtered-out without transient signals. query='{}'", query);
                break;
            }
            if (!transientSignal && !partialDown && !hadRaw) {
                // Pure empty without transient signals → treat as "no results" and stop.
                log.warn("⛔ [WebSearch] Stop retrying: empty without transient signals. query='{}'", query);
                break;
            }

            long backoffMs = baseBackoffMs * (1L << Math.min(4, attempt - 1));
            backoffMs = Math.min(maxBackoffMs, backoffMs);

            long remain = deadlineMs - System.currentTimeMillis();
            if (remain <= 0) {
                break;
            }
            backoffMs = Math.min(backoffMs, remain);

            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.error("❌ [WebSearch] All {} attempts failed or produced no valid results for query='{}'. Returning empty.",
                attempts, query);
        return java.util.Collections.emptyList();
    }

    // ── URL/source 메타 보존을 위한 URL 파서(Null-safe + 정규화)
    private static String extractUrl(String text) {
        if (text == null)
            return null;
        try {
            int a = text.indexOf("href=\"");
            if (a >= 0) {
                int s = a + 6, e = text.indexOf('"', s);
                if (e > s) {
                    return sanitizeUrl(text.substring(s, e));
                }
            }
        } catch (Exception ignore) {
        }
        try {
            int http = text.indexOf("http");
            if (http >= 0) {
                int sp = text.indexOf(' ', http);
                String raw = sp > http ? text.substring(http, sp) : text.substring(http);
                return sanitizeUrl(raw);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** Trim common trailing punctuation and normalize scheme/quotes. */
    private static String sanitizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String u = raw.trim();
        if (u.isEmpty()) {
            return u;
        }
        // Strip common trailing punctuation/brackets that often leak from snippets/log formatting
        while (!u.isEmpty()) {
            char last = u.charAt(u.length() - 1);
            if (last == ')' || last == ']' || last == ',' || last == '.' || last == ';' || last == '"' || last == '\'') {
                u = u.substring(0, u.length() - 1).trim();
                continue;
            }
            break;
        }
        return HtmlTextUtil.normalizeUrl(u);
    }

    /**
     * Build {@link Content} with URL/source metadata preserved.
     *
     * <p>Many downstream components (guard/provenance/TAA) compute evidence diversity using
     * {@code TextSegment.metadata().getString("url"/"source")}. If we only use {@code Content.from(text)},
     * metadata is empty and diversity collapses to 0 even when the snippet contains a URL.
     */
    private Content toWebContent(String text, String url, String providerName) {
        String t = (text == null) ? "" : text;
        String u = sanitizeUrl(url);
        if (u == null || u.isBlank()) {
            // fail-soft: recover from the text itself (e.g., "URL: https://..." or "[출처] https://...")
            u = extractUrl(t);
        }

        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        if (u != null && !u.isBlank()) {
            meta.put("url", u);
            // keep legacy key used by parts of the pipeline as "url alternative"
            meta.put("source", u);
        }
        if (providerName != null && !providerName.isBlank()) {
            meta.put("provider", providerName);
        }

        if (meta.isEmpty()) {
            return Content.from(t);
        }
        return Content.from(TextSegment.from(t, Metadata.from(meta)));
    }

    private static java.util.List<String> extractSiteFilters(String query) {
        if (query == null || query.isBlank()) {
            return java.util.Collections.emptyList();
        }
        java.util.regex.Matcher m = SITE_FILTER.matcher(query);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        while (m.find()) {
            String raw = m.group(1);
            String norm = normalizeSiteToken(raw);
            if (norm != null && !norm.isBlank()) {
                out.add(norm);
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * Canonicalizes a query into a "base" form by stripping site: filters and the
     * OR tokens that
     * only exist to join those site filters. This allows us to match:
     *
     * <ul>
     * <li>{@code "foo"}</li>
     * <li>{@code "foo site:example.com"}</li>
     * <li>{@code "foo (site:example.com OR site:example.org)"}</li>
     * </ul>
     */
    private static String canonicalBaseQuery(String query) {
        if (query == null) {
            return "";
        }
        String q = query.trim();
        if (q.isEmpty()) {
            return "";
        }

        // Token-based approach: remove site:* tokens and remove OR tokens only when
        // they are
        // directly adjacent to site tokens (so we don't accidentally drop legitimate
        // query terms).
        String[] parts = q.split("\\s+");
        boolean[] isSite = new boolean[parts.length];
        String[] core = new String[parts.length];

        for (int i = 0; i < parts.length; i++) {
            String t = parts[i];
            if (t == null) {
                core[i] = "";
                isSite[i] = false;
                continue;
            }
            String c = t;
            // strip leading/trailing parentheses to normalize tokens like "(site:..." /
            // "... )"
            while (c.startsWith("("))
                c = c.substring(1);
            while (c.endsWith(")"))
                c = c.substring(0, c.length() - 1);
            core[i] = c;
            isSite[i] = c.toLowerCase(java.util.Locale.ROOT).startsWith("site:");
        }

        java.util.ArrayList<String> keep = new java.util.ArrayList<>();
        for (int i = 0; i < core.length; i++) {
            String tok = core[i];
            if (tok == null || tok.isBlank())
                continue;
            if (isSite[i])
                continue;
            if (tok.equalsIgnoreCase("OR")
                    && ((i > 0 && isSite[i - 1]) || (i + 1 < isSite.length && isSite[i + 1]))) {
                continue;
            }
            keep.add(tok);
        }

        return String.join(" ", keep).trim();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> serpCacheGet(String baseKey) {
        if (baseKey == null || baseKey.isBlank()) {
            return null;
        }
        Object o = com.example.lms.search.TraceStore.get(SERP_CACHE_TRACE_KEY);
        if (o instanceof java.util.Map<?, ?> m) {
            try {
                return ((java.util.Map<String, java.util.List<String>>) m).get(baseKey);
            } catch (ClassCastException ignore) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void serpCachePut(String baseKey, java.util.List<String> snippets) {
        if (baseKey == null || baseKey.isBlank()) {
            return;
        }
        if (snippets == null || snippets.isEmpty()) {
            return;
        }

        java.util.Map<String, java.util.List<String>> cache;
        Object o = com.example.lms.search.TraceStore.get(SERP_CACHE_TRACE_KEY);
        if (o instanceof java.util.Map<?, ?> m) {
            try {
                cache = (java.util.Map<String, java.util.List<String>>) m;
            } catch (ClassCastException e) {
                cache = new java.util.concurrent.ConcurrentHashMap<>();
                com.example.lms.search.TraceStore.put(SERP_CACHE_TRACE_KEY, cache);
            }
        } else {
            cache = new java.util.concurrent.ConcurrentHashMap<>();
            com.example.lms.search.TraceStore.put(SERP_CACHE_TRACE_KEY, cache);
        }

        java.util.List<String> trimmed = snippets.stream().distinct().limit(SERP_CACHE_MAX).toList();
        cache.put(baseKey, trimmed);
    }

    private static java.util.List<String> filterSnippetsBySites(
            java.util.List<String> snippets,
            java.util.List<String> sites,
            int limit) {
        if (snippets == null || snippets.isEmpty() || sites == null || sites.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> normSites = sites.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .map(WebSearchRetriever::normalizeSiteToken)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (normSites.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        int hardLimit = Math.max(1, limit);

        for (String snip : snippets) {
            if (snip == null || snip.isBlank())
                continue;
            String url = extractUrl(snip);
            if (url == null || url.isBlank())
                continue;
            if (urlMatchesAnySite(url, normSites)) {
                out.add(snip);
                if (out.size() >= hardLimit)
                    break;
            }
        }

        return new java.util.ArrayList<>(out);
    }

    private static boolean urlMatchesAnySite(String url, java.util.List<String> sites) {
        String host = null;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception ignore) {
            // fall through
        }
        if (host == null || host.isBlank()) {
            try {
                String s = url;
                s = s.replaceFirst("^https?://", "");
                int cut = s.indexOf('/');
                if (cut >= 0)
                    s = s.substring(0, cut);
                cut = s.indexOf('?');
                if (cut >= 0)
                    s = s.substring(0, cut);
                cut = s.indexOf('#');
                if (cut >= 0)
                    s = s.substring(0, cut);
                host = s;
            } catch (Exception ignore2) {
                host = null;
            }
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for (String site : sites) {
            if (site == null || site.isBlank())
                continue;
            String s = site.toLowerCase(java.util.Locale.ROOT);
            if (h.equals(s) || h.endsWith("." + s) || h.endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeSiteToken(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        s = s.replaceFirst("^https?://", "");
        // trim path
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        // strip leading www.
        s = s.replaceFirst("^www\\.", "");
        return s.trim();
    }

    // ── NEW: SnippetPruner 없이도 동작하는 경량 딥 스니펫 추출기
    private static String pickByHeuristic(String q, String body, int maxLen) {
        if (body == null || body.isBlank())
            return "";
        if (q == null)
            q = "";
        String[] toks = q.toLowerCase().split("\\s+");
        String[] sents = body.split("(?<=[\\.\\?\\!。！？])\\s+");
        String best = "";
        int bestScore = -1;
        for (String s : sents) {
            if (s == null || s.isBlank())
                continue;
            String ls = s.toLowerCase();
            int score = 0;
            for (String t : toks) {
                if (t.isBlank())
                    continue;
                if (ls.contains(t))
                    score += 2; // 질의 토큰 포함 가중
            }
            score += Math.min(s.length(), 300) / 60; // 문장 길이 가중(너무 짧은 문장 패널티)
            if (score > bestScore) {
                bestScore = score;
                best = s.trim();
            }
        }
        if (best.isEmpty()) {
            best = body.length() > maxLen ? body.substring(0, maxLen) : body;
        } else if (best.length() > maxLen) {
            best = best.substring(0, maxLen) + "/* ... *&#47;";
        }
        return best;
    }
}

// PATCH_MARKER: WebSearchRetriever updated per latest spec.
