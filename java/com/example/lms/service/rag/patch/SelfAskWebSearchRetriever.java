// src/main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java
package com.example.lms.service.rag;

import com.example.lms.service.NaverSearchService;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import com.example.lms.service.rag.QueryUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.lms.service.rag.pre.QueryContextPreprocessor;      // 🆕 전처리기 클래스 import
import com.example.lms.service.rag.detector.GameDomainDetector;       // + 도메인 감지
import com.example.lms.search.TypoNormalizer;                         // NEW: typo normalizer
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.*;                                        // 중복 정리: 한 번만 남김
import jakarta.annotation.PreDestroy;
@Slf4j
@Component                          // ➍
@RequiredArgsConstructor            // ➋ 모든 final 필드 주입
public class SelfAskWebSearchRetriever implements ContentRetriever {

    private final NaverSearchService searchSvc;
    private final ChatModel chatModel;
    @Qualifier("guardrailQueryPreprocessor")
    private final QueryContextPreprocessor preprocessor;
    private final GameDomainDetector domainDetector; // + GENSHIN 감지용

    // Optional typo normalizer for hygiene. Injected if available.
    @Autowired(required = false)
    private TypoNormalizer typoNormalizer;

    /* 선택적 Tavily 폴백(존재 시에만 사용) */
    @Autowired(required = false)
    @Qualifier("tavilyWebSearchRetriever")
    private ContentRetriever tavily;
    /* ---------- 튜닝 가능한 기본값(프로퍼티 주입) ---------- */
    @Value("${selfask.max-depth:2}")                private int maxDepth;                 // Self-Ask 재귀 깊이
    @Value("${selfask.web-top-k:5}")                private int webTopK;                  // 키워드당 검색 스니펫 수
    @Value("${selfask.overall-top-k:10}")           private int overallTopK;              // 최종 반환 상한
    @Value("${selfask.max-api-calls-per-query:8}")  private int maxApiCallsPerQuery;      // 질의당 최대 호출
    @Value("${selfask.followups-per-level:2}")      private int followupsPerLevel;        // 레벨별 추가 키워드
    @Value("${selfask.first-hit-stop-threshold:3}") private int firstHitStopThreshold;    // 1차 검색이 n개 이상이면 종료
    @Value("${selfask.timeout-seconds:12}")         private int selfAskTimeoutSec;        // 레벨 타임박스(초)
    @Value("${selfask.per-request-timeout-ms:5000}") private int perRequestTimeoutMs; // 개별 검색 타임아웃(ms)
    @Value("${selfask.use-llm-followups:false}")     private boolean useLlmFollowups;  // 하위 키워드 LLM 사용 여부
    /**
     * Executor for parallel Naver searches
     */

    private final ExecutorService searchExecutor =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    /** 간이 복잡도 추정 → Self-Ask 깊이(1..3) */
    private int estimateDepthByComplexity(String q) {
        if (!StringUtils.hasText(q)) return 1;
        int len = q.codePointCount(0, q.length());
        long spaces = q.chars().filter(ch -> ch == ' ').count();
        // vs (대소문자 무관) 도 비교/차이 질문의 한 형태이므로 패턴에 포함한다.
        boolean hasWh = q.matches(".*(?i)(누가|언제|어디|무엇|왜|어떻게|비교|차이|원리|vs).*");
        int score = 0;
        if (len > 30) score++;
        if (spaces > 6) score++;
        if (hasWh) score++;
        return Math.min(3, Math.max(1, score)); // 1..3
    }
    /**
     * 편의 생성자(Bean 기본형)
     */
      /* ➎ Lombok이 생성자를 자동 생성하므로
       명시적 생성자 블록을 전부 삭제합니다. */

    /* ───────────── 휴리스틱 키워드 규칙 ───────────── */
    private static final Set<String> STOPWORDS = Set.of(
            "그리고", "또는", "그러나", "하지만", "에서", "으로", "에게", "대한", "관련",
            "무엇", "어떻게", "알려줘", "정리", "설명", "해주세요", "해주세요."
    );

    /* ───────────── 정규화 유틸 ───────────── */
    private static final Pattern LEADING_TRAILING_PUNCT =
            Pattern.compile("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$");

