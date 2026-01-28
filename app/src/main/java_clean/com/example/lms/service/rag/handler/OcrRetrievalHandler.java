package com.example.lms.service.rag.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stub OCR retrieval handler that converts attachment text into standard context items.
 * This file is safe to compile without external OCR dependencies.
 */
public class OcrRetrievalHandler {

    public static final class ContextItem {
        public final String id;
        public final String title;
        public final String snippet;
        public final String source;
        public final double score;
        public final int rank;
        public ContextItem(String id, String title, String snippet, String source, double score, int rank){
            this.id = id; this.title = title; this.snippet = snippet; this.source = source; this.score = score; this.rank = rank;
        }
    }

    public List<ContextItem> retrieve(byte[] attachmentBytes, String attachmentName){
        if (attachmentBytes == null || attachmentBytes.length == 0) return List.of();
        // No real OCR here - emit a single placeholder item
        List<ContextItem> out = new ArrayList<>();
        out.add(new ContextItem(UUID.randomUUID().toString(), attachmentName, "[ocr text omitted in clean build]", "ocr", 0.5, 0));
        return out;
    }
}