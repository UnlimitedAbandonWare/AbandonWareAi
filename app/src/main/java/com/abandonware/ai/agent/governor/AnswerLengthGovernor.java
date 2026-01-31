package com.abandonware.ai.agent.governor;

import org.springframework.stereotype.Component;

@Component
public class AnswerLengthGovernor {
    public String clamp(String text, int maxChars) {
        if (text == null) return null;
        if (maxChars <= 0) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }
}
