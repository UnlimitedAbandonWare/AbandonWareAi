package com.example.lms.orchestration;

import com.example.lms.trace.SafeRedactor;

final class OrchAutoReportRedaction {
    private OrchAutoReportRedaction() {
    }

    static String providerLabel(Object value) {
        return SafeRedactor.traceLabelOrFallback(value, "");
    }

    static String breakerKeyLabel(String breakerKey) {
        String raw = breakerKey == null ? "" : breakerKey.trim();
        if (raw.isBlank()) {
            return "";
        }
        String prefix = "websearch:";
        if (raw.startsWith(prefix)) {
            return prefix + providerLabel(raw.substring(prefix.length()));
        }
        return providerLabel(raw);
    }
}
