package com.example.lms.llm.spec;

import com.example.lms.trace.SafeRedactor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record ModelSpecSnapshot(
        String provider,
        String model,
        String endpointHost,
        Integer contextTokens,
        Integer embeddingDim,
        List<String> capabilities,
        Map<String, Object> metadata,
        Instant observedAt) {
    private static final java.util.regex.Pattern ENV_NAME =
            java.util.regex.Pattern.compile("[A-Z_][A-Z0-9_]{1,127}");

    public ModelSpecSnapshot {
        provider = safe(provider);
        model = safe(model);
        endpointHost = safe(endpointHost);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }

    public static ModelSpecSnapshot of(
            String provider,
            String model,
            String endpointHost,
            Integer contextTokens,
            Integer embeddingDim,
            Collection<String> capabilities,
            Map<String, ?> metadata) {
        List<String> caps = new ArrayList<>();
        if (capabilities != null) {
            for (String capability : capabilities) {
                String safe = safe(capability);
                if (safe != null) {
                    caps.add(safe);
                }
            }
        }
        Map<String, Object> safeMeta = new LinkedHashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, ?> entry : metadata.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                Object safeValue = safeCatalogValue(entry.getKey(), entry.getValue());
                if (safeValue != null) {
                    safeMeta.put(entry.getKey(), safeValue);
                }
            }
        }
        return new ModelSpecSnapshot(provider, model, endpointHost, contextTokens, embeddingDim, caps, safeMeta, Instant.now());
    }

    public String key() {
        return key(provider, model);
    }

    public static String key(String provider, String model) {
        String p = safe(provider);
        String m = safe(model);
        if (p == null || m == null) {
            return null;
        }
        return p.toLowerCase(Locale.ROOT) + ":" + m.toLowerCase(Locale.ROOT);
    }

    private static boolean publicCatalogScalar(String key, Object value) {
        if (key == null || value == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if ("maxoutputtokens".equals(normalized)) {
            return value instanceof Number;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return false;
        }
        if ("credentialenv".equals(normalized)) {
            return ENV_NAME.matcher(text).matches();
        }
        return "apisurface".equals(normalized)
                || "costtier".equals(normalized)
                || "latencytier".equals(normalized)
                || "routingdecision".equals(normalized)
                || "catalogcapturedat".equals(normalized)
                || "catalogtrust".equals(normalized)
                || "endpointtype".equals(normalized);
    }

    private static Object safeCatalogValue(String key, Object value) {
        if (publicCatalogScalar(key, value)) {
            return value;
        }
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if ("credentialenv".equals(normalized)) {
            return "(redacted)";
        }
        return SafeRedactor.diagnosticValue(key, value);
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String redacted = SafeRedactor.redact(trimmed).replace('\n', ' ').replace('\r', ' ');
        return redacted.length() > 160 ? redacted.substring(0, 160) : redacted;
    }
}
