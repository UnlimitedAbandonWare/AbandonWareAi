// src/main/java/com/example/lms/service/rag/ContextOrchestrator.java
package com.example.lms.service.rag;

import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.PromptEngine;
import com.example.lms.service.verbosity.VerbosityProfile;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.service.rag.energy.ContextEnergyModel;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser;
import com.example.lms.service.rag.tune.StrategyWeightTuner;
import com.example.lms.service.rag.rerank.LightWeightRanker;
import com.example.lms.service.rag.rerank.WeightedSumRanker;
import com.example.lms.service.rag.rerank.RerankGate;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContextOrchestrator {

    private final PromptEngine promptEngine;
    private final AuthorityScorer authorityScorer;
    private final ContradictionScorer contradictionScorer;
    private final ContextEnergyModel energyModel;
    private final HyperparameterService hp;

    // Chain 2.0 components: RRF fuser, strategy tuner, lightweight ranker, weighted sum ranker
    private final WeightedReciprocalRankFuser weightedReciprocalRankFuser;
    private final StrategyWeightTuner strategyWeightTuner;
    private final LightWeightRanker lightWeightRanker;
    private final WeightedSumRanker weightedSumRanker;
    // Diversity picker for eliminating near‑duplicate context after weighted ranking
    private final com.example.lms.service.rag.diversity.DiversityPicker diversityPicker;

    // CE re‑ranking components (optional)
    private final RerankGate rerankGate;
    private final CrossEncoderReranker crossEncoderReranker;

    @Value("${orchestrator.max-docs:10}")
    private int maxDocs;

    // Verbosity에 따른 상한 확장
    @Value("${orchestrator.max-docs.deep:14}")
    private int maxDocsDeep;

    @Value("${orchestrator.max-docs.ultra:18}")
    private int maxDocsUltra;

    @Value("${orchestrator.min-top-score:0.60}")
    private double minTopScore;
    @Value("${orchestrator.energy.enabled:true}")
    private boolean energyBasedSelection;

    // '최신성' 요구 쿼리를 감지하기 위한 정규식
    private static final Pattern TIMELY = Pattern.compile("(?i)(공지|업데이트|패치|스케줄|일정|news|update|patch|release)");

    /**
     * 여러 정보 소스를 바탕으로 최종 컨텍스트를 조율하고, 동적 규칙을 포함하여 프롬프트를 생성합니다.
     * (기존 호환 버전: Verbosity 미사용)
     */
    /** Verbosity-aware + Memory-aware (메모리 단독도 허용) */
    public String orchestrate(String query,
                              List<Content> vectorResults,
                              List<Content> webResults,
                              Map<String, Set<String>> interactionRules) {
        return orchestrate(query, vectorResults, webResults, interactionRules, null, null);
    }
    /**
     * Verbosity-aware 오케스트레이션
     * - Verbosity(deep/ultra)에 따라 상위 문서 캡 확장
     * - Verbosity 신호를 PromptContext에 전파(섹션/최소길이/토큰 버짓/대상/인용스타일)
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
        // ----- Chain 2.0 pipeline: fused retrieval and ranking -----
        try {
            // Prepare sources for RRF fusion
            java.util.List<java.util.List<Content>> sources = new java.util.ArrayList<>();
            sources.add(vectorResults != null ? vectorResults : java.util.List.of());
            sources.add(webResults != null ? webResults : java.util.List.of());
            // Determine weights via strategy tuner (web, vector)
            double[] tuned = null;
            try {
                tuned = (strategyWeightTuner != null)
                        ? strategyWeightTuner.tune(query == null ? null : new dev.langchain4j.rag.query.Query(query), null)
                        : null;
            } catch (Exception ignore) {
                tuned = null;
            }
            java.util.List<Double> weights;
            if (tuned == null || tuned.length != 2) {
                weights = java.util.List.of(0.5, 0.5);
            } else {
                double w0 = tuned[0];
                double w1 = tuned[1];
                if (w0 < 0.0) w0 = 0.0; else if (w0 > 1.0) w0 = 1.0;
                if (w1 < 0.0) w1 = 0.0; else if (w1 > 1.0) w1 = 1.0;
                weights = java.util.List.of(w0, w1);
            }
            // Fuse lists
            java.util.List<Content> fused = (weightedReciprocalRankFuser != null)
                    ? weightedReciprocalRankFuser.fuse(sources, weights, 80)
                    : new java.util.ArrayList<>();
            // Light weight ranking
            java.util.List<Content> stage1 = fused;
            try {
                if (lightWeightRanker != null) {
                    stage1 = lightWeightRanker.rank(fused, query, 40);
                }
            } catch (Exception ignore) {
                // keep fused
            }
            // Weighted sum ranking
            java.util.List<Content> stage2;
            try {
                stage2 = (weightedSumRanker != null)
                        ? weightedSumRanker.rank(stage1, query, 32)
                        : stage1;
            } catch (Exception ex) {
                stage2 = stage1;
            }
            // Optional CE re‑ranker: if the gate says so, re‑rank via cross encoder
            java.util.List<Content> stage3 = stage2;
            try {
                if (stage2 != null && rerankGate != null && rerankGate.shouldRerank(stage2)) {
                    if (crossEncoderReranker != null) {
                        stage3 = crossEncoderReranker.rerank(query, stage2, Math.min(32, stage2.size()));
                    }
                }
            } catch (Exception ignore) {
                // fail‑soft: keep stage2
            }
            // Use re‑ranked results
            stage2 = stage3;
            // Apply diversity picking to reduce duplicate contexts (fail‑soft)
            try {
                if (stage2 != null && diversityPicker != null) {
                    stage2 = diversityPicker.pick(stage2, Math.min(12, stage2.size()));
                }
            } catch (Exception ignore) {
                // ignore diversity failures
            }
            boolean hasMemoryLocal = memoryCtx != null && !memoryCtx.isBlank();
            if (stage2 != null && !stage2.isEmpty()) {
                // Determine cap based on verbosity
                int cap2 = this.maxDocs;
                String hint2 = profile != null ? profile.hint() : null;
                if ("deep".equalsIgnoreCase(hint2)) {
                    cap2 = Math.max(cap2, maxDocsDeep);
                } else if ("ultra".equalsIgnoreCase(hint2)) {
                    cap2 = Math.max(cap2, maxDocsUltra);
                }
                java.util.List<Content> candidates2 = stage2;
                java.util.List<Content> finalDocs2 = energyBasedSelection
                        ? energyModel.selectByEnergy(
                        java.util.Optional.ofNullable(query).orElse(""),
                        candidates2,
                        Math.max(1, cap2))
                        : candidates2.stream().limit(Math.max(1, cap2)).toList();
                PromptContext ctx2 = PromptContext.builder()
                        .rag(finalDocs2)
                        .web(java.util.List.of())
                        .memory(memoryCtx)
                        .domain("GENERAL")
                        .intent("GENERAL")
                        .interactionRules(interactionRules == null ? java.util.Map.of() : interactionRules)
                        .verbosityHint(profile == null ? null : profile.hint())
                        .minWordCount(profile == null ? null : profile.minWordCount())
                        .targetTokenBudgetOut(profile == null ? null : profile.targetTokenBudgetOut())
                        .sectionSpec(profile == null ? null : profile.sections())
                        .audience(profile == null ? null : profile.audience())
                        .citationStyle(profile == null ? null : profile.citationStyle())
                        .build();
                return promptEngine.createPrompt(ctx2);
            } else if (!hasMemoryLocal) {
                return "정보 없음";
            }
        } catch (Exception ignore) {
            // Fall through to legacy scoring
        }
        // ----- End Chain 2.0 pipeline -----

        List<Scored> pool = new ArrayList<>();
        boolean wantsFresh = query != null && TIMELY.matcher(query).find();

        // 1. 각 소스별 결과를 점수화하여 풀(pool)에 추가
        addAll(pool, vectorResults, wantsFresh, Source.VECTOR);
        addAll(pool, webResults, wantsFresh, Source.WEB);

        boolean hasMemory = memoryCtx != null && !memoryCtx.isBlank();
        if (pool.isEmpty() && hasMemory) {
            // ⚠️ 검색 결과가 전혀 없어도 메모리만으로 프롬프트를 생성한다.
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
        if (pool.isEmpty()) return "정보 없음";

        // 2. 텍스트 내용 기반으로 중복 제거
        LinkedHashMap<String, Scored> uniq = new LinkedHashMap<>();
        for (Scored s : pool) {
            uniq.putIfAbsent(hashOf(s.text()), s);
        }

        // 3. (기본) 점수 상위 캡 결정
        int cap = this.maxDocs;
        String hint = profile != null ? profile.hint() : null;
        if ("deep".equalsIgnoreCase(hint)) {
            cap = Math.max(cap, maxDocsDeep);
        } else if ("ultra".equalsIgnoreCase(hint)) {
            cap = Math.max(cap, maxDocsUltra);
        }

        List<Content> candidates = uniq.values().stream()
                .sorted(Comparator.comparingDouble(s -> -s.score))
                .map(s -> s.content)
                .collect(Collectors.toList());

        // 3-B. 에너지 기반 선택 (Authority/Redundancy/Contradiction/Recency 등)
        List<Content> finalDocs = energyBasedSelection
                ? energyModel.selectByEnergy(
                Optional.ofNullable(query).orElse(""),
                candidates,
                Math.max(1, cap))
                : candidates.stream().limit(Math.max(1, cap)).toList();

        // 4. 안전장치: 가장 높은 점수가 기준 미달이면 신뢰할 수 없는 정보로 판단하고 차단
        double topScore = uniq.values().stream().mapToDouble(Scored::score).max().orElse(0.0);
        if (!hasMemory && topScore < minTopScore) {
            log.warn("[Orchestrator] Top score ({}) is below the minimum threshold ({}). Returning '정보 없음'.",
                    String.format("%.2f", topScore), minTopScore);
            return "정보 없음";
        }

        // 5. 최종 프롬프트 생성을 위한 PromptContext 구성 (Verbosity 신호 전파)
        PromptContext ctx = PromptContext.builder()
                .rag(finalDocs)
                .web(List.of()) // 웹 결과는 통합되었으므로 비워 전달(기존 빌더 계약 유지)
                .memory(memoryCtx) // ★ 메모리 증거 주입(있으면)
                .domain("GENERAL")
                .intent("GENERAL")
                .interactionRules(interactionRules == null ? Map.of() : interactionRules)
                // ▼ Verbosity 파라미터 전파(옵션)
                .verbosityHint(profile == null ? null : profile.hint())
                .minWordCount(profile == null ? null : profile.minWordCount())
                .targetTokenBudgetOut(profile == null ? null : profile.targetTokenBudgetOut())
                .sectionSpec(profile == null ? null : profile.sections())
                .audience(profile == null ? null : profile.audience())
                .citationStyle(profile == null ? null : profile.citationStyle())
                .build();

        // (실제 프롬프트 생성/합성은 PromptEngine이 담당)
        return promptEngine.createPrompt(ctx);
    }

    /**
     * 점수화된 컨텐츠 목록을 누적기에 추가합니다.
     */
    private void addAll(List<Scored> acc, List<Content> src, boolean wantsFresh, Source source) {
        if (src == null || src.isEmpty()) return;

        int rank = 0;
        for (Content c : src) {
            rank++;
            String text = Optional.ofNullable(c.textSegment()).map(TextSegment::text).orElse(c.toString());
            if (text == null || text.isBlank()) continue;

            double baseScore = 1.0 / rank; // 순위가 높을수록 기본 점수가 높음
            double freshnessBonus = (wantsFresh && source == Source.WEB) ? 0.25 : 0.0; // 최신성 요구 시 웹 검색에 보너스
            double lengthPenalty = Math.max(0, (text.length() - 1200) / 1200.0); // 긴 텍스트에 약간의 페널티
            double score = Math.max(0.0, Math.min(1.0, baseScore + freshnessBonus - 0.1 * lengthPenalty));

            acc.add(new Scored(c, score, source));
        }
    }

    /**
     * 중복 제거를 위한 간단한 해시 생성
     */
    private static String hashOf(String s) {
        return Integer.toHexString(Objects.requireNonNullElse(s, "").hashCode());
    }

    /**
     * 점수와 출처 메타데이터를 포함하는 내부 DTO
     */
    private record Scored(Content content, double score, Source source) {
        String text() {
            return Optional.ofNullable(content.textSegment()).map(TextSegment::text).orElse(content.toString());
        }
    }

    /**
     * 정보 출처 Enum
     */
    private enum Source {
        VECTOR, WEB
    }
}