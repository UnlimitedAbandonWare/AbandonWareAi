package com.example.lms.llm.spec;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudModelMetadataProbeTest {

    @Test
    void createsCloudSpecSnapshotWithRedactedMetadata() {
        CloudModelMetadataProbe probe = new CloudModelMetadataProbe();

        ModelSpecSnapshot snapshot = probe.fromCatalogEntry(
                "openai",
                "https://api.openai.com/v1",
                "gpt-5.4-mini",
                400_000,
                List.of("chat", "reasoning", "code", "tools"),
                Map.of(
                        "apiSurface", "responses",
                        "maxOutputTokens", 128_000,
                        "credentialEnv", "OPENAI_API_KEY",
                        "sourceDocUrl", "https://developers.openai.com/api/docs/models/gpt-5.4-mini",
                        "apiKey", "raw-value-that-must-not-survive"));

        assertEquals("openai", snapshot.provider());
        assertEquals("gpt-5.4-mini", snapshot.model());
        assertEquals("api.openai.com", snapshot.endpointHost());
        assertEquals(400_000, snapshot.contextTokens());
        assertEquals(List.of("chat", "reasoning", "code", "tools"), snapshot.capabilities());
        assertEquals(128_000, snapshot.metadata().get("maxOutputTokens"));
        assertEquals("OPENAI_API_KEY", snapshot.metadata().get("credentialEnv"));
        assertEquals("(redacted)", snapshot.metadata().get("apiKey"));
        assertFalse(snapshot.metadata().toString().contains("raw-value-that-must-not-survive"));
    }

    @Test
    void secretShapedCredentialEnvIsRedactedButEnvNameIsPreserved() {
        CloudModelMetadataProbe probe = new CloudModelMetadataProbe();
        String fakeTokenValue = "sk-" + "cloudcatalogtoken1234567890";

        ModelSpecSnapshot fromProbe = probe.fromCatalogEntry(
                "openai",
                "https://api.openai.com/v1",
                "gpt-5.4-mini",
                400_000,
                List.of("chat"),
                Map.of("credentialEnv", fakeTokenValue));
        ModelSpecSnapshot fromFactory = ModelSpecSnapshot.of(
                "openai",
                "gpt-5.4-mini",
                "api.openai.com",
                400_000,
                null,
                List.of("chat"),
                Map.of("credentialEnv", fakeTokenValue));
        ModelSpecSnapshot envName = probe.fromCatalogEntry(
                "openai",
                "https://api.openai.com/v1",
                "gpt-5.4-mini",
                400_000,
                List.of("chat"),
                Map.of("credentialEnv", "OPENAI_API_KEY"));

        assertEquals("OPENAI_API_KEY", envName.metadata().get("credentialEnv"));
        assertEquals("(redacted)", fromProbe.metadata().get("credentialEnv"));
        assertEquals("(redacted)", fromFactory.metadata().get("credentialEnv"));
        assertFalse(fromProbe.metadata().toString().contains("cloudcatalogtoken"), fromProbe.metadata().toString());
        assertFalse(fromFactory.metadata().toString().contains("cloudcatalogtoken"), fromFactory.metadata().toString());
    }

    @Test
    void cloudCatalogManifestParsesAndKeepsRoutesDisabledByDefault() throws Exception {
        Path manifest = Path.of("main/resources/configs/cloud-models.manifest.yaml");

        Map<?, ?> root;
        try (InputStream in = Files.newInputStream(manifest)) {
            root = new Yaml().load(in);
        }

        assertNotNull(root);
        assertEquals("attachment_unverified", root.get("catalogTrust"));
        List<?> models = (List<?>) root.get("models");
        assertNotNull(models);
        assertTrue(models.size() >= 5);
        for (Object raw : models) {
            Map<?, ?> model = (Map<?, ?>) raw;
            assertEquals(Boolean.FALSE, model.get("enabled"));
            assertEquals("attachment_unverified", model.get("catalogTrust"));
            assertNotNull(model.get("catalogCapturedAt"));
            assertFalse(model.containsKey("lastVerifiedAt"));
            assertNotNull(model.get("provider"));
            assertNotNull(model.get("credentialEnv"));
            assertNotNull(model.get("sourceDocUrl"));
        }
    }

    @Test
    void cloudCatalogLoaderBuildsSnapshotsWithoutPublishingOrOutboundCalls() throws Exception {
        ModelSpecRegistry registry = new ModelSpecRegistry(null, null);
        CloudModelCatalogLoader loader = new CloudModelCatalogLoader(new CloudModelMetadataProbe());

        List<ModelSpecSnapshot> snapshots = loader.loadDefaultCatalog();

        assertTrue(registry.snapshots().isEmpty(), "catalog load must not publish into registry");
        assertTrue(snapshots.size() >= 5);
        Set<String> keys = snapshots.stream().map(ModelSpecSnapshot::key).collect(Collectors.toSet());
        assertTrue(keys.contains("openai:gpt-5.5"));
        assertTrue(keys.contains("openai:gpt-5.4-mini"));
        assertTrue(keys.contains("groq:openai/gpt-oss-120b"));
        assertFalse(keys.contains("groq:qwen/qwen3-32b"));
        assertTrue(keys.contains("gemini:gemini-2.5-pro"));
        assertTrue(keys.contains("mistral:mistral-medium-3-5"));
        assertTrue(keys.contains("anthropic:claude-current-family"));

        ModelSpecSnapshot gemini = snapshot(snapshots, "gemini", "gemini-2.5-pro");
        assertEquals("generativelanguage.googleapis.com", gemini.endpointHost());
        assertEquals(1_048_576, gemini.contextTokens());
        assertTrue(gemini.capabilities().contains("multimodal"));
        assertEquals("GEMINI_API_KEY", gemini.metadata().get("credentialEnv"));
        assertEquals("disabled_by_default_long_context_route", gemini.metadata().get("routingDecision"));
        assertEquals("attachment_unverified", gemini.metadata().get("catalogTrust"));
        assertEquals("2026-07-01", gemini.metadata().get("catalogCapturedAt"));
        assertFalse(gemini.metadata().containsKey("lastVerifiedAt"));
        assertFalse(gemini.metadata().toString().contains("AIza"));

        String llmYaml = Files.readString(Path.of("main/resources/application-llm.yaml"));
        assertTrue(llmYaml.contains("LLMROUTER_API3_NAME:openai/gpt-oss-120b"));
        assertFalse(llmYaml.contains("LLMROUTER_API3_NAME:qwen/qwen3-32b"));

        ModelSpecSnapshot mistral = snapshot(snapshots, "mistral", "mistral-medium-3-5");
        assertEquals("api.mistral.ai", mistral.endpointHost());
        assertEquals(262_144, mistral.contextTokens());
    }

    private static ModelSpecSnapshot snapshot(
            Collection<ModelSpecSnapshot> snapshots,
            String provider,
            String model) {
        return snapshots.stream()
                .filter(snapshot -> provider.equals(snapshot.provider()) && model.equals(snapshot.model()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing cloud snapshot " + provider + ":" + model));
    }
}
