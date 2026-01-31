package com.abandonware.ai.agent.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ChatContext {
    private String sessionId;
    private String requestId;
    private String userQuestion;
    private ChatMode mode = ChatMode.ZERO_BREAK;
    private String provider;
    private boolean officialSourcesOnly = false;
    private List<String> conversationHistory;
    private double finalConfidenceScore = 1.0;
    private List<String> sources;
    private String agentId = "default";
}