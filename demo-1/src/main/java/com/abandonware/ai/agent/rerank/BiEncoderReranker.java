package com.abandonware.ai.agent.rerank;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Comparator;

@Component
public class BiEncoderReranker {
    public static class DocScore {
        public final String docId; public final double score;
        public DocScore(String docId, double score) {this.docId=docId; this.score=score;}
    }
    public List<DocScore> rerank(List<DocScore> candidates) {
        // placeholder: already scored; ensure descending order
        candidates.sort(Comparator.comparingDouble((DocScore ds) -> ds.score).reversed());
        return candidates;
    }
}
