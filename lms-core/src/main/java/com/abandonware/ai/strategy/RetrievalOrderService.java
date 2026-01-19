package com.abandonware.ai.strategy;



import strategy.PlanLoader;
import trace.TraceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.strategy.RetrievalOrderService
 * Role: service
 * Feature Flags: kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.strategy.RetrievalOrderService
role: service
flags: [kg]
*/
public class RetrievalOrderService {
  public RetrievalOrderService(){ this.plans = new PlanLoader("safe_autorun.v1.yaml"); }

  private final PlanLoader plans;

    @Autowired(required=false) com.nova.protocol.alloc.RiskKAllocator kalloc;
    @Autowired(required=false) com.nova.protocol.properties.NovaNextProperties nprops;


    public List<String> decide(String policy, String query) {
        if (policy == null) policy = "auto";
        return switch (policy) {
            case "web_first" -> List.of("web", "vector", "kg");
            case "vector_first" -> List.of("vector", "web", "kg");
            case "kg_first" -> List.of("kg", "vector", "web");
            default -> auto(query);
        };
    }

    protected List<String> auto(String query) {
        // naive heuristic: if query length is small, web first; if it looks like entity, kg first; else vector.
        if (query == null || query.length() < 24) return List.of("web", "vector", "kg");
        if (query.toLowerCase().contains("who is") || query.toLowerCase().contains("what is")) return List.of("kg", "web", "vector");
        return List.of("vector", "web", "kg");
    }
}
// Hypernova patch hint: Use service.rag.allocation.RiskKAllocator to allocate K per source.