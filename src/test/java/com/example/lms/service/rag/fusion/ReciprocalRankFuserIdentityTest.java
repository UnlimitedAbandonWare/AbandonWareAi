package com.example.lms.service.rag.fusion;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReciprocalRankFuserIdentityTest {
    @Test
    void distinctTextsWithSameJavaHashBothSurviveFusion() {
        Content first = Content.from(TextSegment.from("Aa"));
        Content second = Content.from(TextSegment.from("BB"));
        var output = new ReciprocalRankFuser().fuse(List.of(List.of(first), List.of(second)), 2);
        assertEquals(2, output.size(), "distinct texts must not share one RRF identity");
        assertTrue(output.contains(first));
        assertTrue(output.contains(second));
    }

    @Test
    void existingWhitespaceDeduplicationPreservesFirstSeenMetadata() {
        Content first = Content.from(TextSegment.from("same   text", Metadata.from("source", "first")));
        Content later = Content.from(TextSegment.from("same text", Metadata.from("source", "later")));
        var output = new ReciprocalRankFuser().fuse(List.of(List.of(first), List.of(later)), 2);
        assertEquals(1, output.size());
        assertSame(first, output.get(0));
    }

    @Test
    void repeatedDocumentAccumulatesRanksAndRespectsTopK() {
        Content first = Content.from(TextSegment.from("first"));
        Content shared = Content.from(TextSegment.from("shared"));
        var output = new ReciprocalRankFuser().fuse(
                List.of(List.of(first, shared), List.of(shared)), 1);
        assertEquals(List.of(shared), output);
    }

    @Test
    void nullAndEmptySourcesStayEmpty() {
        var fuser = new ReciprocalRankFuser();
        assertTrue(fuser.fuse(null, 2).isEmpty());
        assertTrue(fuser.fuse(List.of(), 2).isEmpty());
    }

    @Test
    void maximumConfiguredKPreservesPositiveAccumulatedRankScores() {
        Content first = Content.from(TextSegment.from("first"));
        Content shared = Content.from(TextSegment.from("shared"));
        var output = new ReciprocalRankFuser(Integer.MAX_VALUE).fuse(
                List.of(List.of(first, shared), List.of(shared)), 1);
        assertEquals(List.of(shared), output, "integer denominator overflow must not invert RRF evidence");
    }
}
