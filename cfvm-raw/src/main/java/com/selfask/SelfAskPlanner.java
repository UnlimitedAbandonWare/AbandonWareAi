package com.selfask;

import java.util.*;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.selfask.SelfAskPlanner
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.selfask.SelfAskPlanner
role: config
*/
public class SelfAskPlanner {
    public static class SubQ {
        public final String type; public final String text;
        public SubQ(String t, String x) { type=t; text=x; }
    }
    public List<SubQ> generateSubQuestions(String query) {
        // naive heuristic placeholders; integrate LLM later
        String core = query.trim();
        return Arrays.asList(
            new SubQ("BQ", "정의/도메인 관점: " + core),
            new SubQ("ER", "별칭/동의어/오타 보정 관점: " + core),
            new SubQ("RC", "관계/가설 관점: " + core)
        );
    }
}