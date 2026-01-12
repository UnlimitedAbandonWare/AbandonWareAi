package com.abandonware.ai.agent.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CitationGate {
    private final int min;
    public CitationGate(@Value("${gate.citation.min:3}") int min) {
        this.min = min;
    }
    // naive checker: counts occurrences of 'http' as citation proxy
    public boolean hasMinimumCitations(String answer) {
        if (answer == null) return false;
        int count = 0;
        int idx = 0;
        while ((idx = answer.indexOf("http", idx)) >= 0) { count++; idx += 4; }
        return count >= min;
    }
}
