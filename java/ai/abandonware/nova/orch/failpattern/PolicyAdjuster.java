package ai.abandonware.nova.orch.failpattern;

import ai.abandonware.nova.config.NovaFailurePatternProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PolicyAdjuster 역할(최소 버전).
 *
 * <p>코어 로직을 건드리지 않고,
 * RetrievalOrderService의 결과(List)의 order를 AOP로 약하게 조정한다.
 */
public final class PolicyAdjuster {

    private final FailurePatternOrchestrator orchestrator;
    private final NovaFailurePatternProperties props;

    public PolicyAdjuster(FailurePatternOrchestrator orchestrator, NovaFailurePatternProperties props) {
        this.orchestrator = orchestrator;
        this.props = props;
    }

    public <T> List<T> adjustOrder(List<T> baseOrder) {
        if (baseOrder == null || baseOrder.isEmpty()) {
            return baseOrder;
        }
        if (!props.getFeedback().isEnabled()) {
            return baseOrder;
        }

        NovaFailurePatternProperties.Mode mode = props.getFeedback().getMode();
        if (mode == null) {
            mode = NovaFailurePatternProperties.Mode.WEAK_REORDER;
        }

        // Determine which elements are in cooldown
        List<T> hot = new ArrayList<>();
        List<T> cool = new ArrayList<>();

        for (T t : baseOrder) {
            String canon = canonicalSourceOf(t);
            boolean isCooling = orchestrator.isCoolingDown(canon);
            if (isCooling) {
                cool.add(t);
            } else {
                hot.add(t);
            }
        }

        if (mode == NovaFailurePatternProperties.Mode.WEAK_REORDER) {
            // keep all, demote cooled-down sources to the end
            List<T> out = new ArrayList<>(baseOrder.size());
            out.addAll(hot);
            out.addAll(cool);
            return out;
        }

        // SKIP_TURN: remove cooled sources for this call (if it would not empty the plan)
        if (!hot.isEmpty()) {
            return hot;
        }
        // if everything is cooled down, fall back to original plan (avoid "no retrieval")
        return baseOrder;
    }

    private static String canonicalSourceOf(Object o) {
        if (o == null) {
            return "web";
        }
        String s = o.toString();
        if (s == null) {
            return "web";
        }
        String v = s.trim().toLowerCase(Locale.ROOT);

        if (v.contains("web")) {
            return "web";
        }
        if (v.contains("vector") || v.contains("rag")) {
            return "vector";
        }
        if (v.equals("kg") || v.contains("knowledge") || v.contains("graph")) {
            return "kg";
        }
        if (v.contains("disambig")) {
            return "disambig";
        }

        if (v.contains("llm") || v.contains("chat") || v.contains("draft")
                || v.contains("query-transformer") || v.contains("transformer") || v.contains("model")) {
            return "llm";
        }

        // default: keep conservative
        return "web";
    }
}
