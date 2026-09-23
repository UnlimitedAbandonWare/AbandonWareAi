package com.example.lms.search.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveSearchQueryVariantsTest {

    @Test
    void keepsBaseQueryFirstAndBounded() {
        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu",
                List.of("alpha beta"),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.BRAVE,
                        true,
                        3,
                        3500,
                        3500,
                        700,
                        true,
                        false,
                        false));

        assertEquals("alpha beta", plan.queries().get(0));
        assertTrue(plan.queries().size() <= 3);
        assertEquals("recall-policy", plan.triggerReason());
        assertTrue(plan.perCallMs() >= 700);
    }

    @Test
    void slicesCompoundQueryOnlyWhenAdaptiveIsEnabled() {
        String query = "First release note changed the API. Second sentence explains migration. Third sentence asks for examples.";

        AdaptiveSearchQueryVariants.Plan enabled = AdaptiveSearchQueryVariants.plan(
                query,
                List.of(query),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        true,
                        4,
                        4500,
                        4500,
                        600,
                        false,
                        false,
                        false));

        AdaptiveSearchQueryVariants.Plan disabled = AdaptiveSearchQueryVariants.plan(
                query,
                List.of(query),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        false,
                        4,
                        4500,
                        4500,
                        600,
                        false,
                        false,
                        false));

        assertEquals("compound-query", enabled.triggerReason());
        assertTrue(enabled.sliceCount() > 0 || enabled.expansionCount() > 0);
        assertEquals("disabled", disabled.triggerReason());
        assertEquals(1, disabled.queries().size());
    }

    @Test
    void shortSuccessfulQueryStaysBaseOnly() {
        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                "spring boot",
                List.of("spring boot"),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.BRAVE,
                        true,
                        3,
                        3500,
                        3500,
                        700,
                        false,
                        false,
                        false));

        assertEquals("base-only", plan.triggerReason());
        assertEquals(1, plan.queries().size());
        assertFalse(plan.enabled() && plan.variants().size() > 0);
    }

    @Test
    void recallPlanAddsAuthorityAndBoundedExplorationLanes() {
        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                "spring boot virtual threads",
                List.of("spring boot virtual threads"),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        true,
                        5,
                        4500,
                        4500,
                        600,
                        true,
                        false,
                        false));

        assertEquals("spring boot virtual threads", plan.queries().get(0));
        List<String> suffixes = suffixesAfterBase("spring boot virtual threads", plan.queries());
        assertTrue(suffixes.contains("implementation examples"));
        assertTrue(suffixes.contains("official source"));
        assertTrue(countExploratoryFacets(suffixes) >= 2);
        assertEquals("recall-policy", plan.triggerReason());
        assertTrue(plan.expansionCount() >= 3);
    }

    @Test
    void recallPlanKeepsVerificationCoolWhileAddingSeededExplorationLanes() {
        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                "spring boot virtual threads",
                List.of("spring boot virtual threads"),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        true,
                        7,
                        4500,
                        4500,
                        600,
                        true,
                        false,
                        false));

        assertEquals("exploratory", plan.temperatureProfile());
        assertTrue(plan.validationTemperature() <= 0.25d);
        assertTrue(plan.explorationTemperature() > plan.validationTemperature());
        assertTrue(plan.explorationTemperature() <= 0.75d);
        assertTrue(plan.explorationRate() > 0.0d);
        assertTrue(plan.queries().contains("spring boot virtual threads implementation examples"));
        assertTrue(plan.queries().contains("spring boot virtual threads official source"));
        assertTrue(countExploratoryFacets(suffixesAfterBase("spring boot virtual threads", plan.queries())) >= 3);
    }

    @Test
    void tightRecallBudgetKeepsAuthorityAndOneCreativeExplorationLane() {
        String query = "supabase realtime vectors";

        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                query,
                List.of(query),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.BRAVE,
                        true,
                        3,
                        3500,
                        3500,
                        700,
                        true,
                        false,
                        false));

        AdaptiveSearchQueryVariants.Diagnostics diagnostics = plan.diagnostics();
        List<String> suffixes = suffixesAfterBase(query, plan.queries());

        assertEquals(3, plan.queries().size());
        assertEquals("recall-policy", plan.triggerReason());
        assertTrue(plan.validationTemperature() <= 0.25d);
        assertTrue(plan.explorationTemperature() > plan.validationTemperature());
        assertTrue(suffixes.contains("official source"));
        assertTrue(countExploratoryFacets(suffixes) >= 1);
        assertEquals(1, diagnostics.verificationLaneCount());
        assertEquals(1, diagnostics.explorationLaneCount());
        assertTrue(String.valueOf(diagnostics.laneSummary()).contains("verification:official_source"));
        assertTrue(String.valueOf(diagnostics.laneSummary()).contains("exploration:"));
    }

    @Test
    void recencyHeavyRecallPlanPinsChangelogAsFirstExplorationLane() {
        String query = "RAG ON 웹검색 질의재작성 검증: 2026년 기준 AI 검색과 RAG 시스템에서 "
                + "conservative verification lane, official-source lane, changelog exploration lane을 "
                + "어떤 온도와 질의 변형으로 분리해야 하는지 최신 근거 중심으로 비교해줘.";

        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                query,
                List.of(query),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.BRAVE,
                        true,
                        3,
                        3500,
                        3500,
                        700,
                        true,
                        false,
                        false));

        AdaptiveSearchQueryVariants.Diagnostics diagnostics = plan.diagnostics();
        List<String> suffixes = suffixesAfterBase(query, plan.queries());

        assertEquals(3, plan.queries().size());
        assertTrue(suffixes.contains("official source"));
        assertTrue(suffixes.contains("changelog"));
        assertTrue(diagnostics.laneLabels().contains("verification:official_source"));
        assertTrue(diagnostics.laneLabels().contains("exploration:changelog"));
        assertTrue(plan.variantLaneDiagnostics().stream()
                .filter(lane -> "exploration:changelog".equals(lane.laneLabel()))
                .allMatch(lane -> lane.temperature() == plan.explorationTemperature()));
    }

    @Test
    void recallPlanRotatesExploratoryLanesByQuerySeedAfterVerificationAnchors() {
        String springQuery = "spring boot virtual threads";
        String supabaseQuery = "supabase realtime vectors";

        AdaptiveSearchQueryVariants.Plan spring = AdaptiveSearchQueryVariants.plan(
                springQuery,
                List.of(springQuery),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        true,
                        5,
                        4500,
                        4500,
                        600,
                        true,
                        false,
                        false));
        AdaptiveSearchQueryVariants.Plan supabase = AdaptiveSearchQueryVariants.plan(
                supabaseQuery,
                List.of(supabaseQuery),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        true,
                        5,
                        4500,
                        4500,
                        600,
                        true,
                        false,
                        false));

        List<String> springSuffixes = suffixesAfterBase(springQuery, spring.queries());
        List<String> supabaseSuffixes = suffixesAfterBase(supabaseQuery, supabase.queries());

        assertTrue(springSuffixes.contains("implementation examples"));
        assertTrue(springSuffixes.contains("official source"));
        assertTrue(supabaseSuffixes.contains("implementation examples"));
        assertTrue(supabaseSuffixes.contains("official source"));
        assertNotEquals(springSuffixes, supabaseSuffixes,
                "recall mode should seed-rotate exploratory lanes instead of repeating one fixed order");
    }

    @Test
    void compoundBalancedPlanReservesVerificationAndExplorationLanesBeforeSlices() {
        String query = "RAG web search query rewriting should verify official documentation while exploring creative failure modes and recent release changes.";

        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                query,
                List.of(query),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        true,
                        5,
                        4500,
                        4500,
                        600,
                        false,
                        false,
                        false));

        AdaptiveSearchQueryVariants.Diagnostics diagnostics = plan.diagnostics();
        List<String> suffixes = suffixesAfterBase(query, plan.queries());

        assertEquals("compound-query", plan.triggerReason());
        assertEquals("balanced", plan.temperatureProfile());
        assertTrue(plan.validationTemperature() <= 0.25d);
        assertTrue(plan.explorationTemperature() > plan.validationTemperature());
        assertTrue(suffixes.contains("official source"));
        assertTrue(countExploratoryFacets(suffixes) >= 1);
        assertTrue(diagnostics.verificationLaneCount() >= 1);
        assertTrue(diagnostics.explorationLaneCount() >= 1);
    }

    @Test
    void diagnosticsSummarizeSeededLanesWithoutRawQueryText() {
        String query = "private spring boot virtual threads ownerToken=secret";

        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                query,
                List.of(query),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        true,
                        7,
                        4500,
                        4500,
                        600,
                        true,
                        false,
                        false));

        AdaptiveSearchQueryVariants.Diagnostics diagnostics = plan.diagnostics();

        assertEquals(plan.queries().size(), diagnostics.queryCount());
        assertEquals(plan.variants().size(), diagnostics.variantCount());
        assertTrue(diagnostics.querySeedHash12().matches("[0-9a-f]{12}"));
        assertTrue(diagnostics.variantSetHash12().matches("[0-9a-f]{12}"));
        assertEquals(2, diagnostics.verificationLaneCount());
        assertTrue(diagnostics.explorationLaneCount() >= 3);
        assertTrue(diagnostics.laneLabels().contains("verification:implementation_examples"));
        assertTrue(diagnostics.laneLabels().contains("verification:official_source"));
        assertTrue(diagnostics.laneLabels().stream().anyMatch(label -> label.startsWith("exploration:")));
        String rendered = diagnostics.toString();
        assertFalse(rendered.contains("private spring boot"));
        assertFalse(rendered.contains("ownerToken"));
        assertFalse(rendered.contains("secret"));
    }

    @Test
    void variantLaneDiagnosticsExposeTemperatureBucketsWithoutRawQueryText() {
        String query = "private supabase vector search ownerToken=secret";

        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                query,
                List.of(query),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        true,
                        7,
                        4500,
                        4500,
                        600,
                        true,
                        false,
                        false));

        List<AdaptiveSearchQueryVariants.VariantLaneDiagnostic> lanes = plan.variantLaneDiagnostics();

        assertEquals(plan.variants().size(), lanes.size());
        assertTrue(lanes.stream().anyMatch(lane -> "verification".equals(lane.role())));
        assertTrue(lanes.stream().anyMatch(lane -> "exploration".equals(lane.role())));
        assertTrue(lanes.stream()
                .filter(lane -> "verification".equals(lane.role()))
                .allMatch(lane -> lane.temperature() == plan.validationTemperature()));
        assertTrue(lanes.stream()
                .filter(lane -> "exploration".equals(lane.role()))
                .allMatch(lane -> lane.temperature() == plan.explorationTemperature()));
        assertTrue(lanes.stream().allMatch(lane -> lane.queryHash12().matches("[0-9a-f]{12}")));
        String rendered = lanes.toString();
        assertFalse(rendered.contains("private supabase"));
        assertFalse(rendered.contains("ownerToken"));
        assertFalse(rendered.contains("secret"));
    }

    private static List<String> suffixesAfterBase(String query, List<String> planned) {
        String prefix = query + " ";
        return planned.stream()
                .skip(1)
                .map(value -> value.startsWith(prefix) ? value.substring(prefix.length()) : value)
                .collect(Collectors.toList());
    }

    private static long countExploratoryFacets(List<String> suffixes) {
        List<String> exploratory = List.of(
                "latest update",
                "counterexample",
                "failure modes",
                "changelog",
                "reference docs");
        return suffixes.stream()
                .filter(exploratory::contains)
                .count();
    }
}
