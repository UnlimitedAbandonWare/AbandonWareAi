
package com.abandonware.ai.agent.integrations;

import java.util.List;
import java.util.Map;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.EmbeddingReranker
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.EmbeddingReranker
role: config
*/
public interface EmbeddingReranker {
    List<Map<String,Object>> rerank(String query, List<Map<String,Object>> items);
}