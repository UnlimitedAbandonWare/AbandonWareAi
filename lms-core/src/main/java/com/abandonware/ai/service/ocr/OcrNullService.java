package com.abandonware.ai.service.ocr;

import org.springframework.stereotype.Service;
import java.util.List;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ocr.OcrNullService
 * Role: service
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.ocr.OcrNullService
role: service
*/
public class OcrNullService implements OcrService {
    @Override public List<OcrChunk> extract(byte[] image) { return java.util.List.of(); }
}