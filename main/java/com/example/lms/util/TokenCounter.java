// 경로: com/example/lms/util/TokenCounter.java
package com.example.lms.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;



@Component
public class TokenCounter {
    private static final System.Logger LOG = System.getLogger(TokenCounter.class.getName());

    private static final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    private static final Encoding cl100k = registry.getEncoding(EncodingType.CL100K_BASE);

    /**
     * Counts every text message using CL100K plus estimated framing. This is an
     * estimate for the selected model, not its tokenizer or provider usage.
     * Multimodal/tool-call payloads are outside this text-only accounting.
     */
    public static long estimateTextChatInput(java.util.List<dev.langchain4j.data.message.ChatMessage> messages) {
        long total = 16;
        for (var message : messages) {
            total += 8;
            if (message instanceof dev.langchain4j.data.message.SystemMessage system) {
                total += cl100k.countTokens(system.text());
            } else if (message instanceof dev.langchain4j.data.message.UserMessage user) {
                for (var content : user.contents()) {
                    if (!(content instanceof dev.langchain4j.data.message.TextContent text)) return -1;
                    total += cl100k.countTokens(text.text());
                }
            } else if (message instanceof dev.langchain4j.data.message.AiMessage answer) {
                if (answer.hasToolExecutionRequests()) return -1;
                total += cl100k.countTokens(answer.text() == null ? "" : answer.text());
            } else {
                return -1;
            }
        }
        return total;
    }

    /**
     * Flag indicating whether a local LLM is active.  When {@code true} token
     * counting falls back to a heuristic based on character count because the
     * CL100K encoding is specific to the OpenAI tokeniser.  When {@code false}
     * tokens are counted using the CL100K vocabulary via JTokkit.
     */
    @org.springframework.beans.factory.annotation.Value("${local-llm.enabled:false}")
    private boolean local;

    /**
     * Attempt to extract the total token count from an OpenAI style usage
     * object.  When the supplied JSON node contains both {@code prompt_tokens}
     * and {@code completion_tokens} fields their sum is returned.  If either
     * field is missing or the node is {@code null}, {@code -1} is returned.
     *
     * @param usage a JSON object containing usage statistics, may be null
     * @return the sum of prompt and completion tokens or -1 when unavailable
     */
    public int countForOpenAiCompatUsage(com.fasterxml.jackson.databind.JsonNode usage) {
        if (usage != null && usage.has("prompt_tokens") && usage.has("completion_tokens")) {
            try {
                return usage.path("prompt_tokens").asInt() + usage.path("completion_tokens").asInt();
            } catch (Exception ignore) {
                LOG.log(System.Logger.Level.DEBUG, "OpenAI compat usage token parse skipped");
                return -1;
            }
        }
        return -1;
    }

    /**
     * Heuristically estimate the number of tokens for a local model.  Many
     * non-OpenAI models use a token length of roughly 4 characters on
     * average.  This method divides the text length by four and rounds up to
     * provide a simple approximation.
     *
     * @param text the input string, may be null
     * @return an approximate token count
     */
    public int approxForLocal(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }

    /**
     * Count the number of tokens in the given text.  When a local LLM is
     * enabled the count falls back to a heuristic approximation; otherwise
     * tokens are counted using the CL100K encoding.  A {@code null} input
     * returns zero.
     *
     * @param text the input text, may be null
     * @return the number of tokens estimated or counted
     */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (local) {
            return approxForLocal(text);
        }
        return cl100k.countTokens(text);
    }
}
