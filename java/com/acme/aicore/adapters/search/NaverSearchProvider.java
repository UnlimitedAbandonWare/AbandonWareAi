package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.SearchBundle.Doc;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import com.example.lms.service.NaverSearchService;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.util.HtmlTextUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter that exposes the main application's {@link NaverSearchService} via the
 * {@link com.acme.aicore.domain.ports.WebSearchProvider} interface.
 *
 * <p>This provider exists so that {@link CachedWebSearch} fan-out can keep working
 * even when the primary web-search provider path is degraded. The upstream
 * {@link NaverSearchService} already contains its own filtering + fail-soft logic;
 * this class focuses only on wiring + lightweight mapping.</p>
 */
@Component
@RequiredArgsConstructor
public class NaverSearchProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(NaverSearchProvider.class);
    private static final AtomicBoolean LOGGED_EMPTY_QUERY = new AtomicBoolean(false);

    @Value("${gpt-search.naver.top-k:${search.naver.top-k:6}}")
    private int topK;

    private final NaverSearchService naver;

    @Override
    public String id() {
        return "naver";
    }

    @Override
    public int priority() {
        // Keep Naver first so that fanout=2 selects Naver + Brave.
        return 5;
    }

    @Override
    public Mono<SearchBundle> search(WebSearchQuery query) {
        final String q = (query == null || query.text() == null) ? "" : query.text().trim();
        if (q.isEmpty()) {
            if (LOGGED_EMPTY_QUERY.compareAndSet(false, true)) {
                log.debug("[SKIP_EMPTY_QUERY] NaverSearchProvider skipped (blank query){}", LogCorrelation.suffix());
            }
            return Mono.just(new SearchBundle("web", List.of()));
        }

        final int k = Math.max(1, topK);

        // NaverSearchService does blocking work (it may block on reactive calls) so run on boundedElastic.
        return Mono.fromCallable(ContextPropagation.wrapCallable(() -> {
                    List<String> raw = naver.searchSnippets(q, k);
                    if (raw == null || raw.isEmpty()) {
                        return new SearchBundle("web", List.of());
                    }

                    List<Doc> docs = new ArrayList<>(raw.size());
                    for (String line : raw) {
                        if (line == null || line.isBlank()) {
                            continue;
                        }

                        String url = HtmlTextUtil.normalizeUrl(HtmlTextUtil.extractFirstHref(line));
                        String title = HtmlTextUtil.extractAnchorText(line);
                        String snippet = HtmlTextUtil.stripAndCollapse(HtmlTextUtil.afterAnchor(line));
                        if (snippet == null || snippet.isBlank()) {
                            snippet = HtmlTextUtil.stripAndCollapse(line);
                        }

                        if ((url == null || url.isBlank())
                                && (title == null || title.isBlank())
                                && (snippet == null || snippet.isBlank())) {
                            continue;
                        }

                        String keyMaterial = (url != null && !url.isBlank())
                                ? url
                                : ((title == null ? "" : title) + "|" + (snippet == null ? "" : snippet));
                        String id = DigestUtils.sha1Hex(keyMaterial);
                        docs.add(new Doc(id, title, snippet, url, null));
                    }

                    return new SearchBundle("web", docs);
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    log.debug("NaverSearchProvider failed: {}{}", ex.toString(), LogCorrelation.suffix());
                    return Mono.just(new SearchBundle("web", List.of()));
                });
    }
}
