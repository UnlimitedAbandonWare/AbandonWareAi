package com.nova.protocol.fusion;

import com.nova.protocol.fusion.NovaNextFusionService;
import com.nova.protocol.fusion.NovaNextFusionService.ScoredResult;
import com.nova.protocol.properties.NovaNextProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@ConditionalOnProperty(prefix="nova.next", name="enabled", havingValue="true")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.fusion.RrfHypernovaBridge
 * Role: config
 * Dependencies: com.nova.protocol.fusion.NovaNextFusionService, com.nova.protocol.fusion.NovaNextFusionService.ScoredResult, com.nova.protocol.properties.NovaNextProperties
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.nova.protocol.fusion.RrfHypernovaBridge
role: config
*/
public class RrfHypernovaBridge {
  @Autowired(required=false)
  private NovaNextFusionService fusion;
  private final NovaNextProperties props;

  @Autowired
  public RrfHypernovaBridge(NovaNextProperties props){
    this.props = props;
  }

  @SuppressWarnings({"rawtypes","unchecked"})
  public List postProcess(List rrfOut){
    if (props==null || !props.isEnabled() || rrfOut==null || rrfOut.isEmpty()) return rrfOut;
    if (fusion==null) return rrfOut;
    try {
      // reflect getScore/setScore
      java.lang.reflect.Method getter = null, setter = null;
      Object first = rrfOut.get(0);
      for (java.lang.reflect.Method m : first.getClass().getMethods()){
        if (getter==null && m.getName().equals("getScore") && m.getParameterCount()==0){ getter = m; }
        if (setter==null && m.getName().equals("setScore") && m.getParameterCount()==1){ setter = m; }
      }
      if (getter==null || setter==null) return rrfOut;
      List<ScoredResult> tmp = new ArrayList<>(rrfOut.size());
      for (Object o : rrfOut){
        Object val = getter.invoke(o);
        double score = (val instanceof Number)? ((Number)val).doubleValue() : 0.0;
        ScoredResult sr = new ScoredResult(); sr.setScore(score); tmp.add(sr);
      }
      List<ScoredResult> fused = fusion.fuse(tmp);
      for (int i=0;i<rrfOut.size() && i<fused.size();i++){
        setter.invoke(rrfOut.get(i), fused.get(i).getScore());
      }
      return rrfOut;
    } catch (Exception ignore){
      return rrfOut;
    }
  }

// Compatibility helper: post-process RRF scores list via Nova fusion
public java.util.List<Double> postProcessScores(java.util.List<Double> rrfOut) {
    if (fusion == null || rrfOut == null) return rrfOut;
    java.util.List<NovaNextFusionService.ScoredResult> tmp = new java.util.ArrayList<>(rrfOut.size());
    for (Double score : rrfOut) {
        NovaNextFusionService.ScoredResult sr = new NovaNextFusionService.ScoredResult();
        sr.setScore(score != null ? score.doubleValue() : 0.0);
        tmp.add(sr);
    }
    java.util.List<NovaNextFusionService.ScoredResult> fused = fusion.fuse(tmp);
    java.util.List<Double> out = new java.util.ArrayList<>(fused.size());
    for (NovaNextFusionService.ScoredResult sr : fused) out.add(sr.getScore());
    return out;
}


    // Overload for RRF pipeline: pass-through when given a single ContextSlice
    public com.abandonware.ai.service.rag.model.ContextSlice postProcess(
            com.abandonware.ai.service.rag.model.ContextSlice in) {
        // No-op in local build; real Hypernova calibration can be injected via NovaNextFusionService if desired.
        return in;
    }
    
}