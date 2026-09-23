package com.nova.protocol.fusion;

import com.abandonware.ai.service.rag.model.ContextSlice;
import com.nova.protocol.fusion.NovaNextFusionService.ScoredResult;
import com.nova.protocol.properties.NovaNextProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@ConditionalOnProperty(prefix="nova.next", name="enabled", havingValue="true")
public class RrfHypernovaBridge {
  @Autowired(required=false)
  private NovaNextFusionService fusion;
  private final NovaNextProperties props;

  @Autowired
  public RrfHypernovaBridge(NovaNextProperties props){
    this.props = props;
  }

  public List<ContextSlice> postProcessContextSlices(List<ContextSlice> rrfOut){
    if (props==null || !props.isEnabled() || rrfOut==null || rrfOut.isEmpty()) return rrfOut;
    if (fusion==null) return rrfOut;
    List<ScoredResult> tmp = new ArrayList<>(rrfOut.size());
    for (ContextSlice slice : rrfOut){
      ScoredResult sr = new ScoredResult();
      sr.setScore(slice == null ? 0.0d : slice.getScore());
      tmp.add(sr);
    }
    List<ScoredResult> fused = fusion.fuse(tmp);
    for (int i=0;i<rrfOut.size() && i<fused.size();i++){
      ContextSlice slice = rrfOut.get(i);
      if (slice != null) {
        slice.setScore(fused.get(i).getScore());
      }
    }
    return rrfOut;
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
    public ContextSlice postProcess(ContextSlice in) {
        // No-op in local build; real Hypernova calibration can be injected via NovaNextFusionService if desired.
        return in;
    }
    
}
