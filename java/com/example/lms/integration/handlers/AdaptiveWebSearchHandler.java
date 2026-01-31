package com.example.lms.integration.handlers;

import com.example.lms.gptsearch.decision.SearchDecision;
import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.service.rag.auth.DomainWhitelist;
import com.example.lms.config.NaverFilterProperties;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.extract.PageContentScraper;
import com.example.lms.service.rag.handler.AbstractRetrievalHandler;
import com.example.lms.service.rag.RelevanceScoringService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;





/**
 * Adaptive web search handler that decides whether to execute a web search
 * based on the user query and preferences.  When the decision service
 * indicates that a search is warranted, the handler invokes the configured
 * providers in parallel (sequentially in this shim implementation) and
 * converts the returned snippets into {@link Content} objects for the
 * retrieval chain.  Errors are logged but do not propagate.
 */

public class AdaptiveWebSearchHandler extends AbstractRetrievalHandler {

    public AdaptiveWebSearchHandler(com.example.lms.gptsearch.decision.SearchDecisionService decisionService,
                                    java.util.List<com.example.lms.gptsearch.web.WebSearchProvider> webSearchProviders,
                                    com.example.lms.service.rag.extract.PageContentScraper scraper,
                                    com.example.lms.service.rag.RelevanceScoringService relevanceScoringService,
                                    com.example.lms.service.rag.auth.DomainProfileLoader domainProfileLoader) {
        this.decisionService = decisionService;
        this.providers = webSearchProviders;
        this.scraper = scraper;
        this.relevance = relevanceScoringService;
        this.domainProfiles = domainProfileLoader;
    }

    private static final Logger log = LoggerFactory.getLogger(AdaptiveWebSearchHandler.class);
    @Autowired
    DomainWhitelist domainWhitelist;

    @Autowired(required = false)
    private NaverFilterProperties naverFilterProperties;

    @Value("${search.budget.per-page-ms:3500}")
    int perPageMs;


    private final SearchDecisionService decisionService;
    private final List<WebSearchProvider> providers;

    // Scraper for fetching full page content in precision mode
    private final PageContentScraper scraper;
    // Service for computing embedding relatedness when filtering precision results
    private final RelevanceScoringService relevance;

    // Loader for domain allowlist profiles.  When present and a
    // domainProfile hint is specified the handler will consult this
    // loader to decide whether a URL should be included.  The loader is
    // optional and will be null if no bean is defined.
    private final com.example.lms.service.rag.auth.DomainProfileLoader domainProfiles;

    // Upper bound on the total number of characters to aggregate when performing
    // precision crawling.  Configurable via application.yml (rag.precision.max-aggregate-chars).
    @Value("${rag.precision.max-aggregate-chars:60000}")
    private int maxAggregateChars;

    /**
     * Execute adaptive web search.  The query metadata may contain the
     * ChatRequestDto with user preferences, but if unavailable the handler
     * falls back to AUTO mode and default providers.
     */
    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        if (q == null || acc == null) {
            return true;
        }

