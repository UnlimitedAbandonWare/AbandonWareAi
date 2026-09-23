package com.abandonware.ai.agent.tool;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;



/**
 * Registry that holds all registered agent tools.  Tools are registered via
 * configuration and can be looked up by their unique identifier.  The
 * registry preserves first-writer ownership for each identifier. Duplicate
 * registrations are recorded for contract reports instead of silently
 * shadowing the first tool.
 */
@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final Map<String, Integer> duplicateToolIds = new LinkedHashMap<>();

    public ToolRegistry() {
    }

    public ToolRegistry(Collection<AgentTool> discoveredTools) {
        if (discoveredTools != null) {
            discoveredTools.forEach(this::register);
        }
    }

    /** Registers a tool in the registry. */
    public void register(AgentTool tool) {
        if (tool == null || tool.id() == null || tool.id().isBlank()) {
            return;
        }
        String id = tool.id().trim();
        if (tools.containsKey(id)) {
            duplicateToolIds.merge(id, 1, Integer::sum);
            TraceStore.put("toolRegistry.duplicate.id", SafeRedactor.traceLabelOrFallback(id, "unknown"));
            TraceStore.put("toolRegistry.duplicate.rejected",
                    SafeRedactor.traceLabelOrFallback(tool.getClass().getSimpleName(), "unknown"));
            TraceStore.inc("toolRegistry.duplicate.count");
            return;
        }
        tools.put(id, tool);
        TraceStore.put("toolRegistry.registered." + id,
                SafeRedactor.traceLabelOrFallback(tool.getClass().getSimpleName(), "unknown"));
    }

    /** Retrieves a tool by its identifier. */
    public Optional<AgentTool> get(String id) {
        return Optional.ofNullable(tools.get(id));
    }

    /** Returns all registered tools. */
    public Collection<AgentTool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /** Returns duplicate tool ids and the number of duplicate registrations. */
    public Map<String, Integer> duplicateToolIdCounts() {
        return Collections.unmodifiableMap(duplicateToolIds);
    }
}
