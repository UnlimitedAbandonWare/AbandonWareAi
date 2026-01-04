package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.LearningExampleRow;
import com.example.lms.dto.learning.TuningJobRequest;
import com.example.lms.dto.learning.TuningJobStatus;
import com.example.lms.guard.KeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Unified Gemini client.
 *
 * <p>Key resolution is centralized via {@link KeyResolver}.
 * BLUE(Gemini) is intended to be used only from offline/idle jobs (TrainingJobRunner).
 */
@Component("geminiClient")
public class GeminiClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final WebClient.Builder webClientBuilder;
    private final KeyResolver keyResolver;

    public GeminiClient(WebClient.Builder webClientBuilder, KeyResolver keyResolver) {
        this.webClientBuilder = webClientBuilder;
        this.keyResolver = keyResolver;
    }

    /*───────────────────────────────────────────────────────────────────
     * 1) Translation / generation - Public REST (generateContent)
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

    /**
     * Best-effort query variant helper (offline). Caller should enforce quota/cooldown.
     */
    public List<String> keywordVariants(String cleaned, String anchor, int cap) {
        KeywordVariantsResult r = keywordVariantsWithMeta(cleaned, anchor, cap, Duration.ofSeconds(12));
        return r == null ? Collections.emptyList() : r.variants();
    }

    /**
     * Best-effort query variant helper with HTTP status + headers (for ops/debug).
     *
     * <p>BLUE is intended for offline/idle jobs only. Caller should still enforce
     * max-calls/cooldown. This method surfaces response headers so that the caller
     * can store whitelisted x-goog/quota/rate-limit hints.</p>
     *
     * @throws WebClientResponseException when the Gemini API responds with a non-2xx status.
     */
    public KeywordVariantsResult keywordVariantsWithMeta(String cleaned, String anchor, int cap, Duration timeout) {
        int n = Math.max(0, cap);
        if (n <= 0) return new KeywordVariantsResult(Collections.emptyList(), null, HttpHeaders.EMPTY);

        String key = keyResolver.resolveGeminiApiKeyStrict();
        if (key == null || key.isBlank()) {
            return new KeywordVariantsResult(Collections.emptyList(), null, HttpHeaders.EMPTY);
        }

        String q = (cleaned == null || cleaned.isBlank()) ? (anchor == null ? "" : anchor) : cleaned;
        String prompt = """
                You are a search query expansion helper.
                Return only expanded queries in Korean, one per line, no numbering.
                Base query: %s
                Generate up to %d alternative queries.
                """.formatted(q, n);

        Duration t = timeout == null ? Duration.ofSeconds(12) : timeout;

        ResponseEntity<GeminiResponse> entity = postToGeminiEntity(prompt)
                .timeout(t)
                .block();

        if (entity == null) {
            return new KeywordVariantsResult(Collections.emptyList(), null, HttpHeaders.EMPTY);
        }

        GeminiResponse resp = entity.getBody();
        if (resp == null || resp.candidates() == null || resp.candidates().isEmpty()) {
            return new KeywordVariantsResult(Collections.emptyList(), entity.getStatusCodeValue(), entity.getHeaders());
        }

        String raw = resp.candidates().get(0).content().parts().get(0).text();
        if (raw == null || raw.isBlank()) {
            return new KeywordVariantsResult(Collections.emptyList(), entity.getStatusCodeValue(), entity.getHeaders());
        }

        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            if (line == null) continue;
            String s = line.trim();
            if (s.isEmpty()) continue;
            s = s.replaceAll("^[0-9]+[).:-]\\s*", "");
            if (s.length() > 200) s = s.substring(0, 200);
            if (!s.isBlank()) uniq.add(s);
            if (uniq.size() >= n) break;
        }

        return new KeywordVariantsResult(new ArrayList<>(uniq), entity.getStatusCodeValue(), entity.getHeaders());
    }

    private Mono<GeminiResponse> postToGemini(String prompt) {
        String apiKey = keyResolver.resolveGeminiApiKeyStrict();
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new IllegalStateException("Gemini API key missing"));
        }

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

    private Mono<ResponseEntity<GeminiResponse>> postToGeminiEntity(String prompt) {
        String apiKey = keyResolver.resolveGeminiApiKeyStrict();
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new IllegalStateException("Gemini API key missing"));
        }

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
                .toEntity(GeminiResponse.class);
    }

    private String toPrettyJson(GeminiResponse r) {
        String text = r.candidates().get(0).content().parts().get(0).text();
        ObjectMapper om = new ObjectMapper();
        ObjectNode node = om.createObjectNode();
        node.put("ok", true);
        node.put("data", text);
        return node.toPrettyString();
    }

    /*───────────────────────────────────────────────────────────────────
     * 2) Training pipeline stubs - SDK 연동 전까지 빈 결과 반환
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

    /*─── Response mapping records ───────────────────────────────────*/
    private record Part(String text) {}
    private record Content(List<Part> parts) {}
    private record Candidate(Content content) {}
    private record GeminiResponse(List<Candidate> candidates) {}

    /**
     * Keyword variants response wrapper containing status + headers.
     *
     * <p>Headers are returned as-is; callers should whitelist before persisting.</p>
     */
    public record KeywordVariantsResult(List<String> variants, Integer httpStatus, HttpHeaders headers) {}
}
