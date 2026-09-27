package com.abandonware.ai.agent.service.rag.policy;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.stereotype.Component;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;

@Component
public class KAllocationPolicy {
    private static final Logger log = Logger.getLogger(KAllocationPolicy.class.getName());

    public void apply(RetrievalPlan plan, Object ctx) {
        try {
            if (ctx instanceof KAllocationTarget target) {
                target.setKFor("web", plan.k().getOrDefault("web", 10));
                target.setKFor("vector", plan.k().getOrDefault("vector", 6));
                target.setKFor("bm25", plan.k().getOrDefault("bm25", 10));
                target.setKFor("kg", plan.k().getOrDefault("kg", 3));
            }
        } catch (Exception e) {
            logFailSoft("apply", e);
        }
    }

    public interface KAllocationTarget {
        void setKFor(String source, int k);
    }

    private static void logFailSoft(String stage, Exception e) {
        if (log.isLoggable(Level.FINE)) {
            String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
            log.fine("[AWX][rag][k-allocation] failSoft stage=" + stage + " errorType=" + errorType);
        }
    }
}
