package com.example.lms.image;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageIntentDetectorTest {

    @Test
    void slashCommandsRequireCommandBoundary() {
        ImageIntentDetector detector = new ImageIntentDetector();

        assertThat(detector.isImageIntent("/image a small icon")).isTrue();
        assertThat(detector.isImageIntent("/img a small icon")).isTrue();
        assertThat(detector.isImageIntent("/imgration status")).isFalse();
        assertThat(detector.isImageIntent("/imageboard discussion")).isFalse();
    }
}
