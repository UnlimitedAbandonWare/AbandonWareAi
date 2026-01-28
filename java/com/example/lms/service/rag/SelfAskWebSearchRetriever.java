// src/main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java
package com.example.lms.service.rag;

import com.example.lms.search.provider.WebSearchProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.regex.Pattern;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


// SelfAskWebSearchRetriever.java


import com.example.lms.service.rag.pre.QueryContextPreprocessor;      // 🆕 전처리기 클래스 import
import com.example.lms.service.rag.detector.GameDomainDetector;       // + 도메인 감지
import com.example.lms.search.TypoNormalizer;                         // NEW: typo normalizer
import java.util.concurrent.*;                                        // 중복 정리: 한 번만 남김
@Component                          // ➍
@RequiredArgsConstructor            // ➋ 모든 final 필드 주입
public class SelfAskWebSearchRetriever implements ContentRetriever {
    private static final Logger log = LoggerFactory.getLogger(SelfAskWebSearchRetriever.class);
    private final WebSearchProvider webSearchProvider;
    @Qualifier("fastChatModel")
    private final ChatModel chatModel;
        private final QueryContextPreprocessor preprocessor;
    private final GameDomainDetector domainDetector; // + GENSHIN 감지용

    // Optional typo normalizer for hygiene. Injected if available.
    @Autowired(required = false)
    private TypoNormalizer typoNormalizer;

    @Autowired(required = false)
    private com.example.lms.infra.resilience.NightmareBreaker nightmareBreaker;

    /* 선택적 Tavily 폴백(존재 시에만 사용) */
    @Autowired(required = false)
    @Qualifier("tavilyWebSearchRetriever")
    private ContentRetriever tavily;
    /* ---------- 튜닝 가능한 기본값(프로퍼티 주입) ---------- */
    @Value("${selfask.max-depth:2}")                private int maxDepth;                 // Self-Ask 재귀 깊이
    @Value("${selfask.web-top-k:8}")                private int webTopK;                  // 키워드당 검색 스니펫 수
    @Value("${selfask.overall-top-k:10}")           private int overallTopK;              // 최종 반환 상한
    @Value("${selfask.max-api-calls-per-query:8}")  private int maxApiCallsPerQuery;      // 질의당 최대 호출
    @Value("${selfask.followups-per-level:2}")      private int followupsPerLevel;        // 레벨별 추가 키워드
    @Value("${selfask.first-hit-stop-threshold:3}") private int firstHitStopThreshold;    // 1차 검색이 n개 이상이면 종료
    @Value("${selfask.timeout-seconds:12}")         private int selfAskTimeoutSec;        // 레벨 타임박스(초)
    @Value("${selfask.per-request-timeout-ms:5000}") private int perRequestTimeoutMs; // 개별 검색 타임아웃(ms)
    @Value("${selfask.use-llm-followups:false}")     private boolean useLlmFollowups;  // 하위 키워드 LLM 사용 여부
    @Value("${selfask.use-llm-seeds:false}")         private boolean useLlmSeeds;      // 시드 키워드 LLM 사용 여부
    /**
     * Search I/O executor.
     *
     * <p>Do not use {@code ForkJoinPool.commonPool} for blocking I/O (web search/HTTP).
     */
    @Autowired
    @Qualifier("searchIoExecutor")
    private ExecutorService searchExecutor;
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
        java.util.Map<String, Object> meta = toMetaMap(query);
        meta.putIfAbsent("purpose", "WEB_SEARCH");
        // Apply typo normalization if configured
        if (typoNormalizer != null && qText != null) {
            qText = typoNormalizer.normalize(qText);
        }
        // ① Guardrail: 오타 교정/금칙어/중복 정리 (중복 호출 제거 + NPE 가드)
        qText = (preprocessor != null) ? preprocessor.enrich(qText, meta) : qText;
        if (!StringUtils.hasText(qText)) {
            log.debug("[SelfAsk] empty query -> []");
            return List.of();
        }

        int reqWebTopK = metaInt(meta, "webTopK", this.webTopK);
        long webBudgetMs = metaLong(meta, "webBudgetMs", -1L);
        boolean allowWeb = metaBool(meta, "allowWeb", true);
        if (!allowWeb) {
            return java.util.List.of();
        }
        boolean enableSelfAskHint = metaBool(meta, "enableSelfAsk", true);
        boolean nightmareMode = metaBool(meta, "nightmareMode", false);
        boolean auxLlmDown = metaBool(meta, "auxLlmDown", false);

