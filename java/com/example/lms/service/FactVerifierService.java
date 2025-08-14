
        package com.example.lms.service;

import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.service.ner.NamedEntityExtractor;
import com.example.lms.service.rag.guard.EvidenceGate;
import com.example.lms.service.verification.ClaimVerifierService;
import com.example.lms.service.verification.FactStatusClassifier;
import com.example.lms.service.verification.FactVerificationStatus;
import com.example.lms.service.verification.SourceAnalyzerService;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 답변 생성의 최종 단계에서 사실 여부를 검증하는 서비스입니다.
 * 소스 신뢰도 분석, 증거 충분성 평가, LLM을 이용한 주장 검증 및 수정 등 여러 단계를 조율합니다.
 */
@Slf4j
@Service
public class FactVerifierService {

    private final SourceAnalyzerService sourceAnalyzer;
    private final OpenAiService openAi;
    private final FactStatusClassifier classifier;
    private final ClaimVerifierService claimVerifier;
    private final EvidenceGate evidenceGate;

    // 선택적으로 주입되는 의존성
    @Autowired(required = false)
    private NamedEntityExtractor entityExtractor;

    // 생성자를 통해 모든 필수 의존성을 주입받습니다.
    public FactVerifierService(OpenAiService openAi,
                               FactStatusClassifier classifier,
                               SourceAnalyzerService sourceAnalyzer,
                               ClaimVerifierService claimVerifier,
                               EvidenceGate evidenceGate) {
        this.openAi = Objects.requireNonNull(openAi, "openAi");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.sourceAnalyzer = Objects.requireNonNull(sourceAnalyzer, "sourceAnalyzer");
        this.claimVerifier = Objects.requireNonNull(claimVerifier, "claimVerifier");
        this.evidenceGate = Objects.requireNonNull(evidenceGate, "evidenceGate");
    }

    private static final int MIN_CONTEXT_CHARS = 80;

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
        You are a senior investigative journalist and fact‑checker.

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

// 클래스 내 다른 verify(...)들 바로 위/아래 아무 곳에 추가

    /** 하위호환: (question, context, memory, draft, model) */
    public String verify(String question, String context, String memory, String draft, String model) {
        return verify(question, context, memory, draft, model, false);
    }
    /** 메모리 증거와 후속 질문 여부까지 반영하는 핵심 검증 메서드 */
    public String verify(String question, String context, String memory, String draft, String model, boolean isFollowUp) {
        if (!StringUtils.hasText(draft)) return "";

        // --- 1. 사전 검사 (Pre-checks) ---
        boolean hasSufficientContext = StringUtils.hasText(context) && context.length() >= MIN_CONTEXT_CHARS;
        boolean hasSufficientMemory = StringUtils.hasText(memory) && memory.length() >= 40;

        // 컨텍스트와 메모리가 모두 빈약하면, LLM에 의존하지 않고 시너지 주장 등만 간단히 필터링
        if (!hasSufficientContext && !hasSufficientMemory) {
            var result = claimVerifier.verifyClaims("", draft, model);
            return result.verifiedAnswer();
        }

        if (StringUtils.hasText(context) && context.contains("[검색 결과 없음]")) return draft;

        // --- 2. 메타 검증 (Meta-Verification) ---
        // 2a. 소스 신뢰도 분석: 팬 추측/상충 정보는 조기 차단
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

        // 2b. LLM을 이용한 질문-컨텍스트 정합성 분석
        try {
            String metaPrompt = String.format(META_TEMPLATE, question, context);
            String metaVerdict = callOpenAi(metaPrompt, model, 0.0, 0.05, 5);
            if (metaVerdict.trim().toUpperCase(Locale.ROOT).startsWith("MISMATCH")) {
                log.debug("[Verify] META-CHECK detected MISMATCH -> '정보 없음' 반환");
                return "정보 없음";
            }
        } catch (Exception e) {
            log.debug("[Verify] META-CHECK failed: {}", e.toString());
        }

        // --- 3. 핵심 검증 및 답변 재구성 ---
        FactVerificationStatus status = classifier.classify(question, context, draft, model);

        boolean isGrounded = isGroundedInContext(context, extractEntities(draft), 2);
        boolean hasEnoughEvidence = evidenceGate.hasSufficientCoverage(
                question, toLines(context), toLines(memory), List.of(), isFollowUp);

        // 근거가 부족하면(grounding 또는 evidence 부족) LLM을 통한 공격적인 수정을 피하고,
        // ClaimVerifier의 보수적인 필터링(SOFT-FAIL)만 거쳐서 반환
        if (!isGrounded || !hasEnoughEvidence) {
            log.debug("[Verify] 근거 부족(grounded: {}, evidence: {}) -> SOFT-FAIL 필터만 적용", isGrounded, hasEnoughEvidence);
            var result = claimVerifier.verifyClaims(mergeContext(context, memory), draft, model);
            return result.verifiedAnswer().isBlank() ? "정보 없음" : result.verifiedAnswer();
        }

        // 근거가 충분할 때의 로직
        switch (status) {
            case PASS, INSUFFICIENT:
                // PASS 상태여도, 미지원 주장(unsupported claims)은 제거해야 함
                var passResult = claimVerifier.verifyClaims(mergeContext(context, memory), draft, model);
                return passResult.verifiedAnswer();

            case CORRECTED:
                // CORRECTED 상태이고 근거도 충분하면, LLM을 통해 답변을 적극적으로 수정
                log.debug("[Verify] CORRECTED 상태이며 근거 충분 -> LLM 기반 수정 시도");
                String correctionPrompt = String.format(CORRECTION_TEMPLATE, question, context, draft);
                try {
                    String rawResponse = callOpenAi(correctionPrompt, model, 0.0, 0.05, 256);
                    int contentStartIndex = rawResponse.indexOf("CONTENT:");
                    String correctedText = (contentStartIndex > -1) ? rawResponse.substring(contentStartIndex + 8).trim() : rawResponse.trim();

                    // 수정된 답변도 마지막으로 한번 더 검증
                    var finalResult = claimVerifier.verifyClaims(mergeContext(context, memory), correctedText, model);
                    return finalResult.verifiedAnswer();
                } catch (Exception e) {
                    log.error("Correction generation failed, falling back to '정보 없음'", e);
                    return "정보 없음";
                }

            default:
                return draft;
        }
    }

    // --- Private Helper Methods ---

    private String callOpenAi(String prompt, String model, double temp, double topP, int maxTokens) {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(new ChatMessage(ChatMessageRole.SYSTEM.value(), prompt)))
                .temperature(temp)
                .topP(topP)
                .maxTokens(maxTokens)
                .build();
        return openAi.createChatCompletion(request).getChoices().get(0).getMessage().getContent();
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
        // 폴백: 간단한 정규식 기반 개체 추출
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
            return entities == null || entities.isEmpty(); // 개체가 없으면 grounding은 의미 없으므로 true
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
        return entitiesFound >= entities.size(); // 모든 개체가 최소 기준을 만족해야 함
    }
}