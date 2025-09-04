package com.example.lms.gptsearch.web.provider;

import com.example.lms.gptsearch.web.MultiWebSearch;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Naver 검색 결과를 MultiWebSearch.Result로 어댑트하는 어댑터.
 */
/**
 * Register this adapter only when GPT search is enabled via
 * feature.gptsearch.enabled=true.  Combined with the bean conditional on
 * Naver provider/service beans this prevents injection when disabled.
 */
@ConditionalOnProperty(value = "feature.gptsearch.enabled", havingValue = "true", matchIfMissing = false)
@Component
@ConditionalOnBean(name = {"naverSearchService", "naverProvider"})
public class NaverProviderAdapter extends BaseWebProviderAdapter {
    private final Object naverSearchService;
    private final Object naverProvider;
    public NaverProviderAdapter(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            @org.springframework.beans.factory.annotation.Qualifier("naverSearchService") Object naverSearchService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            @org.springframework.beans.factory.annotation.Qualifier("naverProvider") Object naverProvider) {
        this.naverSearchService = naverSearchService;
        this.naverProvider = naverProvider;
    }
    @Override
    public String name() { return "naver"; }
    @Override
    @SuppressWarnings("unchecked")
    public List<MultiWebSearch.Result> search(String query, int topK, Map<String, Object> meta) {
        List<MultiWebSearch.Result> out = new ArrayList<>();
        // 서비스 경로 우선
        if (naverSearchService != null) {
            try {
                Object resp = naverSearchService.getClass()
                        .getMethod("searchWithTrace", String.class, int.class)
                        .invoke(naverSearchService, query, topK);
                List<?> items = extractItems(resp);
                for (Object it : items) {
                    // Pass arrays of method names instead of varargs to avoid using the prohibited triple-dot syntax
                    String title = callString(it, new String[]{"getTitle", "title"});
                    String url = callString(it, new String[]{"getUrl", "getLink", "url", "link"});
                    String snippet = callString(it, new String[]{"getSnippet", "getDescription", "snippet", "description"});
                    Instant published = parsePublished(it, new String[]{"getPublishedAt", "getPubDate", "publishedAt", "pubDate"});
                    Map<String,Object> m = Map.of("provider", "naver", "trace", true);
                    out.add(new MultiWebSearch.Result(title, url, snippet, published, 0.0, m));
                }
                return out;
            } catch (NoSuchMethodException nsme) {
                // 폴백
            } catch (Exception e) {
                // fail-soft
            }
        }
        if (naverProvider != null) {
            try {
                List<?> docs = (List<?>) naverProvider.getClass()
                        .getMethod("search", String.class, int.class, Map.class)
                        .invoke(naverProvider, query, topK, meta);
                for (Object d : docs) {
                    // Use explicit String[] for reflection instead of varargs
                    String title = callString(d, new String[]{"getTitle", "title"});
                    String url = callString(d, new String[]{"getUrl", "getLink", "url", "link"});
                    String snippet = callString(d, new String[]{"getSnippet", "getDescription", "snippet", "description"});
                    Instant published = parsePublished(d, new String[]{"getPublishedAt", "getPubDate", "publishedAt", "pubDate"});
                    Map<String,Object> m = Map.of("provider", "naver");
                    out.add(new MultiWebSearch.Result(title, url, snippet, published, 0.0, m));
                }
            } catch (Exception e) {
                // fail-soft
            }
        }
        return out;
    }
    private static List<?> extractItems(Object resp) {
        try {
            var m = resp.getClass().getMethod("items");
            Object v = m.invoke(resp);
            if (v instanceof List<?> l) return l;
        } catch (Exception ignored) { }
        try {
            var m = resp.getClass().getMethod("getItems");
            Object v = m.invoke(resp);
            if (v instanceof List<?> l) return l;
        } catch (Exception ignored) { }
        return List.of();
    }
}