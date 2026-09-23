package com.abandonware.ai.service.ocr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.List;

/** Null-object OCR (always returns empty). */
@Service
@ConditionalOnProperty(prefix = "ocr", name = "enabled", havingValue = "false", matchIfMissing = true)
public class OcrNullService implements OcrService {
    @Override public List<OcrChunk> extract(byte[] image) { return java.util.List.of(); }
}
