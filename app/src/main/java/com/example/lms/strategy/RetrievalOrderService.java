package com.example.lms.strategy;

import com.nova.protocol.plan.PlanLoader;
import com.example.lms.trace.TraceContext;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.strategy.RetrievalOrderService
 * Role: config
 * Feature Flags: kg
 * Dependencies: com.nova.protocol.plan.PlanLoader, com.example.lms.trace.TraceContext
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.example.lms.strategy.RetrievalOrderService
role: config
flags: [kg]
*/
public \1

static class RetrievalIntent {
    boolean prefersWeb(){ return true; }
    boolean prefersVector(){ return true; }
    boolean prefersKG(){ return false; }
}
private RetrievalIntent analyzeIntent(Object query){
    return new RetrievalIntent();
}


    public RetrievalOrderService(){
        this.plans = new PlanLoader();
    }

    private final PlanLoader plans;

    @Autowired(required = false)
    private com.nova.protocol.alloc.RiskKAllocator riskK;

    @Autowired(required = false)
    private com.nova.protocol.properties.NovaNextProperties novaProps;

    private final Map<String,Integer> kOverrides = new HashMap<>();
    private final Map<String,Boolean> enableOverrides = new HashMap<>();
    private List<String> lastOrder = java.util.Arrays.asList("web","vector","kg");

    /** Decide the retrieval order conservatively; apply mode bias if present. */
    public List<String> decideOrder(){
        List<String> base = new ArrayList<>(java.util.Arrays.asList("web","vector","kg"));
        TraceContext tc = TraceContext.current();
        String mode = tc.getMode();
        if (mode != null && "HYPERNOVA".equalsIgnoreCase(mode)) {
            // bias to web recency
            return java.util.Arrays.asList("web","vector","kg");
        }
        return base;
    }

    /** Decide K for each source with CVaR-aware soft allocation (fail-soft). */
    protected Map<String,Integer> decideKWithRisk(double webLogit, double vecLogit, double kgLogit,
                                                  double[] recentScores){
        int K = (novaProps != null ? novaProps.getKTotal() : 24);
        int[] floor = new int[]{ (novaProps!=null? novaProps.getFloorWeb():4),
                                 (novaProps!=null? novaProps.getFloorVec():4),
                                 (novaProps!=null? novaProps.getFloorKg():2) };
        double T = (novaProps!=null? novaProps.getTempSoftmax():0.85);

        int[] alloc = (riskK!=null)
            ? riskK.allocCvarAware(new double[]{webLogit, vecLogit, kgLogit}, recentScores, K, T)
            : new int[]{Math.max(floor[0], K/2), Math.max(floor[1], K/3), Math.max(floor[2], K/6)};

        Map<String,Integer> out = new java.util.LinkedHashMap<>();
        out.put("web",   Math.max(floor[0], alloc[0]));
        out.put("vector",Math.max(floor[1], alloc[1]));
        out.put("kg",    Math.max(floor[2], alloc[2]));
        return out;
    }

    private void incK(double factor) {
        kOverrides.compute("web", (k,v) -> (int)Math.round((v==null?10:v) * (1.0+factor)));
        kOverrides.compute("vec", (k,v) -> (int)Math.round((v==null?6:v) * (1.0+factor)));
        kOverrides.compute("kg", (k,v) -> (int)Math.round((v==null?3:v) * (1.0+factor)));
    }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number)o).intValue();
        try { return Integer.parseInt(o.toString()); } catch(Exception e){ return def; }
    }
}