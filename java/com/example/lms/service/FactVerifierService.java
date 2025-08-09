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
import org.springframework.util.StringUtils;  // ★ 누락된 유틸
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.List;                     // ★ 누락된 List
import java.util.regex.Pattern;
import com.example.lms.service.verification.FactVerificationStatus;
import com.example.lms.service.verification.FactStatusClassifier;

@Slf4j
@Service
public class FactVerifierService {
    private final OpenAiService openAi;
    private final FactStatusClassifier classifier;

    /**
     * Spring-DI용(2-인자) – 선호 경로
     */
    public FactVerifierService(OpenAiService openAi,
                               FactStatusClassifier classifier) {
        this.openAi = Objects.requireNonNull(openAi, "openAi");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    /**
     * ▼ 기존 OpenAiConfig 호환용(1-인자) – 내부에서 분기 생성
     */
    public FactVerifierService(OpenAiService openAi) {
        this(openAi, new FactStatusClassifier(openAi));
    }

    /**
     * 컨텍스트가 이보다 짧으면 검증 스킵 (검색 스니펫 2~3개면 충분)
     */
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
        if (!StringUtils.hasText(draft)) return "";
        if (!StringUtils.hasText(context)
                || context.length() < MIN_CONTEXT_CHARS) return draft;
        if (context.contains("[검색 결과 없음]")) return draft;

        /* ───────── 1. STATUS 분류 ───────── */
        FactVerificationStatus status = classifier.classify(question, context, draft, model);

        // 1-a) Grounding check: 초안의 핵심 개체가 컨텍스트의 '서로 다른' 라인에 최소 2회 등장해야 신뢰
        var entities = extractEntities(draft);
        boolean grounded = groundedInContext(context, entities, 2);

        switch (status) {
            case PASS, INSUFFICIENT -> {
                if (status == FactVerificationStatus.PASS && !grounded) {
                    log.debug("[Verify] PASS->INSUFFICIENT (grounding 실패): ents={}", entities);
                }
                // 생성 모델 호출 없이 초안 그대로 반환
                return draft;
            }
            case CORRECTED -> {
                // 근거 부족 시 수정 LLM 호출 스킵 (비용·환각 방지)
                if (!grounded) {
                    log.debug("[Verify] CORRECTED 스킵(grounding 실패) -> 초안 반환");
                    return draft;
                }
                // -- 2. 필요한 경우에만 수정 LLM 호출 --
                String gPrompt = String.format(TEMPLATE, question, context, draft);
                ChatCompletionRequest req = ChatCompletionRequest.builder()
                        .model(model)
                        .messages(List.of(new ChatMessage(ChatMessageRole.SYSTEM.value(), gPrompt)))
                        .temperature(0d)
                        .topP(0.05d)
                        .build();
                try {
                    String raw = openAi.createChatCompletion(req)
                            .getChoices().get(0).getMessage().getContent();
                    int split = raw.indexOf("CONTENT:");
                    if (split > -1) {
                        return raw.substring(split 8).trim();
                    }
                    return raw.trim();
                } catch (Exception e) {
                    log.error("Correction generation failed – fallback to draft", e);
                    return draft;
                }
            }
        }
    }


    /**
     * 간단 개체 추출: 모델/제품/버전 등 (KO/EN 혼용)
     */
    private static List<String> extractEntities(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String[] patterns = {
                "(?i)\\b(Core\\s+Ultra\\s+\\d+\\s*\\d*[A-Z]?)\\b",
                "(?i)\\b(Ryzen\\s+[3579]\\s+\\d{3,5}[A-Z]?)\\b",
                "(?i)\\b(Arc\\s+Graphics)\\b",
                "(?i)\\b([A-Z]{1,3}\\d{1,4}[A-Z]?)\\b",
                "(?i)(코어\\s*울트라\\s*\\d+\\s*\\d*[A-Z]?)",
                "(?i)(라데온|인텔|AMD)"
        };
        for (String p : patterns) {
            var m = Pattern.compile(p).matcher(text);
            while (m.find()) {
                String e = m.group(0).trim();
                if (e.length() > 1 && !out.contains(e)) out.add(e);
            }
        }
        return out;
    }

    /**
     * 각 개체가 서로 다른 컨텍스트 라인 최소 minLines 에 등장하는지 검사
     */
    private static boolean groundedInContext(String context, List<String> entities, int minLines) {
        if (context == null || context.isBlank() || entities == null || entities.isEmpty()) return false;
        String[] lines = context.split("\\R+");
        int ok = 0;
        for (String e : entities) {
            int c = 0;
            for (String ln : lines) {
                if (ln.toLowerCase().contains(e.toLowerCase())) c++;
                if (c >= minLines) break;
            }
            if (c >= minLines) ok++;
        }
        return ok > 0;
    }
}
