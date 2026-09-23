package com.example.lms.service.rag.pre;

import com.example.lms.config.rag.RagCognitiveProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
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
            if (guardrailDisabled(d)) {
                traceGuardrailSkipped("enrich", d);
                continue;
            }
            try {
                String next;
                if (d instanceof MetaAwareQueryContextPreprocessor mp) {
                    next = mp.enrich(enriched, m);
                } else {
                    next = d.enrich(enriched);
                }
                if (next == null || next.isBlank()) {
                    traceNullPreprocessorOutput("enrich", d, enriched, next == null ? "null_output" : "blank_output");
                    continue;
                }
                enriched = next;
            } catch (Exception e) {
                tracePreprocessorFailure("enrich", d, enriched, e);
                if (d instanceof GuardrailQueryPreprocessor) {
                    // Guardrail 단계에서만 fail-soft: 경고 로그만 남기고 원본 쿼리로 진행
                    log.warn("[AWX][rag][preprocessor] guardrail failed failureReason={} errorType={} preprocessor={} queryHash12={} queryLength={}",
                            "guardrail-preprocessor-error",
                            SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                            SafeRedactor.traceLabelOrFallback(d.getClass().getSimpleName(), "unknown"),
                            SafeRedactor.hash12(enriched),
                            enriched == null ? 0 : enriched.length());
                    continue;
                }
                // fail-soft: a single preprocessor should not break the whole RAG flow
                log.warn("[AWX][rag][preprocessor] delegate failed failureReason={} errorType={} preprocessor={} queryHash12={} queryLength={}",
                        "query-preprocessor-error",
                        SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                        SafeRedactor.traceLabelOrFallback(d.getClass().getSimpleName(), "unknown"),
                        SafeRedactor.hash12(enriched),
                        enriched == null ? 0 : enriched.length());
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
            if (guardrailDisabled(d)) {
                traceGuardrailSkipped("detect_domain", d);
                continue;
            }
            try {
                String dom = d.detectDomain(q);
                if (dom == null || dom.isBlank()) continue;
                if (first == null) first = dom;
                if (!"GENERAL".equalsIgnoreCase(dom)) return dom;
            } catch (Exception domainEx) {
                String errorType = SafeRedactor.traceLabelOrFallback(domainEx.getClass().getSimpleName(), "unknown");
                TraceStore.put("query.preprocessor.suppressed.stage", "detectDomain");
                TraceStore.put("query.preprocessor.suppressed.errorType", errorType);
                TraceStore.put("query.preprocessor.suppressed.detectDomain", true);
                TraceStore.put("query.preprocessor.suppressed.detectDomain.errorType", errorType);
                tracePreprocessorFailure("detect_domain", d, q, domainEx);
            }
        }
        return first != null ? first : QueryContextPreprocessor.super.detectDomain(q);
    }

    @Override
    public String inferIntent(String q) {
        String first = null;
        for (QueryContextPreprocessor d : delegates) {
            if (d == this) continue;
            if (guardrailDisabled(d)) {
                traceGuardrailSkipped("infer_intent", d);
                continue;
            }
            try {
                String intent = d.inferIntent(q);
                if (intent == null || intent.isBlank()) continue;
                if (first == null) first = intent;
                if (!"GENERAL".equalsIgnoreCase(intent)) return intent;
            } catch (Exception intentEx) {
                String errorType = SafeRedactor.traceLabelOrFallback(intentEx.getClass().getSimpleName(), "unknown");
                TraceStore.put("query.preprocessor.suppressed.stage", "inferIntent");
                TraceStore.put("query.preprocessor.suppressed.errorType", errorType);
                TraceStore.put("query.preprocessor.suppressed.inferIntent", true);
                TraceStore.put("query.preprocessor.suppressed.inferIntent.errorType", errorType);
                tracePreprocessorFailure("infer_intent", d, q, intentEx);
            }
        }
        return first != null ? first : QueryContextPreprocessor.super.inferIntent(q);
    }

    @Override
    public Map<String, Set<String>> getInteractionRules(String q) {
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        for (QueryContextPreprocessor d : delegates) {
            if (d == this) continue;
            if (guardrailDisabled(d)) {
                traceGuardrailSkipped("interaction_rules", d);
                continue;
            }
            try {
                Map<String, Set<String>> rules = d.getInteractionRules(q);
                if (rules == null || rules.isEmpty()) continue;
                for (Map.Entry<String, Set<String>> e : rules.entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) continue;
                    Set<String> vals = e.getValue();
                    if (vals == null || vals.isEmpty()) continue;
                    merged.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(vals);
                }
            } catch (Exception rulesEx) {
                String errorType = SafeRedactor.traceLabelOrFallback(rulesEx.getClass().getSimpleName(), "unknown");
                TraceStore.put("query.preprocessor.suppressed.stage", "interactionRules");
                TraceStore.put("query.preprocessor.suppressed.errorType", errorType);
                TraceStore.put("query.preprocessor.suppressed.interactionRules", true);
                TraceStore.put("query.preprocessor.suppressed.interactionRules.errorType", errorType);
                tracePreprocessorFailure("interaction_rules", d, q, rulesEx);
            }
        }

        if (merged.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        merged.forEach((k, v) -> out.put(k, Set.copyOf(v)));
        return out;
    }

    private static void tracePreprocessorFailure(
            String stage,
            QueryContextPreprocessor delegate,
            String query,
            Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safePreprocessor = delegate == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(delegate.getClass().getSimpleName(), "unknown");
        String safeReason = error == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
        TraceStore.inc("query.preprocessor." + safeStage + ".failed");
        TraceStore.put("query.preprocessor." + safeStage + ".failureReason", safeReason);
        TraceStore.put("query.preprocessor." + safeStage + ".name", safePreprocessor);
        TraceStore.put("query.preprocessor." + safeStage + ".queryHash", SafeRedactor.hash12(query));
        TraceStore.put("query.preprocessor." + safeStage + ".queryLength", query == null ? 0 : query.length());
        TraceStore.putIfAbsent("query.preprocessor.failed", true);
        TraceStore.putIfAbsent("query.preprocessor.failureStage", safeStage);
        TraceStore.putIfAbsent("query.preprocessor.failureReason", safeReason);
        TraceStore.putIfAbsent("query.preprocessor.name", safePreprocessor);
        TraceStore.putIfAbsent("query.preprocessor.queryHash", SafeRedactor.hash12(query));
        TraceStore.putIfAbsent("query.preprocessor.queryLength", query == null ? 0 : query.length());
    }

    private boolean guardrailDisabled(QueryContextPreprocessor delegate) {
        return !cognitiveProps.isEnabled() && delegate instanceof GuardrailQueryPreprocessor;
    }

    private static void traceGuardrailSkipped(String stage, QueryContextPreprocessor delegate) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safePreprocessor = delegate == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(delegate.getClass().getSimpleName(), "unknown");
        TraceStore.inc("query.preprocessor.guardrailSkipped.count");
        TraceStore.put("query.preprocessor.guardrailSkipped", true);
        TraceStore.put("query.preprocessor.guardrailSkipped.stage", safeStage);
        TraceStore.put("query.preprocessor.guardrailSkipped.reason", "cognitive_disabled");
        TraceStore.put("query.preprocessor.guardrailSkipped.name", safePreprocessor);
    }

    private static void traceNullPreprocessorOutput(
            String stage,
            QueryContextPreprocessor delegate,
            String previousQuery,
            String reason) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safePreprocessor = delegate == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(delegate.getClass().getSimpleName(), "unknown");
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        TraceStore.put("query.preprocessor.nullOutput", true);
        TraceStore.put("query.preprocessor.nullOutput.stage", safeStage);
        TraceStore.put("query.preprocessor.nullOutput.reason", safeReason);
        TraceStore.put("query.preprocessor.nullOutput.name", safePreprocessor);
        TraceStore.put("query.preprocessor.nullOutput.queryHash", SafeRedactor.hash12(previousQuery));
        TraceStore.put("query.preprocessor.nullOutput.queryLength", previousQuery == null ? 0 : previousQuery.length());
    }
}
