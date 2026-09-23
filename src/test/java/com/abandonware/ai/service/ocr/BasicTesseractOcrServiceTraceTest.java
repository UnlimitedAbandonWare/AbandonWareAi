package com.abandonware.ai.service.ocr;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BasicTesseractOcrServiceTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        Thread.interrupted();
    }

    @Test
    void interruptedAcquireRecordsTraceAndPreservesInterrupt() {
        BasicTesseractOcrService service = new BasicTesseractOcrService();
        ReflectionTestUtils.setField(service, "timeoutMs", 100L);

        Thread.currentThread().interrupt();

        assertTrue(service.extract(new byte[]{1}).isEmpty());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(Boolean.TRUE, TraceStore.get("ocr.basic.suppressed"));
        assertEquals("interrupted", TraceStore.get("ocr.basic.suppressed.stage"));
        assertEquals("cancelled", TraceStore.get("ocr.basic.suppressed.errorClass"));
    }

    @Test
    void nativeFailureCatchPathKeepsBreadcrumbHook() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/abandonware/ai/service/ocr/BasicTesseractOcrService.java"));

        assertTrue(source.contains("traceSuppressed(\"interrupted\", ie);"));
        assertTrue(source.contains("traceSuppressed(\"tesseract\", t);"));
    }
}
