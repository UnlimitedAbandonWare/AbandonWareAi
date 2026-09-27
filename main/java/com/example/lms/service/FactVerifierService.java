
// src/main/java/com/example/lms/service/FactVerifierService.java
package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.llm.TimedChatModelCaller;
import com.example.lms.service.rag.detector.QueryRiskClassifier;
import com.example.lms.service.rag.detector.RiskBand;
import com.example.lms.util.FutureTechDetector;
import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.service.ner.NamedEntityExtractor;
import com.example.lms.service.rag.guard.EvidenceGate;
import com.example.lms.service.verification.ClaimVerifierService;
import com.example.lms.service.verification.FactStatusClassifier;
import com.example.lms.service.verification.FactVerificationStatus;
import com.example.lms.service.verification.NamedEntityValidator;
import com.example.lms.service.verification.SourceAnalyzerService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.UserMessage;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.Duration;
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
    private static final Pattern META_VERDICT_PATTERN = Pattern.compile(
            "\\A\\s*(CONSISTENT|MISMATCH|INSUFFICIENT)"
                    + "(?:\\s*(?:\\||:|-)\\s*(\\S[^\\r\\n]*))?\\s*\\z",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CONTROL_PREFIX_PATTERN = Pattern.compile(
            "\\A\\s*(?:CONSISTENT|MISMATCH|INSUFFICIENT)(?:\\b|\\s*(?:\\||:|-))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CORRECTION_ENVELOPE_PATTERN = Pattern.compile(
            "\\A\\s*STATUS:\\s*(PASS|CORRECTED|INSUFFICIENT)\\s*\\R+"
                    + "\\s*CONTENT:\\s*(\\S[\\s\\S]*?)\\s*\\z",
            Pattern.CASE_INSENSITIVE);

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
        return verifyDetailed(question, context, memory, draft, model, isFollowUp).answer();
    }

    public DetailedVerificationResult verifyDetailed(
            String question,
            String context,
            String memory,
            String draft,
            String model,
            boolean isFollowUp) {
        VerificationState state = new VerificationState();
        String answer = verifyInternal(question, context, memory, draft, model, isFollowUp, 0, state);
        return new DetailedVerificationResult(
                answer,
                state.status,
                state.outcomeKnown,
                state.acceptedForMemory);
    }

    public record DetailedVerificationResult(
            String answer,
            String status,
            boolean outcomeKnown,
            boolean acceptedForMemory) {
    }

    private String verifyInternal(String question, String context, String memory, String draft, String model,
                                  boolean isFollowUp, int attempt, VerificationState state) {
        if (!StringUtils.hasText(draft)) {
            state.reject("unknown");
            return "";
        }

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
                        return verifyInternal(question, context, memory, healed, model, isFollowUp, attempt + 1, state);
                    }
                }
                state.reject("rejected");
                return "정보 없음";
            }
        }

        boolean hasSufficientContext = StringUtils.hasText(context) && context.length() >= MIN_CONTEXT_CHARS;
        boolean hasSufficientMemory = StringUtils.hasText(memory) && memory.length() >= 40;

        if (!hasSufficientContext && !hasSufficientMemory) {
            var result = claimVerifier.verifyClaims("", draft, model);
            state.reject(result.outcomeKnown() ? "insufficient" : "unknown");
            return result.verifiedAnswer();
        }

        if (StringUtils.hasText(context) && context.contains("[검색 결과 없음]")) {
            state.reject("insufficient");
            return draft;
        }

        try {
            String mergedContext = mergeContext(context, memory);
            SourceCredibility credibility = sourceAnalyzer.analyze(question, mergedContext);
            // [FUTURE_TECH FIX] 미출시/세대형 제품은 루머/유출 기반 요약을 "차단"하지 않고, 라벨링하여 허용
            boolean futureTech = FutureTechDetector.isFutureTechQuery(question);
            RiskBand riskBand = QueryRiskClassifier.classify(question, null);
            if ((credibility == SourceCredibility.FAN_MADE_SPECULATION
                    || credibility == SourceCredibility.CONFLICTING)
                    && futureTech
                    && riskBand != RiskBand.HIGH) {
                String header = "※ 아래 내용은 공식 발표가 아니라 웹 검색에서 수집된 루머/유출/예상 정보 기반이며, 변경될 수 있습니다\n\n";
                String out = (draft == null ? "" : draft.trim());
                if (!out.startsWith("※")) out = header + out;
                state.reject("insufficient");
                return out;
            }
            if (credibility == SourceCredibility.FAN_MADE_SPECULATION || credibility == SourceCredibility.CONFLICTING) {
                log.warn("[Meta-Verify] 낮은 신뢰도({}) 탐지 -> 답변 차단", credibility);
                state.reject("rejected");
                return "웹에서 찾은 정보는 공식 발표가 아니거나, 커뮤니티의 추측일 가능성이 높습니다. 이에 기반한 답변은 부정확할 수 있어 제공하지 않습니다.";
            }
        } catch (Exception e) {
            state.markFailSoft();
            log.debug("[Meta-Verify] Source analysis failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }

        try {
            String metaPrompt = buildVerifierPrompt("FACT_META_CHECK", META_TEMPLATE, question, context);
            String metaVerdict = callChatModel(metaPrompt);
            MetaVerdict parsedMetaVerdict = parseMetaVerdict(metaVerdict);
            if (parsedMetaVerdict == null) {
                state.markFailSoft();
                log.debug("[Verify] META-CHECK malformed rawHash={} rawLength={}",
                        SafeRedactor.hashValue(metaVerdict), metaVerdict == null ? 0 : metaVerdict.length());
            } else {
                switch (parsedMetaVerdict) {
                    case CONSISTENT -> {
                        // Continue to the structured fact/claim verification stages.
                    }
                    case INSUFFICIENT -> {
                        state.reject("insufficient");
                        return draft;
                    }
                    case MISMATCH -> {
                        boolean futureTechMeta = FutureTechDetector.isFutureTechQuery(question);
                        if (futureTechMeta) {
                            // Preserve the labeled answer but keep memory fail-closed.
                            log.debug("[Verify] META-CHECK detected MISMATCH (FutureTech) -> continue with labeling");
                            state.markFailSoft();
                        } else {
                            log.debug("[Verify] META-CHECK detected MISMATCH -> '정보 없음' 반환");
                            state.reject("rejected");
                            return "정보 없음";
                        }
                    }
                }
            }
        } catch (Exception e) {
            state.markFailSoft();
            log.debug("[Verify] META-CHECK failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }

        FactVerificationStatus status = classifier.classify(question, context, draft, model);
        if (TraceStore.get("factStatusClassifier.judge.disabledReason") != null) {
            state.markFailSoft();
        }

        boolean isGrounded = isGroundedInContext(context, extractEntities(draft), 2);
        List<String> ragLines = toLines(context);
        List<String> memoryLines = toLines(memory);
        List<String> kbLines = List.of();



RiskBand risk = QueryRiskClassifier.classify(question, null);
        boolean hasEnoughEvidence = evidenceGate.hasSufficientCoverage(
                question, ragLines, memoryLines, kbLines, isFollowUp);

        boolean strongWebHit = hasStrongWebHit(question, ragLines);
        if (!hasEnoughEvidence && strongWebHit) {
            log.debug("[Verify] Coverage insufficient but strong hit detected in evidence context → overriding coverage gate");
            hasEnoughEvidence = true;
        }

        if (!isGrounded || !hasEnoughEvidence) {
            log.debug("[Verify] 근거 부족(grounded: {}, evidence: {})", isGrounded, hasEnoughEvidence);
            if (attempt < MAX_HEALING_RETRIES) {
                List<String> uc = computeUnsupportedEntities(context, memory, draft);
                if (!uc.isEmpty()) {
                    String healed = correctiveRegenerate(question, context, memory, draft, model, uc);
                    return verifyInternal(question, context, memory, healed, model, isFollowUp, attempt + 1, state);
                }
            }
            var result = claimVerifier.verifyClaims(mergeContext(context, memory), draft, model);
            state.reject(result.outcomeKnown() ? "insufficient" : "unknown");
            return result.verifiedAnswer().isBlank() ? "정보 없음" : result.verifiedAnswer();
        }

        switch (status) {
            case PASS:
                var passResult = claimVerifier.verifyClaims(mergeContext(context, memory), draft, model);
                String passAnswer = passResult.verifiedAnswer();
                if (attempt < MAX_HEALING_RETRIES) {
                    List<String> ucPass = computeUnsupportedEntities(context, memory, passAnswer);
                    if (!ucPass.isEmpty()) {
                        String healed = correctiveRegenerate(question, context, memory, passAnswer, model, ucPass);
                        return verifyInternal(question, context, memory, healed, model, isFollowUp, attempt + 1, state);
                    }
                }
                state.accept(
                        "pass",
                        passResult.outcomeKnown(),
                        passResult.acceptedForMemory());
                return passAnswer;

            case INSUFFICIENT:
                var insufficientResult = claimVerifier.verifyClaims(mergeContext(context, memory), draft, model);
                state.reject(insufficientResult.outcomeKnown() ? "insufficient" : "unknown");
                return insufficientResult.verifiedAnswer();

            case CORRECTED:
                log.debug("[Verify] CORRECTED 상태이며 근거 충분 -> LLM 기반 수정 시도");
                String correctionPrompt = buildVerifierPrompt("FACT_CORRECTION", CORRECTION_TEMPLATE, question, context, draft);
                try {
                    String rawResponse = callChatModel(correctionPrompt);
                    CorrectionEnvelope correction = parseCorrectionEnvelope(rawResponse);
                    if (!correction.valid()) {
                        state.markFailSoft();
                        return draft;
                    }
                    if ("INSUFFICIENT".equals(correction.status())) {
                        state.reject("insufficient");
                        return correction.content();
                    }
                    String correctedText = correction.content();

                    var finalResult = claimVerifier.verifyClaims(mergeContext(context, memory), correctedText, model);
                    String finalAns = finalResult.verifiedAnswer();
                    if (attempt < MAX_HEALING_RETRIES) {
                        List<String> ucFinal = computeUnsupportedEntities(context, memory, finalAns);
                        if (!ucFinal.isEmpty()) {
                            String healed = correctiveRegenerate(question, context, memory, finalAns, model, ucFinal);
                            return verifyInternal(question, context, memory, healed, model, isFollowUp, attempt + 1, state);
                        }
                    }
                    String verifiedStatus = "PASS".equals(correction.status())
                            ? "pass"
                            : "corrected";
                    state.accept(
                            verifiedStatus,
                            finalResult.outcomeKnown(),
                            finalResult.acceptedForMemory() && StringUtils.hasText(finalAns));
                    return finalAns;
                } catch (Exception e) {
                    log.error("Correction generation failed, falling back to '정보 없음'. errorHash={} errorLength={}",
                            SafeRedactor.hashValue(messageOf(e)), messageLength(e));
                    state.reject("unknown");
                    return "정보 없음";
                }

            default:
                state.reject("unknown");
                return draft;
        }
    }

    private static MetaVerdict parseMetaVerdict(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        List<String> lines = Arrays.stream(raw.strip().split("\\R"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (lines.isEmpty() || lines.size() > 2) {
            return null;
        }
        var matcher = META_VERDICT_PATTERN.matcher(lines.get(0));
        if (!matcher.matches()) {
            return null;
        }
        if (isMetaControlReason(matcher.group(2))) {
            return null;
        }
        if (lines.size() == 2) {
            String reason = lines.get(1);
            if (isMetaControlReason(reason)) {
                return null;
            }
        }
        return switch (matcher.group(1).toUpperCase(Locale.ROOT)) {
            case "CONSISTENT" -> MetaVerdict.CONSISTENT;
            case "MISMATCH" -> MetaVerdict.MISMATCH;
            case "INSUFFICIENT" -> MetaVerdict.INSUFFICIENT;
            default -> null;
        };
    }

    private static boolean isMetaControlReason(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.strip();
        return META_CONTROL_PREFIX_PATTERN.matcher(normalized).find()
                || normalized.toUpperCase(Locale.ROOT).startsWith("STATUS:");
    }

    private static CorrectionEnvelope parseCorrectionEnvelope(String raw) {
        if (!StringUtils.hasText(raw)) {
            return CorrectionEnvelope.invalid();
        }
        var matcher = CORRECTION_ENVELOPE_PATTERN.matcher(raw);
        if (!matcher.matches()) {
            return CorrectionEnvelope.invalid();
        }
        String status = matcher.group(1).toUpperCase(Locale.ROOT);
        String content = matcher.group(2).trim();
        if (!StringUtils.hasText(content)) {
            return CorrectionEnvelope.invalid();
        }
        return new CorrectionEnvelope(status, content, true);
    }

    private String buildVerifierPrompt(String stage, String template, Object... args) {
        String body = String.format(Locale.ROOT, template, args);
        PromptContext ctx = PromptContext.builder()
                .systemInstruction(stage)
                .userQuery(body)
                .build();
        return promptBuilder.build(ctx);
    }

    private String callChatModel(String factVerifierPrompt) {
        try {
            TimeBudget requestBudget = TimeBudgetContext.get();
            if (requestBudget != null && requestBudget.expired()) return "";
            var userMessage = UserMessage.from(factVerifierPrompt);
            dev.langchain4j.data.message.AiMessage ai;
            if (requestBudget == null) {
                var res = verifier.chat(userMessage);
                ai = res == null ? null : res.aiMessage();
            } else {
                long remainingMs = requestBudget.remainingMillis();
                if (remainingMs <= 0L) return "";
                ai = TimedChatModelCaller.chat(
                        verifier,
                        List.of(userMessage),
                        Duration.ofMillis(remainingMs),
                        "fact_verifier_judge",
                        verifier.getClass().getName());
            }
            if (ai == null) return "";
            return ai.text() == null ? "" : ai.text();
        } catch (Exception e) {
            log.debug("[FactVerifier] ChatModel call failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
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

    private static final class VerificationState {
        private String status = "unknown";
        private boolean outcomeKnown;
        private boolean acceptedForMemory;
        private boolean failSoft;

        private void markFailSoft() {
            failSoft = true;
            status = "unknown";
            outcomeKnown = false;
            acceptedForMemory = false;
        }

        private void accept(
                String acceptedStatus,
                boolean knownOutcome,
                boolean positiveOutcome) {
            if (!knownOutcome || failSoft) {
                reject("unknown");
                return;
            }
            if (!positiveOutcome) {
                reject("rejected");
                return;
            }
            status = acceptedStatus;
            outcomeKnown = true;
            acceptedForMemory = true;
        }

        private void reject(String rejectedStatus) {
            String safeStatus = rejectedStatus == null || rejectedStatus.isBlank()
                    ? "unknown"
                    : rejectedStatus;
            if (failSoft || "unknown".equals(safeStatus)) {
                status = "unknown";
                outcomeKnown = false;
                acceptedForMemory = false;
                return;
            }
            status = safeStatus;
            outcomeKnown = true;
            acceptedForMemory = false;
        }
    }

    private enum MetaVerdict {
        CONSISTENT,
        MISMATCH,
        INSUFFICIENT
    }

    private record CorrectionEnvelope(String status, String content, boolean valid) {
        private static CorrectionEnvelope invalid() {
            return new CorrectionEnvelope("UNKNOWN", "", false);
        }
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
            log.warn("[Self-Healing] correctiveRegenerate failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return draft;
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
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


    /**
     * Heuristic to detect a "strong" hit in the evidence text.
     * If at least one non-trivial token from the question appears in the
     * evidence lines, we treat this as a strong hit and may relax the
     * coverage gate when EvidenceGate reports insufficient coverage.
     */
    private boolean hasStrongWebHit(String question, List<String> evidenceLines) {
        if (question == null || question.isBlank()
                || evidenceLines == null || evidenceLines.isEmpty()) {
            return false;
        }

        String[] tokens = question.split("\\s+");
        List<String> keyTokens = new ArrayList<>();
        for (String t : tokens) {
            if (t == null) continue;
            String trimmed = t.trim();
            if (trimmed.length() <= 1) continue;
            // 간단한 조사/불용어 필터링
            if (trimmed.matches("^(에서|하고|인가요\\?|뭐야|란|이란|입니까|인지)$")) continue;
            keyTokens.add(trimmed.toLowerCase(Locale.ROOT));
        }
        if (keyTokens.isEmpty()) {
            return false;
        }

        StringBuilder sb = new StringBuilder();
        for (String line : evidenceLines) {
            if (line == null || line.isBlank()) continue;
            sb.append(line.toLowerCase(Locale.ROOT)).append('\n');
        }
        String text = sb.toString();
        if (text.isEmpty()) {
            return false;
        }

        int hitCount = 0;
        for (String token : keyTokens) {
            if (text.contains(token)) {
                hitCount++;
            }
        }
        // 핵심 토큰 1개 이상 매칭되면 strong hit 으로 간주
        return hitCount >= 1;
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
