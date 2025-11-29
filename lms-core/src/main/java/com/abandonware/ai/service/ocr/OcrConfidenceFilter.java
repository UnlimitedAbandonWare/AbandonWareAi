package com.abandonware.ai.service.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ocr.OcrConfidenceFilter
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.service.ocr.OcrConfidenceFilter
role: config
*/
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
        try {
            Method m = s.getClass().getMethod("confidence");
            Object v = m.invoke(s);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignore) {}
        try {
            Method m = s.getClass().getMethod("getConfidence");
            Object v = m.invoke(s);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignore) {}
        try {
            Field f = s.getClass().getDeclaredField("confidence");
            f.setAccessible(true);
            Object v = f.get(s);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignore) {}
        return null;
    }
}