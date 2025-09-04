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
 * Tavily 검색 어댑터. Tavily Provider/Service 빈을 감싸 MultiWebSearch.Result로 변환합니다.
 */
/**
 * Register this adapter only when GPT search is enabled via
 * feature.gptsearch.enabled=true.  Combined with the bean conditional on
 * Tavily provider/service this prevents injection when disabled.
 */
@ConditionalOnProperty(value = "feature.gptsearch.enabled", havingValue = "true", matchIfMissing = false)
@Component
@ConditionalOnBean(name = {"tavilyProvider", "tavilyService"})
public class TavilyProviderAdapter extends BaseWebProviderAdapter implements MultiWebSearch.Provider {

    private final Object provider;
    private final Object service;

    public TavilyProviderAdapter(
            @Autowired(required = false) @Qualifier("tavilyProvider") Object provider,
            @Autowired(required = false) @Qualifier("tavilyService") Object service
    ) {
        this.provider = provider;
        this.service = service;
    }

    @Override public String name() { return "tavily"; }

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
                    // Use arrays of getter names instead of varargs
                    String title = callString(d, new String[]{"getTitle", "title"});
                    String url = callString(d, new String[]{"getUrl", "getLink", "url"});
                    String snippet = callString(d, new String[]{"getSnippet", "getSummary", "snippet", "summary"});
                    Instant published = parsePublished(d, new String[]{"getPublishedAt", "publishedAt"});
                    out.add(new MultiWebSearch.Result(title, url, snippet, published, 0.0, Map.of("provider", "tavily")));
                }
                return out;
            } catch (Exception ignored) { }
        }
        if (service != null) {
            try {
                Object resp = tryInvoke(service, new String[]{"searchWithTrace", "search"}, query, topK);
                List<?> items = asList(extractList(resp, new String[]{"items", "getItems", "results", "getResults"}));
                for (Object it : items) {
                    // Use arrays for reflection instead of varargs
                    String title = callString(it, new String[]{"getTitle", "title"});
                    String url = callString(it, new String[]{"getUrl", "getLink", "url"});
                    String snippet = callString(it, new String[]{"getSnippet", "getSummary", "snippet", "summary"});
                    Instant published = parsePublished(it, new String[]{"getPublishedAt", "publishedAt"});
                    out.add(new MultiWebSearch.Result(title, url, snippet, published, 0.0, Map.of("provider", "tavily", "trace", true)));
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
     * Extracts a list from the response by invoking the given getter names in order.
     *
     * <p>This method accepts a {@code String[]} for the getter names to avoid
     * the varargs ellipsis (the triple-dot syntax) in the signature, complying with the
     * project's purity rules.</p>
     *
     * @param resp the response object
     * @param getters the potential getter method names
     * @return the first list returned, or an empty list if none succeed
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