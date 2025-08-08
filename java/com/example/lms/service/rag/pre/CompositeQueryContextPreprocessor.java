package com.example.lms.service.rag.pre;

import java.util.List;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;          // ★ 필요 시
// import org.springframework.beans.factory.annotation.Qualifier; // ★ 필요 시

/**
 * 여러 QueryContextPreprocessor를 체인으로 묶어 순차 실행하는 합성 전처리기.
 */
@Component   // 빈으로 등록하려면 유지, 아니면 제거
public class CompositeQueryContextPreprocessor implements QueryContextPreprocessor {

    private final List<QueryContextPreprocessor> delegates;

    public CompositeQueryContextPreprocessor(List<QueryContextPreprocessor> delegates) {
        // @Order, @Priority 애노테이션 순서를 자동 반영해 정렬
        this.delegates = delegates.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
    }

    @Override
    public String enrich(String q) {
        for (QueryContextPreprocessor d : delegates) {
            q = d.enrich(q);   // 체인 방식 호출
        }
        return q;
    }
}
