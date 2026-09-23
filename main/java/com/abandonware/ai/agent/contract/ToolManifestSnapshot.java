package com.abandonware.ai.agent.contract;

import java.util.List;
import java.util.Map;

public record ToolManifestSnapshot(
        String resourcePath,
        Map<String, ToolManifestEntry> entries,
        List<Map<String, Object>> issues,
        List<Map<String, Object>> warnings
) {
    public ToolManifestEntry entry(String id) {
        return id == null ? null : entries.get(id);
    }
}
