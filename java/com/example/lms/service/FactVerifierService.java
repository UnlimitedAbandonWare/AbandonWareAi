
// src/main/java/com/example/lms/service/FactVerifierService.java
package com.example.lms.service;

import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.service.ner.NamedEntityExtractor;
import com.example.lms.service.rag.guard.EvidenceGate;
import com.example.lms.service.verification.ClaimVerifierService;
import com.example.lms.service.verification.FactStatusClassifier;
import com.example.lms.service.verification.FactVerificationStatus;
import com.example.lms.service.verification.NamedEntityValidator;
import com.example.lms.service.verification.SourceAnalyzerService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.UserMessage;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


import org.springframework.beans.factory.annotation.Qualifier; // ✅ 수정: Qualifier 임포트 추가


/**
 * 답변 생성의 최종 단계에서 사실 여부를 검증하는 서비스입니다.
 * 소스 신뢰도 분석, 증거 충분성 평가, LLM을 이용한 주장 검증 및 수정 등 여러 단계를 조율합니다.
 */
@Service
public class FactVerifierService {
    private static final Logger log = LoggerFactory.getLogger(FactVerifierService.class);

    private final SourceAnalyzerService sourceAnalyzer;
    private final ChatModel verifier;
    private final FactStatusClassifier classifier;
    private final ClaimVerifierService claimVerifier;
    private final EvidenceGate evidenceGate;
    private final PromptBuilder promptBuilder;

    // 선택적으로 주입되는 의존성
    @Autowired(required = false)
    private NamedEntityExtractor entityExtractor;

    // [NEW] 선택적으로 주입되는 엔티티 검증기
    @Autowired(required = false)
    private NamedEntityValidator namedEntityValidator;

