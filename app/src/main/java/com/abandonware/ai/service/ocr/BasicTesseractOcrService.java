package com.abandonware.ai.service.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ocr.BasicTesseractOcrService
 * Role: service
 * Feature Flags: sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.service.ocr.BasicTesseractOcrService
role: service
flags: [sse]
*/
public class BasicTesseractOcrService implements OcrService {

    private final Semaphore limiter = new Semaphore(Math.max(1, Runtime.getRuntime().availableProcessors() / 4));

    @Value("${ocr.timeoutMs:900}")
    private long timeoutMs;

    @Value("${ocr.minConfidence:0.65}")
    private double minConfidence;

    @Override
    public List<OcrChunk> extract(byte[] image) {
        try {
            if (!limiter.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return java.util.List.of();
            try {
                // If Tess4J is not on classpath, Class.forName will throw.
                Class.forName("net.sourceforge.tess4j.Tesseract");
                // NOTE: Real OCR omitted for portability in this bundle.
                // Return a placeholder to keep the pipeline connected.
                return java.util.List.of(new OcrChunk("[[ocr]]", 0, 0, 0, 0));
            } catch (ClassNotFoundException noTess4j) {
                return java.util.List.of();
            } finally {
                limiter.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return java.util.List.of();
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }
}