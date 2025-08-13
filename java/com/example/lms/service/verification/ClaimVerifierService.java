// src/main/java/com/example/lms/service/verification/ClaimVerifierService.java
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

    public record Result(String verifiedAnswer, List<String> unsupportedClaims) {}

    public Result verifyClaims(String context, String draft, String model) {
        try {
            List<String> claims = extractClaims(draft, model);
            if (claims.isEmpty()) return new Result(draft, List.of());
            List<Boolean> verdicts = judgeClaims(context, claims, model);

            // unsupported 제거 후 답변 재구성(단순 문장 필터)
            List<String> unsupported = new ArrayList<>();
            String filtered = rebuild(draft, claims, verdicts, unsupported);
            return new Result(filtered.isBlank() ? "정보 없음" : filtered, unsupported);
        } catch (Exception e) {
            // 실패 시 원문 유지
            return new Result(draft, List.of());
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
        String prompt = """
      For each CLAIM[i], answer STRICTLY "true" or "false" if it is directly supported by CONTEXT.
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

    // --- helpers ---
    private static List<String> parseJsonArray(String raw) {
        try {
            // 매우 경량 파서(환경에 따라 Jackson 사용 권장)
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

    private static String rebuild(String draft, List<String> claims, List<Boolean> ok, List<String> unsupported) {
        if (claims.isEmpty() || ok.isEmpty()) return draft;
        for (int i=0;i<claims.size();i++) {
            if (!ok.get(i)) unsupported.add(claims.get(i));
        }
        // 간단: unsupported claim 문장 제거
        String filtered = draft;
        for (String bad : unsupported) {
            filtered = filtered.replace(bad, "");
        }
        return filtered.replaceAll("\\s{2,}", " ").trim();
    }
}
