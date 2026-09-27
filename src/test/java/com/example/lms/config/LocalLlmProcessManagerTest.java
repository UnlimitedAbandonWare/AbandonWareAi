package com.example.lms.config;

import com.example.lms.search.TraceStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLlmProcessManagerTest {

    @Test
    void explicitOllamaServeLaunchesDirectlyWithChildEnvironment() {
        FakeStartupRuntime runtime = managedHealthyRuntime();
        LocalLlmProcessManager manager = enabledManager(runtime,
                "local-llm.start-command=ollama serve");
        try {
            manager.postProcessBeanFactory(new DefaultListableBeanFactory());
            assertThat(runtime.launchRequests).hasSize(1);
            assertThat(runtime.launchRequests.get(0).command()).containsExactly("ollama", "serve");
            assertThat(runtime.launchRequests.get(0).environment())
                    .containsEntry("OLLAMA_HOST", "127.0.0.1:11435");
        } finally {
            manager.stop();
        }
    }

    @Test
    void serverOnlyReadinessDoesNotClaimModelReadyWhenWarmupIsDisabled() {
        FakeStartupRuntime runtime = new FakeStartupRuntime();
        runtime.probes.add(healthyProbe(9123L));
        LocalLlmProcessManager manager = enabledManager(runtime);
        try {
            manager.postProcessBeanFactory(new DefaultListableBeanFactory());
            assertThat(TraceStore.get("localLlm.model.ready")).isEqualTo(false);
            assertThat(TraceStore.get("localLlm.process.owned")).isEqualTo(false);
        } finally {
            manager.stop();
        }
    }

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void generatedWindowsCommandSetsOllamaHostBeforeServe() {
        String command = LocalLlmProcessManager.generatedWindowsStartCommand("127.0.0.1:11435");

        assertEquals("start \"Ollama 127.0.0.1:11435\" cmd.exe /k \"set OLLAMA_HOST=127.0.0.1:11435&& ollama serve\"",
                command);
        assertTrue(command.contains("OLLAMA_HOST=127.0.0.1:11435"));
        assertTrue(command.contains("ollama serve"));
    }

    @Test
    void generatedWindowsCommandPinsSingleCudaUuidBeforeServe() {
        String uuid = "GPU-12345678-1234-1234-1234-123456789abc";

        assertEquals("start \"Ollama 127.0.0.1:11435\" cmd.exe /k \"set \"OLLAMA_HOST=127.0.0.1:11435\"&& set \"CUDA_VISIBLE_DEVICES=GPU-12345678-1234-1234-1234-123456789abc\"&& set \"OLLAMA_VULKAN=0\"&& ollama serve\"",
                LocalLlmProcessManager.generatedWindowsStartCommand("127.0.0.1:11435", uuid));
    }

    @Test
    void normalizesCudaVisibleDeviceAtThePackageBoundary() {
        String uuid = "GPU-12345678-1234-1234-1234-123456789abc";
        String invalid = "GPU-12345678-1234-1234-1234-123456789abc & calc";

        assertThat(LocalLlmProcessManager.normalizeCudaVisibleDevice(null)).isNull();
        assertThat(LocalLlmProcessManager.normalizeCudaVisibleDevice(" \t\n ")).isNull();
        assertThat(LocalLlmProcessManager.normalizeCudaVisibleDevice(" \t" + uuid + "\n ")).isEqualTo(uuid);
        Throwable thrown = catchThrowable(() -> LocalLlmProcessManager.normalizeCudaVisibleDevice(invalid));
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_cuda_visible_device");
        assertThat(String.valueOf(thrown)).doesNotContain(invalid);
    }

    @Test
    void generatedWindowsCommandDelegatesBlankCudaPinToLegacyCommandAndTrimsValidPin() {
        String host = "127.0.0.1:11435";
        String uuid = "GPU-12345678-1234-1234-1234-123456789abc";
        String legacy = LocalLlmProcessManager.generatedWindowsStartCommand(host);

        assertEquals(legacy, LocalLlmProcessManager.generatedWindowsStartCommand(host, ""));
        assertEquals(legacy, LocalLlmProcessManager.generatedWindowsStartCommand(host, " \t\n"));
        assertThat(legacy).doesNotContain("OLLAMA_VULKAN");
        assertThat(LocalLlmProcessManager.generatedWindowsStartCommand(host, "")).doesNotContain("OLLAMA_VULKAN");
        assertThat(LocalLlmProcessManager.generatedWindowsStartCommand(host, " \t\n")).doesNotContain("OLLAMA_VULKAN");
        assertEquals("start \"Ollama 127.0.0.1:11435\" cmd.exe /k \"set \"OLLAMA_HOST=127.0.0.1:11435\"&& set \"CUDA_VISIBLE_DEVICES=GPU-12345678-1234-1234-1234-123456789abc\"&& set \"OLLAMA_VULKAN=0\"&& ollama serve\"",
                LocalLlmProcessManager.generatedWindowsStartCommand(host, " " + uuid + " "));
    }

    @Test
    void generatedWindowsCommandRejectsUnsafeCudaDeviceValuesWithoutEchoingThem() {
        List<String> invalidValues = List.of(
                "GPU-0,GPU-1",
                "0,1",
                "GPU-bad",
                "MIG-GPU-12345678-1234-1234-1234-123456789abc/1/0",
                "GPU-12345678-1234-1234-1234-123456789abc & calc",
                "GPU-12345678-1234-1234-1234-123456789abc GPU-abcdefab-cdef-cdef-cdef-abcdefabcdef");

        for (String invalid : invalidValues) {
            Throwable thrown = catchThrowable(() -> LocalLlmProcessManager.generatedWindowsStartCommand("127.0.0.1:11435", invalid));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid_cuda_visible_device");
            assertThat(String.valueOf(thrown)).doesNotContain(invalid);
        }
    }

    @Test
    void disabledStartupTracesRedactedCudaPinFacts() {
        String uuid = "GPU-12345678-1234-1234-1234-123456789abc";
        LocalLlmProcessManager manager = new LocalLlmProcessManager(new MockEnvironment()
                .withProperty("local-llm.enabled", "false")
                .withProperty("local-llm.autostart", "true")
                .withProperty("local-llm.cuda-visible-device", uuid));

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        assertEquals(Boolean.TRUE, TraceStore.get("localLlm.startup.cudaPinned"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue(uuid), TraceStore.get("localLlm.startup.cudaDeviceHash"));
        assertEquals(uuid.length(), TraceStore.get("localLlm.startup.cudaDeviceLength"));
        assertEquals("configured_uuid", TraceStore.get("localLlm.startup.cudaPinReason"));
        assertThat(TraceStore.getAll().toString()).doesNotContain(uuid);
    }

    @Test
    void effectiveStartCommandRejectsCudaPinWhenExplicitCommandIsConfigured() {
        LocalLlmProcessManager manager = new LocalLlmProcessManager(new MockEnvironment()
                .withProperty("local-llm.start-command", "ollama serve")
                .withProperty("local-llm.cuda-visible-device", "GPU-12345678-1234-1234-1234-123456789abc"));
        ReflectionTestUtils.invokeMethod(manager, "loadFromEnvironment");

        Throwable thrown = catchThrowable(() -> ReflectionTestUtils.invokeMethod(manager, "effectiveStartCommand"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("cuda_pin_conflicts_with_explicit_start_command");
        assertThat(String.valueOf(thrown)).doesNotContain("GPU-12345678-1234-1234-1234-123456789abc")
                .doesNotContain("ollama serve");
    }

    @Test
    void normalizesHttpUrlToOllamaHostEnvValue() {
        assertEquals("127.0.0.1:11435",
                LocalLlmProcessManager.normalizeOllamaHostForEnv("http://127.0.0.1:11435/api/embed"));
    }

    @Test
    void generatedWindowsCommandDropsUrlUserInfoFromOllamaHost() {
        String command = LocalLlmProcessManager.generatedWindowsStartCommand(
                "http://user:secret@127.0.0.1:11435/api/embed");

        assertTrue(command.contains("OLLAMA_HOST=127.0.0.1:11435&& ollama serve"));
        assertThat(command)
                .doesNotContain("user")
                .doesNotContain("secret")
                .doesNotContain("@");
    }

    @Test
    void generatedWindowsCommandFallsBackWhenOllamaHostContainsShellMetacharacters() {
        String command = LocalLlmProcessManager.generatedWindowsStartCommand(
                "127.0.0.1:11435 & calc");

        assertTrue(command.contains("OLLAMA_HOST=127.0.0.1:11435&& ollama serve"));
        assertThat(command).doesNotContain("calc");
    }

    @Test
    void beanFactoryPostProcessorRunsEarlyGateWhenDisabledWithoutNetworkCall() {
        LocalLlmProcessManager manager = new LocalLlmProcessManager(new MockEnvironment()
                .withProperty("local-llm.enabled", "false")
                .withProperty("local-llm.autostart", "true"));

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        assertTrue(manager.isRunning());
        assertEquals(Boolean.FALSE, TraceStore.get("localLlm.startup.enabled"));
        assertEquals(Boolean.TRUE, TraceStore.get("localLlm.startup.autostart"));
        assertEquals("skipped", TraceStore.get("localLlm.startup.status"));
        assertEquals("disabled_or_autostart_false", TraceStore.get("localLlm.startup.reason"));
        assertEquals("127.0.0.1:11435", TraceStore.get("localLlm.startup.host"));
        assertTrue(String.valueOf(TraceStore.get("localLlm.startup.hostHash")).startsWith("hash:"));
        assertEquals("127.0.0.1:11435", TraceStore.get("localLlm.startup.healthUrlHost"));
        assertTrue(String.valueOf(TraceStore.get("localLlm.startup.healthUrlHash")).startsWith("hash:"));
        assertEquals(Boolean.FALSE, TraceStore.get("localLlm.startup.cudaPinned"));
        assertEquals("", TraceStore.get("localLlm.startup.cudaDeviceHash"));
        assertEquals(0, TraceStore.get("localLlm.startup.cudaDeviceLength"));
        assertEquals("not_configured", TraceStore.get("localLlm.startup.cudaPinReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("localLlm.warmup.enabled"));
    }

    @Test
    void defaultConstructedBeanFactoryPostProcessorCanSkipWhenAutostartIsFalse() {
        LocalLlmProcessManager manager = new LocalLlmProcessManager();
        manager.setEnvironment(new MockEnvironment()
                .withProperty("local-llm.enabled", "false")
                .withProperty("local-llm.autostart", "false"));

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        assertTrue(manager.isRunning());
    }

    @Test
    void http200WithoutAnOllamaVersionIsNotHealthy() throws Exception {
        HttpServer server = jsonServer("/api/version", 200, "{}");
        try {
            LocalLlmProcessManager manager = new LocalLlmProcessManager();
            ReflectionTestUtils.setField(manager, "healthCheckUrl", endpoint(server) + "/api/version");

            Boolean healthy = ReflectionTestUtils.invokeMethod(manager, "isServiceRunning");

            assertThat(healthy).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validVersionReusesServiceWithoutLaunchingAndSecondStartIsIdempotent() {
        FakeStartupRuntime runtime = new FakeStartupRuntime();
        runtime.probes.add(healthyProbe(9123L));
        LocalLlmProcessManager manager = enabledManager(runtime);

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        manager.start();

        assertThat(runtime.launchRequests).isEmpty();
        assertThat(runtime.probeCount).isEqualTo(1);
        assertThat(TraceStore.get("localLlm.startup.status")).isEqualTo("already_healthy");
        assertThat(TraceStore.get("localLlm.startup.reused")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("localLlm.startup.launchAttempted")).isEqualTo(Boolean.FALSE);
        assertThat(TraceStore.get("localLlm.startup.portPid")).isEqualTo(9123L);
    }

    @Test
    void freePortLaunchesOnceWithHostGpuPidLogAndElapsedEvidence() {
        String uuid = "GPU-12345678-1234-1234-1234-123456789abc";
        FakeStartupRuntime runtime = new FakeStartupRuntime();
        runtime.probes.add(notListeningProbe());
        runtime.probes.add(notListeningProbe());
        runtime.probes.add(notListeningProbe());
        runtime.probes.add(healthyProbe(4812L));
        runtime.discoveredGpuUuids = List.of(uuid);
        runtime.launchPid = 4812L;
        LocalLlmProcessManager manager = enabledManager(runtime);

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        assertThat(runtime.launchRequests).hasSize(1);
        LocalLlmProcessManager.LaunchRequest request = runtime.launchRequests.get(0);
        assertThat(request.environment())
                .containsEntry("OLLAMA_HOST", "127.0.0.1:11435")
                .containsEntry("CUDA_VISIBLE_DEVICES", uuid);
        assertThat(request.command()).endsWith("serve");
        assertThat(runtime.sleeps).containsExactly(100L);
        assertThat(TraceStore.get("localLlm.startup.status")).isEqualTo("healthy");
        assertThat(TraceStore.get("localLlm.startup.launchPid")).isEqualTo(4812L);
        assertThat(TraceStore.get("localLlm.startup.healthAttemptCount")).isEqualTo(4);
        assertThat(TraceStore.get("localLlm.startup.elapsedMs")).isEqualTo(100L);
        assertThat(String.valueOf(TraceStore.get("localLlm.startup.stderrLogPathHash"))).startsWith("hash:");
        assertThat((Integer) TraceStore.get("localLlm.startup.stderrLogPathLength")).isGreaterThan(0);
        assertThat(TraceStore.get("localLlm.startup.cudaPinReason")).isEqualTo("auto_discovered_uuid");
    }

    @Test
    void stopTerminatesOnlyTheManagerOwnedLaunchExactlyOnce() {
        FakeStartupRuntime runtime = managedHealthyRuntime();
        LocalLlmProcessManager manager = enabledManager(runtime);

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        manager.stop();

        assertThat(TraceStore.get("localLlm.shutdown.status")).isEqualTo("captured_handles_terminated");
        assertThat(TraceStore.get("localLlm.shutdown.owned")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("localLlm.shutdown.pidPresent")).isEqualTo(Boolean.TRUE);
        manager.stop();

        assertThat(manager.isRunning()).isFalse();
        assertThat(runtime.ownedProcess.terminateCalls).isEqualTo(1);
        assertThat(runtime.ownedProcess.gracefulBudgets).hasSize(1);
        assertThat(runtime.ownedProcess.gracefulBudgets.get(0)).isPositive();
        assertThat(runtime.ownedProcess.forcedBudgets).hasSize(1);
        assertThat(runtime.ownedProcess.forcedBudgets.get(0)).isPositive();
        assertThat(TraceStore.get("localLlm.shutdown.status")).isEqualTo("not_owned");
        assertThat(TraceStore.get("localLlm.shutdown.owned")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void stopNeverTerminatesAReusedHealthyListenerWithAnObservationalPid() {
        FakeStartupRuntime runtime = new FakeStartupRuntime();
        runtime.probes.add(healthyProbe(9123L));
        LocalLlmProcessManager manager = enabledManager(runtime);

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        manager.stop();

        assertThat(runtime.launchRequests).isEmpty();
        assertThat(runtime.ownedProcess.terminateCalls).isZero();
        assertThat(TraceStore.get("localLlm.shutdown.status")).isEqualTo("not_owned");
        assertThat(TraceStore.get("localLlm.shutdown.owned")).isEqualTo(Boolean.FALSE);
        assertThat(TraceStore.get("localLlm.shutdown.pidPresent")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void stopCallbackRunsExactlyOnceAfterAlreadyExitedOwnedCleanup() {
        FakeStartupRuntime runtime = managedHealthyRuntime();
        runtime.ownedProcess.results.add(termination(
                LocalLlmProcessManager.TerminationStatus.ALREADY_EXITED, false, false, 1, 0));
        LocalLlmProcessManager manager = enabledManager(runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        manager.stop(() -> runtime.events.add("callback"));

        assertThat(runtime.events).containsExactly("terminate", "callback");
        assertThat(runtime.ownedProcess.terminateCalls).isEqualTo(1);
    }

    @Test
    void stillAliveOwnedCapabilityIsRetainedForOneLaterRetry() {
        FakeStartupRuntime runtime = managedHealthyRuntime();
        runtime.ownedProcess.results.add(termination(
                LocalLlmProcessManager.TerminationStatus.STILL_ALIVE, true, false, 2, 2));
        runtime.ownedProcess.results.add(termination(
                LocalLlmProcessManager.TerminationStatus.CAPTURED_HANDLES_TERMINATED,
                false, false, 2, 2));
        LocalLlmProcessManager manager = enabledManager(runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        manager.stop();
        manager.stop();

        assertThat(runtime.ownedProcess.terminateCalls).isEqualTo(2);
        assertThat(TraceStore.get("localLlm.shutdown.status")).isEqualTo("captured_handles_terminated");
        assertThat(TraceStore.get("localLlm.shutdown.aliveAfter")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void interruptedStopRestoresFlagRetainsExactCapabilityAndStillRunsCallback() {
        FakeStartupRuntime runtime = managedHealthyRuntime();
        runtime.ownedProcess.clearInterruptOnTerminate = true;
        runtime.ownedProcess.results.add(termination(
                LocalLlmProcessManager.TerminationStatus.INTERRUPTED_STILL_ALIVE,
                true, true, 2, 0));
        runtime.ownedProcess.results.add(termination(
                LocalLlmProcessManager.TerminationStatus.CAPTURED_HANDLES_TERMINATED,
                false, false, 2, 2));
        LocalLlmProcessManager manager = enabledManager(runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        int[] callbacks = {0};

        try {
            Thread.currentThread().interrupt();
            manager.stop(() -> callbacks[0]++);

            assertThat(callbacks[0]).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(runtime.ownedProcess.terminateCalls).isEqualTo(1);

            Thread.interrupted();
            runtime.ownedProcess.clearInterruptOnTerminate = false;
            manager.stop();
            assertThat(runtime.ownedProcess.terminateCalls).isEqualTo(2);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void nullStopCallbackIsDefensivelyIgnoredAfterOwnedCleanup() {
        FakeStartupRuntime runtime = managedHealthyRuntime();
        LocalLlmProcessManager manager = enabledManager(runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        manager.stop((Runnable) null);

        assertThat(runtime.ownedProcess.terminateCalls).isEqualTo(1);
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleStillAliveResultCannotOverwriteANewerOwnedCapability() {
        FakeStartupRuntime runtime = managedHealthyRuntime();
        LocalLlmProcessManager manager = enabledManager(runtime);
        manager.postProcessBeanFactory(new DefaultListableBeanFactory());
        FakeOwnedProcess replacement = new FakeOwnedProcess(runtime.events);
        AtomicReference<LocalLlmProcessManager.OwnedProcess> ownership =
                (AtomicReference<LocalLlmProcessManager.OwnedProcess>) ReflectionTestUtils.getField(
                        manager, "ownedProcess");
        runtime.ownedProcess.onTerminate = () -> ownership.set(replacement);
        runtime.ownedProcess.results.add(termination(
                LocalLlmProcessManager.TerminationStatus.STILL_ALIVE, true, false, 1, 1));

        manager.stop();

        assertThat(ownership).hasValue(replacement);
        manager.stop();
        assertThat(runtime.ownedProcess.terminateCalls).isEqualTo(1);
        assertThat(replacement.terminateCalls).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void launchRegistrationCollisionIsClassifiedWithoutReportingStartupSuccess() {
        FakeStartupRuntime runtime = managedHealthyRuntime();
        runtime.ownedProcess.results.add(termination(
                LocalLlmProcessManager.TerminationStatus.STILL_ALIVE, true, false, 1, 1));
        runtime.ownedProcess.results.add(termination(
                LocalLlmProcessManager.TerminationStatus.STILL_ALIVE, true, false, 1, 1));
        runtime.ownedProcess.results.add(termination(
                LocalLlmProcessManager.TerminationStatus.CAPTURED_HANDLES_TERMINATED,
                false, false, 1, 1));
        LocalLlmProcessManager manager = enabledManager(runtime);
        FakeOwnedProcess alreadyRegistered = new FakeOwnedProcess(runtime.events);
        AtomicReference<LocalLlmProcessManager.OwnedProcess> ownership =
                (AtomicReference<LocalLlmProcessManager.OwnedProcess>) ReflectionTestUtils.getField(
                        manager, "ownedProcess");
        ownership.set(alreadyRegistered);

        Throwable failure = catchThrowable(() ->
                manager.postProcessBeanFactory(new DefaultListableBeanFactory()));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(manager.isRunning()).isTrue();
        assertThat(manager.diagnostics()).containsEntry("state", "COOLDOWN").containsEntry("modelReady", false);
        assertThat(runtime.ownedProcess.terminateCalls).isEqualTo(2);
        assertThat(alreadyRegistered.terminateCalls).isEqualTo(1);
        assertThat(TraceStore.get("localLlm.shutdown.registrationCollision"))
                .isEqualTo("still_alive");
        assertThat(TraceStore.get("localLlm.shutdown.registrationCollisionRetained"))
                .isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("localLlm.startup.status")).isEqualTo("failed");

        manager.stop();

        assertThat(runtime.ownedProcess.terminateCalls).isEqualTo(3);
        assertThat(alreadyRegistered.terminateCalls).isEqualTo(1);
        assertThat(TraceStore.get("localLlm.shutdown.status"))
                .isEqualTo("captured_handles_terminated");
    }

    @Test
    void missingWarmupModelKeepsApplicationAliveAndPreservesExternalOwnership() throws Exception {
        HttpServer server = jsonServer("/api/pull", 500, "{\"error\":\"synthetic\"}");
        try {
            String host = endpoint(server);
            FakeStartupRuntime managedRuntime = managedHealthyRuntime();
            LocalLlmProcessManager managed = enabledManager(managedRuntime,
                    "local-llm.ollama-host=" + host,
                    "local-llm.warmup.enabled=true",
                    "local-llm.warmup.pull=true",
                    "local-llm.warmup.show=false",
                    "local-llm.warmup.embed=false");

            Throwable managedFailure = catchThrowable(() ->
                    managed.postProcessBeanFactory(new DefaultListableBeanFactory()));

            assertThat(managedFailure).isNull();
            assertThat(managed.diagnostics()).containsEntry("reasonCode", "MODEL_NOT_FOUND");
            assertThat(managedRuntime.ownedProcess.terminateCalls).isZero();
            managed.stop();
            assertThat(managedRuntime.ownedProcess.terminateCalls).isEqualTo(1);

            FakeStartupRuntime reusedRuntime = new FakeStartupRuntime();
            reusedRuntime.probes.add(healthyProbe(9123L));
            LocalLlmProcessManager reused = enabledManager(reusedRuntime,
                    "local-llm.ollama-host=" + host,
                    "local-llm.warmup.enabled=true",
                    "local-llm.warmup.pull=true",
                    "local-llm.warmup.show=false",
                    "local-llm.warmup.embed=false");

            Throwable reusedFailure = catchThrowable(() ->
                    reused.postProcessBeanFactory(new DefaultListableBeanFactory()));

            assertThat(reusedFailure).isNull();
            assertThat(reused.diagnostics()).containsEntry("reasonCode", "MODEL_NOT_FOUND");
            reused.stop();
            assertThat(reusedRuntime.launchRequests).isEmpty();
            assertThat(reusedRuntime.ownedProcess.terminateCalls).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void degradedModelKeepsLifecycleRunningUntilOwnedCleanup() throws Exception {
        HttpServer server = jsonServer("/api/pull", 500, "{\"error\":\"synthetic\"}");
        try {
            String host = endpoint(server);
            FakeStartupRuntime managedRuntime = managedHealthyRuntime();
            LocalLlmProcessManager managed = enabledManager(managedRuntime,
                    "local-llm.fail-fast=false",
                    "local-llm.ollama-host=" + host,
                    "local-llm.warmup.enabled=true",
                    "local-llm.warmup.pull=true",
                    "local-llm.warmup.show=false",
                    "local-llm.warmup.embed=false");

            Throwable managedFailure = catchThrowable(() ->
                    managed.postProcessBeanFactory(new DefaultListableBeanFactory()));

            assertThat(managedFailure).isNull();
            assertThat(managed.isRunning()).isTrue();
            assertThat(managed.diagnostics()).containsEntry("state", "COOLDOWN").containsEntry("modelReady", false);
            managed.stop();
            assertThat(managedRuntime.ownedProcess.terminateCalls).isEqualTo(1);
            assertThat(TraceStore.get("localLlm.shutdown.status"))
                    .isEqualTo("captured_handles_terminated");

            TraceStore.clear();
            FakeStartupRuntime reusedRuntime = new FakeStartupRuntime();
            reusedRuntime.probes.add(healthyProbe(9123L));
            LocalLlmProcessManager reused = enabledManager(reusedRuntime,
                    "local-llm.fail-fast=false",
                    "local-llm.ollama-host=" + host,
                    "local-llm.warmup.enabled=true",
                    "local-llm.warmup.pull=true",
                    "local-llm.warmup.show=false",
                    "local-llm.warmup.embed=false");

            Throwable reusedFailure = catchThrowable(() ->
                    reused.postProcessBeanFactory(new DefaultListableBeanFactory()));

            assertThat(reusedFailure).isNull();
            assertThat(reused.isRunning()).isTrue();
            assertThat(reused.diagnostics()).containsEntry("state", "COOLDOWN").containsEntry("modelReady", false);
            reused.stop();
            assertThat(reusedRuntime.launchRequests).isEmpty();
            assertThat(reusedRuntime.ownedProcess.terminateCalls).isZero();
            assertThat(TraceStore.get("localLlm.shutdown.status")).isEqualTo("not_owned");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cudaUuidPinDisablesVulkanBackendForManagedLaunch() {
        String uuid = "GPU-12345678-1234-1234-1234-123456789abc";
        FakeStartupRuntime runtime = new FakeStartupRuntime();
        runtime.probes.add(notListeningProbe());
        runtime.probes.add(notListeningProbe());
        runtime.probes.add(healthyProbe(4812L));
        runtime.discoveredGpuUuids = List.of(uuid);
        LocalLlmProcessManager manager = enabledManager(runtime);

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        assertThat(runtime.launchRequests).hasSize(1);
        assertThat(runtime.launchRequests.get(0).environment())
                .containsEntry("CUDA_VISIBLE_DEVICES", uuid)
                .containsEntry("OLLAMA_VULKAN", "0");
    }

    @Test
    void occupiedUnhealthyListenerFailsWithoutLaunchingOrKilling() {
        FakeStartupRuntime runtime = new FakeStartupRuntime();
        runtime.probes.add(new LocalLlmProcessManager.ProbeResult(
                LocalLlmProcessManager.ProbeState.UNHEALTHY_LISTENER,
                200,
                7007L,
                "foreign.exe",
                11435,
                "version_invalid"));
        LocalLlmProcessManager manager = enabledManager(runtime);

        Throwable thrown = catchThrowable(() -> manager.postProcessBeanFactory(new DefaultListableBeanFactory()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(runtime.launchRequests).isEmpty();
        assertThat(TraceStore.get("localLlm.startup.status")).isEqualTo("failed");
        assertThat(TraceStore.get("localLlm.startup.reason")).isEqualTo("unhealthy_listener_occupied");
        assertThat(TraceStore.get("localLlm.startup.portPid")).isEqualTo(7007L);
        assertThat(TraceStore.get("localLlm.startup.portProcessName")).isEqualTo("foreign.exe");
        assertThat(manager.isRunning()).isTrue();
        assertThat(manager.diagnostics()).containsEntry("reasonCode", "PORT_CONFLICT");
    }

    @Test
    void missingExecutableFailsBeforeLaunchWithFixedReasonAndNoPid() {
        FakeStartupRuntime runtime = new FakeStartupRuntime();
        runtime.probes.add(notListeningProbe());
        runtime.executable = null;
        LocalLlmProcessManager manager = enabledManager(runtime);

        Throwable thrown = catchThrowable(() -> manager.postProcessBeanFactory(new DefaultListableBeanFactory()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(runtime.launchRequests).isEmpty();
        assertThat(TraceStore.get("localLlm.startup.reason")).isEqualTo("start_executable_not_found");
        assertThat(TraceStore.get("localLlm.startup.launchPid")).isEqualTo(0L);
    }

    @Test
    void healthPollingStopsAtTheDeadlineWithoutOversleeping() {
        FakeStartupRuntime runtime = new FakeStartupRuntime();
        runtime.probes.add(notListeningProbe());
        runtime.probes.add(notListeningProbe());
        runtime.launchPid = 4812L;
        LocalLlmProcessManager manager = enabledManager(runtime,
                "local-llm.health-check-timeout=250ms",
                "local-llm.health-check-interval=100ms");

        Throwable thrown = catchThrowable(() -> manager.postProcessBeanFactory(new DefaultListableBeanFactory()));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(runtime.launchRequests).hasSize(1);
        assertThat(runtime.sleeps).containsExactly(100L, 100L, 50L);
        assertThat(runtime.nowMillis).isEqualTo(250L);
        assertThat(runtime.probeCount).isEqualTo(5);
        assertThat(TraceStore.get("localLlm.startup.reason")).isEqualTo("health_timeout");
        assertThat(runtime.ownedProcess.terminateCalls).isEqualTo(1);
        assertThat(TraceStore.get("localLlm.shutdown.reason")).isEqualTo("startup_failure");
    }

    @Test
    void multipleGpuUuidsAreNotGuessed() {
        FakeStartupRuntime runtime = new FakeStartupRuntime();
        runtime.probes.add(notListeningProbe());
        runtime.probes.add(notListeningProbe());
        runtime.probes.add(healthyProbe(4812L));
        runtime.discoveredGpuUuids = List.of(
                "GPU-12345678-1234-1234-1234-123456789abc",
                "GPU-abcdefab-cdef-cdef-cdef-abcdefabcdef");
        runtime.launchPid = 4812L;
        LocalLlmProcessManager manager = enabledManager(runtime);

        manager.postProcessBeanFactory(new DefaultListableBeanFactory());

        assertThat(runtime.launchRequests).hasSize(1);
        assertThat(runtime.launchRequests.get(0).environment())
                .doesNotContainKeys("CUDA_VISIBLE_DEVICES", "OLLAMA_VULKAN");
        assertThat(TraceStore.get("localLlm.startup.cudaPinReason")).isEqualTo("auto_discovery_ambiguous");
    }

    @Test
    void processTreeAdapterRefreshesExactHandleUnionBeforeForcedTermination() {
        List<String> events = new ArrayList<>();
        FakeExactProcessHandle root = new FakeExactProcessHandle("root", 41L, events);
        FakeExactProcessHandle firstChild = new FakeExactProcessHandle("child-1", 42L, events);
        FakeExactProcessHandle lateChild = new FakeExactProcessHandle("child-2", 43L, events);
        root.descendantSnapshots.add(List.of(firstChild));
        root.descendantSnapshots.add(List.of(firstChild, lateChild));
        FakeProcessTreeOps ops = new FakeProcessTreeOps(root);
        LocalLlmProcessManager.OwnedProcess owned = new LocalLlmProcessManager.ProcessTreeOwnedProcess(
                root, ops, false);

        LocalLlmProcessManager.TerminationResult result = owned.terminate(5L, 3L);

        assertThat(result.status()).isEqualTo(
                LocalLlmProcessManager.TerminationStatus.CAPTURED_HANDLES_TERMINATED);
        assertThat(result.aliveAfter()).isFalse();
        assertThat(result.capturedHandleCount()).isEqualTo(3);
        assertThat(result.forcedHandleCount()).isEqualTo(3);
        assertThat(root.descendantsCalls).isEqualTo(2);
        assertThat(events).containsExactly(
                "graceful:child-1", "graceful:root",
                "forced:child-1", "forced:child-2", "forced:root");
        assertThat(ops.sleepBudgets).allMatch(value -> value > 0L && value <= 5L);
    }

    @Test
    void processTreeAdapterStopsBeforeSignalsWhenShutdownThreadIsAlreadyInterrupted() {
        List<String> events = new ArrayList<>();
        FakeExactProcessHandle root = new FakeExactProcessHandle("root", 51L, events);
        FakeProcessTreeOps ops = new FakeProcessTreeOps(root);
        LocalLlmProcessManager.OwnedProcess owned = new LocalLlmProcessManager.ProcessTreeOwnedProcess(
                root, ops, false);

        try {
            Thread.currentThread().interrupt();
            LocalLlmProcessManager.TerminationResult result = owned.terminate(5L, 3L);

            assertThat(result.status()).isEqualTo(
                    LocalLlmProcessManager.TerminationStatus.INTERRUPTED_STILL_ALIVE);
            assertThat(result.interrupted()).isTrue();
            assertThat(result.aliveAfter()).isTrue();
            assertThat(events).isEmpty();
            assertThat(ops.sleepBudgets).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void processTreeAdapterUsesOverflowSafeWaitBudgetAcrossNegativeNanoTime() {
        List<String> events = new ArrayList<>();
        FakeExactProcessHandle root = new FakeExactProcessHandle("root", 56L, events);
        FakeProcessTreeOps ops = new FakeProcessTreeOps(root);
        ops.nowNanos = Long.MIN_VALUE + 17L;
        ops.onSleep = () -> root.alive = false;
        LocalLlmProcessManager.OwnedProcess owned = new LocalLlmProcessManager.ProcessTreeOwnedProcess(
                root, ops, false);

        LocalLlmProcessManager.TerminationResult result = owned.terminate(Long.MAX_VALUE, 0L);

        assertThat(result.status()).isEqualTo(
                LocalLlmProcessManager.TerminationStatus.CAPTURED_HANDLES_TERMINATED);
        assertThat(result.aliveAfter()).isFalse();
        assertThat(ops.sleepBudgets).containsExactly(25L);
    }

    @Test
    void shellBackedExactGraphReportsTreeScopeIncompleteWithoutOverclaiming() {
        List<String> events = new ArrayList<>();
        FakeExactProcessHandle root = new FakeExactProcessHandle("shell-root", 57L, events);
        root.stopOnGraceful = true;
        FakeProcessTreeOps ops = new FakeProcessTreeOps(root);
        LocalLlmProcessManager.OwnedProcess owned = new LocalLlmProcessManager.ProcessTreeOwnedProcess(
                root, ops, true);

        LocalLlmProcessManager.TerminationResult result = owned.terminate(5L, 3L);

        assertThat(result.status()).isEqualTo(
                LocalLlmProcessManager.TerminationStatus.TREE_SCOPE_INCOMPLETE);
        assertThat(result.aliveAfter()).isFalse();
        assertThat(events).containsExactly("graceful:shell-root");
    }

    @Test
    void launchTransferFailureRollsBackOnlyTheExactSyntheticProcess() {
        List<String> events = new ArrayList<>();
        FakeExactProcessHandle root = new FakeExactProcessHandle("synthetic-root", 61L, events);
        root.stopOnGraceful = true;
        FakeProcessTreeOps ops = new FakeProcessTreeOps(root);
        Process synthetic = org.mockito.Mockito.mock(Process.class);
        LocalLlmProcessManager.SystemStartupRuntime runtime =
                new LocalLlmProcessManager.SystemStartupRuntime(
                        ignored -> synthetic,
                        (ignored, ignoredOps, ignoredScope) -> {
                            throw new IllegalStateException("synthetic_transfer_failure");
                        },
                        ops);
        LocalLlmProcessManager.LaunchRequest request = new LocalLlmProcessManager.LaunchRequest(
                List.of("ollama", "serve"), Map.of(),
                Path.of("build", "owned-transfer.out"),
                Path.of("build", "owned-transfer.err"));

        Throwable thrown = catchThrowable(() -> runtime.launch(request));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic_transfer_failure");
        assertThat(ops.rootCalls).isEqualTo(1);
        assertThat(events).containsExactly("graceful:synthetic-root");
    }

    @ParameterizedTest
    @CsvSource({"llama-server process has terminated: synthetic exit status 1,RUNNER_EXITED",
            "GPU is lost,GPU_UNAVAILABLE", "INSUFFICIENT_MEMORY,INSUFFICIENT_MEMORY",
            "cudaMalloc failed: out of memory,INSUFFICIENT_MEMORY",
            "not an insufficient memory failure,NONE", "insufficient_memory=false,NONE"})
    void childOutputFailureNotifiesExistingRecoveryListener(String message, String expectedReason) throws Exception {
        var drained = new java.util.concurrent.CountDownLatch(1);
        var reasons = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var output = new java.io.ByteArrayInputStream(
                message.getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException {
                super.close();
                drained.countDown();
            }
        };
        Process synthetic = org.mockito.Mockito.mock(Process.class);
        org.mockito.Mockito.when(synthetic.getInputStream()).thenReturn(output);
        org.mockito.Mockito.when(synthetic.isAlive()).thenReturn(true);
        List<String> events = new ArrayList<>();
        FakeOwnedProcess owned = new FakeOwnedProcess(events);
        LocalLlmProcessManager.SystemStartupRuntime runtime = new LocalLlmProcessManager.SystemStartupRuntime(
                ignored -> synthetic, (ignored, ignoredOps, ignoredScope) -> owned,
                new FakeProcessTreeOps(new FakeExactProcessHandle("synthetic-output", 62L, events)));
        runtime.onFailure(reasons::add);
        try {
            runtime.launch(new LocalLlmProcessManager.LaunchRequest(List.of("ollama", "serve"), Map.of(),
                    Path.of("build", "synthetic-output.out"), Path.of("build", "synthetic-output.err")));
            assertThat(drained.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            if ("NONE".equals(expectedReason)) assertThat(reasons).isEmpty();
            else assertThat(reasons).containsExactly(expectedReason);
        } finally {
            runtime.close();
        }
    }

    @Test
    void replacedChildMemoryOutputDoesNotNotifyCurrentRecoveryListener() {
        var reasons = new ArrayList<String>();
        Process original = org.mockito.Mockito.mock(Process.class);
        Process replacement = org.mockito.Mockito.mock(Process.class);
        org.mockito.Mockito.when(original.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(
                "INSUFFICIENT_MEMORY".getBytes(StandardCharsets.UTF_8)));
        org.mockito.Mockito.when(original.isAlive()).thenReturn(true);
        var runtime = new LocalLlmProcessManager.SystemStartupRuntime();
        runtime.onFailure(reasons::add);
        try {
            @SuppressWarnings("unchecked")
            var active = (AtomicReference<Process>) ReflectionTestUtils.getField(runtime, "activeOutputProcess");
            active.set(replacement);
            ReflectionTestUtils.invokeMethod(runtime, "drainProcessOutput", original);
            assertThat(reasons).isEmpty();
        } finally { runtime.close(); }
    }

    @Test
    void rollbackFailureCannotMaskTheOriginalOwnershipTransferFailure() {
        Process synthetic = org.mockito.Mockito.mock(Process.class);
        IllegalStateException transferFailure = new IllegalStateException("synthetic_transfer_failure");
        String privateRollbackDetail = "rollback_" + com.example.lms.test.SecretFixtures.openAiKey();
        LocalLlmProcessManager.ProcessTreeOps failingOps = new LocalLlmProcessManager.ProcessTreeOps() {
            @Override
            public LocalLlmProcessManager.ExactProcessHandle root(Process process) {
                throw new AssertionError(privateRollbackDetail);
            }

            @Override
            public long nanoTime() {
                return 0L;
            }

            @Override
            public void sleepMillis(long millis) {
            }
        };
        LocalLlmProcessManager.SystemStartupRuntime runtime =
                new LocalLlmProcessManager.SystemStartupRuntime(
                        ignored -> synthetic,
                        (ignored, ignoredOps, ignoredScope) -> {
                            throw transferFailure;
                        },
                        failingOps);
        LocalLlmProcessManager.LaunchRequest request = new LocalLlmProcessManager.LaunchRequest(
                List.of("ollama", "serve"), Map.of(),
                Path.of("build", "owned-transfer-error.out"),
                Path.of("build", "owned-transfer-error.err"));

        Throwable thrown = catchThrowable(() -> runtime.launch(request));

        assertThat(thrown).isSameAs(transferFailure);
        assertThat(thrown.getSuppressed()).hasSize(1);
        assertThat(String.valueOf(thrown.getSuppressed()[0]))
                .contains("owned_process_transfer_rollback_failed")
                .doesNotContain(privateRollbackDetail);
        assertThat(TraceStore.get("localLlm.shutdown.transferRollback"))
                .isEqualTo("termination_error");
    }

    @Test
    void ownedProcessSourceHasNoPidOrShellKillFallbackAndNewEvidenceIsFixedShape() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/LocalLlmProcessManager.java"),
                StandardCharsets.UTF_8);

        // Read-only process identity lookup is permitted; termination must use captured handles.
        String ownedTermination = source.substring(source.indexOf("static final class ProcessTreeOwnedProcess"),
                source.indexOf("interface StartupRuntime"));
        assertThat(ownedTermination).doesNotContain("ProcessHandle.of(");
        assertThat(source)
                .doesNotContain("taskkill")
                .doesNotContain("Stop-Process")
                .contains("localLlm.shutdown.status")
                .contains("localLlm.shutdown.reason")
                .contains("localLlm.shutdown.owned")
                .contains("localLlm.shutdown.pidPresent")
                .contains("localLlm.shutdown.capturedHandleCount")
                .contains("localLlm.shutdown.forcedHandleCount");
    }

    @Test
    void warmupPostErrorDoesNotExposeRawResponseBody() throws Exception {
        String errorBody = "{\"error\":\"failed api_key=" + com.example.lms.test.SecretFixtures.openAiKey() + "\"}";
        HttpServer server = errorServer(errorBody);
        try {
            LocalLlmProcessManager manager = new LocalLlmProcessManager();
            ReflectionTestUtils.setField(manager, "ollamaHost", endpoint(server));

            Throwable thrown = catchThrowable(() -> ReflectionTestUtils.invokeMethod(
                    manager,
                    "postJson",
                    "/api/embed",
                    Map.of("model", "test-model"),
                    1000L));

            String rendered = renderThrowable(thrown);
            assertThat(rendered)
                    .doesNotContain(errorBody)
                    .doesNotContain("" + com.example.lms.test.SecretFixtures.openAiKey() + "")
                    .contains("bodyHash=")
                    .contains("bodyLength=");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startupFailureShortErrDoesNotExposeRawThrowableTypeOrMessage() {
        String rendered = ReflectionTestUtils.invokeMethod(
                LocalLlmProcessManager.class,
                "shortErr",
                new IllegalStateException("failed ownerToken=fake-token"));

        assertThat(rendered)
                .contains("startup_failure")
                .contains("messageHash=")
                .contains("messageLength=")
                .doesNotContain("IllegalStateException")
                .doesNotContain("ownerToken=fake-token")
                .doesNotContain("failed ownerToken");
    }

    @Test
    void warmupSuccessLogsDoNotWriteRawModelIdentifiers() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/LocalLlmProcessManager.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("step=pull status=ok model={}")
                .doesNotContain("step=show status=ok model={}")
                .doesNotContain("step=embed status=ok model={}");
        assertThat(source)
                .contains("step=pull status=ok modelHash={} modelLength={}")
                .contains("step=show status=ok modelHash={} modelLength={}")
                .contains("step=embed status=ok modelHash={} modelLength={} targetDim={} returnedDim={}")
                .contains("SafeRedactor.hashValue(model)");
    }

    @Test
    void startupLogsDoNotWriteRawHealthUrlOrHostAuthority() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/LocalLlmProcessManager.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("healthUrl={} host={}")
                .doesNotContain("status=healthy healthUrl={} timeoutMs={}")
                .doesNotContain("safeUrl(effectiveHealthCheckUrl())")
                .doesNotContain("normalizeOllamaHostForEnv(ollamaHost), hasText(startCommand)")
                .doesNotContain("normalizeOllamaHostForEnv(ollamaHost), shortErr(e)")
                .doesNotContain("POST \" + safeUrl(url)");
        assertThat(source)
                .contains("healthUrlHost={} healthUrlHash={} healthUrlLength={} host={} hostHash={}")
                .contains("healthUrlHost={} healthUrlHash={} healthUrlLength={} timeoutMs={}")
                .contains("safeUrlHost(effectiveHealthCheckUrl())")
                .contains("safeUrlHash(effectiveHealthCheckUrl())")
                .contains("safeUrlLength(effectiveHealthCheckUrl())")
                .contains("safeOllamaHostForLog(ollamaHost), safeOllamaHostHash(ollamaHost), hasText(startCommand)")
                .contains("safeOllamaHostForLog(ollamaHost), safeOllamaHostHash(ollamaHost), shortErr(e)")
                .contains("safeUrlDiagnostic(url)");
    }

    @Test
    void suppressedStartupHelpersLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/LocalLlmProcessManager.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("traceSuppressed(\"ollama.start\", ex);")
                .contains("traceSuppressed(\"ollama.unexpected\", e);")
                .contains("traceSuppressed(\"ollama.isServiceRunning\", e);")
                .contains("traceSuppressed(\"ollama.warmup\", e);")
                .contains("traceSuppressed(\"ollama.hostUri\", ignore);")
                .contains("traceSuppressed(\"ollama.urlHost\", ignore);")
                .contains("TraceStore.put(\"localLlm.suppressed.\" + safeStage, true);");
    }

    @Test
    void suppressedTraceIncludesSafeAggregateStageAndErrorType() {
        String secret = com.example.lms.test.SecretFixtures.openAiKey();

        ReflectionTestUtils.invokeMethod(LocalLlmProcessManager.class,
                "traceSuppressed",
                "ollama.hostUri " + secret,
                new IllegalStateException("raw " + secret));

        Object safeStage = TraceStore.get("localLlm.suppressed.stage");
        assertThat(String.valueOf(safeStage)).startsWith("hash:");
        assertEquals(Boolean.TRUE, TraceStore.get("localLlm.suppressed." + safeStage));
        assertEquals("local_llm_suppressed", TraceStore.get("localLlm.suppressed.errorType"));
        assertEquals("local_llm_suppressed",
                TraceStore.get("localLlm.suppressed." + safeStage + ".errorType"));
        assertThat(TraceStore.getAll().toString()).doesNotContain(secret);
    }

    private static HttpServer errorServer(String body) throws IOException {
        return jsonServer("/api/embed", 500, body);
    }

    private static HttpServer jsonServer(String path, int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String endpoint(HttpServer server) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    private static String renderThrowable(Throwable thrown) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            out.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return out.toString();
    }

    private static FakeStartupRuntime managedHealthyRuntime() {
        FakeStartupRuntime runtime = new FakeStartupRuntime();
        runtime.probes.add(notListeningProbe());
        runtime.probes.add(notListeningProbe());
        runtime.probes.add(healthyProbe(runtime.launchPid));
        return runtime;
    }

    private static LocalLlmProcessManager.TerminationResult termination(
            LocalLlmProcessManager.TerminationStatus status,
            boolean aliveAfter,
            boolean interrupted,
            int capturedHandleCount,
            int forcedHandleCount) {
        return new LocalLlmProcessManager.TerminationResult(
                status, aliveAfter, interrupted, capturedHandleCount, forcedHandleCount);
    }

    private static LocalLlmProcessManager enabledManager(FakeStartupRuntime runtime, String... extraProperties) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("local-llm.enabled", "true")
                .withProperty("local-llm.autostart", "true")
                .withProperty("local-llm.fail-fast", "true")
                .withProperty("local-llm.warmup.enabled", "false")
                .withProperty("local-llm.ollama-host", "127.0.0.1:11435")
                .withProperty("local-llm.health-check-url", "http://127.0.0.1:11435/api/version")
                .withProperty("local-llm.health-check-attempt-timeout", "50ms")
                .withProperty("local-llm.health-check-timeout", "500ms")
                .withProperty("local-llm.health-check-interval", "100ms")
                .withProperty("local-llm.cuda-auto-discover", "true")
                .withProperty("local-llm.log-dir", "var/codex-runtime/test-ollama");
        for (String property : extraProperties) {
            int equals = property.indexOf('=');
            environment.setProperty(property.substring(0, equals), property.substring(equals + 1));
        }
        environment.setConversionService(new ApplicationConversionService());
        return new LocalLlmProcessManager(environment, runtime);
    }

    private static LocalLlmProcessManager.ProbeResult healthyProbe(long pid) {
        return new LocalLlmProcessManager.ProbeResult(
                LocalLlmProcessManager.ProbeState.HEALTHY, 200, pid, "ollama.exe", 11435, "version_ok");
    }

    private static LocalLlmProcessManager.ProbeResult notListeningProbe() {
        return new LocalLlmProcessManager.ProbeResult(
                LocalLlmProcessManager.ProbeState.NOT_LISTENING, 0, 0L, "", 11435, "connect_failed");
    }

    private static final class FakeStartupRuntime implements LocalLlmProcessManager.StartupRuntime {
        final Deque<LocalLlmProcessManager.ProbeResult> probes = new ArrayDeque<>();
        final List<LocalLlmProcessManager.LaunchRequest> launchRequests = new ArrayList<>();
        final List<Long> sleeps = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final FakeOwnedProcess ownedProcess = new FakeOwnedProcess(events);
        int probeCount;
        long nowMillis;
        long launchPid = 4812L;
        String executable = "ollama";
        List<String> discoveredGpuUuids = List.of();

        @Override
        public LocalLlmProcessManager.ProbeResult probe(String healthUrl, int timeoutMs) {
            probeCount++;
            return probes.isEmpty() ? notListeningProbe() : probes.removeFirst();
        }

        @Override
        public String resolveOllamaExecutable(int timeoutMs) {
            return executable;
        }

        @Override
        public List<String> discoverGpuUuids(int timeoutMs) {
            return discoveredGpuUuids;
        }

        @Override
        public LocalLlmProcessManager.LaunchResult launch(LocalLlmProcessManager.LaunchRequest request)
                throws IOException {
            launchRequests.add(request);
            ownedProcess.pid = launchPid;
            return new LocalLlmProcessManager.LaunchResult(ownedProcess);
        }

        @Override
        public long nowMillis() {
            return nowMillis;
        }

        @Override
        public void sleepMillis(long millis) {
            sleeps.add(millis);
            nowMillis += millis;
        }
    }

    private static final class FakeOwnedProcess implements LocalLlmProcessManager.OwnedProcess {
        final Deque<LocalLlmProcessManager.TerminationResult> results = new ArrayDeque<>();
        final List<Long> gracefulBudgets = new ArrayList<>();
        final List<Long> forcedBudgets = new ArrayList<>();
        final List<String> events;
        long pid = 4812L;
        int terminateCalls;
        boolean clearInterruptOnTerminate;
        Runnable onTerminate = () -> { };

        private FakeOwnedProcess(List<String> events) {
            this.events = events;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public LocalLlmProcessManager.TerminationResult terminate(long gracefulMs, long forcedMs) {
            terminateCalls++;
            gracefulBudgets.add(gracefulMs);
            forcedBudgets.add(forcedMs);
            events.add("terminate");
            if (clearInterruptOnTerminate) {
                Thread.interrupted();
            }
            onTerminate.run();
            return results.isEmpty()
                    ? termination(LocalLlmProcessManager.TerminationStatus.CAPTURED_HANDLES_TERMINATED,
                    false, false, 1, 0)
                    : results.removeFirst();
        }
    }

    private static final class FakeExactProcessHandle implements LocalLlmProcessManager.ExactProcessHandle {
        final String name;
        final long pid;
        final List<String> events;
        final Deque<List<LocalLlmProcessManager.ExactProcessHandle>> descendantSnapshots = new ArrayDeque<>();
        boolean alive = true;
        boolean stopOnGraceful;
        int descendantsCalls;

        private FakeExactProcessHandle(String name, long pid, List<String> events) {
            this.name = name;
            this.pid = pid;
            this.events = events;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public List<LocalLlmProcessManager.ExactProcessHandle> descendants() {
            descendantsCalls++;
            if (descendantSnapshots.isEmpty()) {
                return List.of();
            }
            return descendantSnapshots.size() == 1
                    ? descendantSnapshots.peekFirst()
                    : descendantSnapshots.removeFirst();
        }

        @Override
        public boolean destroy() {
            events.add("graceful:" + name);
            if (stopOnGraceful) {
                alive = false;
            }
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            events.add("forced:" + name);
            alive = false;
            return true;
        }
    }

    private static final class FakeProcessTreeOps implements LocalLlmProcessManager.ProcessTreeOps {
        final LocalLlmProcessManager.ExactProcessHandle root;
        final List<Long> sleepBudgets = new ArrayList<>();
        long nowNanos;
        int rootCalls;
        Runnable onSleep = () -> { };

        private FakeProcessTreeOps(LocalLlmProcessManager.ExactProcessHandle root) {
            this.root = root;
        }

        @Override
        public LocalLlmProcessManager.ExactProcessHandle root(Process process) {
            rootCalls++;
            return root;
        }

        @Override
        public long nanoTime() {
            return nowNanos;
        }

        @Override
        public void sleepMillis(long millis) {
            sleepBudgets.add(millis);
            nowNanos += Math.max(1L, millis) * 1_000_000L;
            onSleep.run();
        }
    }
}
