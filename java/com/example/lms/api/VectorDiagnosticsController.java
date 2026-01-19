package com.example.lms.api;

import com.example.lms.config.PineconeProps;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.vector.UpstashVectorStoreAdapter;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vector")
@RequiredArgsConstructor
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
            Map<String, Object> up = new LinkedHashMap<>();
            up.put("configured", upstash.isConfigured());
            up.put("writeEnabled", upstash.isWriteEnabled());
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
        return ResponseEntity.ok(upstash.indexInfo());
    }

    @GetMapping("/upstash/namespaces")
    public ResponseEntity<List<String>> upstashNamespaces() {
        UpstashVectorStoreAdapter upstash = upstashProvider == null ? null : upstashProvider.getIfAvailable();
        if (upstash == null || !upstash.isConfigured()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(upstash.listNamespaces());
    }
}
