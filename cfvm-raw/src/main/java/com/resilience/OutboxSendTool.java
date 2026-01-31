package com.resilience;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.resilience.OutboxSendTool
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.resilience.OutboxSendTool
role: config
*/
public class OutboxSendTool {
    private final List<String> outbox = new ArrayList<>();
    public boolean sendOrSave(boolean channelUp, String payload) {
        if (channelUp) return true;
        outbox.add(payload);
        return false;
    }
    public List<String> getOutbox() { return outbox; }
}