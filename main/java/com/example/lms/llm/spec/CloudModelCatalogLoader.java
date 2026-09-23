package com.example.lms.llm.spec;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class CloudModelCatalogLoader {

    static final String DEFAULT_CATALOG = "configs/cloud-models.manifest.yaml";

    private static final System.Logger LOG = System.getLogger(CloudModelCatalogLoader.class.getName());

    private final CloudModelMetadataProbe metadataProbe;

    public CloudModelCatalogLoader(CloudModelMetadataProbe metadataProbe) {
        this.metadataProbe = metadataProbe == null ? new CloudModelMetadataProbe() : metadataProbe;
    }

    public List<ModelSpecSnapshot> loadDefaultCatalog() {
        return loadClasspath(DEFAULT_CATALOG);
    }

    public List<ModelSpecSnapshot> loadClasspath(String manifestPath) {
        String resourcePath = normalizeResourcePath(manifestPath);
        if (resourcePath == null) {
            traceSkipped("missing_path", manifestPath, null);
            return List.of();
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                traceSkipped("not_found", resourcePath, null);
                return List.of();
            }
            return load(in);
        } catch (Exception ex) {
            traceSkipped("load_failed", resourcePath, ex);
            return List.of();
        }
    }

    public List<ModelSpecSnapshot> load(InputStream input) {
        if (input == null) {
            traceSkipped("missing_input", null, null);
            return List.of();
        }
        try {
            Object loaded = new Yaml().load(input);
            if (!(loaded instanceof Map<?, ?> root)) {
                traceSkipped("invalid_root", null, null);
                return List.of();
            }
            Object rawModels = root.get("models");
            if (!(rawModels instanceof Collection<?> models)) {
                traceSkipped("missing_models", null, null);
                return List.of();
            }
            List<ModelSpecSnapshot> snapshots = new ArrayList<>();
            for (Object rawModel : models) {
                if (!(rawModel instanceof Map<?, ?> model)) {
                    continue;
                }
                ModelSpecSnapshot snapshot = snapshotFrom(model);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
            return List.copyOf(snapshots);
        } catch (Exception ex) {
            traceSkipped("parse_failed", null, ex);
            return List.of();
        }
    }

    private ModelSpecSnapshot snapshotFrom(Map<?, ?> model) {
        String provider = text(model.get("provider"));
        String id = text(model.get("id"));
        if (provider == null || id == null) {
            return null;
        }
        Map<?, ?> endpoint = asMap(model.get("endpoint"));
        String baseUrl = endpoint == null ? null : text(endpoint.get("base_url"));
        Integer contextTokens = intValue(model.get("ctx"));
        List<String> capabilities = stringList(model.get("capabilities"));
        Map<String, Object> metadata = new LinkedHashMap<>();
        copy(metadata, model, "enabled");
        copy(metadata, model, "credentialEnv");
        copy(metadata, model, "apiSurface");
        copy(metadata, model, "maxOutputTokens");
        copy(metadata, model, "costTier");
        copy(metadata, model, "latencyTier");
        copy(metadata, model, "routingDecision");
        copy(metadata, model, "sourceDocUrl");
        copy(metadata, model, "catalogCapturedAt");
        copy(metadata, model, "catalogTrust");
        if (endpoint != null) {
            copy(metadata, endpoint, "type", "endpointType");
        }
        return metadataProbe.fromCatalogEntry(provider, baseUrl, id, contextTokens, capabilities, metadata);
    }

    private static void copy(Map<String, Object> target, Map<?, ?> source, String key) {
        copy(target, source, key, key);
    }

    private static void copy(Map<String, Object> target, Map<?, ?> source, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : collection) {
            String text = text(item);
            if (text != null) {
                out.add(text);
            }
        }
        return List.copyOf(out);
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            traceSkipped("invalid_context_tokens", null, ex);
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String normalizeResourcePath(String rawPath) {
        String path = text(rawPath);
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.toLowerCase(Locale.ROOT).startsWith("classpath:")) {
            normalized = normalized.substring("classpath:".length());
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private static void traceSkipped(String reason, String path, Exception error) {
        try {
            TraceStore.put("llm.gateway.spec.cloud.catalog.skippedReason",
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            if (path != null) {
                TraceStore.put("llm.gateway.spec.cloud.catalog.pathHash", SafeRedactor.hashValue(path));
                TraceStore.put("llm.gateway.spec.cloud.catalog.pathLength", path.length());
            }
            TraceStore.put("llm.gateway.spec.cloud.catalog.errorType",
                    error == null ? null : errorType(error));
        } catch (Exception traceError) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Cloud model catalog telemetry skipped stage=trace_skip errorType=" + errorType(traceError));
        }
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }
}
