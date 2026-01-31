package com.abandonware.ai.agent.consent;

import com.abandonware.ai.agent.tool.ToolScope;
import java.util.Arrays;

public class ConsentCardRenderer {
    public String renderBasic(String sessionId, String roomId, String[] scopes, long ttlSeconds) {
        String s = Arrays.toString(scopes);
        return "{"type":"consent","sessionId":""+sessionId+"","roomId":""+roomId+"","scopes":"+s+","ttl":"+ttlSeconds+"}";
    }
}