    /**
     * Canonicalize keyword by removing whitespace and lowercasing for duplicate detection.
     */
    private static String canonicalKeyword(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        String s = LEADING_TRAILING_PUNCT.matcher(raw).replaceAll("")   // 앞뒤 특수문자
                .replace("\"", "")                                      // 따옴표
                .replace("?", "")                                       // 물음표
                .replaceAll("\\s{2,}", " ")                             // 다중 공백
                .trim();
        // 선언형/접두 제거
        s = s.replaceFirst("^검색어\\s*:\\s*", "");
        s = s.replace("입니다", "");
        return s;
    }

    /* ───────────── ContentRetriever 구현 ───────────── */
    @Override
    public List<Content> retrieve(Query query) {
        // 입력 검증


        String qText = (query != null) ? query.text() : null;
        // Apply typo normalization if configured
        if (typoNormalizer != null && qText != null) {
            qText = typoNormalizer.normalize(qText);
        }
        // ① Guardrail: 오타 교정/금칙어/중복 정리 (중복 호출 제거 + NPE 가드)
        qText = (preprocessor != null) ? preprocessor.enrich(qText) : qText;
        if (!StringUtils.hasText(qText)) {
            log.debug("[SelfAsk] empty query -> []");
            return List.of();
        }

        /* 1) 빠른 1차 검색 */
        List<String> firstSnippets = safeSearch(qText, webTopK);

        // 질의 복잡도 간단 판정
        boolean enableSelfAsk = qText.length() > 25
                || qText.chars().filter(ch -> ch == ' ').count() > 3
                // '비교', '차이' 또는 ' vs '가 포함되면 Self-Ask가 필요하다.
                || qText.contains("비교")
                || qText.contains("차이")
                || qText.toLowerCase(Locale.ROOT).contains(" vs ");
        // 질의 복잡도 기반 동적 깊이(1..maxDepth)
        final int depthLimit = Math.max(1, Math.min(maxDepth, estimateDepthByComplexity(qText)));

        /* 1‑B) Self‑Ask 조기 종료 결정 (품질 평가는 LLM 키워드 확장에서 수행) */
        if (!enableSelfAsk) {
            if (firstSnippets.isEmpty()) return List.of(Content.from("[검색 결과 없음]"));
            // 단순 질의면서 1차에서 충분히 많이 맞으면(=조기 종료)
            if (firstSnippets.size() >= firstHitStopThreshold) {
                return firstSnippets.stream().limit(overallTopK).map(Content::from).toList();
            }
            // 아니면 얕게 한 번만 확장
            if (depthLimit <= 1) {
                return firstSnippets.stream().limit(overallTopK).map(Content::from).toList();
            }
        }

        // 2) 휴리스틱 키워드 시드 구성 → BFS 확장
        List<String> seeds = new ArrayList<>(basicKeywords(qText)); // 또는 원하는 변수명

        // Seed queue with canonical uniqueness to avoid duplicate/synonym searches
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visitedCanon = new HashSet<>();
        for (String s : seeds) {
            String norm = normalize(s);
            if (StringUtils.hasText(norm)) {
                String canon = canonicalKeyword(norm);
                if (visitedCanon.add(canon)) {
                    queue.add(norm);
                }
            }
        }


        // 3) BFS(Self-Ask) + 네이버 검색
        LinkedHashSet<String> snippets = new LinkedHashSet<>(firstSnippets);
        int depth = 0;
        SearchBudget budget = new SearchBudget(maxApiCallsPerQuery); // ✅ 호출 상한 제어

        while (!queue.isEmpty() && snippets.size() < overallTopK && depth < depthLimit) {
            int levelSize = queue.size();
            List<String> currentKeywords = new ArrayList<>();
            while (levelSize-- > 0) {
                String kw = normalize(queue.poll());
                if (StringUtils.hasText(kw)) currentKeywords.add(kw);
            }


            // 해당 depth의 키워드들을 병렬 검색 (상한 적용)
            List<CompletableFuture<List<String>>> futures = new ArrayList<>();
            for (String kw : currentKeywords) {
                if (!budget.tryConsume()) break; // ✅ 상한
                log.debug("[SelfAsk][d{}] 검색어: {}", depth, kw);
                CompletableFuture<List<String>> f =
                        CompletableFuture.supplyAsync(() -> {
                                    try {
                                        return searchSvc.searchSnippets(kw, webTopK);
                                    } catch (Exception e) {
                                        log.warn("[SelfAsk] 검색 실패(kw={}): {}", kw, e.toString());
                                        return List.<String>of();
                                    }
                                }, searchExecutor)
                                .completeOnTimeout(List.of(), perRequestTimeoutMs, TimeUnit.MILLISECONDS)
                                .exceptionally(ex -> {
                                    log.debug("[SelfAsk] future 실패: {}", ex.toString());
                                    return List.of();
                                });
                futures.add(f);

            }

            // (레벨 타임박스) 모든 future 완료 대기(부분 실패/타임아웃은 무시하고 병합 계속)
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .orTimeout(selfAskTimeoutSec, TimeUnit.SECONDS)
                        .exceptionally(ex -> null)
                        .join();
            } catch (Exception ignore) {
                log.debug("[SelfAsk] level={} 타임아웃 — partial merge 진행", depth);
            }

            // 결과 병합 및 다음 레벨 키워드 생성
            for (int i = 0; i < futures.size(); i++) {
                String kw = i < currentKeywords.size() ? currentKeywords.get(i) : "";
                List<String> results = futures.get(i).getNow(List.of()); // 🔒 비차단/예외 無
                results.forEach(snippets::add);

                if (depth + 1 <= maxDepth && snippets.size() < overallTopK) {
                    int used = 0;
                    // LLM 호출 최소화: 기본은 휴리스틱, 필요 시에만 LLM
                    List<String> children = useLlmFollowups
                            ? followUpKeywords(kw)
                            : heuristicFollowups(kw);
                    for (String child : children) {
                        if (used >= followupsPerLevel) break;  // per-level 제한
                        String norm = normalize(child);
                        String canon = canonicalKeyword(norm);
                        if (StringUtils.hasText(norm) && visitedCanon.add(canon)) {
                            queue.add(norm);
                            used++;
                        }
                    }
                }
            }
            depth++;
        }

