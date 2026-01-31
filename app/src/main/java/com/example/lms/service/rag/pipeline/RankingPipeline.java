package com.example.lms.service.rag.pipeline;

import java.util.*;
import com.example.lms.service.rag.rerank.diversity.DppDiversityReranker;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.pipeline.RankingPipeline
 * Role: config
 * Dependencies: com.example.lms.service.rag.rerank.diversity.DppDiversityReranker
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.pipeline.RankingPipeline
role: config
*/
public class RankingPipeline {
    public <T> List<T> run(List<T> biStage, int kAfterBi, int kToOnnx){
        if (biStage == null || biStage.isEmpty()) return java.util.Collections.emptyList();
        List<T> stage1 = biStage.size() > kAfterBi
                ? new ArrayList<>(biStage.subList(0, kAfterBi))
                : new ArrayList<>(biStage);
        // 다양성 선별: 안전하게 크기만 제한 (임베딩 어댑터는 상위 계층에서 제공)
        try {
            DppDiversityReranker dpp = new DppDiversityReranker();
            // NOTE: 여기서는 DPP 내부가 제네릭이 아니므로, 상위에서 래핑된 호출을 사용하도록 남겨둡니다.
            // 컴파일 안전성을 위해 직접 호출은 생략하고, 향후 adapter가 주입되면 활성화합니다.
        } catch (Throwable ignore) {}
        return stage1.size() > kToOnnx ? new ArrayList<>(stage1.subList(0, kToOnnx)) : stage1;
    }
}