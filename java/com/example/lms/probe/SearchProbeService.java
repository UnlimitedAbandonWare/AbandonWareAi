

        package com.example.lms.probe;

import com.example.lms.probe.dto.ProbeDoc;
import com.example.lms.probe.dto.ProbeRequest;
import com.example.lms.probe.dto.ProbeResult;
import com.example.lms.search.CompanyNormalizer;
import com.example.lms.service.rag.HybridRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service implementing the probe search. This service constructs a
 * metadata map from the incoming request, executes the hybrid
 * retriever and computes summary statistics on the returned documents.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchProbeService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SearchProbeService.class);


    private final HybridRetriever hybridRetriever;
    private final SearchProbeSecurity security;
    private final CompanyNormalizer companyNormalizer; // ✅ 의존성 주입 추가

    /**
     * Execute a probe search and return diagnostic information.
     *
     * @param req the probe request
     * @return the probe result containing documents and statistics
     */
    public ProbeResult run(ProbeRequest req) {
        String q = Optional.ofNullable(req.getQuery()).orElse("").trim();
        // ✅ 정적 호출을 인스턴스 호출로 변경
        String normalized = companyNormalizer.normalize(q);

        // Build String-only metadata
        Map<String, Object> meta = new HashMap<>();
        put(meta, "useWebSearch", req.getUseWebSearch());
        put(meta, "useRag", req.getUseRag());
        put(meta, "officialSourcesOnly", req.getOfficialSourcesOnly());
        put(meta, "webTopK", req.getWebTopK());
        put(meta, "searchMode", req.getSearchMode());
        put(meta, "intent", req.getIntent());
        // Replace all values with their String representation
        meta.replaceAll((k, v) -> v == null ? null : String.valueOf(v));
        // Remove null entries to avoid Metadata.from() failures
        meta.entrySet().removeIf(e -> e.getValue() == null);

        Query query;
        try {
            Metadata md = Metadata.from(meta);
            query = Query.builder().text(q).metadata(md).build();
        } catch (Throwable t) {
            // fallback to query without metadata when metadata creation fails
            log.warn("Probe metadata creation failed: {}", t.toString());
            query = Query.builder().text(q).build();
        }

        List<Content> contents = Collections.emptyList();
        try {
            contents = hybridRetriever.retrieve(query);
        } catch (Throwable t) {
            log.warn("Probe retrieve failed: {}", t.toString());
        }

        // Extract rows
        List<ProbeDoc> docs = contents.stream().map(c -> {
            Map<String, Object> m = toMapSafe(c.metadata());
            String url = str(m.getOrDefault("url", ""));
            String title = str(m.getOrDefault("title", ""));
            String snippet = str(m.getOrDefault("snippet", ""));
            String host = hostOf(url);
            return new ProbeDoc(title, snippet, url, host);
        }).collect(Collectors.toList());

        // Stats
        Map<String, Long> byDomain = docs.stream().collect(Collectors.groupingBy(ProbeDoc::host, LinkedHashMap::new, Collectors.counting()));
        long financeNoise = docs.stream().filter(d -> security.isFinanceNoise(d.url())).count();
        long official = docs.stream().filter(d -> security.isOfficial(d.url())).count();

        // “Technologies” keyword hits (ko/en)
        long techHits = docs.stream().filter(d ->
                containsAny(d.title(), "테크놀로지", "테크놀로지스", "Technologies")
                        || containsAny(d.snippet(), "테크놀로지", "테크놀로지스", "Technologies")
        ).count();

        // Verdict heuristic
        int total = docs.size();
        double noiseRatio = total == 0 ? 0.0 : (double) financeNoise / total;
        boolean pass = noiseRatio <= security.getMaxNoiseRatio();

        return ProbeResult.builder()
                .query(q)
                .normalized(normalized)
                .metaEcho(new LinkedHashMap<>(meta))
                .total(total)
                .byDomain(byDomain)
                .financeNoiseCount(financeNoise)
                .officialCount(official)
                .technologiesHits(techHits)
                .financeNoiseRatio(noiseRatio)
                .pass(pass)
                .docs(docs)
                .build();
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ✅ 시그니처와 내부 로직 변경
    private static Map<String, Object> toMapSafe(Object meta) {
        if (meta == null) return Collections.emptyMap();

        // 1) 이미 Map 이면 키를 String으로 변환
        if (meta instanceof Map<?, ?> rawMap) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : rawMap.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }

        // 2) asMap()/map() 리플렉션 시도
        try {
            Object raw;
            try {
                raw = meta.getClass().getMethod("asMap").invoke(meta);
            } catch (NoSuchMethodException e1) {
                raw = meta.getClass().getMethod("map").invoke(meta);
            }
            if (raw instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            }
        } catch (Throwable ignore) {
            // fall through
        }
        return Collections.emptyMap();
    }

    private static String hostOf(String url) {
        try {
            return StringUtils.hasText(url) ? Optional.ofNullable(new URI(url).getHost()).orElse("") : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean containsAny(String s, String... kws) {
        if (!StringUtils.hasText(s)) return false;
        String t = s.toLowerCase(Locale.ROOT);
        for (String k : kws) {
            if (t.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}