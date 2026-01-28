package com.abandonware.ai.agent.model;

import lombok.Data;

@Data
public class ChatRequest {
    private String sessionId;
    private String requestId;
    private String userQuestion;
}