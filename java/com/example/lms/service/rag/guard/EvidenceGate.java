// src/main/java/com/example/lms/service/rag/guard/EvidenceGate.java
package com.example.lms.service.rag.guard;

import dev.langchain4j.rag.content.Content;

import java.util.List;
import java.util.Objects;

public class EvidenceGate {

    /**
     * 주어진 컨텍스트(web/rag/external)에 대해 '최소 증거 문서 수'를 만족하는지 체크
     */
    public boolean hasSufficientCoverage(String query,
                                         List<String> ragSnippets,
                                         String externalContext,
                                         int minEvidence) {
        int hits = 0;
        if (ragSnippets != null) {
            for (String r : ragSnippets) {
                if (containsAnyToken(query, r)) hits++;
            }
        }
        if (externalContext != null && containsAnyToken(query, externalContext)) {
            hits++;
        }
        return hits >= Math.max(1, minEvidence);
    }

    private boolean containsAnyToken(String q, String text) {
        if (q == null || text == null) return false;
        String[] toks = q.toLowerCase().split("\\s+");
        String base = text.toLowerCase();
        for (String t : toks) {
            if (t.length() >= 2 && base.contains(t)) return true;
        }
        return false;
    }
}
