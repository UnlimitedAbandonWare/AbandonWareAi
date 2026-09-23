package com.example.lms.search.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StochasticExpanderTest {

    @Test
    void ordinaryKoreanExpansionKeepsLegacyLocalizedSuffixVocabulary() {
        List<String> expanded = StochasticExpander.expand(
                "한국어 창의 검색", SearchPolicyMode.RECALL, 10);
        List<String> allowedSuffixes = List.of(
                "정리", "요약", "가이드", "사용법", "tutorial", "examples",
                "docs", "reference", "release notes", "changelog");

        assertTrue(expanded.stream().allMatch(value ->
                value.equals("\"한국어 창의 검색\"")
                        || allowedSuffixes.stream().anyMatch(suffix -> value.endsWith(" " + suffix))));
    }

    @Test
    void asciiQueriesDoNotEmitMojibakeSuffixes() {
        List<String> queries = List.of(
                "spring boot virtual threads",
                "postgres row level security",
                "ollama local model startup",
                "java structured concurrency",
                "supabase realtime vectors");

        for (String query : queries) {
            for (SearchPolicyMode mode : List.of(
                    SearchPolicyMode.PRECISION,
                    SearchPolicyMode.BALANCED,
                    SearchPolicyMode.RECALL,
                    SearchPolicyMode.DISAMBIGUATE)) {
                List<String> expansions = StochasticExpander.expand(query, mode, 20);

                assertFalse(expansions.isEmpty(), "expected expansions for " + mode);
                for (String expansion : expansions) {
                    assertFalse(containsNonAscii(expansion),
                            "ASCII query expansion must stay ASCII-searchable: " + expansion);
                }
            }
        }
    }

    @Test
    void creativeSuffixOrderComesOnlyFromRequestedOptionsHash() {
        String query = "modular garden pavilion";

        List<String> first = StochasticExpander.expandCreative(
                query, SearchPolicyMode.BALANCED, 6, "hash:000000000001");
        List<String> repeated = StochasticExpander.expandCreative(
                query, SearchPolicyMode.BALANCED, 6, "hash:000000000001");
        List<String> second = StochasticExpander.expandCreative(
                query, SearchPolicyMode.BALANCED, 6, "hash:ffffffffffff");

        assertEquals(first, repeated);
        assertNotEquals(first, second);
        assertEquals(6, first.size());
        assertTrue(first.stream().allMatch(value -> value.startsWith(query + " ")));
        assertFalse(first.contains(query));
        assertFalse(first.stream().anyMatch(value -> value.contains("official primary source")));
    }

    private static boolean containsNonAscii(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 32 || ch > 126) {
                return true;
            }
        }
        return false;
    }
}
