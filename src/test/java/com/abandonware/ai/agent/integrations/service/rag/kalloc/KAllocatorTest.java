package com.abandonware.ai.agent.integrations.service.rag.kalloc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KAllocatorTest {

    @Test
    void floorAndDefaultMaxSourceShareAreEnforced() {
        KAllocator.Settings settings = settings();
        settings.policy = "recency_first";
        settings.maxSourceShare = 0.65d;

        KAllocator.KPlan plan = new KAllocator(settings).decide(
                new KAllocator.Input("news", "recent release update", false));

        assertTrue(plan.webK >= settings.minPerSource);
        assertTrue(plan.vectorK >= settings.minPerSource);
        assertTrue(plan.kgK >= settings.minPerSource);
        assertTrue(plan.webK <= (int) Math.floor(settings.maxTotalK * settings.maxSourceShare));
    }

    @Test
    void hypernovaRelaxedShareCanKeepLargerWebBudget() {
        KAllocator.Settings strict = settings();
        strict.policy = "recency_first";
        strict.maxSourceShare = 0.50d;
        KAllocator.KPlan strictPlan = new KAllocator(strict).decide(
                new KAllocator.Input("news", "recent release update", false));

        KAllocator.Settings hypernova = settings();
        hypernova.policy = "recency_first";
        hypernova.maxSourceShare = 0.75d;
        KAllocator.KPlan hypernovaPlan = new KAllocator(hypernova).decide(
                new KAllocator.Input("news", "recent release update", false));

        assertTrue(strictPlan.webK <= 12);
        assertTrue(hypernovaPlan.webK > strictPlan.webK);
    }

    @Test
    void highRiskSourceLosesBudget() {
        KAllocator.Settings settings = settings();
        settings.policy = "recency_first";

        KAllocator.KPlan plan = new KAllocator(settings).decide(
                new KAllocator.Input("news", "recent release update", false,
                        0.90d, 0.10d, 0.10d,
                        0.10d, 0.40d, 0.20d));

        assertTrue(plan.webK < 15);
    }

    @Test
    void lowRiskTailOpportunityReceivesSmallIncrease() {
        KAllocator.Settings settings = settings();

        KAllocator.KPlan plan = new KAllocator(settings).decide(
                new KAllocator.Input("research", "deep archive signal", false,
                        0.10d, 0.10d, 0.10d,
                        0.80d, 0.10d, 0.10d));

        assertTrue(plan.webK > 8);
    }

    @Test
    void spreadProbeWideBudgetHonorsTotalAndShareCap() {
        KAllocator.Settings settings = settings();
        settings.policy = "recency_first";
        settings.maxTotalK = 64;
        settings.minPerSource = 4;
        settings.kStep = 8;
        settings.maxSourceShare = 0.60d;

        KAllocator.KPlan plan = new KAllocator(settings).decide(
                new KAllocator.Input("news", "recent release update", false));

        assertTrue(plan.webK + plan.vectorK + plan.kgK == 64);
        assertTrue(plan.webK >= settings.minPerSource);
        assertTrue(plan.vectorK >= settings.minPerSource);
        assertTrue(plan.kgK >= settings.minPerSource);
        assertTrue(plan.webK <= (int) Math.floor(settings.maxTotalK * settings.maxSourceShare));
        assertTrue(plan.vectorK <= (int) Math.floor(settings.maxTotalK * settings.maxSourceShare));
        assertTrue(plan.kgK <= (int) Math.floor(settings.maxTotalK * settings.maxSourceShare));
    }

    private static KAllocator.Settings settings() {
        KAllocator.Settings settings = new KAllocator.Settings();
        settings.enabled = true;
        settings.maxTotalK = 24;
        settings.minPerSource = 2;
        settings.kStep = 4;
        settings.maxSourceShare = 0.65d;
        return settings;
    }
}
