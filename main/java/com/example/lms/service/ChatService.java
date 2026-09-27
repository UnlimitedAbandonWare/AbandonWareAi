package com.example.lms.service;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Thin orchestration facade.
 * <p>
 * The heavy pipeline lives in {@link ChatWorkflow}. This class exists to keep
 * the
 * public surface stable for controllers and to provide a compact, compile-time
 * visible
 * API (incl. {@link ChatResult}) while avoiding a God-class.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatWorkflow workflow;

    public ChatResult continueChat(ChatRequestDto req) {
        return workflow.continueChat(req);
    }

    public ChatResult continueChat(ChatRequestDto req, Function<String, List<String>> externalCtxProvider) {
        return workflow.continueChat(req, externalCtxProvider);
    }

    /** Internal owner-checked context path deliberately bypasses the response cache. */
    public ChatResult continueChat(ChatRequestDto req, Function<String,List<String>> externalCtxProvider,ChatConversationContext context) {
        return workflow.continueChat(req,externalCtxProvider,context);
    }

    public ChatResult ask(String userMsg) {
        return workflow.ask(userMsg);
    }

    /** Called by /api/chat/cancel */
    public void cancelSession(Long sessionId) {
        workflow.cancelSession(sessionId);
    }

    /**
     * Build a composite cache key from a chat request.
     * <p>
     * Kept here because the cache SpEL expression references this class.
     */
    public static String cacheKey(ChatRequestDto req) {
        if (req == null)
            return "";

        MessageDigest digest = sha256();
        appendFingerprint(digest, "message", req.getMessage());
        appendFingerprint(digest, "systemPrompt", req.getSystemPrompt());
        appendFingerprint(digest, "traits", req.getTraits());
        appendFingerprint(digest, "history", req.getHistory());
        appendFingerprint(digest, "mode", req.getMode());
        appendFingerprint(digest, "memoryMode", req.getMemoryMode());
        appendFingerprint(digest, "model", req.getModel());
        appendFingerprint(digest, "temperature", req.getTemperature());
        appendFingerprint(digest, "topP", req.getTopP());
        appendFingerprint(digest, "frequencyPenalty", req.getFrequencyPenalty());
        appendFingerprint(digest, "presencePenalty", req.getPresencePenalty());
        appendFingerprint(digest, "maxTokens", req.getMaxTokens());
        appendFingerprint(digest, "sessionId", req.getSessionId());
        appendFingerprint(digest, "useRag", req.getUseRag());
        appendFingerprint(digest, "useWebSearch", req.getUseWebSearch());
        appendFingerprint(digest, "useVerification", req.getUseVerification());
        appendFingerprint(digest, "ragStandalone", req.getRagStandalone());
        appendFingerprint(digest, "useAdaptive", req.isUseAdaptive());
        appendFingerprint(digest, "autoTranslate", req.isAutoTranslate());
        appendFingerprint(digest, "polish", req.getPolish());
        appendFingerprint(digest, "understandingEnabled", req.isUnderstandingEnabled());
        appendFingerprint(digest, "inputType", req.getInputType());
        appendFingerprint(digest, "maxMemoryTokens", req.getMaxMemoryTokens());
        appendFingerprint(digest, "maxRagTokens", req.getMaxRagTokens());
        appendFingerprint(digest, "searchMode", req.getSearchMode());
        appendFingerprint(digest, "webProviders", req.getWebProviders());
        appendFingerprint(digest, "webTopK", req.getWebTopK());
        appendFingerprint(digest, "searchQueries", req.getSearchQueries());
        appendFingerprint(digest, "accumulation", req.getAccumulation());
        appendFingerprint(digest, "roleScope", req.getRoleScope());
        appendFingerprint(digest, "domainProfile", req.getDomainProfile());
        appendFingerprint(digest, "officialSourcesOnly", req.getOfficialSourcesOnly());
        appendFingerprint(digest, "searchScopes", req.getSearchScopes());
        appendFingerprint(digest, "precisionSearch", req.getPrecisionSearch());
        appendFingerprint(digest, "precisionTopK", req.getPrecisionTopK());
        appendFingerprint(digest, "imageBase64", req.getImageBase64());
        appendFingerprint(digest, "attachmentIds", req.getAttachmentIds());
        appendFingerprint(digest, "webSearchExplicit", req.getWebSearchExplicit());
        ChatRequestDto.RetrievalRequestIntent intent = req.getRetrievalRequestIntent();
        appendFingerprint(digest, "retrievalIntent.webSearch", intent == null ? null : intent.webSearch());
        appendFingerprint(digest, "retrievalIntent.rag", intent == null ? null : intent.rag());
        appendFingerprint(digest, "profile", req.getProfile());
        appendFingerprint(digest, "guardLevel", req.getGuardLevel());
        appendFingerprint(digest, "memoryProfile", req.getMemoryProfile());

        String messageHash = SafeRedactor.hash12(req.getMessage());
        return "chat:v2:" + (messageHash == null ? "none" : messageHash)
                + ":" + HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Only stateless requests with explicit execution choices may reuse a response.
     */
    public static boolean isCacheSafe(ChatRequestDto req) {
        if (req == null
                || req.getSessionId() != null
                || req.getModel() == null
                || req.getModel().isBlank()
                || req.getMode() == null
                || req.getMode().isBlank()
                || !"ephemeral".equalsIgnoreCase(String.valueOf(req.getMemoryMode()).trim())
                || req.getTemperature() == null
                || req.getTopP() == null
                || req.getFrequencyPenalty() == null
                || req.getPresencePenalty() == null
                || req.getMaxTokens() == null
                || !Boolean.FALSE.equals(req.getUseRag())
                || !Boolean.FALSE.equals(req.getUseWebSearch())
                || !Boolean.FALSE.equals(req.getUseVerification())
                || req.isUnderstandingEnabled()
                || (req.getHistory() != null && !req.getHistory().isEmpty())
                || (req.getAttachmentIds() != null && !req.getAttachmentIds().isEmpty())
                || (req.getImageBase64() != null && !req.getImageBase64().isBlank())) {
            return false;
        }

        ChatRequestDto.RetrievalRequestIntent intent = req.getRetrievalRequestIntent();
        return intent == null
                || (Boolean.FALSE.equals(intent.webSearch()) && Boolean.FALSE.equals(intent.rag()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void appendFingerprint(MessageDigest digest, String name, Object value) {
        appendToken(digest, name);
        if (value == null) {
            appendToken(digest, "<null>");
            return;
        }
        if (value instanceof Iterable<?> values) {
            appendToken(digest, "<list>");
            int index = 0;
            for (Object item : values) {
                appendFingerprint(digest, Integer.toString(index++), item);
            }
            appendToken(digest, "</list>");
            return;
        }
        if (value instanceof ChatRequestDto.Message message) {
            appendToken(digest, "<message>");
            appendFingerprint(digest, "role", message.getRole());
            appendFingerprint(digest, "content", message.getContent());
            appendToken(digest, "</message>");
            return;
        }
        appendToken(digest, value instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(value));
    }

    private static void appendToken(MessageDigest digest, String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        digest.update((byte) (length >>> 24));
        digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8));
        digest.update((byte) length);
        digest.update(bytes);
    }
}
