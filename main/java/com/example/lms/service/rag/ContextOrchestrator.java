// src/main/java/com/example/lms/service/rag/ContextOrchestrator.java
package com.example.lms.service.rag;

import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.anchor.AnchorNarrowingResult;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import com.example.lms.service.verbosity.VerbosityProfile;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceMemoryFingerprintProbe;
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
 * - merge -> dedupe -> cap -> PromptContext 생성 -> PromptBuilder 위임
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

    private final PromptBuilder promptBuilder;

    @Value("${orchestrator.max-docs:10}")
    private int maxDocs;

    @Value("${orchestrator.max-docs.deep:14}")
    private int maxDocsDeep;

    @Value("${orchestrator.max-docs.ultra:18}")
    private int maxDocsUltra;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DynamicContextCompressor promptContextCompressor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OverdriveGuard overdriveGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AnchorNarrower overdriveAnchorNarrower;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TraceMemoryFingerprintProbe traceMemoryFingerprintProbe;

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

        String promptMemoryCtx = memoryCtx;
        traceMemoryFingerprint("raw_snapshot", "before_memory_compression", query, promptMemoryCtx, null);
        if (shouldActivateMemoryCompression(query, promptMemoryCtx)) {
            try {
                promptMemoryCtx = promptContextCompressor.compressMemoryForPrompt(query, promptMemoryCtx);
                TraceStore.put("prompt.memory.composer.applied", true);
            } catch (NullPointerException | IllegalArgumentException | IllegalStateException ex) {
                TraceStore.put("prompt.memory.composer.applied", false);
                traceMemoryComposerSkipped("exception_fail_soft");
                log.debug("[ContextOrchestrator] compressMemoryForPrompt fail-soft. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
                // fail-soft: keep original memory context
            }
        }
        traceMemoryFingerprint("first_refinement", "after_memory_compression", query, promptMemoryCtx,
                checkpointDetails("memory_compression", 0, 0, 0));
        boolean hasMemory = promptMemoryCtx != null && !promptMemoryCtx.isBlank();

        // [FIX-B1] Null-safe 래핑 + 디버그 로그 강화
        // 1) 후보 merge (웹을 먼저 배치하여 최신성을 우선)
        List<Content> safeWebResults = webResults != null ? webResults : Collections.emptyList();
        List<Content> safeVectorResults = vectorResults != null ? vectorResults : Collections.emptyList();
        int rawCandidateCount = safeWebResults.size() + safeVectorResults.size();
        traceContextStart(rawCandidateCount);
        log.debug("[ContextOrchestrator] Input sizes: web={}, vector={}",
                safeWebResults.size(), safeVectorResults.size());
        List<Content> merged = new ArrayList<>();
        merged.addAll(safeWebResults);
        merged.addAll(safeVectorResults);
        // [FIX-B2] 병합 후 상태 로그
        if (merged.isEmpty()) {
            log.warn("[ContextOrchestrator] Both web and vector results are empty for queryHash12={} queryLength={}",
                    SafeRedactor.hash12(query), query == null ? 0 : query.length());
        }

        // 2) 메모리 단독 모드: 검색 결과가 없어도 메모리만으로 프롬프트 생성
        if (merged.isEmpty() && hasMemory) {
            traceContextResult(0, "memory_only_no_candidates");
            traceMemoryFingerprint("load", "memory_only_no_candidates", query, promptMemoryCtx,
                    checkpointDetails("memory_only_no_candidates", 0, 0, 0));
            PromptContext ctx = PromptContext.builder()
                    .rag(List.of())
                    .web(List.of())
                    .memory(promptMemoryCtx)
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
            return promptBuilder.build(ctx);
        }

        // 3) empty handling
        // [FUTURE_TECH FIX] Do NOT early-return '정보 없음' here; delegate graceful handling to Prompt/Guard layers.
        // Build an empty-context prompt so the assistant can respond based on system policies.
        if (merged.isEmpty()) {
            traceContextResult(0, "empty_candidates");
            traceMemoryFingerprint("load", "empty_candidates", query, promptMemoryCtx,
                    checkpointDetails("empty_candidates", 0, 0, 0));
            PromptContext ctx = PromptContext.builder()
                    .rag(List.of())
                    .web(List.of())
                    .memory(promptMemoryCtx)
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
            return promptBuilder.build(ctx);
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

        List<Content> finalWeb = new ArrayList<>();
        List<Content> finalRag = new ArrayList<>();
        splitPromptDocs(finalDocs, finalWeb, finalRag);
        String contextRefinementSummary = "";
        Map<String, Double> contextRefinementSignals = Map.of();
        if (promptContextCompressor != null && allPromptDocsAlreadyCompressed(finalWeb, finalRag)) {
            tracePromptComposerSkipped("already_compressed");
        } else if (promptContextCompressor != null) {
            if (shouldActivatePromptCompression(query, finalDocs)) {
                finalDocs = maybeApplyOverdriveAnchorNarrowing(query, finalDocs);
                finalWeb = new ArrayList<>();
                finalRag = new ArrayList<>();
                splitPromptDocs(finalDocs, finalWeb, finalRag);
                try {
                    DynamicContextCompressor.PromptContextComposition composition =
                            promptContextCompressor.composeForPrompt(query, finalWeb, finalRag);
                    if (composition != null) {
                        finalWeb = composition.web() == null ? new ArrayList<>() : new ArrayList<>(composition.web());
                        finalRag = composition.rag() == null ? new ArrayList<>() : new ArrayList<>(composition.rag());
                        contextRefinementSummary = composition.contextRefinementSummary();
                        contextRefinementSignals = composition.contextRefinementSignals();
                    }
                } catch (Exception ex) {
                    TraceStore.put("orchestrator.compress.skipReason", "exception_fail_soft");
                    log.debug("[ContextOrchestrator] composeForPrompt fail-soft. errorHash={} errorLength={}",
                            SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
                    // fail-soft: keep original assembled context
                }
            }
        }

        log.debug("[ContextOrchestrator] Assembled docs: web={}, rag={}, cap={}",
                finalWeb.size(), finalRag.size(), cap);
        int finalContextCount = finalWeb.size() + finalRag.size();
        traceContextResult(finalContextCount, finalContextCount == 0 ? "empty_after_filter" : "");
        traceMemoryFingerprint("second_refinement", "after_context_filter", query, promptMemoryCtx,
                checkpointDetails("after_context_filter", finalContextCount, finalWeb.size(), finalRag.size()));

        PromptContext ctx = PromptContext.builder()
                .rag(finalRag)
                // 웹/벡터 분리는 retrieval 단계에서 결정해야 하므로 여기서는 빈 리스트 유지
                .web(finalWeb)
                .memory(promptMemoryCtx)
                .domain("GENERAL")
                .intent("GENERAL")
                .interactionRules(interactionRules == null ? Map.of() : interactionRules)
                .verbosityHint(profile == null ? null : profile.hint())
                .minWordCount(profile == null ? null : profile.minWordCount())
                .targetTokenBudgetOut(profile == null ? null : profile.targetTokenBudgetOut())
                .sectionSpec(profile == null ? null : profile.sections())
                .audience(profile == null ? null : profile.audience())
                .citationStyle(profile == null ? null : profile.citationStyle())
                .contextRefinementSummary(contextRefinementSummary)
                .contextRefinementSignals(contextRefinementSignals)
                .build();

        traceMemoryFingerprint("load", "prompt_builder", query, promptMemoryCtx,
                checkpointDetails("prompt_builder", finalContextCount, finalWeb.size(), finalRag.size()));
        return promptBuilder.build(ctx);
    }

    private void traceMemoryFingerprint(String stage,
                                        String phase,
                                        String query,
                                        String memoryCtx,
                                        Map<String, Object> extra) {
        TraceMemoryFingerprintProbe probe = traceMemoryFingerprintProbe;
        if (probe == null) {
            return;
        }
        try {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("memoryCtx", memoryCtx == null ? "" : memoryCtx);
            raw.put("memoryLength", memoryCtx == null ? 0 : memoryCtx.length());
            raw.put("queryHash", SafeRedactor.hashValue(query));
            raw.put("queryLength", query == null ? 0 : query.length());
            raw.put("phase", SafeRedactor.traceLabelOrFallback(phase, "unknown"));
            if (extra != null && !extra.isEmpty()) {
                raw.putAll(extra);
            }
            probe.checkpoint(stage, "ContextOrchestrator", raw);
        } catch (RuntimeException ex) {
            TraceStore.put("traceMemory.probe.skippedReason", "exception_fail_soft");
            TraceStore.put("traceMemory.probe.errorHash", SafeRedactor.hashValue(messageOf(ex)));
            log.debug("[ContextOrchestrator] trace-memory fingerprint fail-soft. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
        }
    }

    private static Map<String, Object> checkpointDetails(String route,
                                                        int contextCount,
                                                        int webCount,
                                                        int ragCount) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("route", SafeRedactor.traceLabelOrFallback(route, "unknown"));
        details.put("contextCount", Math.max(0, contextCount));
        details.put("webCount", Math.max(0, webCount));
        details.put("ragCount", Math.max(0, ragCount));
        return details;
    }

    private static boolean allPromptDocsAlreadyCompressed(List<Content> webDocs, List<Content> ragDocs) {
        int total = 0;
        List<List<Content>> groups = List.of(
                webDocs == null ? List.<Content>of() : webDocs,
                ragDocs == null ? List.<Content>of() : ragDocs);
        for (List<Content> docs : groups) {
            for (Content doc : docs) {
                total++;
                if (!isAlreadyCompressed(doc)) {
                    return false;
                }
            }
        }
        return total > 0;
    }

    private static void splitPromptDocs(List<Content> docs, List<Content> web, List<Content> rag) {
        for (Content c : docs == null ? List.<Content>of() : docs) {
            if (isWebContent(c)) {
                web.add(c);
            } else {
                rag.add(c);
            }
        }
    }

    private List<Content> maybeApplyOverdriveAnchorNarrowing(String query, List<Content> candidates) {
        List<Content> safeCandidates = candidates == null ? List.of() : candidates;
        if (overdriveAnchorNarrower == null) {
            traceOverdriveAnchorSkip("anchor_narrower_missing");
            return safeCandidates;
        }
        List<String> texts = safeCandidates.stream()
                .map(ContextOrchestrator::textOf)
                .filter(text -> text != null && !text.isBlank())
                .toList();
        if (texts.isEmpty()) {
            traceOverdriveAnchorSkip("empty_candidates");
            return safeCandidates;
        }
        try {
            AnchorNarrowingResult result = overdriveAnchorNarrower.narrow(query, texts, 3, 0.65d);
            List<String> filtered = overdriveAnchorNarrower.filterCandidates(query, texts, result, 0.65d);
            List<Content> narrowed = retainByText(safeCandidates, filtered);
            if (narrowed.isEmpty()) {
                traceOverdriveAnchorSkip("empty_filtered_original_returned");
                return safeCandidates;
            }
            traceOverdriveAnchorNarrowed(narrowed.size(), result == null ? "unknown" : result.reason());
            return narrowed;
        } catch (RuntimeException ex) {
            traceOverdriveAnchorSkip("exception_original_returned");
            log.debug("[ContextOrchestrator] overdrive anchor narrow fail-soft. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
            return safeCandidates;
        }
    }

    private static List<Content> retainByText(List<Content> candidates, List<String> filteredTexts) {
        if (filteredTexts == null || filteredTexts.isEmpty()) {
            return List.of();
        }
        Set<String> keep = new LinkedHashSet<>(filteredTexts);
        List<Content> out = new ArrayList<>();
        for (Content candidate : candidates == null ? List.<Content>of() : candidates) {
            if (keep.contains(textOf(candidate))) {
                out.add(candidate);
            }
        }
        return out;
    }

    private static String textOf(Content content) {
        return Optional.ofNullable(content)
                .map(Content::textSegment)
                .map(TextSegment::text)
                .orElse(null);
    }

    private static void traceOverdriveAnchorNarrowed(int narrowedCount, String reason) {
        TraceStore.put("overdrive.anchor.narrowed.k", Math.max(0, narrowedCount));
        TraceStore.put("overdrive.anchor.narrowedReason",
                SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        TraceStore.put("overdrive.anchor.skipReason", "");
    }

    private static void traceOverdriveAnchorSkip(String reason) {
        TraceStore.put("overdrive.anchor.narrowed.k", 0);
        TraceStore.put("overdrive.anchor.narrowedReason", "");
        TraceStore.put("overdrive.anchor.skipReason",
                SafeRedactor.traceLabelOrFallback(reason, "unknown"));
    }

    private static void traceContextStart(int rawCandidateCount) {
        try {
            TraceStore.put("context.candidates.raw", Math.max(0, rawCandidateCount));
            TraceStore.put("context.overdrive.activated", false);
            TraceStore.put("context.extremeZ.activated", false);
            TraceStore.put("context.overdrive.skipReason", "");
        } catch (IllegalArgumentException | IllegalStateException ignore) {
            log.debug("[ContextOrchestrator] fail-soft stage={}", "traceContextStart");
        }
    }

    private static void traceContextResult(int afterFilterCount, String starvationReason) {
        try {
            int safeCount = Math.max(0, afterFilterCount);
            TraceStore.put("context.candidates.afterFilter", safeCount);
            TraceStore.put("context.starvation", safeCount == 0);
            TraceStore.put("context.starvation.reason",
                    SafeRedactor.traceLabelOrFallback(starvationReason, ""));
        } catch (IllegalArgumentException | IllegalStateException ignore) {
            log.debug("[ContextOrchestrator] fail-soft stage={}", "traceContextResult");
        }
    }

    private static void traceContextOverdrive(boolean activated, String skipReason) {
        try {
            TraceStore.put("context.overdrive.activated", activated);
            TraceStore.put("context.overdrive.skipReason",
                    SafeRedactor.traceLabelOrFallback(skipReason, ""));
        } catch (IllegalArgumentException | IllegalStateException ignore) {
            log.debug("[ContextOrchestrator] fail-soft stage={}", "traceContextOverdrive");
        }
    }

    private static boolean isAlreadyCompressed(Content content) {
        try {
            if (content == null || content.textSegment() == null || content.textSegment().metadata() == null) {
                return false;
            }
            Object value = content.textSegment().metadata().toMap().get("_nova.compressed");
            return "true".equalsIgnoreCase(String.valueOf(value));
        } catch (IllegalArgumentException | IllegalStateException ignore) {
            log.debug("[ContextOrchestrator] fail-soft stage={}", "isAlreadyCompressed");
            return false;
        }
    }

    private static void tracePromptComposerSkipped(String reason) {
        try {
            TraceStore.put("prompt.context.composer.skippedReason",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (IllegalArgumentException | IllegalStateException ignore) {
            log.debug("[ContextOrchestrator] fail-soft stage={}", "tracePromptComposerSkipped");
            // best-effort
        }
    }

    private static void traceMemoryComposerSkipped(String reason) {
        try {
            TraceStore.put("prompt.memory.composer.skippedReason",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (IllegalArgumentException | IllegalStateException ignore) {
            log.debug("[ContextOrchestrator] fail-soft stage={}", "traceMemoryComposerSkipped");
            // best-effort
        }
    }

    private boolean shouldActivateMemoryCompression(String query, String memoryCtx) {
        if (promptContextCompressor == null || memoryCtx == null || memoryCtx.isBlank()) {
            return false;
        }
        if (overdriveGuard == null) {
            TraceStore.put("overdrive.guard.memory.decision", false);
            traceMemoryComposerSkipped("guard_missing");
            return false;
        }
        try {
            boolean activated = overdriveGuard.shouldActivate(query, List.of());
            TraceStore.put("overdrive.guard.memory.decision", activated);
            if (!activated) {
                traceMemoryComposerSkipped("guard_declined");
            }
            return activated;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            TraceStore.put("overdrive.guard.memory.decision", false);
            traceMemoryComposerSkipped("guard_exception");
            log.debug("[ContextOrchestrator] overdrive memory guard fail-soft. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
            return false;
        }
    }

    private boolean shouldActivatePromptCompression(String query, List<Content> candidates) {
        if (overdriveGuard == null) {
            TraceStore.put("overdrive.guard.decision", false);
            traceContextOverdrive(false, "guard_missing");
            tracePromptComposerSkipped("guard_missing");
            return false;
        }
        try {
            boolean activated = overdriveGuard.shouldActivate(query, candidates == null ? List.of() : candidates);
            TraceStore.put("overdrive.guard.decision", activated);
            TraceStore.put("overdrive.trigger.activated", activated);
            TraceStore.put("overdrive.trigger.score", TraceStore.get("overdrive.score"));
            traceContextOverdrive(activated, activated ? "" : "guard_declined");
            if (!activated) {
                traceOverdriveAnchorSkip("guard_declined");
                tracePromptComposerSkipped("guard_declined");
            }
            return activated;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            TraceStore.put("overdrive.guard.decision", false);
            TraceStore.put("overdrive.trigger.activated", false);
            traceContextOverdrive(false, "guard_exception");
            traceOverdriveAnchorSkip("guard_exception");
            tracePromptComposerSkipped("guard_exception");
            log.debug("[ContextOrchestrator] overdrive guard fail-soft. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
            return false;
        }
    }

    private static String hashOf(String s) {
        return Integer.toHexString(Objects.requireNonNullElse(s, "").hashCode());
    }

    private static boolean isWebContent(Content c) {
        try {
            if (c == null || c.textSegment() == null || c.textSegment().metadata() == null) {
                return false;
            }
            Map<String, Object> md = c.textSegment().metadata().toMap();
            String stage = value(md, "retrieval_stage");
            String docType = value(md, "doc_type");
            String source = value(md, "source");
            String sourceTag = value(md, "source_tag");
            String url = value(md, "url");
            String provider = value(md, "provider");
            return containsWeb(stage)
                    || "WEB".equalsIgnoreCase(docType)
                    || containsWeb(source)
                    || containsWeb(sourceTag)
                    || containsWeb(provider)
                    || (url != null && (url.startsWith("http://") || url.startsWith("https://")));
        } catch (Exception ignore) {
            log.debug("[ContextOrchestrator] fail-soft stage={}", "isWebContent");
            return false;
        }
    }

    private static boolean containsWeb(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("web");
    }

    private static String value(Map<String, Object> md, String key) {
        Object v = md != null ? md.get(key) : null;
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }
}
