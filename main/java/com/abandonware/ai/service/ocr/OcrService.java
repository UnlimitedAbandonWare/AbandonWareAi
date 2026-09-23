package com.abandonware.ai.service.ocr;

import java.util.List;

public interface OcrService {
    List<OcrChunk> extract(byte[] image);
}
