package com.example.lms.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Google Translate v2 REST 래퍼
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GTranslateClient {

    /** WebClientConfig 에서 만든 @Bean(name="googleTranslateWebClient") */
    @Qualifier("googleTranslateWebClient")
    private final WebClient webClient;

    /** application.properties 에 comma 로 나열된 여러 API Key */
    @Value("${google.translate.keys}")
    private List<String> apiKeys;

    private int keyIndex = 0;   // simple round-robin

    public Mono<String> translate(String text, String srcLang, String tgtLang) {

        String apiKey = apiKeys.get(keyIndex++ % apiKeys.size());
        String url = "/language/translate/v2?key=" + apiKey;

        Map<String, Object> body = Map.of(
                "q",      text,
                "source", srcLang,
                "target", tgtLang,
                "format", "text"
        );

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(GoogleTranslateResponse.class)
                .map(r -> r.data().translations().get(0).translatedText())
                .doOnError(e -> log.error("[GTranslate] API 호출 실패: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty());   // 실패 시 Gemini 로 폴백
    }

    /* ▼ (GSON/Jackson record 매핑용) */
    private record Translation(String translatedText) {}
    private record TranslationData(List<Translation> translations) {}
    private record GoogleTranslateResponse(TranslationData data) {}
}
