package com.example.lms.service.correction;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * OpenAI-Java를 이용해 쿼리를 간단 교정합니다.
 * - 실패/타임아웃/비활성 시 입력 그대로 반환 (fail-open)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMQueryCorrectionService implements QueryCorrectionService {

    private final OpenAiService openAi;

    @Value("${query.correction.enabled:true}")
    private boolean enabled;

    @Value("${query.correction.model:gpt-4o-mini}")
    private String model;

    /** 길이가 너무 길면 교정하지 않고 그대로 사용 */
    @Value("${query.correction.max-length:140}")
    private int maxLength;

    @Override
    public String correct(String input) {
        if (!enabled) return safe(input);
        String s = safe(input);
        if (s.isBlank()) return s;
        if (s.length() > maxLength) return s; // 지나치게 긴 문장 패스

        final String sys = """
                너는 한국어 검색어 교정기야.
                - 맞춤법/띄어쓰기/자주 보이는 오타만 수정해.
                - 의미를 바꾸지 마.
                - 불필요한 설명, 따옴표 없이 '교정된 문장만' 출력해.
                """;

        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model(model)
                .messages(java.util.List.of(
                        new ChatMessage(ChatMessageRole.SYSTEM.value(), sys),
                        new ChatMessage(ChatMessageRole.USER.value(), s)))
                .temperature(0d)
                .topP(0.05d)
                .build();

        try {
            String out = openAi.createChatCompletion(req)
                    .getChoices().get(0).getMessage().getContent();
            if (out == null) return s;
            out = out.trim();
            // 과교정 방지: 결과가 비었거나 너무 다르면 원문 유지
            if (out.isEmpty()) return s;
            return out;
        } catch (Throwable t) {
            log.debug("[QC] correction failed → passthrough: {}", t.toString());
            return s;
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
