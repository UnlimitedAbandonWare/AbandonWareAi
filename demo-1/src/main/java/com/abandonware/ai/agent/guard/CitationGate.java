package com.abandonware.ai.agent.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = "gate.citation.enabled", havingValue = "true", matchIfMissing = true)
public class CitationGate {
    private final int minCitations;
    private static final Pattern CIT_PATTERN = Pattern.compile("\\[\\d+\\]"); // e.g., [1]
    public CitationGate(@Value("{gate.citation.min:3}") int minCitations) {
        this.minCitations = minCitations;
    }
    public boolean hasEnoughCitations(String answer) {
        if (answer == null) return false;
        Matcher m = CIT_PATTERN.matcher(answer);
        int count = 0;
        while (m.find()) count++;
        return count >= minCitations;
    }
}
