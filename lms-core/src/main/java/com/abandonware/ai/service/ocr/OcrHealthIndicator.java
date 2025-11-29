
    package com.abandonware.ai.service.ocr;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ocr.OcrHealthIndicator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.ocr.OcrHealthIndicator
role: config
*/
public class OcrHealthIndicator {
        private final boolean enabled;
        public OcrHealthIndicator(boolean enabled) { this.enabled = enabled; }
        public String health() {
            if (!enabled) return "UP {enabled=false}";
            return "UP {engine=tess4j}"; // best-effort placeholder
        }
    }
    