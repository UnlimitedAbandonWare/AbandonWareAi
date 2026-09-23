package com.example.lms.config;

import com.example.lms.search.TraceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalLlmRecoveryTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path runtimeDirectory;
    private LocalLlmProcessManager manager;
    private MockEnvironment routeEnvironment;
    @AfterEach void stop() { if (manager != null) manager.stop(); TraceStore.clear(); }

    @Test void disabledAutostartNeverLaunchesOrProbes() {
        Runtime runtime = new Runtime();
        start(runtime, "local-llm.autostart", "false");
        assertThat(runtime.launches).isEmpty();
        assertThat(runtime.probes).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void operatorDisabledRouteNeverSchedulesLaunchProbeOrWarmup(boolean externalServer) {
        Runtime runtime = new Runtime();
        runtime.server = externalServer;
        manager = routedManager(runtime, false);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        manager.requestRecovery("GPU_DEVICE_LOST");
        runtime.now += 60_000;
        assertThat(manager.diagnostics()).containsEntry("state", "DISABLED")
                .containsEntry("reasonCode", "route_disabled").containsEntry("modelReady", false);
        assertThat(runtime.launches).isEmpty();
        assertThat(runtime.probes).isZero();
        assertThat(runtime.warmups).isZero();
        assertThat(runtime.pulls).isZero();
        assertThat(runtime.executions).isZero();
        assertThat(runtime.scheduled).isEmpty();
        assertThat(runtime.terminations).isZero();
    }

    @Test void disabledDifferentEndpointDoesNotBlockIndependentManager() {
        Runtime runtime = new Runtime();
        manager = routedManager(runtime, false);
        routeEnvironment.setProperty("llmrouter.models.managed.base-url", "http://127.0.0.1:11436/v1");
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY");
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.warmups).isEqualTo(1);
    }

    @Test void ambiguousRoleStopsOnlyTheManagedMappingBeforeProbe() {
        Runtime runtime = new Runtime();
        manager = routedManager(runtime, false);
        routeEnvironment.setProperty("llmrouter.models.managed.device-role", "");
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "DISABLED")
                .containsEntry("reasonCode", "route_mapping_unconfirmed");
        assertThat(runtime.probes).isZero();
        assertThat(runtime.executions).isZero();
        assertThat(runtime.launches).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void offDuringWarmupPreventsRetryRestartAndReady(boolean warmupFails) {
        Runtime runtime = new Runtime();
        manager = routedManager(runtime, true);
        runtime.onWarmup = () -> routeEnvironment.setProperty("llmrouter.models.managed.enabled", "false");
        if (warmupFails) runtime.alwaysFail = "GPU is lost";
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "DISABLED")
                .containsEntry("reasonCode", "route_disabled").containsEntry("modelReady", false);
        assertThat(runtime.warmups).isEqualTo(1);
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.terminations).isZero();
        assertThat(runtime.scheduled).isEmpty();
    }

    @Test void operatorOnStillRequiresSuccessfulModelRecovery() {
        Runtime runtime = new Runtime();
        manager = routedManager(runtime, true);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        routeEnvironment.setProperty("llmrouter.models.managed.enabled", "false");
        manager.requestRecovery("GPU_DEVICE_LOST");
        routeEnvironment.setProperty("llmrouter.models.managed.enabled", "true");
        runtime.alwaysFail = "insufficient memory";
        manager.requestRecovery("GPU_DEVICE_LOST");
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN")
                .containsEntry("reasonCode", "INSUFFICIENT_MEMORY").containsEntry("modelReady", false)
                .containsEntry("gpuRoleVerified", false);
        assertThat(runtime.launches).hasSize(2);
        assertThat(runtime.terminations).isEqualTo(1);
        assertThat(runtime.scheduled).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recovery-event", "cooldown-callback", "queued-start", "queued-recovery"})
    void operatorOffIsRecheckedBeforeDeferredLifecycleWork(String phase) {
        Runtime runtime = new Runtime();
        runtime.deferExecution = "queued-start".equals(phase);
        manager = routedManager(runtime, true);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        if ("cooldown-callback".equals(phase)) manager.requestRecovery("INSUFFICIENT_MEMORY");
        if ("queued-recovery".equals(phase)) {
            runtime.deferExecution = true;
            manager.requestRecovery("GPU_DEVICE_LOST");
        }
        int probes = runtime.probes, warmups = runtime.warmups;
        int launches = runtime.launches.size(), terminations = runtime.terminations;
        routeEnvironment.setProperty("llmrouter.models.managed.enabled", "false");
        runtime.now += 60_000;
        if ("cooldown-callback".equals(phase)) runtime.scheduled.removeFirst().run();
        else if (phase.startsWith("queued-")) runtime.pendingExecution.removeFirst().run();
        else manager.requestRecovery("GPU_DEVICE_LOST");
        assertThat(manager.diagnostics()).containsEntry("state", "DISABLED")
                .containsEntry("reasonCode", "route_disabled").containsEntry("modelReady", false);
        assertThat(runtime.probes).isEqualTo(probes);
        assertThat(runtime.warmups).isEqualTo(warmups);
        assertThat(runtime.launches).hasSize(launches);
        assertThat(runtime.terminations).isEqualTo(terminations);
        assertThat(runtime.scheduled).isEmpty();
        assertThat(runtime.pendingExecution).isEmpty();
    }

    @Test void externalOllamaRequiresRealWarmupAndIsNeverDestroyed() {
        Runtime runtime = new Runtime(); runtime.server = true;
        start(runtime);
        assertThat(manager.diagnostics()).containsEntry("state", "READY").containsEntry("owned", false)
                .containsEntry("modelPresent", true).containsEntry("modelReady", true);
        assertThat(runtime.warmups).isEqualTo(1);
        assertThat(runtime.launches).isEmpty();
        manager.stop();
        assertThat(runtime.terminations).isZero();
    }

    @Test void externalEmbeddingProviderNeverSuppliesOrBlocksChatReadiness() {
        Runtime runtime = new Runtime();
        manager = manager(runtime, false,
                "embedding.provider", "openai", "embedding.model", "text-embedding-3-small",
                "llm.chat-model", "fixture:chat", "local-llm.warmup.embed", "true");
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("modelReady", true).containsEntry("embedVerified", false)
                .containsEntry("embedWarmupApplies", false);
        assertThat(runtime.embeds).isZero();
        assertThat(manager.isAvailable("http://127.0.0.1:11435/v1")).isTrue();
    }

    @Test void managedEmbeddingProviderIsVerifiedSeparatelyFromChatReadiness() {
        Runtime runtime = new Runtime();
        manager = manager(runtime, false,
                "embedding.provider", "ollama", "embedding.model", "fixture:embed",
                "llm.chat-model", "fixture:chat", "local-llm.warmup.embed", "true");
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("modelReady", true).containsEntry("embedVerified", true)
                .containsEntry("embedWarmupApplies", true);
        assertThat(runtime.embeds).isEqualTo(1);
    }

    @Test void managedEmbeddingFailureIsDiagnosticAndNeverBlocksChatReadiness() {
        Runtime runtime = new Runtime();
        runtime.embedFail = "embedding backend exploded";
        manager = manager(runtime, false,
                "embedding.provider", "ollama", "embedding.model", "fixture:embed",
                "llm.chat-model", "fixture:chat", "local-llm.warmup.embed", "true");
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("modelReady", true).containsEntry("embedVerified", false);
        assertThat(runtime.embeds).isEqualTo(1);
        assertThat(manager.isAvailable("http://127.0.0.1:11435/v1")).isTrue();
    }

    @Test void explicitWarmupEmbedModelOverridesForeignProvider() {
        Runtime runtime = new Runtime();
        runtime.expectedEmbedModel = "fixture:embed";
        manager = manager(runtime, false,
                "embedding.provider", "openai", "embedding.model", "text-embedding-3-small",
                "local-llm.warmup.embed-model", "fixture:embed",
                "llm.chat-model", "fixture:chat", "local-llm.warmup.embed", "true");
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("modelReady", true).containsEntry("embedVerified", true)
                .containsEntry("embedWarmupApplies", true);
        assertThat(runtime.embeds).isEqualTo(1);
    }

    @Test void managedProviderPostsExplicitWarmupEmbedModelNotEmbeddingModel() {
        Runtime runtime = new Runtime();
        runtime.expectedEmbedModel = "fixture:explicit-embed";
        manager = manager(runtime, false,
                "embedding.provider", "ollama", "embedding.model", "fixture:embed",
                "local-llm.warmup.embed-model", "fixture:explicit-embed",
                "llm.chat-model", "fixture:chat", "local-llm.warmup.embed", "true");
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("embedWarmupApplies", true)
                .containsEntry("embedVerified", true);
        assertThat(runtime.embeds).isEqualTo(1);
    }

    @Test void realProfileYamlDefaultEmbedModelIsNotAnExplicitOverride() throws Exception {
        Runtime runtime = new Runtime();
        Map<String, Object> overrides = new java.util.LinkedHashMap<>();
        overrides.put("local-llm.enabled", "true");
        overrides.put("local-llm.cuda-auto-discover", "false");
        overrides.put("local-llm.log-dir", runtimeDirectory.toString());
        overrides.put("local-llm.autostart", "true");
        overrides.put("local-llm.fail-fast", "false");
        overrides.put("local-llm.ollama-host", "127.0.0.1:11435");
        overrides.put("local-llm.warmup.enabled", "true");
        overrides.put("local-llm.warmup.pull", "false");
        overrides.put("local-llm.warmup.model", "fixture:chat");
        overrides.put("local-llm.warmup.embed", "true");
        overrides.put("local-llm.health-check-interval", "1ms");
        overrides.put("embedding.provider", "openai");
        overrides.put("embedding.model", "text-embedding-3-small");
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("testOverrides", overrides));
        for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load(
                "application-llm.yaml",
                new org.springframework.core.io.FileSystemResource("main/resources/application-llm.yaml"))) {
            env.getPropertySources().addLast(source);
        }
        env.setConversionService(new ApplicationConversionService());
        // The packaged default resolves local-llm.warmup.embed-model through embedding.model;
        // that resolved presence is not a user override and must stay closed under a foreign provider.
        assertThat(env.getProperty("local-llm.warmup.embed-model")).isEqualTo("text-embedding-3-small");
        manager = new LocalLlmProcessManager(env, runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("modelReady", true)
                .containsEntry("embedWarmupApplies", false)
                .containsEntry("embedVerified", false);
        assertThat(runtime.embeds).isZero();
    }

    @Test void realProfileExplicitOverrideUnderForeignProviderWarmsManagedModel() throws Exception {
        Runtime runtime = new Runtime();
        runtime.expectedEmbedModel = "fixture:embed";
        Map<String, Object> overrides = new java.util.LinkedHashMap<>();
        overrides.put("local-llm.enabled", "true");
        overrides.put("local-llm.cuda-auto-discover", "false");
        overrides.put("local-llm.log-dir", runtimeDirectory.toString());
        overrides.put("local-llm.autostart", "true");
        overrides.put("local-llm.fail-fast", "false");
        overrides.put("local-llm.ollama-host", "127.0.0.1:11435");
        overrides.put("local-llm.warmup.enabled", "true");
        overrides.put("local-llm.warmup.pull", "false");
        overrides.put("local-llm.warmup.model", "fixture:chat");
        overrides.put("local-llm.warmup.embed", "true");
        overrides.put("local-llm.warmup.embed-model", "fixture:embed");
        overrides.put("local-llm.health-check-interval", "1ms");
        overrides.put("embedding.provider", "openai");
        overrides.put("embedding.model", "text-embedding-3-small");
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("testOverrides", overrides));
        for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load(
                "application-llm.yaml",
                new org.springframework.core.io.FileSystemResource("main/resources/application-llm.yaml"))) {
            env.getPropertySources().addLast(source);
        }
        env.setConversionService(new ApplicationConversionService());
        manager = new LocalLlmProcessManager(env, runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("embedWarmupApplies", true)
                .containsEntry("embedVerified", true);
        assertThat(runtime.embeds).isEqualTo(1);
    }

    @Test void realProfileExplicitSameValueOverrideUnderForeignProviderWarmsManagedModel() throws Exception {
        Runtime runtime = new Runtime();
        runtime.expectedEmbedModel = "text-embedding-3-small";
        Map<String, Object> overrides = new java.util.LinkedHashMap<>();
        overrides.put("local-llm.enabled", "true");
        overrides.put("local-llm.cuda-auto-discover", "false");
        overrides.put("local-llm.log-dir", runtimeDirectory.toString());
        overrides.put("local-llm.autostart", "true");
        overrides.put("local-llm.fail-fast", "false");
        overrides.put("local-llm.ollama-host", "127.0.0.1:11435");
        overrides.put("local-llm.warmup.enabled", "true");
        overrides.put("local-llm.warmup.pull", "false");
        overrides.put("local-llm.warmup.model", "fixture:chat");
        overrides.put("local-llm.warmup.embed", "true");
        // Same value as embedding.model, but set by the user source: origin, not
        // value equality, marks it explicit and the managed warmup must honour it.
        overrides.put("local-llm.warmup.embed-model", "text-embedding-3-small");
        overrides.put("local-llm.health-check-interval", "1ms");
        overrides.put("embedding.provider", "openai");
        overrides.put("embedding.model", "text-embedding-3-small");
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("testOverrides", overrides));
        for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load(
                "application-llm.yaml",
                new org.springframework.core.io.FileSystemResource("main/resources/application-llm.yaml"))) {
            env.getPropertySources().addLast(source);
        }
        env.setConversionService(new ApplicationConversionService());
        manager = new LocalLlmProcessManager(env, runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("embedWarmupApplies", true)
                .containsEntry("embedVerified", true);
        assertThat(runtime.embeds).isEqualTo(1);
    }

    @Test void realProfileExplicitPlaceholderReferenceUnderForeignProviderWarmsManagedModel() throws Exception {
        // A user source may legitimately set warmup.embed-model to a ${...} reference;
        // that is still an explicit setting and must not be skipped as a "placeholder".
        Runtime runtime = new Runtime();
        runtime.expectedEmbedModel = "fixture:embed";
        Map<String, Object> overrides = new java.util.LinkedHashMap<>();
        overrides.put("local-llm.enabled", "true");
        overrides.put("local-llm.cuda-auto-discover", "false");
        overrides.put("local-llm.log-dir", runtimeDirectory.toString());
        overrides.put("local-llm.autostart", "true");
        overrides.put("local-llm.fail-fast", "false");
        overrides.put("local-llm.ollama-host", "127.0.0.1:11435");
        overrides.put("local-llm.warmup.enabled", "true");
        overrides.put("local-llm.warmup.pull", "false");
        overrides.put("local-llm.warmup.model", "fixture:chat");
        overrides.put("local-llm.warmup.embed", "true");
        overrides.put("local-llm.warmup.embed-model", "${fixture.embed.ref}");
        overrides.put("fixture.embed.ref", "fixture:embed");
        overrides.put("local-llm.health-check-interval", "1ms");
        overrides.put("embedding.provider", "openai");
        overrides.put("embedding.model", "text-embedding-3-small");
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("testOverrides", overrides));
        for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load(
                "application-llm.yaml",
                new org.springframework.core.io.FileSystemResource("main/resources/application-llm.yaml"))) {
            env.getPropertySources().addLast(source);
        }
        env.setConversionService(new ApplicationConversionService());
        assertThat(env.getProperty("local-llm.warmup.embed-model")).isEqualTo("fixture:embed");
        manager = new LocalLlmProcessManager(env, runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("embedWarmupApplies", true)
                .containsEntry("embedVerified", true);
        assertThat(runtime.embeds).isEqualTo(1);
    }

    @Test void realProfileHigherPrecedencePlaceholderBeatsLowerPrecedenceDirectValue() throws Exception {
        // The first-defining source decides. A higher-precedence ${...} reference must
        // not be skipped so that a lower-precedence literal becomes the basis.
        Runtime runtime = new Runtime();
        runtime.expectedEmbedModel = "fixture:embed";
        Map<String, Object> overrides = new java.util.LinkedHashMap<>();
        overrides.put("local-llm.enabled", "true");
        overrides.put("local-llm.cuda-auto-discover", "false");
        overrides.put("local-llm.log-dir", runtimeDirectory.toString());
        overrides.put("local-llm.autostart", "true");
        overrides.put("local-llm.fail-fast", "false");
        overrides.put("local-llm.ollama-host", "127.0.0.1:11435");
        overrides.put("local-llm.warmup.enabled", "true");
        overrides.put("local-llm.warmup.pull", "false");
        overrides.put("local-llm.warmup.model", "fixture:chat");
        overrides.put("local-llm.warmup.embed", "true");
        overrides.put("local-llm.warmup.embed-model", "${fixture.embed.ref}");
        overrides.put("fixture.embed.ref", "fixture:embed");
        overrides.put("local-llm.health-check-interval", "1ms");
        overrides.put("embedding.provider", "openai");
        overrides.put("embedding.model", "text-embedding-3-small");
        Map<String, Object> lower = new java.util.LinkedHashMap<>();
        lower.put("local-llm.warmup.embed-model", "should-not-win");
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("testOverrides", overrides));
        env.getPropertySources().addAfter("testOverrides",
                new org.springframework.core.env.MapPropertySource("lowerOverrides", lower));
        for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load(
                "application-llm.yaml",
                new org.springframework.core.io.FileSystemResource("main/resources/application-llm.yaml"))) {
            env.getPropertySources().addLast(source);
        }
        env.setConversionService(new ApplicationConversionService());
        assertThat(env.getProperty("local-llm.warmup.embed-model")).isEqualTo("fixture:embed");
        manager = new LocalLlmProcessManager(env, runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("embedWarmupApplies", true)
                .containsEntry("embedVerified", true);
        assertThat(runtime.embeds).isEqualTo(1);
    }

    @Test void realProfileEmptyDedicatedEnvVarDoesNotWarmForeignProvider() throws Exception {
        // An empty dedicated variable resolves to an empty value: explicit in origin
        // but no usable model, so warmup must still not fire.
        Runtime runtime = new Runtime();
        Map<String, Object> overrides = new java.util.LinkedHashMap<>();
        overrides.put("local-llm.enabled", "true");
        overrides.put("local-llm.cuda-auto-discover", "false");
        overrides.put("local-llm.log-dir", runtimeDirectory.toString());
        overrides.put("local-llm.autostart", "true");
        overrides.put("local-llm.fail-fast", "false");
        overrides.put("local-llm.ollama-host", "127.0.0.1:11435");
        overrides.put("local-llm.warmup.enabled", "true");
        overrides.put("local-llm.warmup.pull", "false");
        overrides.put("local-llm.warmup.model", "fixture:chat");
        overrides.put("local-llm.warmup.embed", "true");
        overrides.put("LOCAL_LLM_WARMUP_EMBED_MODEL", "");
        overrides.put("local-llm.health-check-interval", "1ms");
        overrides.put("embedding.provider", "openai");
        overrides.put("embedding.model", "text-embedding-3-small");
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("testOverrides", overrides));
        for (var source : new org.springframework.boot.env.YamlPropertySourceLoader().load(
                "application-llm.yaml",
                new org.springframework.core.io.FileSystemResource("main/resources/application-llm.yaml"))) {
            env.getPropertySources().addLast(source);
        }
        env.setConversionService(new ApplicationConversionService());
        assertThat(env.getProperty("local-llm.warmup.embed-model")).isEqualTo("");
        manager = new LocalLlmProcessManager(env, runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("modelReady", true)
                .containsEntry("embedWarmupApplies", false)
                .containsEntry("embedVerified", false);
        assertThat(runtime.embeds).isZero();
    }

    @Test void absentOllamaLaunchesOnceWithExactChildEnvironment() {
        Runtime runtime = new Runtime(); start(runtime);
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.launches.get(0).command()).containsExactly("ollama", "serve");
        assertThat(runtime.launches.get(0).environment()).containsEntry("OLLAMA_HOST", "127.0.0.1:11435");
        assertThat(manager.diagnostics()).containsEntry("owned", true).containsEntry("state", "READY");
    }

    @Test void unattestedExternalGpuDoesNotBecomeTheManagedServiceTarget() {
        Runtime runtime = new Runtime(); runtime.externalAtConfiguredPort = true; runtime.gpuLoaded = true;
        start(runtime, "local-llm.cuda-visible-device", Runtime.GPU_UUID);
        assertThat(runtime.launches).hasSize(1);
        String host = runtime.launches.get(0).environment().get("OLLAMA_HOST");
        assertThat(host).startsWith("127.0.0.1:").isNotEqualTo("127.0.0.1:11435");
        assertThat(runtime.lastWarmupUrl).startsWith("http://" + host + "/api/");
        assertThat(runtime.terminations).isZero();
    }

    @Test void unavailableAllocationTelemetryDoesNotRestartARespondingManagedModel() {
        Runtime runtime = new Runtime(); runtime.psEvidence = "invalid_vram";
        start(runtime, "local-llm.cuda-visible-device", Runtime.GPU_UUID);
        assertThat(manager.diagnostics()).containsEntry("modelReady", true);
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.warmups).isEqualTo(1);
        assertThat(runtime.terminations).isZero();
    }

    @Test void concurrentInitializationStartsOnlyOneProcess() throws Exception {
        Runtime runtime = new Runtime(); manager = manager(runtime);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> manager.postProcessBeanFactory(new DefaultListableBeanFactory())));
            for (var future : futures) future.get(3, TimeUnit.SECONDS);
            assertThat(runtime.launches).hasSize(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void anotherApplicationReusesRegisteredEndpointByPidAndStartIdentity() {
        Runtime runtime = new Runtime(); runtime.externalAtConfiguredPort = true; runtime.startIdentity = 1000;
        start(runtime, "local-llm.cuda-visible-device", Runtime.GPU_UUID);
        String first = manager.resolveServiceUrl("http://127.0.0.1:11435/api/chat");
        var second = manager(runtime, "local-llm.cuda-visible-device", Runtime.GPU_UUID);
        try {
            second.postProcessBeanFactory(new DefaultListableBeanFactory());
            assertThat(runtime.launches).hasSize(1);
            assertThat(second.resolveServiceUrl("http://127.0.0.1:11435/api/chat")).isEqualTo(first);
            assertThat(second.diagnostics()).containsEntry("owned", false).containsEntry("serverStartedAtEpochMs", 1000L);
        } finally { second.stop(); }
        assertThat(runtime.terminations).isZero();
    }

    @Test void restartedExternalServerCannotReusePreviousWarmupEvidence() {
        Runtime runtime = new Runtime(); runtime.server = true; runtime.startIdentity = 1000;
        start(runtime);
        assertThat(manager.isAvailable("http://127.0.0.1:11435/v1")).isTrue();
        runtime.startIdentity = 2000;
        assertThat(manager.isAvailable("http://127.0.0.1:11435/v1")).isFalse();
        assertThat(manager.diagnostics()).containsEntry("modelReady", false)
                .containsEntry("warmupVerifiedAtEpochMs", -1L).containsEntry("gpuRoleVerified", false);
        runtime.now += 60_000; runtime.scheduled.removeFirst().run();
        assertThat(manager.diagnostics()).containsEntry("serverStartedAtEpochMs", 2000L).containsEntry("modelReady", true);
        assertThat(runtime.warmups).isEqualTo(2);
        assertThat(runtime.launches).isEmpty(); assertThat(runtime.terminations).isZero();
    }

    @Test void nonOllamaListenerIsPortConflictWithoutTermination() {
        Runtime runtime = new Runtime(); runtime.conflict = true; start(runtime);
        assertThat(manager.diagnostics()).containsEntry("reasonCode", "PORT_CONFLICT");
        assertThat(runtime.launches).isEmpty(); assertThat(runtime.terminations).isZero();
    }

    @Test void missingModelNeverPullsOrClaimsReady() {
        Runtime runtime = new Runtime(); runtime.server = true; runtime.present = false; start(runtime);
        assertThat(manager.diagnostics()).containsEntry("reasonCode", "MODEL_NOT_FOUND").containsEntry("modelReady", false);
        assertThat(runtime.warmups).isZero(); assertThat(runtime.pulls).isZero();
        assertThat(manager.isAvailable("http://127.0.0.1:11435/v1")).isFalse();
    }

    @Test void transientWarmupRetriesOnceThenBecomesReady() {
        Runtime runtime = new Runtime(); runtime.server = true; runtime.failures.add("timed out"); start(runtime);
        assertThat(runtime.warmups).isEqualTo(2);
        assertThat(manager.diagnostics()).containsEntry("state", "READY");
        assertThat(runtime.launches).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"INSUFFICIENT_MEMORY,false", "INSUFFICIENT_MEMORY,true",
            "model requires more system memory,false", "model requires more system memory,true",
            "CUDA out of memory,false", "CUDA out of memory,true",
            "insufficient memory,false", "insufficient memory,true",
            "CUDA error: out of memory,false", "CUDA error: out of memory,true",
            "cudaMalloc failed: out of memory,false", "cudaMalloc failed: out of memory,true"})
    void memoryExhaustionWaitsForCooldownWithoutImmediateRetryOrRestart(String failure, boolean external) {
        Runtime runtime = new Runtime(); runtime.server = external; runtime.alwaysFail = failure;
        start(runtime);
        assertThat(runtime.warmups).isEqualTo(1);
        assertThat(runtime.launches).hasSize(external ? 0 : 1);
        assertThat(runtime.terminations).isZero();
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN")
                .containsEntry("reasonCode", "INSUFFICIENT_MEMORY").containsEntry("modelReady", false);
        assertThat(manager.isAvailable("http://127.0.0.1:11435/v1")).isFalse();
        assertThat(manager.isAvailable("http://127.0.0.1:11434/v1")).isTrue();
        runtime.now += 60_000; runtime.scheduled.removeFirst().run();
        assertThat(runtime.warmups).isEqualTo(2);
        assertThat(runtime.launches).hasSize(external ? 0 : 1);
        assertThat(runtime.terminations).isZero();
        runtime.alwaysFail = null; runtime.now += 60_000;
        runtime.scheduled.removeFirst().run();
        assertThat(runtime.warmups).isEqualTo(3);
        assertThat(manager.diagnostics()).containsEntry("state", "READY").containsEntry("modelReady", true);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            manager.bindTo(registry);
            assertThat(registry.get("local.llm.recovery.failure").tag("reason", "INSUFFICIENT_MEMORY")
                    .functionCounter().count()).isEqualTo(2);
            assertThat(registry.get("local.llm.recovery.success").tag("reason", "INSUFFICIENT_MEMORY")
                    .functionCounter().count()).isEqualTo(1);
        } finally { registry.close(); }
    }

    @Test void reportedMemoryFailurePreservesOwnedServerAndAllowsLaterRecovery() {
        Runtime runtime = new Runtime(); start(runtime);
        manager.requestRecovery("INSUFFICIENT_MEMORY");
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.terminations).isZero();
        assertThat(runtime.warmups).isEqualTo(1);
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN")
                .containsEntry("reasonCode", "INSUFFICIENT_MEMORY").containsEntry("modelReady", false);
        runtime.now += 60_000; runtime.scheduled.removeFirst().run();
        assertThat(runtime.warmups).isEqualTo(2);
        assertThat(manager.diagnostics()).containsEntry("state", "READY");
    }

    @Test void nestedMemoryFailureIsNotHiddenByGenericCudaWrapper() {
        Runtime runtime = new Runtime();
        runtime.nestedFailure = new IOException("CUDA error", new IOException("cudaMalloc failed: out of memory"));
        start(runtime);
        assertThat(runtime.warmups).isEqualTo(1);
        assertThat(runtime.terminations).isZero();
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN")
                .containsEntry("reasonCode", "INSUFFICIENT_MEMORY");
    }

    @Test void deviceLossRemainsDecisiveWhenMemoryErrorIsAlsoPresent() {
        Runtime runtime = new Runtime(); runtime.alwaysFail = "GPU is lost; insufficient memory";
        start(runtime);
        assertThat(manager.diagnostics()).containsEntry("reasonCode", "GPU_UNAVAILABLE");
        assertThat(runtime.launches).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"startup", "restart", "half-open"})
    void lateMemoryFailureAtReadyBoundaryIsDrainedAfterInFlightWork(String phase) {
        Runtime runtime = new Runtime();
        if ("startup".equals(phase)) runtime.readyFailure = "INSUFFICIENT_MEMORY";
        if ("half-open".equals(phase)) runtime.alwaysFail = "INSUFFICIENT_MEMORY";
        start(runtime);
        if ("restart".equals(phase)) {
            runtime.readyFailure = "INSUFFICIENT_MEMORY";
            manager.requestRecovery("RUNNER_EXITED");
        } else if ("half-open".equals(phase)) {
            runtime.alwaysFail = null; runtime.readyFailure = "INSUFFICIENT_MEMORY";
            runtime.now += 60_000; runtime.scheduled.removeFirst().run();
        }
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN")
                .containsEntry("reasonCode", "INSUFFICIENT_MEMORY").containsEntry("modelReady", false);
        assertThat(runtime.scheduled).hasSize(1);
        assertThat(runtime.launches).hasSize("restart".equals(phase) ? 2 : 1);
        runtime.now += 60_000; runtime.scheduled.removeFirst().run();
        assertThat(manager.diagnostics()).containsEntry("state", "READY").containsEntry("modelReady", true);
        assertThat(runtime.scheduled).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"timed out,MODEL_LOAD_TIMEOUT", "MODEL_LOAD_FAILED failed status=503,MODEL_LOAD_FAILED",
            "llama-server process has terminated: synthetic exit status 1,RUNNER_EXITED",
            "llama-server process no longer running: synthetic exit status 1,RUNNER_EXITED",
            "llama-server process has not terminated,MODEL_LOAD_FAILED",
            "llama-server request terminated,MODEL_LOAD_FAILED",
            "llama-server memory not available,MODEL_LOAD_FAILED",
            "not an insufficient memory failure,MODEL_LOAD_FAILED",
            "insufficient_memory=false,MODEL_LOAD_FAILED", "not out of memory,MODEL_LOAD_FAILED"})
    void persistentWarmupFailureRestartsOwnedServerOnceThenOnlyHalfOpens(String failure, String reason) {
        Runtime runtime = new Runtime(); runtime.alwaysFail = failure; start(runtime);
        assertThat(runtime.warmups).isEqualTo(4);
        assertThat(runtime.launches).hasSize(2);
        assertThat(runtime.terminations).isEqualTo(1);
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN")
                .containsEntry("reasonCode", reason).containsEntry("modelReady", false).containsEntry("attempt", 1);
        assertThat(manager.isAvailable("http://127.0.0.1:11435/v1")).isFalse();
        for (int i = 0; i < 5; i++) manager.requestRecovery(reason);
        assertThat(runtime.scheduled).hasSize(1);
        runtime.now += 60_000; runtime.scheduled.removeFirst().run();
        assertThat(runtime.warmups).isEqualTo(5);
        assertThat(runtime.launches).hasSize(2);
        assertThat(runtime.terminations).isEqualTo(1);
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN").containsEntry("reasonCode", reason);
        assertThat(runtime.scheduled).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"timed out,MODEL_LOAD_TIMEOUT", "MODEL_LOAD_FAILED failed status=503,MODEL_LOAD_FAILED",
            "llama-server process has terminated: synthetic exit status 1,RUNNER_EXITED",
            "llama-server process no longer running: synthetic exit status 1,RUNNER_EXITED",
            "llama-server process has not terminated,MODEL_LOAD_FAILED",
            "llama-server request terminated,MODEL_LOAD_FAILED",
            "llama-server memory not available,MODEL_LOAD_FAILED",
            "not an insufficient memory failure,MODEL_LOAD_FAILED",
            "insufficient_memory=false,MODEL_LOAD_FAILED", "not out of memory,MODEL_LOAD_FAILED"})
    void persistentWarmupFailureNeverRestartsExternalServer(String failure, String reason) {
        Runtime runtime = new Runtime(); runtime.server = true; runtime.alwaysFail = failure; start(runtime);
        assertThat(runtime.warmups).isEqualTo(2);
        assertThat(runtime.launches).isEmpty(); assertThat(runtime.terminations).isZero();
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN")
                .containsEntry("reasonCode", reason).containsEntry("modelReady", false).containsEntry("owned", false);
        assertThat(manager.isAvailable("http://127.0.0.1:11435/v1")).isFalse();
        runtime.now += 60_000; runtime.scheduled.removeFirst().run();
        assertThat(runtime.warmups).isEqualTo(3);
        assertThat(runtime.launches).isEmpty(); assertThat(runtime.terminations).isZero();
        manager.stop();
        assertThat(runtime.terminations).isZero();
    }

    @Test void repeatedGpuLossRestartsOwnedProcessOnceThenOpensCircuit() {
        Runtime runtime = new Runtime(); runtime.alwaysFail = "GPU is lost"; start(runtime);
        assertThat(runtime.launches).hasSize(2);
        assertThat(runtime.terminations).isEqualTo(1);
        assertThat(runtime.warmups).isEqualTo(4);
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN")
                .containsEntry("reasonCode", "GPU_UNAVAILABLE").containsEntry("modelReady", false);
        for (int i = 0; i < 10; i++) manager.requestRecovery("GPU_UNAVAILABLE");
        assertThat(runtime.launches).hasSize(2);
        assertThat(manager.isAvailable("http://127.0.0.1:11435/v1")).isFalse();
        assertThat(manager.isAvailable("http://127.0.0.1:11434/v1")).isTrue();
    }

    @Test void externalGpuLossRetriesWarmupButNeverRestartsServer() {
        Runtime runtime = new Runtime(); runtime.server = true; runtime.alwaysFail = "GPU is lost"; start(runtime);
        assertThat(runtime.launches).isEmpty(); assertThat(runtime.terminations).isZero();
        assertThat(runtime.warmups).isEqualTo(2);
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN");
    }

    @Test void respondingCpuModelDoesNotClaimGpuRoleOrTriggerRestart() {
        Runtime runtime = new Runtime(); runtime.cpuOnly = true;
        start(runtime, "local-llm.cuda-visible-device", "GPU-11111111-1111-1111-1111-111111111111");
        assertThat(manager.diagnostics()).containsEntry("state", "READY").containsEntry("modelReady", true)
                .containsEntry("gpuRoleVerified", false).containsEntry("exclusivePlacementVerified", false);
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.scheduled).isEmpty();
        assertThat(runtime.terminations).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "empty", "other_model", "missing_vram", "invalid_vram"})
    void missingMatchingAllocationEvidenceDoesNotRejectAResponseOrProveGpuRole(String evidence) {
        Runtime runtime = new Runtime(); runtime.externalAtConfiguredPort = true; runtime.psEvidence = evidence;
        start(runtime, "local-llm.cuda-visible-device", Runtime.GPU_UUID);
        assertThat(manager.diagnostics()).containsEntry("modelReady", true)
                .containsEntry("state", "READY").containsEntry("gpuRoleVerified", false);
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.scheduled).isEmpty();
        assertThat(runtime.terminations).isZero();
        assertThat(manager.isAvailable("http://127.0.0.1:11434/v1")).isTrue();
    }

    @Test void inheritedGpuSelectionCannotTurnCpuResponseIntoGpuProof() {
        Runtime runtime = new Runtime(); runtime.externalAtConfiguredPort = true; runtime.cpuOnly = true;
        start(runtime, "CUDA_VISIBLE_DEVICES", Runtime.GPU_UUID);
        assertThat(manager.diagnostics()).containsEntry("modelReady", true)
                .containsEntry("state", "READY").containsEntry("gpuRoleVerified", false);
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.scheduled).isEmpty();
        assertThat(runtime.terminations).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"local-llm.cuda-visible-device", "CUDA_VISIBLE_DEVICES"})
    void matchingAllocationAndObservedServerUuidVerifyExplicitOrInheritedPin(String pinProperty) {
        Runtime runtime = new Runtime();
        runtime.gpuLoaded = true;
        runtime.observedServerGpuUuids = Set.of(Runtime.GPU_UUID);
        start(runtime, pinProperty, Runtime.GPU_UUID);
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("modelReady", true).containsEntry("gpuAllocationObserved", true)
                .containsEntry("gpuRoleVerified", true).containsEntry("exclusivePlacementVerified", true);
        assertThat(TraceStore.get("localLlm.model.running")).isEqualTo(true);
        assertThat(TraceStore.get("localLlm.model.gpuAllocationObserved")).isEqualTo(true);
        assertThat(TraceStore.get("localLlm.model.gpuAttribution")).isEqualTo("server_runner_observed");
        assertThat(TraceStore.get("localLlm.model.exclusivePlacement")).isEqualTo(true);
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.launches.get(0).environment()).containsEntry("CUDA_VISIBLE_DEVICES", Runtime.GPU_UUID);
        assertThat(runtime.scheduled).isEmpty();
        assertThat(runtime.terminations).isZero();
    }

    @Test void matchingModelAllocationOnAnotherServerGpuDoesNotVerifyInheritedPin() {
        Runtime runtime = new Runtime();
        runtime.gpuLoaded = true;
        runtime.observedServerGpuUuids = Set.of("GPU-22222222-2222-2222-2222-222222222222");
        start(runtime, "CUDA_VISIBLE_DEVICES", Runtime.GPU_UUID);
        assertThat(manager.diagnostics()).containsEntry("state", "READY")
                .containsEntry("modelReady", true).containsEntry("gpuAllocationObserved", true)
                .containsEntry("gpuRoleVerified", false).containsEntry("exclusivePlacementVerified", false);
        assertThat(TraceStore.get("localLlm.model.gpuAttribution")).isNotEqualTo("server_runner_observed");
        assertThat(runtime.terminations).isZero();
    }

    @Test void absentConfiguredUuidNeverLaunchesOrSubstitutesAnotherGpu() {
        Runtime runtime = new Runtime();
        runtime.gpuUuids = List.of("GPU-22222222-2222-2222-2222-222222222222");
        start(runtime, "local-llm.cuda-visible-device", Runtime.GPU_UUID);
        assertThat(runtime.launches).isEmpty();
        assertThat(runtime.warmups).isZero();
        assertThat(manager.diagnostics()).containsEntry("modelReady", false);
        assertThat(TraceStore.get("localLlm.startup.cudaPinReason")).isEqualTo("configured_uuid_unavailable");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredUuidSelectionSurvivesGpuEnumerationOrder(boolean reversed) {
        Runtime runtime = new Runtime(); runtime.gpuLoaded = true;
        String other = "GPU-22222222-2222-2222-2222-222222222222";
        runtime.gpuUuids = reversed ? List.of(other, Runtime.GPU_UUID) : List.of(Runtime.GPU_UUID, other);
        start(runtime, "local-llm.cuda-visible-device", Runtime.GPU_UUID);
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.launches.get(0).environment()).containsEntry("CUDA_VISIBLE_DEVICES", Runtime.GPU_UUID)
                .containsEntry("OLLAMA_VULKAN", "0");
        assertThat(manager.diagnostics()).containsEntry("modelReady", true);
    }

    @Test void onExitRecoversOwnedProcessAndStopCallbackRunsOnce() {
        Runtime runtime = new Runtime(); start(runtime);
        runtime.server = false;
        runtime.exits.get(0).complete(runtime.processes.get(0));
        assertThat(runtime.launches).hasSize(2);
        assertThat(manager.diagnostics()).containsEntry("state", "READY");
        int[] callbacks = {0}; manager.stop(() -> callbacks[0]++);
        assertThat(callbacks[0]).isEqualTo(1);
        runtime.exits.get(1).complete(runtime.processes.get(1));
        assertThat(runtime.launches).hasSize(2);
    }

    @Test void halfOpenRequiresBothServerAndModelSuccessAndDoesNotRestartAgain() {
        Runtime runtime = new Runtime(); runtime.alwaysFail = "GPU is lost"; start(runtime);
        manager.recordFallback("GPU_UNAVAILABLE");
        assertThat(manager.diagnostics()).containsEntry("fallbackUsed", true);
        runtime.now += 60_000;
        runtime.scheduled.removeFirst().run();
        assertThat(runtime.launches).hasSize(2);
        assertThat(runtime.warmups).isEqualTo(5);
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN");
        runtime.alwaysFail = null; runtime.now += 60_000;
        runtime.scheduled.removeFirst().run();
        assertThat(manager.diagnostics()).containsEntry("state", "READY").containsEntry("modelReady", true);
        assertThat(manager.diagnostics()).containsEntry("fallbackUsed", false);
    }

    @Test void healthyBindRaceAdoptsExternalServerAfterOwnChildExits() {
        Runtime runtime = new Runtime(); runtime.externalRace = true; start(runtime);
        assertThat(runtime.launches).hasSize(1);
        assertThat(runtime.terminations).isEqualTo(1);
        assertThat(manager.diagnostics()).containsEntry("state", "READY").containsEntry("owned", false);
    }

    @Test void metricsReflectObservedRestartFailureAndReadyState() {
        Runtime runtime = new Runtime(); runtime.alwaysFail = "GPU is lost"; start(runtime);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            manager.bindTo(registry);
            assertThat(registry.get("local.llm.restarts").functionCounter().count()).isEqualTo(1);
            assertThat(registry.get("local.llm.start.attempts").functionCounter().count()).isEqualTo(2);
            assertThat(registry.get("local.llm.ready").gauge().value()).isZero();
            assertThat(registry.get("local.llm.recovery.failure").tag("reason", "GPU_UNAVAILABLE")
                    .functionCounter().count()).isEqualTo(1);
            runtime.alwaysFail = null; runtime.now += 60_000;
            runtime.scheduled.removeFirst().run();
            assertThat(registry.get("local.llm.recovery.success").tag("reason", "GPU_UNAVAILABLE")
                    .functionCounter().count()).isEqualTo(1);
        } finally { registry.close(); }
    }

    @Test void repeatedRunnerCrashesRespectThreeRestartsPerFiveMinutes() {
        Runtime runtime = new Runtime(); start(runtime);
        for (int i = 0; i < 4; i++) {
            runtime.server = false;
            runtime.exits.get(i).complete(runtime.processes.get(i));
        }
        assertThat(runtime.launches).hasSize(4);
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN").containsEntry("attempt", 3);
    }

    @Test void realChildOutputIsDrainedWithoutKeepingPrivateLines() throws Exception {
        var runtime = new LocalLlmProcessManager.SystemStartupRuntime();
        var javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        var classes = java.nio.file.Path.of(LocalLlmRecoveryTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var request = new LocalLlmProcessManager.LaunchRequest(List.of(javaExecutable, "-cp", classes, NoisyChild.class.getName()),
                Map.of(), java.nio.file.Path.of("unused.out"), java.nio.file.Path.of("unused.err"));
        var launched = runtime.launch(request);
        try {
            assertThat(launched.process().waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(launched.process().exitValue()).isZero();
            var reasons = (java.util.Deque<?>) org.springframework.test.util.ReflectionTestUtils.getField(runtime, "recentReasons");
            synchronized (reasons) {
                assertThat(reasons.size()).isBetween(1, 64);
                assertThat(reasons.toString()).doesNotContain("PRIVATE_OUTPUT");
            }
        } finally {
            launched.ownedProcess().terminate(1000, 1000);
            runtime.close();
        }
    }

    @Test void realHttpModelCatalogAndEmbeddingAreNotTruncatedAtFourKilobytes() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String catalog = "{\"models\":[{\"name\":\"fixture:chat\",\"metadata\":\"" + "x".repeat(8058) + "\"}]}";
        String embedding = "{\"embeddings\":[[" + String.join(",", java.util.Collections.nCopies(1536, "0.123456")) + "]]}";
        server.createContext("/api/", exchange -> {
            exchange.getRequestBody().close();
            byte[] response = (exchange.getRequestURI().getPath().endsWith("tags") ? catalog : embedding)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        var runtime = new LocalLlmProcessManager.SystemStartupRuntime();
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(runtime.getJson(base + "/api/tags", 2000).path("models").get(0).path("name").asText())
                    .isEqualTo("fixture:chat");
            assertThat(runtime.postJson(base + "/api/embed", Map.of("model", "fixture:chat"), 2000)
                    .path("embeddings").get(0).size()).isEqualTo(1536);
        } finally { server.stop(0); runtime.close(); }
    }

    public static class NoisyChild {
        public static void main(String[] args) {
            for (int i = 0; i < 12000; i++) {
                System.out.println("PRIVATE_OUTPUT_" + "x".repeat(100));
                System.err.println("PRIVATE_OUTPUT_" + "y".repeat(100));
            }
        }
    }

    private void start(Runtime runtime, String... overrides) {
        manager = manager(runtime, overrides); manager.postProcessBeanFactory(new DefaultListableBeanFactory());
    }
    private LocalLlmProcessManager routedManager(Runtime runtime, boolean routeEnabled) {
        return manager(runtime, "llmrouter.models.managed.enabled", Boolean.toString(routeEnabled),
                "llmrouter.models.managed.name", "fixture:chat",
                "llmrouter.models.managed.base-url", "http://127.0.0.1:11435/v1",
                "llmrouter.models.managed.stage", "chat", "llmrouter.models.managed.device-role", "rtx3090");
    }
    private LocalLlmProcessManager manager(Runtime runtime, String... overrides) {
        return manager(runtime, true, overrides);
    }
    private LocalLlmProcessManager manager(Runtime runtime, boolean defaultWarmupModel, String... overrides) {
        MockEnvironment env = new MockEnvironment().withProperty("local-llm.enabled", "true")
                .withProperty("local-llm.cuda-auto-discover", "false")
                .withProperty("local-llm.log-dir", runtimeDirectory.toString())
                .withProperty("local-llm.autostart", "true").withProperty("local-llm.fail-fast", "false")
                .withProperty("local-llm.ollama-host", "127.0.0.1:11435")
                .withProperty("local-llm.warmup.enabled", "true")
                .withProperty("local-llm.warmup.embed", "false").withProperty("local-llm.warmup.pull", "false")
                .withProperty("local-llm.health-check-interval", "1ms");
        if (defaultWarmupModel) env.setProperty("local-llm.warmup.model", "fixture:chat");
        for (int i = 0; i < overrides.length; i += 2) env.setProperty(overrides[i], overrides[i + 1]);
        env.setConversionService(new ApplicationConversionService());
        routeEnvironment = env;
        return new LocalLlmProcessManager(env, runtime);
    }
    private static class Runtime implements LocalLlmProcessManager.StartupRuntime {
        static final String GPU_UUID = "GPU-11111111-1111-1111-1111-111111111111";
        List<String> gpuUuids = List.of(GPU_UUID);
        Set<String> observedServerGpuUuids = Set.of();
        public Set<String> serverGpuUuids(long launchPid, int timeoutMillis) {
            return observedServerGpuUuids;
        }
        String psEvidence;
        boolean externalAtConfiguredPort;
        String lastWarmupUrl;
        final List<LocalLlmProcessManager.LaunchRequest> launches = new ArrayList<>();
        final List<CompletableFuture<Process>> exits = new ArrayList<>();
        final List<Process> processes = new ArrayList<>();
        final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        final ArrayDeque<Runnable> pendingExecution = new ArrayDeque<>();
        boolean deferExecution;
        int executions;
        public void execute(Runnable work) {
            executions++;
            if (deferExecution) pendingExecution.addLast(work);
            else work.run();
        }
        final ArrayDeque<String> failures = new ArrayDeque<>();
        boolean server, conflict, externalRace, cpuOnly, gpuLoaded, present = true;
        String alwaysFail;
        IOException nestedFailure;
        String readyFailure;
        Runnable onWarmup = () -> { };
        java.util.function.Consumer<String> failureListener = ignored -> { };
        int probes, warmups, pulls, terminations, embeds;
        String expectedEmbedModel = "fixture:embed";
        String embedFail;
        long now;
        long startIdentity;
        public long processStartedAt(long pid) { return pid > 0 ? startIdentity : 0; }
        public LocalLlmProcessManager.ProbeResult probe(String url, int timeout) {
            probes++;
            if (externalAtConfiguredPort) {
                int port = java.net.URI.create(url).getPort();
                boolean present = port == 11435 || launches.stream().anyMatch(r -> r.environment().get("OLLAMA_HOST").endsWith(":" + port));
                return new LocalLlmProcessManager.ProbeResult(present ? LocalLlmProcessManager.ProbeState.HEALTHY
                        : LocalLlmProcessManager.ProbeState.NOT_LISTENING, present ? 200 : 0,
                        present ? port == 11435 ? 42 : 100 + launches.size() : 0, "ollama", port, "fixture");
            }
            return new LocalLlmProcessManager.ProbeResult(conflict ? LocalLlmProcessManager.ProbeState.UNHEALTHY_LISTENER
                    : server ? LocalLlmProcessManager.ProbeState.HEALTHY : LocalLlmProcessManager.ProbeState.NOT_LISTENING,
                    server ? 200 : 0, server ? launches.isEmpty() || externalRace ? 42 : 100 + launches.size() : 0, "ollama", 11435, "fixture");
        }
        public String resolveOllamaExecutable(int timeout) { return "ollama"; }
        public List<String> discoverGpuUuids(int timeout) { return gpuUuids; }
        public LocalLlmProcessManager.LaunchResult launch(LocalLlmProcessManager.LaunchRequest request) {
            launches.add(request); server = true; long pid = 100 + launches.size();
            Process process = mock(Process.class); var exit = new CompletableFuture<Process>();
            when(process.onExit()).thenReturn(exit); when(process.exitValue()).thenReturn(1);
            processes.add(process); exits.add(exit);
            if (externalRace) exit.complete(process);
            return new LocalLlmProcessManager.LaunchResult(new LocalLlmProcessManager.OwnedProcess() {
                public long pid() { return pid; }
                public LocalLlmProcessManager.TerminationResult terminate(long graceful, long forced) {
                    terminations++; server = false;
                    return new LocalLlmProcessManager.TerminationResult(LocalLlmProcessManager.TerminationStatus.CAPTURED_HANDLES_TERMINATED, false, false, 1, 0);
                }
            }, process);
        }
        public JsonNode getJson(String url, int timeout) throws IOException {
            if (url.endsWith("/api/ps") && psEvidence != null) {
                return switch (psEvidence) {
                    case "unavailable" -> throw new IOException("ps_unavailable");
                    case "empty" -> new ObjectMapper().readTree("{\"models\":[]}");
                    case "other_model" -> new ObjectMapper().readTree("{\"models\":[{\"name\":\"other:model\",\"size_vram\":100}]}");
                    case "invalid_vram" -> new ObjectMapper().readTree("{\"models\":[{\"name\":\"fixture:chat\",\"size_vram\":\"N/A\"}]}");
                    default -> new ObjectMapper().readTree("{\"models\":[{\"name\":\"fixture:chat\"}]}");
                };
            }
            if (url.endsWith("/api/ps") && (cpuOnly || gpuLoaded)) {
                return new ObjectMapper().readTree("{\"models\":[{\"name\":\"fixture:chat\",\"size_vram\":"
                        + (gpuLoaded ? "100" : "0") + "}]}");
            }
            return new ObjectMapper().readTree(present ? "{\"models\":[{\"name\":\"fixture:chat\"}]}" : "{\"models\":[]}");
        }
        public JsonNode postJson(String url, Map<String, Object> body, long timeout) throws IOException {
            lastWarmupUrl = url;
            if (url.endsWith("/api/pull")) { pulls++; throw new AssertionError("unexpected pull"); }
            warmups++;
            onWarmup.run();
            if (nestedFailure != null) throw nestedFailure;
            String failure = failures.isEmpty() ? alwaysFail : failures.removeFirst();
            if (failure != null) throw new IOException(failure);
            if (url.endsWith("/api/embed")) {
                embeds++;
                if (embedFail != null) throw new IOException(embedFail);
                assertThat(body).containsEntry("model", expectedEmbedModel);
                return new ObjectMapper().readTree("{\"embeddings\":[["
                        + String.join(",", java.util.Collections.nCopies(1536, "0.125")) + "]]}");
            }
            assertThat(body).containsEntry("model", "fixture:chat");
            return new ObjectMapper().readTree("{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}");
        }
        public void onFailure(java.util.function.Consumer<String> listener) { failureListener = listener; }
        public long nowMillis() {
            if (readyFailure != null && "READY".equals(TraceStore.get("localLlm.startup.state"))) {
                String reason = readyFailure; readyFailure = null;
                failureListener.accept(reason);
            }
            return now;
        }
        public void sleepMillis(long millis) { now += millis; }
        public void schedule(Runnable work, long delayMs) { scheduled.addLast(work); }
    }
}
