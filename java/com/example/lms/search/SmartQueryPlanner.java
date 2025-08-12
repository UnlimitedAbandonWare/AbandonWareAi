// src/main/java/com/example/lms/search/SmartQueryPlanner.java
package com.example.lms.search;

import com.example.lms.transform.QueryTransformer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class SmartQueryPlanner {

    private final QueryTransformer transformer;

    // ✅ 명시적 생성자에서 주입 + 어떤 빈을 쓸지 지정
    public SmartQueryPlanner(@Qualifier("defaultQueryTransformer") QueryTransformer transformer) {
        this.transformer = transformer;
    }

    /**
     * 사용자 질문(+선택적 초안)을 바탕으로 검색에 투입할 "핵심 쿼리"를 생성.
     * - 위생(sanitize) + 최대 maxQueries(기본 2)로 제한.
     */
    public List<String> plan(String userPrompt, @Nullable String assistantDraft, int maxQueries) {
        int cap = Math.max(1, Math.min(4, maxQueries));
        List<String> raw = transformer.transformEnhanced(
                Objects.toString(userPrompt, ""),
                assistantDraft
        );
        return QueryHygieneFilter.sanitize(raw, cap, 0.80);
    }

    public List<String> plan(String userPrompt) {
        return plan(userPrompt, null, 2);
    }
}
