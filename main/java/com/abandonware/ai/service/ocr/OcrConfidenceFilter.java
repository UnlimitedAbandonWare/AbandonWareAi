package com.abandonware.ai.service.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Confidence-based OCR span filter.
 * If the current span type does not expose a typed confidence contract, the span is kept fail-soft.
 */
@Component
public class OcrConfidenceFilter {

    @Value("${ocr.min-confidence:0.6}")
    private double min;

    public <T> List<T> filter(List<T> spans) {
        if (spans == null || spans.isEmpty()) return spans;
        List<T> out = new ArrayList<>(spans.size());
        for (T s : spans) {
            if (s == null) continue;
            Double c = readConfidence(s);
            if (c == null || c >= min) out.add(s);
        }
        return out;
    }

    private Double readConfidence(Object s) {
        if (s instanceof com.example.lms.service.ocr.OcrSpan span) {
            return (double) span.confidence();
        }
        return null;
    }
}
