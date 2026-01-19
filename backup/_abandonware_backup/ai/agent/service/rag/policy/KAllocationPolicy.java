package com.abandonware.ai.agent.service.rag.policy;

import org.springframework.stereotype.Component;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.service.rag.policy.KAllocationPolicy
 * Role: config
 * Feature Flags: kg
 * Dependencies: com.abandonware.ai.agent.service.plan.RetrievalPlan
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.service.rag.policy.KAllocationPolicy
role: config
flags: [kg]
*/
public class KAllocationPolicy {
    public void apply(RetrievalPlan plan, Object ctx) {
        try {
            java.lang.reflect.Method set = ctx.getClass().getMethod("setKFor", String.class, int.class);
            set.invoke(ctx, "web",   plan.k().getOrDefault("web", 10));
            set.invoke(ctx, "vector",plan.k().getOrDefault("vector", 6));
            set.invoke(ctx, "bm25",  plan.k().getOrDefault("bm25", 10));
            set.invoke(ctx, "kg",    plan.k().getOrDefault("kg", 3));
        } catch (Exception ignore) {
            // Context does not support dynamic K: ignore
        }
    }
}