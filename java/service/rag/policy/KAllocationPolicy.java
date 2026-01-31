package service.rag.policy;

import planner.PlannerNexus;
import java.util.Map;

public class KAllocationPolicy {
  private final PlannerNexus plan;
  public KAllocationPolicy(PlannerNexus plan){ this.plan=plan; }

  public Map<String,Integer> decide(String intent, boolean needsWebFreshness){
    int webK = plan.k("web", 8);
    int vecK = plan.k("vector", 8);
    int kgK  = plan.k("kg", 4);
    if (needsWebFreshness) webK = Math.min(webK*2, 20);
    if (intent != null && intent.matches(".*(finance|news|changelog).*")) webK = Math.max(webK, 12);
    return java.util.Map.of("web", webK, "vector", vecK, "kg", kgK);
  }
}