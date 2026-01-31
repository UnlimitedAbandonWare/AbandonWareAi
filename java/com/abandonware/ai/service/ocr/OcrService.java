package com.abandonware.ai.service.ocr;

import java.util.List;

/** Unified OCR service contract (lightweight). */
public interface OcrService {
    /** Extract text spans from an image/PDF page represented as raw bytes. */
    List<OcrChunk> extract(byte[] image);
}