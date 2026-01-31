package com.abandonware.ai.agent.tool;

import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;



/**
 * Registry that holds all registered agent tools.  Tools are registered via
 * configuration and can be looked up by their unique identifier.  The
 * registry does not impose any ordering or uniqueness constraints beyond
 * the identifier; if multiple tools share the same id the latter will
 * overwrite the former.
 */
@Component
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