package com.abandonware.ai.agent.tool;

import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.tool.ToolRegistry
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.tool.ToolRegistry
role: config
*/
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new HashMap<>();

    /** Registers a tool in the registry. */
    public void register(AgentTool tool) {
        tools.put(tool.id(), tool);
    }

    /** Retrieves a tool by its identifier. */
    public Optional<AgentTool> get(String id) {
        return Optional.ofNullable(tools.get(id));
    }

    /** Returns all registered tools. */
    public Collection<AgentTool> all() {
        return tools.values();
    }
}