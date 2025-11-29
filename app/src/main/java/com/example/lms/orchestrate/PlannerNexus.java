package com.example.lms.orchestrate;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import com.example.lms.orchestrate.plan.PlanResolver;
import com.example.lms.strategy.RetrievalOrderService;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.orchestrate.PlannerNexus
 * Role: config
 * Dependencies: com.example.lms.orchestrate.plan.PlanResolver, com.example.lms.strategy.RetrievalOrderService
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.orchestrate.PlannerNexus
role: config
*/
public class PlannerNexus {
    private final PlanResolver plan;
    private final RetrievalOrderService order;

    public PlannerNexus(PlanResolver plan, RetrievalOrderService order) {
        this.plan = plan; this.order = order;
    }

    @PostConstruct
    public void applyPlanToPipeline() {
        var p = plan.current();
        if (p != null) {
            order.overrideK(p.k());
            order.overrideEnables(p.enable());
        }
    }
}