package com.example.lms.agent;

import com.example.lms.llm.ChatModel;
import com.example.lms.service.verification.ClaimVerifierService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SynthesisService {
    private static final Logger log = LoggerFactory.getLogger(SynthesisService.class);

    private final ChatModel chatModel;
    private final ClaimVerifierService claimVerifier;
    private final ObjectMapper om = new ObjectMapper();

    // Use the default gemma3:27b model for verification to align with MOE routing defaults
    @Value("${openai.chat.model:gemma3:27b}")
    private String verifyModel;

    /** Minimum number of URL-like sources required to treat the KB row as "verified enough". */
    @Value("${agent.knowledge-curation.min-url-sources:1}")
    private int minUrlSources;

    /** If too many attributes are UNKNOWN-like, mark verification_needed. */
    @Value("${agent.knowledge-curation.max-unknown-attr-ratio:0.50}")
    private double maxUnknownAttrRatio;

    /** CSV list of values that are treated as UNKNOWN (case-insensitive). */
    @Value("${agent.knowledge-curation.unknown-value-tokens:unknown,unconfirmed,n/a,na,none,미상,불명,정보없음}")
    private String unknownValueTokensCsv;

    /**
     * Remove enclosing markdown code fences from a JSON string.
     * LLMs may produce JSON wrapped in triple backticks (with optional language specifier).
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
                  "attributes": { "<속성>": "<값>", /* ... */ },
                  "sources": ["<출처1>", "<출처2>", /* ... */]
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
            ClaimVerifierService.VerificationResult result = claimVerifier.verifyClaims(context, synthesized, verifyModel);
            String verifiedJson = result.verifiedAnswer(); // 프로젝트의 기존 시그니처에 맞춤
            if (verifiedJson == null || verifiedJson.isBlank()) {
                return Optional.empty();
            }
            verifiedJson = sanitizeJson(verifiedJson);

            // 2) 파싱 성공 여부로 confidence 근사
            double conf = isJson(verifiedJson) ? 0.82 : 0.0;
            if (conf < 0.75) return Optional.empty();

            JsonNode root = om.readTree(verifiedJson);

            // 3) 출처 추출 및 evidence 기반 verification_needed 계산
            List<String> sources = extractSources(root);
            int urlCount = countUrlSources(sources);
            double unknownRatio = computeUnknownAttrRatio(root.path("attributes"));

            boolean verificationNeeded = urlCount < Math.max(0, minUrlSources)
                    || unknownRatio > Math.max(0.0, maxUnknownAttrRatio);

            // 4) JSON에 verification 힌트 삽입 (Downstream: KB indexer => shadow-write)
            if (root instanceof ObjectNode obj) {
                obj.put("verification_needed", verificationNeeded);
                obj.put("source_count", sources.size());
                obj.put("source_url_count", urlCount);
                obj.put("unknown_attr_ratio", unknownRatio);
                verifiedJson = om.writeValueAsString(obj);
            }

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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyOr(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    private boolean isJson(String s) {
        try {
            om.readTree(sanitizeJson(s));
            return true;
        } catch (Exception ignore) {
            return false;
        }
    }

    private List<String> extractSources(JsonNode root) {
        try {
            if (root != null && root.has("sources") && root.get("sources").isArray()) {
                List<String> out = new ArrayList<>();
                root.get("sources").forEach(x -> out.add(x.asText("")));
                out.removeIf(s -> s == null || s.isBlank());
                // trim + dedupe while keeping order
                LinkedHashSet<String> uniq = out.stream()
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                return new ArrayList<>(uniq);
            }
        } catch (Exception ignore) {
            // ignore parse errors
        }
        return List.of();
    }

    private int countUrlSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) return 0;
        int c = 0;
        for (String s : sources) {
            if (looksLikeUrl(s)) c++;
        }
        return c;
    }

    private boolean looksLikeUrl(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isBlank()) return false;

        String lower = t.toLowerCase(Locale.ROOT);

        // Explicit URLs
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true;

        // Common pseudo-url patterns (keep conservative)
        if (lower.startsWith("www.")) return true;

        // Domain-like: has a dot, no spaces, and contains at least one letter
        if (t.indexOf(' ') < 0 && t.contains(".") && t.length() >= 6) {
            // avoid "v1.2.3" style tokens without letters
            boolean hasLetter = false;
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (Character.isLetter(ch)) {
                    hasLetter = true;
                    break;
                }
            }
            if (hasLetter) return true;
        }
        return false;
    }

    private double computeUnknownAttrRatio(JsonNode attrsNode) {
        if (attrsNode == null || !attrsNode.isObject()) return 0.0;

        Set<String> unknownTokens = Arrays.stream(unknownValueTokensCsv == null ? new String[0] : unknownValueTokensCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        int total = 0;
        int unknown = 0;

        Iterator<Map.Entry<String, JsonNode>> it = attrsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e == null) continue;
            String v = "";
            JsonNode n = e.getValue();
            if (n != null && n.isValueNode()) {
                v = n.asText("");
            } else if (n != null) {
                v = n.toString();
            }
            v = (v == null) ? "" : v.trim();

            total++;
            if (v.isBlank() || isUnknownValue(v, unknownTokens)) {
                unknown++;
            }
        }

        if (total <= 0) return 0.0;
        return (double) unknown / (double) total;
    }

    private static boolean isUnknownValue(String v, Set<String> unknownTokens) {
        if (v == null) return true;
        String t = v.trim();
        if (t.isBlank()) return true;

        String lower = t.toLowerCase(Locale.ROOT);

        if (unknownTokens != null && !unknownTokens.isEmpty()) {
            if (unknownTokens.contains(lower)) return true;
        }

        // heuristic
        if (lower.equals("unknown") || lower.equals("unconfirmed") || lower.equals("n/a") || lower.equals("na") || lower.equals("none")) {
            return true;
        }
        return false;
    }
}
