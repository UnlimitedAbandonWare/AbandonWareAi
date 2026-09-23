package com.example.lms.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTypeHeuristicsUnicodeBoundaryTest {

    @Test
    void unicodePunctuationUsesTheSameEntityTokenLimitAsAsciiPunctuation() {
        String fullwidthNine = "Aa\uFF0CBb\uFF0CCc\uFF0CDd\uFF0CEe\uFF0CFf\uFF0CGg\uFF0CHh\uFF0CIi";
        String asciiNine = "Aa,Bb,Cc,Dd,Ee,Ff,Gg,Hh,Ii";
        String ideographicNine = "Aa\u3001Bb\u3001Cc\u3001Dd\u3001Ee\u3001Ff\u3001Gg\u3001Hh\u3001Ii";
        String symbolNine = "Aa+Bb+Cc+Dd+Ee+Ff+Gg+Hh+Ii";
        String ideographicSpaceNine = "Aa\u3000Bb\u3000Cc\u3000Dd\u3000Ee\u3000Ff\u3000Gg\u3000Hh\u3000Ii";
        String fullwidthEight = "Aa\uFF0CBb\uFF0CCc\uFF0CDd\uFF0CEe\uFF0CFf\uFF0CGg\uFF0CHh";

        assertAll(
                () -> assertFalse(QueryTypeHeuristics.looksLikeEntityQuery(fullwidthNine)),
                () -> assertFalse(QueryTypeHeuristics.looksLikeEntityQuery(asciiNine)),
                () -> assertFalse(QueryTypeHeuristics.looksLikeEntityQuery(ideographicNine)),
                () -> assertFalse(QueryTypeHeuristics.looksLikeEntityQuery(symbolNine)),
                () -> assertFalse(QueryTypeHeuristics.looksLikeEntityQuery(ideographicSpaceNine)),
                () -> assertTrue(QueryTypeHeuristics.looksLikeEntityQuery(fullwidthEight)),
                () -> assertTrue(QueryTypeHeuristics.looksLikeEntityQuery("Samsung")),
                () -> assertTrue(QueryTypeHeuristics.isDefinitional("RAG\uB780?")));
    }
}
