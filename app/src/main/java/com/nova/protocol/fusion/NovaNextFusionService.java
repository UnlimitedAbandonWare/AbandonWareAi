package com.nova.protocol.fusion;

import com.nova.protocol.properties.NovaNextProperties;
import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.fusion.NovaNextFusionService
 * Role: config
 * Dependencies: com.nova.protocol.properties.NovaNextProperties
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.nova.protocol.fusion.NovaNextFusionService
role: config
*/
public class NovaNextFusionService {
  private final TailWeightedPowerMeanFuser twpm;
  private final CvarAggregator cvar;
  private final NovaNextProperties props;
  public NovaNextFusionService(TailWeightedPowerMeanFuser twpm, CvarAggregator cvar, NovaNextProperties props){
    this.twpm = twpm; this.cvar = cvar; this.props = props;
  }
  public List<ScoredResult> fuse(List<ScoredResult> rrfOut){
    if (!props.isEnabled() || rrfOut==null || rrfOut.isEmpty()) return rrfOut;
    double[] xs = rrfOut.stream().mapToDouble(ScoredResult::getScore).toArray();
    double[] ws = new double[xs.length]; java.util.Arrays.fill(ws, 1.0);
    double base = twpm.fuse(xs, ws, props.getP0(), props.getAlphaTwpm());
    double cv   = cvar.cvar(xs, props.getAlphaCvar());
    double s    = cvar.fuse(base, cv, props.getLambdaCvar());
    for (ScoredResult r : rrfOut) r.setScore(0.5*r.getScore()+0.5*s);
    return rrfOut;
  }
  public static class ScoredResult {
    private double score; public double getScore(){return score;} public void setScore(double s){this.score=s;}
  }
}