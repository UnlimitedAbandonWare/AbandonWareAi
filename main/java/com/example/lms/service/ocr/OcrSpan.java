package com.example.lms.service.ocr;


public record OcrSpan(String text, Rect bbox, float confidence, int page) {}