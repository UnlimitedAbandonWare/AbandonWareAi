package com.example.lms.service.ocr;

import com.abandonware.ai.service.ocr.OcrService;
import com.abandonware.ai.service.ocr.OcrChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Example-compatible OCR that delegates to the unified Abandonware OCR when available. */
@Service
public class BasicTesseractOcrService implements com.example.lms.service.ocr.OcrService {

    @Autowired(required = false)
    private OcrService delegate; // Abandonware OCR

    @Override
    public List<OcrSpan> extract(InputStream pdfOrImage, OcrOptions opts) {
        try {
            byte[] bytes = pdfOrImage.readAllBytes();
            if (delegate != null) {
                List<OcrSpan> spans = new ArrayList<>();
                for (OcrChunk c : delegate.extract(bytes)) {
                    spans.add(new OcrSpan(c.text, new Rect(c.x, c.y, c.w, c.h), 0.99f, 0));
                }
                return spans;
            }
            // Fallback: return empty (graceful downgrade if no engine on classpath)
            return java.util.List.of();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}