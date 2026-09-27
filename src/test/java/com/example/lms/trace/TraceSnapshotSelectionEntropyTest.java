package com.example.lms.trace;

import com.example.lms.infra.selection.SelectionEntropyProjection;
import com.example.lms.search.TraceStore;
import com.example.lms.service.trace.TraceHtmlBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class TraceSnapshotSelectionEntropyTest {

    private static final String DIGEST =
            "7b4cfe15f9aaf721fe73133247ced66ecb89e14dbe1a6a59c0bb33aecc3dc349";

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void snapshotAllowsOnlyExactTypedEntropyKeys() {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("selectionEntropy.schema", "awx.selection-entropy.v1");
        trace.put("selectionEntropy.mode", "replay");
        trace.put("selectionEntropy.seedFingerprint", "009e8892e5b3");
        trace.put("selectionEntropy.decisionCount", 4);
        trace.put("selectionEntropy.completionOrderDeterministic", false);
        trace.put("selectionEntropy.seed", "sentinel-seed-material");
        trace.put("selectionEntropy.unknown", "authorization-shaped-value");
        trace.put("selectionEntropy.drawCount", "4");

        Map<String, Object> safe = capture(trace).trace();

        assertThat(safe)
                .containsEntry("selectionEntropy.mode", "replay")
                .containsEntry("selectionEntropy.decisionCount", 4)
                .containsEntry("selectionEntropy.completionOrderDeterministic", false)
                .doesNotContainKeys(
                        "selectionEntropy.seed",
                        "selectionEntropy.unknown",
                        "selectionEntropy.drawCount");
        assertThat(safe.toString())
                .doesNotContain("sentinel-seed-material", "authorization-shaped-value");
    }

    @Test
    void exactTraceRoundTripsOnlyACompleteValidatedProjection() {
        SelectionEntropyProjection projection = replayProjection();
        SelectionEntropyTraceSupport.write(projection);
        Map<String, Object> trace = TraceStore.getAll();

        assertThat(SelectionEntropyTraceSupport.fromTrace(trace)).contains(projection);

        Map<String, Object> unknown = new LinkedHashMap<>(trace);
        unknown.put("selectionEntropy.ownerToken", "sentinel-owner-token");
        assertThat(SelectionEntropyTraceSupport.fromTrace(unknown)).isEmpty();

        Map<String, Object> missing = new LinkedHashMap<>(trace);
        missing.remove("selectionEntropy.algorithmVersion");
        assertThat(SelectionEntropyTraceSupport.fromTrace(missing)).isEmpty();
    }

    @Test
    void malformedModesHashesCountsBooleansAndReasonsAreRejected() {
        Map<String, Object> invalid = Map.ofEntries(
                Map.entry("selectionEntropy.mode", "unknown"),
                Map.entry("selectionEntropy.coherenceStatus", "MATCHED"),
                Map.entry("selectionEntropy.seedFingerprint", "009e8892e5b"),
                Map.entry("selectionEntropy.decisionDigest", "A".repeat(64)),
                Map.entry("selectionEntropy.decisionCount", -1),
                Map.entry("selectionEntropy.drawCount", 10_001),
                Map.entry("selectionEntropy.routerDrawCount", "4"),
                Map.entry("selectionEntropy.replayAccepted", "true"),
                Map.entry("selectionEntropy.completionOrderDeterministic", "false"),
                Map.entry("selectionEntropy.reasonCode", "unknown_reason"),
                Map.entry("selectionEntropy.seed", "raw-seed"),
                Map.entry("selectionEntropy.X-AWX-Selection-Replay", "raw-header"));

        invalid.forEach((key, value) -> assertThat(
                SelectionEntropyTraceSupport.sanitizeExactValue(key, value))
                .as(key)
                .isNull());
        assertThat(SelectionEntropyTraceSupport.sanitizeExactValue(
                "selectionEntropy.seedFingerprint", "009e8892e5b30")).isNull();
        assertThat(SelectionEntropyTraceSupport.sanitizeExactValue(
                "selectionEntropy.decisionDigest", "a".repeat(63))).isNull();
    }

    private static SelectionEntropyProjection replayProjection() {
        return new SelectionEntropyProjection(
                "awx.selection-entropy.v1",
                "replay",
                "selection-entropy-v1",
                true,
                "matched",
                "009e8892e5b3",
                DIGEST,
                5,
                4,
                2,
                0,
                1,
                1,
                2,
                false,
                "");
    }

    private static TraceSnapshotStore.TraceSnapshot capture(Map<String, Object> trace) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        ObjectProvider<TraceHtmlBuilder> provider = factory.getBeanProvider(TraceHtmlBuilder.class);
        TraceSnapshotStore store = new TraceSnapshotStore(provider);
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "maxValueLen", 1000);
        ReflectionTestUtils.setField(store, "maxEntries", 100);
        ReflectionTestUtils.setField(store, "allowReasonsCsv", "");
        ReflectionTestUtils.setField(store, "denyReasonsCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysCsv", "");
        ReflectionTestUtils.setField(store, "allowKeysMode", "any");
        ReflectionTestUtils.setField(store, "denyKeysCsv", "");
        ReflectionTestUtils.setField(store, "captureSample", 1.0d);
        ReflectionTestUtils.setField(store, "minIntervalMs", 0L);
        ReflectionTestUtils.setField(store, "maxPerTrace", 10);
        ReflectionTestUtils.setField(store, "budgetWindowMs", 600_000L);
        ReflectionTestUtils.setField(store, "httpStatusMin", 400);
        ReflectionTestUtils.setField(store, "captureHttpOnDebug", true);
        ReflectionTestUtils.setField(store, "captureHttpOnMl", true);
        ReflectionTestUtils.setField(store, "captureHttpOnOrch", true);
        ReflectionTestUtils.setField(store, "captureHttpOnException", true);
        ReflectionTestUtils.setField(store, "htmlEnabled", false);
        ReflectionTestUtils.setField(store, "htmlMaxLen", 60_000);
        String id = store.captureCustom(
                "selection_entropy_test", "POST", "/api/chat", 200, null, trace, null);
        return store.get(id).orElseThrow();
    }
}
