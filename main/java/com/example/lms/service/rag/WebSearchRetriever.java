package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.trace.SafeRedactor;
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
        return TraceStore.withSearchContext("retrievalExecutionId", () -> retrieveObserved(query));
    }

    private List<Content> retrieveObserved(Query query) {
        String normalized = normalize(query != null ? query.text() : "");
        final String requestedIdentityQuery = normalized;

        java.util.Map<String, Object> meta = new java.util.HashMap<>(toMetaMap(query));
        meta.putIfAbsent("purpose", "WEB_SEARCH");
        Object purpose = meta.get("purpose");
        String purposeText = purpose == null ? null : String.valueOf(purpose);
        log.debug("[WebSearch][meta] purposeHash12={} purposeLength={} keyCount={}",
                SafeRedactor.hash12(purposeText), purposeText == null ? 0 : purposeText.length(), meta.size());

        var gctx = GuardContextHolder.get();
        boolean sensitive = gctx != null && gctx.isSensitiveTopic();
        boolean planBlockAll = gctx != null && gctx.planBool("privacy.boundary.block-web-search", false);
        boolean planBlockOnSensitive = gctx != null
                && gctx.planBool("privacy.boundary.block-web-search-on-sensitive", false);
        if (blockWebSearch || planBlockAll || (sensitive && (blockWebSearchOnSensitive || planBlockOnSensitive))) {
            TraceStore.put("privacy.web.blocked", true);
            traceWebSearchCounts(normalized, 0, 0, 0, "privacy_blocked");
            return java.util.Collections.emptyList();
        }

        if (preprocessor != null) {
            try {
                normalized = preprocessor.enrich(normalized, meta);
            } catch (Exception e) {
                log.debug("[AWX][rag][web] preprocessor failed failureReason={} errorType={} queryHash12={} queryLength={}", "web-preprocessor-error", SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"), SafeRedactor.hash12(normalized), normalized == null ? 0 : normalized.length());
            }
        }

        // If the caller appended "site:" filters (e.g., detour/cheap-retry or
        // user-specified),
        // try to reuse the base SERP snippets we already have (prefetch/trace cache)
        // instead of triggering a new
        // external search call. This helps prevent "web=0" starvation loops when a base
        // SERP was already fetched.
        final java.util.List<String> requestedSites = extractSiteFilters(requestedIdentityQuery);
        final boolean hasSiteFilters = requestedSites != null && !requestedSites.isEmpty();
        final String baseQueryKey = canonicalBaseQuery(requestedIdentityQuery);
        // 쿼리 도메인 추정: null 가능성을 고려하여 GENERAL 기본값 사용
        String domain = domainDetector != null ? domainDetector.detect(normalized) : "GENERAL";
        boolean isGeneral = "GENERAL".equalsIgnoreCase(domain);

        int reqTopK = metaInt(meta, "webTopK", this.topK);
        long webBudgetMs = metaLong(meta, "webBudgetMs", -1L);
        long webStartedMs = System.currentTimeMillis();
        TraceStore.put("webSearch.providerAttempts", 0);
        boolean allowWeb = metaBool(meta, "allowWeb", true);
        if (!allowWeb) {
            com.example.lms.search.RequestTrace.emit("web.search", "skip", "reason=allowWeb_false");
            traceWebSearchCounts(normalized, reqTopK, 0, 0, "allowWeb_false");
            return java.util.Collections.emptyList();
        }

        // Request-scoped deadline: when the caller's TimeBudget is present it
        // caps webBudgetMs; once exhausted, no new provider call is started.
        final com.abandonware.ai.addons.budget.TimeBudget requestBudget =
                com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        if (requestBudget != null) {
            long requestRemaining = requestBudget.remainingMillis();
            if (requestRemaining <= 0L) {
                com.example.lms.search.DeadlineProbe.skip("web.search", "request_budget_exhausted");
                traceWebSearchCounts(normalized, reqTopK, 0, 0, "request_budget_exhausted");
                return java.util.Collections.emptyList();
            }
            webBudgetMs = webBudgetMs > 0 ? Math.min(webBudgetMs, requestRemaining) : requestRemaining;
        }
        com.example.lms.search.DeadlineProbe.enter("web.search");

        int k = Math.max(reqTopK, MIN_SNIPPETS);
        int maxAttempts = (webBudgetMs > 0 ? (webBudgetMs <= 1500 ? 1 : (webBudgetMs <= 3000 ? 2 : 3)) : 3);
        com.example.lms.search.RequestTrace.emit("web.search", "enter",
                "k=" + k + ";webBudgetMs=" + webBudgetMs + ";maxAttempts=" + maxAttempts);

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
        Object pqh = meta.get("prefetch.web.queryHash");
        Object ps = meta.get("prefetch.web.snippets");
        java.util.List<String> prefetchSnips = java.util.Collections.emptyList();
        String prefetchKeyNorm = null;
        boolean prefetchIdentityMatch = false;
        String prefetchMismatchReason = "identity_missing";
        TraceStore.put("webSearch.prefetch.present", false);
        TraceStore.put("webSearch.prefetch.identityPresent", false);
        TraceStore.put("webSearch.prefetch.identityMatch", false);

        if (ps instanceof java.util.List<?> raw) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object o : raw) {
                if (o == null)
                    continue;
                String s = String.valueOf(o).trim();
                if (!s.isBlank())
                    out.add(s);
            }
            prefetchSnips = out;

            if (!out.isEmpty()) {
                prefetchKeyNorm = pq == null ? null : normalize(String.valueOf(pq));
                String suppliedHash = pqh == null ? null : String.valueOf(pqh);
                String expectedHash = pq == null ? null : SafeRedactor.hashValue(String.valueOf(pq));
                String prefetchBaseIdentity = canonicalQueryIdentity(prefetchKeyNorm);
                String currentBaseIdentity = canonicalQueryIdentity(requestedIdentityQuery);
                java.util.List<String> prefetchSites = canonicalScopeValues(extractSiteFilters(prefetchKeyNorm));
                java.util.List<String> currentSites = canonicalScopeValues(requestedSites);
                java.util.List<String> prefetchProviders = canonicalScopeValues(meta.get("prefetch.web.providerScope"));
                java.util.List<String> currentProviders = canonicalScopeValues(meta.get("webProviders"));

                boolean identityPresent = !prefetchBaseIdentity.isBlank()
                        && suppliedHash != null
                        && suppliedHash.equals(expectedHash);
                boolean siteScopeMatches = prefetchSites.equals(currentSites);
                boolean providerScopeMatches = prefetchProviders.equals(currentProviders);
                boolean searchModeMatches = optionalScopeMatches(
                        meta.get("prefetch.web.searchMode"), meta.get("searchMode"));
                boolean filterScopeMatches = optionalScopeMatches(
                        meta.get("prefetch.web.filterScope"), meta.get("webFilterScope"));

                if (!identityPresent) {
                    prefetchMismatchReason = suppliedHash == null || prefetchBaseIdentity.isBlank()
                            ? "identity_missing"
                            : "identity_invalid";
                } else if (!siteScopeMatches) {
                    prefetchMismatchReason = "site_scope_mismatch";
                } else if (!providerScopeMatches) {
                    prefetchMismatchReason = "provider_scope_mismatch";
                } else if (!searchModeMatches) {
                    prefetchMismatchReason = "search_mode_mismatch";
                } else if (!filterScopeMatches) {
                    prefetchMismatchReason = "filter_scope_mismatch";
                } else if (!prefetchBaseIdentity.equals(currentBaseIdentity)) {
                    prefetchMismatchReason = "query_mismatch";
                } else {
                    prefetchIdentityMatch = true;
                    prefetchMismatchReason = "none";
                }

                TraceStore.put("webSearch.prefetch.present", true);
                TraceStore.put("webSearch.prefetch.identityPresent", identityPresent);
                TraceStore.put("webSearch.prefetch.identityMatch", prefetchIdentityMatch);
                TraceStore.put("webSearch.prefetch.mismatchReason", prefetchMismatchReason);
                String identityFingerprint = SafeRedactor.hash12(String.join("|",
                        prefetchBaseIdentity,
                        String.join(",", prefetchSites),
                        String.join(",", prefetchProviders)));
                if (identityFingerprint != null) {
                    TraceStore.put("webSearch.prefetch.queryFingerprint12", identityFingerprint);
                }

                if (prefetchIdentityMatch && !hasSiteFilters) {
                    usedPrefetched = true;
                    first = out;
                    reusedBaseSerpSource = "prefetch.canonical";
                }
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
        if (first == null && prefetchIdentityMatch && hasSiteFilters
                && prefetchKeyNorm != null && !prefetchKeyNorm.isBlank()
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
            if (!prefetchSnips.isEmpty()) {
                TraceStore.put("webSearch.prefetch.decision", "fresh_search");
            }
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
        if (usedPrefetched && !prefetchSnips.isEmpty()) {
            TraceStore.put("webSearch.prefetch.decision", "reuse");
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
                // The block duration never exceeds the remaining request budget.
                long multiBudgetMs = webBudgetMs > 0 ? Math.min(5000L, Math.max(600L, webBudgetMs)) : 5000L;
                if (requestBudget != null) {
                    multiBudgetMs = Math.min(multiBudgetMs, requestBudget.remainingMillis());
                }
                if (multiBudgetMs <= 0L) {
                    TraceStore.put("webSearch.multiSearch.budgetExhausted", true);
                    com.example.lms.search.DeadlineProbe.skip("web.multiSearch", "budget_exhausted");
                } else {
                // Provider-internal caching is not visible at this boundary:
                // a returned bundle may be a cache hit — record it honestly.
                com.example.lms.search.RequestTrace.emit("web.multi", "call",
                        "bounded_ms=" + multiBudgetMs + ";cache_obs=not_observed");
                var bundle = multiSearch.searchMulti(q, 2)
                        .block(java.time.Duration.ofMillis(multiBudgetMs));
                com.example.lms.search.RequestTrace.emit("web.multi", "result",
                        "docs=" + (bundle != null && bundle.docs() != null ? bundle.docs().size() : 0));
                if (bundle != null && bundle.docs() != null) {
                    supplemental = bundle.docs().stream()
                            .map(d -> {
                                String core = (d.title() + " - " + d.snippet()).trim();
                                return core.isBlank() ? d.url() : core;
                            })
                            .filter(s -> s != null && !s.isBlank())
                            .toList();
                }
                }
            } catch (Exception e) {
                // ignore errors; supplemental remains empty
                log.debug("[WebSearchRetriever] fail-soft stage={}", "multiSearch.supplemental");
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
        int webSearchReturnedCount = first == null ? 0 : first.size();
        if (log.isDebugEnabled()) {
            log.debug("[WebSearchRetriever] first raw={} queryHash12={} queryLength={}",
                    first.size(), SafeRedactor.hash12(normalized), normalized == null ? 0 : normalized.length());
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
        String courtesyQuery = normalized.replace("교수님", "교수").replace("님", "");
        Object attemptsValue = TraceStore.get("webSearch.providerAttempts");
        int attemptsUsed = attemptsValue instanceof Number n ? n.intValue() : 0;
        String failureClass = canonicalWebFailureClass(first == null ? 0 : first.size(), ranked.size(), null);
        List<String> fallback = List.of();
        if (!usedPrefetched && ranked.size() < MIN_SNIPPETS && !courtesyQuery.equals(normalized)
                && attemptsUsed < maxAttempts
                && (requestBudget == null || requestBudget.remainingMillis() > 0L)
                && (webBudgetMs <= 0 || System.currentTimeMillis() - webStartedMs < webBudgetMs)
                && ("none".equals(failureClass) || "provider_empty".equals(failureClass)
                    || "after_filter_starvation".equals(failureClass))) {
            try {
                TraceStore.put("webSearch.providerAttempts", attemptsUsed + 1);
                com.example.lms.search.RequestTrace.emit("web.fallback", "call",
                        "attempt=" + (attemptsUsed + 1) + ";reason=courtesy");
                List<String> alternate = webSearchProvider.search(courtesyQuery, k);
                fallback = alternate == null ? List.of() : alternate;
            } catch (Exception failure) {
                TraceStore.put("webSearch.providerFailureClass", webExceptionFailureClass(failure));
                log.debug("[WebSearchRetriever] courtesy fallback failed; preserving prior results. errorType={}",
                        SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown"));
            }
        }

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
                            log.debug("[WebSearchRetriever] fail-soft stage={}", "educationClassifier.filter");
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
            log.debug("[WebSearchRetriever] fail-soft stage={}", "providerName");
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
            // Per-page scraping consumes the shared request budget: when it is
            // exhausted, no new wire call is started and the snippet fallback
            // is used for the remaining pages.
            long scrapeBudgetMs = requestBudget != null
                    ? requestBudget.remainingMillis()
                    : Long.MAX_VALUE;
            if (scrapeBudgetMs <= 0L) {
                TraceStore.put("webSearch.scrape.budgetExhausted", true);
                com.example.lms.search.DeadlineProbe.skip("web.scrape", "budget_exhausted");
                out.add(toWebContent(s, url, providerName));
                continue;
            }
            try {
                com.example.lms.search.RequestTrace.emit("web.scrape", "page",
                        "timeout_ms=" + (int) Math.min(6000L, scrapeBudgetMs));
                String body = pageScraper.fetchText(url,
                        (int) Math.min(6000L, scrapeBudgetMs));
                // SnippetPruner는 (String, String) 시그니처만 존재 → 단일 결과로 처리
                // 🔵 우리 쪽 간단 딥 스니펫 추출(임베딩 없이 키워드/길이 기반)
                String picked = pickByHeuristic(query.text(), body, 480);
                if (picked == null || picked.isBlank()) {
                    out.add(toWebContent(s, url, providerName));
                } else {
                    out.add(toWebContent(picked + "\n\n[출처] " + url, url, providerName));
                }
            } catch (Exception e) {
                log.debug("[WebSearchRetriever] scrape fail urlPresent={} urlHash12={} urlLength={} → fallback snippet",
                        url != null && !url.isBlank(),
                        SafeRedactor.hash12(url),
                        url == null ? 0 : url.length());
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
                            log.debug("[WebSearchRetriever] fail-soft stage={}", "authorityMin.filter");
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
        java.util.List<Content> limited = out.stream().limit(k).toList();
        traceWebSearchCounts(normalized, k, webSearchReturnedCount, limited.size(), null);
        com.example.lms.search.DeadlineProbe.finish("web.search");
        com.example.lms.search.RequestTrace.emit("web.search", "finish", "out=" + limited.size());
        return limited;
    }

    private static void traceWebSearchCounts(String query,
                                             int requestedCount,
                                             int returnedCount,
                                             int afterFilterCount,
                                             String disabledReason) {
        try {
            int requested = Math.max(0, requestedCount);
            int returned = Math.max(0, returnedCount);
            int after = Math.max(0, afterFilterCount);
            TraceStore.put("webSearch.requestedCount", requested);
            TraceStore.put("webSearch.timeoutMs", 0);
            TraceStore.put("webSearch.returnedCount", returned);
            TraceStore.put("webSearch.afterFilterCount", after);
            ai.abandonware.nova.orch.trace.OrchEventEmitter.ragEvent(
                    "rag.pipeline", "retrieve", "web_retrieval", "complete", "WebSearchRetriever",
                    after > 0 ? "ok" : "empty",
                    java.util.Map.of("queryHash", SafeRedactor.hashValue(query), "requestedTopK", requested,
                            "mode", "all_selected_provider_and_cache_results"),
                    java.util.Map.of("returnedCount", returned, "afterFilterCount", after, "selectedCount", after),
                    java.util.Map.of(), java.util.Map.of());
            TraceStore.put("webSearch.zeroResults", returned == 0);
            TraceStore.put("webSearch.afterFilterStarved", returned > 0 && after == 0);
            TraceStore.put("webSearch.providerDisabled", disabledReason != null && !disabledReason.isBlank());
            TraceStore.put("webSearch.failureClass", canonicalWebFailureClass(returned, after, disabledReason));
            TraceStore.put("webSearch.queryHash", query == null ? "" : org.apache.commons.codec.digest.DigestUtils.sha256Hex(query));
            TraceStore.put("webSearch.queryLength", query == null ? 0 : query.length());
            TraceStore.put("webSearch.queryTokenBucket", queryTokenBucket(query));
            TraceStore.put("rag.returnedCount", returned);
            TraceStore.put("rag.afterFilterCount", after);
            TraceStore.put("rag.zeroResults", returned == 0);
            TraceStore.put("rag.afterFilterStarved", returned > 0 && after == 0);
            if (disabledReason != null && !disabledReason.isBlank()) {
                TraceStore.put("webSearch.providerDisabled", true);
                TraceStore.put("webSearch.disabledReason", SafeRedactor.traceLabelOrFallback(disabledReason, "unknown"));
            }
        } catch (Throwable ignore) {
            // fail-soft telemetry only
            log.debug("[WebSearchRetriever] fail-soft stage={}", "traceWebSearchCounts");
        }
    }

    private static String canonicalWebFailureClass(int returned, int after, String disabledReason) {
        if (after > 0) return "none";
        if (returned > 0) return "after_filter_starvation";
        if (disabledReason != null && !disabledReason.isBlank()) return "provider_disabled";
        String statusCategory = webHttpFailureCategory(TraceStore.get("web.brave.httpStatus"));
        if (statusCategory != null && !"provider_error".equals(statusCategory)) return statusCategory;
        for (String key : List.of("web.failureClass", "web.naver.failureClass",
                "web.brave.failureReason", "webSearch.providerFailureClass")) {
            String category = webFailureCategory(TraceStore.get(key));
            if (category != null) return category;
        }
        if (statusCategory != null) return statusCategory;
        Object last = TraceStore.get("web.await.last");
        if (last instanceof java.util.Map<?, ?> event) {
            statusCategory = webHttpFailureCategory(event.get("httpStatus"));
            if (statusCategory != null) return statusCategory;
            for (String key : List.of("cause", "errorType", "timeoutCategory")) {
                String category = webFailureCategory(event.get(key));
                if (category != null) return category;
            }
        }
        return "provider_empty";
    }

    private static String webFailureCategory(Object value) {
        if (!(value instanceof String text)) return null;
        return switch (text.toLowerCase(java.util.Locale.ROOT)) {
            case "auth", "auth_or_config", "http-401", "http-403" -> "auth_or_config";
            case "rate_limit", "rate-limit", "http-429" -> "rate_limit";
            case "timeout", "timeout_or_budget", "soft_timeout", "hard_timeout",
                    "budget-exhausted", "budget_exhausted" -> "timeout_or_budget";
            case "parse_error", "parse-error", "jsonparseexception", "jsonmappingexception",
                    "jsonprocessingexception", "decodingexception" -> "parse_error";
            case "breaker_or_cooldown", "cooldown", "breaker-open" -> "breaker_or_cooldown";
            case "cancelled", "canceled" -> "cancelled";
            case "provider_error", "provider-exception", "exception", "http-error" -> "provider_error";
            default -> null;
        };
    }

    private static String webHttpFailureCategory(Object value) {
        if (!(value instanceof Number number)) return null;
        int status = number.intValue();
        if (status == 401 || status == 403) return "auth_or_config";
        if (status == 429) return "rate_limit";
        if (status == 408 || status == 504) return "timeout_or_budget";
        return status >= 400 ? "provider_error" : null;
    }

    private static String webExceptionFailureClass(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof com.fasterxml.jackson.core.JsonProcessingException) return "parse_error";
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketTimeoutException) return "timeout_or_budget";
            if (current instanceof java.util.concurrent.CancellationException
                    || current instanceof InterruptedException) return "cancelled";
            String status = null;
            if (current instanceof org.springframework.web.reactive.function.client.WebClientResponseException http) {
                status = webHttpFailureCategory(http.getStatusCode().value());
            } else if (current instanceof dev.langchain4j.exception.HttpException http) {
                status = webHttpFailureCategory(http.statusCode());
            }
            if (status != null) return status;
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return "provider_error";
    }

    private static String queryTokenBucket(String query) {
        if (query == null || query.isBlank()) {
            return "0";
        }
        int tokens = query.trim().split("\\s+").length;
        if (tokens <= 4) {
            return "1-4";
        }
        if (tokens <= 12) {
            return "5-12";
        }
        if (tokens <= 32) {
            return "13-32";
        }
        return "33+";
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> toMetaMap(Query query) {
        if (query == null)
            return java.util.Collections.emptyMap();
        return QueryUtils.metadata(query);
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
        if (v instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                WebSearchRetrieverTraceSuppressions.trace("metaInt.parse",
                        new NumberFormatException("non-finite"));
                log.debug("[WebSearchRetriever] fail-soft stage={} errorType={}",
                        "metaInt.parse", "invalid_number");
                return def;
            }
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignore) {
                WebSearchRetrieverTraceSuppressions.trace("metaInt.parse", ignore);
                log.debug("[WebSearchRetriever] fail-soft stage={} errorType={}",
                        "metaInt.parse", "invalid_number");
            }
        }
        return def;
    }

    private static long metaLong(java.util.Map<String, Object> meta, String key, long def) {
        if (meta == null)
            return def;
        Object v = meta.get(key);
        if (v instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                WebSearchRetrieverTraceSuppressions.trace("metaLong.parse",
                        new NumberFormatException("non-finite"));
                log.debug("[WebSearchRetriever] fail-soft stage={} errorType={}",
                        "metaLong.parse", "invalid_number");
                return def;
            }
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                WebSearchRetrieverTraceSuppressions.trace("metaLong.parse", ignore);
                log.debug("[WebSearchRetriever] fail-soft stage={} errorType={}",
                        "metaLong.parse", "invalid_number");
            }
        }
        return def;
    }

    private static double metaDouble(java.util.Map<String, Object> meta, String key, double def) {
        if (meta == null)
            return def;
        Object v = meta.get(key);
        if (v instanceof Number n) {
            double parsed = n.doubleValue();
            if (!Double.isFinite(parsed)) {
                WebSearchRetrieverTraceSuppressions.trace("metaDouble.parse",
                        new NumberFormatException("non-finite"));
                log.debug("[WebSearchRetriever] fail-soft stage={} errorType={}",
                        "metaDouble.parse", "invalid_number");
                return def;
            }
            return parsed;
        }
        if (v instanceof String s) {
            try {
                double parsed = Double.parseDouble(s.trim());
                if (!Double.isFinite(parsed)) {
                    WebSearchRetrieverTraceSuppressions.trace("metaDouble.parse",
                            new NumberFormatException("non-finite"));
                    log.debug("[WebSearchRetriever] fail-soft stage={} errorType={}",
                            "metaDouble.parse", "invalid_number");
                    return def;
                }
                return parsed;
            } catch (NumberFormatException ignore) {
                WebSearchRetrieverTraceSuppressions.trace("metaDouble.parse", ignore);
                log.debug("[WebSearchRetriever] fail-soft stage={} errorType={}",
                        "metaDouble.parse", "invalid_number");
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
        if (v instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                return def;
            }
            return n.intValue() != 0;
        }
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
     * True when a request-scoped {@link com.abandonware.ai.addons.budget.TimeBudget}
     * exists and is exhausted or cancelled. Downstream stages consult this so a
     * finished request cannot start new provider calls.
     */
    private static boolean requestBudgetExhausted() {
        com.abandonware.ai.addons.budget.TimeBudget budget =
                com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        return budget != null && budget.expired();
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
            if (System.currentTimeMillis() > deadlineMs || requestBudgetExhausted()) {
                com.example.lms.search.DeadlineProbe.skip("web.retry", "budget_exhausted");
                log.warn("⚠️ [WebSearch] budget exhausted ({}ms). Stop retrying. queryHash12={} queryLength={}",
                        budgetMs, SafeRedactor.hash12(query), query == null ? 0 : query.length());
                break;
            }

            long start = System.currentTimeMillis();
            boolean hadRaw = false;

            try {
                TraceStore.put("webSearch.providerAttempts", attempt);
                com.example.lms.search.RequestTrace.emit("web.provider", "call", "attempt=" + attempt);
                List<String> rawResults = webSearchProvider.search(query, k);
                com.example.lms.search.RequestTrace.emit("web.provider", "result",
                        "attempt=" + attempt + ";raw=" + (rawResults == null ? 0 : rawResults.size()));

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
                TraceStore.put("webSearch.providerFailureClass", webExceptionFailureClass(e));
                log.warn("[AWX][search][web] attempt failed failureReason={} errorType={} attempt={}/{} queryHash12={} queryLength={}",
                        "provider-exception",
                        SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                        attempt, attempts, SafeRedactor.hash12(query), query == null ? 0 : query.length());
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
                WebSearchRetrieverTraceSuppressions.trace("retry.skippedCount", ignore);
                log.debug("[WebSearchRetriever] fail-soft stage={}", "retry.skippedCount");
            }

            if (effectiveDown || skippedCount >= 2L) {
                log.warn(
                        "⛔ [WebSearch] Stop retrying: web effectively down (effectiveDown={}, skippedCount={}). queryHash12={} queryLength={}",
                        effectiveDown, skippedCount, SafeRedactor.hash12(query), query == null ? 0 : query.length());
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
                WebSearchRetrieverTraceSuppressions.trace("retry.awaitCounters", ignore);
                log.debug("[WebSearchRetriever] fail-soft stage={}", "retry.awaitCounters");
            }

            boolean transientSignal = timeoutCount > 0 || timeoutHardCount > 0 || nonOkCount > 0;
            boolean partialDown = traceBool("orch.webPartialDown", false)
                    || traceBool("orch.webRateLimited.anyDown", false);

            if (!transientSignal && !partialDown && hadRaw) {
                // All filtered out but no transient signals → additional retries rarely help.
                log.warn("⛔ [WebSearch] Stop retrying: filtered-out without transient signals. queryHash12={} queryLength={}",
                        SafeRedactor.hash12(query), query == null ? 0 : query.length());
                break;
            }
            if (!transientSignal && !partialDown && !hadRaw) {
                // Pure empty without transient signals → treat as "no results" and stop.
                log.warn("⛔ [WebSearch] Stop retrying: empty without transient signals. queryHash12={} queryLength={}",
                        SafeRedactor.hash12(query), query == null ? 0 : query.length());
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
                log.debug("[WebSearchRetriever] fail-soft stage={}", "retry.sleepInterrupted");
                break;
            }
        }

        log.error("❌ [WebSearch] All {} attempts failed or produced no valid results for queryHash12={} queryLength={}. Returning empty.",
                attempts, SafeRedactor.hash12(query), query == null ? 0 : query.length());
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
            WebSearchRetrieverTraceSuppressions.trace("extractUrl.href", ignore);
            log.debug("[WebSearchRetriever] fail-soft stage={}", "extractUrl.href");
        }
        try {
            int http = text.indexOf("http");
            if (http >= 0) {
                int sp = text.indexOf(' ', http);
                String raw = sp > http ? text.substring(http, sp) : text.substring(http);
                return sanitizeUrl(raw);
            }
        } catch (Exception ignore) {
            WebSearchRetrieverTraceSuppressions.trace("extractUrl.http", ignore);
            log.debug("[WebSearchRetriever] fail-soft stage={}", "extractUrl.http");
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

    private static String canonicalQueryIdentity(String query) {
        return canonicalBaseQuery(normalize(query))
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean matchesPrefetchQueryIdentity(String prefetchedQuery, String requestedQuery) {
        String prefetchedBase = canonicalQueryIdentity(prefetchedQuery);
        String requestedBase = canonicalQueryIdentity(requestedQuery);
        return !prefetchedBase.isBlank()
                && prefetchedBase.equals(requestedBase)
                && canonicalScopeValues(extractSiteFilters(prefetchedQuery))
                        .equals(canonicalScopeValues(extractSiteFilters(requestedQuery)));
    }

    public static String prefetchQueryFingerprint12(String query) {
        String base = canonicalQueryIdentity(query);
        if (base.isBlank()) {
            return null;
        }
        return SafeRedactor.hash12(String.join("|",
                base,
                String.join(",", canonicalScopeValues(extractSiteFilters(query)))));
    }

    private static java.util.List<String> canonicalScopeValues(Object raw) {
        if (raw == null) {
            return java.util.Collections.emptyList();
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        if (raw instanceof java.util.Collection<?> collection) {
            for (Object value : collection) {
                if (value != null) {
                    values.add(String.valueOf(value));
                }
            }
        } else {
            values.addAll(java.util.Arrays.asList(String.valueOf(raw).split("[,;]")));
        }
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean optionalScopeMatches(Object prefetchedScope, Object currentScope) {
        if (prefetchedScope == null) {
            return true;
        }
        return canonicalScopeValues(prefetchedScope).equals(canonicalScopeValues(currentScope));
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> serpCacheGet(String baseKey) {
        if (baseKey == null || baseKey.isBlank()) {
            return null;
        }
        String cacheKey = com.example.lms.util.HashUtil.sha256(canonicalQueryIdentity(baseKey));
        if (cacheKey == null) {
            return null;
        }
        Object o = com.example.lms.search.TraceStore.get(SERP_CACHE_TRACE_KEY);
        if (o instanceof java.util.Map<?, ?> m) {
            try {
                return ((java.util.Map<String, java.util.List<String>>) m).get(cacheKey);
            } catch (ClassCastException ignore) {
                log.debug("[WebSearchRetriever] fail-soft stage={}", "serpCache.getCast");
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
        String cacheKey = com.example.lms.util.HashUtil.sha256(canonicalQueryIdentity(baseKey));
        if (cacheKey == null) {
            return;
        }

        java.util.Map<String, java.util.List<String>> cache;
        Object o = com.example.lms.search.TraceStore.get(SERP_CACHE_TRACE_KEY);
        if (o instanceof java.util.Map<?, ?> m) {
            try {
                cache = (java.util.Map<String, java.util.List<String>>) m;
            } catch (ClassCastException e) {
                cache = new java.util.concurrent.ConcurrentHashMap<>();
            }
        } else {
            cache = new java.util.concurrent.ConcurrentHashMap<>();
        }
        com.example.lms.search.TraceStore.putInternal(SERP_CACHE_TRACE_KEY, cache);

        java.util.List<String> trimmed = snippets.stream().distinct().limit(SERP_CACHE_MAX).toList();
        cache.put(cacheKey, trimmed);
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
            log.debug("[WebSearchRetriever] fail-soft stage={}", "urlMatchesAnySite.uri");
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
                log.debug("[WebSearchRetriever] fail-soft stage={}", "urlMatchesAnySite.fallback");
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
