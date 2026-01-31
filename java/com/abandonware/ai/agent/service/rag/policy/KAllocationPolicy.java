package com.abandonware.ai.agent.service.rag.policy;

import org.springframework.stereotype.Component;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;

@Component
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