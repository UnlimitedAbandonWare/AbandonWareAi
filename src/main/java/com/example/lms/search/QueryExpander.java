// src/main/java/com/example/lms/search/QueryExpander.java
package com.example.lms.search;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ─────────────────────────────────────────────────────────
 *  ▸ 1차 웹 스니펫       → BM25 상위 키워드 채굴
 *  ▸ LLM(챗모델) 초안    → 짧은 키워드형 질의 1~2줄
 *  ▸ LLM Self-Check     → 추측/루머 키워드 제거   ★ NEW
 *  ▸ 버전/패치-노트     → 기존 규칙 유지
 *  ▸ 모두 합쳐서 중복 제거·순서 보존 후 반환
 * ─────────────────────────────────────────────────────────
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryExpander {

    private final KeyTermMiner        miner;
    private final ChatModel           chatModel;
    private final LlmKeywordSanitizer sanitizer;              // △ NEW

    /* BM25 파라미터 */
    private static final int EXTRA_TERMS = 3;

    /* 버전-패턴: “1.2” 같은 점표기 버전 */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+)");

    /* LLM 프롬프트(설명 없이 키워드만) */
    private static final String LLM_PROMPT = """
        당신은 검색어 보조 생성기입니다.
        다음 질문을 더 구체적으로 검색할 때 유용할 **한국어 키워드형 질의** 1-2개만 제안하세요.
        - 한 줄에 하나, 문장·설명·접두사 없이 **키워드만** 출력
        질문: %s
        """;

    /* 10 분 캐시 (key = 원질문|첫 스니펫 해시) */
    private static final long CACHE_TTL_MS = 10 * 60 * 1_000L;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public List<String> expand(String original, List<String> snippets) {

        String cacheKey = original + "|" + snippets.hashCode();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }

        /* 순서 보존 + 중복 제거 */
        Set<String> out = new LinkedHashSet<>();
        if (StringUtils.hasText(original)) {
            out.add(original.trim());
        }

        /* ① 버전/패치-노트 확장 */
        Matcher m = VERSION_PATTERN.matcher(original);
        if (m.find()) {
            out.add(original + " 패치 노트");
            out.add(original + " 업데이트 내용");
            out.add(original + " 변경사항");
        }

        /* ② LLM 초안(1-2줄) */
        List<String> llmLines = List.of();
        try {
            String reply = chatModel.chat(LLM_PROMPT.formatted(original));
            llmLines = splitLines(reply);
        } catch (Exception e) {
            log.warn("[QueryExpander] LLM 초안 생성 실패: {}", e.toString());
        }

        /* ③ Self-Check 필터   ★ */
        /* ③ Self-Check: 스니펫 근거 기반 필터  */
        try {
            llmLines = sanitizer.filter(original, snippets, llmLines);
        } catch (Exception e) {
            log.warn("[QueryExpander] Sanitizer 실패, 원본 라인 사용: {}", e.toString());
        }
        out.addAll(llmLines);

        /* ④ BM25 핵심어 확장 */
        miner.topKeyTerms(snippets, EXTRA_TERMS).forEach(kw -> {
            if (StringUtils.hasText(kw)) out.add(original + " " + kw);
        });

        List<String> result = out.stream().collect(Collectors.toList());
        cache.put(cacheKey, new CacheEntry(result));
        return result;
    }

    /* ───────────── 내부 util ───────────── */
    private static List<String> splitLines(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        return Arrays.stream(raw.split("\\R+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private record CacheEntry(List<String> value, long timestamp) {
        CacheEntry(List<String> value) {
            this(value, System.currentTimeMillis());
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
