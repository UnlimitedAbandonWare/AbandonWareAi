package com.example.lms.service.understanding;

import com.example.lms.client.GeminiClient;
import com.example.lms.dto.answer.AnswerUnderstanding;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

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
@Slf4j
public class AnswerUnderstandingService {

    private final GeminiClient geminiClient;
    private final AnswerUnderstandingPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            // call Gemini; generate() returns JSON wrapper { ok: true, data: "..." }
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
                // parse wrapper
                var node = objectMapper.readTree(trimmed);
                if (node.has("data")) {
                    json = node.get("data").asText();
                } else {
                    json = trimmed;
                }
            } else {
                json = trimmed;
            }
            // Remove markdown fences if present
            json = json.strip();
            if (json.startsWith("```")) {
                json = json.substring(3);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            AnswerUnderstanding u = objectMapper.readValue(json, AnswerUnderstanding.class);
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
            if (lt.startsWith("-") || lt.startsWith("*") || lt.startsWith("•")) {
                keyPoints.add(lt.replaceFirst("^[-*•]\s*", "").strip());
            }
        }
        if (keyPoints.isEmpty()) {
            // fallback to sentences
            String[] sents = trimmed.split("(?<=[.!?])\s+");
            for (int i = 0; i < sents.length && keyPoints.size() < 3; i++) {
                String sent = sents[i].strip();
                if (!sent.isEmpty()) keyPoints.add(sent);
            }
        }
        // Action items: extremely simple heuristic – none
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
}