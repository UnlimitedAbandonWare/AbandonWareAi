package com.example.lms.service.web;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Wikipedia 등 MediaWiki API에서 문서 요약을 직접 가져오는 클라이언트.
 * <p>
 * 이 서비스는 간단한 검색을 수행하여 해당 제목의 페이지 요약을 가져옵니다.  반환되는
 * 문자열은 "제목 - 요약" 형식이며, 요약은 600자 정도로 잘립니다.  언어와
 * User-Agent는 애플리케이션 설정을 통해 지정할 수 있습니다.
 */
@Service
@RequiredArgsConstructor
public class MediaWikiClient {
    private static final Logger log = LoggerFactory.getLogger(MediaWikiClient.class);

    /**
     * A WebClient instance used for performing MediaWiki API requests.  The full
     * URI is specified in each call so the configured base URL on the injected
     * client (if any) does not impact the request.  Customize this client via
     * a qualifier if you need special timeout settings.
     */
    private final WebClient webClient;

    @Value("${search.wiki.enabled:true}")
    private boolean enabled;
    @Value("${search.wiki.lang:en}")
    private String lang;
    @Value("${search.wiki.user-agent:AbandonWareAI/1.0 (contact: you@example.com)}")
    private String userAgent;

    /**
     * Perform a search on the configured MediaWiki instance and return a list
     * of page extracts.  If the service is disabled, an empty list will be
     * returned.  Any exceptions are caught and logged, resulting in an empty
     * list.
     *
     * @param query    the search query
     * @param maxPages the maximum number of pages to fetch
     * @return a list of "title - extract" strings
     */
    public List<String> searchExtracts(String query, int maxPages) {
        if (!enabled) {
            return List.of();
        }
        try {
            String base = "https://" + lang + ".wikipedia.org/w/api.php";
            String url = base + "?action=query&list=search&srsearch=" + enc(query)
                    + "&format=json&srlimit=" + Math.max(1, maxPages);
            String body = webClient.get().uri(url)
                    .header("User-Agent", userAgent)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorReturn("")
                    .block();
            if (body == null || body.isBlank()) {
                return List.of();
            }
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            JsonNode search = root.path("query").path("search");
            if (!search.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonNode n : search) {
                String title = n.path("title").asText("");
                if (title.isBlank()) {
                    continue;
                }
                // extracts
                String extUrl = base + "?action=query&prop=extracts&explaintext=true&format=json&titles=" + enc(title);
                String ext = webClient.get().uri(extUrl)
                        .header("User-Agent", userAgent)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(8))
                        .onErrorReturn("")
                        .block();
                if (ext == null || ext.isBlank()) {
                    continue;
                }
                JsonNode pages = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ext)
                        .path("query").path("pages");
                pages.fields().forEachRemaining(e -> {
                    String text = e.getValue().path("extract").asText("");
                    if (!text.isBlank()) {
                        // 첫 600자 정도만
                        out.add((title + " - " + trim(text, 600)).trim());
                    }
                });
                if (out.size() >= maxPages) {
                    break;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("MediaWiki query failed", e);
            return List.of();
        }
    }

    private static String trim(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}