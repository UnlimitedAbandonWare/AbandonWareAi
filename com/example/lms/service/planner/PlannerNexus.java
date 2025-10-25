package com.example.lms.service.planner;
import java.util.Map;
public final class PlannerNexus {
  private final PlanDslLoader loader = new PlanDslLoader();
  public Map<String,Object> resolvePlan(String headerOrDefault){
    String p=(headerOrDefault==null||headerOrDefault.isBlank())?"recency_first.v1":headerOrDefault;
    return loader.loadPlan(p);
  }
}
