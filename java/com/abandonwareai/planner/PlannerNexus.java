package com.abandonwareai.planner;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;
import com.abandonware.ai.agent.service.rag.policy.KAllocationPolicy;

/**
 * PlannerNexus: apply plan knobs to the running retrieval context.
 * Minimal integration: delegate dynamic K allocation and leave other
 * knobs to existing chain components (calibrators, fusers, gates).
 */
@Component
public class PlannerNexus {
    private final KAllocationPolicy kPolicy;

    @Autowired
    public PlannerNexus(KAllocationPolicy kPolicy) {
        this.kPolicy = kPolicy;
    }

    /**
     * Apply plan knobs in a fail-soft way. The second parameter is a loosely-typed
     * retrieval-chain context which may (or may not) expose reflective hooks such as:
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
        } catch (Throwable ignore) {
            // best-effort only
        }

        // 2) RRF parameters if the context supports it
        try {
            java.lang.reflect.Method rrfGetter = safeGetter(plan.getClass(), "rrf");
            if (rrfGetter != null) {
                Object rrf = rrfGetter.invoke(plan);
                Integer k = (Integer) tryGet(rrf, "k");
                @SuppressWarnings("unchecked")
                java.util.Map<String, Double> weight = (java.util.Map<String, Double>) tryGet(rrf, "weight");
                try {
                    java.lang.reflect.Method setRrf = chainContext.getClass()
                        .getMethod("setRrfParams", int.class, java.util.Map.class);
                    setRrf.invoke(chainContext, k != null ? k.intValue() : 60, weight);
                } catch (NoSuchMethodException ignore) { /* context without RRF hook */ }
            }
        } catch (Throwable ignore) {
            // optional
        }

        // 3) Calibration knobs (recency/authority scaling), best-effort
        try {
            Object cal = tryGet(plan, "calibration");
            if (cal != null) {
                try {
                    java.lang.reflect.Method setCal = chainContext.getClass()
                        .getMethod("setCalibration", Object.class);
                    setCal.invoke(chainContext, cal);
                } catch (NoSuchMethodException ignore) { /* context without calibration hook */ }
            }
        } catch (Throwable ignore) {
            // optional
        }
    }

    private static java.lang.reflect.Method safeGetter(Class<?> cls, String name) {
        try {
            return cls.getMethod(name);
        } catch (NoSuchMethodException e1) {
            try {
                return cls.getMethod("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
            } catch (NoSuchMethodException e2) {
                return null;
            }
        }
    }

    private static Object tryGet(Object obj, String field) {
        if (obj == null) return null;
        java.lang.reflect.Method m = safeGetter(obj.getClass(), field);
        if (m == null) return null;
        try {
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }
}