package com.example.lms.llm.spec;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudModelCatalogLoaderTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void missingClasspathCatalogFailsSoftWithPathHashOnly() {
        CloudModelCatalogLoader loader = new CloudModelCatalogLoader(new CloudModelMetadataProbe());
        String missingPath = "configs/missing-private-cloud-models.yaml";

        List<ModelSpecSnapshot> snapshots = loader.loadClasspath(missingPath);

        assertTrue(snapshots.isEmpty());
        assertEquals("not_found", TraceStore.get("llm.gateway.spec.cloud.catalog.skippedReason"));
        assertTrue(String.valueOf(TraceStore.get("llm.gateway.spec.cloud.catalog.pathHash")).startsWith("hash:"));
        assertEquals(missingPath.length(), TraceStore.get("llm.gateway.spec.cloud.catalog.pathLength"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(missingPath));
    }

    @Test
    void malformedCatalogFailsSoftWithRedactedErrorType() {
        CloudModelCatalogLoader loader = new CloudModelCatalogLoader(new CloudModelMetadataProbe());
        ByteArrayInputStream malformed = new ByteArrayInputStream(
                "models: [".getBytes(StandardCharsets.UTF_8));

        List<ModelSpecSnapshot> snapshots = loader.load(malformed);

        assertTrue(snapshots.isEmpty());
        assertEquals("parse_failed", TraceStore.get("llm.gateway.spec.cloud.catalog.skippedReason"));
        assertEquals("ParserException", TraceStore.get("llm.gateway.spec.cloud.catalog.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("models: ["));
    }

    @Test
    void invalidContextTokensRemainFailSoftWithRedactedEvidence() {
        CloudModelCatalogLoader loader = new CloudModelCatalogLoader(new CloudModelMetadataProbe());
        String rawContext = "not-an-integer ownerToken=private";
        String manifest = """
                models:
                  - id: "malformed-context"
                    provider: "openai"
                    ctx: "%s"
                    capabilities: [chat]
                """.formatted(rawContext);

        List<ModelSpecSnapshot> snapshots = loader.load(new ByteArrayInputStream(
                manifest.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, snapshots.size());
        assertNull(snapshots.get(0).contextTokens());
        assertEquals("invalid_context_tokens",
                TraceStore.get("llm.gateway.spec.cloud.catalog.skippedReason"));
        assertEquals("NumberFormatException",
                TraceStore.get("llm.gateway.spec.cloud.catalog.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawContext));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void laterReasonWithoutExceptionClearsPreviousErrorType() {
        CloudModelCatalogLoader loader = new CloudModelCatalogLoader(new CloudModelMetadataProbe());
        String invalidContextManifest = """
                models:
                  - id: "malformed-context"
                    provider: "openai"
                    ctx: "not-an-integer"
                """;

        loader.load(new ByteArrayInputStream(invalidContextManifest.getBytes(StandardCharsets.UTF_8)));
        loader.loadClasspath("configs/missing-after-invalid-context.yaml");

        assertEquals("not_found", TraceStore.get("llm.gateway.spec.cloud.catalog.skippedReason"));
        assertNull(TraceStore.get("llm.gateway.spec.cloud.catalog.errorType"));
    }
}