        // ===== Meta gate (defensive) =====
        try {
            // Extract useWebSearch and searchMode flags from query metadata if present.
            Map<String, Object> md = toMetaMap(q);
            boolean useWeb = false;
            String modeStr = "AUTO";
            if (md != null) {
                Object v1 = md.get("useWebSearch");
                Object v2 = md.get("searchMode");
                if (v1 instanceof Boolean b) {
                    useWeb = b;
                } else if (v1 != null) {
                    // fall back to string parsing
                    useWeb = Boolean.parseBoolean(String.valueOf(v1));
                }
                if (v2 != null) {
                    modeStr = String.valueOf(v2);
                }
            }
            if ("OFF".equalsIgnoreCase(modeStr)) {
                useWeb = false;
            }
            if (!useWeb) {
                // Web search is disabled by meta; skip this handler and allow the
                // retrieval chain to continue.  Existing chain-level gating remains
                // the primary defence but this defensive check guards against
                // misconfigurations or future changes to the chain topology.
                return true;
            }
        } catch (Exception ignore) {
            // Ignore metadata parsing errors; proceed with default behaviour.
        }
        String queryText = (q.text() == null ? "" : q.text().trim());
        // meta에서 searchMode / webTopK / webProviders / officialOnly 힌트를 우선 반영
        Map<String, Object> metaMap = toMetaMap(q);
        SearchMode mode = SearchMode.AUTO;
        try {
            Object m = metaMap.get("searchMode");
            if (m instanceof String s) mode = SearchMode.valueOf(s.toUpperCase());
        } catch (Exception ignore) { /* 안전 무시 */ }
        SearchDecision decision = decisionService.decide(queryText, mode, null, null);
        if (!decision.shouldSearch()) {
            return true;
        }
        try {
            // Build search query. webTopK / webProviders 메타 우선
            int topK = Math.max(1, metaInt(metaMap, "webTopK", decision.topK()));
            // Provider hints: honour both 'webProviders' and legacy 'providers' keys
            List<String> providerHints = metaList(metaMap, "webProviders");
            if (providerHints.isEmpty()) {
                providerHints = metaList(metaMap, "providers");
            }
            List<ProviderId> provs = providerHints.isEmpty()
                    ? decision.providers()
                    : toProviderIds(providerHints);
            WebSearchQuery wq = new WebSearchQuery(queryText, topK, provs, null);
            // Accumulate documents along with their originating provider names.
            List<WebDocument> docs = new ArrayList<>();
            List<String> providerNames = new ArrayList<>();
            // Provider selection is fail-soft:
            // - If a requested provider is not registered (missing bean) or disabled, skip it.
            // - Continue with remaining providers (no hard failure).
            for (WebSearchProvider p : resolveProviders(provs)) {
                if (p == null) continue;
                try {
                    WebSearchResult r = p.search(wq);
                    if (r != null && r.getDocuments() != null) {
                        for (WebDocument d : r.getDocuments()) {
                            docs.add(d);
                            providerNames.add(r.getProviderId());
                        }
                    }
                } catch (Exception e) {
                    log.debug("Web provider {} failed: {}", p.id(), e.toString());
                }
            }
            // Meta hints (안전 파서)
            boolean precision = metaBool(metaMap, "precision", false)
                    || "DEEP".equalsIgnoreCase(String.valueOf(metaMap.getOrDefault("depth", "")));
            int deepTopK = Math.max(1, metaInt(metaMap, "webTopK", decision.topK()));
            double minRel = 0.0;
            Object mr = metaMap.get("minRelatedness");
            if (mr instanceof Number n2) minRel = Math.max(0.0, Math.min(1.0, n2.doubleValue()));
            boolean officialOnly = metaBool(metaMap, "officialOnly", false);

            // [PATCH] PlanHint(officialOnly/domainProfile) -> 실제 도메인 필터로 연결
            // - 설정(enable-domain-filter=false)이어도 per-request strict filter는 동작해야 한다.
            String domainProfile = null;
            try {
                Object dp = metaMap.get("domainProfile");
                if (dp == null) dp = metaMap.get("whitelist_profile");
                if (dp != null) {
                    domainProfile = String.valueOf(dp);
                    if (domainProfile != null) {
                        domainProfile = domainProfile.trim();
                        if (domainProfile.isBlank()) domainProfile = null;
                    }
                }
            } catch (Exception ignore) {
                // ignore
            }
            if (domainProfile == null && officialOnly) {
                domainProfile = "official";
            }

            boolean hasDomainProfile = domainProfile != null && !domainProfile.isBlank();
            boolean hardFilter = officialOnly || hasDomainProfile;
            boolean profileUsable = hasDomainProfile && domainProfiles != null;
            if (hardFilter) {
                List<WebDocument> filtered = new ArrayList<>(docs.size());
                List<String> filteredProviders = new ArrayList<>(providerNames.size());
                for (int i = 0; i < docs.size(); i++) {
                    WebDocument d = docs.get(i);
                    String url = (d != null ? d.getUrl() : null);
                    boolean ok = true;
                    if (url != null) {
                        if (profileUsable) {
                            ok = domainProfiles.isAllowedByProfile(url, domainProfile);
                        } else if (domainWhitelist != null) {
                            ok = domainWhitelist.isOfficial(url);
                        }
                    }
                    if (ok) {
                        filtered.add(d);
                        filteredProviders.add(providerNames.get(i));
                    }
                }
                // fail-soft: do not starve to empty
                if (!filtered.isEmpty()) {
                    docs = filtered;
                    providerNames = filteredProviders;
                }
            }

            // After official domain filtering, remove finance noise domains unless intent is FINANCE
            try {
                java.util.Set<String> financeNoise = java.util.Set.of("investing.com", "tradingview.com", "nasdaq.com", "finviz.com");
                java.util.function.Predicate<String> isFinanceNoise = (u) -> {
                    try {
                        if (u == null) return false;
                        java.net.URI uri = new java.net.URI(u);
                        String host = uri.getHost();
                        return host != null && financeNoise.stream().anyMatch(host::endsWith);
                    } catch (Exception e) {
                        return false;
                    }
                };
                boolean financeIntent = "FINANCE".equalsIgnoreCase(String.valueOf(metaMap.getOrDefault("intent", "")));
                if (!financeIntent) {
                    java.util.List<WebDocument> filteredDocs = new java.util.ArrayList<>(docs.size());
                    java.util.List<String> filteredProvs = new java.util.ArrayList<>(providerNames.size());
                    for (int i = 0; i < docs.size(); i++) {
                        WebDocument d = docs.get(i);
                        String url = (d != null) ? d.getUrl() : null;
                        if (d != null && url != null && !isFinanceNoise.test(url)) {
                            filteredDocs.add(d);
                            filteredProvs.add(providerNames.get(i));
                        }
                    }
                    docs = filteredDocs;
                    providerNames = filteredProvs;
                }
            } catch (Exception ignore) {
                // ignore filtering errors
            }

            // Precision mode: fetch full bodies and aggregate into a single context.
            if (precision) {
                StringBuilder big = new StringBuilder(16 * 1024);
                int used = 0;
                int count = 0;
                for (int i = 0; i < docs.size() && count < deepTopK; i++) {
                    WebDocument d = docs.get(i);
                    if (d == null || d.getUrl() == null || d.getUrl().isBlank()) continue;
                    try {
                        // 타임박스: perMs setting applied (meta overrides class default)
                        String body = scraper.fetchText(d.getUrl(), perPageMs);
                        if (body == null || body.isBlank()) continue;
                        // Relatedness cutoff if requested
                        if (minRel > 0.0) {
                            double s = 0.0;
                            try {
                                s = relevance.relatedness(queryText, body);
                            } catch (Throwable ignore2) {
                            }
                            if (s < minRel) continue;
                        }
                        String titlePart = (d.getTitle() != null && !d.getTitle().isBlank()) ? d.getTitle() : d.getUrl();
                        String header = "[" + titlePart + " | " + d.getUrl() + "]";
                        String chunk = header + "\n" + body + "\n\n";
                        int remain = Math.max(0, maxAggregateChars - used);
                        if (remain <= 0) break;
                        if (chunk.length() > remain) chunk = chunk.substring(0, remain);
                        big.append(chunk);
                        used += chunk.length();
                        count++;
                    } catch (Exception ex) {
                        // fail-soft on per-URL failures
                    }
                }
                if (big.length() > 0) {
                    acc.add(Content.from(TextSegment.from(big.toString())));
                }
                return true;
            }

            // Default (lightweight) mode: convert snippets into Content objects.
            // (버전 호환을 위해 Metadata 타입을 쓰지 않고, 헤더를 텍스트에 인라인)
            for (int i = 0; i < docs.size(); i++) {
                WebDocument d = docs.get(i);
                // Always process documents even when the snippet is blank or null; fall back to page text if necessary
                if (d == null) continue;
                String providerName = providerNames.get(i);
                java.util.List<String> parts = new java.util.ArrayList<>();
                if (d.getTitle() != null && !d.getTitle().isBlank()) parts.add(d.getTitle());
                if (providerName != null && !providerName.isBlank()) parts.add(providerName);
                if (d.getUrl() != null && !d.getUrl().isBlank()) parts.add(d.getUrl());
                if (d.getTimestamp() != null) parts.add(String.valueOf(d.getTimestamp().toEpochMilli()));
                String header = parts.isEmpty() ? null : "[" + String.join(" | ", parts) + "]";
                String _snip = d.getSnippet();
                if (_snip == null || _snip.isBlank()) {
                    try {
                        // 타임박스: perMs applies here as well
                        String body = scraper.fetchText(d.getUrl(), perPageMs);
                        if (body != null) {
                            _snip = body.length() > 200 ? body.substring(0, 200) + "/* ... *&#47;" : body;
                        }
                    } catch (Exception ignore) {
                        /* fail-soft */
                    }
                    if (_snip == null) _snip = "";
                }
                String entryText = (header != null ? header + "\n  " : "") + _snip;
                // [Fix] Propagate url/title metadata so downstream Guard/Domain logic can
                // reliably extract evidence attributes (no numeric fallback IDs).
                Map<String, Object> meta = new HashMap<>();
                if (d.getUrl() != null && !d.getUrl().isBlank()) meta.put("url", d.getUrl());
                if (d.getTitle() != null && !d.getTitle().isBlank()) meta.put("title", d.getTitle());
                if (providerName != null && !providerName.isBlank()) meta.put("provider", providerName);
                if (d.getTimestamp() != null) meta.put("timestamp", d.getTimestamp().toEpochMilli());
                acc.add(Content.from(TextSegment.from(entryText, Metadata.from(meta))));
            }
        } catch (Exception ex) {
            log.warn("[AdaptiveWeb] search failed", ex);
        }
        return true;
    }

    private List<WebSearchProvider> resolveProviders(List<ProviderId> desired) {
        if (desired == null || desired.isEmpty()) {
            return providers;
        }
        Map<ProviderId, WebSearchProvider> available = new HashMap<>();
        for (WebSearchProvider p : providers) {
            if (p == null) continue;
            try {
                ProviderId id = p.id();
                if (id != null) available.put(id, p);
            } catch (Exception ignore) {
                // skip broken provider
            }
        }
        List<WebSearchProvider> out = new ArrayList<>();
        for (ProviderId id : desired) {
            WebSearchProvider p = available.get(id);
            if (p == null) {
                log.debug("Requested provider {} is unavailable/disabled - skipping", id);
                continue;
            }
            out.add(p);
        }
        return out;
    }


    // ---- meta helpers ----
    private static boolean metaBool(Map<String, Object> m, String k, boolean d) {
        Object v = m != null ? m.get(k) : null;
        return v instanceof Boolean ? (Boolean) v : d;
    }

    private static int metaInt(Map<String, Object> m, String k, int d) {
        Object v = m != null ? m.get(k) : null;
        try {
            return v == null ? d : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return d;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> metaList(Map<String, Object> m, String k) {
        Object v = m != null ? m.get(k) : null;
        if (v instanceof List) return (List<String>) v;
        if (v instanceof String s) return java.util.Arrays.asList(s.split(","));
        return java.util.Collections.emptyList();
    }

    /**
     * webProviders 메타(문자열) → ProviderId 목록 매핑(무효값은 무시, 대소문자 무시)
     */
    private static List<ProviderId> toProviderIds(List<String> names) {
        List<ProviderId> out = new java.util.ArrayList<>();
        if (names == null) return out;
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            try {
                out.add(ProviderId.valueOf(n.trim().toUpperCase()));
            } catch (IllegalArgumentException ignore) {
                // 알 수 없는 값은 건너뜀 (fail-soft)
            }
        }
        return out;
    }

    // 안전한 메타 Map 추출(LangChain4j 1.0.1의 rag.query.Metadata / data.document.Metadata 모두 흡수)
    private static Map<String, Object> toMetaMap(Query q) {
        if (q == null || q.metadata() == null) return java.util.Collections.emptyMap();
        Object meta = q.metadata();
        try {
            Object raw;
            try {
                raw = meta.getClass().getMethod("asMap").invoke(meta); // 우선 시도
            } catch (NoSuchMethodException e) {
                raw = meta.getClass().getMethod("map").invoke(meta);   // 대안 시도
            }
            if (raw instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                return cast;
            }
        } catch (Exception ignore) { /* 호환 모드 */ }
        return java.util.Collections.emptyMap();
    }
}