    // ✅ 수정: 생성자에 @Qualifier("highModel") 추가
    public FactVerifierService(@Qualifier("highModel") ChatModel verifier,
                               FactStatusClassifier classifier,
                               SourceAnalyzerService sourceAnalyzer,
                               ClaimVerifierService claimVerifier,
                               EvidenceGate evidenceGate,
                               PromptBuilder promptBuilder) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.sourceAnalyzer = Objects.requireNonNull(sourceAnalyzer, "sourceAnalyzer");
        this.claimVerifier = Objects.requireNonNull(claimVerifier, "claimVerifier");
        this.evidenceGate = Objects.requireNonNull(evidenceGate, "evidenceGate");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
    }

    private static final int MIN_CONTEXT_CHARS = 80;
    private static final int MAX_HEALING_RETRIES = 2;

    /** 컨텍스트-질문 정합성 메타 점검용 프롬프트 */
    private static final String META_TEMPLATE = """
        You are a meta fact-checker.
        Decide if the CONTEXT can safely answer the QUESTION without hallucination.
        Output exactly one of: CONSISTENT | MISMATCH | INSUFFICIENT
        and a one-sentence reason (in Korean).

        QUESTION:
        %s

        CONTEXT:
        %s
        """;

    /** LLM 기반 답변 수정용 프롬프트 */
    private static final String CORRECTION_TEMPLATE = """
        You are a senior investigative journalist and fact-checker.

        ## TASK
        1. Read the **Question**, **Context**, and **Draft answer** below.
        2. Compare the Draft with the Context (Context has higher authority).
        3. A fact is verified only if **at least two independent Context lines** state the same information.
        4. Remove or explicitly mark any named entities (characters/items/regions) that **do not appear in Context**.
           4-1. For any **pairing/synergy** claims (e.g., "A works well with B"):
               - Treat as VERIFIED only if Context contains an explicit synergy cue
                 (e.g., "잘 어울린다", "시너지", "조합", "함께 쓰면 좋다") relating A↔B.
               - Mere **stat comparisons**, **co-mentions**, or **example lists** are NOT sufficient.
        5. If the Draft is fully consistent, reply exactly:
           STATUS: PASS
           CONTENT:
           <copy the draft verbatim>
        6. If the Draft contains factual errors or misses key info, fix it **concisely** (max 20%% longer) and reply:
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

    /** 하위호환: (question, context, memory, draft, model) */
    public String verify(String question, String context, String memory, String draft, String model) {
        return verify(question, context, memory, draft, model, false);
    }

    /** 메모리 증거와 후속 질문 여부까지 반영하는 핵심 검증 메서드 */
    public String verify(String question, String context, String memory, String draft, String model, boolean isFollowUp) {
        return verifyInternal(question, context, memory, draft, model, isFollowUp, 0);
    }

    private String verifyInternal(String question, String context, String memory, String draft, String model,
                                  boolean isFollowUp, int attempt) {
        if (!StringUtils.hasText(draft)) return "";

        if (namedEntityValidator != null) {
            List<String> evidenceList = new ArrayList<>();
            if (StringUtils.hasText(context)) evidenceList.add(context);
            if (StringUtils.hasText(memory)) evidenceList.add(memory);

            NamedEntityValidator.ValidationResult vr =
                    namedEntityValidator.validateAnswerEntities(draft, evidenceList);


            if (vr.isEntityMismatch()) {
                log.warn("[Verify] Unsupported entities detected by validator");
                if (attempt < MAX_HEALING_RETRIES) {
                    List<String> uc = computeUnsupportedEntities(context, memory, draft);
                    if (!uc.isEmpty()) {
                        String healed = correctiveRegenerate(question, context, memory, draft, model, uc);
                        return verifyInternal(question, context, memory, healed, model, isFollowUp, attempt + 1);
                    }
                }
                return "정보 없음";
            }
        }

        boolean hasSufficientContext = StringUtils.hasText(context) && context.length() >= MIN_CONTEXT_CHARS;
        boolean hasSufficientMemory = StringUtils.hasText(memory) && memory.length() >= 40;

        if (!hasSufficientContext && !hasSufficientMemory) {
            var result = claimVerifier.verifyClaims("", draft, model);
            return result.verifiedAnswer();
        }

        if (StringUtils.hasText(context) && context.contains("[검색 결과 없음]")) return draft;

        try {
            String mergedContext = mergeContext(context, memory);
            SourceCredibility credibility = sourceAnalyzer.analyze(question, mergedContext);
            if (credibility == SourceCredibility.FAN_MADE_SPECULATION || credibility == SourceCredibility.CONFLICTING) {
                log.warn("[Meta-Verify] 낮은 신뢰도({}) 탐지 -> 답변 차단", credibility);
                return "웹에서 찾은 정보는 공식 발표가 아니거나, 커뮤니티의 추측일 가능성이 높습니다. 이에 기반한 답변은 부정확할 수 있어 제공하지 않습니다.";
            }
        } catch (Exception e) {
            log.debug("[Meta-Verify] Source analysis failed: {}", e.toString());
        }

        try {
            String metaPrompt = String.format(META_TEMPLATE, question, context);
            String metaVerdict = callChatModel(metaPrompt);
            if (metaVerdict.trim().toUpperCase(Locale.ROOT).startsWith("MISMATCH")) {
                log.debug("[Verify] META-CHECK detected MISMATCH -> '정보 없음' 반환");
                return "정보 없음";
            }
        } catch (Exception e) {
            log.debug("[Verify] META-CHECK failed: {}", e.toString());
        }

        FactVerificationStatus status = classifier.classify(question, context, draft, model);

        boolean isGrounded = isGroundedInContext(context, extractEntities(draft), 2);
        boolean hasEnoughEvidence = evidenceGate.hasSufficientCoverage(
                question, toLines(context), toLines(memory), List.of(), isFollowUp);

        if (!isGrounded || !hasEnoughEvidence) {
            log.debug("[Verify] 근거 부족(grounded: {}, evidence: {})", isGrounded, hasEnoughEvidence);
            if (attempt < MAX_HEALING_RETRIES) {
                List<String> uc = computeUnsupportedEntities(context, memory, draft);
                if (!uc.isEmpty()) {
                    String healed = correctiveRegenerate(question, context, memory, draft, model, uc);
                    return verifyInternal(question, context, memory, healed, model, isFollowUp, attempt + 1);
                }
            }
            var result = claimVerifier.verifyClaims(mergeContext(context, memory), draft, model);
            return result.verifiedAnswer().isBlank() ? "정보 없음" : result.verifiedAnswer();
        }

        switch (status) {
            case PASS, INSUFFICIENT:
                var passResult = claimVerifier.verifyClaims(mergeContext(context, memory), draft, model);
                String passAnswer = passResult.verifiedAnswer();
                if (attempt < MAX_HEALING_RETRIES) {
                    List<String> ucPass = computeUnsupportedEntities(context, memory, passAnswer);
                    if (!ucPass.isEmpty()) {
                        String healed = correctiveRegenerate(question, context, memory, passAnswer, model, ucPass);
                        return verifyInternal(question, context, memory, healed, model, isFollowUp, attempt + 1);
                    }
                }
                return passAnswer;

            case CORRECTED:
                log.debug("[Verify] CORRECTED 상태이며 근거 충분 -> LLM 기반 수정 시도");
                String correctionPrompt = String.format(CORRECTION_TEMPLATE, question, context, draft);
                try {
                    String rawResponse = callChatModel(correctionPrompt);
                    int contentStartIndex = rawResponse.indexOf("CONTENT:");
                    String correctedText = (contentStartIndex > -1) ? rawResponse.substring(contentStartIndex + 8).trim() : rawResponse.trim();

                    var finalResult = claimVerifier.verifyClaims(mergeContext(context, memory), correctedText, model);
                    String finalAns = finalResult.verifiedAnswer();
                    if (attempt < MAX_HEALING_RETRIES) {
                        List<String> ucFinal = computeUnsupportedEntities(context, memory, finalAns);
                        if (!ucFinal.isEmpty()) {
                            String healed = correctiveRegenerate(question, context, memory, finalAns, model, ucFinal);
                            return verifyInternal(question, context, memory, healed, model, isFollowUp, attempt + 1);
                        }
                    }
                    return finalAns;
                } catch (Exception e) {
                    log.error("Correction generation failed, falling back to '정보 없음'", e);
                    return "정보 없음";
                }

            default:
                return draft;
        }
    }

    private String callChatModel(String prompt) {
        try {
            var res = verifier.chat(UserMessage.from(prompt));
            if (res == null || res.aiMessage() == null) return "";
            var ai = res.aiMessage();
            return ai.text() == null ? "" : ai.text();
        } catch (Exception e) {
            log.debug("[FactVerifier] ChatModel call failed: {}", e.toString());
            return "";
        }
    }

    private String callChatModel(List<Object> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Object m : messages) {
            if (m == null) continue;
            sb.append(String.valueOf(m));
            sb.append('\n');
        }
        return callChatModel(sb.toString());
    }

    private List<String> computeUnsupportedEntities(String ctx, String mem, String text) {
        List<String> entities = extractEntities(text);
        if (entities.isEmpty()) return List.of();
        String evidence = (ctx == null ? "" : ctx) + "\n" + (mem == null ? "" : mem);
        String lowerEv = evidence.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String e : entities) {
            if (e != null && !e.isBlank()) {
                if (!lowerEv.contains(e.toLowerCase(Locale.ROOT))) out.add(e);
            }
        }
        return out;
    }

    private String correctiveRegenerate(String question, String context, String memory, String draft, String model, List<String> unsupportedClaims) {
        try {
            PromptContext healCtx = PromptContext.builder()
                    .userQuery(question)
                    .lastAssistantAnswer(draft)
                    .unsupportedClaims(unsupportedClaims)
                    .systemInstruction("CORRECTIVE_REGENERATION")
                    .citationStyle("inline")
                    .build();
            String healCtxSection = promptBuilder.build(healCtx);
            String healInstr = promptBuilder.buildInstructions(healCtx);

            List<String> msgs = new ArrayList<>();
            if (StringUtils.hasText(context)) {
                msgs.add(context);
            }
            if (StringUtils.hasText(healCtxSection)) {
                msgs.add(healCtxSection);
            }
            msgs.add(healInstr);
            msgs.add("위 지시를 따르고, 미지원 주장을 제거·수정하여 정답을 한국어로 다시 작성하세요.");
            return callChatModel(new java.util.ArrayList<>(msgs));
        } catch (Exception e) {
            log.warn("[Self-Healing] correctiveRegenerate failed: {}", e.toString());
            return draft;
        }
    }

    private static String mergeContext(String ctx, String mem) {
        String contextPart = (ctx == null) ? "" : ctx;
        String memoryPart = (mem == null || mem.isBlank()) ? "" : ("\n\n### LONG-TERM MEMORY\n" + mem);
        return contextPart + memoryPart;
    }

    private List<String> extractEntities(String text) {
        if (entityExtractor != null) {
            return entityExtractor.extract(text);
        }
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String[] patterns = {
                "(?i)\\b(Core\\s+Ultra\\s+\\d+\\s*\\d*[A-Z]?)\\b",
                "(?i)\\b(Ryzen\\s+[3579]\\s+\\d{3,5}[A-Z]?)\\b",
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

    private static List<String> toLines(String s) {
        if (s == null || s.isBlank()) return Collections.emptyList();
        return Arrays.stream(s.split("\\R+"))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }

    private static boolean isGroundedInContext(String context, List<String> entities, int minLines) {
        if (context == null || context.isBlank() || entities == null || entities.isEmpty()) {
            return entities == null || entities.isEmpty();
        }
        String[] lines = context.split("\\R+");
        int entitiesFound = 0;
        for (String entity : entities) {
            long lineCount = Arrays.stream(lines)
                    .filter(line -> line.toLowerCase(Locale.ROOT).contains(entity.toLowerCase(Locale.ROOT)))
                    .count();
            if (lineCount >= minLines) {
                entitiesFound++;
            }
        }
        return entitiesFound >= entities.size();
    }
}