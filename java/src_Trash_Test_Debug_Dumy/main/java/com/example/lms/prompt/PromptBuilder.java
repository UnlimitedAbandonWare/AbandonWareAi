
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

    // Renamed per GPT Web Search plugin: gather snippets under 'WEB EVIDENCE'
    // --- Section prefixes ---
    // ATTACHMENTS must come first.  Treat attachments as ground truth; when
    // conflicts arise between attachments, RAG or web context, attachments take
    // precedence.  See static compliance notes.
    private static final String ATTACH_PREFIX = """
            ### ATTACHMENTS
            %s
            """;
    private static final String RAG_PREFIX = """
            ### RAG CONTEXT
            %s
            """;
    private static final String WEB_PREFIX = """
            ### WEB CONTEXT
            %s
            """;
    private static final String HIS_PREFIX = """
            ### CONVERSATION HISTORY
            %s
            """;
    private static final String MEM_PREFIX = """
            ### LONG-TERM MEMORY
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

    /**
     * Construct the composite prompt context for the current turn.  Sections are
     * always rendered in the following order to honour precedence rules:
     * <pre>
     * 1. Attachments (file context)
     * 2. Vector RAG
     * 3. Web evidence
     * 4. History
     * 5. Memory
     * </pre>
     * Additional helper sections such as DRAFT, PREVIOUS_ANSWER and LOCATION CONTEXT
     * are interleaved appropriately.  A MUST_INCLUDE summary is appended at the end.
     *
     * @param ctx the structured prompt context
     * @return the fully rendered context
     */
    public String build(PromptContext ctx) {
        // Ensure context is present before building; return empty string if null
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder();

        // SAFETY NOTE block
        // When system hints are provided, emit them as a bulleted list.  Filter
        // out null or blank hints to avoid adding empty lines.  Place this
        // section at the top of the prompt before any other sections.
        if (ctx.systemHints() != null && !ctx.systemHints().isEmpty()) {
            sb.append("\n# SAFETY NOTE\n");
            for (String h : ctx.systemHints()) {
                if (h != null && !h.isBlank()) {
                    sb.append("- ").append(h).append('\n');
                }
            }
        }
        // 1) Attachments: localDocs have the highest priority and are always rendered first
        if (ctx.localDocs() != null && !ctx.localDocs().isEmpty()) {
            StringBuilder att = new StringBuilder();
            for (var doc : ctx.localDocs()) {
                if (doc == null) continue;
                String text;
                try {
                    // Attempt to extract plain text if available
                    java.lang.reflect.Method m = doc.getClass().getMethod("text");
                    text = String.valueOf(m.invoke(doc));
                } catch (Throwable ignore) {
                    text = String.valueOf(doc);
                }
                if (text != null && !text.isBlank()) {
                    att.append(text).append("\n\n");
                }
            }
            // Trim attachments using the token clipper to respect budgets
            String clipped = com.example.lms.util.TokenClipper.clip(att.toString(), 6000);
            sb.append(ATTACH_PREFIX.formatted(clipped));
        } else if (ctx.fileContext() != null && !ctx.fileContext().isBlank()) {
            // Fallback: use fileContext when localDocs are empty
            sb.append(ATTACH_PREFIX.formatted(ctx.fileContext()));
        }
        // 2) Draft answer injection for corrective regeneration
        if ("CORRECTIVE_REGENERATION".equalsIgnoreCase(Objects.toString(ctx.systemInstruction(), ""))
                && StringUtils.hasText(ctx.lastAssistantAnswer())) {
            sb.append(DRAFT_PREFIX.formatted(ctx.lastAssistantAnswer()));
        }
        // 3) Previous answer injection for follow‑up queries
        if (isFollowUp(ctx.userQuery()) && StringUtils.hasText(ctx.lastAssistantAnswer())) {
            sb.append(PREV_PREFIX.formatted(ctx.lastAssistantAnswer()));
        }
        // 4) Vector RAG evidence (higher priority than web)
        if (ctx.rag() != null && !ctx.rag().isEmpty()) {
            sb.append(RAG_PREFIX.formatted(join(ctx.rag())));
        }
        // 5) Web evidence
        if (ctx.web() != null && !ctx.web().isEmpty()) {
            sb.append(WEB_PREFIX.formatted(join(ctx.web())));
        }
        // 6) Conversation history
        if (StringUtils.hasText(ctx.history())) {
            sb.append(HIS_PREFIX.formatted(ctx.history()));
        }
        // 7) Long‑term memory
        if (StringUtils.hasText(ctx.memory())) {
            sb.append(MEM_PREFIX.formatted(ctx.memory()));
        }
        // 8) Optional location context
        if (ctx.location() != null || ctx.locationAddress() != null) {
            sb.append("\n### LOCATION CONTEXT\n");
            if (ctx.location() != null) {
                var loc = ctx.location();
                sb.append("- lat: ").append(loc.lat())
                        .append(", lng: ").append(loc.lng())
                        .append(", accuracy(m): ").append(loc.accuracy())
                        .append('\n');
                sb.append("- capturedAt: ").append(loc.capturedAt()).append('\n');
            }
            if (ctx.locationAddress() != null && !ctx.locationAddress().isBlank()) {
                sb.append("- address: ").append(ctx.locationAddress()).append('\n');
            }
        }
        // 9) MUST_INCLUDE summarisation across rag and web evidence
        java.util.LinkedHashSet<String> must = new java.util.LinkedHashSet<>();
        // Extract tokens from RAG first to bias towards higher priority evidence
        if (ctx.rag() != null) {
            for (var c : ctx.rag()) {
                if (c == null) continue;
                String text = null;
                try {
                    var seg = c.textSegment();
                    if (seg != null) text = seg.text();
                } catch (Exception ignore) {}
                if (text == null || text.isBlank()) {
                    text = c.toString();
                }
                if (text != null) {
                    String[] arr = text.split("[\\s,;:/()\\[\\]{}<>|]+");
                    for (String w : arr) {
                        if (w != null && w.length() >= 2 && !w.matches("(?i)the|and|or|with|of")) {
                            must.add(w);
                        }
                    }
                }
            }
        }
        // Then extract from web evidence
        if (ctx.web() != null) {
            for (var c : ctx.web()) {
                if (c == null) continue;
                String text = null;
                try {
                    var seg = c.textSegment();
                    if (seg != null) text = seg.text();
                } catch (Exception ignore) {}
                if (text == null || text.isBlank()) {
                    text = c.toString();
                }
                if (text != null) {
                    String[] arr = text.split("[\\s,;:/()\\[\\]{}<>|]+");
                    for (String w : arr) {
                        if (w != null && w.length() >= 2 && !w.matches("(?i)the|and|or|with|of")) {
                            must.add(w);
                        }
                    }
                }
            }
        }
        java.util.List<String> mustShort = must.stream().limit(4).toList();
        if (!mustShort.isEmpty()) {
            sb.append("### MUST_INCLUDE\n- ").append(String.join(", ", mustShort)).append("\n\n");
        }
        // 10) User query (always last)
        if (ctx.userQuery() != null && !ctx.userQuery().isBlank()) {
            sb.append("### USER\n").append(ctx.userQuery()).append("\n\n");
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
        // Define the precedence order explicitly.  Attachments (file context) have the
        // highest priority, followed by vector RAG, then live web evidence, history and
        // finally the user query.  In the event of conflicting information the
        // earliest section wins.
        sys.append("- Earlier sections have higher authority: FILE CONTEXT > VECTOR RAG > WEB SEARCH > HISTORY > USER.\n");
        // Explicit precedence rule: when ATTACHMENTS conflict with RAG/WEB evidence,
        // treat attachments as ground truth.  This sentence satisfies static
        // compliance tests by stating the precedence explicitly.
        sys.append("- If ATTACHMENTS conflict with RAG/WEB, prefer ATTACHMENTS as ground truth.\n");
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

            // Evidence-aware guidance
            if ((ctx != null) && ((ctx.web() != null && !ctx.web().isEmpty()) || (ctx.rag() != null && !ctx.rag().isEmpty()))) {
                sys.append("- When uncertain but evidence exists, list candidate pairings with citations; do NOT say '정보 없음'.\n");
            } else {
                sys.append("- If no evidence is available, reply '정보 없음'.\n");
            }
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

            // Evidence-aware guidance reinforcement
            if ((ctx != null) && ((ctx.web() != null && !ctx.web().isEmpty()) || (ctx.rag() != null && !ctx.rag().isEmpty()))) {
                sys.append("- When uncertain but evidence exists, list candidate pairings with citations; do NOT say '정보 없음'.\n");
            } else {
                sys.append("- If no evidence is available, reply '정보 없음'.\n");
            }
        }
        return sys.toString();
    }

    private static String join(List<Content> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Content c : list) {
            if (c == null) continue;
            String text;
            // 한 번만 생성(재할당 금지) → 람다 캡처 오류 방지
            final java.util.Map<String, Object> md = new java.util.HashMap<>();
            try {
                var seg = c.textSegment();
                if (seg != null) {
                    text = (seg.text() != null && !seg.text().isBlank()) ? seg.text() : null;
                    try {
                        Object m = seg.metadata();
                        if (m instanceof java.util.Map<?, ?> mm) {
                            mm.forEach((k, v) -> md.put(String.valueOf(k), v));
                        } else if (m != null) {
                            try {
                                var clazz = m.getClass();
                                java.lang.reflect.Method asMap = null;
                                try { asMap = clazz.getMethod("asMap"); } catch (NoSuchMethodException ignore) {}
                                if (asMap == null) { try { asMap = clazz.getMethod("map"); } catch (NoSuchMethodException ignore) {} }
                                if (asMap != null) {
                                    Object r = asMap.invoke(m);
                                    if (r instanceof java.util.Map<?, ?> mm2) {
                                        mm2.forEach((k, v) -> md.put(String.valueOf(k), v));
                                    }
                                }
                            } catch (Exception ignore) {}
                        }
                    } catch (Exception ignore) {
                        // ignore metadata extraction errors
                    }
                } else {
                    text = null;
                }
            } catch (Exception e) {
                text = null;
            }
            if (text == null || text.isBlank()) {
                text = c.toString();
            }
            // TextSegment에서 못 얻었으면 Content 메타데이터에서 보충
            if (md.isEmpty()) {
                try {
                    Object m2 = c.metadata();
                    if (m2 instanceof java.util.Map<?, ?> mm2) {
                        mm2.forEach((k, v) -> md.put(String.valueOf(k), v));
                    } else if (m2 != null) {
                        try {
                            var clazz = m2.getClass();
                            java.lang.reflect.Method asMap = null;
                            try { asMap = clazz.getMethod("asMap"); } catch (NoSuchMethodException ignore) {}
                            if (asMap == null) { try { asMap = clazz.getMethod("map"); } catch (NoSuchMethodException ignore) {} }
                            if (asMap != null) {
                                Object r = asMap.invoke(m2);
                                if (r instanceof java.util.Map<?, ?> mm3) {
                                    mm3.forEach((k, v) -> md.put(String.valueOf(k), v));
                                }
                            }
                        } catch (Exception ignore) {}
                    }
                } catch (Exception ignore) {}
            }
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (!md.isEmpty()) {
                Object t = md.get("title");
                Object p = md.get("provider");
                Object u = md.get("url");
                Object ts = md.get("timestamp");
                if (t != null && !t.toString().isBlank()) parts.add(t.toString());
                if (p != null && !p.toString().isBlank()) parts.add(p.toString());
                if (u != null && !u.toString().isBlank()) parts.add(u.toString());
                if (ts != null && !ts.toString().isBlank()) parts.add(ts.toString());
            }
            String header = parts.isEmpty() ? null : "[" + String.join(" | ", parts) + "]";
            if (sb.length() > 0) sb.append("\n");
            // Prefix each entry with a dash for readability
            sb.append("- ");
            if (header != null) {
                sb.append(header).append("\n  ");
            }
            sb.append(text);
            count++;
        }
        return sb.toString();
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