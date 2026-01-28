// src/main/java/com/example/lms/service/rag/ContextOrchestrator.java
package com.example.lms.service.rag;

import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.PromptEngine;
import com.example.lms.service.verbosity.VerbosityProfile;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ContextOrchestrator
 *
 * 역할: Prompt Assembly ONLY
 * - Retrieval/Rerank 결과(리스트 순서)를 신뢰하고, 여기서는 조립만 수행합니다.
 * - merge -> dedupe -> cap -> PromptContext 생성 -> PromptEngine 위임
 *
 * 삭제된 기능(의도적으로 제거):
 * - Step0 LLM 재분석 (중복 호출)
 * - shouldPrioritizeWebResults (웹/벡터 우선순위 재판단)
 * - 오버드라이브/에너지 모델 기반 2차 랭킹
 * - 도메인 하드코딩 authority bonus
 */
@Component
@RequiredArgsConstructor
public class ContextOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ContextOrchestrator.class);

    private final PromptEngine promptEngine;

    @Value("${orchestrator.max-docs:10}")
    private int maxDocs;

    @Value("${orchestrator.max-docs.deep:14}")
    private int maxDocsDeep;

    @Value("${orchestrator.max-docs.ultra:18}")
    private int maxDocsUltra;

    /**
     * 여러 정보 소스를 바탕으로 최종 컨텍스트를 조율하고, 동적 규칙을 포함하여 프롬프트를 생성합니다.
     * (기존 호환 버전: Verbosity 미사용)
     */
    public String orchestrate(String query,
                              List<Content> vectorResults,
                              List<Content> webResults,
                              Map<String, Set<String>> interactionRules) {
        return orchestrate(query, vectorResults, webResults, interactionRules, null, null);
    }

    /**
     * Verbosity-aware 오케스트레이션
     */
    public String orchestrate(String query,
                              List<Content> vectorResults,
                              List<Content> webResults,
                              Map<String, Set<String>> interactionRules,
                              VerbosityProfile profile) {
        return orchestrate(query, vectorResults, webResults, interactionRules, profile, null);
    }

    /**
     * Verbosity-aware + Memory-aware 오케스트레이션 (메모리 단독도 허용)
     */
    public String orchestrate(String query,
                              List<Content> vectorResults,
                              List<Content> webResults,
                              Map<String, Set<String>> interactionRules,
                              VerbosityProfile profile,
                              String memoryCtx) {

        boolean hasMemory = memoryCtx != null && !memoryCtx.isBlank();

        // [FIX-B1] Null-safe 래핑 + 디버그 로그 강화
        // 1) 후보 merge (웹을 먼저 배치하여 최신성을 우선)
        List<Content> safeWebResults = webResults != null ? webResults : Collections.emptyList();
        List<Content> safeVectorResults = vectorResults != null ? vectorResults : Collections.emptyList();
        log.debug("[ContextOrchestrator] Input sizes: web={}, vector={}",
                safeWebResults.size(), safeVectorResults.size());
        List<Content> merged = new ArrayList<>();
        merged.addAll(safeWebResults);
        merged.addAll(safeVectorResults);
        // [FIX-B2] 병합 후 상태 로그
        if (merged.isEmpty()) {
            log.warn("[ContextOrchestrator] Both web and vector results are empty for query='{}'", query);
        }

        // 2) 메모리 단독 모드: 검색 결과가 없어도 메모리만으로 프롬프트 생성
        if (merged.isEmpty() && hasMemory) {
            PromptContext ctx = PromptContext.builder()
                    .rag(List.of())
                    .web(List.of())
                    .memory(memoryCtx)
                    .domain("GENERAL")
                    .intent("GENERAL")
                    .interactionRules(interactionRules == null ? Map.of() : interactionRules)
                    .verbosityHint(profile == null ? null : profile.hint())
                    .minWordCount(profile == null ? null : profile.minWordCount())
                    .targetTokenBudgetOut(profile == null ? null : profile.targetTokenBudgetOut())
                    .sectionSpec(profile == null ? null : profile.sections())
                    .audience(profile == null ? null : profile.audience())
                    .citationStyle(profile == null ? null : profile.citationStyle())
                    .build();
            return promptEngine.createPrompt(ctx);
        }

        // 3) empty handling
        // [FUTURE_TECH FIX] Do NOT early-return '정보 없음' here; delegate graceful handling to Prompt/Guard layers.
        // Build an empty-context prompt so the assistant can respond based on system policies.
        if (merged.isEmpty()) {
            PromptContext ctx = PromptContext.builder()
                    .rag(List.of())
                    .web(List.of())
                    .memory(memoryCtx)
                    .domain("GENERAL")
                    .intent("GENERAL")
                    .interactionRules(interactionRules == null ? Map.of() : interactionRules)
                    .verbosityHint(profile == null ? null : profile.hint())
                    .minWordCount(profile == null ? null : profile.minWordCount())
                    .targetTokenBudgetOut(profile == null ? null : profile.targetTokenBudgetOut())
                    .sectionSpec(profile == null ? null : profile.sections())
                    .audience(profile == null ? null : profile.audience())
                    .citationStyle(profile == null ? null : profile.citationStyle())
                    .build();
            return promptEngine.createPrompt(ctx);
        }

        // 4) dedupe (text 기반)
        LinkedHashMap<String, Content> uniq = new LinkedHashMap<>();
        for (Content c : merged) {
            if (c == null) {
                continue;
            }
            String text = Optional.ofNullable(c.textSegment())
                    .map(TextSegment::text)
                    .orElse(c.toString());

            if (text == null || text.isBlank()) {
                continue;
            }
            uniq.putIfAbsent(hashOf(text), c);
        }

        // 5) cap 결정 (Verbosity에 따라 확장)
        int cap = this.maxDocs;
        String hint = profile != null ? profile.hint() : null;
        if ("deep".equalsIgnoreCase(hint)) {
            cap = Math.max(cap, maxDocsDeep);
        } else if ("ultra".equalsIgnoreCase(hint)) {
            cap = Math.max(cap, maxDocsUltra);
        }

        List<Content> finalDocs = uniq.values().stream()
                .limit(Math.max(1, cap))
                .toList();

        log.debug("[ContextOrchestrator] Assembled {} docs (cap={}) for query='{}'", finalDocs.size(), cap, query);

        PromptContext ctx = PromptContext.builder()
                .rag(finalDocs)
                // 웹/벡터 분리는 retrieval 단계에서 결정해야 하므로 여기서는 빈 리스트 유지
                .web(List.of())
                .memory(memoryCtx)
                .domain("GENERAL")
                .intent("GENERAL")
                .interactionRules(interactionRules == null ? Map.of() : interactionRules)
                .verbosityHint(profile == null ? null : profile.hint())
                .minWordCount(profile == null ? null : profile.minWordCount())
                .targetTokenBudgetOut(profile == null ? null : profile.targetTokenBudgetOut())
                .sectionSpec(profile == null ? null : profile.sections())
                .audience(profile == null ? null : profile.audience())
                .citationStyle(profile == null ? null : profile.citationStyle())
                .build();

        return promptEngine.createPrompt(ctx);
    }

    private static String hashOf(String s) {
        return Integer.toHexString(Objects.requireNonNullElse(s, "").hashCode());
    }
}
