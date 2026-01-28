package com.example.lms.agent;

import com.example.lms.llm.ChatModel;
import com.example.lms.service.chat.ChatHistoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * CuriosityTriggerService
 *
 * 역할:
 * - 최근 대화 로그에서 "지식 공백" 후보를 한 개 찾아내는 에이전트 모듈.
 * - LLM은 JSON 한 객체만 반환하도록 프롬프트를 주되,
 * 실제 응답에는 자연어/코드펜스가 섞일 수 있기 때문에
 * lenient JSON 파서로 방어한다.
 *
 * 설계 원칙:
 * - 로그가 없으면 조용히 no-op (Optional.empty).
 * - JSON 파싱이 실패하면 조용히 no-op.
 * - description/initialQuery 둘 중 하나라도 비어 있으면 no-op.
 */
@Service
@RequiredArgsConstructor
public class CuriosityTriggerService {

    private static final Logger log = LoggerFactory.getLogger(CuriosityTriggerService.class);

    private final ChatModel chatModel;
    private final Optional<ChatHistoryService> chatHistory;

    private final Optional<KnowledgeGapLogger> gapLogger;

    /** JSON 파싱용 ObjectMapper (스레드 세이프하게 재사용). */
    private final ObjectMapper om = new ObjectMapper();

    @Value("${agent.knowledge-curation.gap-prompt-max-chars:4000}")
    private int maxChars;

    public static record KnowledgeGap(
            String description,
            String initialQuery,
            String domain,
            String entityName) {
    }

    /**
     * 최근 로그에서 "지식 공백" 하나를 찾아낸다.
     * 실패할 경우 Optional.empty()를 반환하여 파이프라인이 조용히 종료되도록 한다.
     */
    public Optional<KnowledgeGap> findKnowledgeGap() {
        String logs = chatHistory
                .map(svc -> safeTruncate(svc.summarizeRecentLowConfidence(50), maxChars))
                .orElse("");

        if (logs.isBlank()) {
            // Fallback: if chat history summary is unavailable, use recent
            // KnowledgeGapLogger events.
            logs = gapLogger
                    .map(gl -> safeTruncate(buildGapFallback(gl.snapshotRecent(30), maxChars), maxChars))
                    .orElse("");
        }

        if (logs.isBlank()) {
            log.debug("[Curiosity] No log summary available; skip gap detection.");
            return Optional.empty();
        }

        String prompt = """
                당신은 지식 큐레이터 에전트의 '호기심' 모듈입니다.
                아래 로그 요약에서, 시스템이 제대로 답하지 못한 '가장 중요한 지식 공백'을 하나만 추출하세요.

                출력 형식 규칙:
                - 반드시 유효한 JSON 객체 한 개만 출력하세요.
                - JSON 앞뒤에 설명, 문장, 마크다운, 코드블록을 절대 넣지 마세요.

                JSON 스키마:
                {
                  "description": "/* 사람이 이해할 수 있는 한 문장 설명 */",
                  "initialQuery": "/* 검색/조사를 시작할 대표 질문 */",
                  "domain": "PRODUCT|GAME|GENERAL|EDU|OTHER",
                  "entityName": "/* 선택 사항: 핵심 엔티티 이름 */"
                }

                [로그 요약]
                %s
                """.formatted(logs);

        try {
            String raw = chatModel.generate(prompt, 0.2, 400);
            KnowledgeGap gap = parseGapLenient(raw);
            if (gap == null) {
                return Optional.empty();
            }
            if (gap.description().isBlank() || gap.initialQuery().isBlank()) {
                // 불완전한 갭은 버린다.
                return Optional.empty();
            }
            return Optional.of(gap);
        } catch (Exception e) {
            // curiosity는 실패해도 메인 기능을 막으면 안 되므로 debug 레벨로만 남긴다.
            log.debug("[Curiosity] parsing failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * LLM 응답(raw)을 lenient하게 JSON으로 해석하고 KnowledgeGap으로 변환한다.
     * - 코드펜스 제거
     * - 앞뒤 자연어 제거(최초 '{'~마지막 '}' 범위만 다시 시도)
     * - 그래도 실패하면 fallback으로 description에 raw를 넣고 나머지는 비운다.
     */
    private KnowledgeGap parseGapLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = sanitizeJson(raw);
        try {
            JsonNode node = tryParseJson(candidate);
            return new KnowledgeGap(
                    text(node, "description"),
                    text(node, "initialQuery"),
                    text(node, "domain"),
                    text(node, "entityName"));
        } catch (Exception e) {
            // 완전히 JSON이 아닐 경우에는 fallback JSON을 구성하되,
            // downstream에서 description/initialQuery 검증을 한 번 더 거친다.
            log.debug("[Curiosity] JSON parse error, raw='{}'", safeTruncate(candidate, 200));
            ObjectNode fallback = om.createObjectNode();
            fallback.put("description", safeTruncate(candidate, 200));
            fallback.put("initialQuery", "");
            fallback.put("domain", "GENERAL");
            fallback.put("entityName", "");
            return new KnowledgeGap(
                    fallback.get("description").asText(""),
                    fallback.get("initialQuery").asText(""),
                    fallback.get("domain").asText(""),
                    fallback.get("entityName").asText(""));
        }
    }

    /**
     * 1차: 그대로 파싱 시도
     * 2차: 문자열에서 최초 '{' 와 마지막 '}' 사이만 잘라 다시 파싱
     */
    private JsonNode tryParseJson(String candidate) throws Exception {
        try {
            return om.readTree(candidate);
        } catch (Exception e) {
            int start = candidate.indexOf('{');
            int end = candidate.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String sliced = candidate.substring(start, end + 1);
                return om.readTree(sliced);
            }
            throw e;
        }
    }

    /**
     * 코드펜스로 둘러싸인 JSON에서 fence를 제거한다.
     * 예: ```json { ... } ``` → { ... }
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

    private static String text(JsonNode n, String key) {
        return (n != null && n.hasNonNull(key)) ? n.get(key).asText("") : "";
    }

    private static String safeTruncate(String s, int max) {
        if (s == null)
            return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String buildGapFallback(java.util.List<KnowledgeGapLogger.GapEvent> events, int maxChars) {
        if (events == null || events.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Recent knowledge-gap signals (fallback):\n");

        for (KnowledgeGapLogger.GapEvent e : events) {
            if (e == null) {
                continue;
            }

            String q = truncate(e.getQuery(), 180);
            String domain = truncate(e.getDomain(), 80);
            String subject = truncate(e.getSubject(), 80);
            String intent = truncate(e.getIntent(), 80);

            sb.append("- query: ").append(q);
            if (!domain.isBlank())
                sb.append(" | domain: ").append(domain);
            if (!subject.isBlank())
                sb.append(" | subject: ").append(subject);
            if (!intent.isBlank())
                sb.append(" | intent: ").append(intent);
            sb.append('\n');

            if (sb.length() >= maxChars) {
                sb.append("...(truncated)\n");
                break;
            }
        }

        String out = sb.toString();
        if (out.length() > maxChars) {
            out = out.substring(0, Math.max(0, maxChars - 1)) + "…";
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null)
            return "";
        String t = s.strip();
        if (t.length() <= max)
            return t;
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }
}
