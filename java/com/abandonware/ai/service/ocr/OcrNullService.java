package com.abandonware.ai.service.ocr;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class OcrNullService implements OcrService {
    @Override
    public java.util.List<com.abandonware.ai.service.ocr.OcrChunk> extract(byte[] image) {
        return java.util.Collections.emptyList();
    }
}
