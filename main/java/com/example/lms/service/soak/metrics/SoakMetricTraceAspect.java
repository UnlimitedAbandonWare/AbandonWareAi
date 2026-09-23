package com.example.lms.service.soak.metrics;

import com.example.lms.metrics.FaithfulnessMetricSnapshotStore;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class SoakMetricTraceAspect {

    @AfterReturning("execution(* com.example.lms.service.soak.metrics.SoakMetricRegistry.recordWebMerge(..)) && target(registry)")
    public void publishAfterRecordWebMerge(SoakMetricRegistry registry) {
        try {
            if (registry == null) {
                TraceStore.put("soak.trace.aspect.skipReason", "missing_registry");
                return;
            }
            SoakMetricRegistry.Snapshot snapshot = registry.snapshotCurrent();
            TraceStore.put("soak.trace.aspect.recordWebMerge", true);
            publish("soak.fpFilterLegacyBypassCount", snapshot.fpFilterLegacyBypassCount);
            publish("soak.webCalls", snapshot.webCalls);
            publish("soak.webCallsWithNaver", snapshot.webCallsWithNaver);
            publish("soak.webMergedTotal", snapshot.webMergedTotal);
            publish("soak.webMergedFromNaver", snapshot.webMergedFromNaver);
            publish("soak.naverCallInclusionRate", snapshot.naverCallInclusionRate);
            publish("soak.naverMergedShare", snapshot.naverMergedShare);
        } catch (RuntimeException ex) {
            TraceStore.put("soak.trace.aspect.recordWebMerge", false);
            TraceStore.put("soak.trace.aspect.errorType", ex.getClass().getSimpleName());
        }
    }

    private static void publish(String key, Object value) {
        TraceStore.put(key, value);
        FaithfulnessMetricSnapshotStore.put(key, value);
    }
}
