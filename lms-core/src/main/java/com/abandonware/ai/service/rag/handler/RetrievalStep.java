package com.abandonware.ai.service.rag.handler;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.handler.RetrievalStep
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.handler.RetrievalStep
role: config
*/
public enum RetrievalStep {
    WEB, VECTOR, KG
}