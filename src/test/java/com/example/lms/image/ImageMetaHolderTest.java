package com.example.lms.image;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageMetaHolderTest {

    @AfterEach
    void clear() {
        ImageMetaHolder.clear();
    }

    @Test
    void blankKeysAreIgnored() {
        ImageMetaHolder.put("  ", "value");

        assertThat(ImageMetaHolder.get("  ")).isNull();
        assertThat(ImageMetaHolder.getOrDefault("  ", "fallback")).isEqualTo("fallback");
    }
}
