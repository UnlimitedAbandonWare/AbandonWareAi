package com.abandonware.patch.ocr;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class OcrContextAdapter {

    public static class OcrSpan { public String text; public double conf; public OcrSpan(String t,double c){text=t;conf=c;} }
    public static class OcrChunk { public List<OcrSpan> spans = new ArrayList<>(); }

    public static List<Map<String,Object>> toContext(List<OcrChunk> chunks, String sourceName) {
        List<Map<String,Object>> out = new ArrayList<>();
        AtomicInteger rank = new AtomicInteger(1);
        for (OcrChunk c : chunks) {
            StringBuilder sb = new StringBuilder();
            double score = 0.0;
            for (OcrSpan s : c.spans) { sb.append(s.text).append(" "); score = Math.max(score, s.conf); }
            Map<String,Object> doc = new LinkedHashMap<>();
            doc.put("id", UUID.randomUUID().toString());
            doc.put("title", "OCR");
            doc.put("snippet", sb.toString().trim());
            doc.put("source", sourceName);
            doc.put("score", score);
            doc.put("rank", rank.getAndIncrement());
            out.add(doc);
        }
        return out;
    }
}