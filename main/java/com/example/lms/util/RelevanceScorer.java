package com.example.lms.util;

import dev.langchain4j.model.embedding.EmbeddingModel;

//검색

/**
 * Cosine-similarity 기반 간단 유사도 스코어러.
 * 필요한 건 query·doc 두 문장을 받아 0~1 사이 double 점수를 돌려주는 score(/* ... *&#47;) 하나뿐입니다.
 */
public class RelevanceScorer {


    private static final System.Logger LOG = System.getLogger(RelevanceScorer.class.getName());

    private final EmbeddingModel embeddingModel;

    public RelevanceScorer(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public double score(String a, String b) {
        if (a == null || b == null) return 0.0;          // guard against null inputs
        if (a.isBlank() || b.isBlank()) return 0.0;      // empty strings have no relevance
        float[] va, vb;
        try {
            va = embeddingModel.embed(a).content().vector();
            vb = embeddingModel.embed(b).content().vector();
        } catch (Exception ex) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Relevance scoring embedding failed aLength={0} bLength={1}",
                    a.length(), b.length());
            return 0.0;
        }
        if (va == null || vb == null || va.length != vb.length) return 0.0;
        int n = va.length;

        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            if (!Float.isFinite(va[i]) || !Float.isFinite(vb[i])) return 0.0;
            double left = va[i], right = vb[i];
            dot += left * right;
            na  += left * left;
            nb  += right * right;
        }
        if (na == 0 || nb == 0) return 0.0;
        double cosine = dot / (Math.sqrt(na) * Math.sqrt(nb));
        return Double.isFinite(cosine) ? Math.max(0.0, Math.min(1.0, cosine)) : 0.0;
    }
    /**
     * BM25(텍스트 빈도)와 코사인 임베딩 유사도를 모두 반영한 정규화 스코어.
     *
     * @param query   사용자 쿼리
     * @param doc     후보 문서/스니펫
     * @param bm25Raw 검색엔진에서 얻은 원본 BM25 점수
     * @return 0.0 ≤ s ≤ 1.0
     */

    public double score(String query, String doc, double bm25Raw) {
        double cos  = score(query, doc);                         // 0-1 범위
        double bm25 = 1.0 - Math.exp(-Math.max(0.0, bm25Raw));   // 0-1 범위로 압축
        // 경험적 가중치: cosine 0.6 +   bm25 0.4
        return (0.6 * cos) + (0.4 * bm25);
    }
}
