package com.example.lms.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.Locale;

/**
 * Small, dependency-free helpers to keep "model ↔ endpoint" compatibility
 * failures from becoming silent/no-output failures.
 */
public final class OpenAiEndpointCompatibility {

    private OpenAiEndpointCompatibility() {
    }

    /**
     * Detect the classic OpenAI error when a non-chat model is invoked via
     * /v1/chat/completions.
     */
    public static boolean isChatEndpointMismatchMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        // Typical OpenAI payload:
        // "This is not a chat model ... v1/chat/completions ... Did you mean v1/completions?"
        if (m.contains("not a chat model") && m.contains("chat/completions")) {
            return true;
        }
        if (m.contains("does not support chat completions")) {
            return true;
        }
        if (m.contains("v1/chat/completions") && m.contains("v1/completions")) {
            return true;
        }
        return false;
    }

    /**
     * Detect OpenAI-style hints to use /v1/responses (Responses API) instead of chat/completions.
     *
     * <p>Heuristic: looks for "v1/responses" or "Responses API" hints in the error message.</p>
     */
    public static boolean isResponsesEndpointSuggestionMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("/v1/responses") || m.contains("v1/responses")) {
            return true;
        }
        if (m.contains("responses api") && (m.contains("use") || m.contains("did you mean") || m.contains("instead"))) {
            return true;
        }
        return false;
    }

    /**
     * Detect the classic OpenAI error when a chat model is invoked via /v1/completions.
     */
    public static boolean isCompletionsEndpointMismatchMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("v1/completions") && m.contains("not supported")
                && (m.contains("chat model") || m.contains("not a completion model") || m.contains("not a completions model"))) {
            return true;
        }
        if (m.contains("is a chat model") && m.contains("v1/completions")) {
            return true;
        }
        return false;
    }

    /**
     * Detect the case where the server/gateway does not implement /v1/chat/completions at all.
     */
    public static boolean isChatCompletionsEndpointMissingMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        if (!(m.contains("chat/completions") || m.contains("/v1/chat/completions"))) {
            return false;
        }
        // 404 / Not Found / unknown endpoint patterns.
        if (m.contains("404") && (m.contains("not found") || m.contains("unknown"))) {
            return true;
        }
        if (m.contains("not found") && (m.contains("chat/completions") || m.contains("/v1/chat/completions"))) {
            return true;
        }
        if (m.contains("unknown endpoint") || m.contains("unsupported endpoint")) {
            return true;
        }
        return false;
    }

    /**
     * Best-effort heuristic: model IDs that are very likely legacy /v1/completions.
     *
     * <p>Used only for save-time guard / pre-call fast-path. Never rely on this
     * for correctness.</p>
     */
    public static boolean isLikelyCompletionsOnlyModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String m = modelId.trim().toLowerCase(Locale.ROOT);

        // Local/open-source style IDs (ollama/vLLM) are usually chat-compatible.
        if (m.contains(":")) {
            return false;
        }

        // Non-chat categories (handled elsewhere, but keep safe here too)
        if (m.contains("embedding") || m.contains("whisper") || m.contains("tts")
                || m.contains("moderation") || m.contains("image") || m.contains("vision")) {
            return false;
        }

        // Legacy completions families.
        return m.startsWith("text-")
                || m.contains("-instruct")
                || m.startsWith("davinci")
                || m.startsWith("curie")
                || m.startsWith("babbage")
                || m.startsWith("ada");
    }

    public static String summarizeForLog(String message, int maxLen) {
        if (message == null) {
            return "";
        }
        String s = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (maxLen <= 0) {
            return s;
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + " …(truncated)";
    }

    /**
     * Convert chat messages into a single prompt string suitable for /v1/completions.
     */
    public static String toCompletionsPrompt(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(1024);
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                String t = safeText(sm.text());
                if (!t.isBlank()) {
                    sb.append("[System]\n").append(t).append("\n\n");
                }
            } else if (msg instanceof UserMessage um) {
                String t = safeText(um.singleText());
                if (!t.isBlank()) {
                    sb.append("User: ").append(t).append("\n\n");
                }
            } else if (msg instanceof AiMessage am) {
                String t = safeText(am.text());
                if (!t.isBlank()) {
                    sb.append("Assistant: ").append(t).append("\n\n");
                }
            }
        }
        sb.append("Assistant:");
        return sb.toString();
    }

    private static String safeText(String t) {
        return t == null ? "" : t.trim();
    }

    public static String userFacingEndpointMismatch(String modelId) {
        String m = (modelId == null ? "(unknown)" : modelId);
        return "⚠️ LLM 설정 오류: 모델 '" + m + "' 호출 엔드포인트가 호환되지 않습니다. "
                + "(/v1/chat/completions ↔ /v1/responses ↔ /v1/completions)\n"
                + "- 해결: 모델/게이트웨이가 지원하는 엔드포인트로 라우팅하거나, 호환 가능한 모델로 변경하세요.";
    }

    public static String userFacingModelNotFound(String modelId) {
        String m = (modelId == null ? "(unknown)" : modelId);
        return "⚠️ LLM 설정 오류: 모델 '" + m + "' 을(를) 찾을 수 없습니다(권한/조직/엔드포인트/모델 ID 확인 필요).";
    }
}
