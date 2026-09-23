package com.example.lms.llm.spec;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class LocalModelMetadataProbe {

    private static final System.Logger LOG = System.getLogger(LocalModelMetadataProbe.class.getName());

    public ModelSpecSnapshot fromOllamaShow(String baseUrl, String model, Map<String, ?> response) {
        Map<String, Object> meta = new LinkedHashMap<>();
        Integer context = null;
        Integer embedding = null;
        List<String> capabilities = new ArrayList<>();

        if (response != null) {
            Object caps = response.get("capabilities");
            if (caps instanceof Collection<?> values) {
                for (Object value : values) {
                    if (value != null) {
                        capabilities.add(String.valueOf(value));
                    }
                }
            }
            Object details = response.get("details");
            if (details instanceof Map<?, ?> detailsMap) {
                putString(meta, "format", detailsMap.get("format"));
                putString(meta, "family", detailsMap.get("family"));
                putString(meta, "parameterSize", detailsMap.get("parameter_size"));
                putString(meta, "quantizationLevel", detailsMap.get("quantization_level"));
            }
            Object modelInfo = response.get("model_info");
            if (modelInfo instanceof Map<?, ?> info) {
                context = firstIntBySuffix(info, "context_length");
                embedding = firstIntBySuffix(info, "embedding_length");
            }
        }
        return ModelSpecSnapshot.of("ollama", model, endpointHost(baseUrl), context, embedding, capabilities, meta);
    }

    public ModelSpecSnapshot fromEmbeddingProbe(String provider, String baseUrl, String model, int embeddingDim) {
        return ModelSpecSnapshot.of(provider, model, endpointHost(baseUrl), null, embeddingDim, List.of("embedding"), Map.of());
    }

    public ModelSpecSnapshot fromOpenAiModel(String provider, String baseUrl, String model, Collection<String> capabilities) {
        return ModelSpecSnapshot.of(provider, model, endpointHost(baseUrl), null, null, capabilities, Map.of());
    }

    private static void putString(Map<String, Object> meta, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            meta.put(key, String.valueOf(value));
        }
    }

    private static Integer firstIntBySuffix(Map<?, ?> map, String suffix) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if (!key.endsWith(suffix)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Number n) {
                return n.intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ex) {
                TraceStore.put("llm.gateway.spec.metadata.suppressed.model_info_int", true);
                TraceStore.put("llm.gateway.spec.metadata.suppressed.model_info_int.errorType", "invalid_number");
                traceMetadataSkipped("model_info_int", ex);
            }
        }
        return null;
    }

    private static String endpointHost(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(rawBaseUrl.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ex) {
            TraceStore.put("llm.gateway.spec.metadata.suppressed.endpoint_host", true);
            TraceStore.put("llm.gateway.spec.metadata.suppressed.endpoint_host.errorType", errorType(ex));
            traceMetadataSkipped("endpoint_host", ex);
            return null;
        }
    }

    private static void traceMetadataSkipped(String stage, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = "model_info_int".equals(safeStage) ? "invalid_number" : errorType(error);
        TraceStore.put("llm.gateway.spec.metadata.suppressed.stage", safeStage);
        TraceStore.put("llm.gateway.spec.metadata.suppressed.errorType", errorType);
        LOG.log(System.Logger.Level.DEBUG,
                "Local model metadata probe skipped stage=" + safeStage + " errorType=" + errorType);
    }

    private static String errorType(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }
}
