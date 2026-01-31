package com.abandonware.ai.agent.model;

import lombok.Data;

@Data
public class ChatResponse {
    private String sessionId;
    private String requestId;
    private String answer;
}