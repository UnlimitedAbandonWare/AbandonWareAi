package com.abandonware.ai.service.ocr;

import java.util.List;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ocr.OcrService
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.ocr.OcrService
role: config
*/
public interface OcrService {
    /** Extract text spans from an image/PDF page represented as raw bytes. */
    List<OcrChunk> extract(byte[] image);
}