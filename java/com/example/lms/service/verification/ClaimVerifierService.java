

package com.example.lms.service.verification;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.scoring.AdaptiveScoringService;
import java.util.*;
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
@RequiredArgsConstructor
public class ClaimVerifierService {
    private static final Logger log = LoggerFactory.getLogger(ClaimVerifierService.class);

    private final ChatModel chatModel;
    private final AdaptiveScoringService scoring;
    private final KnowledgeBaseService kb;

    /** 검증 결과를 담는 레코드. 검증된 답변과 지원되지 않은 주장 목록을 포함합니다. */
    public record VerificationResult(String verifiedAnswer, List<String> unsupportedClaims) {}

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=\\.|!|\\?|\\n)");

    public VerificationResult verifyClaims(String context, String draftAnswer, String model) {
        if (draftAnswer == null || draftAnswer.isBlank()) {
            return new VerificationResult("정보 없음", List.of());
        }
        try {
            List<String> claims = extractClaims(draftAnswer, model);
            if (claims.isEmpty()) {
                return new VerificationResult(draftAnswer, List.of());
            }

            List<Boolean> verdicts = judgeClaims(context, claims, model);

            List<String> unsupportedClaims = new ArrayList<>();
            String filteredAnswer = rebuildAnswer(draftAnswer, claims, verdicts, unsupportedClaims);

            String finalAnswer = filteredAnswer.isBlank() ? "정보를 찾을 수 없습니다." : filteredAnswer;

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
            } catch (Exception ignore) { /* 안전 무시 */ }

            return new VerificationResult(finalAnswer, unsupportedClaims);
        } catch (Exception e) {
            log.error("Claim verification 중 예외 발생. 원본 답변을 안전하게 반환합니다.", e);
            return new VerificationResult(draftAnswer, List.of());
        }
    }

    private List<String> extractClaims(String draft, String model) {
        // /* ... */ (내용 동일) /* ... */
        String prompt = """
          Extract the core factual claims from the ANSWER as a JSON array of strings (max 8).
          Keep each claim concise and self-contained. Output JSON only.

          ANSWER:
          %s
          """.formatted(draft);
        // Use the ChatModel to execute the prompt directly.  Temperature and top-p
        // parameters cannot be tuned per call; the ChatModel bean should
        // already be configured with appropriate defaults.
        String json = callChatModel(prompt);
        return parseJsonArray(json);
    }

    private List<Boolean> judgeClaims(String context, List<String> claims, String model) {
        // /* ... */ (내용 동일) /* ... */
        String prompt = """
          For each CLAIM[i], answer STRICTLY "true" or "false" if it is directly supported by CONTEXT.
          Treat **pairing/synergy** as true only with explicit synergy cues (e.g., "잘 어울린다", "시너지", "조합", "함께 쓰면 좋다").
          Mere stat comparisons or co-mentions are not sufficient evidence and should be "false".
          Return a JSON array of booleans with the same order and length as CLAIMS.

          CONTEXT:
          %s

          CLAIMS:
          %s
          """.formatted(context, claims.toString());
        String json = callChatModel(prompt);
        return parseJsonBooleans(json, claims.size());
    }

    private String rebuildAnswer(String draft, List<String> claims, List<Boolean> verdicts, List<String> unsupportedClaims) {
        Set<String> unsupported = new HashSet<>();
        for (int i = 0; i < claims.size(); i++) {
            if (i < verdicts.size() && !verdicts.get(i)) {
                unsupported.add(claims.get(i));
                unsupportedClaims.add(claims.get(i));
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

    private List<String> parseJsonArray(String raw) {
        // /* ... */ (내용 동일) /* ... */
        if (raw == null) return Collections.emptyList();
        try {
            String s = raw.trim();
            if (!s.startsWith("[") || !s.endsWith("]")) return List.of();
            s = s.substring(1, s.length() - 1);
            if (s.isBlank()) return Collections.emptyList();

            return Arrays.stream(s.split("\\s*,\\s*(?=\")"))
                    .map(item -> item.replaceAll("^\\s*\"|\"\\s*$", "").trim())
                    .filter(item -> !item.isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("JSON array of strings 파싱 실패: {}", raw, e);
            return Collections.emptyList();
        }
    }

    private List<Boolean> parseJsonBooleans(String raw, int expectedSize) {
        // /* ... */ (내용 동일) /* ... */
        if (raw == null) return Collections.nCopies(expectedSize, false);
        List<Boolean> out = new ArrayList<>();
        try {
            String s = raw.replaceAll("[^a-zA-Z,\\[\\]]", "").trim();
            if (!s.startsWith("[") || !s.endsWith("]")) return Collections.nCopies(expectedSize, false);

            s = s.substring(1, s.length() - 1);
            if (s.isBlank()) return Collections.nCopies(expectedSize, false);

            Arrays.stream(s.split(","))
                    .map(item -> item.trim().toLowerCase(Locale.ROOT))
                    .forEach(item -> out.add("true".equals(item)));
        } catch (Exception e) {
            log.warn("JSON array of booleans 파싱 실패: {}", raw, e);
        }
        while (out.size() < expectedSize) {
            out.add(false);
        }
        return out;
    }

    /**
     * Helper method that delegates to the injected ChatModel.  This method
     * sends the provided prompt as a single user message and returns the
     * assistant's response text.  If the ChatModel returns null the empty
     * string is returned instead.  Any exceptions are caught and logged
     * and an empty string is returned to allow graceful degradation.
     *
     * @param prompt the prompt to send
     * @return the AI response text or an empty string
     */
    private String callChatModel(String prompt) {
        try {
            var res = chatModel.chat(UserMessage.from(prompt));
            if (res == null || res.aiMessage() == null) return "";
            var ai = res.aiMessage();
            return ai.text() == null ? "" : ai.text();
        } catch (Exception e) {
            log.debug("[ClaimVerifier] ChatModel call failed: {}", e.toString());
            return "";
        }
    }
}