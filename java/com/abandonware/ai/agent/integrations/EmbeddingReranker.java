
package com.abandonware.ai.agent.integrations;

import java.util.List;
import java.util.Map;



public interface EmbeddingReranker {
    List<Map<String,Object>> rerank(String query, List<Map<String,Object>> items);
}