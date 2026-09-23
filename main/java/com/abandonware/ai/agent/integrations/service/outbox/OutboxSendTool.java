package com.abandonware.ai.agent.integrations.service.outbox;


import java.util.*;
/**
 * OutboxSendTool: buffered send stub.
 */
public class OutboxSendTool {
    private final List<String> buffer = new ArrayList<>();
    public boolean send(String payload){
        buffer.add(payload);
        return true;
    }
    public int pending(){ return buffer.size(); }
}