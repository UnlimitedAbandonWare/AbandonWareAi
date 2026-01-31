package com.example.lms.search;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;



/**
 * Skeleton tests for the {@link SmartQueryPlanner}.  These tests are
 * intentionally disabled because they rely on Spring context and
 * extensive mock setup.  Developers should implement proper unit
 * tests covering AUTO mode gating logic, domain cap limits and
 * subject anchoring.  See the patch notes for guidance.
 */
public class SmartQueryPlannerTest {

    @Test
    @Disabled("Pending implementation of HybridKeywordExtractor mocks")
    void autoMode_shortQuery_goes_RULE() {
        // TODO: implement test that verifies short/simple queries trigger RULE mode
    }

    @Test
    @Disabled("Pending implementation of HybridKeywordExtractor mocks")
    void autoMode_complexQuery_goes_HYBRID() {
        // TODO: implement test that verifies complex queries trigger HYBRID or LLM mode
    }
}