package com.example.lms.service.verification;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service("factStatusClassifier")
@RequiredArgsConstructor
public class FactStatusClassifier {

    /** 선택적 주입: 없으면 휴리스틱만 사용 */
    private final ObjectProvider<OpenAiService> openAiProvider;

    private static final String CLS_TEMPLATE = """
        You are a classification model.
        Read the Question / Context / Draft and answer with exactly one token:
        PASS, CORRECTED or INSUFFICIENT.

        ## QUESTION
        %s

        ## CONTEXT
        %s

        ## DRAFT
        %s
        """;

    /**
     * 간단 휴리스틱 + (가능 시) OpenAI 분류 호출.
     * - draft 비어있음/“정보 없음” 포함 → INSUFFICIENT
     * - 컨텍스트 짧음(<80자) → PASS (보수적 허용)
     * - 그 외: OpenAI로 1토큰 분류 시도 → 실패 시 휴리스틱 결과 유지
     */
    public FactVerificationStatus classify(String q, String ctx, String draft, String model) {
        // 1) 휴리스틱
        FactVerificationStatus heuristic = heuristicClassify(ctx, draft);
        if (heuristic != FactVerificationStatus.PASS) {
            return heuristic; // INSUFFICIENT 즉시 반환
        }

        // 컨텍스트가 충분히 길면 LLM 분류 시도
        if (!isBlank(ctx) && ctx.length() >= 80) {
            OpenAiService openAi = openAiProvider.getIfAvailable();
            if (openAi != null && !isBlank(model)) {
                try {
                    String prompt = String.format(CLS_TEMPLATE, nz(q), nz(ctx), nz(draft));
                    ChatCompletionRequest req = ChatCompletionRequest.builder()
                            .model(model)
                            .messages(List.of(new ChatMessage(ChatMessageRole.SYSTEM.value(), prompt)))
                            .temperature(0d)
                            .topP(0d)
                            .maxTokens(1)
                            .build();

                    String raw = openAi.createChatCompletion(req)
                            .getChoices().get(0).getMessage().getContent();

                    FactVerificationStatus byLlm = parseLabel(raw);
                    if (byLlm != FactVerificationStatus.UNKNOWN) {
                        return byLlm;
                    }
                } catch (Exception e) {
                    log.debug("FactStatusClassifier LLM 분류 실패, 휴리스틱 결과로 폴백: {}", e.toString());
                }
            }
        }

        // 2) 폴백: 휴리스틱 결과(PASS)
        return heuristic; // 기본 PASS
    }

    // ----------------- 내부 로직 -----------------

    private FactVerificationStatus heuristicClassify(String ctx, String draft) {
        if (isBlank(draft)) return FactVerificationStatus.INSUFFICIENT;
        String d = draft.trim();
        String dl = d.toLowerCase();
        if (dl.contains("정보 없음") || dl.contains("no information") || dl.contains("not found")) {
            return FactVerificationStatus.INSUFFICIENT;
        }
        // 컨텍스트 부족이면 보수적으로 PASS
        if (isBlank(ctx) || ctx.length() < 80) return FactVerificationStatus.PASS;

        // (옵션) 간단 키워드 기반 교정 신호
        String cl = ctx.toLowerCase();
        if ((cl.contains("오류") || cl.contains("틀림") || cl.contains("정정") || cl.contains("contradict"))
                && !dl.contains("정정")) {
            // 컨텍스트에 수정 신호가 강하면 CORRECTED 후보지만,
            // 최종 결정은 LLM 시도 후 실패 시 PASS로 둠.
            // 필요 시 여기서 CORRECTED 반환하도록 조정 가능.
        }

        return FactVerificationStatus.PASS;
    }

    private FactVerificationStatus parseLabel(String raw) {
        if (raw == null) return FactVerificationStatus.UNKNOWN;
        String up = raw.trim().toUpperCase();
        if (up.startsWith("PASS"))         return FactVerificationStatus.PASS;
        if (up.startsWith("CORRECTED"))    return FactVerificationStatus.CORRECTED;
        if (up.startsWith("INSUFFICIENT")) return FactVerificationStatus.INSUFFICIENT;
        return FactVerificationStatus.UNKNOWN;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
