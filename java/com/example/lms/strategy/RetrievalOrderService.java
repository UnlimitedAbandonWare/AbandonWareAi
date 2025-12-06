package com.example.lms.strategy;



import strategy.PlanLoader;
import trace.TraceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import com.example.lms.guard.rulebreak.RuleBreakContext;
import com.example.lms.guard.rulebreak.RuleBreakPolicy;




/**
 * Determines the order in which retrieval sources (Web, Vector, Knowledge Graph)
 * should be queried.  This simple heuristic implementation inspects the query
 * length and the presence of basic interrogative words to choose an order.
 *
 * <p>
 * - If the query contains question words (who/what/where/when/why/how), prefer KG first.
 * - If the query is long (>100 characters), vector search is prioritized.
 * - Otherwise, use the default Web→Vector→KG order.
 */
@Service
@Slf4j
public class RetrievalOrderService {
  public RetrievalOrderService(){ this.plans = new PlanLoader("safe_autorun.v1.yaml"); }

  private final PlanLoader plans;

    @Autowired(required=false) com.nova.protocol.alloc.RiskKAllocator kalloc;
    @Autowired(required=false) com.nova.protocol.properties.NovaNextProperties nprops;


    public static class KAllocationPolicy {
        public double webWeight = 0.7;
        public double ragWeight = 0.3;
        public double kgWeight = 0.0;
    }
    private KAllocationPolicy kPolicy = new KAllocationPolicy();

    public void setKPolicy(KAllocationPolicy p) { if (p != null) this.kPolicy = p; }

    public java.util.Map<Source, Integer> allocateK(int totalK) {
        int webK = (int)Math.max(1, Math.round(totalK * kPolicy.webWeight));
        int ragK = (int)Math.max(0, Math.round(totalK * kPolicy.ragWeight));
        int kgK  = Math.max(0, totalK - webK - ragK);
        java.util.Map<Source, Integer> m = new java.util.EnumMap<>(Source.class);
        m.put(Source.WEB, webK);
        m.put(Source.VECTOR, ragK);
        m.put(Source.KG, kgK);
        return m;
    }
    

    public enum Source { WEB, VECTOR, KG }

    /**
     * Decide the retrieval order for a given query text.
     *
     * @param queryText the raw query string
     * @return a list of sources in the order they should be invoked
     */
    public List<Source> decideOrder(String queryText) {
        // Enforce a fixed retrieval order of Web → Vector → Knowledge Graph.  The heuristics
        // previously used to reorder based on interrogative words or query length
        // have been removed to ensure deterministic execution.  Additional stages
        // such as Self-Ask and Analyze are executed prior to this method.
        return List.of(Source.WEB, Source.VECTOR, Source.KG);
    }
}
// Hypernova patch hint: Use service.rag.allocation.RiskKAllocator to allocate K per source.