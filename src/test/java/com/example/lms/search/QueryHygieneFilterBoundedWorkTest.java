package com.example.lms.search;

import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryHygieneFilterBoundedWorkTest {
    @Test
    void stopsReadingCandidatesWhenRequestedUniqueResultsAreReady() {
        AtomicInteger reads = new AtomicInteger();
        List<String> candidates = new AbstractList<>() {
            @Override public String get(int index) {
                reads.incrementAndGet();
                return "candidate" + index;
            }
            @Override public int size() { return 10_000; }
        };

        assertEquals(List.of("candidate0", "candidate1"),
                QueryHygieneFilter.sanitize(candidates, 2, 0.8d));
        assertEquals(2, reads.get(), "unused candidates must not be normalized or copied");
    }

    @Test
    void skipsNullBlankAndDuplicateCandidatesBeforeApplyingOutputLimit() {
        assertEquals(List.of("IBM earnings", "weather tomorrow"),
                QueryHygieneFilter.sanitize(Arrays.asList(null, " ", " IBM earnings ",
                        "ibm earnings", "weather tomorrow", "unused tail"), 2, 0.8d));
    }

    @Test
    void preservesTruncationAndExistingMinimumOneContract() {
        assertEquals(List.of("a".repeat(128)),
                QueryHygieneFilter.sanitize(List.of("a".repeat(200), "second"), 0, 0.8d));
        assertEquals(List.of(), QueryHygieneFilter.sanitize(null, 2, 0.8d));
    }
}
