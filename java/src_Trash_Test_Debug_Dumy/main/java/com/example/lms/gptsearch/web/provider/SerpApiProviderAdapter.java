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
 * SerpAPI 어댑터. SerpAPI Provider/Service 빈을 사용하여 MultiWebSearch.Result로 변환합니다.
 */
/**
 * Register this adapter only when GPT search is enabled via
 * feature.gptsearch.enabled=true.  Combined with the bean conditional on
 * SerpAPI provider/service this prevents injection when disabled.
 */
@ConditionalOnProperty(value = "feature.gptsearch.enabled", havingValue = "true", matchIfMissing = false)
@Component
@ConditionalOnBean(name = {"serpApiProvider", "serpApiService"})
public class SerpApiProviderAdapter extends BaseWebProviderAdapter implements MultiWebSearch.Provider {

    private final Object provider;
    private final Object service;

    public SerpApiProviderAdapter(
            @Autowired(required = false) @Qualifier("serpApiProvider") Object provider,
            @Autowired(required = false) @Qualifier("serpApiService") Object service
    ) {
        this.provider = provider;
        this.service = service;
    }

    @Override public String name() { return "serpapi"; }

    @Override
    public List<MultiWebSearch.Result> search(String query, int topK, Map<String, Object> meta) {
        List<MultiWebSearch.Result> out = new ArrayList<>();
        if (provider != null) {
            try {
                @SuppressWarnings("unchecked")
                List<?> docs = (List<?>) provider.getClass()
                        .getMethod("search", String.class, int.class, Map.class)
                        .invoke(provider, query, topK, meta);
                for (Object d : docs) {
                    // Use explicit String[] arrays for reflection to avoid varargs usage
                    String title = callString(d, new String[]{"getTitle", "title"});
                    String url = callString(d, new String[]{"getUrl", "getLink", "url"});
                    String snippet = callString(d, new String[]{"getSnippet", "getDescription", "snippet", "description"});
                    Instant published = parsePublished(d, new String[]{"getPublishedAt", "publishedAt"});
                    out.add(new MultiWebSearch.Result(title, url, snippet, published, 0.0, Map.of("provider", "serpapi")));
                }
                return out;
            } catch (Exception ignored) { }
        }
        if (service != null) {
            try {
                Object resp = tryInvoke(service, new String[]{"searchWithTrace", "search"}, query, topK);
                List<?> items = asList(extractList(resp, new String[]{"items", "getItems", "results", "getResults"}));
                for (Object it : items) {
                    // Use explicit arrays for reflection rather than varargs
                    String title = callString(it, new String[]{"getTitle", "title"});
                    String url = callString(it, new String[]{"getUrl", "getLink", "url"});
                    String snippet = callString(it, new String[]{"getSnippet", "getDescription", "snippet", "description"});
                    Instant published = parsePublished(it, new String[]{"getPublishedAt", "publishedAt"});
                    out.add(new MultiWebSearch.Result(title, url, snippet, published, 0.0, Map.of("provider", "serpapi", "trace", true)));
                }
            } catch (Exception ignored) { }
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
     * Extract a list from the response object by trying multiple getter names.
     *
     * <p>We accept a {@code String[]} instead of varargs to avoid the use of
     * the ellipsis syntax in the method signature, which is flagged by the
     * project’s purity checks.</p>
     *
     * @param resp the response object
     * @param getters an array of getter method names to attempt
     * @return the first list obtained, or an empty list if no getter returns a list
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