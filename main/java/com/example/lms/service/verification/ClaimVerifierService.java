

package com.example.lms.service.verification;

import ai.abandonware.nova.orch.llm.ExpectedFailureChatModel;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.llm.TimedChatModelCaller;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.UserMessage;
import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.scoring.AdaptiveScoringService;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * LLM을 사용하여 초안 답변에 포함된 개별 주장(Claim)을 컨텍스트와 비교하여 검증합니다.
 * <p>
 * 검증 파이프라인:
 * 1. <b>주장 추출:</b> 초안 답변에서 핵심 사실 주장들을 목록으로 분리합니다.
 * 2. <b>주장 판정:</b> 각 주장을 컨텍스트와 비교하여 'true'/'false' 판정을 내립니다.
 * 특히 '시너지/조합' 관련 주장은 컨텍스트에 명시적 단서가 있을 때만 'true'로 판정하도록 엄격하게 검사합니다.
 * 3. <b>답변 재구성:</b> 'false' 판정을 받은 주장이 포함된 문장 전체를 초안에서 제거하여 최종 답변을 생성합니다.
 * </p>
 */
@Service
public class ClaimVerifierService {
    private static final Logger log = LoggerFactory.getLogger(ClaimVerifierService.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Pattern UNSUPPORTED_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:owner[-_]?token|authorization|cookie|api[-_]?key|apikey|client[-_]?(?:secret|id)|subscription[-_]?token|password|secret|token)\\s*[:=]\\s*\\S+");
    private static final Pattern UNSUPPORTED_BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+\\S+");

    private final ChatModel chatModel;
    private final AdaptiveScoringService scoring;
    private final KnowledgeBaseService kb;
    private final TemporalConsistencyVerifier temporalVerifier;

    public ClaimVerifierService(@Qualifier("judgeChatModel") ChatModel chatModel,
            AdaptiveScoringService scoring,
            KnowledgeBaseService kb,
            TemporalConsistencyVerifier temporalVerifier) {
        this.chatModel = chatModel;
        this.scoring = scoring;
        this.kb = kb;
        this.temporalVerifier = temporalVerifier;
    }

