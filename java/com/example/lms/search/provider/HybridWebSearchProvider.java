package com.example.lms.search.provider;

import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brave → Naver 순서로 폴백하는 하이브리드 검색 공급자
 * (폴백 로직을 서비스 계층으로 캡슐화)
 */
@Service
@Primary // WebSearchProvider 타입 주입 시 기본 구현체
@RequiredArgsConstructor
public class HybridWebSearchProvider implements WebSearchProvider {
    private static final Logger log = LoggerFactory.getLogger(HybridWebSearchProvider.class);
    private final NaverSearchService naverService;
    private final BraveSearchService braveService;

    @Value("${gpt-search.hybrid.primary:BRAVE}")
    private String primary;

    @Value("${gpt-search.soak.enabled:true}")
    private boolean soakEnabled;

    private boolean isBravePrimary() {
        return "BRAVE".equalsIgnoreCase(primary);
    }

    private static boolean containsHangul(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
            if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return true;
            }
        }
        return false;
    }

    private String extractKeywords(String query) {
        if (query == null) {
            return null;
        }
        // 조사/어미/불용어 제거 (간단 버전)
        String s = query;
        s = s.replaceAll("(누구야|뭐야|무엇이야|알려줘|검색해줘|어떤|사람|캐릭터|설명해줘|해줘|이야|인가요|인가요\\?)", "");
        // 존칭 완화
        s = s.replaceAll("교수님", "교수")
                .replaceAll("선생님", "선생")
                .replaceAll("의사선생님", "의사");
        return s.trim();
    }

    @Override
    public List<String> search(String query, int topK) {

        boolean isKorean = containsHangul(query);

        if (!isKorean) {
            // 기존 동작 유지 (비한국어 쿼리)
            if (isBravePrimary()) {
                return searchBraveFirst(query, topK);
            }
            return searchNaverFirst(query, topK);
        }

        // 한국어 쿼리일 때: Soak(수세식) 전략 적용
        return searchKoreanSmartMerge(query, topK);
    }

    private List<String> searchKoreanSmartMerge(String query, int topK) {
        // 1차: 기존 Brave/Naver 병렬 검색
        List<String> primary;
        if (isBravePrimary()) {
            primary = searchKoreanBraveAndNaver(query, topK);
        } else {
            primary = searchKoreanNaverAndBrave(query, topK);
        }

        if (primary != null && primary.size() >= 3) {
            return primary;
        }

        if (!soakEnabled) {
            return primary != null ? primary : Collections.emptyList();
        }

        String extracted = extractKeywords(query);
        if (!StringUtils.hasText(extracted) || extracted.equals(query)) {
            return primary != null ? primary : Collections.emptyList();
        }

        log.info("[Hybrid] Soak 수세식 발동: '{}' -> '{}'", query, extracted);

        List<String> keywordResults;
        if (isBravePrimary()) {
            keywordResults = searchKoreanBraveAndNaver(extracted, topK);
        } else {
            keywordResults = searchKoreanNaverAndBrave(extracted, topK);
        }

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (keywordResults != null) {
            merged.addAll(keywordResults);
        }

        if (merged.isEmpty()) {
            return Collections.emptyList();
        }
        return merged.stream().limit(topK).toList();
    }

    private List<String> searchBraveFirst(String query, int topK) {

        // 1. Primary: Brave 시도
        try {
            List<String> brave = braveService.searchSnippets(query, topK);
            if (brave != null && !brave.isEmpty()) {
                log.info("[Hybrid] Brave primary returned {} snippets", brave.size());
                return brave;
            }
            log.info("[Hybrid] Brave primary returned no results. Falling back to Naver.");
        } catch (Exception e) {
            log.warn("[Hybrid] Brave primary failed: {}", e.getMessage());
        }

        // 2. Fallback: Naver 시도
        try {
            List<String> naver = naverService.searchSnippetsSync(query, topK);
            if (naver != null && !naver.isEmpty()) {
                log.debug("[Hybrid] Naver fallback returned {} snippets", naver.size());
                return naver;
            }
        } catch (Exception e) {
            log.warn("[Hybrid] Naver fallback failed: {}", e.getMessage());
        }

        // 3. All failed → 빈 리스트 반환 (시스템은 죽지 않음)
        log.info("[Hybrid] All search providers failed. Returning empty list.");
        return Collections.emptyList();
    }

    private List<String> searchNaverFirst(String query, int topK) {
        // 1. Primary: Naver first
        try {
            List<String> naver = naverService.searchSnippetsSync(query, topK);
            if (naver != null && !naver.isEmpty()) {
                log.info("[Hybrid] Naver primary returned {} snippets", naver.size());
                return naver;
            }
            log.info("[Hybrid] Naver primary returned no results. Falling back to Brave.");
        } catch (Exception e) {
            log.warn("[Hybrid] Naver primary failed: {}", e.getMessage());
        }

        // 2. Fallback: Brave
        try {
            List<String> brave = braveService.searchSnippets(query, topK);
            if (brave != null && !brave.isEmpty()) {
                log.debug("[Hybrid] Brave fallback returned {} snippets", brave.size());
                return brave;
            }
        } catch (Exception e) {
            log.warn("[Hybrid] Brave fallback failed: {}", e.getMessage());
        }

        // 3. All failed → 빈 리스트 반환
        log.info("[Hybrid] All search providers failed. Returning empty list.");
        return Collections.emptyList();
    }

    /**
     * Converts Korean tech queries to English for Brave Search.
     * Focuses on smartphone/tech leak queries where English sources dominate.
     */
    private String convertToEnglishSearchTerm(String query) {
        if (query == null || query.isBlank())
            return query;

        // Already has English letters? Use as-is
        if (query.matches(".*[A-Za-z].*"))
            return query;

        String normalized = query.replaceAll("\\s+", "").toLowerCase();

        // Fold series (critical for Galaxy Z Fold 7 rumors)
        if (normalized.contains("폴드7") || normalized.contains("갤럭시폴드7") || normalized.contains("갤럭시z폴드7")) {
            return "Galaxy Z Fold 7 leaks specs camera display release date";
        }
        if (normalized.contains("폴드6") || normalized.contains("갤럭시폴드6") || normalized.contains("갤럭시z폴드6")) {
            return "Galaxy Z Fold 6 specs";
        }

        // Game character queries (Genshin Impact)
        if (normalized.contains("원신") && normalized.contains("마비카")) {
            return "Genshin Impact Mavuika character";
        }
        if (normalized.contains("원신") && normalized.contains("푸리나")) {
            return "Genshin Impact Furina character";
        }

        // General rumor/leak/spec patterns
        if (normalized.matches(".*(루머|유출|스펙|사양|출시).*")) {
            return query + " latest leaks specs";
        }

        // Fallback: let Brave handle Korean (it supports it)
        return query;
    }

    private List<String> searchKoreanBraveAndNaver(String query, int topK) {

        final String braveQuery = convertToEnglishSearchTerm(query);

        CompletableFuture<List<String>> braveFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return braveService.searchSnippets(braveQuery, topK);
            } catch (Exception e) {
                log.warn("[Hybrid] Brave korean-search failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        });

        CompletableFuture<List<String>> naverFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return naverService.searchSnippetsSync(query, topK);
            } catch (Exception e) {
                log.warn("[Hybrid] Naver korean-search failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        });

        List<String> brave = Collections.emptyList();
        List<String> naver = Collections.emptyList();
        try {
            brave = braveFuture.get();
            naver = naverFuture.get();

            if (brave != null && brave.size() == 1) {
                String only = brave.get(0);
                if (only != null) {
                    String trimmed = only.trim();
                    if (trimmed.startsWith("{")
                            && trimmed.contains("\"web\"")
                            && trimmed.contains("\"results\"")) {
                        log.warn("[Hybrid] Brave returned single JSON-like snippet ({} chars). " +
                                "BraveSearchService may not be parsing JSON properly.",
                                trimmed.length());
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            log.warn("[Hybrid] Korean parallel search join failure: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }

        if (brave == null || brave.isEmpty()) {
            log.debug("[HybridWeb] Brave returned empty (no result or skipped). Using Naver only.");
        }

        List<String> merged = mergeAndLimit(naver, brave, topK); // 한국어는 Naver 우선

        log.info("[Hybrid] Korean parallel search merged: brave={}, naver={}, merged={}",
                brave.size(), naver.size(), merged.size());

        return merged;
    }

    private List<String> searchKoreanNaverAndBrave(String query, int topK) {
        // Naver primary 설정일 때도 구조는 동일하되 로그 메시지만 다르게 두어도 OK
        return searchKoreanBraveAndNaver(query, topK);
    }

    /**
     * primary → secondary 순서로 붙이고, 중복 URL/문장을 제거한 뒤 topK까지 자르는 단순 머지 함수
     */
    /**
     * Merge primary/secondary snippet lists while keeping ordering stable.
     * To avoid over-aggressive truncation (e.g. only 1 snippet surviving),
     * we always keep at least 3 snippets when available.
     */
    private static List<String> mergeAndLimit(List<String> primary, List<String> secondary, int topK) {
        if (primary == null) {
            primary = Collections.emptyList();
        }
        if (secondary == null) {
            secondary = Collections.emptyList();
        }

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(primary);
        merged.addAll(secondary);

        int effectiveTopK = topK <= 0 ? 3 : Math.max(3, topK);

        return merged.stream()
                .limit(effectiveTopK)
                .toList();
    }

    @Override
    public NaverSearchService.SearchResult searchWithTrace(String query, int topK) {

        boolean isKorean = containsHangul(query);

        if (!isKorean) {
            if (isBravePrimary()) {
                return searchWithTraceBraveFirst(query, topK);
            }
            return searchWithTraceNaverFirst(query, topK);
        }

        // 한국어 쿼리: Soak(수세식) Trace 전략 적용
        return searchWithTraceKoreanSmartMerge(query, topK);
    }

    private NaverSearchService.SearchResult searchWithTraceKoreanSmartMerge(String query, int topK) {
        NaverSearchService.SearchResult primary = searchWithTraceKoreanBraveAndNaver(query, topK);
        if (!soakEnabled || primary == null || primary.snippets() == null || primary.snippets().size() >= 3) {
            return primary;
        }

        String extracted = extractKeywords(query);
        if (!StringUtils.hasText(extracted) || extracted.equals(query)) {
            return primary;
        }

        log.info("[Hybrid] Soak(trace) 수세식 발동: '{}' -> '{}'", query, extracted);

        NaverSearchService.SearchResult keywordResult = searchWithTraceKoreanBraveAndNaver(extracted, topK);
        if (keywordResult == null || keywordResult.snippets() == null || keywordResult.snippets().isEmpty()) {
            return primary;
        }

        java.util.List<String> mergedSnippets = mergeAndLimit(
                primary.snippets() != null ? primary.snippets() : java.util.Collections.emptyList(),
                keywordResult.snippets(),
                topK);

        NaverSearchService.SearchTrace mergedTrace = primary.trace();
        if (mergedTrace == null) {
            mergedTrace = new NaverSearchService.SearchTrace();
        }
        if (keywordResult.trace() != null && keywordResult.trace().steps != null) {
            mergedTrace.steps.add(new NaverSearchService.SearchStep(
                    "Soak keyword retry",
                    keywordResult.snippets().size(),
                    mergedSnippets.size(),
                    0));
        }

        return new NaverSearchService.SearchResult(mergedSnippets, mergedTrace);
    }

    private NaverSearchService.SearchResult searchWithTraceKoreanBraveAndNaver(String query, int topK) {

        CompletableFuture<List<String>> braveFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return braveService.searchSnippets(query, topK);
            } catch (Exception e) {
                log.warn("[Hybrid] Brave korean-trace search failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        });

        CompletableFuture<NaverSearchService.SearchResult> naverFuture = CompletableFuture.supplyAsync(() -> {
            try {
                NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("[Hybrid] Naver korean-trace search failed: {}", e.getMessage());
            }
            return new NaverSearchService.SearchResult(Collections.emptyList(), null);
        });

        List<String> brave = Collections.emptyList();
        NaverSearchService.SearchResult naver = new NaverSearchService.SearchResult(Collections.emptyList(), null);

        try {
            brave = braveFuture.get();
            naver = naverFuture.get();

            if (brave != null && brave.size() == 1) {
                String only = brave.get(0);
                if (only != null) {
                    String trimmed = only.trim();
                    if (trimmed.startsWith("{")
                            && trimmed.contains("\"web\"")
                            && trimmed.contains("\"results\"")) {
                        log.warn("[Hybrid] Brave returned single JSON-like snippet ({} chars). " +
                                "BraveSearchService may not be parsing JSON properly.",
                                trimmed.length());
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            log.warn("[Hybrid] Korean parallel trace join failure: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }

        int braveCount = (brave != null) ? brave.size() : 0;
        int naverCount = (naver != null && naver.snippets() != null) ? naver.snippets().size() : 0;

        if (braveCount == 0 && naverCount == 0) {
            log.warn("[Hybrid] Both engines returned 0, expanding Brave topK");
            try {
                if (braveService.isEnabled()) {
                    int expandedK = Math.min(topK * 2, 20); // Brave 최대 20
                    brave = braveService.search(query, expandedK);
                    braveCount = brave != null ? brave.size() : 0;
                    log.info("[Hybrid] Brave expanded search returned {} snippets", braveCount);
                }
            } catch (Exception ex) {
                log.warn("[Hybrid] Brave expanded search failed: {}", ex.getMessage());
            }
        } else if (braveCount == 0 && naverCount > 0) {
            log.info("[Hybrid] Brave returned 0, already have Naver={}", naverCount);
        } else if (naverCount == 0 && braveCount > 0) {
            log.info("[Hybrid] Naver returned 0, already have Brave={}", braveCount);
        }

        List<String> merged = mergeAndLimit(
                naver != null && naver.snippets() != null ? naver.snippets() : Collections.emptyList(),
                brave,
                topK);

        NaverSearchService.SearchTrace trace = naver.trace() != null
                ? naver.trace()
                : new NaverSearchService.SearchTrace();

        trace.steps.add(new NaverSearchService.SearchStep(
                "Parallel: Brave Search (Korean)",
                brave.size(),
                merged.size(),
                0));

        naverCount = (naver != null && naver.snippets() != null) ? naver.snippets().size() : 0;
        log.info("[Hybrid] Korean parallel trace merged: brave={}, naver={}, merged={}",
                brave.size(), naverCount, merged.size());

        return new NaverSearchService.SearchResult(merged, trace);
    }

    private NaverSearchService.SearchResult searchWithTraceBraveFirst(String query, int topK) {

        // 1. Primary: Brave (Trace는 래핑하여 제공)
        try {
            List<String> brave = braveService.searchSnippets(query, topK);
            if (brave != null && !brave.isEmpty()) {
                NaverSearchService.SearchTrace trace = new NaverSearchService.SearchTrace();
                trace.steps.add(new NaverSearchService.SearchStep(
                        "Primary: Brave Search",
                        brave.size(),
                        brave.size(),
                        0));
                log.info("[Hybrid] Brave primary (trace) returned {} snippets", brave.size());
                return new NaverSearchService.SearchResult(brave, trace);
            }
            log.info("[Hybrid] Brave primary (trace) returned no results. Falling back to Naver.");
        } catch (Exception e) {
            log.warn("[Hybrid] Brave primary (trace) failed: {}", e.getMessage());
        }

        // 2. Fallback: Naver (Trace 포함)
        try {
            NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK);
            if (result != null && result.snippets() != null && !result.snippets().isEmpty()) {
                return result;
            }
        } catch (Exception e) {
            log.warn("[Hybrid] Naver trace-search failed: {}", e.getMessage());
        }

        // 3. All failed
        return new NaverSearchService.SearchResult(Collections.emptyList(), null);
    }

    private NaverSearchService.SearchResult searchWithTraceNaverFirst(String query, int topK) {
        // 1. Primary: Naver with trace
        try {
            NaverSearchService.SearchResult result = naverService.searchWithTraceSync(query, topK);
            if (result != null && result.snippets() != null && !result.snippets().isEmpty()) {
                log.info("[Hybrid] Naver primary (trace) returned {} snippets", result.snippets().size());
                return result;
            }
            log.info("[Hybrid] Naver primary (trace) returned no results. Falling back to Brave.");
        } catch (Exception e) {
            log.warn("[Hybrid] Naver primary (trace) failed: {}", e.getMessage());
        }

        // 2. Fallback: Brave (trace synthesized)
        try {
            List<String> brave = braveService.searchSnippets(query, topK);
            if (brave != null && !brave.isEmpty()) {
                NaverSearchService.SearchTrace trace = new NaverSearchService.SearchTrace();
                trace.steps.add(new NaverSearchService.SearchStep(
                        "Fallback: Brave Search",
                        brave.size(),
                        brave.size(),
                        0));
                log.info("[Hybrid] Brave fallback (trace) returned {} snippets", brave.size());
                return new NaverSearchService.SearchResult(brave, trace);
            }
        } catch (Exception e) {
            log.warn("[Hybrid] Brave fallback (trace) failed: {}", e.getMessage());
        }

        // 3. All failed
        return new NaverSearchService.SearchResult(Collections.emptyList(), null);
    }

    @Override
    public String buildTraceHtml(Object traceObj, java.util.List<String> snippets) {
        try {
            if (traceObj instanceof NaverSearchService.SearchTrace trace) {
                // NaverSearchService가 제공하는 HTML 생성기를 그대로 재사용한다.
                return naverService.buildTraceHtml(trace, snippets);
            }
        } catch (Exception e) {
            // trace HTML 생성 실패가 전체 흐름을 막지 않도록 fail-soft 처리
            log.error("[Hybrid] buildTraceHtml error: {}", e.getMessage());
        }
        // 알 수 없는 trace 타입이거나 오류 발생 시 기본 구현으로 폴백
        return WebSearchProvider.super.buildTraceHtml(traceObj, snippets);
    }

    @Override
    public boolean isEnabled() {
        // NaverSearchService 내부에서 키/설정 여부를 판단하므로,
        // 여기서는 "필요 시 시도 가능"한 상태라고 보고 true를 반환한다.
        // BraveSearchService 역시 자체 enabled 플래그를 가지고 있어
        // search(...) 호출 시 내부에서 안전하게 처리된다.
        return true;
    }

    @Override
    public String getName() {
        return "Hybrid(Brave+Naver)";
    }
}