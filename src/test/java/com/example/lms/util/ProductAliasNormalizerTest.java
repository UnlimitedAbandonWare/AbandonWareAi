package com.example.lms.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProductAliasNormalizerTest {

    @Test
    void normalizesAsciiAliasBeforeHangulParticle() {
        assertEquals(
                "Core i9을 추천해줘",
                ProductAliasNormalizer.normalize("i9을 추천해줘"));
    }

    @Test
    void preservesUnicodeLeftAndAsciiRightIdentifierBoundaries() {
        assertAll(
                () -> assertEquals("i90", ProductAliasNormalizer.normalize("i90")),
                () -> assertEquals("i9a", ProductAliasNormalizer.normalize("i9a")),
                () -> assertEquals("i9_", ProductAliasNormalizer.normalize("i9_")),
                () -> assertEquals("ki9", ProductAliasNormalizer.normalize("ki9")),
                () -> assertEquals("0i9", ProductAliasNormalizer.normalize("0i9")),
                () -> assertEquals("_i9", ProductAliasNormalizer.normalize("_i9")),
                () -> assertEquals("가i9", ProductAliasNormalizer.normalize("가i9")));
    }

    @Test
    void preservesExistingStandaloneCaseAndPunctuationBehavior() {
        assertAll(
                () -> assertEquals("Core i9", ProductAliasNormalizer.normalize("i9")),
                () -> assertEquals("Core i9", ProductAliasNormalizer.normalize("I9")),
                () -> assertEquals("(Core i9)", ProductAliasNormalizer.normalize("(i9)")));
    }
}
