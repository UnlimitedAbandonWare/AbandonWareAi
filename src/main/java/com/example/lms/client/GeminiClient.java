// ── src/main/java/com/example/lms/client/GeminiClient.java ──────────────
package com.example.lms.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Google AI Gemini-Pro (flash) 백업 번역/텍스트-생성 클라이언트.
 *  - Google Translate 실패 시 translate() 를,
 *  - 자유 프롬프트 → JSON / 텍스트 생성은 generate() 를 사용.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiClient {

    /** 전역 WebClient.Builder 주입 */
    private final WebClient.Builder webClientBuilder;

    @Value("${gemini.api.key}")
    private String apiKey;

    /*───────────────────────────────────────────────────────────────────
     * 1) 번역용 – 기존 로직
     *───────────────────────────────────────────────────────────────────*/
    public Mono<String> translate(String text, String srcLang, String tgtLang) {

        String prompt = "Translate the following text from %s to %s: %s"
                .formatted(srcLang, tgtLang, text);

        return postToGemini(prompt)
                // 응답 JSON → 번역문 String
                .map(r -> r.candidates().get(0)
                        .content().parts().get(0).text())
                .doOnSubscribe(s -> log.debug("▶ Gemini translate {}→{}", srcLang, tgtLang))
                .doOnError(e -> log.error("[Gemini] translate API 실패: {}", e.getMessage()))
                .onErrorResume(e -> Mono.just("[번역 실패] " + text));
    }

    /*───────────────────────────────────────────────────────────────────
     * 2) 🔥 새 기능 : 프리-프롬프트를 넘겨 자유 형식 생성
     *               (TrainingService 등에서 호출)
     *───────────────────────────────────────────────────────────────────*/
    public Mono<String> generate(String prompt) {

        return postToGemini(prompt)
                .map(this::toPrettyJson)              // 필요하면 가공
                .doOnSubscribe(s -> log.debug("▶ Gemini generate prompt='{}'", prompt))
                .doOnError(e -> log.error("[Gemini] generate API 실패: {}", e.getMessage()))
                .onErrorResume(e -> Mono.just("""
                        {
                          "ok"   : false,
                          "error": "%s"
                        }""".formatted(e.getMessage())));
    }

    /*────────────────────────  공통 HTTP 호출  ───────────────────────*/
    private Mono<GeminiResponse> postToGemini(String prompt) {

        WebClient client = webClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();

        String url = "/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;

        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                )
        );

        return client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(GeminiResponse.class);
    }

    /*────────────────────────  JSON 헬퍼  ────────────────────────────*/
    private String toPrettyJson(GeminiResponse r) {
        // candidates[0].content.parts[0].text 만 꺼내도 되고
        // 필요 시 전체 JSON 을 문자열로 직렬화해도 됩니다.
        return """
               {
                 "ok"  : true,
                 "data": "%s"
               }""".formatted(
                r.candidates().get(0).content().parts().get(0).text()
        );
    }

    /*─── Response 매핑용 record 들 (Jackson / Gson 호환) ─────────────*/
    private record Part(String text) {}
    private record Content(List<Part> parts) {}
    private record Candidate(Content content) {}
    private record GeminiResponse(List<Candidate> candidates) {}
}