        int reqPerRequestTimeoutMs = this.perRequestTimeoutMs;
        int reqSelfAskTimeoutSec = this.selfAskTimeoutSec;
        if (webBudgetMs > 0) {
            reqPerRequestTimeoutMs = (int) Math.min((long) reqPerRequestTimeoutMs, Math.max(300L, webBudgetMs));
            reqSelfAskTimeoutSec = (int) Math.min((long) reqSelfAskTimeoutSec, Math.max(1L, (webBudgetMs + 999L) / 1000L));
        }

        /* 1) 빠른 1차 검색 */
        java.util.List<String> firstSnippets = safeSearch(qText, reqWebTopK);

        // 질의 복잡도 간단 판정
        boolean enableSelfAsk = qText.length() > 25
                || qText.chars().filter(ch -> ch == ' ').count() > 3
                // '비교', '차이' 또는 ' vs '가 포함되면 Self-Ask가 필요하다.
                || qText.contains("비교")
                || qText.contains("차이")
                || qText.toLowerCase(Locale.ROOT).contains(" vs ");
        if (!enableSelfAskHint || nightmareMode) {
            enableSelfAsk = false;
        }
        final boolean useLlmSeedsHere = this.useLlmSeeds && enableSelfAskHint && !nightmareMode && !auxLlmDown;
        final boolean useLlmFollowupsHere = this.useLlmFollowups && enableSelfAskHint && !nightmareMode && !auxLlmDown;

        // 질의 복잡도 기반 동적 깊이(1..maxDepth)
        final int depthLimit = Math.max(1, Math.min(maxDepth, estimateDepthByComplexity(qText)));

