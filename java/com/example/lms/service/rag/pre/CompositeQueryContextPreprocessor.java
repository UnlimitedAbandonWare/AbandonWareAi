package com.example.lms.service.rag.pre;

import com.example.lms.config.rag.RagCognitiveProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * 여러 QueryContextPreprocessor를 체인으로 묶어 순차 실행하는 합성 전처리기.
 * <p>
 * - 등록된 전처리기들을 @Order / @Priority 순서에 따라 정렬한 뒤 차례로 적용합니다.
 * - rag.cognitive.enabled=false 인 경우 GuardrailQueryPreprocessor 만 건너뛰고 나머지는 그대로 실행합니다.
 * - Guardrail 단계에서 예외가 발생하면 fail-soft 로 동작하여, 나머지 전처리 체인은 유지합니다.
 */
@Component
@Primary
public class CompositeQueryContextPreprocessor implements QueryContextPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(CompositeQueryContextPreprocessor.class);

    private final List<QueryContextPreprocessor> delegates;
    private final RagCognitiveProperties cognitiveProps;

    public CompositeQueryContextPreprocessor(
            List<QueryContextPreprocessor> delegates,
            RagCognitiveProperties cognitiveProps
    ) {
        // @Order, @Priority 애노테이션 순서를 자동 반영해 정렬
        this.delegates = delegates.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
        this.cognitiveProps = cognitiveProps;
    }

    @Override
    public String enrich(String q) {
        String enriched = q;
        for (QueryContextPreprocessor d : delegates) {
            // rag.cognitive.enabled=false 이면 Guardrail 단계만 스킵
            if (!cognitiveProps.isEnabled() && d instanceof GuardrailQueryPreprocessor) {
                continue;
            }
            try {
                enriched = d.enrich(enriched);
            } catch (Exception e) {
                if (d instanceof GuardrailQueryPreprocessor) {
                    // Guardrail 단계에서만 fail-soft: 경고 로그만 남기고 원본 쿼리로 진행
                    log.warn("[Guardrail] preprocessor failed, skipping for this request. cause={}",
                            e.toString());
                    continue;
                }
                // 그 외 전처리기는 기존 동작을 유지하기 위해 예외 전파
                throw e;
            }
        }
        return enriched;
    }
}
