package com.abandonware.ai.rag.planner;

import org.springframework.stereotype.Component;
import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import jakarta.servlet.http.HttpServletRequest;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.rag.planner.PlannerNexus
 * Role: config
 * Feature Flags: kg
 * Dependencies: com.abandonware.ai.addons.budget.TimeBudget, com.abandonware.ai.addons.budget.TimeBudgetContext
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.rag.planner.PlannerNexus
role: config
flags: [kg]
*/
public class PlannerNexus {
    public static class RagPlanContext {
        public final PlanDefinition plan;
        public RagPlanContext(PlanDefinition plan) { this.plan = plan; }
        public int webK() { return plan.getWebTopK(); }
        public int vectorK() { return plan.getVectorTopK(); }
        public int kgK() { return plan.getKgTopK(); }
        public boolean dpp() { return plan.isDppEnabled(); }
    }

    private final PlanLoader loader;

    public PlannerNexus(PlanLoader loader) {
        this.loader = loader;
    }

    public RagPlanContext select(HttpServletRequest req) {
        String explicit = req != null ? req.getHeader("X-Plan-Key") : null;
        String brave = req != null ? req.getHeader("X-Brave-Mode") : null;
        String zero = req != null ? req.getHeader("X-Zero-Break-Mode") : null;

        PlanDefinition plan;
        if ("on".equalsIgnoreCase(zero)) {
            plan = safeGet("zero_break.v1", "zero_break"); // tolerate legacy ids
        } else if ("on".equalsIgnoreCase(brave)) {
            plan = safeGet("brave.v1", "brave");
        } else if (explicit != null && loader.get(explicit) != null) {
            plan = loader.get(explicit);
        } else {
            plan = safeGet("default.v1", "default");
        }

        // Bind request-level budget into existing TimeBudgetContext (addons)
        TimeBudgetContext.set(new TimeBudget(plan.getTimeBudgetMs()));
        return new RagPlanContext(plan);
    }

    private PlanDefinition safeGet(String preferred, String fallback) {
        PlanDefinition p = loader.get(preferred);
        if (p != null) return p;
        p = loader.get(fallback);
        if (p != null) return p;
        // synthesize a default if not found
        PlanDefinition d = new PlanDefinition();
        d.setId(preferred);
        return d;
    }
}