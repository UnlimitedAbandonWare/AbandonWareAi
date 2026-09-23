package com.example.lms.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextSimilarityUtilTest {

    @Test
    void leadingWhitespaceDoesNotChangeTokenSimilarity() {
        TextSimilarityUtil util = new TextSimilarityUtil();

        assertThat(util.calculateSimilarity("   alpha beta", "alpha beta")).isEqualTo(1.0);
    }
}
