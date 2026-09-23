package com.example.lms.api;

import com.example.lms.config.PineconeProps;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.vector.UpstashVectorStoreAdapter;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.vector.EmbeddingFingerprint;
import com.example.lms.vector.FederatedEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vector")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class VectorDiagnosticsController {

    private final EmbeddingFingerprint embeddingFingerprint;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final PineconeProps pineconeProps;

    // MERGE_HOOK:PROJ_AGENT::VECTOR_DIAGNOSTICS_V1
    private final ObjectProvider<VectorStoreService> vectorStoreServiceProvider;
    private final ObjectProvider<UpstashVectorStoreAdapter> upstashProvider;

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics() {
        Map<String, Object> embedding = new LinkedHashMap<>();
        embedding.put("fingerprintEnabled", embeddingFingerprint != null && embeddingFingerprint.isEnabled());
        embedding.put("fingerprintLength", embeddingFingerprint == null ? null : embeddingFingerprint.getFingerprintLength());
        if (embeddingFingerprint != null) {
            embedding.put("dimensions", embeddingFingerprint.dimensions());
            embedding.put("provider", SafeRedactor.traceLabelOrFallback(embeddingFingerprint.provider(), "unknown"));
            embedding.put("modelHash", SafeRedactor.hashValue(embeddingFingerprint.model()));
            embedding.put("fingerprintHash", SafeRedactor.hashValue(embeddingFingerprint.fingerprint()));
            embedding.put("allowLegacy", embeddingFingerprint.allowLegacy());
            embedding.put("bypassIfMetadataMissing", embeddingFingerprint.bypassIfMetadataMissing());
        }

        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("storeClass", embeddingStore == null ? null : embeddingStore.getClass().getName());

        // MERGE_HOOK:PROJ_AGENT::VECTOR_DIAG_FED_STORE_IDS_V1
        // Diagnostics: expose FederatedEmbeddingStore underlying store ids (if applicable).
        if (embeddingStore instanceof FederatedEmbeddingStore fed) {
            List<String> storeIds = fed.describeStoreIds();
            vector.put("federatedStoreCount", storeIds == null ? 0 : storeIds.size());
            vector.put("federatedStoreIds", storeIds == null ? List.of() : storeIds);
        }
        vector.put("pineconeProject", pineconeProps == null ? null : pineconeProps.getProject());
        vector.put("pineconeEnvironment", pineconeProps == null ? null : pineconeProps.getEnvironment());
        vector.put("pineconeIndex", pineconeProps == null ? null : pineconeProps.getIndex());
        vector.put("pineconeNamespace", pineconeProps == null ? null : pineconeProps.getNamespace());

        VectorStoreService vss = vectorStoreServiceProvider == null ? null : vectorStoreServiceProvider.getIfAvailable();
        if (vss != null) {
            vector.put("buffer", vss.bufferStats());
        }

        UpstashVectorStoreAdapter upstash = upstashProvider == null ? null : upstashProvider.getIfAvailable();
        if (upstash != null) {
            Map<String, Object> up = safeMode(upstash.effectiveMode());
            up.putIfAbsent("configured", upstash.isConfigured());
            up.putIfAbsent("writeEnabled", upstash.isWriteEnabled());
            up.put("namespace", upstash.namespace());
            up.put("restUrlHost", upstash.restUrlHost());
            vector.put("upstash", up);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("embedding", embedding);
        out.put("vector", vector);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/upstash/info")
    public ResponseEntity<Map<String, Object>> upstashInfo() {
        UpstashVectorStoreAdapter upstash = upstashProvider == null ? null : upstashProvider.getIfAvailable();
        if (upstash == null || !upstash.isConfigured()) {
            return ResponseEntity.ok(Map.of());
        }
        Map<String, Object> info = new LinkedHashMap<>(safeMode(upstash.indexInfo()));
        enrichDimensionDiagnostics(info);
        return ResponseEntity.ok(info);
    }

    @GetMapping("/upstash/namespaces")
    public ResponseEntity<List<String>> upstashNamespaces() {
        UpstashVectorStoreAdapter upstash = upstashProvider == null ? null : upstashProvider.getIfAvailable();
        if (upstash == null || !upstash.isConfigured()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(upstash.listNamespaces());
    }

    private Map<String, Object> safeMode(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            out.put(
                    safeModeKey(entry.getKey()),
                    safeModeValue(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    private static String safeModeKey(String key) {
        if ("apiKey".equals(key) || "rawQuery".equals(key)) {
            return key;
        }
        return SafeRedactor.traceLabelOrFallback(key, "field");
    }

    private static Object safeModeValue(String key, Object value) {
        if (isDimensionKey(key)) {
            Integer dim = positiveInt(value);
            if (dim != null) {
                return dim;
            }
        }
        return SafeRedactor.diagnosticValue(key, value, 300);
    }

    private static boolean isDimensionKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
        return "dimension".equals(normalized)
                || "dimensions".equals(normalized)
                || "indexdimensions".equals(normalized);
    }

    private void enrichDimensionDiagnostics(Map<String, Object> info) {
        if (embeddingFingerprint == null) {
            return;
        }
        int configured = embeddingFingerprint.dimensions();
        info.put("configuredEmbeddingDimensions", configured);
        DimensionValue index = firstDimension(info, "dimension", "dimensions");
        if (index == null) {
            info.put("dimensionMatchesConfigured", null);
            return;
        }
        info.put("indexDimensions", index.value());
        info.put("indexDimensionsSource", index.source());
        info.put("dimensionMatchesConfigured", configured == index.value());
    }

    private static DimensionValue firstDimension(Map<String, Object> info, String... keys) {
        if (info == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Integer value = positiveInt(info.get(key));
            if (value != null) {
                return new DimensionValue(key, value);
            }
        }
        return null;
    }

    private static Integer positiveInt(Object value) {
        if (value instanceof Number n) {
            int v = n.intValue();
            return v > 0 ? v : null;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                int v = Integer.parseInt(s.trim());
                return v > 0 ? v : null;
            } catch (NumberFormatException ignored) {
                TraceStore.put("vector.diagnostics.suppressed.stage", "dimensionParse");
                TraceStore.put("vector.diagnostics.suppressed.errorType", "invalid_number");
                TraceStore.put("vector.diagnostics.suppressed.dimensionParse", true);
                TraceStore.put("vector.diagnostics.suppressed.dimensionParse.errorType", "invalid_number");
                return null;
            }
        }
        return null;
    }

    private record DimensionValue(String source, int value) {
    }
}
