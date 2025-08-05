// src/main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java
package com.example.lms.service.rag;

import com.example.lms.service.NaverSearchService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;

/**
 * Self‑Ask + 네이버 웹 검색 리트리버 (통합 버전)
 *
 * <pre>
 * 1) LLM이 1차 키워드(최대 3줄) 추출  ← {스터프1} SEARCH_PROMPT
 * 2) 각 키워드로 네이버 검색 → 스니펫 수집
 * 3) 부족하면 BFS(Self‑Ask) 하위 키워드 생성(깊이 maxDepth) ← {스터프2}
 * 4) 중복 제거 뒤 최대 overallTopK개의 {@link Content} 반환
 * </pre>
 */
@Slf4j
// 명시적 생성자를 쓰기 위해 Lombok 자동 생성 제거
@Component
public class SelfAskWebSearchRetriever implements ContentRetriever {

    /* ─────────────── DI ─────────────── */

    private final ChatModel          chatModel;
    private final NaverSearchService searchSvc;

    /* ───────────── 설정값 ───────────── */
    private final int maxDepth;       // Self‑Ask 재귀 깊이
    private final int webTopK;        // 키워드당 검색 스니펫 수
    private final int overallTopK;    // 최종 반환 상한


    /** Executor for parallel Naver searches */
    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors())
    );

    /** 편의 생성자: overallTopK 만 입력받는 케이스 */
    /** 편의 생성자: overallTopK 만 입력받는 케이스 */
    public SelfAskWebSearchRetriever(ChatModel chatModel,
                                     NaverSearchService searchSvc,
                                     int overallTopK) {
        this(chatModel, searchSvc,
                2,                                  // default maxDepth
                Math.max(1, overallTopK / 2),       // default webTopK
                overallTopK);
    }
    /** LangChainConfig 에서 직접 주입받는 5‑인자 생성자 */
    public SelfAskWebSearchRetriever(ChatModel chatModel,
                                     NaverSearchService searchSvc,
                                     int keywordLines,
                                     int webTopK,
                                     int overallTopK) {
        this.chatModel   = chatModel;

        this.searchSvc   = searchSvc;
        this.maxDepth    = keywordLines;
        this.webTopK     = webTopK;
        this.overallTopK = overallTopK;
    }

    /* ───────────── 기본 프롬프트 (⭐ {스터프1} 그대로) ───────────── */
    /* 🔴 “근거 없으면 모른다” 문구 추가 & T=0 */
    private static final String SEARCH_PROMPT = """
        당신은 검색어 생성기입니다.
        사용자 질문을 가장 효과적으로 찾을 수 있는 **짧은 키워드형 질의** 1~3개를 제시하세요.
         - 근거가 명확하지 않으면 ‘모르겠다’라고 답하세요.
        - 각 줄에 **검색어만** 출력 (설명/문장체 금지, "검색어:" 금지, "~입니다" 금지)
        - 필요 시 site:제한을 활용 (예: site:eulji.ac.kr)
        질문: %s
        """;

    /* ───────────── 하위 질문 프롬프트 (⭐ {스터프2}) ───────────── */
    private static final String FOLLOWUP_PROMPT = """
        "%s" 검색어가 여전히 광범위합니다.
        더 구체적이고 정보성을 높일 수 있는 한국어 **키워드형 질의** 1~2개만 제안하세요.
        (한 줄에 하나, 설명/문장체 금지, "검색어:" 금지, "~입니다" 금지)
        """;

    /* ───────────── 정규화 유틸 ───────────── */
    private static final Pattern LEADING_TRAILING_PUNCT =
            Pattern.compile("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$");

    private static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        String s = LEADING_TRAILING_PUNCT.matcher(raw).replaceAll("")   // 앞뒤 특수문자
                .replace("\"", "")                               // 따옴표
                .replace("?", "")                                  // 물음표
                .replaceAll("\\s{2,}", " ")                       // 다중 공백
                .trim();
        // 선언형/접두 제거
        s = s.replaceFirst("^검색어\\s*:\\s*", "");
        s = s.replace("입니다", "");
        return s;
    }

    /* Bean 정의는 별도 @Configuration 클래스에서 해주세요 */

    /* ───────────── ContentRetriever 구현 ───────────── */
    @Override
    public List<Content> retrieve(Query query) {
        /* 1차 요청 */
        List<String> firstSnippets = searchSvc.searchSnippetsSync(query.text(), webTopK);
        // ────────── 0. 질의 복잡도 휴리스틱 ──────────
        boolean enableSelfAsk = query.text().length() > 25
                || query.text().chars().filter(ch -> ch == ' ').count() > 3; // 단어 4개 초과


        /* 🔴 NEW: 스니펫이 전혀 없으면 “정보 부족” 표시 후 즉시 반환 */
        if (firstSnippets.isEmpty()) {
            return List.of(Content.from("[검색 결과 없음]"));
        }

        // Self‑Ask 비활성화면 1차 결과만 반환
        if (!enableSelfAsk) {
            return firstSnippets.stream()
                    .limit(overallTopK)
                    .map(Content::from)
                    .toList();
        }

        /* 확장 쿼리 생성 – BFS(Self-Ask)로 대체 */
        List<String> queries = followUpKeywords(query.text());


        /* 2차/병렬 요청 & 머지 */
        LinkedHashSet<String> merged = new LinkedHashSet<>(firstSnippets);
        for (String sub : queries) {
            merged.addAll(searchSvc.searchSnippetsSync(sub, webTopK));
            if (merged.size() >= overallTopK) break;
        }
        /* 1️⃣ 1차 키워드(최대 3줄) */
        // 사람(의료진/교수) 질의는 고정 시드 추가
        List<String> seeds = new ArrayList<>(basicKeywords(query.text()));
        if (query != null && StringUtils.hasText(query.text()) &&
                query.text().matches(".*(교수|의사|의료진|전문의|님).*")) {
            String base = query.text().replaceAll("\\s+", " ").trim();
            seeds.add("site:eulji.ac.kr "  + base);
            seeds.add(base.replaceAll("\\s*교수님?\\s*", " 교수"));
        }
        Deque<String> queue = new ArrayDeque<>(
                seeds.stream()
                        .map(SelfAskWebSearchRetriever::normalize)
                        .filter(StringUtils::hasText)
                        .toList()
        );
        // 중복 키워드 재검색 방지를 위해 방문 집합을 유지
        Set<String> visited = new HashSet<>(queue);

        /* 2️⃣ BFS(Self‑Ask) + 네이버 검색 */
        LinkedHashSet<String> snippets = new LinkedHashSet<>();
        int depth = 0;

        while (!queue.isEmpty() && snippets.size() < overallTopK && depth <= maxDepth) {
            int levelSize = queue.size();
            List<String> currentKeywords = new ArrayList<>();
            while (levelSize-- > 0) {
                String kw = normalize(queue.poll());
                if (!StringUtils.hasText(kw)) {
                    continue;
                }
                currentKeywords.add(kw);
            }

            // perform Naver searches in parallel for all keywords at this depth
            List<CompletableFuture<List<String>>> futures = new ArrayList<>();
            for (String kw : currentKeywords) {
                log.debug("[SelfAsk][d{}] 검색어: {}", depth, kw);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return searchSvc.searchSnippets(kw, webTopK);
                    } catch (Exception e) {
                        log.warn("Naver 검색 실패: {}", kw, e);
                        return List.of();
                    }
                }, searchExecutor));
            }

            // merge search results and schedule follow‑up keywords
            for (int i = 0; i < currentKeywords.size(); i++) {
                String kw = currentKeywords.get(i);
                List<String> results;
                try {
                    results = futures.get(i).join();
                } catch (Exception e) {
                    log.warn("검색 결과 병합 실패: {}", kw, e);
                    results = List.of();
                }
                results.forEach(snippets::add);
                if (depth + 1 <= maxDepth && snippets.size() < overallTopK) {
                    for (String child : followUpKeywords(kw)) {
                        String norm = normalize(child);
                        // 방문하지 않은 키워드만 큐에 추가
                        if (StringUtils.hasText(norm) && visited.add(norm)) {
                            queue.add(norm);
                        }
                    }
                }
            }
            depth++;
        }

        /* 3️⃣ Content 변환 */
        return snippets.stream()
                .limit(overallTopK)
                .map(Content::from)
                .toList();
    }

    /** 빈 스레드 풀을 정리하여 애플리케이션 종료 시 스레드 누수를 방지 */
    @PreDestroy
    public void close() {
        searchExecutor.shutdown();
    }

    /* ───────────── 프롬프트 Helper ───────────── */

    /** {스터프1} – 얕은 3줄 키워드 */
    private List<String> basicKeywords(String question) {
        String reply;
        try {
            reply = chatModel.chat(SEARCH_PROMPT.formatted(question));
        } catch (Exception e) {
            log.warn("LLM 호출 실패 (basicKeywords)", e);
            return List.of();
        }
        return splitLines(reply);
    }

    /** {스터프2} – Self‑Ask follow‑up 키워드 */
    private List<String> followUpKeywords(String parent) {
        String reply;
        try {
            reply = chatModel.chat(FOLLOWUP_PROMPT.formatted(parent));
        } catch (Exception e) {
            log.warn("LLM 호출 실패 (followUp)", e);
            return List.of();
        }
        return splitLines(reply);
    }

    private static List<String> splitLines(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        return Arrays.stream(raw.split("\\R+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }
}
