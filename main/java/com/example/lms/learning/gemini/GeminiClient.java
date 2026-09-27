package com.example.lms.learning.gemini;

import com.example.lms.dto.learning.KnowledgeDelta;
import com.example.lms.dto.learning.LearningEvent;
import com.example.lms.dto.learning.LearningExampleRow;
import com.example.lms.dto.learning.TuningJobRequest;
import com.example.lms.dto.learning.TuningJobStatus;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Unified Gemini client.
 *
 * <p>All provider calls are delegated to {@link GeminiGateway}. BLUE/Gemini is
 * intended for offline or idle jobs, not request-path learning writes.</p>
 */
@Component("geminiClient")
public class GeminiClient {
    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final GeminiGateway gateway;

    public GeminiClient(GeminiGateway gateway) {
        this.gateway = gateway;
    }

    public Mono<String> translate(String text, String srcLang, String tgtLang) {
        String translationPrompt = "Translate the following text from %s to %s: %s"
                .formatted(srcLang, tgtLang, text);
        return gateway.generate(translationPrompt, GeminiGateway.Purpose.TRANSLATION)
                .flatMap(result -> result.text().isBlank()
                        ? Mono.error(new IllegalStateException(result.status().fallbackReason()))
                        : Mono.just(result.text()))
                .doOnSubscribe(s -> log.debug("Gemini translate srcLang={} tgtLang={}", srcLang, tgtLang))
                .doOnError(e -> log.error("[Gemini] translate API failed. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(e)), messageLength(e)))
                .onErrorResume(e -> Mono.just("[translation failed] " + safeFallbackText(text)));
    }

    public Mono<String> generate(String prompt) {
        return gateway.generate(prompt, GeminiGateway.Purpose.UNDERSTANDING)
                .flatMap(result -> result.text().isBlank()
                        ? Mono.error(new IllegalStateException(result.status().fallbackReason()))
                        : Mono.just(toPrettyJson(result.text())))
                .doOnSubscribe(s -> log.debug("Gemini generate promptHash={} promptLength={}",
                        SafeRedactor.hashValue(prompt), prompt == null ? 0 : prompt.length()))
                .doOnError(e -> log.error("[Gemini] generate API failed. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(e)), messageLength(e)))
                .onErrorResume(e -> Mono.just("""
                        {
                          "ok"   : false,
                          "errorHash": "%s",
                          "errorLength": %d
                        }""".formatted(SafeRedactor.hashValue(messageOf(e)), messageLength(e))));
    }

    /**
     * Best-effort query variant helper. Caller should enforce quota/cooldown.
     */
    public List<String> keywordVariants(String cleaned, String anchor, int cap) {
        KeywordVariantsResult r = keywordVariantsWithMeta(cleaned, anchor, cap, Duration.ofSeconds(12));
        return r == null ? Collections.emptyList() : r.variants();
    }

    /**
     * Best-effort query variant helper with HTTP status and headers for ops/debug.
     */
    public KeywordVariantsResult keywordVariantsWithMeta(String cleaned, String anchor, int cap, Duration timeout) {
        int n = Math.max(0, cap);
        if (n <= 0) return new KeywordVariantsResult(Collections.emptyList(), null, HttpHeaders.EMPTY);

        String q = (cleaned == null || cleaned.isBlank()) ? (anchor == null ? "" : anchor) : cleaned;
        String keywordVariantPrompt = """
                You are a search query expansion helper.
                Return only expanded queries in Korean, one per line, no numbering.
                Base query: %s
                Generate up to %d alternative queries.
                """.formatted(q, n);

        Duration t = timeout == null ? Duration.ofSeconds(12) : timeout;

        GeminiGateway.GenerationResult result;
        try {
            result = gateway.generate(keywordVariantPrompt, GeminiGateway.Purpose.KEYWORD_TRAINING)
                    .timeout(t)
                    .block();
        } catch (RuntimeException e) {
            traceKeywordVariantSuppressed(e);
            return new KeywordVariantsResult(Collections.emptyList(), null, HttpHeaders.EMPTY);
        }

        if (result == null) {
            return new KeywordVariantsResult(Collections.emptyList(), null, HttpHeaders.EMPTY);
        }

        String raw = result.text();
        if (raw == null || raw.isBlank()) {
            return new KeywordVariantsResult(
                    Collections.emptyList(), result.status().statusCode(), HttpHeaders.EMPTY);
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

        return new KeywordVariantsResult(
                new ArrayList<>(uniq), result.status().statusCode(), HttpHeaders.EMPTY);
    }

    private String toPrettyJson(String text) {
        ObjectMapper om = new ObjectMapper();
        ObjectNode node = om.createObjectNode();
        node.put("ok", true);
        node.put("data", text);
        return node.toPrettyString();
    }

    private static String safeFallbackText(String text) {
        String safe = SafeRedactor.safeMessage(text, 4_000);
        return safe == null ? "" : safe;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private static void traceKeywordVariantSuppressed(Throwable failure) {
        String message = messageOf(failure);
        TraceStore.put("gemini.suppressed.stage", "keywordVariants");
        TraceStore.put("gemini.suppressed.errorType",
                failure == null ? "unknown"
                        : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown"));
        TraceStore.put("gemini.suppressed.messageHash", SafeRedactor.hashValue(message));
        TraceStore.put("gemini.suppressed.messageLength", message == null ? 0 : message.length());
    }

    public KnowledgeDelta curate(LearningEvent event, String model, Duration timeout) {
        if (event == null) {
            return emptyKnowledgeDelta();
        }
        ObjectMapper mapper = new ObjectMapper();
        String eventJson;
        try {
            eventJson = mapper.writeValueAsString(event);
        } catch (Exception serializationFailure) {
            traceSuppressed("curation-serialize", serializationFailure);
            return emptyKnowledgeDelta();
        }

        String prompt = """
                Convert the learning event below into a KnowledgeDelta JSON object.
                Return JSON only with exactly these array fields:
                triples, rules, aliases, memories, protectedTerms.
                Learning event:
                %s
                """.formatted(eventJson);
        Duration effectiveTimeout = timeout == null ? Duration.ofSeconds(12) : timeout;
        try {
            GeminiGateway.GenerationResult result = gateway.generate(prompt, GeminiGateway.Purpose.CURATION)
                    .timeout(effectiveTimeout)
                    .block();
            if (result == null || result.text() == null || result.text().isBlank()) {
                return emptyKnowledgeDelta();
            }
            return mapper.readValue(stripJsonFence(result.text()), KnowledgeDelta.class);
        } catch (Exception failure) {
            traceSuppressed("curation", failure);
            return emptyKnowledgeDelta();
        }
    }

    private static KnowledgeDelta emptyKnowledgeDelta() {
        return new KnowledgeDelta(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    private static String stripJsonFence(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("```")) {
            int firstNewline = normalized.indexOf('\n');
            int closingFence = normalized.lastIndexOf("```");
            if (firstNewline >= 0 && closingFence > firstNewline) {
                normalized = normalized.substring(firstNewline + 1, closingFence).trim();
            }
        }
        return normalized;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String message = messageOf(failure);
        TraceStore.put("gemini.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("gemini.suppressed.errorType",
                failure == null ? "unknown"
                        : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown"));
        TraceStore.put("gemini.suppressed.messageHash", SafeRedactor.hashValue(message));
        TraceStore.put("gemini.suppressed.messageLength", message == null ? 0 : message.length());
    }

    public String startTuningJob(TuningJobRequest request) {
        TraceStore.put("gemini.tuning.disabled", true);
        TraceStore.put("gemini.tuning.disabledReason", "vertex_tuning_client_unavailable");
        return "disabled:" + SafeRedactor.hash12(request == null ? null : request.toString());
    }

    public TuningJobStatus getTuningJobStatus(String jobId) {
        String safeJobId = jobId == null || jobId.isBlank() ? "unknown" : jobId;
        return new TuningJobStatus(safeJobId, "DISABLED", "vertex_tuning_client_unavailable");
    }

    public List<LearningExampleRow> batchNormalize(List<LearningEvent> events, String model) {
        return List.of();
    }

    /**
     * Keyword variants response wrapper containing status and headers.
     *
     * <p>Headers are returned as-is; callers should whitelist before persisting.</p>
     */
    public record KeywordVariantsResult(List<String> variants, Integer httpStatus, HttpHeaders headers) {}
}
