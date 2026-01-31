package com.abandonware.ai.service.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OcrServiceTest {

    @Test
    void fallback() {
        OcrNullService s = new OcrNullService();
        assertTrue(s.extractText(new byte[0], "kor").isEmpty());
    }
}