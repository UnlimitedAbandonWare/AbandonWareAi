package com.abandonware.ai.service.ocr;

/** Minimal OCR chunk with a single text + bbox. */
public class OcrChunk {
    public final String text;
    public final int x, y, w, h;
    public OcrChunk(String text, int x, int y, int w, int h) {
        this.text = text; this.x = x; this.y = y; this.w = w; this.h = h;
    }
}