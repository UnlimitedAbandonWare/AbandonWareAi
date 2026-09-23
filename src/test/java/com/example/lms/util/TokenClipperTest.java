package com.example.lms.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenClipperTest {

    @Test
    void leadingWhitespaceDoesNotProduceEmptyFirstToken() {
        assertThat(TokenClipper.clip("   alpha beta gamma", 2)).isEqualTo("alpha beta");
    }
}
