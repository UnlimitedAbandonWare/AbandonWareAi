package com.example.lms.agent;

import com.example.lms.llm.ChatModel;
import com.example.lms.service.verification.ClaimVerifierService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


@Service
@RequiredArgsConstructor
public class SynthesisService {
    private static final Logger log = LoggerFactory.getLogger(SynthesisService.class);

    private final ChatModel chatModel;
    private final ClaimVerifierService claimVerifier;
    private final ObjectMapper om = new ObjectMapper();

    /**
     * Remove enclosing markdown code fences from a JSON string.  LLMs may
     * produce JSON wrapped in triple backticks (with optional language
     * specifier).  Jackson cannot parse such strings directly.  This helper
     * strips the opening and closing fences and trims whitespace.  If no
     * fences are present the input is returned unchanged.
     *
     * @param s raw string from the LLM
     * @return sanitized JSON without code fences
     */
    private static String sanitizeJson(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?\\s*", "");
            t = t.replaceFirst("\\s*```\\s*$", "");
        }
        return t;
    }

    // Use the default gemma3:27b model for verification to align with MOE routing defaults
    @Value("${openai.chat.model:gemma3:27b}")
    private String verifyModel;

    public Optional<VerifiedKnowledge> synthesizeAndVerify(List<String> rawData, CuriosityTriggerService.KnowledgeGap gap) {
        if (rawData == null || rawData.isEmpty() || gap == null) return Optional.empty();

        String context = String.join("\n\n", rawData);
        String prompt = """
                당신은 원본 데이터를 구조화하는 분석가입니다.
                아래 '주제'와 '원본'을 바탕으로 핵심 속성(JSON)만 생성하세요.
                반드시 이 스키마를 따르세요:
                {
                  "entity": "<엔티티명>",
                  "domain": "<도메인>",
                  "attributes": { "<속성>": "<값>", /* ... *&#47; },
                  "sources": ["<출처1>", "<출처2>", /* ... *&#47;]
                }
                
                [주제]
                entity=%s
                domain=%s
                description=%s
                
                [원본]
                %s
                """.formatted(
                nullToEmpty(gap.entityName()),
                nullToEmpty(gap.domain()),
                nullToEmpty(gap.description()),
                context
        );

        String synthesized = chatModel.generate(prompt, 0.2, 900);
        if (synthesized == null || synthesized.isBlank()) return Optional.empty();
        synthesized = sanitizeJson(synthesized);

        try {
            // 1) 자체 검증(기존 ClaimVerifierService 사용)
            var result = claimVerifier.verifyClaims(context, synthesized, verifyModel);
            String verifiedJson = result.verifiedAnswer(); // 프로젝트의 기존 시그니처에 맞춤

            // 2) 파싱 성공 여부로 confidence 근사
            double conf = isJson(verifiedJson) ? 0.82 : 0.0;
            if (conf < 0.75) return Optional.empty();

            // 3) 출처 추출(있으면)
            List<String> sources = extractSources(verifiedJson);
            return Optional.of(new VerifiedKnowledge(
                    emptyOr(gap.domain(), "GENERAL"),
                    emptyOr(gap.entityName(), "UNKNOWN"),
                    verifiedJson,
                    sources,
                    conf
            ));
        } catch (Exception e) {
            log.debug("[Synthesis] verify failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static String emptyOr(String s, String def) { return (s == null || s.isBlank()) ? def : s; }

    private boolean isJson(String s) {
        try {
            om.readTree(sanitizeJson(s));
            return true;
        } catch (Exception ignore) {
            return false;
        }
    }

    private List<String> extractSources(String json) {
        try {
            JsonNode n = om.readTree(sanitizeJson(json));
            if (n.has("sources") && n.get("sources").isArray()) {
                List<String> out = new ArrayList<>();
                n.get("sources").forEach(x -> out.add(x.asText("")));
                out.removeIf(String::isBlank);
                return out;
            }
        } catch (Exception ignore) {
            // ignore parse errors
        }
        return List.of();
    }
}