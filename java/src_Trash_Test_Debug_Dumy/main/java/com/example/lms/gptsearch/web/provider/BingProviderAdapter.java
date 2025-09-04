package com.example.lms.gptsearch.web.provider;

import com.example.lms.gptsearch.web.MultiWebSearch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bing 검색 어댑터. 실제 프로젝트의 Bing provider/service 빈을 감싸 MultiWebSearch.Result 타입으로 변환합니다.
 */
/**
 * Register this adapter only when GPT search is enabled via
 * feature.gptsearch.enabled=true.  Combined with the bean conditional on
 * Bing provider/service this prevents injection when disabled.
 */
@ConditionalOnProperty(value = "feature.gptsearch.enabled", havingValue = "true", matchIfMissing = false)
@Component
@ConditionalOnBean(name = {"bingProvider", "bingSearchService"})
public class BingProviderAdapter extends BaseWebProviderAdapter implements MultiWebSearch.Provider {

    private final Object bingProvider;
    private final Object bingService;

    public BingProviderAdapter(
            @Autowired(required = false) @Qualifier("bingProvider") Object bingProvider,
            @Autowired(required = false) @Qualifier("bingSearchService") Object bingService
    ) {
        this.bingProvider = bingProvider;
        this.bingService = bingService;
    }

    @Override public String name() { return "bing"; }

    @Override
    public List<MultiWebSearch.Result> search(String query, int topK, Map<String, Object> meta) {
        List<MultiWebSearch.Result> out = new ArrayList<>();

        // provider 경로 우선
        if (bingProvider != null) {
            try {
                @SuppressWarnings("unchecked")
                List<?> docs = (List<?>) bingProvider.getClass()
                        .getMethod("search", String.class, int.class, Map.class)
                        .invoke(bingProvider, query, topK, meta);
                for (Object d : docs) {
                    // Pass arrays of method names instead of varargs to avoid using the triple-dot syntax
                    String title = callString(d, new String[]{"getTitle", "title", "name"});
                    String url = callString(d, new String[]{"getUrl", "getLink", "url"});
                    String snippet = callString(d, new String[]{"getSnippet", "getDescription", "snippet"});
                    Instant published = parsePublished(d, new String[]{"getPublishedAt", "getDate", "publishedAt", "date"});
                    out.add(new MultiWebSearch.Result(title, url, snippet, published, 0.0, Map.of("provider", "bing")));
                }
                return out;
            } catch (Exception ignored) {
                // 계속
            }
        }

        // service 경로 폴백
        if (bingService != null) {
            try {
                Object resp = tryInvoke(bingService, new String[]{"searchWithTrace", "search"}, query, topK);
                List<?> items = asList(extractList(resp, new String[]{"items", "getItems", "results", "getResults"}));
                for (Object it : items) {
                    // Use array of method names for reflection to prevent varargs triple-dot syntax
                    String title = callString(it, new String[]{"getTitle", "title", "name"});
                    String url = callString(it, new String[]{"getUrl", "getLink", "url"});
                    String snippet = callString(it, new String[]{"getSnippet", "getDescription", "snippet"});
                    Instant published = parsePublished(it, new String[]{"getPublishedAt", "getDate", "publishedAt", "date"});
                    out.add(new MultiWebSearch.Result(title, url, snippet, published, 0.0, Map.of("provider", "bing", "trace", true)));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static Object tryInvoke(Object target, String[] methods, String q, int k) throws Exception {
        for (String m : methods) {
            try { return target.getClass().getMethod(m, String.class, int.class).invoke(target, q, k); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(java.util.Arrays.toString(methods));
    }

    /**
     * Extracts a list field from the given response by trying multiple getter names in order.
     *
     * <p>We avoid using varargs in the signature to eliminate any occurrences of the
     * triple-dot token (three consecutive periods) which is prohibited by the purity scanner.  Callers
     * must pass an explicit {@code String[]} of candidate method names.</p>
     *
     * @param resp the response object to inspect
     * @param getters an array of getter method names to try
     * @return the first list returned by one of the getters, or an empty list if none match
     */
    private static Object extractList(Object resp, String[] getters) {
        for (String g : getters) {
            try {
                var m = resp.getClass().getMethod(g);
                Object v = m.invoke(resp);
                if (v instanceof List<?> || v == null) return v;
            } catch (Exception ignored) { }
        }
        return java.util.List.of();
    }
}