        /* 1-B) Self-Ask 조기 종료 결정 (품질 평가는 LLM 키워드 확장에서 수행) */
        if (!enableSelfAsk) {
            if (firstSnippets.isEmpty()) return List.of();
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
        java.util.List<String> seeds = new java.util.ArrayList<>(basicKeywords(qText, useLlmSeedsHere));

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
                List<Future<List<String>>> futures = new ArrayList<>();
            for (String kw : currentKeywords) {
                if (!budget.tryConsume()) break; // ✅ 상한
                log.debug("[SelfAsk][d{}] 검색어: {}", depth, kw);
                Future<java.util.List<String>> f = searchExecutor.submit(() -> safeSearch(kw, reqWebTopK));
                futures.add(f);

            }

            // Level-wide budget for this depth.
            // Important: we do NOT rely on CompletableFuture.orTimeout(), because it only times out the
            // future result and may leave the underlying work running ("zombie" tasks).
            final long levelDeadlineMs = System.currentTimeMillis() + java.util.concurrent.TimeUnit.SECONDS.toMillis(reqSelfAskTimeoutSec);

            // 결과 병합 및 다음 레벨 키워드 생성
            for (int i = 0; i < futures.size(); i++) {
                String kw = i < currentKeywords.size() ? currentKeywords.get(i) : "";
                List<String> results = getWithHardTimeout(
                        futures.get(i),
                        Math.min(reqPerRequestTimeoutMs, Math.max(0L, levelDeadlineMs - System.currentTimeMillis())),
                        kw
                );
                results.forEach(snippets::add);

                if (depth + 1 <= maxDepth && snippets.size() < overallTopK) {
                    int used = 0;
                    // LLM 호출 최소화: 기본은 휴리스틱, 필요 시에만 LLM
                    java.util.List<String> children = useLlmFollowupsHere
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

            // Cancel any straggling tasks once this depth budget is exhausted.
            for (Future<List<String>> f : futures) {
                if (f != null && !f.isDone()) {
                    // Interrupt Hygiene: never interrupt pooled workers (cancel(false) only).
                    f.cancel(false);
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
                // [HARDENING] use builder API to propagate metadata and avoid deprecated Query.from
                Query fallbackQuery = dev.langchain4j.rag.query.Query.builder()
                        .text(qText)
                        .metadata((query != null ? query.metadata() : null))
                        .build();
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
                        .limit(Math.max(1, Math.min(overallTopK, reqWebTopK)))
                        .map(Content::from)
                        .toList();
            }
            // No snippets found at all. Return an empty list instead of a placeholder to avoid polluting the vector store.
            return java.util.List.of();
        }
        return snippets.stream()
                .limit(overallTopK)
                .map(Content::from)
                .toList();
    }



    // ---- per-request metadata helpers (OrchestrationHints bridge) ----
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> toMetaMap(Query query) {
        if (query == null || query.metadata() == null) return java.util.Collections.emptyMap();
        Object meta = query.metadata();
        if (meta instanceof java.util.Map<?, ?> raw) {
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            for (java.util.Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        try {
            java.lang.reflect.Method m = meta.getClass().getMethod("asMap");
            Object v = m.invoke(meta);
            if (v instanceof java.util.Map<?, ?> m2) {
                java.util.Map<String, Object> out = new java.util.HashMap<>();
                for (java.util.Map.Entry<?, ?> e : m2.entrySet()) {
                    if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            }
        } catch (NoSuchMethodException ignore) {
            try {
                java.lang.reflect.Method m = meta.getClass().getMethod("map");
                Object v = m.invoke(meta);
                if (v instanceof java.util.Map<?, ?> m2) {
                    java.util.Map<String, Object> out = new java.util.HashMap<>();
                    for (java.util.Map.Entry<?, ?> e : m2.entrySet()) {
                        if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    return out;
                }
            } catch (Exception ignore2) {
                return java.util.Collections.emptyMap();
            }
        } catch (Exception ignore) {
            return java.util.Collections.emptyMap();
        }
        return java.util.Collections.emptyMap();
    }

    private static int metaInt(java.util.Map<String, Object> meta, String key, int def) {
        if (meta == null) return def;
        Object v = meta.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignore) {}
        }
        return def;
    }

    private static long metaLong(java.util.Map<String, Object> meta, String key, long def) {
        if (meta == null) return def;
        Object v = meta.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (Exception ignore) {}
        }
        return def;
    }

    private static boolean metaBool(java.util.Map<String, Object> meta, String key, boolean def) {
        if (meta == null) return def;
        Object v = meta.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("true") || t.equals("1") || t.equals("yes")) return true;
            if (t.equals("false") || t.equals("0") || t.equals("no")) return false;
        }
        return def;
    }
    // Executor lifecycle is managed by Spring (SearchExecutorConfig.searchIoExecutor).

    /* ───────────── 키워드 Helper (휴리스틱) ───────────── */
    /** 얕은 1~3개 시드 키워드 */
    /**
     * LLM 한 번으로 1~3개 핵심 키워드를 추출
     */
    private List<String> basicKeywords(String question, boolean allowLlm) {
        if (!StringUtils.hasText(question)) return List.of();
        if (!allowLlm) return heuristicSeeds(question);

        String prompt = SEARCH_PROMPT.formatted(question.trim());
        String reply = "";
        try {
            if (nightmareBreaker != null) {
                reply = nightmareBreaker.execute(
                        com.example.lms.infra.resilience.NightmareKeys.SELFASK_SEED,
                        prompt,
                        () -> chatModel.chat(List.of(
                                SystemMessage.from("당신은 최고의 검색 전문가입니다."),
                                UserMessage.from(prompt)
                        )).aiMessage().text(),
                        com.example.lms.infra.resilience.FriendShieldPatternDetector::looksLikeSilentFailure,
                        () -> ""
                );
            } else {
                reply = chatModel.chat(List.of(
                        SystemMessage.from("당신은 최고의 검색 전문가입니다."),
                        UserMessage.from(prompt)
                )).aiMessage().text();
            }
        } catch (Exception e) {
            log.warn("LLM keyword generation failed", e);
        }

        List<String> out = splitLines(reply).stream().limit(3).toList();
        if (out == null || out.isEmpty()) return heuristicSeeds(question);
        return out;
    }

    private List<String> heuristicSeeds(String question) {
        if (!StringUtils.hasText(question)) return List.of();
        String cleaned = question.replaceAll("[\\p{Punct}]+", " ").trim();
        if (!StringUtils.hasText(cleaned)) {
            return List.of(question.trim());
        }

        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
        for (String t : cleaned.split("\\s+")) {
            String norm = normalize(t);
            if (!StringUtils.hasText(norm)) continue;
            String canon = canonicalKeyword(norm);
            if (!StringUtils.hasText(canon)) continue;
            uniq.add(norm);
            if (uniq.size() >= 3) break;
        }
        if (uniq.isEmpty()) return List.of(question.trim());
        return uniq.stream().limit(3).toList();
    }

    /**
     * Self-Ask 하위 키워드를 LLM으로 1~2개 생성
     */
    private List<String> followUpKeywords(String parent) {
        if (!StringUtils.hasText(parent)) return List.of();
        String prompt = FOLLOWUP_PROMPT.formatted(parent.trim());
        String reply = "";
        try {
            if (nightmareBreaker != null) {
                reply = nightmareBreaker.execute(
                        com.example.lms.infra.resilience.NightmareKeys.SELFASK_FOLLOWUP,
                        prompt,
                        () -> chatModel.chat(List.of(
                                SystemMessage.from("검색어를 더 구체화하세요."),
                                UserMessage.from(prompt)
                        )).aiMessage().text(),
                        com.example.lms.infra.resilience.FriendShieldPatternDetector::looksLikeSilentFailure,
                        () -> ""
                );
            } else {
                reply = chatModel.chat(List.of(
                        SystemMessage.from("검색어를 더 구체화하세요."),
                        UserMessage.from(prompt)
                )).aiMessage().text();
            }
        } catch (Exception e) {
            log.warn("LLM follow-up generation failed", e);
        }

        List<String> out = splitLines(reply).stream().limit(Math.max(1, followupsPerLevel)).toList();
        if (out == null || out.isEmpty()) return heuristicFollowups(parent);
        return out;
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

    /**
     * Hard timeout: on timeout, actively cancel the running task.
     */
    private List<String> getWithHardTimeout(Future<List<String>> future, long timeoutMs, String keyword) {
        if (future == null) {
            return List.of();
        }
        if (timeoutMs <= 0) {
            // Best-effort cancel without interrupt to avoid cancellation toxicity.
            future.cancel(false);
            return List.of();
        }
        try {
            List<String> v = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return (v != null) ? v : List.of();
        } catch (TimeoutException te) {
            // Best-effort cancel without interrupt to avoid poisoning pooled workers.
            future.cancel(false);
            log.debug("[SelfAsk] hard timeout ({}ms) keyword={}", timeoutMs, keyword);
            return List.of();
        } catch (InterruptedException ie) {
            // Interrupt Hygiene: consume interrupt flag (parry) and fail-soft.
            Thread.interrupted();
            future.cancel(false);
            log.debug("[SelfAsk] interrupted while waiting keyword={} (interrupt consumed)", keyword);
            return List.of();
        } catch (Exception e) {
            future.cancel(false);
            log.debug("[SelfAsk] keyword search failed: {} -> {}", keyword, e.toString());
            return List.of();
        }
    }

    private List<String> safeSearch(String q, int k) {
        try {
            if (!StringUtils.hasText(q)) return List.of();
            return webSearchProvider.search(q, k);
        } catch (Exception e) {
            log.warn("초기 검색 실패: {}", q, e);
            return Collections.emptyList();
        }
    }
    /** LLM 호출 없이 간단 확장(최대 followupsPerLevel개) - 도메인 민감 */
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
        var md = (original.metadata() != null)
                ? original.metadata()
                : dev.langchain4j.data.document.Metadata.from(
                java.util.Map.of(com.example.lms.service.rag.LangChainRAGService.META_SID, sessionKey));
        try {
            return dev.langchain4j.rag.query.Query.builder()
                    .text(original.text())
                    .metadata(md)
                    .build();
        } catch (Throwable t) {
            try {
                // Fallback: 일부 환경에서만 존재할 수 있는 생성자(없으면 원본 반환)
                var ctor = dev.langchain4j.rag.query.Query.class
                        .getDeclaredConstructor(String.class, dev.langchain4j.data.document.Metadata.class);
                ctor.setAccessible(true);
                return ctor.newInstance(original.text(), md);
            } catch (Throwable t2) {
                return original;
            }
        }
    }

}