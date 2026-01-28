package com.example.lms.service.rag.whiten;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.whiten.Whitening
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.whiten.Whitening
role: config
*/
public interface Whitening {
    double[] apply(double[] v);
}