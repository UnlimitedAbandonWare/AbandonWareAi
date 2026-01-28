package com.example.lms.scoring;

import org.junit.jupiter.api.Test;
import java.util.List;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ContextualScorer verifying that the path-alignment multiplier
 * influences the overall score appropriately. The underlying PathAlignedScorer
 * implementation is deterministic so we can assert relative ordering.
 */
public class ContextualScorerTest {

    private final PathAlignedScorer pathAlignedScorer = new PathAlignedScorer();
    private final ContextualScorer scorer = new ContextualScorer(pathAlignedScorer);

    @Test
    void alignedPathBoostsScore() {
        String q = "What is apple?";
        String ctx = "apple is fruit\napple grows on trees";
        String ans = "Apple is a fruit.";
        List<String> past = List.of("root", "fruit");
        List<String> predicted = List.of("fruit");
        var base = scorer.score(q, ctx, ans);
        var aligned = scorer.score(q, ctx, ans, past, predicted);
        assertTrue(aligned.overall() > base.overall(), "aligned path should boost score");
    }

    @Test
    void misalignedPathPenalisesScore() {
        String q = "What is apple?";
        String ctx = "apple is fruit\napple grows on trees";
        String ans = "Apple is a fruit.";
        List<String> past = List.of("root", "other");
        List<String> predicted = List.of("fruit");
        var base = scorer.score(q, ctx, ans);
        var misaligned = scorer.score(q, ctx, ans, past, predicted);
        assertTrue(misaligned.overall() < base.overall(), "misaligned path should penalise score");
    }
}