        // 3-B) 결과 부족 시 Tavily로 보강
        if (snippets.size() < overallTopK && tavily != null) {
            try {
                int need = Math.max(0, overallTopK - snippets.size());
                // [HARDENING] Always propagate existing query metadata (e.g. session sid) when
                // constructing new Query objects for the Tavily fallback.  This ensures that
                // downstream retrievers enforce per-session isolation and do not pollute
                // transient or public namespaces.  When the original query has no metadata
                // attached, the builder will accept a null and Tavily will treat it as
                // __PRIVATE__ internally.  Avoid the deprecated Query.from API.
                Query fallbackQuery = new dev.langchain4j.rag.query.Query(
                        qText,
                        query != null ? query.metadata() : null);
                tavily.retrieve(fallbackQuery).stream()
                        .map(Content::toString)
                        .filter(StringUtils::hasText)
                        .limit(need)
                        .forEach(snippets::add);
            } catch (Exception e) {
                log.debug("[SelfAsk] Tavily fallback skipped: {}", e.toString());
            }
        }
// 4) Content 변환(비어있을 경우 안전 폴백)
        if (snippets.isEmpty()) {
            if (!firstSnippets.isEmpty()) {
                return firstSnippets.stream()
                        .limit(Math.max(1, Math.min(overallTopK, webTopK)))
                        .map(Content::from)
                        .toList();
            }
            return List.of(Content.from("[검색 결과 없음]"));
        }
        return snippets.stream()
                .limit(overallTopK)
                .map(Content::from)
                .toList();
    }

    /**
     * 빈 스레드 풀 정리
     */
    @PreDestroy
    public void close() {
        searchExecutor.shutdown();
        try {
            if (!searchExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                searchExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            searchExecutor.shutdownNow();
        }
    }

    /* ───────────── 키워드 Helper (휴리스틱) ───────────── */
    /** 얕은 1~3개 시드 키워드 */
    /**
     * LLM 한 번으로 1~3개 핵심 키워드를 추출
     */
    private List<String> basicKeywords(String question) {
        if (!StringUtils.hasText(question)) return List.of();
        String prompt = SEARCH_PROMPT.formatted(question.trim());
        try {
            String reply = chatModel.chat(List.of(
                    SystemMessage.from("당신은 최고의 검색 전문가입니다."),
                    UserMessage.from(prompt)
            )).aiMessage().text();
            return splitLines(reply).stream().limit(3).toList();
        } catch (Exception e) {
            log.warn("LLM keyword generation failed", e);
            return List.of();
        }
    }

    /** 하위 키워드(간단 확장) */
    /**
     * Self‑Ask 하위 키워드를 LLM으로 1~2개 생성
     */
    private List<String> followUpKeywords(String parent) {
        if (!StringUtils.hasText(parent)) return List.of();
        String prompt = FOLLOWUP_PROMPT.formatted(parent.trim());
        try {
            String reply = chatModel.chat(List.of(
                    SystemMessage.from("검색어를 더 구체화하세요."),
                    UserMessage.from(prompt)
            )).aiMessage().text();
            return splitLines(reply).stream().limit(followupsPerLevel).toList();
        } catch (Exception e) {
            log.warn("LLM follow‑up generation failed", e);
            return List.of();
        }
    }

    /* ───────────── LLM 프롬프트 상수 및 검색 예산 ───────────── */

    private static final String SEARCH_PROMPT = """
            당신은 검색어 생성기입니다.
            사용자 질문을 가장 효과적으로 찾을 수 있는 **짧은 키워드형 질의** 1~3개를 제시하세요.
            - 설명이나 접두사는 금지하고, 한 줄에 검색어만 출력하세요.
            질문: %s
            """;

    private static final String FOLLOWUP_PROMPT = """
            "%s" 검색어가 광범위합니다.
            더 구체적이고 정보성을 높일 **키워드형 질의** 1~2개만 한국어로 제안하세요.
            (한 줄에 하나, 설명 금지)
            """;

    /**
     * 질의별 API 호출 예산 관리
     */
    private static final class SearchBudget {
        private int left;

        SearchBudget(int max) {
            this.left = Math.max(0, max);
        }

        boolean tryConsume() {
            return left-- > 0;
        }

        int remaining() {
            return Math.max(0, left);
        }
    }


    private List<String> safeSearch(String q, int k) {
        try {
            if (!StringUtils.hasText(q)) return List.of();
            return searchSvc.searchSnippets(q, k);
        } catch (Exception e) {
            log.warn("초기 검색 실패: {}", q, e);
            return Collections.emptyList();
        }
    }
    /** LLM 호출 없이 간단 확장(최대 followupsPerLevel개) — 도메인 민감 */
    private List<String> heuristicFollowups(String parent) {
        if (!StringUtils.hasText(parent)) return List.of();
        boolean isGenshin = (domainDetector != null)
                && "GENSHIN".equalsIgnoreCase(domainDetector.detect(parent));
        List<String> cands = isGenshin
                ? List.of(parent + " 파티 조합", parent + " 시너지", parent + " 상성", parent + " 추천 파티")
                : List.of(parent + " 개요", parent + " 핵심 포인트");
        return cands.stream()
                .limit(Math.max(1, followupsPerLevel))
                .toList();
    }

    /**
     * 로컬 휴리스틱: 실시간 패치/공지 질의 여부
     */


    private static List<String> splitLines(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        return Arrays.stream(raw.split("\\R+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private List<String> rephrase(String q) {
        if (q == null || q.isBlank()) return List.of();
        // 필요하면 더 똑똑하게 확장
        return List.of(q, q + " 후기", q + " 정리", q + " 요약");
    }




    // [HARDENING] ensure SID metadata is present on every query
    private dev.langchain4j.rag.query.Query ensureSidMetadata(dev.langchain4j.rag.query.Query original, String sessionKey) {
        // Rebuild the query with the correct SID metadata using QueryUtils.  The
        // history is omitted (null) in this context.
        return QueryUtils.buildQuery(original.text(), sessionKey, null);
    }

}