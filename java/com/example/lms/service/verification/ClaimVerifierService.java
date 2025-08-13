package com.example.lms.service.verification;

import com.theokanning.openai.completion.chat.*;
import com.theokanning.openai.service.OpenAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ClaimVerifierService {

    private final OpenAiService openAi;

    public record VerificationResult(String verifiedAnswer, List<String> unsupportedClaims) {}

    public VerificationResult verifyClaims(String context, String draftAnswer, String model) {
        try {
            List<String> claims = extractClaims(draftAnswer, model);
            if (claims.isEmpty()) return new VerificationResult(draftAnswer, List.of());

            // 두 버전 중 더 강화된 프롬프트를 사용하는 judgeClaims 호출
            List<Boolean> verdicts = judgeClaims(context, claims, model);

            List<String> unsupported = new ArrayList<>();
            String filtered = rebuild(draftAnswer, claims, verdicts, unsupported);
            return new VerificationResult(filtered.isBlank() ? "정보 없음" : filtered, unsupported);
        } catch (Exception e) {
            // 예외 발생 시 안전하게 원본 답변 반환
            return new VerificationResult(draftAnswer, List.of());
        }
    }

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

    private List<Boolean> judgeClaims(String context, List<String> claims, String model) {
        // - (제거) 단순 true/false 질문
        // + (개선) "조합/시너지"와 "단순 스탯 비교/동시 언급"을 구분하도록 명시적으로 지시
        String prompt = """
          For each CLAIM[i], answer STRICTLY "true" or "false" if it is directly supported by CONTEXT.
          Treat **pairing/synergy** as true only with explicit synergy cues (e.g., "잘 어울린다", "시너지", "조합", "함께 쓰면 좋다").
          Mere stat comparisons / co-mentions are false.
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

    // --- Helper Methods (두 버전에서 동일) ---
    private static List<String> parseJsonArray(String raw) {
        try {
            String s = raw.trim();
            if (!s.startsWith("[")) return List.of();
            s = s.substring(1, s.lastIndexOf(']'));
            List<String> out = new ArrayList<>();
            for (String item : s.split("\\s*,\\s*(?=\")")) {
                String v = item.replaceAll("^\\s*\"|\"\\s*$", "");
                if (!v.isBlank()) out.add(v);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<Boolean> parseJsonBooleans(String raw, int n) {
        List<Boolean> out = new ArrayList<>();
        try {
            String s = raw.replaceAll("[^\\[\\]tfrueals,]", "").trim();
            if (!s.startsWith("[")) return Collections.nCopies(n, Boolean.FALSE);
            s = s.substring(1, s.lastIndexOf(']'));
            for (String item : s.split("\\s*,\\s*")) {
                out.add(item.toLowerCase(Locale.ROOT).startsWith("t"));
            }
        } catch (Exception ignore) {}
        while (out.size() < n) out.add(Boolean.FALSE);
        return out;
    }

    private static String rebuild(String draft, List<String> claims, List<Boolean> verdicts, List<String> unsupported) {
        if (claims.isEmpty() || verdicts.isEmpty()) return draft;
        for (int i = 0; i < claims.size(); i++) {
            if (i < verdicts.size() && !verdicts.get(i)) {
                unsupported.add(claims.get(i));
            }
        }
        String filtered = draft;
        for (String badClaim : unsupported) {
            // 문장 단위로 제거하기 위해 정규식 사용 고려 가능
            filtered = filtered.replace(badClaim, "");
        }
        return filtered.replaceAll("\\s{2,}", " ").trim();
    }
}