    /** 검증 결과를 담는 레코드. 검증된 답변과 지원되지 않은 주장 목록을 포함합니다. */
    public record VerificationResult(
            String verifiedAnswer,
            List<String> unsupportedClaims,
            boolean outcomeKnown,
            boolean acceptedForMemory) {

        public VerificationResult(String verifiedAnswer, List<String> unsupportedClaims) {
            this(verifiedAnswer, unsupportedClaims, false, false);
        }

        public VerificationResult(
                String verifiedAnswer,
                List<String> unsupportedClaims,
                boolean outcomeKnown) {
            this(verifiedAnswer, unsupportedClaims, outcomeKnown, false);
        }
    }

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=\\.|!|\\?|\\n)");

    public VerificationResult verifyClaims(String context, String draftAnswer, String model) {
        if (draftAnswer == null || draftAnswer.isBlank()) {
            return new VerificationResult("정보 없음", List.of(), false, false);
        }
        try {
            ClaimExtraction extraction = extractClaimsDetailed(draftAnswer, model);
            if (!extraction.outcomeKnown()) {
                return new VerificationResult(draftAnswer, List.of(), false, false);
            }
            List<String> claims = extraction.claims();
            List<Boolean> verdicts = List.of();
            if (claims.isEmpty()) {
                traceJudgeFailSoft("judge_no_claims_extracted");
            } else {
                ClaimJudgment judgment = judgeClaimsDetailed(context, claims, model);
                if (!judgment.outcomeKnown()) {
                    return new VerificationResult(draftAnswer, List.of(), false, false);
                }
                verdicts = judgment.verdicts();
            }

            List<String> unsupportedClaims = new ArrayList<>();
            String filteredAnswer = rebuildAnswer(draftAnswer, claims, verdicts, unsupportedClaims);

            String finalAnswer = filteredAnswer.isBlank() ? "정보를 찾을 수 없습니다." : filteredAnswer;
            boolean temporalOutcomeKnown = !claims.isEmpty();
            boolean temporalAcceptedForMemory = !claims.isEmpty();

// --- 시간 정합성 검증 (TemporalConsistencyVerifier) ---
try {
    if (temporalVerifier != null) {
        List<String> evidences = (context == null || context.isBlank())
                ? List.of()
                : Arrays.stream(SENTENCE_SPLIT.split(context))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        var temporalResult = temporalVerifier.verify(finalAnswer, evidences, LocalDate.now());
        if (!temporalResult.isPass()) {
            String safeTemporalReason = SafeRedactor.traceLabelOrFallback(temporalResult.reason(), "unknown");
            log.warn("[ClaimVerifier] Temporal mismatch detected: {}", safeTemporalReason);
            finalAnswer = finalAnswer + "\n\n[시간 정합성 경고] " + safeTemporalReason;
            temporalAcceptedForMemory = false;
        }
    }
} catch (Exception ignore) {
    // Temporal checker failures must never break the chat flow
    log.debug("[ClaimVerifier] temporal verification failed. errorHash={} errorLength={}",
            SafeRedactor.hashValue(messageOf(ignore)), messageLength(ignore));
    traceJudgeFailSoft("temporal_verification_failed");
    temporalOutcomeKnown = false;
    temporalAcceptedForMemory = false;
}

            // --- 암묵 피드백(시너지 확신도) 반영 ---
            try {
                double conf = estimateSynergyConfidence(claims, verdicts);
                if (conf > 0.0 && kb != null && scoring != null) {
                    String domain = kb.inferDomain(draftAnswer);
                    var ents = kb.findMentionedEntities(domain, draftAnswer);
                    if (ents != null && ents.size() >= 2) {
                        var it = ents.iterator();
                        String subject = it.next();
                        String partner = it.next();
                        scoring.applyImplicitPositive(domain, subject, partner, conf);
                        log.debug("[ClaimVerifier] implicit+ (domain={}, subject={}, partner={}, conf={})",
                                domain, subject, partner, String.format(java.util.Locale.ROOT, "%.2f", conf));
                    }
                }
            } catch (Exception ignore) {
                log.debug("[ClaimVerifier] implicit positive update failed. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(ignore)), messageLength(ignore));
            }

            return new VerificationResult(
                    finalAnswer,
                    unsupportedClaims,
                    temporalOutcomeKnown,
                    temporalOutcomeKnown && temporalAcceptedForMemory);
        } catch (Exception e) {
            log.error("Claim verification failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            traceJudgeFailSoft("judge_processing_failed");
            return new VerificationResult(draftAnswer, List.of(), false, false);
        }
    }

    private ClaimExtraction extractClaimsDetailed(String draft, String model) {
        // /* ... */ (내용 동일) /* ... */
        String claimExtractionPrompt = """
          Extract the core factual claims from the ANSWER as a JSON array of strings (max 8).
          Keep each claim concise and self-contained. Output JSON only.

          ANSWER:
          %s
          """.formatted(draft);
        // Use the ChatModel to execute the prompt directly.  Temperature and top-p
        // parameters cannot be tuned per call; the ChatModel bean should
        // already be configured with appropriate defaults.
        JudgeCallResult response = callChatModelDetailed(claimExtractionPrompt);
        ParsedStringArray parsed = parseJsonArray(response.text());
        if (response.outcomeKnown() && !parsed.valid()) {
            traceJudgeFailSoft("judge_malformed_response");
        }
        return new ClaimExtraction(parsed.values(), response.outcomeKnown() && parsed.valid());
    }

    private ClaimJudgment judgeClaimsDetailed(String context, List<String> claims, String model) {
        // /* ... */ (내용 동일) /* ... */
        String claimJudgmentPrompt = """
          For each CLAIM[i], answer STRICTLY "true" or "false" if it is directly supported by CONTEXT.
          Treat **pairing/synergy** as true only with explicit synergy cues (e.g., "잘 어울린다", "시너지", "조합", "함께 쓰면 좋다").
          Mere stat comparisons or co-mentions are not sufficient evidence and should be "false".
          Return a JSON array of booleans with the same order and length as CLAIMS.

          CONTEXT:
          %s

          CLAIMS:
          %s
          """.formatted(context, claims.toString());
        JudgeCallResult response = callChatModelDetailed(claimJudgmentPrompt);
        ParsedBooleanArray parsed = parseJsonBooleans(response.text(), claims.size());
        if (response.outcomeKnown() && !parsed.valid()) {
            traceJudgeFailSoft("judge_malformed_response");
        }
        return new ClaimJudgment(
                parsed.values(),
                response.outcomeKnown() && parsed.valid());
    }

    private String rebuildAnswer(String draft, List<String> claims, List<Boolean> verdicts, List<String> unsupportedClaims) {
        Set<String> unsupported = new HashSet<>();
        for (int i = 0; i < claims.size(); i++) {
            if (i < verdicts.size() && !verdicts.get(i)) {
                unsupported.add(claims.get(i));
                unsupportedClaims.add(safeUnsupportedClaim(claims.get(i)));
            }
        }

        if (unsupported.isEmpty()) {
            return draft;
        }

        return Arrays.stream(SENTENCE_SPLIT.split(draft))
                .map(String::trim)
                .filter(sentence -> !sentence.isBlank())
                .filter(sentence -> unsupported.stream().noneMatch(sentence::contains))
                .collect(Collectors.joining(" "))
                .trim();
    }

    private static String safeUnsupportedClaim(String claim) {
        String safe = SafeRedactor.safeMessage(claim, 240);
        if (safe == null || safe.isBlank()) {
            return "";
        }
        safe = UNSUPPORTED_BEARER_TOKEN.matcher(safe).replaceAll("[redacted]");
        safe = UNSUPPORTED_SECRET_ASSIGNMENT.matcher(safe).replaceAll("[redacted]");
        return safe.trim();
    }

    // --- Helper Methods Moved Here ---
    // 아래 메서드들이 rebuildAnswer 메서드 밖으로 이동했습니다.

    private static final String[] SYNERGY_CUES = {"시너지", "조합", "궁합", "함께", "어울", "콤보"};

    private static boolean isSynergyClaim(String s) {
        if (s == null) return false;
        String t = s.toLowerCase(java.util.Locale.ROOT);
        for (String cue : SYNERGY_CUES) {
            if (t.contains(cue)) return true;
        }
        return false;
    }

    /** 시너지 관련 주장에 한해 true 비율로 확신도 산출(없으면 0.0). */
    private static double estimateSynergyConfidence(List<String> claims, List<Boolean> verdicts) {
        if (claims == null || verdicts == null) return 0.0;
        int total = 0, ok = 0;
        for (int i = 0; i < Math.min(claims.size(), verdicts.size()); i++) {
            if (isSynergyClaim(claims.get(i))) {
                total++;
                if (Boolean.TRUE.equals(verdicts.get(i))) ok++;
            }
        }
        if (total == 0) return 0.0;
        double ratio = ok / (double) total;
        // 소수의 true에 의한 과대평가 방지: 최소 2개 이상일 때만 가중 보너스
        if (ok >= 2) ratio = Math.min(1.0, ratio + 0.1);
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    // --- JSON Parsing Helper Methods ---

    private ParsedStringArray parseJsonArray(String raw) {
        // /* ... */ (내용 동일) /* ... */
        if (raw == null) return new ParsedStringArray(List.of(), false);
        try {
            JsonNode root = JSON_MAPPER.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(raw);
            if (root == null || !root.isArray()) {
                return new ParsedStringArray(List.of(), false);
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isTextual()) {
                    return new ParsedStringArray(List.of(), false);
                }
                String value = item.textValue() == null ? "" : item.textValue().trim();
                if (value.isEmpty()) {
                    return new ParsedStringArray(List.of(), false);
                }
                values.add(value);
            }
            return new ParsedStringArray(List.copyOf(values), true);
        } catch (Exception e) {
            log.warn("JSON array of strings parse failed rawHash={} rawLength={} errorHash={} errorLength={}",
                    SafeRedactor.hashValue(raw), raw == null ? 0 : raw.length(),
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return new ParsedStringArray(List.of(), false);
        }
    }

    private ParsedBooleanArray parseJsonBooleans(String raw, int expectedSize) {
        // /* ... */ (내용 동일) /* ... */
        if (raw == null || expectedSize < 0) {
            return new ParsedBooleanArray(List.of(), false);
        }
        try {
            JsonNode root = JSON_MAPPER.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(raw);
            if (root == null || !root.isArray() || root.size() != expectedSize) {
                return new ParsedBooleanArray(List.of(), false);
            }
            List<Boolean> values = new ArrayList<>(expectedSize);
            for (JsonNode item : root) {
                if (!item.isBoolean()) {
                    return new ParsedBooleanArray(List.of(), false);
                }
                values.add(item.booleanValue());
            }
            return new ParsedBooleanArray(List.copyOf(values), true);
        } catch (Exception e) {
            log.warn("JSON array of booleans parse failed rawHash={} rawLength={} errorHash={} errorLength={}",
                    SafeRedactor.hashValue(raw), raw == null ? 0 : raw.length(),
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return new ParsedBooleanArray(List.of(), false);
        }
    }

    /**
     * Helper method that delegates to the injected ChatModel.  This method
     * sends the provided prompt as a single user message and returns the
     * assistant's response text.  If the ChatModel returns null the empty
     * string is returned instead.  Any exceptions are caught and logged
     * and an empty string is returned to allow graceful degradation.
     *
     * @param claimVerifierPrompt the prompt to send
     * @return the AI response text or an empty string
     */
    private String callChatModel(String claimVerifierPrompt) {
        return callChatModelDetailed(claimVerifierPrompt).text();
    }

    private JudgeCallResult callChatModelDetailed(String claimVerifierPrompt) {
        if (chatModel == null || chatModel instanceof ExpectedFailureChatModel) {
            traceJudgeFailSoft("judge_model_unavailable");
            return new JudgeCallResult("", false);
        }
        try {
            TimeBudget requestBudget = TimeBudgetContext.get();
            if (requestBudget != null && requestBudget.expired()) {
                traceJudgeFailSoft("request_budget_exhausted");
                return new JudgeCallResult("", false);
            }
            var userMessage = UserMessage.from(claimVerifierPrompt);
            dev.langchain4j.data.message.AiMessage ai;
            if (requestBudget == null) {
                var res = chatModel.chat(userMessage);
                ai = res == null ? null : res.aiMessage();
            } else {
                long remainingMs = requestBudget.remainingMillis();
                if (remainingMs <= 0L) {
                    traceJudgeFailSoft("request_budget_exhausted");
                    return new JudgeCallResult("", false);
                }
                ai = TimedChatModelCaller.chat(
                        chatModel,
                        List.of(userMessage),
                        Duration.ofMillis(remainingMs),
                        "claim_verifier_judge",
                        chatModel.getClass().getName());
            }
            if (ai == null) {
                traceJudgeFailSoft("judge_empty_response");
                return new JudgeCallResult("", false);
            }
            String text = ai.text() == null ? "" : ai.text().trim();
            if (text.isBlank()) {
                traceJudgeFailSoft("judge_empty_response");
                return new JudgeCallResult("", false);
            }
            return new JudgeCallResult(text, true);
        } catch (Exception e) {
            traceJudgeFailSoft("judge_call_failed");
            log.debug("[ClaimVerifier] ChatModel call failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return new JudgeCallResult("", false);
        }
    }

    private record JudgeCallResult(String text, boolean outcomeKnown) {
    }

    private record ClaimExtraction(List<String> claims, boolean outcomeKnown) {
    }

    private record ClaimJudgment(List<Boolean> verdicts, boolean outcomeKnown) {
    }

    private record ParsedStringArray(List<String> values, boolean valid) {
    }

    private record ParsedBooleanArray(List<Boolean> values, boolean valid) {
    }

    private static void traceJudgeFailSoft(String reason) {
        try {
            TraceStore.put("claimVerifier.judge.path", "judgeChatModel");
            TraceStore.put("claimVerifier.judge.disabledReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (RuntimeException ignore) {
            log.debug("[ClaimVerifier] fail-soft stage={}", "judge.trace");
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
}
