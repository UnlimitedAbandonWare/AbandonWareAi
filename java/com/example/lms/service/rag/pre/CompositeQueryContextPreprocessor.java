package com.example.lms.service.rag.pre;

import com.example.lms.config.rag.RagCognitiveProperties;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * - 전처리기 단계에서 예외가 발생해도 fail-soft 로 동작하여, 전체 검색 흐름을 깨지 않도록 합니다.
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
                // Defensive: avoid self-recursion if Spring injects this composite into the list (proxy-safe).
                .filter(d -> !(d instanceof CompositeQueryContextPreprocessor))
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
        this.cognitiveProps = cognitiveProps;
    }

    @Override
    public String enrich(String q) {
        return enrich(q, new HashMap<>());
    }

    @Override
    public String enrich(String q, Map<String, Object> meta) {
        String enriched = q;
        Map<String, Object> m = (meta != null) ? meta : new HashMap<>();
        for (QueryContextPreprocessor d : delegates) {
            // Defensive: avoid self-recursion if Spring injects this bean into the list.
            if (d == this) {
                continue;
            }
            // rag.cognitive.enabled=false 이면 Guardrail 단계만 스킵
            if (!cognitiveProps.isEnabled() && d instanceof GuardrailQueryPreprocessor) {
                continue;
            }
            try {
                if (d instanceof MetaAwareQueryContextPreprocessor mp) {
                    enriched = mp.enrich(enriched, m);
                } else {
                    enriched = d.enrich(enriched);
                }
            } catch (Exception e) {
                if (d instanceof GuardrailQueryPreprocessor) {
                    // Guardrail 단계에서만 fail-soft: 경고 로그만 남기고 원본 쿼리로 진행
                    log.warn("[Guardrail] preprocessor failed, skipping for this request. cause={}",
                            e.toString());
                    continue;
                }
                // fail-soft: a single preprocessor should not break the whole RAG flow
                log.warn("[QueryPreprocessor] delegate failed (fail-soft). preprocessor={} err={}", d.getClass().getSimpleName(), e.toString());
                continue;
            }
        }
        return enriched;
    }

    @Override
    public String detectDomain(String q) {
        String first = null;
        for (QueryContextPreprocessor d : delegates) {
            if (d == this) continue;
            if (!cognitiveProps.isEnabled() && d instanceof GuardrailQueryPreprocessor) continue;
            try {
                String dom = d.detectDomain(q);
                if (dom == null || dom.isBlank()) continue;
                if (first == null) first = dom;
                if (!"GENERAL".equalsIgnoreCase(dom)) return dom;
            } catch (Exception ignore) {
                // fail-soft
            }
        }
        return first != null ? first : QueryContextPreprocessor.super.detectDomain(q);
    }

    @Override
    public String inferIntent(String q) {
        String first = null;
        for (QueryContextPreprocessor d : delegates) {
            if (d == this) continue;
            if (!cognitiveProps.isEnabled() && d instanceof GuardrailQueryPreprocessor) continue;
            try {
                String intent = d.inferIntent(q);
                if (intent == null || intent.isBlank()) continue;
                if (first == null) first = intent;
                if (!"GENERAL".equalsIgnoreCase(intent)) return intent;
            } catch (Exception ignore) {
                // fail-soft
            }
        }
        return first != null ? first : QueryContextPreprocessor.super.inferIntent(q);
    }

    @Override
    public Map<String, Set<String>> getInteractionRules(String q) {
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        for (QueryContextPreprocessor d : delegates) {
            if (d == this) continue;
            if (!cognitiveProps.isEnabled() && d instanceof GuardrailQueryPreprocessor) continue;
            try {
                Map<String, Set<String>> rules = d.getInteractionRules(q);
                if (rules == null || rules.isEmpty()) continue;
                for (Map.Entry<String, Set<String>> e : rules.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) continue;
                    Set<String> vals = e.getValue();
                    if (vals == null || vals.isEmpty()) continue;
                    merged.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(vals);
                }
            } catch (Exception ignore) {
                // fail-soft
            }
        }

        if (merged.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        merged.forEach((k, v) -> out.put(k, Set.copyOf(v)));
        return out;
    }
}
