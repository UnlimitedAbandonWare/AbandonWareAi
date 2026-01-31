package planner.model;
import java.util.Map;

public class PlanSpec {
  public String id;
  public String description;
  public Map<String,Object> retrieval;
  public Map<String,Object> fusion;
  public Map<String,Object> gates;
}