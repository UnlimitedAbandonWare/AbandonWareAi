package com.example.lms.search;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic unit test for the {@link NoiseClipper} class ensuring that
 * polite suffixes are stripped from the end of a query.  More
 * comprehensive tests should cover leading labels, quote removal and
 * stopword pruning but are omitted here for brevity.
 */
public class NoiseClipperTest {

    @Test
    void clip_should_remove_trailing_politeness() {
        NoiseClipper clipper = new NoiseClipper();
        String input  = "자바 성능 이슈 알려줘요";
        String output = clipper.clip(input);
        assertEquals("자바 성능 이슈", output);
    }
}