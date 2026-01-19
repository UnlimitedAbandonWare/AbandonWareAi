
    package com.abandonware.ai.service.ocr;
    public class OcrHealthIndicator {
        private final boolean enabled;
        public OcrHealthIndicator(boolean enabled) { this.enabled = enabled; }
        public String health() {
            if (!enabled) return "UP {enabled=false}";
            return "UP {engine=tess4j}"; // best-effort placeholder
        }
    }
    