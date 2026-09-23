package com.example.lms.search.policy;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GrokPromotionQueryVariantsTest {
    private AdaptiveSearchQueryVariants.Options options(boolean enabled, int count, long budget) {
        return new AdaptiveSearchQueryVariants.Options(AdaptiveSearchQueryVariants.Provider.BRAVE,
                enabled, count, budget, budget, 700, false, false, false);
    }

    @Test
    void shortPromotionQueryDiscoversTargetedAndRetentionOffersWithoutWaitingForEmptyResults() {
        var plan = AdaptiveSearchQueryVariants.plan("SuperGrok Heavy discount",
                List.of("SuperGrok Heavy discount"), options(true, 3, 3500));
        assertEquals(3, plan.queries().size());
        assertTrue(plan.variants().stream().anyMatch(q -> q.contains("targeted offer") && q.contains("retention")));
        assertTrue(plan.variants().stream().anyMatch(q -> q.contains("eligibility") && q.contains("renewal")));
        assertEquals(1, plan.diagnostics().explorationLaneCount());
        assertEquals(1, plan.diagnostics().verificationLaneCount());
        assertTrue(plan.triggerReason().contains("promotion"));
    }

    @Test
    void genericBaseCandidatesCannotCrowdOutPlanPlatformAndExistingAccountConstraints() {
        String query = "SuperGrok Heavy discount existing account Google Play not Stripe";
        var plan = AdaptiveSearchQueryVariants.plan(query,
                List.of("SuperGrok pricing", "Grok API docs", "Grok review"), options(true, 3, 3500));
        assertEquals(query, plan.queries().get(0));
        assertTrue(plan.queries().stream().allMatch(q -> q.startsWith(query)));
        assertTrue(plan.variants().stream().anyMatch(q -> q.contains("retention")));
        assertTrue(plan.queries().size() <= 3);
    }

    @Test
    void koreanAndTargetedLinkQueriesRemainDiscoveryCandidates() {
        for (String query : List.of("\uadf8\ub85d \uae30\uc874 \uacc4\uc815 \ud560\uc778",
                "https://grok.com/supergrok/targeted-offer/email-supergrok-67-off-3mo",
                "Grok Heavy promotion, not hacking or payment bypass")) {
            var plan = AdaptiveSearchQueryVariants.plan(query, List.of(query), options(true, 3, 3500));
            assertTrue(plan.variants().stream().anyMatch(q -> q.contains("retention")), query);
        }
    }

    @Test
    void ordinaryGrokCodingAndUnrelatedDiscountsDoNotActivatePromotionRouting() {
        for (String query : List.of("Grok API syntax", "grokking algorithms discount", "hotel discount")) {
            var plan = AdaptiveSearchQueryVariants.plan(query, List.of(query), options(true, 3, 3500));
            assertEquals(List.of(query), plan.queries());
        }
    }

    @Test
    void disabledAndInsufficientBudgetStillStopExpansion() {
        String query = "Grok Heavy discount";
        assertEquals(List.of(query), AdaptiveSearchQueryVariants.plan(query, List.of(query),
                options(false, 3, 3500)).queries());
        assertEquals(List.of(query), AdaptiveSearchQueryVariants.plan(query, List.of(query),
                options(true, 3, 900)).queries());
        assertEquals(List.of(query), AdaptiveSearchQueryVariants.plan(query, List.of(query),
                options(true, 1, 3500)).queries());
    }

    @Test
    void twoCallBudgetKeepsOriginalAndDiscoveryWithoutExceedingFloor() {
        String query = "Grok Heavy discount";
        var plan = AdaptiveSearchQueryVariants.plan(query, List.of(query), options(true, 3, 1400));
        assertEquals(2, plan.queries().size());
        assertEquals(query, plan.queries().get(0));
        assertTrue(plan.queries().get(1).contains("retention"));
        assertTrue(plan.perCallMs() * plan.queries().size() <= plan.budgetMs());
    }
}
