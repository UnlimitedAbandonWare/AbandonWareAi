package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AblationContributionTrackerTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void ablationContributionTrackerDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/AblationContributionTracker.java"),
                StandardCharsets.UTF_8);
        long exactEmptyCatches = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source).results().count();

        assertEquals(0L, exactEmptyCatches,
                "ablation contribution tracking needs safe breadcrumbs instead of exact empty catch bodies");
        assertFalse(source.contains("catch (Exception ignore) {\n            return dflt;\n        }"));
        assertTrue(source.contains("catch (NumberFormatException ignore) {"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.asDouble\", true)"));
        assertTrue(source.contains("return dflt;"));
        assertFalse(source.contains("\"бж\""));
        assertTrue(source.contains("+ \"...\";"));
    }

    @Test
    void ablationContributionTrackerFallbacksExposeBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/trace/AblationContributionTracker.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.minScoreRead\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.minScoreRead.errorType\", \"trace_read_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.recordPenalty\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.recordPenalty.errorType\", \"record_penalty_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.onceDedupe\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.onceDedupe.errorType\", \"dedupe_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.finalizationDedupe\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.finalizationDedupe.errorType\", \"finalization_dedupe_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.finalizationEventCast\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.finalizationEventCast.errorType\", \"event_cast_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.finalization\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.finalization.errorType\", \"finalization_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.scoreSummary\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.scoreSummary.errorType\", \"score_summary_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.sampleIndex\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.sampleIndex.errorType\", \"sample_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.traceAnchorProjection\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.traceAnchorProjection.errorType\", \"trace_anchor_projection_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.idHashFromTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.idHashFromTrace.errorType\", \"trace_id_hash_failed\")"));
        assertTrue(source.contains("TraceStore.put(\"ablation.suppressed.asDouble\", true)"));
    }

    @Test
    void numericFallbackUsesStableInvalidNumberLabel() throws Exception {
        Method method = AblationContributionTracker.class.getDeclaredMethod("asDouble", Object.class, double.class);
        method.setAccessible(true);

        assertEquals(0.25d, (Double) method.invoke(null, "not-a-double", 0.25d), 1.0e-9d);
        assertEquals(Boolean.TRUE, TraceStore.get("ablation.suppressed.asDouble"));
        assertEquals("invalid_number", TraceStore.get("ablation.suppressed.asDouble.errorType"));
    }

    @Test
    void finalizationProjectsPrimaryAndSecondaryAnchorStacksWithoutRawQuery() {
        TraceStore.put("rawQuery", "raw query must not leak");
        TraceStore.put("traceId", "trace-raw");
        TraceStore.put("requestId", "request-raw");
        TraceStore.put("sessionId", "session-raw");
        AblationContributionTracker.recordPenalty("web.await", "missing_future", 0.20d, "raw query must not leak");
        AblationContributionTracker.recordPenalty("model_guard", "citation escalation", 0.10d, "ownerToken=abc");

        AblationContributionTracker.finalizeTraceIfNeeded();

        assertEquals("anchor-stack-v1", TraceStore.get("ablation.anchor.version"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> primary =
                (List<Map<String, Object>>) TraceStore.get("ablation.anchor.primaryStack");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondary =
                (List<Map<String, Object>>) TraceStore.get("ablation.anchor.secondaryStack");

        assertFalse(primary.isEmpty());
        assertFalse(secondary.isEmpty());
        assertTrue(primary.stream().anyMatch(row -> "web".equals(row.get("component"))));
        assertTrue(secondary.stream().allMatch(row -> row.containsKey("anchorHash")));
        assertTrue(secondary.stream().allMatch(row -> row.containsKey("expectedDelta")));
        assertTrue(secondary.stream().allMatch(row -> row.containsKey("scoreDelta")));
        assertTrue(secondary.stream().allMatch(row -> row.containsKey("dropRatio")));
        assertTrue(secondary.stream().allMatch(row -> row.containsKey("maxDrawdown")));
        assertEquals("trace-anchor-v1", TraceStore.get("ablation.traceAnchor.version"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traceAnchors =
                (List<Map<String, Object>>) TraceStore.get("ablation.traceAnchor.rows");
        assertFalse(traceAnchors.isEmpty());
        Map<String, Object> row = traceAnchors.get(0);
        for (String key : List.of("traceIdHash", "requestIdHash", "sessionIdHash", "anchorHash",
                "anchorLen", "evidenceDigestHash", "matrixTile", "delta", "p", "expectedDelta",
                "component", "lane", "routeHint")) {
            assertTrue(row.containsKey(key), "missing traceAnchor key " + key);
        }
        assertTrue(String.valueOf(row.get("traceIdHash")).startsWith("hash:"));
        assertTrue(TraceStore.get("ablation.traceAnchor.top") instanceof Map<?, ?>);
        String publicPayload = primary + " " + secondary + " " + traceAnchors + " "
                + TraceStore.get("ablation.traceAnchor.top");
        assertFalse(publicPayload.contains("raw query must not leak"));
        assertFalse(publicPayload.contains("ownerToken=abc"));
        assertFalse(publicPayload.contains("trace-raw"));
        assertFalse(publicPayload.contains("request-raw"));
        assertFalse(publicPayload.contains("session-raw"));
    }

    @Test
    void primaryAnchorKeepsLaneSplitAndStoredStacksBounded() {
        AblationContributionTracker.recordPenalty("web.await", "domain_definition", 0.20d, "bq lane");
        AblationContributionTracker.recordPenalty("web.await", "alias_synonym", 0.19d, "er lane");
        for (int i = 0; i < 14; i++) {
            AblationContributionTracker.recordPenalty("stage-" + i, "guard-" + i, 0.01d, "bulk");
        }

        AblationContributionTracker.finalizeTraceIfNeeded();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> primary =
                (List<Map<String, Object>>) TraceStore.get("ablation.anchor.primaryStack");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondary =
                (List<Map<String, Object>>) TraceStore.get("ablation.anchor.secondaryStack");

        assertTrue(primary.size() <= 9);
        assertTrue(secondary.size() <= 12);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traceAnchors =
                (List<Map<String, Object>>) TraceStore.get("ablation.traceAnchor.rows");
        assertTrue(traceAnchors.size() <= 12);
        assertTrue(primary.stream().anyMatch(row ->
                "web".equals(row.get("component")) && "BQ".equals(row.get("lane"))));
        assertTrue(primary.stream().anyMatch(row ->
                "web".equals(row.get("component")) && "ER".equals(row.get("lane"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void finalizationClassifiesTavilyProviderFailuresAsWebAnchor() {
        AblationContributionTracker.recordPenalty("provider.skipped", "tavily-disabled", 0.20d, "safe");

        AblationContributionTracker.finalizeTraceIfNeeded();

        List<Map<String, Object>> traceAnchors =
                (List<Map<String, Object>>) TraceStore.get("ablation.traceAnchor.rows");

        assertTrue(traceAnchors.stream().anyMatch(row ->
                "web".equals(row.get("component"))
                        && "WEB".equals(row.get("lane"))
                        && Integer.valueOf(1).equals(row.get("matrixTile"))
                        && "fail_soft_fallback".equals(row.get("routeHint"))),
                String.valueOf(traceAnchors));
    }

    @Test
    @SuppressWarnings("unchecked")
    void drawdownMetricsAreClampedAndDuplicateOnceKeyIsSuppressed() {
        AblationContributionTracker.recordPenaltyOnce("same-key", "rerank.secondPass", "score_drop", 1.0d,
                "ownerToken=abc");
        AblationContributionTracker.recordPenaltyOnce("same-key", "rerank.secondPass", "score_drop", 1.0d,
                "ownerToken=abc");

        List<Map<String, Object>> penalties =
                (List<Map<String, Object>>) TraceStore.get("ablation.penalties");
        assertEquals(1, penalties.size());
        Map<String, Object> first = penalties.get(0);
        double scoreDelta = ((Number) first.get("scoreDelta")).doubleValue();
        double dropRatio = ((Number) first.get("dropRatio")).doubleValue();
        double maxDrawdown = ((Number) first.get("maxDrawdown")).doubleValue();

        assertTrue(scoreDelta >= 0.0d && scoreDelta <= 1.0d);
        assertTrue(dropRatio >= 0.0d && dropRatio <= 1.0d);
        assertTrue(maxDrawdown >= 0.0d && maxDrawdown <= 1.0d);
        assertTrue(scoreDelta < 0.20d, "Bode-like clamp should damp a single spike");
        assertFalse(String.valueOf(first.get("note")).contains("ownerToken=abc"));

        AblationContributionTracker.finalizeTraceIfNeeded();
        assertTrue(TraceStore.get("ablation.score") instanceof Map<?, ?>);
        assertTrue(TraceStore.get("ablation.scoreDelta.latest") instanceof Map<?, ?>);
        assertTrue(TraceStore.get("ablation.maxDrawdown") instanceof Number);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonFinitePenaltyDeltaDoesNotBecomeMaxPenalty() {
        AblationContributionTracker.recordPenalty("web.await", "missing_future", Double.POSITIVE_INFINITY, "safe");

        List<Map<String, Object>> penalties =
                (List<Map<String, Object>>) TraceStore.get("ablation.penalties");
        Map<String, Object> first = penalties.get(0);

        assertEquals(0.0d, ((Number) first.get("rawScoreDelta")).doubleValue(), 0.0001d);
        assertEquals(0.0d, ((Number) first.get("scoreDelta")).doubleValue(), 0.0001d);
        assertEquals(1.0d, ((Number) TraceStore.get("ablation.score.current")).doubleValue(), 0.0001d);
    }

    @Test
    @SuppressWarnings("unchecked")
    void penaltyStepAndGuardUseSafeLabels() {
        String rawStep = "private step ownerToken=raw-secret";
        String rawGuard = "private guard api_key=token";

        AblationContributionTracker.recordPenalty(rawStep, rawGuard, 0.10d, "safe note");

        List<Map<String, Object>> penalties =
                (List<Map<String, Object>>) TraceStore.get("ablation.penalties");
        Map<String, Object> first = penalties.get(0);
        String payload = String.valueOf(first) + " " + TraceStore.get("ablation.scoreDelta.latest");

        assertFalse(payload.contains(rawStep), payload);
        assertFalse(payload.contains(rawGuard), payload);
        assertFalse(payload.contains("raw-secret"), payload);
        assertTrue(String.valueOf(first.get("step")).startsWith("hash:"));
        assertTrue(String.valueOf(first.get("guard")).startsWith("hash:"));
    }

    @Test
    void duplicateOnceKeyIsStoredAsHashOnlyTraceKey() {
        String rawOnceKey = "student private prompt token=test-secret-abcdefghijklmnop";

        AblationContributionTracker.recordPenaltyOnce(rawOnceKey, "web.await", "missing_future", 0.10d, "safe");

        String traceKeys = TraceStore.getAll().keySet().toString();
        assertFalse(traceKeys.contains(rawOnceKey));
        assertFalse(traceKeys.contains("token=test-secret-abcdefghijklmnop"));
        assertTrue(traceKeys.contains("ablation.once.hash."));
    }
}
