package com.example.lms.service.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Component("tavilyWebSearchRetriever")
@ConditionalOnProperty(prefix = "tavily", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class TavilyWebSearchRetriever implements ContentRetriever {
    private static final Logger log = LoggerFactory.getLogger(TavilyWebSearchRetriever.class);

    private final WebClient.Builder http;

    @Value("${tavily.api.url:https://api.tavily.com/search}")
    private String baseUrl;

    @Value("${tavily.api.key:}")
    private String apiKey;

    @Value("${tavily.max-results:5}")
    private int maxResults;

    @Value("${tavily.timeout-ms:3000}")
    private int timeoutMs;

    @Override
    public List<Content> retrieve(Query query) {
        String q = (query != null && query.text() != null) ? query.text().strip() : "";
        if (q.isBlank() || apiKey == null || apiKey.isBlank()) return List.of();

        try {
            WebClient client = http.baseUrl(baseUrl).build();
            Map<String, Object> req = Map.of(
                    "api_key", apiKey,
                    "query", q,
                    "max_results", Math.max(1, maxResults)
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .onErrorResume(e -> {
                        log.debug("[Tavily] call failed: {}", e.toString());
                        return Mono.empty();
                    })
                    // Execute the HTTP call on an elastic scheduler to avoid
                    // blocking reactive event loop threads.  The downstream
                    // block() remains synchronous, but the network I/O
                    // happens off the event loop.
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();

            if (resp == null) return List.of();

            List<Content> out = new ArrayList<>();
            Object results = resp.get("results"); // each: {title,url,content/snippet,/* ... */}
            if (results instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        String snippet = str(m.get("content"));
                        String url     = str(m.get("url"));
                        String text    = (snippet == null || snippet.isBlank())
                                ? url
                                : snippet + (url == null ? "" : "\n\n[출처] " + url);
                        if (text != null && !text.isBlank()) {
                            out.add(Content.from(text));
                        }
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("[Tavily] retrieve failed: {}", e.toString());
            return List.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}