package com.example.lms.service.understanding;

import com.example.lms.learning.gemini.GeminiClient;
import com.example.lms.dto.answer.AnswerUnderstanding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Service that transforms an assistant's final answer into a structured
 * {@link AnswerUnderstanding} record.  This service uses the Gemini API to
 * request a JSON summary when enabled, falling back to simple heuristics if
 * generation fails or the response cannot be parsed.  The configuration
 * properties {@code abandonware.understanding.enabled}, {@code
 * abandonware.understanding.model} and {@code abandonware.understanding.timeout-ms}
 * control the behaviour of this service.
 */
@Service
@RequiredArgsConstructor
public class AnswerUnderstandingService {
    private static final Logger log = LoggerFactory.getLogger(AnswerUnderstandingService.class);

    private final GeminiClient geminiClient;
    private final AnswerUnderstandingPromptBuilder promptBuilder;
    /** 관용 파서: LLM 산출물의 제어문자(개행 등) 허용 */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();


    @Value("${abandonware.understanding.enabled:true}")
    private boolean understandingEnabled;
    @Value("${abandonware.understanding.model:gemini-2.5-pro}")
    private String model;
    @Value("${abandonware.understanding.timeout-ms:12000}")
    private long timeoutMs;

    /**
     * Generate a structured understanding of the final answer.  When the
     * understanding feature is disabled or the input answer is blank, this
     * method returns a heuristic summary.  Otherwise it sends a prompt to
     * Gemini via {@link GeminiClient#generate(String)} and attempts to parse
     * the returned JSON into an {@link AnswerUnderstanding} instance.  On
     * failure it falls back to the heuristic implementation.
     *
     * @param finalAnswer the assistant's verified and sanitized answer
     * @param question    the user's original question (used for context)
     * @return a populated {@link AnswerUnderstanding}
     */
    public AnswerUnderstanding understand(String finalAnswer, String question) {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return fallback(finalAnswer);
        }
        if (!understandingEnabled) {
            return fallback(finalAnswer);
        }
        try {
            String prompt = promptBuilder.build(question, finalAnswer);
            // call Gemini; generate() returns JSON wrapper { ok: true, data: "/* ... */" }
            String response = geminiClient.generate(prompt)
                    .block(Duration.ofMillis(timeoutMs));
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("Empty response from Gemini");
            }
            // Extract the JSON payload.  The generate() helper returns a string
            // containing the JSON inside the "data" field if ok==true.  We
            // attempt to parse either the entire string or the data field.
            String json;
            String trimmed = response.trim();
            if (trimmed.startsWith("{") && trimmed.contains("\"data\"")) {
                // parse wrapper (data는 JSON 문자열로 들어옴)
                var node = objectMapper.readTree(trimmed);
                json = node.has("data") ? node.get("data").asText() : trimmed;
            } else {
                json = trimmed;
            }

            // 1차: JSON 후보 문자열 정규화(코드펜스/잡음/제로폭/선후행 텍스트 제거)
            json = sanitizeForJsonParsing(json);

