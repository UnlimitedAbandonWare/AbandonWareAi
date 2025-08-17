
// src/main/java/com/example/lms/prompt/PromptBuilder.java
        package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PromptBuilder {

    private static final String WEB_PREFIX = """
            ### LIVE WEB RESULTS
            %s
            """;
    private static final String RAG_PREFIX = """
            ### VECTOR RAG
            %s
            """;
    private static final String MEM_PREFIX = """
            ### LONG-TERM MEMORY
            %s
            """;
    private static final String HIS_PREFIX = """
            ### HISTORY
            %s
            """;
    // [NEW] Uploaded file context has the highest authority
    private static final String FILE_PREFIX = """
            ### UPLOADED FILE CONTEXT
            %s
            """;
    // [NEW] 직전 답변 명시 섹션
    private static final String PREV_PREFIX = """
            ### PREVIOUS_ANSWER
            %s
            """;
    // 🆕 치유 모드에서 사용할 초안 명시 섹션
    private static final String DRAFT_PREFIX = """
            ### DRAFT_ANSWER
            %s
            """;

    // Inject Spring Environment to look up persona instructions
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment env;

    /** 컨텍스트 본문(자료 영역) */
    public String build(PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx != null) {
            // Inject uploaded file context first when present
            if (ctx.fileContext() != null && !ctx.fileContext().isBlank()) {
                sb.append(FILE_PREFIX.formatted(ctx.fileContext()));
            }

            // 🆕 치유 모드일 때는 초안(DRAFT)을 명시적으로 주입
            if ("CORRECTIVE_REGENERATION".equalsIgnoreCase(Objects.toString(ctx.systemInstruction(), ""))
                    && StringUtils.hasText(ctx.lastAssistantAnswer())) {
                sb.append(DRAFT_PREFIX.formatted(ctx.lastAssistantAnswer()));
            }
            // [NEW] 후속 질문이면 직전 답변을 먼저 명확히 제공
            if (isFollowUp(ctx.userQuery()) && StringUtils.hasText(ctx.lastAssistantAnswer())) {
                sb.append(PREV_PREFIX.formatted(ctx.lastAssistantAnswer()));
            }
            if (ctx.web() != null && !ctx.web().isEmpty()) {
                sb.append(WEB_PREFIX.formatted(join(ctx.web())));
            }
            if (ctx.rag() != null && !ctx.rag().isEmpty()) {
                sb.append(RAG_PREFIX.formatted(join(ctx.rag())));
            }
            if (StringUtils.hasText(ctx.memory())) {
                sb.append(MEM_PREFIX.formatted(ctx.memory()));
            }
            if (StringUtils.hasText(ctx.history())) {
                sb.append(HIS_PREFIX.formatted(ctx.history()));
            }
        }
        return sb.toString();
    }

    /** 시스템 인스트럭션(정책/의도/제약 영역) */
    public String buildInstructions(PromptContext ctx) {
        StringBuilder sys = new StringBuilder();
        sys.append("### INSTRUCTIONS\n");
        // Insert persona instructions when available.  Personas are defined via
        // abandonware.persona.* keys in configuration.  If the CognitiveState
        // contains a persona value, we look up the corresponding instruction
        // and prepend it to the instruction section so that it guides the
        // assistant's style and tone for this turn.
        if (ctx != null && ctx.cognitiveState() != null && ctx.cognitiveState().persona() != null) {
            try {
                String personaKey = "abandonware.persona." + ctx.cognitiveState().persona().toLowerCase(java.util.Locale.ROOT);
                String personaInstr = env.getProperty(personaKey);
                // Defensive measure: prevent excessively long persona instructions from blowing up the
                // token budget.  Some persona definitions may include very long descriptions or
                // examples.  We cap the instructions at a reasonable length.  If a longer
                // instruction is defined, truncate it to PERSONA_MAX_CHARS characters.  This avoids
                // overflowing the token budget and ensures downstream services like ContextOrchestrator
                // can still operate within configured limits.  The limit value can be adjusted via
                // the abandonware.persona.max-chars property; when unspecified we default to 1200.
                int personaMaxChars = 1200;
                try {
                    String cfg = env.getProperty("abandonware.persona.max-chars");
                    if (cfg != null && !cfg.isBlank()) {
                        personaMaxChars = Integer.parseInt(cfg.trim());
                    }
                } catch (Exception ignore) {
                    // use default
                }
                if (personaInstr != null && !personaInstr.isBlank()) {
                    String trimmed = personaInstr.trim();
                    if (trimmed.length() > personaMaxChars) {
                        trimmed = trimmed.substring(0, personaMaxChars);
                    }
                    sys.append(trimmed).append('\n');
                }
            } catch (Exception ignore) {
                // ignore if property not found or environment is unavailable
            }
        }
        // Update the authority order to include uploaded file context at the top
        sys.append("- Earlier sections have higher authority: UPLOADED FILE CONTEXT > VECTOR RAG > HISTORY > WEB SEARCH.\n");
        sys.append("- Ground every claim in the provided sections; if evidence is insufficient, reply \"정보 없음\".\n");
        sys.append("- Cite specific snippets or sources inline when possible.\n");
        // [MERGED] 엄격한 컨텍스트 한정 규칙 추가
        sys.append("- 답변은 제공된 컨텍스트와 문서 근거에 기반해야 합니다. 컨텍스트에 없는 새로운 정보나 추측을 답변에 포함하지 마세요. 정보가 부족하면 '정보 없음'이라고 답하세요.\n");

        if (ctx != null) {
            // 🆕 치유 모드 전용 규칙 (인스트럭션 영역에 위치)
            if ("CORRECTIVE_REGENERATION".equalsIgnoreCase(Objects.toString(ctx.systemInstruction(), ""))) {
                sys.append("### CORRECTIVE REGENERATION MODE\n");
                sys.append("- 위의 컨텍스트를 최우선 근거로 하여 'DRAFT_ANSWER'의 오류를 수정하세요.\n");
                sys.append("- 아래 'UNSUPPORTED_CLAIMS'에 포함된 개체/주장은 **컨텍스트에 근거가 없으므로 제거하거나 수정**해야 합니다.\n");
                sys.append("- 새로운 개체나 출처를 추가하지 마세요. 근거가 부족하면 해당 문장을 삭제하거나 최종적으로 '정보 없음'으로 답하세요.\n");
                sys.append("- 한국어로 간결하게, 원문 대비 최대 +20% 길이 내에서 수정하세요.\n");
                List<String> uc = (ctx.unsupportedClaims() == null ? List.of() : ctx.unsupportedClaims());
                if (!uc.isEmpty()) {
                    sys.append("### UNSUPPORTED_CLAIMS\n");
                    for (String c : uc) {
                        if (StringUtils.hasText(c)) sys.append("- ").append(c).append('\n');
                    }
                }
            }
            // [NEW] 후속 질문이면 '이전 답변'을 주제로 확장하라고 강제
            if (isFollowUp(ctx.userQuery()) && StringUtils.hasText(ctx.lastAssistantAnswer())) {
                sys.append("- Treat the section 'PREVIOUS_ANSWER' as the primary subject of the user's new query.\n");
                sys.append("- Expand, elaborate, or give concrete examples based on 'PREVIOUS_ANSWER'.\n");
                sys.append("- If the follow-up is underspecified, infer the omitted object from 'PREVIOUS_ANSWER'.\n");
            }

            if (StringUtils.hasText(ctx.domain())) {
                sys.append("- Domain: ").append(ctx.domain()).append("\n");
            }
            if (StringUtils.hasText(ctx.subject())) {
                sys.append("- Primary subject anchor: ").append(ctx.subject()).append("\n");
            }
            if (ctx.protectedTerms() != null && !ctx.protectedTerms().isEmpty()) {
                sys.append("- Do not alter these entity strings: ")
                        .append(String.join(", ", ctx.protectedTerms())).append('\n');
            }
            Map<String, Set<String>> rules = ctx.interactionRules();
            if (rules != null && !rules.isEmpty()) {
                sys.append("### DYNAMIC RELATIONSHIP RULES\n");
                rules.forEach((k, v) -> {
                    if (v != null && !v.isEmpty()) {
                        sys.append("- ").append(k).append(": ")
                                .append(String.join(",", v))
                                .append('\n');
                    }
                });
            }

            // 공통 보수적 가드
            sys.append("- Answer conservatively; prefer synergy evidence; if unsure, say '정보 없음'.\n");
            sys.append("- If evidence is weak but related to the same subject, provide a conservative summary and add a short 'clarify' question instead of refusing outright.\n");

            // ▼ Verbosity/Output policy (섹션/최소길이/상세도 강제)
            String vh = Objects.toString(ctx.verbosityHint(), "standard");
            Integer minWords = ctx.minWordCount();
            List<String> sections = ctx.sectionSpec();

            if ("deep".equalsIgnoreCase(vh) || "ultra".equalsIgnoreCase(vh)) {
                sys.append("- Write in a detailed, structured style (not brief).\n");
                if (sections != null && !sections.isEmpty()) {
                    sys.append("- Organize the answer with the following section headers (use Korean): ")
                            .append(String.join(", ", sections)).append('\n');
                }
                if (minWords != null && minWords > 0) {
                    sys.append("- The final answer MUST be at least ")
                            .append(minWords).append(" Korean words.\n");
                }
            }

            String audience = Objects.toString(ctx.audience(), "");
            if (!audience.isBlank()) {
                sys.append("- Target audience: ").append(audience).append('\n');
            }

            String cite = Objects.toString(ctx.citationStyle(), "inline");
            sys.append("- Citation style: ").append(cite).append('\n');

            // 보수적 가드 재확인
            sys.append("- Answer conservatively; prefer synergy evidence; if unsure, say '정보 없음'.\n");
        }
        return sys.toString();
    }

    private static String join(List<Content> list) {
        return list.stream()
                .map(c -> {
                    var seg = c.textSegment();
                    return (seg != null && seg.text() != null && !seg.text().isBlank())
                            ? seg.text()
                            : c.toString();
                })
                .collect(Collectors.joining("\n"));
    }

    // [NEW] 후속질문 간단 감지: 한국어/영어 패턴
    private static boolean isFollowUp(String q) {
        if (!StringUtils.hasText(q)) return false;
        String s = q.toLowerCase(Locale.ROOT).trim();
        return s.matches("^(더|조금|좀)\\s*자세히.*")
                || s.matches(".*자세히\\s*말해줘.*")
                || s.matches(".*예시(도|를)\\s*들(어|어서)?\\s*줘.*")
                || s.matches("^왜\\s+그렇(게|지).*")
                || s.matches(".*근거(는|가)\\s*뭐(야|지).*")
                || s.matches("^(tell me more|more details|give me examples|why is that).*");
    }
}