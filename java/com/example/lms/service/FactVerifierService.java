package com.example.lms.service;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
/* 🔴 기타 import 유지 */
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import java.util.Objects;          // ←★ 추가
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import com.example.lms.service.verification.FactVerificationStatus;
import com.example.lms.service.verification.FactStatusClassifier;
@Slf4j
@Service
public class FactVerifierService {
    private final OpenAiService openAi;
    private final FactStatusClassifier classifier;

    /** Spring-DI용(2-인자) – 선호 경로 */
    public FactVerifierService(OpenAiService openAi,
                               FactStatusClassifier classifier) {
        this.openAi   = Objects.requireNonNull(openAi,   "openAi");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /** ▼ 기존 OpenAiConfig 호환용(1-인자) – 내부에서 분기 생성 */
    public FactVerifierService(OpenAiService openAi) {
        this(openAi, new FactStatusClassifier(openAi));
    }

    /** 컨텍스트가 이보다 짧으면 검증 스킵 (검색 스니펫 2~3개면 충분) */
    private static final int MIN_CONTEXT_CHARS = 80;

    private static final String TEMPLATE = """
        You are a senior investigative journalist and fact‑checker.
        
        ## TASK
        1. Read the **Question**, **Context**, and **Draft answer** below.
        2. Compare the Draft with the Context (Context has higher authority).
        3. A fact is verified only if **at least two independent Context lines** state the same information.
        4. If the Draft is fully consistent, reply exactly:
           STATUS: PASS
           CONTENT:
           <copy the draft verbatim>
        5. If the Draft contains factual errors or misses key info, fix it **concisely** (max 20 %% longer) and reply:
           STATUS: CORRECTED
           CONTENT:
           <your revised answer in Korean>
        6. If the Context is insufficient to verify, reply:
           STATUS: INSUFFICIENT
           CONTENT:
           <copy the draft verbatim>
           
        ## QUESTION
        %s
        
        ## CONTEXT
        %s
        
        ## DRAFT
        %s
        """;

    public String verify(String question,
                         String context,
                         String draft,
                         String model) {

        /* ───────── 0. 기초 검증 ───────── */
        if (!StringUtils.hasText(draft))                       return "";
        if (!StringUtils.hasText(context)
                || context.length() < MIN_CONTEXT_CHARS)       return draft;
        if (context.contains("[검색 결과 없음]"))               return draft;

        /* ───────── 1. STATUS 분류 ───────── */
        FactVerificationStatus status =
                classifier.classify(question, context, draft, model);

        switch (status) {
            case PASS, INSUFFICIENT -> {
                // 생성 모델 호출 없이 초안 그대로 반환
                return draft;
            }
            case CORRECTED -> {
                // ── 2. 필요한 경우에만 수정 LLM 호출 ──
                String gPrompt = String.format(TEMPLATE, question, context, draft);
                ChatCompletionRequest req = ChatCompletionRequest.builder()
                        .model(model)
                        .messages(List.of(
                                new ChatMessage(ChatMessageRole.SYSTEM.value(), gPrompt)))
                        .temperature(0d)
                        .topP(0.05d)
                        .build();
                try {
                    String raw = openAi.createChatCompletion(req)
                            .getChoices().get(0).getMessage().getContent();
                    int split = raw.indexOf("CONTENT:");
                    if (split > -1) {
                        return raw.substring(split + 8).trim();
                    }
                    return raw.trim();
                } catch (Exception e) {
                    log.error("Correction generation failed – fallback to draft", e);
                    return draft;
                }
            }
            default -> { return draft; }
        }
    }
}