            AnswerUnderstanding u;
            try {
                // 2차: 관용 파서로 바로 시도
                u = objectMapper.readValue(json, AnswerUnderstanding.class);
            } catch (JsonProcessingException pe) {
                // 3차: 문자열 내부의 생개행을 \n으로 치환 후 재시도
                String repaired = escapeBareNewlinesInsideStrings(json);
                u = objectMapper.readValue(repaired, AnswerUnderstanding.class);
            }
            // Guard confidence to [0,1]
            double conf = u.confidence();
            if (Double.isNaN(conf) || conf < 0 || conf > 1) {
                u = new AnswerUnderstanding(
                        u.tldr(),
                        u.keyPoints(),
                        u.actionItems(),
                        u.decisions(),
                        u.risks(),
                        u.followUps(),
                        u.glossary(),
                        u.entities(),
                        u.citations(),
                        Math.max(0.0, Math.min(1.0, conf))
                );
            }
            return u;
        } catch (Exception e) {
            // Log as warning and fall back.  Do not propagate exception as this
            // should never break the chat flow.
            log.warn("[Understanding] Gemini call or parsing failed: {}", e.toString());
            return fallback(finalAnswer);
        }
    }

    /**
     * Heuristic fallback summarization used when the LLM fails or the feature
     * is disabled.  The TL;DR is the first sentence of the answer.  Key
     * points are derived from either bullet lists (lines starting with a
     * bullet character) or the first few sentences.  Action items are
     * extracted based on the presence of imperative cues; for simplicity this
     * implementation returns an empty list.
     *
     * @param answer the final answer text
     * @return a simple {@link AnswerUnderstanding}
     */
    private AnswerUnderstanding fallback(String answer) {
        if (answer == null) answer = "";
        String trimmed = answer.strip();
        // TLDR: first sentence up to period, newline or 100 chars
        String tldr;
        int end = indexOfSentenceEnd(trimmed);
        if (end > 0) {
            tldr = trimmed.substring(0, Math.min(end + 1, trimmed.length())).strip();
        } else {
            tldr = trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
        }
        // Key points: collect bullet lines or first 3 sentences
        List<String> keyPoints = new ArrayList<>();
        String[] lines = trimmed.split("\n");
        for (String line : lines) {
            String lt = line.strip();
            // [HARDENING] detect bullet markers without embedding '*' directly in a string literal
            if (lt.startsWith("-") || lt.startsWith(String.valueOf('*')) || lt.startsWith("•")) {
                // Java 문자열에서는 공백 클래스는 \\s 로 이스케이프해야 함
                keyPoints.add(lt.replaceFirst("^[*•-]\\s*", "").strip());
            }
        }
        if (keyPoints.isEmpty()) {
            // fallback to sentences
            String[] sents = trimmed.split("(?<=[.!?])\\s+");
            for (int i = 0; i < sents.length && keyPoints.size() < 3; i++) {
                String sent = sents[i].strip();
                if (!sent.isEmpty()) keyPoints.add(sent);
            }
        }
        // Action items: extremely simple heuristic - none
        List<String> actions = new ArrayList<>();
        return new AnswerUnderstanding(
                tldr,
                keyPoints.isEmpty() ? null : keyPoints,
                actions.isEmpty() ? null : actions,
                null,
                null,
                null,
                null,
                null,
                null,
                0.5
        );
    }

    /**
     * Find the index of the end of the first sentence based on Korean and
     * Western punctuation.  If no sentence boundary is found this returns -1.
     */
    private static int indexOfSentenceEnd(String text) {
        if (text == null || text.isEmpty()) return -1;
        int idx = text.indexOf('.');
        if (idx < 0 || (text.indexOf('!') >= 0 && text.indexOf('!') < idx)) idx = text.indexOf('!');
        if (idx < 0 || (text.indexOf('?') >= 0 && text.indexOf('?') < idx)) idx = text.indexOf('?');
        // Korean full stop '다.' could be used but we treat generic punctuation
        return idx;
    }


    /*───────────────────────── Sanitize helpers ─────────────────────────*/
    private static final Pattern FENCED_BLOCK =
            Pattern.compile("(?s)\\s*```\\s*(?:jsonc?|json5)?\\s*\\n?(.*?)\\s*```\\s*");

    /** 마크다운 펜스/잡음 제거, 첫 JSON 덩어리만 남김 */
    private static String sanitizeForJsonParsing(String s) {
        if (s == null) return "";
        String v = s.strip();
        // BOM/제로폭/불가시 문자 제거
        v = v.replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\u00A0", " ");
        v = v.replace("\r\n", "\n");

        Matcher m = FENCED_BLOCK.matcher(v);
        if (m.find()) {
            v = m.group(1);
        } else {
            // 남아있을 수 있는 역백틱 라인 제거
            v = v.replaceAll("(?s)^```\\s*(?:jsonc?|json5|json)?\\s*\\n?", "")
                    .replaceAll("(?s)\\n?```\\s*$", "");
        }

        // 선행/후행의 비JSON 텍스트 제거: 첫 { 또는 [ 부터 마지막 } 또는 ] 까지만 보존
        int startObj = v.indexOf('{');
        int startArr = v.indexOf('[');
        int start = (startObj >= 0 && startArr >= 0) ? Math.min(startObj, startArr)
                : Math.max(startObj, startArr);
        int endObj = v.lastIndexOf('}');
        int endArr = v.lastIndexOf(']');
        int end = Math.max(endObj, endArr);
        if (start >= 0 && end >= start) {
            v = v.substring(start, end + 1);
        }
        return v.strip();
    }

    /** JSON 문자열 리터럴 내부의 생개행(\r, \n)을 \\n 으로 치환 */
    private static String escapeBareNewlinesInsideStrings(String s) {
        StringBuilder out = new StringBuilder(s.length() + 32);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                out.append(c);
                escaped = true;
                continue;
            }
            if (c == '\"') {
                out.append(c);
                inString = !inString;
                continue;
            }
            if (inString && (c == '\n' || c == '\r')) {
                out.append('\\').append('n');
                // CRLF 처리: \r 다음 \n 하나 건너뛴다
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}