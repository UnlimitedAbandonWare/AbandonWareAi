package strategy;



import strategy.PlanLoader;
import trace.TraceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal K-allocation policy container to avoid compile errors.
 */
public class RetrievalOrderService {
  public RetrievalOrderService(){ this.plans = new PlanLoader("safe_autorun.v1.yaml"); }

  private final PlanLoader plans;

    @Autowired(required=false) com.nova.protocol.alloc.RiskKAllocator kalloc;
    @Autowired(required=false) com.nova.protocol.properties.NovaNextProperties nprops;


    public static class KAllocationPolicy {
        public double webWeight = 0.7;
        public double ragWeight = 0.3;
    }

    private KAllocationPolicy policy = new KAllocationPolicy();

    public void setPolicy(KAllocationPolicy p) {
        if (p != null) this.policy = p;
    }

    public Map<String, Integer> selectOrderWithK(int totalK) {
        Map<String, Integer> alloc = new HashMap<>();
        int webK = (int)Math.max(1, Math.round(totalK * policy.webWeight));
        int ragK = Math.max(1, totalK - webK);
        alloc.put("web", webK);
        alloc.put("rag", ragK);
        alloc.put("kg", 0);
        return alloc;
    }


    /**
     * Recommend K allocation across sources using softmax over simple signals.
     * Signals (z): web prior, rag prior, kg prior. Temperature controls peaky-ness.
     */
    public Map<String, Integer> recommendK(int baseK, String query, double temperature) {
        int K = Math.max(1, baseK);
        // crude priors: favor web unless query hints kg keywords
        double webPrior = policy.webWeight;
        double ragPrior = policy.ragWeight;
        double kgPrior  = 0.1;
        if (query != null) {
            String q = query.toLowerCase();
            if (q.contains("graph") || q.contains("kg:") || q.contains("ontology")) kgPrior += 0.4;
            if (q.contains("pdf") || q.contains("site:gov") || q.contains("site:ac.kr")) webPrior += 0.2;
        }
        double[] pi = SoftmaxUtil.softmax(new double[]{webPrior, ragPrior, kgPrior}, temperature <= 0 ? 0.7 : temperature);
        int webK = Math.max(1, (int)Math.round(K * pi[0]));
        int ragK = Math.max(1, (int)Math.round(K * pi[1]));
        int kgK  = Math.max(0, K - webK - ragK);
        java.util.Map<String,Integer> m = new java.util.HashMap<>();
        m.put("web", webK); m.put("rag", ragK); m.put("kg", kgK);
        return m;
    }

}

// Hypernova patch hint: Use service.rag.allocation.RiskKAllocator to allocate K per source.