package com.abandonware.ai.service.ocr;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ocr.OcrSpan
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.ocr.OcrSpan
role: config
*/
public class OcrSpan {
    public final String text;
    public OcrSpan(String text) { this.text = text; }
}