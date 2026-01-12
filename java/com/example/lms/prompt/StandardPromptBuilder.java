package com.example.lms.prompt;

import com.example.lms.util.FutureTechDetector;
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
        // [FIX-C1] Null-safe: contexts/question 방어 + null 요소 스킵
        if (contexts == null) {
            contexts = java.util.Collections.emptyList();
        }
        String safeQuestion = question == null ? "" : question.trim();
        if (contexts.isEmpty()) {
            return "### SEARCH RESULTS\n(검색 결과 없음)\n\n### USER QUESTION\n" + safeQuestion;
        }

        // Generic mode: when a caller provides only a systemInstruction and a
        // plain question/text (no web/rag/memory), build a minimal prompt.
        // This is used by non-chat tasks (e.g. NER) to avoid ad-hoc
        // system+user string concatenation.
        if (contexts.size() == 1) {
            PromptContext only = contexts.get(0);
            if (only != null) {
                String sys = only.systemInstruction();
                boolean noEvidence = (only.web() == null || only.web().isEmpty())
                        && (only.rag() == null || only.rag().isEmpty());
                boolean noMemory = (only.memory() == null || only.memory().isBlank());
                if (sys != null && !sys.isBlank() && noEvidence && noMemory) {
                    return sys.strip() + "\n\n" + safeQuestion;
                }
            }
        }

        StringBuilder sb = new StringBuilder();

        // Optional memory injection (first non-blank wins)
        for (PromptContext ctx : contexts) {
            if (ctx == null) {
                continue;
            }
            try {
                String mem = ctx.memory();
                if (mem != null && !mem.isBlank()) {
                    sb.append("### MEMORY\n");
                    sb.append(mem.strip()).append("\n\n");
                    break;
                }
            } catch (Throwable ignore) {
                // fail-soft
            }
        }

        sb.append("### SEARCH RESULTS\n");

        int idx = 1;
        for (PromptContext ctx : contexts) {
            if (ctx == null) {
                continue;
            }
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
        sb.append(safeQuestion);

        return sb.toString();
    }

    @Override
    public String buildInstructions(PromptContext ctx) {
        String currentDate = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        boolean hasSnippets = hasAnySnippets(ctx);
        String safeQuery = (ctx == null || ctx.userQuery() == null) ? "" : ctx.userQuery();
        boolean futureTech = FutureTechDetector.isFutureTechQuery(safeQuery);

        // VisionMode 결정 (null 방어)
        VisionMode visionMode = (ctx != null && ctx.visionMode() != null) ? ctx.visionMode() : VisionMode.HYBRID;

        // [NEW] AnswerMode / MemoryMode 결정 (null 방어)
        AnswerMode answerMode = (ctx != null && ctx.answerMode() != null) ? ctx.answerMode() : AnswerMode.ALL_ROUNDER;
        MemoryMode memoryMode = (ctx != null && ctx.memoryMode() != null) ? ctx.memoryMode() : MemoryMode.HYBRID;

        String sectionSpecBlock = "";
        if (ctx != null && ctx.sectionSpec() != null && !ctx.sectionSpec().isEmpty()) {
            sectionSpecBlock = "\n### SECTION TEMPLATE\n" +
                    "- 아래 순서로 섹션 헤더를 사용해 답변을 구성해:\n" +
                    "  " + String.join(" -> ", ctx.sectionSpec()) + "\n";
        }

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
                        - Focus on engagement and clarity for Entertainment/Community/Lifestyle topics (including games), but keep claims grounded in provided sources.
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

        // user rule: fixed single-line instruction prefix (must be first)
        sb.append("### INSTRUCTIONS: Synthesize answers from sources (higher authority first). Cite evidence. If insufficient, reply '정보 없음'.\n");

        // [시선1 핵심] 시간 앵커 + 모드 인젝션
        sb.append("### CRITICAL SYSTEM CONTEXT ###\n");
        sb.append("Current Date: ").append(currentDate).append("\n");
        sb.append("ANSWER_MODE: ").append(answerMode.name()).append("\n");
        sb.append("MEMORY_MODE: ").append(memoryMode.name()).append("\n\n");

        // AnswerMode별 지침
        switch (answerMode) {
            case ALL_ROUNDER -> sb.append("""
                    ### MODE: ALL_ROUNDER
                    - 어떤 주제든 '구조화된 실행형 답변'을 기본으로 제공합니다.
                    - 기본 흐름: (1) 요약 1~2문장 → (2) 핵심 답변 → (3) 추가 설명/비교/옵션 → (4) 주의/확인 → (5) 다음 단계.
                    - 사용자가 '짧게/요약만'을 원하면, 위 흐름을 유지하되 각 섹션을 1~2줄로 압축합니다.
                    - 질문이 애매하면 가능한 해석 2~3개를 제시하고, 가장 중요한 확인 질문 1개를 마지막에 둡니다.
                    - 버전/시점이 중요한 주제(소프트웨어, 정책/법령, 제품 출시, 게임/커뮤니티 포함)는 근거에 날짜/버전이 없으면 단정하지 말고 '근거에 표시된 시점 기준'으로 표현합니다.
                    """);
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
                - 답변은 한국어로, 구조화된 올라운더 형태를 기본으로 합니다.
                - 핵심 결론을 먼저 제시하고, 필요하면 비교/대안/리스크/다음행동까지 포함합니다.
                - 근거가 있는 문장에는 [W1], [V2] 등 마커를 붙이고, 근거가 약하면 '추정/확인 필요'로 표시합니다.
                """.formatted(visionBlock, currentDate, guardBlock));

        // THINKING SETUP: 내부 사고 유도 (출력은 결론만)
        sb.append("""
                ### THINKING SETUP (INTERNAL)
                - 답변 전 내부적으로 3단계로 정리: (1) 의도/제약 파악 → (2) 근거 추출/충돌 확인 → (3) 섹션별 아웃라인 작성
                - 내부 추론/메모(Chain-of-thought)는 출력하지 말고, 최종 답변만 출력합니다.
                - 계산/비교/판단이 필요하면, 가능한 한 명확한 기준(가정 포함)을 선언하고 진행합니다.
                """);



        // OUTPUT BUDGET: 토큰 예산 힌트 (transport-level 제한이 안 될 때 프롬프트로 제어)
        Integer tokBudget = (ctx == null) ? null : ctx.targetTokenBudgetOut();
        Integer minWords = (ctx == null) ? null : ctx.minWordCount();
        String vh = (ctx == null) ? null : ctx.verbosityHint();
        String aud = (ctx == null) ? null : ctx.audience();
        if ((tokBudget != null && tokBudget > 0) || (minWords != null && minWords > 0)
                || (vh != null && !vh.isBlank()) || (aud != null && !aud.isBlank())) {
            sb.append("\n### OUTPUT BUDGET\n");
            if (vh != null && !vh.isBlank()) sb.append("- verbosity: ").append(vh).append("\n");
            if (aud != null && !aud.isBlank()) sb.append("- audience: ").append(aud).append("\n");
            if (minWords != null && minWords > 0) sb.append("- minimum words: ").append(minWords).append("\n");
            if (tokBudget != null && tokBudget > 0) sb.append("- target output tokens: ").append(tokBudget).append("\n");
            sb.append("- If the budget is tight, prioritize: 결론 → 핵심 근거 → 다음 행동.\n");
            sb.append("- Avoid long preambles; keep citations compact.\n");
        }

        sb.append(sectionSpecBlock);



        // [FUTURE_TECH FIX] Unreleased / rumor product mode (web-first, no refusal-only answers)
        if (futureTech) {
            sb.append("""

[CRITICAL: UNRELEASED / RUMOR PRODUCT MODE]
- Do NOT refuse just because there is no official announcement.
- Summarize rumors/leaks/expectations ONLY from SEARCH RESULTS (prefer [WEB]).
- Clearly label each major claim as (루머)/(유출)/(예상) and say it may change.
- Use sections: (1) 예상 출시 시기, (2) 루머 스펙, (3) 디자인/변경점, (4) 신뢰도/출처.
- If sources conflict, present both and label as '상충되는 루머'.
- End with: '출시 전 정보는 변경될 수 있습니다.'
- NEVER end with refusal-only answers like '공식 정보 없음', '정보가 없습니다'.
""");
        }

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
        if (text == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        // [PATCH] Prefer head+tail sampling to preserve sparse evidence near the end of long docs.
        if (max <= 12) {
            return text.substring(0, Math.min(max, text.length()));
        }
        int head = max / 2;
        int tail = max - head - 1;
        if (tail <= 0) {
            return text.substring(0, Math.min(max, text.length()));
        }
        return text.substring(0, head) + "…" + text.substring(Math.max(0, text.length() - tail));
    }
}
