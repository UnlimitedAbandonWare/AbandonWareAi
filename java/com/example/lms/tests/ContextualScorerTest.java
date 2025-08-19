package com.example.lms.tests;

import com.example.lms.scoring.ContextualScorer;
import com.example.lms.scoring.PathAlignedScorer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

        assertTrue(aligned.overall() > base.overall());
    }

    @Test
    void misalignedPathPenalizesScore() {
        String q = "What is apple?";
        String ctx = "apple is fruit\napple grows on trees";
        String ans = "Apple is a fruit.";
        List<String> past = List.of("root", "other");
        List<String> predicted = List.of("fruit");

        var base = scorer.score(q, ctx, ans);
        var misaligned = scorer.score(q, ctx, ans, past, predicted);

        assertTrue(misaligned.overall() < base.overall());
    }
}
