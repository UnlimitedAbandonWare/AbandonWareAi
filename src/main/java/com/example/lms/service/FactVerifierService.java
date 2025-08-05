package com.example.lms.service;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
/* 🔴 기타 import 유지 */
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Arrays;   // ⭐ 추가
@Slf4j
@Service
@RequiredArgsConstructor
public class FactVerifierService {

    private final OpenAiService openAi;

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

        if (!StringUtils.hasText(draft))  return "";
        if (!StringUtils.hasText(context) || context.length() < MIN_CONTEXT_CHARS) {
            return draft;
        }
        /* 🔴 검색 실패 센티널([검색 결과 없음] / 정보 없음[.])만 정확히 걸러낸다 */
        if (context.contains("[검색 결과 없음]") ||
                "정보 없음".equals(context.trim()) ||
                "정보 없음.".equals(context.trim())) {
            return draft;
        }

        /* 🔴 NEW: 검색 스니펫이 모두 동일 문장 반복 = 정보 편향 → 검증 skip */
        if (Arrays.stream(context.split("\\R+"))
                .distinct().count() <= 1) {
            return draft;
        }

        String prompt = String.format(TEMPLATE, question, context, draft);

        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(new ChatMessage(ChatMessageRole.SYSTEM.value(), prompt)))
                .temperature(0d)
                .topP(0.05d)
                .build();

        try {
            String raw = openAi.createChatCompletion(req)
                    .getChoices().get(0).getMessage().getContent();

            int split = raw.indexOf("CONTENT:");
            if (split > -1) {
                String statusLine = raw.substring(0, split).trim();
                String content    = raw.substring(split + 8).trim();
                /* PASS / CORRECTED / INSUFFICIENT 모두 LLM CONTENT 우선 */
                return content;
            }
            return draft;

        } catch (Exception e) {
            log.error("Fact verification failed → draft 사용", e);
            return draft;
        }
    }
}
