package com.abandonware.ai.agent.integrations;

import java.util.List;
import java.util.Map;



public interface WebSearchGateway {
    List<Map<String,Object>> searchAndRank(String query, int topK, String lang);
}