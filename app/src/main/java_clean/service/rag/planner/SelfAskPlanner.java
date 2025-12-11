package service.rag.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Self-Ask planner (LLM-free scaffold).
 * Non-invasive: can be wired into DynamicRetrievalHandlerChain later.
 */
public class SelfAskPlanner {
    public List<String> generateSubQuestions(String q, int n) {
        if (q == null) return List.of();
        List<String> out = new ArrayList<>();
        out.add(q + " 정의는?");
        out.add(q + " 동의어/별칭은?");
        out.add(q + " 관련 원인/결과는?");
        // trim to n if smaller desired
        if (n > 0 && n < out.size()) {
            return out.subList(0, n);
        }
        return out;
    }
}