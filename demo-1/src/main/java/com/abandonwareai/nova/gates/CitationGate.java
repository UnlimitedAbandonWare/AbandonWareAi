package com.abandonwareai.nova.gates;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Very lightweight placeholder: checks that the answer contains at least N occurrences of "["digit"]".
 * In real system this would validate structured citations.
 */
@Component
public class CitationGate {
    private final int minCitations;

    public CitationGate(@Value("${idle.gates.minCitations:3}") int minCitations) {
        this.minCitations = minCitations;
    }

    public boolean passes(String answer) {
        int count = 0;
        for (int i = 0; i < answer.length(); i++) {
            if (answer.charAt(i) == '[') {
                int j = i + 1;
                while (j < answer.length() && Character.isDigit(answer.charAt(j))) j++;
                if (j < answer.length() && answer.charAt(j) == ']') {
                    count++;
                }
            }
        }
        return count >= minCitations;
    }
}
