// src/main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java
package com.example.lms.service.rag;

import com.example.lms.service.NaverSearchService;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.time.Duration;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;                       // 🆕 @Component 찾도록 추가

import com.example.lms.service.rag.pre.QueryContextPreprocessor;      // 🆕 전처리기 클래스 import

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

    /* ---------- 튜닝 가능한 기본값 (중복 선언 제거) ---------- */
    private int maxDepth  = 2;   // Self-Ask 재귀 깊이
    private int webTopK   = 5;   // 키워드당 검색 스니펫 수

    private int overallTopK            = 10;  // 최종 반환 상한
    private int maxApiCallsPerQuery    = 8;   // 질의당 최대 호출
    private int followupsPerLevel      = 2;   // 레벨별 추가 키워드
    private int firstHitStopThreshold  = 3;   // 1차 검색이 n개 이상이면 종료

    /**
     * Executor for parallel Naver searches
     */

    private final ExecutorService searchExecutor =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));

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

        // ① Guardrail 전처리 적용 ------------------------------------------------
        qText = preprocessor.enrich(qText);          // ➊ null-safe 보장은 PreProcessor 내부 책임
        if (!StringUtils.hasText(qText)) {
            log.debug("[SelfAsk] empty query -> []");
            return List.of();
        }

        /* 1) 빠른 1차 검색 */
        List<String> firstSnippets = safeSearch(qText, webTopK);

        // 질의 복잡도 간단 판정
        boolean enableSelfAsk = qText.length() > 25
                || qText.chars().filter(ch -> ch == ' ').count() > 3;

        /* 1‑B) Self‑Ask 조기 종료 결정 (품질 평가는 LLM 키워드 확장에서 수행) */
        if (!enableSelfAsk) {
            if (firstSnippets.isEmpty()) return List.of(Content.from("[검색 결과 없음]"));
            return firstSnippets.stream().limit(overallTopK).map(Content::from).toList();
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
        int apiCalls = 0; // ✅ 호출 상한 제어

        while (!queue.isEmpty() && snippets.size() < overallTopK && depth <= maxDepth) {
            int levelSize = queue.size();
            List<String> currentKeywords = new ArrayList<>();
            while (levelSize-- > 0) {
                String kw = normalize(queue.poll());
                if (StringUtils.hasText(kw)) currentKeywords.add(kw);
            }

            // 해당 depth의 키워드들을 병렬 검색 (상한 적용)
            List<CompletableFuture<List<String>>> futures = new ArrayList<>();
            for (String kw : currentKeywords) {
                if (apiCalls >= maxApiCallsPerQuery) break; // ✅ 상한
                log.debug("[SelfAsk][d{}] 검색어: {}", depth, kw);
                CompletableFuture<List<String>> f =
                        CompletableFuture.supplyAsync(() -> {
                                    try {
                                        return searchSvc.searchSnippets(kw, webTopK);
                                    } catch (Exception e) {
                                        log.warn("Naver 검색 실패: {}", kw, e);
                                        return List.<String>of();
                                    }
                                }, searchExecutor)
                                .orTimeout(7, TimeUnit.SECONDS);
                futures.add(f);
                apiCalls++; // ✅ 호출 카운트 증가
            }

            // 결과 병합 및 다음 레벨 키워드 생성
            for (int i = 0; i < futures.size(); i++) {
                String kw = i < currentKeywords.size() ? currentKeywords.get(i) : "";
                List<String> results;
                try {
                    results = futures.get(i).join();
                } catch (Exception e) {
                    log.warn("검색 결과 병합 실패: {}", kw, e);
                    results = List.of();
                }
                results.forEach(snippets::add);

                if (depth + 1 <= maxDepth && snippets.size() < overallTopK) {
                    int used = 0;
                    for (String child : followUpKeywords(kw)) {
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

        // 4) Content 변환
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



}
