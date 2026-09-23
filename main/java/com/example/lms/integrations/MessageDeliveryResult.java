package com.example.lms.integrations;

import java.util.Map;

public record MessageDeliveryResult(
        boolean accepted,
        String provider,
        String targetHash,
        String disabledReason,
        Map<String, Object> diagnostics
) {
    public static MessageDeliveryResult disabled(String provider, String targetHash, String reason) {
        return new MessageDeliveryResult(
                false,
                provider,
                targetHash,
                reason,
                Map.of(
                        "accepted", false,
                        "provider", provider,
                        "targetHash", targetHash,
                        "disabledReason", reason
                ));
    }
}
