package service.rag.handler;




import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.config.alias.NineTileAliasCorrector;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
/**
 * Minimal chain stub with plan binding hooks.
 */
@lombok.RequiredArgsConstructor
@org.springframework.stereotype.Component

public class DynamicRetrievalHandlerChain {

  @org.springframework.beans.factory.annotation.Value("${rag.diversity.enabled:true}") private boolean diversityEnabled;
  @org.springframework.beans.factory.annotation.Value("${rag.diversity.lambda:0.7}") private double mmrLambda;

  @org.springframework.beans.factory.annotation.Autowired(required = false) private telemetry.LoggingSseEventPublisher sse;

//     private Object sse;  // removed duplicate

    @Autowired(required=false) com.nova.protocol.alloc.RiskKAllocator kalloc;
    @Autowired(required=false) com.nova.protocol.properties.NovaNextProperties nprops;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private NineTileAliasCorrector aliasCorrector;

    private final com.abandonware.ai.agent.service.rag.bm25.Bm25LocalRetriever bm25Retriever;

    public static class PlanParams {
        public int webTopK = 10;
        public int timeoutMs = 1800;
        public boolean calibratedRrf = true;
    }

    private PlanParams plan = new PlanParams();

    public void bindPlan(PlanParams plan) {
        if (plan != null) this.plan = plan;
        // SSE placeholder: plan.selected
        TraceStore.put("dynamicRetrieval.alias.planSelected", plan == null ? "default" : "bound");
    }

    public int getWebTopK() { return plan.webTopK; }
    public int getTimeoutMs() { return plan.timeoutMs; }
    public boolean isCalibratedRrf() { return plan.calibratedRrf; }

    private void _sse(Object a1, Object a2) {
        if (sse == null) return;
        try {
            sse.emit(String.valueOf(a1), String.valueOf(a2));
        } catch (Throwable _t) { traceSseFallback("emit2", _t); }
    }
    private void _sse(Object a1, Object a2, java.util.Map meta) {
        if (sse == null) return;
        try {
            sse.emit(String.valueOf(a1), String.valueOf(a2));
        } catch (Throwable _t) { traceSseFallback("emit3", _t); }
    }

    private static void traceSseFallback(String stage, Throwable error) {
        String safeStage = stage == null || stage.isBlank() ? "unknown" : stage;
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        TraceStore.put("dynamicRetrieval.alias.sse.suppressed.stage", safeStage);
        TraceStore.put("dynamicRetrieval.alias.sse.suppressed.errorType", errorType);
        TraceStore.put("dynamicRetrieval.alias.sse.suppressed." + safeStage, true);
        TraceStore.put("dynamicRetrieval.alias.sse.suppressed." + safeStage + ".errorType", errorType);
    }

}
