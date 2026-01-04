package com.abandonware.ai.agent.integrations;

import java.util.List;
import java.util.Map;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.WebSearchGateway
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.WebSearchGateway
role: config
*/
public interface WebSearchGateway {
    List<Map<String,Object>> searchAndRank(String query, int topK, String lang);
}