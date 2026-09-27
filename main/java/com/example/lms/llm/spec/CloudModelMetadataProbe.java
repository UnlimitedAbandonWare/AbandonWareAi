package com.example.lms.llm.spec;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class CloudModelMetadataProbe {

    private static final System.Logger LOG = System.getLogger(CloudModelMetadataProbe.class.getName());
    private static final java.util.regex.Pattern ENV_NAME =
            java.util.regex.Pattern.compile("[A-Z_][A-Z0-9_]{1,127}");

    public ModelSpecSnapshot fromCatalogEntry(
            String provider,
            String baseUrl,
            String model,
            Integer contextTokens,
            Collection<String> capabilities,
            Map<String, ?> metadata) {
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
        safeMeta.putIfAbsent("source", "cloud_catalog");
        return ModelSpecSnapshot.of(provider, model, endpointHost(baseUrl), contextTokens, null, capabilities, safeMeta);
    }

    private static String endpointHost(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(rawBaseUrl.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ex) {
            TraceStore.put("llm.gateway.spec.cloud.suppressed.endpoint_host", true);
            TraceStore.put("llm.gateway.spec.cloud.suppressed.endpoint_host.errorType", errorType(ex));
            LOG.log(System.Logger.Level.DEBUG,
                    "Cloud model metadata probe skipped stage=endpoint_host errorType=" + errorType(ex));
            return null;
        }
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

    private static String errorType(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }
}
