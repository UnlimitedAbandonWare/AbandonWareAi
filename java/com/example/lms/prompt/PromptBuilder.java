// src/main/java/com/example/lms/prompt/PromptBuilder.java
package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
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

    /** 컨텍스트 본문(자료 영역) */
    public String build(PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx != null) {
            if (ctx.web() != null && !ctx.web().isEmpty()) {
                sb.append(WEB_PREFIX.formatted(join(ctx.web())));
            }
            if (ctx.rag() != null && !ctx.rag().isEmpty()) {
                sb.append(RAG_PREFIX.formatted(join(ctx.rag())));
            }
            if (ctx.memory() != null && !ctx.memory().isBlank()) {
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
        sys.append("- Earlier sections have higher authority: VECTOR RAG > HISTORY > WEB SEARCH.\n");
        sys.append("- Ground every claim in the provided sections; if evidence is insufficient, reply \"정보 없음\".\n");
        sys.append("- Cite specific snippets or sources inline when possible.\n");

        if (ctx != null) {
            if (StringUtils.hasText(ctx.domain())) {
                sys.append("- Domain: ").append(ctx.domain()).append("\n");
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

            // RECOMMENDATION/PAIRING 공통 보수적 가드
            sys.append("- Answer conservatively; prefer synergy evidence; if unsure, say '정보 없음'.\n");

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
                    var seg = c.textSegment(); // LangChain4j 1.x API
                    return (seg != null && seg.text() != null && !seg.text().isBlank())
                            ? seg.text()
                            : c.toString();
                })
                .collect(Collectors.joining("\n"));
    }
}