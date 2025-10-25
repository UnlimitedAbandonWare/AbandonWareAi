// src/main/java/com/example/lms/service/rag/ContextOrchestrator.java
package com.example.lms.service.rag;

import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.PromptEngine;
import com.example.lms.service.verbosity.VerbosityProfile;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.service.rag.energy.ContextEnergyModel;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.config.HyperparameterService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Component
@RequiredArgsConstructor
public class ContextOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ContextOrchestrator.class);

    private final PromptEngine promptEngine;
    private final AuthorityScorer authorityScorer;
    private final ContradictionScorer contradictionScorer;
    private final ContextEnergyModel energyModel;
    private final HyperparameterService hp;

    // ─ Anger Overdrive (optional beans) ─
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.rag.overdrive.OverdriveGuard overdriveGuard;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.service.rag.overdrive.AngerOverdriveNarrower overdriveNarrower;


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

        // 3-A. (희소/저권위/모순) 조건 시 앵거 오버드라이브 발동 → 점군 압축 & 다단계 축소
        if (overdriveGuard != null && overdriveNarrower != null) {
            try {
                if (overdriveGuard.shouldActivate(query, candidates)) {
                    candidates = overdriveNarrower.narrow(query, candidates);
                }
            } catch (Exception ignored) { /* fail-soft */ }
        }


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