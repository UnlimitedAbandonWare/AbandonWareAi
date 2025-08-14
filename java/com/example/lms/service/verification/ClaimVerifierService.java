
        package com.example.lms.service.verification;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimVerifierService {

    private final OpenAiService openAi;

    /** 검증 결과를 담는 레코드. 검증된 답변과 지원되지 않은 주장 목록을 포함합니다. */
    public record VerificationResult(String verifiedAnswer, List<String> unsupportedClaims) {}

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=\\.|!|\\?|\\n)");

    /**
     * 초안 답변의 주장들을 검증하고, 지원되지 않는 주장이 포함된 문장을 제거합니다.
     *
     * @param context       사실 판단의 근거가 되는 컨텍스트
     * @param draftAnswer   검증할 LLM의 초안 답변
     * @param model         사용할 OpenAI 모델
     * @return 검증이 완료된 답변과 제거된 주장 목록이 담긴 {@link VerificationResult}
     */
    public VerificationResult verifyClaims(String context, String draftAnswer, String model) {
        if (draftAnswer == null || draftAnswer.isBlank()) {
            return new VerificationResult("정보 없음", List.of());
        }
        try {
            // 1. LLM을 통해 초안에서 핵심 주장 추출
            List<String> claims = extractClaims(draftAnswer, model);
            if (claims.isEmpty()) {
                return new VerificationResult(draftAnswer, List.of());
            }

            // 2. 컨텍스트를 기반으로 각 주장의 사실 여부 판정
            List<Boolean> verdicts = judgeClaims(context, claims, model);

            // 3. 'false' 판정된 주장을 포함한 문장을 제거하여 답변 재구성
            List<String> unsupportedClaims = new ArrayList<>();
            String filteredAnswer = rebuildAnswer(draftAnswer, claims, verdicts, unsupportedClaims);

            String finalAnswer = filteredAnswer.isBlank() ? "정보를 찾을 수 없습니다." : filteredAnswer;
            return new VerificationResult(finalAnswer, unsupportedClaims);
        } catch (Exception e) {
            log.error("Claim verification 중 예외 발생. 원본 답변을 안전하게 반환합니다.", e);
            return new VerificationResult(draftAnswer, List.of());
        }
    }

    /**
     * LLM을 호출하여 초안 답변에서 핵심 주장 목록을 추출합니다.
     */
    private List<String> extractClaims(String draft, String model) {
        String prompt = """
          Extract the core factual claims from the ANSWER as a JSON array of strings (max 8).
          Keep each claim concise and self-contained. Output JSON only.

          ANSWER:
          %s
          """.formatted(draft);
        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model(model).temperature(0d).topP(0.05d)
                .messages(List.of(new ChatMessage(ChatMessageRole.SYSTEM.value(), prompt)))
                .build();
        String json = openAi.createChatCompletion(req).getChoices().get(0).getMessage().getContent();
        return parseJsonArray(json);
    }

    /**
     * LLM을 호출하여 각 주장이 컨텍스트에 의해 지원되는지 판정합니다.
     */
    private List<Boolean> judgeClaims(String context, List<String> claims, String model) {
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
        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model(model).temperature(0d).topP(0.05d)
                .messages(List.of(new ChatMessage(ChatMessageRole.SYSTEM.value(), prompt)))
                .build();
        String json = openAi.createChatCompletion(req).getChoices().get(0).getMessage().getContent();
        return parseJsonBooleans(json, claims.size());
    }

    /**
     * 판정 결과를 바탕으로, 지원되지 않는 주장이 포함된 '문장'을 제거하여 답변을 재구성합니다.
     */
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
                // 문장에 unsupported 주장이 하나라도 포함되면 해당 문장 필터링
                .filter(sentence -> unsupported.stream().noneMatch(sentence::contains))
                .collect(Collectors.joining(" "))
                .trim();
    }

    // --- JSON Parsing Helper Methods ---

    private List<String> parseJsonArray(String raw) {
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
        if (raw == null) return Collections.nCopies(expectedSize, false);
        List<Boolean> out = new ArrayList<>();
        try {
            String s = raw.replaceAll("[^a-zA-Z,\\[\\]]", "").trim(); // true, false, 쉼표, 대괄호만 남김
            if (!s.startsWith("[") || !s.endsWith("]")) return Collections.nCopies(expectedSize, false);

            s = s.substring(1, s.length() - 1);
            if (s.isBlank()) return Collections.nCopies(expectedSize, false);

            Arrays.stream(s.split(","))
                    .map(item -> item.trim().toLowerCase(Locale.ROOT))
                    .forEach(item -> out.add("true".equals(item)));
        } catch (Exception e) {
            log.warn("JSON array of booleans 파싱 실패: {}", raw, e);
        }
        // 파싱이 잘못되거나 개수가 부족할 경우 false로 채움
        while (out.size() < expectedSize) {
            out.add(false);
        }
        return out;
    }
}