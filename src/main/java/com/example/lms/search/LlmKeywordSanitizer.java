package com.example.lms.search;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.List;

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
@Slf4j
public class LlmKeywordSanitizer {

    private final ChatModel chatModel;

    private static final int MAX_SNIPPETS = 6;          // 토큰 절약용
    private static final String PROMPT = """
        You are a search keyword verifier.
        Decide whether each candidate keyword is **supported** by the given web snippets.
        Output a JSON array like [{"kw":"...","ok":true}, ...]
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

        // 토큰 과다 방지: 앞쪽 N 개만 사용
        String snippetBlock = String.join("\n",
                snippets.stream().limit(MAX_SNIPPETS).toList());

        String prompt = PROMPT.formatted(
                original,
                snippetBlock,
                String.join(", ", candidates)
        );

        try {
            String json = chatModel.chat(prompt);

            // 아주 단순한 파싱 (의존성 줄이기 위해 Jackson 생략)
            List<String> passed = new ArrayList<>();
            for (String line : json.split("[\\[{\\]}]")) {
                if (line.contains("\"ok\":true")) {
                    int s = line.indexOf("\"kw\"");
                    if (s > -1) {
                        String kw = line.substring(line.indexOf('"', s + 4) + 1,
                                line.lastIndexOf('"')).trim();
                        if (StringUtils.hasText(kw)) passed.add(kw);
                    }
                }
            }
            return passed.isEmpty() ? candidates : passed;

        } catch (Exception e) {
            log.warn("[Sanitizer] LLM call failed – bypassing filter", e);
            return candidates;   // fail-open
        }
    }
}
