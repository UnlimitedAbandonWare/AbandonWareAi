package com.abandonware.ai.agent.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gate.answerLength.enabled", havingValue = "true", matchIfMissing = true)
public class AnswerLengthGovernor {
    private final int maxTokens;
    public AnswerLengthGovernor(@Value("{gate.answerLength.max-tokens:256}") int maxTokens) {
        this.maxTokens = maxTokens;
    }
    public String enforce(String text) {
        if (text == null) return null;
        // naive char-based trim as a placeholder (tokenization is model-specific)
        int maxChars = Math.max(64, this.maxTokens * 3);
        return text.length() > maxChars ? text.substring(0, maxChars) + "..." : text;
    }
}
