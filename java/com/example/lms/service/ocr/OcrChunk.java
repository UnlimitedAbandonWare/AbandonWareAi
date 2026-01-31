package com.example.lms.service.ocr;

import java.util.List;
import java.util.Map;



public record OcrChunk(
    String id, String sourceUri, int page, String content,
    List<Rect> boxes, List<Float> confs, Map<String,Object> meta
) {}