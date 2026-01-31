package com.example.lms.service.verification;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * 초안 답변을 컨텍스트와 비교하여 PASS, CORRECTED, INSUFFICIENT 상태로 분류합니다.
 * <p>
 * 빠른 휴리스틱을 먼저 실행하고, 조건이 충족되면 LLM을 이용한 정교한 분류를 시도합니다.
 * LLM 호출이 실패하면 안전하게 휴리스틱 결과로 폴백(Fallback)합니다.
 * </p>
 */
@Service("factStatusClassifier")
@RequiredArgsConstructor
public class FactStatusClassifier {
    private static final Logger log = LoggerFactory.getLogger(FactStatusClassifier.class);

    /** ChatModel은 선택적으로 주입됩니다. 없으면 휴리스틱 분류만 동작합니다. */
    private final ObjectProvider<ChatModel> chatModelProvider;

    private static final String CLASSIFICATION_TEMPLATE = """
        You are a classification model.
        Read the Question, Context, and Draft Answer, then respond with exactly one token that best describes the situation:
        PASS, CORRECTED, or INSUFFICIENT.

        - PASS: The Draft is factually correct and fully supported by the Context.
        - CORRECTED: The Draft has factual errors, exaggerations, or omissions that need correction based on the Context.
        - INSUFFICIENT: The Context is not enough to verify the Draft, or the Draft is empty/meaningless.

        ## QUESTION
        %s

        ## CONTEXT
        %s

        ## DRAFT
        %s
        """;

    /**
     * 휴리스틱과 LLM(사용 가능 시)을 이용해 검증 상태를 분류합니다.
     */
    public FactVerificationStatus classify(String question, String context, String draft, String model) {
        // 1. 강화된 휴리스틱 분류를 먼저 실행합니다.
        FactVerificationStatus heuristicStatus = heuristicClassify(question, context, draft);
        if (heuristicStatus == FactVerificationStatus.INSUFFICIENT) {
            // 정보 부족은 더 검사할 필요 없이 즉시 반환합니다.
            return heuristicStatus;
        }

        // 2. LLM 사용이 가능하면, 정밀 분류를 시도합니다.
        ChatModel llm = chatModelProvider.getIfAvailable();
        if (llm != null && isNotBlank(context) && context.length() >= 80 && isNotBlank(model)) {
            try {
                String prompt = String.format(CLASSIFICATION_TEMPLATE, toNn(question), toNn(context), toNn(draft));
                String rawResponse = callChatModel(llm, prompt);
                FactVerificationStatus llmStatus = parseLabel(rawResponse);
                if (llmStatus != FactVerificationStatus.UNKNOWN) {
                    // LLM이 성공적으로 분류했다면 그 결과를 최종적으로 신뢰합니다.
                    return llmStatus;
                }
            } catch (Exception e) {
                log.debug("FactStatusClassifier LLM 분류 실패, 휴리스틱 결과로 폴백: {}", e.toString());
            }
        }

        // 3. LLM 분류에 실패했거나 조건이 안 되면, 휴리스틱 결과를 최종 결과로 사용합니다.
        return heuristicStatus;
    }

    // --- Private Helper Methods ---

    /**
     * LLM 호출 없이, 간단한 규칙으로 상태를 빠르게 분류하는 휴리스틱 메서드입니다.
     * (V1의 질문-컨텍스트 관련성 로직 통합)
     */
    private FactVerificationStatus heuristicClassify(String question, String context, String draft) {
        if (isBlank(draft) || draft.trim().toLowerCase().contains("정보 없음")) {
            return FactVerificationStatus.INSUFFICIENT;
        }
        if (isBlank(context)) {
            return FactVerificationStatus.INSUFFICIENT;
        }

        // V1의 로직: 질문의 핵심 토큰이 컨텍스트에 없으면 관련 없는 내용일 가능성이 높음
        String[] qTokens = Arrays.stream(toNn(question).toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(t -> t.length() >= 2)
                .toArray(String[]::new);

        if (qTokens.length > 0) {
            String cLower = context.toLowerCase(Locale.ROOT);
            long hits = Arrays.stream(qTokens).filter(cLower::contains).count();
            if (hits == 0) {
                // 컨텍스트가 질문과 전혀 관련 없어 보이면, 교정이 필요하다고 판단
                return FactVerificationStatus.CORRECTED;
            }
        }

        // 위 조건들에 걸리지 않으면 기본적으로 PASS로 간주
        return FactVerificationStatus.PASS;
    }

    /**
     * LLM의 텍스트 응답을 FactVerificationStatus enum으로 파싱합니다.
     */
    private FactVerificationStatus parseLabel(String rawResponse) {
        if (isBlank(rawResponse)) return FactVerificationStatus.INSUFFICIENT;

        String upperResponse = rawResponse.trim().toUpperCase();
        if (upperResponse.startsWith("PASS")) return FactVerificationStatus.PASS;
        if (upperResponse.startsWith("CORRECTED")) return FactVerificationStatus.CORRECTED;
        if (upperResponse.startsWith("INSUFFICIENT")) return FactVerificationStatus.INSUFFICIENT;

        return FactVerificationStatus.INSUFFICIENT;
    }
    // 클래스 내부 private helpers 근처에 추가
    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String toNn(String s) {
        return s == null ? "" : s;
    }

    /**
     * Execute a chat completion using the supplied ChatModel.  This method
     * sends the prompt as a single user message and returns the assistant's
     * text.  It swallows any exceptions and returns an empty string to
     * allow callers to safely fall back to heuristics.
     */
    private String callChatModel(ChatModel llm, String prompt) {
        if (llm == null || prompt == null) return "";
        try {
            var res = llm.chat(UserMessage.from(prompt));
            if (res == null || res.aiMessage() == null) return "";
            var ai = res.aiMessage();
            return ai.text() == null ? "" : ai.text();
        } catch (Exception e) {
            log.debug("[FactStatusClassifier] ChatModel call failed: {}", e.toString());
            return "";
        }
    }
}