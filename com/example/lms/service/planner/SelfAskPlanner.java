package com.example.lms.service.planner;
import java.util.*; 
import com.example.lms.service.planner.dto.SelfAskPlan;
public class SelfAskPlanner {
  public SelfAskPlan plan(String q){
    List<String> subs=new ArrayList<>();
    subs.add(q+" definition and key terms");      // BQ
    subs.add("aliases, synonyms, typos of: "+q);  // ER
    subs.add("causal, metric, KPI context of: "+q); // RC
    return new SelfAskPlan(q, subs);
  }
}
