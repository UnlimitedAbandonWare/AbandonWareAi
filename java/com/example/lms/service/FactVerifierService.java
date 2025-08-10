package com.example.lms.service;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
/* 🔴 기타 import 유지 */
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.example.lms.service.verification.FactVerificationStatus;
import com.example.lms.service.verification.FactStatusClassifier;

@Slf4j
@Service
public class FactVerifierService {
    private final OpenAiService openAi;
    private final FactStatusClassifier classifier;

    public FactVerifierService(OpenAiService openAi,
                               FactStatusClassifier classifier) {
        this.openAi = Objects.requireNonNull(openAi, "openAi");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    public FactVerifierService(OpenAiService openAi) {
        this(openAi, new FactStatusClassifier(openAi));
    }

    private static final int MIN_CONTEXT_CHARS = 80;

    private static final String TEMPLATE = """
            You are a senior investigative journalist and fact‑checker.

            ## TASK
            1. Read the **Question**, **Context**, and **Draft answer** below.
            2. Compare the Draft with the Context (Context has higher authority).
            3. A fact is verified only if **at least two independent Context lines** state the same information.
            4. Remove or explicitly mark any named entities (characters/items/regions) that **do not appear in Context**.
            5. If the Draft is fully consistent, reply exactly:
               STATUS: PASS
               CONTENT:
               <copy the draft verbatim>
            6. If the Draft contains factual errors or misses key info, fix it **concisely** (max 20%% longer) and reply:
               STATUS: CORRECTED
               CONTENT:
               <your revised answer in Korean>
            7. If the Context is insufficient to verify, reply:
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
        if (!StringUtils.hasText(draft)) return "";
        if (!StringUtils.hasText(context) || context.length() < MIN_CONTEXT_CHARS) return draft;
        if (context.contains("[검색 결과 없음]")) return draft;

        FactVerificationStatus status = classifier.classify(question, context, draft, model);

        var entities = extractEntities(draft);
        boolean grounded = groundedInContext(context, entities, 2);

        switch (status) {
            case PASS, INSUFFICIENT -> {
                if (!grounded) {
                    // + 컨텍스트 근거 부족 → 환각 가능성 → 답변 차단
                    log.debug("[Verify] grounding 실패 → 정보 없음");
                    return "정보 없음";
                }
                return draft;
            }
            case CORRECTED -> {
                if (!grounded) {
                    log.debug("[Verify] CORRECTED 스킵(grounding 실패) -> 정보 없음");
                    return "정보 없음";
                }
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
                        return raw.substring(split + 8).trim();
                    }
                    return raw.trim();
                } catch (Exception e) {
                    log.error("Correction generation failed – fallback to '정보 없음'", e);
                    return "정보 없음";
                }
            }
            default -> {
                return draft;
            }
        }
    }

    /** 간단 개체 추출(모델/제품/버전/캐릭터 등; KO/EN 혼용) */
    private static List<String> extractEntities(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String[] patterns = {
                "(?i)\\b(Core\\s+Ultra\\s+\\d+\\s*\\d*[A-Z]?)\\b",
                "(?i)\\b(Ryzen\\s+[3579]\\s+\\d{3,5}[A-Z]?)\\b",
                "(?i)\\b(Arc\\s+Graphics)\\b",
                "(?i)\\b([A-Z]{1,3}\\d{1,4}[A-Z]?)\\b",
                "(?i)(코어\\s*울트라\\s*\\d+\\s*\\d*[A-Z]?)",
                "(?i)(라데온|인텔|AMD)",
                // + 게임 고유명사 예시
                "(?i)(다이루크|후리나|푸리나|원신|genshin|에스코피에|escoffier)"
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

    /** 개체가 서로 다른 컨텍스트 라인에 최소 minLines 등장하는지 */
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
