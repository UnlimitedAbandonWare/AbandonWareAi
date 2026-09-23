package com.abandonware.ai.selfask;

import java.util.*;

/** SelfAskPlanner - splits long-tail query into 3 branches: BQ/ER/RC. */
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