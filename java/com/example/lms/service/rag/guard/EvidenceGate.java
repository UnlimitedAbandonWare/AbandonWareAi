// src/main/java/com/example/lms/service/rag/guard/EvidenceGate.java
package com.example.lms.service.rag.guard;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EvidenceGate {

    private final int minEvidence;
    private final int minEvidenceFollowUp;

    public EvidenceGate(
            @Value("${verifier.evidence.min-count:2}") int minEvidence,
            @Value("${verifier.evidence.min-count.followup:1}") int minEvidenceFollowUp) {
        this.minEvidence = minEvidence;
        this.minEvidenceFollowUp = minEvidenceFollowUp;
    }

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

    /** ✅ 메모리/KB도 포함하고, 후속질의 시 임계 완화 지원 */
    public boolean hasSufficientCoverage(String query,
                                         List<String> ragSnippets,
                                         String externalContext,
                                         List<String> memorySnippets,
                                         List<String> kbSnippets,
                                         boolean followUp) {
        int need = followUp ? Math.max(1, minEvidenceFollowUp) : Math.max(1, minEvidence);
        int hits = 0;
        if (ragSnippets != null) for (String r : ragSnippets) if (containsAnyToken(query, r)) hits++;
        if (externalContext != null && containsAnyToken(query, externalContext)) hits++;
        if (memorySnippets != null) for (String m : memorySnippets) if (containsAnyToken(query, m)) hits++;
        if (kbSnippets != null) for (String k : kbSnippets) if (containsAnyToken(query, k)) hits++;
        return hits >= need;
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
