package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DefaultLightWeightRankerContractTest {
    private final DefaultLightWeightRanker ranker = new DefaultLightWeightRanker();

    @Test
    void tokenlessCandidateCannotOutrankAnExactMatch() {
        Content punctuation = Content.from("!!!");
        Content relevant = Content.from("relevant");
        assertEquals(List.of(relevant), ranker.rank(List.of(punctuation, relevant), "relevant", 1));
    }

    @Test
    void tokenlessCandidatesDoNotFillLexicalResultsWhenAMatchExists() {
        Content punctuation = Content.from("!!!");
        Content oneLetter = Content.from("x");
        Content relevant = Content.from("relevant");
        assertEquals(List.of(relevant),
                ranker.rank(List.of(punctuation, relevant, oneLetter), "relevant", 10));
    }

    @Test
    void emptyQueryRetainsInputOrderAndLimit() {
        List<Content> candidates = List.of(Content.from("!!!"), Content.from("second"), Content.from("third"));
        assertEquals(candidates.subList(0, 2), ranker.rank(candidates, "", 2));
        assertEquals(candidates.subList(0, 1), ranker.rank(candidates, null, 0));
    }

    @Test
    void noMatchRetainsTheExistingCandidateFallback() {
        List<Content> candidates = List.of(Content.from("!!!"), Content.from("unrelated"));
        assertEquals(candidates, ranker.rank(candidates, "relevant", 10));
    }

    @Test
    void matchingTiesStayStableAndEmptyCandidatesAreSupported() {
        List<Content> candidates = List.of(Content.from("RELEVANT first"), Content.from("relevant second"));
        assertEquals(candidates, ranker.rank(candidates, "relevant", 10));
        assertEquals(List.of(), ranker.rank(List.of(), "relevant", 1));
        assertEquals(List.of(), ranker.rank(null, "relevant", 1));
    }
}
