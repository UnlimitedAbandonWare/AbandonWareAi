package com.abandonware.ai.agent.rag.handler;

import com.abandonware.ai.agent.model.ChatContext;
import com.abandonware.ai.agent.model.ChatMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DynamicRetrievalHandlerChain {
    public List<String> retrieve(String query, ChatContext ctx) {
        List<String> results = new ArrayList<>();
        // Web search stub
        results.add("web:result1");
        if (ctx.getMode() == ChatMode.ZERO_BREAK) return results;
        // Vector/KG stubs
        results.add("vector:result");
        if (ctx.getMode() == ChatMode.BRAVE) results.add("kg:result");
        return results;
    }
}

// PATCH_MARKER: DynamicRetrievalHandlerChain updated per latest spec.
