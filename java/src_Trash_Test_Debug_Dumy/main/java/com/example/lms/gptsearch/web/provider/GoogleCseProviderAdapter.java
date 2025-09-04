package com.example.lms.gptsearch.web.provider;

import com.example.lms.gptsearch.web.MultiWebSearch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Custom Search Engine 어댑터.
 * CSE 프로바이더와 서비스 빈을 리플렉션으로 호출하여 MultiWebSearch.Result 형태로 변환합니다.
 */
/**
 * Register this adapter only when GPT search is enabled via
 * feature.gptsearch.enabled=true.  Combined with the bean conditional on
 * Google CSE provider/service this prevents injection when disabled.
 */
@ConditionalOnProperty(value = "feature.gptsearch.enabled", havingValue = "true", matchIfMissing = false)
@Component
@ConditionalOnBean(name = {"googleCseProvider", "googleCseService"})
public class GoogleCseProviderAdapter extends BaseWebProviderAdapter implements MultiWebSearch.Provider {

    private final Object cseProvider;
    private final Object cseService;

    public GoogleCseProviderAdapter(
            @Autowired(required = false) @Qualifier("googleCseProvider") Object cseProvider,
            @Autowired(required = false) @Qualifier("googleCseService") Object cseService
    ) {
        this.cseProvider = cseProvider;
        this.cseService = cseService;
    }

    @Override public String name() { return "google_cse"; }

    @Override
    public List<MultiWebSearch.Result> search(String query, int topK, Map<String, Object> meta) {
        List<MultiWebSearch.Result> out = new ArrayList<>();
        // provider 우선
        if (cseProvider != null) {
            try {
                @SuppressWarnings("unchecked")
                List<?> docs = (List<?>) cseProvider.getClass()
                        .getMethod("search", String.class, int.class, Map.class)
                        .invoke(cseProvider, query, topK, meta);
                for (Object d : docs) {
                    // Use array arguments to avoid varargs and the triple-dot syntax
                    String title = callString(d, new String[]{"getTitle", "title"});
                    String url = callString(d, new String[]{"getUrl", "getLink", "url", "link"});
                    String snippet = callString(d, new String[]{"getSnippet", "getDescription", "snippet"});
                    Instant published = parsePublished(d, new String[]{"getPublishedAt", "publishedAt"});
                    out.add(new MultiWebSearch.Result(title, url, snippet, published, 0.0, Map.of("provider", "google_cse")));
                }
                return out;
            } catch (Exception ignored) { }
        }
        // service 폴백
        if (cseService != null) {
            try {
                Object resp = tryInvoke(cseService, new String[]{"searchWithTrace", "search"}, query, topK);
                // Pass getter names as an array rather than varargs
                List<?> items = asList(extractList(resp, new String[]{"items", "getItems", "results", "getResults"}));
                for (Object it : items) {
                    String title = callString(it, new String[]{"getTitle", "title"});
                    String url = callString(it, new String[]{"getUrl", "getLink", "url", "link"});
                    String snippet = callString(it, new String[]{"getSnippet", "getDescription", "snippet"});
                    Instant published = parsePublished(it, new String[]{"getPublishedAt", "publishedAt"});
                    out.add(new MultiWebSearch.Result(title, url, snippet, published, 0.0, Map.of("provider", "google_cse", "trace", true)));
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