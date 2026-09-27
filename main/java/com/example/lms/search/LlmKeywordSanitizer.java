package com.example.lms.search;

import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * LLM-as-a-Judge:  웹 스니펫 증거로 키워드 검증 (도메인 중립)
 *
 *  ✅  스니펫에 근거 있음
 *  ❌  스니펫에 단 한 번도 안 나옴 → speculative
 *
 *  ※ 스니펫이 부족하거나 LLM 호출 실패 시 fail-open
 */
@Service
@RequiredArgsConstructor
public class LlmKeywordSanitizer {
    private static final Logger log = LoggerFactory.getLogger(LlmKeywordSanitizer.class);
    private static final Pattern APPROVED_KEYWORD = Pattern.compile(
            "\\\"kw\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"ok\\\"\\s*:\\s*true");

    @Qualifier("judgeChatModel")
    private final ChatModel judgeChatModel;

    private static final int MAX_SNIPPETS = 6;          // 토큰 절약용
    private static final String PROMPT = """
        You are a search keyword verifier.
        Decide whether each candidate keyword is **supported** by the given web snippets.
        Output a JSON array like [{"kw":"/* ... */","ok":true}, /* ... *&#47;]
        ---
        ORIGINAL_QUESTION:
        %s

        WEB_SNIPPETS:
        %s

        CANDIDATES:
        %s
        """;

    /**
     * @param original   사용자 원 질문
     * @param snippets   검색으로 얻은 스니펫 모음
     * @param candidates LLM 이 제안한 키워드 후보
     */
    public List<String> filter(String original,
                               List<String> snippets,
                               List<String> candidates) {

        if (candidates == null || candidates.isEmpty()) return candidates;
        if (snippets == null || snippets.size() <= 1)   return candidates; // 근거 부족 → 패스

        var gctx = GuardContextHolder.get();
        if (gctx != null && (gctx.isSensitiveTopic() || gctx.planBool("memory.forceOff", false))) {
            return candidates;
        }

        // 토큰 과다 방지: 앞쪽 N 개만 사용
        String snippetBlock = String.join("\n",
                snippets.stream().limit(MAX_SNIPPETS).toList());

        String keywordVerifierPrompt = PROMPT.formatted(
                safePromptField(original, 800),
                safePromptField(snippetBlock, 2_000),
                safePromptField(String.join(", ", candidates), 800)
        );

        try {
            String json = judgeChatModel.chat(java.util.List.of(dev.langchain4j.data.message.UserMessage.from(keywordVerifierPrompt)))
                    .aiMessage().text();

            // 의존성 추가 없이 prompt contract의 {"kw":"...","ok":true} 항목만 추출한다.
            List<String> passed = new ArrayList<>();
            Matcher approvedKeyword = APPROVED_KEYWORD.matcher(json);
            while (approvedKeyword.find()) {
                String kw = approvedKeyword.group(1).trim();
                if (StringUtils.hasText(kw) && candidates.contains(kw)) {
                    passed.add(kw);
                }
            }
            return passed.isEmpty() ? candidates : passed;

        } catch (Exception e) {
            log.warn("[Sanitizer] LLM call failed - bypassing filter type={} errorHash={} errorLength={}",
                    e.getClass().getSimpleName(),
                    SafeRedactor.hashValue(messageOf(e)),
                    messageLength(e));
            return candidates;   // fail-open
        }
    }

    private static String safePromptField(String value, int maxChars) {
        String safe = SafeRedactor.safeMessage(value, maxChars);
        return safe == null ? "" : safe;
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message.length();
    }
}
