package com.example.lms.service.soak.metrics;

import com.example.lms.metrics.FaithfulnessMetricSnapshotStore;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoakMetricRegistryTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        FaithfulnessMetricSnapshotStore.clear();
    }

    @Test
    void recordWebMergePublishesRedactedSnapshot() {
        SoakMetricRegistry registry = new SoakMetricRegistry();

        registry.recordWebCall(true);
        registry.recordWebMerge(4, 1);

        assertEquals(1L, TraceStore.get("soak.webCalls"));
        assertEquals(1L, TraceStore.get("soak.webCallsWithNaver"));
        assertEquals(4L, TraceStore.get("soak.webMergedTotal"));
        assertEquals(1L, TraceStore.get("soak.webMergedFromNaver"));
        assertEquals(1.0d, (Double) TraceStore.get("soak.naverCallInclusionRate"), 1e-9);
        assertEquals(0.25d, (Double) TraceStore.get("soak.naverMergedShare"), 1e-9);
        assertFalse(TraceStore.getAll().toString().contains("ownerToken"));
    }

    @Test
    void recordWebCallPublishesSnapshotBeforeMerge() {
        SoakMetricRegistry registry = new SoakMetricRegistry();

        registry.recordWebCall(true);
        TraceStore.clear();

        assertEquals(1L, FaithfulnessMetricSnapshotStore.get("soak.webCalls"));
        assertEquals(1L, FaithfulnessMetricSnapshotStore.get("soak.webCallsWithNaver"));
        assertEquals(0L, FaithfulnessMetricSnapshotStore.get("soak.webMergedTotal"));
        assertEquals(1.0d, (Double) FaithfulnessMetricSnapshotStore.get("soak.naverCallInclusionRate"), 1e-9);
    }

    @Test
    void traceAspectPublishesSnapshotAfterRecordWebMerge() {
        SoakMetricRegistry registry = new SoakMetricRegistry();
        registry.recordWebCall(true);
        registry.recordWebMerge(4, 1);
        TraceStore.clear();

        new SoakMetricTraceAspect().publishAfterRecordWebMerge(registry);

        assertEquals(Boolean.TRUE, TraceStore.get("soak.trace.aspect.recordWebMerge"));
        assertEquals(4L, TraceStore.get("soak.webMergedTotal"));
        assertEquals(1L, TraceStore.get("soak.webMergedFromNaver"));
        assertEquals(0.25d, (Double) TraceStore.get("soak.naverMergedShare"), 1e-9);
        assertFalse(TraceStore.getAll().toString().contains("ownerToken"));
    }

    @Test
    void traceAspectHasAfterReturningRecordWebMergePointcut() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/soak/metrics/SoakMetricTraceAspect.java"));

        assertTrue(source.contains("@Aspect"));
        assertTrue(source.contains("@Order"));
        assertTrue(source.contains("@AfterReturning"));
        assertTrue(source.contains("SoakMetricRegistry.recordWebMerge(..)"));
        assertFalse(source.contains("catch (Exception ignored)"));
    }
}
