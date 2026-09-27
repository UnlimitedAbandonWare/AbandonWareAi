// src/main/java/com/example/lms/search/QueryExpander.java
package com.example.lms.search;

import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




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
public class QueryExpander {
    private static final Logger log = LoggerFactory.getLogger(QueryExpander.class);

    private final KeyTermMiner        miner;
    @Qualifier("exploreChatModel")
    private final ChatModel exploreChatModel;
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
        - **인물/캐릭터/지역 등에 대해, 질문에 없는 원소/무기/조직/세계관 설정을 새로 만들지 마세요.**
        - **확실하지 않은 정보(속성, 직업, 연도 등)는 포함하지 말고, 중립적인 키워드만 사용하세요.**
        질문: %s
        """;

    /* 10 분 캐시 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1_000L;
    private static final int CACHE_MAX_ENTRIES = 256;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);

    public List<String> expand(String original, List<String> snippets) {

        String cacheKey = cacheKey(original, snippets);
        CacheEntry cached = cached(cacheKey, System.currentTimeMillis());
        if (cached != null) {
            return new ArrayList<>(cached.value());
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
        var gctx = GuardContextHolder.get();
        boolean forceNoLlm = gctx != null && (gctx.isSensitiveTopic() || gctx.planBool("memory.forceOff", false));
        if (!forceNoLlm) {
            try {
                String reply = exploreChatModel.chat(java.util.List.of(dev.langchain4j.data.message.UserMessage.from(LLM_PROMPT.formatted(original))))
                        .aiMessage().text();
                llmLines = splitLines(reply);
            } catch (Exception e) {
                log.warn("[QueryExpander] LLM suggestion generation failed. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            }
        } else {
            log.debug("[QueryExpander] sensitive/forceOff -> skip LLM expansion");
        }

        /* ③ Self-Check 필터   ★ */
        /* ③ Self-Check: 스니펫 근거 기반 필터  */
        try {
            llmLines = sanitizer.filter(original, snippets, llmLines);
        } catch (Exception e) {
            log.warn("[QueryExpander] sanitizer failed, using original lines. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
        out.addAll(llmLines);

        /* ④ BM25 핵심어 확장 */
        miner.topKeyTerms(snippets, EXTRA_TERMS).forEach(kw -> {
            if (StringUtils.hasText(kw)) out.add(original + " " + kw);
        });

        List<String> result = out.stream().collect(Collectors.toList());
        cache(cacheKey, result, System.currentTimeMillis());
        return result;
    }

    /* ───────────── 내부 util ───────────── */
    private CacheEntry cached(String key, long now) {
        synchronized (cache) {
            CacheEntry entry = cache.get(key);
            if (entry != null && entry.isExpired(now)) {
                cache.remove(key);
                return null;
            }
            return entry;
        }
    }

    private void cache(String key, List<String> value, long now) {
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
            cache.put(key, new CacheEntry(List.copyOf(value), now));
            while (cache.size() > CACHE_MAX_ENTRIES) {
                Iterator<String> eldest = cache.keySet().iterator();
                eldest.next();
                eldest.remove();
            }
        }
    }

    private static String cacheKey(String original, List<String> snippets) {
        MessageDigest digest = sha256();
        updateDigestPart(digest, original);
        if (snippets == null) {
            digest.update((byte) 0);
        } else {
            digest.update((byte) 1);
            for (String snippet : snippets) {
                updateDigestPart(digest, snippet);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void updateDigestPart(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static List<String> splitLines(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        return Arrays.stream(raw.split("\\R+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private record CacheEntry(List<String> value, long timestamp) {
        boolean isExpired(long now) {
            return now - timestamp > CACHE_TTL_MS;
        }
    }
}
