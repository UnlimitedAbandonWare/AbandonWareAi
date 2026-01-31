package com.example.lms.service.chat.interceptor;

import com.example.lms.dto.answer.AnswerUnderstanding;
import com.example.lms.service.understanding.AnswerUnderstandingService;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.chat.ChatStreamEmitter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.example.lms.service.ChatHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Interceptor that triggers the understanding/memory summarization pipeline
 * after the assistant's answer has been verified and sanitized.  When the
 * feature toggle is enabled both globally and for the individual request,
 * this interceptor will call the {@link AnswerUnderstandingService} to
 * generate a structured summary, save the distilled text to long-term
 * memory via {@link MemoryReinforcementService} and emit an SSE event
 * through {@link ChatStreamEmitter} so that the front-end can render it.
 */

@Component
@RequiredArgsConstructor
public class UnderstandAndMemorizeInterceptor {
    private static final Logger log = LoggerFactory.getLogger(UnderstandAndMemorizeInterceptor.class);

    private final AnswerUnderstandingService understandingService;
    private final MemoryReinforcementService memoryService;
    private final ChatStreamEmitter chatStreamEmitter;
    private final ChatHistoryService chatHistoryService;

    @Value("${abandonware.understanding.enabled:true}")
    private boolean globalEnabled;

    // Prefix for understanding summary meta persisted as a system message.
    private static final String USUM_META_PREFIX = "⎔USUM⎔";

    /**
     * Invoke the understanding pipeline.  This method is idempotent and
     * will return immediately if any required input is missing or the feature
     * toggles are disabled.  Exceptions thrown from downstream services are
     * caught and logged to avoid disrupting the normal chat flow.
     *
     * @param sessionKey          the normalized session key (e.g. "chat-123")
     * @param question            the user's original query
     * @param finalAnswer         the final verified answer
     * @param requestEnabled      whether the user enabled understanding for this request
     */
    public void afterVerified(String sessionKey,
                              String question,
                              String finalAnswer,
                              boolean requestEnabled) {
        if (!globalEnabled) return;
        if (!requestEnabled) return;
        if (finalAnswer == null || finalAnswer.isBlank()) return;
        try {
            AnswerUnderstanding u = understandingService.understand(finalAnswer, question);
            if (u != null) {
                // Render summary into a single snippet for memory storage
                String text = renderForMemory(u);
                double conf = u.confidence();
                try {
                    memoryService.reinforceWithSnippet(sessionKey, question, text, "UNDERSTANDING", conf);
                } catch (Exception e) {
                    log.warn("[Understand] memory store failed: {}", e.toString());
                }
                try {
                    
// Persist the understanding summary as a system meta message
try {
    Long sessionId = parseNumericSessionId(sessionKey);
    if (sessionId != null) {
        String json = new ObjectMapper().writeValueAsString(u);
        chatHistoryService.appendMessage(sessionId, "system", USUM_META_PREFIX + json);
    }
} catch (Exception ignore) {
    // ignore serialization or persistence errors
}
chatStreamEmitter.emitUnderstanding(sessionKey, u);
                } catch (Exception e) {
                    log.debug("[Understand] SSE emit failed: {}", e.toString());
                }
            }
        } catch (Exception ex) {
            log.warn("[Understand] summarization failed: {}", ex.toString());
        }
    }

    /**
     * Render the TL;DR, key points and action items into a compact snippet
     * separated by newlines.  Only non-blank sections are included.  This
     * representation is stored into the translation memory and used for
     * embedding.
     */
    private static String renderForMemory(AnswerUnderstanding u) {
        StringBuilder sb = new StringBuilder();
        if (u == null) return "";
        if (u.tldr() != null && !u.tldr().isBlank()) {
            sb.append(u.tldr().trim()).append("\n");
        }
        if (u.keyPoints() != null) {
            for (String k : u.keyPoints()) {
                if (k != null && !k.isBlank()) {
                    sb.append("- ").append(k.trim()).append("\n");
                }
            }
        }
        if (u.actionItems() != null) {
            for (String a : u.actionItems()) {
                if (a != null && !a.isBlank()) {
                    sb.append("* ").append(a.trim()).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }
/**
 * Extract the numeric session ID from a normalized session key of the form "chat-<number>".
 * If the key does not follow this pattern or cannot be parsed into a long, null is returned.
 */
private static Long parseNumericSessionId(String key) {
    if (key == null) return null;
    String s = key.trim();
    // Strip the "chat-" prefix if present
    if (s.startsWith("chat-")) {
        s = s.substring(5);
    }
    /*
     * Ensure the session ID is composed only of digits.  In Java
     * string literals a single backslash must be escaped, so the
     * regular expression "\\d+" becomes "\\d+" at runtime, which
     * matches one or more digits.  See JLS §3.3 for details.
     */
    if (s.matches("\\d+")) {
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }
    return null;
}
}