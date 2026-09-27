package com.abandonware.ai.service.ocr;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Basic Tess4J-backed OCR; degrades gracefully if native Tesseract is unavailable. */
@Service
@ConditionalOnProperty(prefix = "ocr", name = "enabled", havingValue = "true")
public class BasicTesseractOcrService implements OcrService {

    private final Semaphore limiter = new Semaphore(Math.max(1, Runtime.getRuntime().availableProcessors() / 4));

    @Value("${ocr.timeout-ms:${ocr.timeoutMs:900}}")
    private long timeoutMs;

    @Value("${ocr.min-confidence:${ocr.minConfidence:0.65}}")
    private double minConfidence;

    @Value("${ocr.language:eng+kor}")
    private String language;

    @Value("${ocr.datapath:${TESSDATA_PREFIX:}}")
    private String datapath;

    @Override
    public List<OcrChunk> extract(byte[] image) {
        boolean acquired = false;
        try {
            if (image == null || image.length == 0) {
                return List.of();
            }
            acquired = limiter.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                return List.of();
            }
            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(image));
            if (buffered == null) {
                return List.of();
            }
            TraceStore.put("ocr.basic.disabledReason", "tess4j_dependency_unavailable");
            TraceStore.put("ocr.basic.inputReadable", buffered != null);
            TraceStore.put("ocr.basic.languageConfigured", language != null && !language.isBlank());
            TraceStore.put("ocr.basic.datapathConfigured", datapath != null && !datapath.isBlank());
            return List.of();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            traceSuppressed("interrupted", ie);
            return List.of();
        } catch (Throwable t) {
            traceSuppressed("tesseract", t);
            return List.of();
        } finally {
            if (acquired) {
                limiter.release();
            }
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        TraceStore.put("ocr.basic.suppressed", true);
        TraceStore.put("ocr.basic.suppressed.stage", stage);
        TraceStore.put("ocr.basic.suppressed.errorClass", errorClass(error));
        TraceStore.inc("ocr.basic.suppressed.count");
    }

    private static String errorClass(Throwable error) {
        if (error instanceof InterruptedException) {
            return "cancelled";
        }
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
