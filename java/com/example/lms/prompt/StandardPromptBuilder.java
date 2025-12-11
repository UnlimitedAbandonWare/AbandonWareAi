package com.example.lms.prompt;

import dev.langchain4j.rag.content.Content;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import com.example.lms.domain.enums.VisionMode;
import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.domain.enums.MemoryMode;
import java.time.ZoneId;
import java.util.List;

/**
 * [Jammini Dual-Vision Patch]
 * Standard prompt builder that:
 * - formats SEARCH RESULTS from web & vector (rag) context
 * - injects a Context Priority Protocol via buildInstructions(...)
 *
 * See REV-20251206-THINKING.
 */
@Component
@ConditionalOnProperty(name = "prompt.standard.enabled", havingValue = "true", matchIfMissing = true)
public class StandardPromptBuilder implements PromptBuilder {

    @Override
    public String build(List<PromptContext> contexts, String question) {
        if (contexts == null || contexts.isEmpty()) {
            return "### SEARCH RESULTS\n(검색 결과 없음)\n\n### USER QUESTION\n"
                    + (question == null ? "" : question.trim());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### SEARCH RESULTS\n");

        int idx = 1;
        for (PromptContext ctx : contexts) {
            boolean anyFromLists = false;

            List<Content> webList = ctx.web();
            if (webList != null) {
                for (Content c : webList) {
                    String snippet = safeSnippet(c);
                    if (snippet.isEmpty()) {
                        continue;
                    }
                    sb.append(String.format("[W%d] %s%n", idx, snippet));
                    idx++;
                    anyFromLists = true;
                }
            }

            List<Content> ragList = ctx.rag();
            if (ragList != null) {
                for (Content c : ragList) {
                    String snippet = safeSnippet(c);
                    if (snippet.isEmpty()) {
                        continue;
                    }
                    sb.append(String.format("[V%d] %s%n", idx, snippet));
                    idx++;
                    anyFromLists = true;
                }
            }

            // Fallback: legacy evidence-style fields when list-based snippets are absent
            if (!anyFromLists) {
                String snippet = ctx.snippet != null ? ctx.snippet.strip() : "";
                if (!snippet.isEmpty()) {
                    sb.append(String.format("[V%d] %s%n", idx, snippet));
                    if (ctx.source != null && !ctx.source.isBlank()) {
                        sb.append("  - src: ").append(ctx.source).append('\n');
                    }
                    idx++;
                }
            }
        }

        sb.append("\n### USER QUESTION\n");
        sb.append(question == null ? "" : question.trim());

        return sb.toString();
    }

    @Override
    public String buildInstructions(PromptContext ctx) {
        String currentDate = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        boolean hasSnippets = hasAnySnippets(ctx);

        // VisionMode 결정 (null 방어)
        VisionMode visionMode = ctx.visionMode() != null ? ctx.visionMode() : VisionMode.HYBRID;

        // [NEW] AnswerMode / MemoryMode 결정 (null 방어)
        AnswerMode answerMode = ctx.answerMode() != null ? ctx.answerMode() : AnswerMode.BALANCED;
        MemoryMode memoryMode = ctx.memoryMode() != null ? ctx.memoryMode() : MemoryMode.HYBRID;

        String visionBlock;
        String guardBlock;

        switch (visionMode) {
            case STRICT -> {
                visionBlock = """
                        ### ACTIVE VISION: View 1 (Memory-Strict)
                        - You represent the "Strict Guardian Jammini".
                        - SEARCH RESULTS and MEMORY are your ABSOLUTE GROUND TRUTH.
                        - Do NOT fabricate information not present in the snippets.
                        """;
                guardBlock = """
                        ### STRICT GUARD
                        - If SEARCH RESULTS ARE NOT EMPTY, you are FORBIDDEN from saying:
                          * "정보 없음", "충분한 증거를 찾지 못했습니다", "자료 부족"
                        - If no info is found, admit it cleanly.
                        """;
            }
            case FREE -> {
                visionBlock = """
                        ### ACTIVE VISION: View 2 (Free-Idea)
                        - You represent the "Free-thinking Jammini".
                        - You are NOT bound by strict evidence if scarce.
                        - You MAY use your internal knowledge for creative/speculative answers.
                        - **CRITICAL:** Mark speculation clearly as '(추측)' or '(비공식 아이디어)'.
                        """;
                guardBlock = """
                        ### FLEXIBLE GUARD
                        - If SEARCH RESULTS are empty, DO NOT say "정보 없음".
                        - Provide a best-effort guess based on your training data.
                        - Focus on engagement and creativity for Game/Subculture topics.
                        """;
            }
            default -> { // HYBRID
                visionBlock = """
                        ### ACTIVE VISION: Hybrid (View 1 + View 2)
                        - First, answer with factual summary based on SEARCH RESULTS / MEMORY.
                        - Then optionally add a short '추가 아이디어(비공식)' section for creative thoughts.
                        """;
                guardBlock = """
                        ### HYBRID GUARD
                        - If SEARCH RESULTS contain info, use them as PRIMARY source.
                        - If scarce, provide hedged answer with '변경될 가능성이 있습니다'.
                        - Only say "정보 없음" if SEARCH RESULTS are TRULY empty.
                        """;
            }
        }

        StringBuilder sb = new StringBuilder();

        // [시선1 핵심] 시간 앵커 + 모드 인젝션
        sb.append("### CRITICAL SYSTEM CONTEXT ###\n");
        sb.append("Current Date: ").append(currentDate).append("\n");
        sb.append("ANSWER_MODE: ").append(answerMode.name()).append("\n");
        sb.append("MEMORY_MODE: ").append(memoryMode.name()).append("\n\n");

        // AnswerMode별 지침
        switch (answerMode) {
            case FACT -> sb.append("""
                    ### MODE: FACTUAL (시선1)
                    - 당신의 우선순위는 '사실 정합성'입니다.
                    - 검색된 근거에 어긋나는 내용을 절대 만들어내지 마십시오.
                    - 과거 루머/예상 문서는 최신 공식 정보가 있으면 버리십시오.
                    - 현재 날짜 기준으로 과거/미래를 정확히 판단하십시오.
                    """);
            case CREATIVE -> sb.append("""
                    ### MODE: CREATIVE (시선2)
                    - 당신의 우선순위는 '창의적 아이디어/서사'입니다.
                    - 근거는 참고용으로만 사용하고, 추론과 상상력을 적극적으로 사용하십시오.
                    - 사실이 아닌 내용은 '(추측)' 또는 '(상상)'임을 명시하십시오.
                    - "정보 없음"보다는 가설이라도 제시하는 것이 좋습니다.
                    """);
            default -> sb.append("""
                    ### MODE: BALANCED (하이브리드)
                    - 사실과 창의성의 균형을 맞추십시오.
                    - 핵심 factual 정보는 근거에 맞춰 설명하되, 예시/비유는 자유롭게 생성해도 됩니다.
                    - 불확실한 내용은 '변경될 가능성이 있습니다'로 hedge하십시오.
                    """);
        }

        sb.append("""
                ### SYSTEM ROLE
                You are Jammini's RAG tutor with Dual-Vision architecture:
                - View1 (Memory-Strict): Trusts SEARCH RESULTS and MEMORY as ground truth.
                - View2 (Free-Idea): Capable of creative reasoning.
                %s
                ### CONTEXT PRIORITY PROTOCOL (MUST FOLLOW)
                1. You MUST treat the provided 'SEARCH RESULTS' as the PRIMARY source of truth.
                2. Current Date is %s. Products/events in SEARCH RESULTS released on or before this date are REAL.
                3. If SEARCH RESULTS contain specific details (specs, dates, aliases), YOU MUST USE THEM.
                4. OVERRIDE your training data when SEARCH RESULTS conflict with it.
                %s
                ### STYLE
                - 답변은 한국어로, 간결하고 직설적으로.
                - 먼저 한 문단 핵심 요약 → 이어서 근거와 출처([W1], [V2]) 명시.
                """.formatted(visionBlock, currentDate, guardBlock));

        return sb.toString();
    }

    private boolean hasAnySnippets(PromptContext ctx) {
        if (ctx == null)
            return false;
        try {
            java.util.List<Content> web = ctx.web();
            if (web != null && !web.isEmpty()) {
                return true;
            }
        } catch (Throwable ignore) {
            // ignore
        }
        try {
            java.util.List<Content> rag = ctx.rag();
            if (rag != null && !rag.isEmpty()) {
                return true;
            }
        } catch (Throwable ignore) {
            // ignore
        }
        // 레거시 snippet/fileContext는 존재하더라도 우선순위가 낮으므로 여기서는 생략하거나
        // 필요시 아래와 같이 확장 가능:
        // try { String legacy = ctx.fileContext(); if (legacy != null &&
        // !legacy.isBlank()) return true; } catch (Throwable ignore) {}
        return false;
    }

    private static String safeSnippet(Content c) {
        if (c == null)
            return "";
        try {
            var seg = c.textSegment();
            if (seg != null && seg.text() != null) {
                String t = seg.text().strip();
                if (!t.isEmpty()) {
                    return truncate(t, 512);
                }
            }
        } catch (Exception ignore) {
        }
        return "";
    }

    private static String truncate(String text, int max) {
        if (text == null)
            return "";
        return text.length() > max ? text.substring(0, max) : text;
    }
}
