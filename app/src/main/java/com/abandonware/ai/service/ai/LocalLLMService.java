package com.abandonware.ai.service.ai;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ai.LocalLLMService
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.ai.LocalLLMService
role: config
*/
public interface LocalLLMService {
    String generateText(String prompt) throws Exception;
    String engineName();
}