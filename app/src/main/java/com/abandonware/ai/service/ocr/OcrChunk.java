package com.abandonware.ai.service.ocr;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ocr.OcrChunk
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.ocr.OcrChunk
role: config
*/
public class OcrChunk {
    public final String text;
    public final int x, y, w, h;
    public OcrChunk(String text, int x, int y, int w, int h) {
        this.text = text; this.x = x; this.y = y; this.w = w; this.h = h;
    }
}