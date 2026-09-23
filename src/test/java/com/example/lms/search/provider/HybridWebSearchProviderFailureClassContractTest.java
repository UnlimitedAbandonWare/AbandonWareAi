package com.example.lms.search.provider;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridWebSearchProviderFailureClassContractTest {

    @Test
    void nonBlankProviderCandidatesSurviveTheCurrentMergeBoundary() {
        List<String> merged = ReflectionTestUtils.invokeMethod(
                null,
                HybridWebSearchProvider.class,
                "mergeAndLimit",
                List.of("brave-one", "brave-two"),
                List.of("naver-one"),
                4);

        assertEquals(List.of("brave-one", "brave-two", "naver-one"), merged);
    }

    @Test
    void blankProviderValuesAreFilterZeroEvidenceNotMergeStarvation() {
        List<String> merged = ReflectionTestUtils.invokeMethod(
                null,
                HybridWebSearchProvider.class,
                "mergeAndLimit",
                List.of(" ", ""),
                List.of(),
                2);

        assertTrue(merged.isEmpty());
    }
}
