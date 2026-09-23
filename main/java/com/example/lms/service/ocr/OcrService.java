package com.example.lms.service.ocr;

import java.io.InputStream;
import java.util.List;

public interface OcrService {
    List<OcrSpan> extract(InputStream pdfOrImage, OcrOptions opts);
    record OcrOptions(String lang, int maxPages, float minConfidence) {
        public static OcrOptions defaults() { return new OcrOptions("eng+kor", 50, 0.65f); }
    }
}