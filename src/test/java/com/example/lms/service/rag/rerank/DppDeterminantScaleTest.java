package com.example.lms.service.rag.rerank;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DppDeterminantScaleTest {
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0d, 0.7d, 1.0d})
    void disjointLowScoreDocumentsKeepTheHighestRelevanceSubset(double lambda) {
        List<Candidate> input = candidates(5, 0.01d, 0.005d);
        assertHighestRelevance(input, 4, lambda);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0d, 0.7d, 1.0d})
    void disjointOrdinaryScoreDocumentsKeepTheHighestRelevanceAtDefaultK(double lambda) {
        List<Candidate> input = candidates(10, 0.1d, 0.01d);
        assertHighestRelevance(input, 8, lambda);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0d, 0.7d, 1.0d})
    void largerScoreScaleKeepsTheSameRelevanceOrder(double lambda) {
        List<Candidate> input = candidates(5, 0.2d, 0.1d);
        assertHighestRelevance(input, 4, lambda);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0d, 0.7d, 1.0d})
    void genuineEqualScoreTiesStillUseStableKeys(double lambda) {
        List<Candidate> input = new ArrayList<>(candidates(5, 0.15d, 0.0d));
        java.util.Collections.reverse(input);
        List<Candidate> expected = input.stream().sorted(Comparator.comparing(Candidate::id)).limit(4).toList();

        assertEquals(expected, rerank(input, 4, lambda));
    }

    @Test
    void selectionPreservesInputAndSelectedObjectIdentity() {
        List<Candidate> input = new ArrayList<>(candidates(5, 0.01d, 0.005d));
        List<Candidate> before = List.copyOf(input);

        List<Candidate> output = rerank(input, 4, 0.7d);

        assertEquals(before, input);
        assertNotSame(input, output);
        assertEquals(4, output.size());
        assertTrue(output.stream().allMatch(selected -> input.stream().anyMatch(original -> original == selected)));
    }

    private static void assertHighestRelevance(List<Candidate> input, int k, double lambda) {
        // Each text has a different sole trigram, so every off-diagonal affinity is zero.
        // The determinant is a product of positive diagonal qualities; its maximum
        // therefore selects descending relevance independently of input order or lambda.
        List<Candidate> expected = input.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .limit(k).toList();

        List<Candidate> actual = rerank(input, k, lambda);

        assertEquals(expected, actual);
        assertEquals(k, TraceStore.get("dpp.rerank.outputCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("hypernova.dppApplied"));
    }

    private static List<Candidate> rerank(List<Candidate> input, int k, double lambda) {
        return new DppDiversityReranker().rerank(new DppDiversityReranker.Config(lambda, k),
                input, "", k, Candidate::text, Candidate::score, Candidate::id);
    }

    private static List<Candidate> candidates(int count, double start, double step) {
        return IntStream.range(0, count).mapToObj(index -> {
            String symbol = Character.toString((char) ('a' + index));
            return new Candidate(symbol, symbol.repeat(8), start + step * index);
        }).toList();
    }

    private record Candidate(String id, String text, double score) { }
}
