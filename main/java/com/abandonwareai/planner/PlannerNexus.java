package com.abandonwareai.planner;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;
import com.abandonware.ai.agent.service.rag.policy.KAllocationPolicy;

/**
 * PlannerNexus: apply plan knobs to the running retrieval context.
 * Minimal integration: delegate dynamic K allocation and leave other
 * knobs to existing chain components (calibrators, fusers, gates).
 */
@Component
public class PlannerNexus {
    private static final Logger log = LoggerFactory.getLogger(PlannerNexus.class);

    private final KAllocationPolicy kPolicy;

    @Autowired
    public PlannerNexus(KAllocationPolicy kPolicy) {
        this.kPolicy = kPolicy;
    }

    /**
     * Apply plan knobs in a fail-soft way. The second parameter is a loosely-typed
     * retrieval-chain context which may (or may not) expose typed hooks such as:
     *   - setKFor(String,int)
     *   - setRrfParams(int, java.util.Map)
     *   - setCalibration(Object)
     */
    public void applyPlan(RetrievalPlan plan, Object chainContext) {
        if (plan == null || chainContext == null) return;

        // 1) Dynamic K allocation via policy
        try {
            if (kPolicy != null) {
                kPolicy.apply(plan, chainContext);
            }
        } catch (Throwable error) {
            logFailSoft("kPolicy.apply", error);
            // best-effort only
        }

        // 2) RRF parameters if the context supports it
        try {
            if (chainContext instanceof RrfParamsTarget target && plan.rrf() != null) {
                target.setRrfParams(plan.rrf().k, plan.rrf().weight);
            }
        } catch (Throwable error) {
            logFailSoft("rrf.apply", error);
            // optional
        }

        // 3) Calibration knobs (recency/authority scaling), best-effort
        try {
            if (chainContext instanceof CalibrationTarget target && plan.calibration() != null) {
                target.setCalibration(plan.calibration());
            }
        } catch (Throwable error) {
            logFailSoft("calibration.apply", error);
            // optional
        }
    }

    public interface RrfParamsTarget {
        void setRrfParams(int k, java.util.Map<String, Double> weight);
    }

    public interface CalibrationTarget {
        void setCalibration(RetrievalPlan.Calibration calibration);
    }

    private static void logFailSoft(String stage, Throwable error) {
        if (log.isDebugEnabled()) {
            log.debug("[PlannerNexus] fail-soft stage={} errorType={}", stage, errorType(error));
        }
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
