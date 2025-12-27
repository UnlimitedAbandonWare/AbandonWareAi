package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.LearningExampleRow;
import com.example.lms.dto.learning.TuningJobRequest;
import com.example.lms.dto.learning.TuningJobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * 통합 Gemini 클라이언트.
 * - REST 호출(번역/프롬프트 생성) + 학습 파이프라인용 스텁(curate/batch/tuning)을 한 곳에 모음.
 * - Bean 이름 충돌 방지를 위해 이 클래스만 남기고 learning/gemini 쪽 동명 클래스는 제거하세요.
 */
@Component("geminiClient")
@RequiredArgsConstructor
public class GeminiClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    /** 전역 WebClient.Builder 주입 */
    private final WebClient.Builder webClientBuilder;

    /** 설정 키 호환: gemini.api.key 또는 gemini.api-key */
    @Value("${gemini.api.key:${gemini.api-key:}}")
    private String apiKey;

    /*───────────────────────────────────────────────────────────────────
     * 1) 번역/텍스트 생성 - Public REST (generateContent)
     *───────────────────────────────────────────────────────────────────*/
    public Mono<String> translate(String text, String srcLang, String tgtLang) {
        String prompt = "Translate the following text from %s to %s: %s"
                .formatted(srcLang, tgtLang, text);
        return postToGemini(prompt)
                .map(r -> r.candidates().get(0).content().parts().get(0).text())
                .doOnSubscribe(s -> log.debug("▶ Gemini translate {}→{}", srcLang, tgtLang))
                .doOnError(e -> log.error("[Gemini] translate API 실패: {}", e.getMessage()))
                .onErrorResume(e -> Mono.just("[번역 실패] " + text));
    }

    public Mono<String> generate(String prompt) {
        return postToGemini(prompt)
                .map(this::toPrettyJson)
                .doOnSubscribe(s -> log.debug("▶ Gemini generate prompt='{}'", prompt))
                .doOnError(e -> log.error("[Gemini] generate API 실패: {}", e.getMessage()))
                .onErrorResume(e -> Mono.just("""
                        {
                          "ok"   : false,
                          "error": "%s"
                        }""".formatted(e.getMessage())));
    }

    // Base hook: Decorator에서 오버라이드, 기본은 no-op
    public List<String> keywordVariants(String cleaned, String anchor, int cap) {
        return java.util.Collections.emptyList();
    }

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

    private String toPrettyJson(GeminiResponse r) {
        String text = r.candidates().get(0).content().parts().get(0).text();
        ObjectMapper om = new ObjectMapper();
        ObjectNode node = om.createObjectNode();
        node.put("ok", true);
        node.put("data", text); // 자동 이스케이프
        return node.toPrettyString();
    }

    /*───────────────────────────────────────────────────────────────────
     * 2) 학습 파이프라인용 스텁 - SDK 연동 전까지 빈 결과 반환
     *    (필요 시 google-genai SDK로 구현 교체)
     *───────────────────────────────────────────────────────────────────*/
    public KnowledgeDelta curate(LearningEvent event, String model, Duration timeout) {
        return new KnowledgeDelta(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    public List<LearningExampleRow> batchNormalize(List<LearningEvent> events, String model) {
        return List.of();
    }

    public String startTuningJob(TuningJobRequest req) {
        return null;
    }

    public TuningJobStatus getTuningJobStatus(String jobId) {
        return null;
    }

    /*─── Response 매핑용 record 들 ───────────────────────────────────*/
    private record Part(String text) {}
    private record Content(List<Part> parts) {}
    private record Candidate(Content content) {}
    private record GeminiResponse(List<Candidate> candidates) {}
}