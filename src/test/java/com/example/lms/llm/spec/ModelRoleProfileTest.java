package com.example.lms.llm.spec;

import com.example.lms.llm.ModelCapabilities;
import com.example.lms.routing.RoutingProfile.Role;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static com.example.lms.llm.spec.ModelRoleProfile.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelRoleProfileTest {
    @Test void catalogDocumentMetadataKeepsTheExistingOutputContract() {
        String document = "https://developers.openai.com/reference";
        ModelSpecSnapshot snapshot = ModelSpecSnapshot.of("provider", "Exact-Model", "example.org", 1000, null,
                Set.of("chat"), Map.of("sourceDocUrl", document));
        assertEquals(com.example.lms.trace.SafeRedactor.diagnosticValue("sourceDocUrl", document),
                snapshot.metadata().get("sourceDocUrl"));
    }
    @Test void documentReferenceDoesNotExposeSensitiveQueryParameters() {
        String canary = "private-fixture-doc-key";
        ModelSpecSnapshot s = ModelSpecSnapshot.of("provider", "Exact-Model", "example.org", 1000, null,
                Set.of("chat"), Map.of("sourceDocUrl", "https://developers.openai.com/reference?api_key=" + canary));
        assertFalse(s.metadata().toString().contains(canary));
        assertTrue(s.roleProfile().nominalTier().provisional());
    }
    private static final Instant AT = Instant.parse("2026-10-03T00:00:00Z");
    private static final EvaluationVersion V1 = new EvaluationVersion("dataset-a", "model-a", "loadout-a", "grader-1");
    private ModelRoleProfile profile(Map<Role, Map<MetricName, Metric>> metrics) {
        return new ModelRoleProfile(new ModelKey("provider", "Exact-Model", "snapshot-a", "responses", "adapter-1"),
                new TierReference(Tier.GENERAL, "https://example.org/spec", "2026-10-03", true),
                Set.of(Role.MAIN_DEFAULT, Role.MAIN_FAST), ModelCapabilities.Profile.unknown(), "fixture",
                Readiness.CANDIDATE, V1, metrics);
    }
    @Test void unmeasuredCostIsUnknownNotFree() {
        Metric m = profile(Map.of()).metric(Role.MAIN_DEFAULT, MetricName.COST_PER_SUCCESS);
        assertEquals(State.UNKNOWN, m.state()); assertNull(m.value());
    }
    @Test void zeroExecutionsAreNotPerfectSuccess() {
        Metric m = Metric.rate(0, 0, AT, V1);
        assertEquals(State.UNKNOWN, m.state()); assertNull(m.value());
    }
    @Test void rolesDoNotShareSuccessMetrics() {
        ModelRoleProfile p = profile(Map.of(Role.MAIN_FAST, Map.of(MetricName.TASK_SUCCESS, Metric.rate(3, 4, AT, V1))));
        assertEquals(.75, p.metric(Role.MAIN_FAST, MetricName.TASK_SUCCESS).value());
        assertEquals(State.UNKNOWN, p.metric(Role.MAIN_DEFAULT, MetricName.TASK_SUCCESS).state());
    }
    @Test void mockCannotPromoteValidated() {
        ValidationEvidence mock = new ValidationEvidence(false, true, 2, 30, 1, 1, 1, true, true, true, true, V1);
        assertNotEquals(Readiness.VALIDATED, profile(Map.of()).validated(mock).readiness());
    }
    @Test void versionChangeKeepsHistoryAndResetsReadiness() {
        EvaluationVersion v2 = new EvaluationVersion("dataset-a", "model-a", "loadout-b", "grader-2");
        ModelRoleProfile p = profile(Map.of(Role.MAIN_FAST, Map.of(MetricName.TASK_SUCCESS, Metric.rate(3, 4, AT, V1)))).atVersion(v2);
        assertEquals(Readiness.UNMEASURED, p.readiness());
        assertEquals(State.HISTORICAL, p.roleMetrics().get(Role.MAIN_FAST).get(MetricName.TASK_SUCCESS).state());
        assertEquals(State.UNKNOWN, p.metric(Role.MAIN_FAST, MetricName.TASK_SUCCESS).state());
    }
    @Test void notApplicableRemainsDistinctFromUnknown() {
        Metric m = Metric.notApplicable(); assertEquals(State.NOT_APPLICABLE, m.state()); assertNull(m.value());
        assertEquals("N/A", m.unit());
    }
    @Test void zeroSuccessRetainsTotalCostWithoutDividingByZero() {
        Metric total = new Metric(State.MEASURED, .21, 3, "USD", AT, V1);
        ModelRoleProfile p = profile(Map.of(Role.MAIN_FAST, Map.of(MetricName.TOTAL_COST, total,
                MetricName.COST_PER_SUCCESS, Metric.costPerSuccess(.21, 0, AT, V1))));
        assertEquals(State.UNKNOWN, p.metric(Role.MAIN_FAST, MetricName.COST_PER_SUCCESS).state());
        assertEquals(.21, p.metric(Role.MAIN_FAST, MetricName.TOTAL_COST).value());
    }
    @Test void catalogDoesNotPromoteOrChangeAvailability() {
        ModelSpecSnapshot s = ModelSpecSnapshot.of("provider", "Exact-Model", "example.org", 1000, null,
                Set.of("tools"), Map.of("enabled", false, "catalogTrust", "attachment_unverified"));
        ModelRoleProfile p = ModelRoleProfile.fromCatalog(s);
        assertEquals(Readiness.UNMEASURED, p.readiness());
        assertEquals(ModelCapabilities.Support.UNKNOWN, p.capabilities().toolCalling());
        assertEquals(false, s.metadata().get("enabled")); assertEquals("Exact-Model", p.modelKey().modelId());
    }
    @Test void mismatchedDatasetCannotBeCompared() {
        EvaluationVersion other = new EvaluationVersion("dataset-other", "model-a", "loadout-a", "grader-1");
        ModelRoleProfile p = profile(Map.of(Role.MAIN_FAST, Map.of(MetricName.TASK_SUCCESS, Metric.rate(1, 1, AT, other))));
        assertEquals(State.UNKNOWN, p.metric(Role.MAIN_FAST, MetricName.TASK_SUCCESS).state());
    }
    @Test void syntheticEvaluatorPreservesCountsUnitsProvenanceAndCannotValidate() {
        Metric task = Metric.rate(3, 4, AT, V1);
        Metric cost = Metric.costPerSuccess(.21, 3, AT, V1);
        assertEquals(.75, task.value()); assertEquals(4, task.samples()); assertEquals("ratio", task.unit());
        assertEquals(AT, task.measuredAt()); assertEquals(V1, task.version()); assertEquals(.07, cost.value(), 1e-12);
        assertEquals("USD/success", cost.unit()); assertEquals(3, cost.samples());
        var p = profile(Map.of(Role.MAIN_FAST, Map.of(MetricName.TASK_SUCCESS, task, MetricName.COST_PER_SUCCESS, cost)));
        var synthetic = new ValidationEvidence(false, true, 2, 30, .99, .99, .99, true, true, true, true, V1);
        assertFalse(p.meetsValidationThresholds(synthetic)); assertEquals(Readiness.CANDIDATE, p.validated(synthetic).readiness());
    }
}
