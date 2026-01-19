package com.abandonware.ai.service.ocr;

import org.springframework.stereotype.Service;
import java.util.List;

/** Null-object OCR (always returns empty). */
@Service
public class OcrNullService implements OcrService {
    @Override public List<OcrChunk> extract(byte[] image) { return java.util.List.of(); }
}