
package com.abandonware.ai.agent.onnx;
import com.abandonware.ai.agent.rag.model.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.Semaphore;

@Component
public class OnnxCrossEncoderReranker {
    private final Semaphore limiter;
    private final boolean enabled;

    public OnnxCrossEncoderReranker(Semaphore onnxLimiter, @Value("${onnx.enabled:true}") boolean enabled) {
        this.limiter = onnxLimiter; this.enabled = enabled;
    }

    // Placeholder: simulate scoring + sigmoid normalization (k=12, x0=0)
    private double sigmoid(double x) { return 1.0 / (1.0 + Math.exp(-12.0 * (x - 0.0))); }

    public List<Result> rerank(String query, List<Result> top) {
        if (!enabled || top==null || top.isEmpty()) return top;
        try {
            if (!limiter.tryAcquire()) return top; // degrade gracefully
            List<Result> scored = new ArrayList<>();
            int i=0;
            for (Result r: top) {
                double raw = 1.0/(i+1); // dummy: higher for earlier items
                double cal = sigmoid(raw);
                r.setScore(cal);
                r.setRank(++i);
                scored.add(r);
            }
            return scored;
        } finally {
            if (limiter.availablePermits()==0) limiter.release();
        }
    }
}
