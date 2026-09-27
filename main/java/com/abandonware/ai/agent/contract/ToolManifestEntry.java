package com.abandonware.ai.agent.contract;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public record ToolManifestEntry(
        String id,
        boolean enabled,
        String description,
        String risk,
        List<String> scopes,
        boolean readOnly,
        boolean ownerTokenRequired,
        int maxOutputBytes,
        boolean returnsLargePayloadByReference,
        String endpoint,
        String disabledReason,
        Map<String, Object> rawSummary
) {
    public boolean sideEffectRisk() {
        String r = risk == null ? "" : risk.trim().toLowerCase(Locale.ROOT);
        return !readOnly || r.equals("write_controlled") || r.equals("external_call") || r.equals("destructive");
    }
}
