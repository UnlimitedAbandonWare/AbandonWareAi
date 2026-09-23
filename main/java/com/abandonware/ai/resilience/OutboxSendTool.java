package com.abandonware.ai.resilience;

import java.util.*;

public class OutboxSendTool {
    private final List<String> outbox = new ArrayList<>();
    public boolean sendOrSave(boolean channelUp, String payload) {
        if (channelUp) return true;
        outbox.add(payload);
        return false;
    }
    public List<String> getOutbox() { return outbox; }
}