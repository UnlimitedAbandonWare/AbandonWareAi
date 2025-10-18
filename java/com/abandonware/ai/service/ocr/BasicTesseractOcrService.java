package com.abandonware.ai.service.ocr;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import java.util.Collections;
import java.util.List;

/**
 * Fail-soft OCR via reflection. Returns empty if Tess4J not available.
 */
@Service
public class BasicTesseractOcrService implements OcrService {
private final Semaphore limiter;
private final long timeoutMs;
private final double minConfidence;
private final int maxChunkChars;

public BasicTesseractOcrService(
    @Value("${ocr.max-concurrency:2}") int maxConcurrency,
    @Value("${ocr.timeout-ms:3000}") long timeoutMs,
    @Value("${ocr.min-confidence:0.45}") double minConfidence,
    @Value("${ocr.max-chunk-chars:1200}") int maxChunkChars
) {
    this.limiter = new Semaphore(Math.max(1, maxConcurrency));
    this.timeoutMs = timeoutMs;
    this.minConfidence = minConfidence;
    this.maxChunkChars = maxChunkChars;
}

    @Override
    public List<OcrChunk> extract(byte[] image) {
        try {
            if (!limiter.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return java.util.Collections.emptyList();
            Class.forName("net.sourceforge.tess4j.Tesseract");
            // real OCR omitted; minConfidence="+minConfidence+"
            return Collections.emptyList();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }
}
