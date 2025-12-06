package planner;

import planner.model.PlanSpec;

import java.util.Map;

public class PlannerNexus {
  private final PlanSpec plan;
  public PlannerNexus(PlanSpec plan) { this.plan = plan; }

  @SuppressWarnings("unchecked")
  public String[] retrievalOrder() {
    if (plan == null || plan.retrieval == null) return new String[]{"vector", "web", "kg"};
    Object order = plan.retrieval.get("order");
    if (!(order instanceof Map)) return new String[]{"vector", "web", "kg"};
    Object v = ((Map<String,Object>)order).get("stages");
    if (v instanceof java.util.List) {
      java.util.List<String> list = (java.util.List<String>) v;
      return list.toArray(new String[0]);
    }
    return new String[]{"vector", "web", "kg"};
  }

  @SuppressWarnings("unchecked")
  public int k(String source, int fallback) {
    if (plan == null || plan.retrieval == null) return fallback;
    Object k = plan.retrieval.get("k");
    if (!(k instanceof Map)) return fallback;
    Object v = ((Map<String,Object>)k).getOrDefault(source, fallback);
    if (v instanceof Number) return ((Number)v).intValue();
    return fallback;
  }

  @SuppressWarnings("unchecked")
  public double recencyHalfLifeDays(double fallback) {
    if (plan == null || plan.fusion == null) return fallback;
    Object fw = plan.fusion.get("recencyWeight");
    if (!(fw instanceof Map)) return fallback;
    Object v = ((Map<String,Object>)fw).getOrDefault("halfLifeDays", fallback);
    if (v instanceof Number) return ((Number)v).doubleValue();
    return fallback;
  }

  @SuppressWarnings("unchecked")
  public boolean gate(String name, boolean fallback) {
    if (plan == null || plan.gates == null) return fallback;
    Object v = plan.gates.getOrDefault(name, fallback);
    return v instanceof Boolean ? (Boolean)v : fallback;
  }
}