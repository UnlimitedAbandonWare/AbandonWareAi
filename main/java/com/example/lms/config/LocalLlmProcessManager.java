package com.example.lms.config;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalLlmProcessManager implements BeanFactoryPostProcessor, SmartLifecycle, Ordered, EnvironmentAware,
        io.micrometer.core.instrument.binder.MeterBinder {
    private static final Logger log = LoggerFactory.getLogger(LocalLlmProcessManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_OLLAMA_HOST_ENV = "127.0.0.1:11435";
    private static final int LOCAL_COMMAND_TIMEOUT_MS = 3000;
    private static final long OWNED_PROCESS_GRACEFUL_STOP_MS = 2_000L;
    private static final long OWNED_PROCESS_FORCED_STOP_MS = 1_000L;
    private static final Pattern CUDA_VISIBLE_DEVICE_UUID = Pattern.compile(
            "GPU-[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}");
    private static final Pattern MEMORY_FAILURE_SIGNAL = Pattern.compile(
            "\\b(?:insufficient[_ ]memory|model requires more system memory|out of memory)\\b");
    private static final Pattern NEGATED_MEMORY_PREFIX = Pattern.compile("\\b(?:not|no)(?:\\s+an?)?\\s*$");
    private static final Pattern FALSE_MEMORY_SUFFIX = Pattern.compile("^\\s*['\"]?\\s*[:=]\\s*false\\b");

    private Environment env;
    private StartupRuntime startupRuntime;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean attempted = new AtomicBoolean(false);
    private final AtomicReference<OwnedProcess> ownedProcess = new AtomicReference<>();
    private final Queue<OwnedProcess> ownedProcessRetries = new ConcurrentLinkedQueue<>();
    private int healthAttemptCount;
    private boolean launchAttempted;
    private boolean reused;
    private long launchPid;
    private long startupStartedMs;
    private Path stderrLogPath;
    private ProbeResult lastProbe = ProbeResult.notListening(0, "not_probed");
    private String cudaPinReason = "not_configured";
    private final AtomicBoolean recoveryInFlight = new AtomicBoolean();
    private final Object ownershipLock = new Object();
    private final Deque<Long> restartTimes = new ArrayDeque<>();
    private volatile State state = State.DISABLED;
    private volatile String recoveryReason = "";
    private volatile boolean stopping;
    private volatile boolean modelPresent;
    private volatile boolean modelReady;
    private volatile long cooldownUntil;
    private boolean restartInProgress;
    private boolean halfOpen;
    private boolean gpuRestartUsed;
    private int restartCount;
    private long cooldownMs = 60_000L;
    private final java.util.concurrent.atomic.AtomicLong startAttempts = new java.util.concurrent.atomic.AtomicLong();
    private static final List<String> RECOVERY_REASONS = List.of("STARTUP", "SERVER_DOWN", "PORT_CONFLICT",
            "EXECUTABLE_NOT_FOUND", "START_TIMEOUT", "RUNNER_EXITED", "MODEL_NOT_FOUND",
            "MODEL_LOAD_TIMEOUT", "MODEL_LOAD_FAILED", "INSUFFICIENT_MEMORY", "GPU_UNAVAILABLE", "RESTART_RATE_LIMITED");
    private final Map<String, java.util.concurrent.atomic.AtomicLong> recoverySuccesses = recoveryCounters();
    private final Map<String, java.util.concurrent.atomic.AtomicLong> recoveryFailures = recoveryCounters();
    private volatile String lastRecoveryReason = "STARTUP";
    private final java.util.concurrent.atomic.AtomicLong fallbackCount = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean fallbackUsed;
    private volatile long serverStartElapsedMs;
    private volatile long warmupElapsedMs;
    private volatile String warmupStatus = "not_observed";
    private volatile String warmupReason = "not_observed";
    private volatile int warmupReturnedDim;
    private volatile long warmupVerifiedAtMillis = -1;
    private volatile String configuredOllamaHost;
    private volatile long serverStartedAtMillis;
    private volatile boolean gpuRoleVerified;
    private volatile boolean routingIdentityEstablished;
    private volatile boolean exclusivePlacementVerified;
    private volatile boolean gpuAllocationObserved;
    private volatile String externalRoleStatus = "not_observed";
    private final AtomicReference<String> observedProcessFailure = new AtomicReference<>();

    public enum State {
        DISABLED, PROBING, STARTING, SERVER_READY, MODEL_WARMING, READY, DEGRADED, COOLDOWN, STOPPED
    }

    @Value("${local-llm.enabled:true}")
    private boolean enabled;

    @Value("${local-llm.autostart:false}")
    private boolean autostart;

    @Value("${local-llm.ollama-host:127.0.0.1:11435}")
    private String ollamaHost = DEFAULT_OLLAMA_HOST_ENV;

    @Value("${local-llm.health-check-url:}")
    private String healthCheckUrl;

    @Value("${local-llm.health-check-timeout:20s}")
    private Duration healthCheckTimeout = Duration.ofSeconds(30);

    @Value("${local-llm.health-check-interval:1s}")
    private Duration healthCheckInterval = Duration.ofSeconds(1);

    @Value("${local-llm.health-check-attempt-timeout:2s}")
    private Duration healthCheckAttemptTimeout = Duration.ofSeconds(2);

    @Value("${local-llm.start-command:}")
    private String startCommand;

    @Value("${local-llm.cuda-visible-device:}")
    private String cudaVisibleDevice;

    @Value("${local-llm.cuda-auto-discover:true}")
    private boolean cudaAutoDiscover;

    @Value("${local-llm.log-dir:var/codex-runtime}")
    private String logDir;

    @Value("${local-llm.fail-fast:false}")
    private boolean failFast;

    @Value("${local-llm.warmup.enabled:false}")
    private boolean warmupEnabled;

    @Value("${local-llm.warmup.pull:false}")
    private boolean warmupPull;

    @Value("${local-llm.warmup.show:true}")
    private boolean warmupShow;

    @Value("${local-llm.warmup.embed:true}")
    private boolean warmupEmbed;

    @Value("${local-llm.warmup.model:${llm.chat-model:}}")
    private String warmupModel;

    @Value("${local-llm.warmup.embed-model:${embedding.model:}}")
    private String warmupEmbedModel;

    @Value("${embedding.model:}")
    private String embeddingModel;

    @Value("${embedding.provider:ollama}")
    private String embeddingProvider = "ollama";

    @Value("${local-llm.warmup.dimensions:${embedding.dimensions:1536}}")
    private int warmupDimensions;

    private boolean embedVerified;

    @Value("${local-llm.warmup.keep-alive:5m}")
    private String warmupKeepAlive;

    @Value("${local-llm.warmup.timeout-ms:120000}")
    private long warmupTimeoutMs = 120_000L;

    public LocalLlmProcessManager() {
        this.startupRuntime = new SystemStartupRuntime();
    }

    public LocalLlmProcessManager(Environment env) {
        this(env, new SystemStartupRuntime());
    }

    LocalLlmProcessManager(Environment env, StartupRuntime startupRuntime) {
        this.env = env;
        this.startupRuntime = startupRuntime == null ? new SystemStartupRuntime() : startupRuntime;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

    @Override
    public synchronized void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!attempted.get()) loadFromEnvironment();
        start();
    }

    @Override
    public void start() {
        if (!attempted.compareAndSet(false, true)) {
            return;
        }
        running.set(true);
        if (operatorRouteUnavailable()) return;
        startupRuntime.onFailure(this::requestRecovery);
        recoveryInFlight.set(true);
        Runnable work = () -> {
            try {
                if (!stopping) startNow();
            } finally {
                finishRecoveryAttempt();
            }
        };
        if (!enabled || !autostart) work.run();
        else startupRuntime.execute(work);
    }

    private void startNow() {
        if (operatorRouteUnavailable()) return;
        ManagedOllamaEndpoint routingLease = null;
        observedProcessFailure.set(null);
        warmupVerifiedAtMillis = -1;
        gpuRoleVerified = false;
        routingIdentityEstablished = false;
        exclusivePlacementVerified = false;
        gpuAllocationObserved = false;
        serverStartedAtMillis = 0;
        startupStartedMs = startupRuntime.nowMillis();
        healthAttemptCount = 0;
        launchAttempted = false;
        reused = false;
        launchPid = 0L;
        stderrLogPath = null;
        lastProbe = ProbeResult.notListening(effectiveOllamaPort(), "not_probed");

        try {
            logSecretPresence();

            if (!enabled || !autostart) {
                transition(State.DISABLED, "disabled_or_autostart_false");
                traceStartupSnapshot("skipped", "disabled_or_autostart_false");
                traceWarmupSkipped();
                log.info("[AWX][ollama][startup] enabled={} autostart={} status=skipped", enabled, autostart);
                running.set(true);
                return;
            }

            transition(State.PROBING, "");
            if (configuredOllamaHost == null) configuredOllamaHost = ollamaHost;
            ollamaHost = configuredOllamaHost;
            selectCudaVisibleDevice();
            if (hasText(cudaVisibleDevice)) {
                routingLease = ManagedOllamaEndpoint.acquire(
                        Path.of(hasText(logDir) ? logDir : "var/codex-runtime"), configuredOllamaHost, cudaVisibleDevice);
            }
            ProbeResult initial = probeHealth();
            if (routingLease != null && initial.state() == ProbeState.HEALTHY
                    && !routingLease.registered(ollamaHost, initial.pid(), startupRuntime.processStartedAt(initial.pid()))
                    && !startupRuntime.serverGpuUuids(initial.pid(), LOCAL_COMMAND_TIMEOUT_MS).equals(Set.of(cudaVisibleDevice))) {
                externalRoleStatus = "reachable_role_unverified";
                ollamaHost = routingLease.host();
                initial = probeHealth();
                if (initial.state() == ProbeState.HEALTHY
                        && !routingLease.registered(ollamaHost, initial.pid(), startupRuntime.processStartedAt(initial.pid()))) {
                    throw new IOException("managed_endpoint_identity_unverified");
                }
            }
            if (initial.state() == ProbeState.HEALTHY) {
                reused = true;
                launchPid = Math.max(0L, initial.pid());
                serverStartedAtMillis = startupRuntime.processStartedAt(launchPid);
                routingIdentityEstablished = true;
                if (routingLease != null) routingLease.close();
                traceStartupSnapshot("already_healthy", "");
                log.info("[AWX][ollama][startup] status=already_healthy reused=true launchAttempted=false pid={} processName={} port={} portState={} elapsedMs={} healthUrlHost={} healthUrlHash={} healthUrlLength={} host={} hostHash={}",
                        launchPid, safeProcessName(initial.processName()), initial.port(), initial.state().traceValue(), elapsedMs(),
                        safeUrlHost(effectiveHealthCheckUrl()), safeUrlHash(effectiveHealthCheckUrl()),
                        safeUrlLength(effectiveHealthCheckUrl()), safeOllamaHostForLog(ollamaHost),
                        safeOllamaHostHash(ollamaHost));
                if (!warmupOllama()) {
                    return;
                }
                running.set(true);
                return;
            }

            if (initial.state() == ProbeState.UNHEALTHY_LISTENER) {
                failForOccupiedListener(initial);
                return;
            }

            traceStartupSnapshot("health_down", "process_start");
            log.warn("[AWX][ollama][startup] status=health_down action=process_start port={} portState={} healthUrlHost={} healthUrlHash={} healthUrlLength={} host={} hostHash={}",
                    initial.port(), initial.state().traceValue(),
                    safeUrlHost(effectiveHealthCheckUrl()), safeUrlHash(effectiveHealthCheckUrl()),
                    safeUrlLength(effectiveHealthCheckUrl()), safeOllamaHostForLog(ollamaHost),
                    safeOllamaHostHash(ollamaHost));

            String explicit = trimToNull(startCommand);
            String executable = null;
            if (explicit == null) {
                executable = trimToNull(startupRuntime.resolveOllamaExecutable(LOCAL_COMMAND_TIMEOUT_MS));
                if (executable == null) {
                    handleStartupFailure("start_executable_not_found",
                            new IllegalStateException("ollama executable is not available on PATH"));
                    return;
                }
            }

            ProbeResult raceCheck = probeHealth();
            if (raceCheck.state() == ProbeState.HEALTHY) {
                if (routingLease != null && !routingLease.registered(ollamaHost, raceCheck.pid(),
                        startupRuntime.processStartedAt(raceCheck.pid()))) {
                    throw new IOException("managed_endpoint_identity_unverified");
                }
                reused = true;
                launchPid = Math.max(0L, raceCheck.pid());
                serverStartedAtMillis = startupRuntime.processStartedAt(launchPid);
                routingIdentityEstablished = true;
                if (routingLease != null) routingLease.close();
                traceStartupSnapshot("already_healthy", "race_reuse");
                log.info("[AWX][ollama][startup] status=already_healthy reused=true launchAttempted=false pid={} processName={} port={} portState={} elapsedMs={} reason=race_reuse",
                        launchPid, safeProcessName(raceCheck.processName()), raceCheck.port(),
                        raceCheck.state().traceValue(), elapsedMs());
                if (!warmupOllama()) {
                    return;
                }
                running.set(true);
                return;
            }
            if (raceCheck.state() == ProbeState.UNHEALTHY_LISTENER) {
                failForOccupiedListener(raceCheck);
                return;
            }

            try {
                LaunchRequest request = buildLaunchRequest(executable, explicit);
                if (operatorRouteUnavailable()) return;
                transition(State.STARTING, "");
                launchAttempted = true;
                startAttempts.incrementAndGet();
                stderrLogPath = request.stderrLog();
                LaunchResult launched;
                synchronized (ownershipLock) {
                    if (stopping) return;
                    launched = startupRuntime.launch(request);
                if (!ownedProcess.compareAndSet(null, launched.ownedProcess())) {
                    TerminationResult collisionResult = terminateUnregisteredOwnedProcess(
                            launched.ownedProcess());
                    TraceStore.put("localLlm.shutdown.registrationCollision",
                            collisionResult.status().traceValue());
                    TraceStore.put("localLlm.shutdown.registrationCollisionAliveAfter",
                            collisionResult.aliveAfter());
                    if (collisionResult.aliveAfter()) {
                        ownedProcessRetries.add(launched.ownedProcess());
                    }
                    TraceStore.put("localLlm.shutdown.registrationCollisionRetained",
                            collisionResult.aliveAfter());
                    throw new IllegalStateException("owned process already registered");
                }
                }
                launchPid = Math.max(0L, launched.pid());
                TraceStore.put("localLlm.startup.processStarted", true);
                TraceStore.put("localLlm.process.owned", true);
                if (launched.process() != null) {
                    launched.process().onExit().thenAccept(exited -> {
                        if (!stopping && ownedProcess.get() == launched.ownedProcess()) {
                            TraceStore.put("localLlm.process.exitCode", exited.exitValue());
                            requestRecovery("RUNNER_EXITED");
                        }
                    });
                }
                log.info("[AWX][ollama][startup] status=launched launchAttempted=true reused=false pid={} port={} stderrLog={} elapsedMs={} host={} hostHash={} explicitCommand={}",
                        launchPid, effectiveOllamaPort(), safeLogPath(stderrLogPath), elapsedMs(),
                        safeOllamaHostForLog(ollamaHost), safeOllamaHostHash(ollamaHost), hasText(startCommand));
            } catch (Exception ex) {
                traceSuppressed("ollama.start", ex);
                handleStartupFailure(isExecutableMissing(ex)
                        ? "start_executable_not_found"
                        : "start_command_failed", ex);
                return;
            }

            ProbeResult ready = waitForHealthCheck();
            if (ready == null) {
                handleStartupFailure("health_timeout", new IllegalStateException(
                        "Ollama did not become healthy within " + healthCheckTimeout));
                return;
            }

            if (ready.pid() > 0 && ready.pid() != launchPid) {
                terminateOwnedProcess("startup_failure");
                if (routingLease != null) throw new IOException("managed_endpoint_identity_changed");
                reused = true;
                launchPid = ready.pid();
                observedProcessFailure.set(null);
            }

            serverStartedAtMillis = startupRuntime.processStartedAt(launchPid);
            routingIdentityEstablished = true;
            if (routingLease != null) {
                routingLease.register(ollamaHost, launchPid, serverStartedAtMillis);
                routingLease.close();
            }

            traceStartupSnapshot("healthy", "");
            log.info("[AWX][ollama][startup] status=healthy reused=false launchAttempted=true pid={} processName={} port={} portState={} elapsedMs={} healthAttempts={} stderrLog={} healthUrlHost={} healthUrlHash={} healthUrlLength={} timeoutMs={}",
                    launchPid, safeProcessName(ready.processName()), ready.port(), ready.state().traceValue(),
                    elapsedMs(), healthAttemptCount, safeLogPath(stderrLogPath),
                    safeUrlHost(effectiveHealthCheckUrl()), safeUrlHash(effectiveHealthCheckUrl()),
                    safeUrlLength(effectiveHealthCheckUrl()), healthCheckTimeout.toMillis());
            if (!warmupOllama()) {
                return;
            }
            running.set(true);
        } catch (StartupFailureException alreadyClassified) {
            throw alreadyClassified;
        } catch (Exception e) {
            traceSuppressed("ollama.unexpected", e);
            handleStartupFailure("unexpected", e);
        } finally {
            if (routingLease != null) {
                try { routingLease.close(); } catch (IOException failure) { traceSuppressed("ollama.routingLease", failure); }
            }
        }
    }

    @Override
    public void stop() {
        stopping = true;
        running.set(false);
        startupRuntime.close();
        synchronized (ownershipLock) {
            terminateOwnedProcess("lifecycle_stop");
        }
        transition(State.STOPPED, "");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 100;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            if (callback != null) {
                callback.run();
            }
        }
    }

    private void terminateOwnedProcess(String reason) {
        OwnedProcess claimed = ownedProcess.getAndSet(null);
        List<OwnedProcess> retryClaims = new ArrayList<>();
        OwnedProcess retryClaim;
        while ((retryClaim = ownedProcessRetries.poll()) != null) {
            retryClaims.add(retryClaim);
        }
        if (claimed == null && retryClaims.isEmpty()) {
            traceOwnedTermination(reason, false, false,
                    new TerminationResult(TerminationStatus.ALREADY_EXITED,
                            false, false, 0, 0), "not_owned");
            return;
        }

        if (claimed != null) {
            TerminationResult result = terminateUnregisteredOwnedProcess(claimed);
            if (result.aliveAfter()) {
                ownedProcess.compareAndSet(null, claimed);
            }
            traceOwnedTermination(
                    reason, true, claimed.pid() > 0L, result, result.status().traceValue());
        }

        for (OwnedProcess retry : retryClaims) {
            TerminationResult result = terminateUnregisteredOwnedProcess(retry);
            if (result.aliveAfter()) {
                ownedProcessRetries.add(retry);
            }
            traceOwnedTermination(
                    reason, true, retry.pid() > 0L, result, result.status().traceValue());
        }
    }

    private TerminationResult terminateUnregisteredOwnedProcess(OwnedProcess process) {
        TerminationResult result;
        try {
            result = process.terminate(
                    OWNED_PROCESS_GRACEFUL_STOP_MS,
                    OWNED_PROCESS_FORCED_STOP_MS);
            if (result == null) {
                result = new TerminationResult(
                        TerminationStatus.TERMINATION_ERROR, true, false, 0, 0);
            }
        } catch (RuntimeException failure) {
            result = new TerminationResult(
                    TerminationStatus.TERMINATION_ERROR, true, false, 0, 0);
            TraceStore.put("localLlm.shutdown.errorType", "local_llm_termination_error");
        }
        if (result.interrupted()) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    private void traceOwnedTermination(
            String reason,
            boolean owned,
            boolean pidPresent,
            TerminationResult result,
            String status) {
        String safeReason = "startup_failure".equals(reason) ? "startup_failure" : "lifecycle_stop";
        String safeStatus = SafeRedactor.traceLabelOrFallback(status, "termination_error");
        TraceStore.put("localLlm.shutdown.status", safeStatus);
        TraceStore.put("localLlm.shutdown.reason", safeReason);
        TraceStore.put("localLlm.shutdown.owned", owned);
        TraceStore.put("localLlm.shutdown.pidPresent", pidPresent);
        TraceStore.put("localLlm.shutdown.capturedHandleCount", result.capturedHandleCount());
        TraceStore.put("localLlm.shutdown.forcedHandleCount", result.forcedHandleCount());
        TraceStore.put("localLlm.shutdown.aliveAfter", result.aliveAfter());
        TraceStore.put("localLlm.shutdown.interrupted", result.interrupted());
        log.info("[AWX][ollama][shutdown] status={} reason={} owned={} pidPresent={} capturedHandleCount={} forcedHandleCount={} aliveAfter={} interrupted={}",
                safeStatus, safeReason, owned, pidPresent,
                result.capturedHandleCount(), result.forcedHandleCount(),
                result.aliveAfter(), result.interrupted());
    }

    private boolean isServiceRunning() {
        try {
            return probeHealth().state() == ProbeState.HEALTHY;
        } catch (Exception e) {
            traceSuppressed("ollama.isServiceRunning", e);
            return false;
        }
    }

    private ProbeResult probeHealth() {
        healthAttemptCount++;
        ProbeResult result = startupRuntime.probe(
                effectiveHealthCheckUrl(),
                boundedMillis(healthCheckAttemptTimeout, 2000));
        if (result == null) {
            result = ProbeResult.notListening(effectiveOllamaPort(), "probe_result_missing");
        }
        lastProbe = result;
        TraceStore.put("localLlm.startup.probe", result.state().traceValue());
        return result;
    }

    private void loadFromEnvironment() {
        enabled = envBool("local-llm.enabled", enabled);
        autostart = envBool("local-llm.autostart", autostart);
        ollamaHost = envString("local-llm.ollama-host", ollamaHost);
        healthCheckUrl = envString("local-llm.health-check-url", healthCheckUrl);
        healthCheckTimeout = envDuration("local-llm.health-check-timeout", healthCheckTimeout);
        healthCheckInterval = envDuration("local-llm.health-check-interval", healthCheckInterval);
        healthCheckAttemptTimeout = envDuration("local-llm.health-check-attempt-timeout", healthCheckAttemptTimeout);
        startCommand = envString("local-llm.start-command", startCommand);
        cudaVisibleDevice = normalizeCudaVisibleDevice(envString("local-llm.cuda-visible-device", cudaVisibleDevice));
        cudaAutoDiscover = envBool("local-llm.cuda-auto-discover", cudaAutoDiscover);
        logDir = envString("local-llm.log-dir", logDir);
        cudaPinReason = cudaVisibleDevice == null ? "not_configured" : "configured_uuid";
        failFast = envBool("local-llm.fail-fast", failFast);
        warmupEnabled = envBool("local-llm.warmup.enabled", warmupEnabled);
        warmupPull = envBool("local-llm.warmup.pull", warmupPull);
        warmupShow = envBool("local-llm.warmup.show", warmupShow);
        warmupEmbed = envBool("local-llm.warmup.embed", warmupEmbed);
        embeddingProvider = envString("embedding.provider", embeddingProvider);
        warmupModel = envString("local-llm.warmup.model",
                envString("llm.chat-model", warmupModel));
        warmupEmbedModel = envString("local-llm.warmup.embed-model",
                envString("embedding.model", warmupEmbedModel));
        embeddingModel = envString("embedding.model", embeddingModel);
        warmupDimensions = envInt("local-llm.warmup.dimensions",
                envInt("embedding.dimensions", warmupDimensions));
        warmupKeepAlive = envString("local-llm.warmup.keep-alive", warmupKeepAlive);
        warmupTimeoutMs = envLong("local-llm.warmup.timeout-ms", warmupTimeoutMs);
        cooldownMs = Math.max(1000L, envLong("local-llm.recovery.cooldown-ms", cooldownMs));
    }

    private LaunchRequest buildLaunchRequest(String executable, String explicit) {
        List<String> command;
        if (explicit != null) {
            command = List.copyOf(splitCommand(explicit));
            if (command.size() == 1) command = List.of(command.get(0), "serve");
            if (command.size() != 2 || !"serve".equals(command.get(1))
                    || SystemStartupRuntime.shellBacked(command)) {
                throw new IllegalArgumentException("invalid_ollama_start_command");
            }
        } else {
            command = List.of(executable, "serve");
        }

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OLLAMA_HOST", normalizeOllamaHostForEnv(ollamaHost));
        String cudaDevice = normalizeCudaVisibleDevice(cudaVisibleDevice);
        if (cudaDevice != null) {
            environment.put("CUDA_VISIBLE_DEVICES", cudaDevice);
            environment.put("OLLAMA_VULKAN", "0");
            environment.put("GGML_VK_VISIBLE_DEVICES", "-1");
        }

        int port = effectiveOllamaPort();
        Path root = Path.of(trimToNull(logDir) == null ? "var/codex-runtime" : logDir).normalize();
        Path stdout = root.resolve("ollama-" + port + ".out.log").normalize();
        Path stderr = root.resolve("ollama-" + port + ".err.log").normalize();
        return new LaunchRequest(command, Map.copyOf(environment), stdout, stderr);
    }

    private ProbeResult waitForHealthCheck() throws InterruptedException {
        long total = Math.max(1L, healthCheckTimeout.toMillis());
        long deadline = safeDeadline(startupRuntime.nowMillis(), total);
        long step = Math.max(100L, healthCheckInterval.toMillis());
        while (startupRuntime.nowMillis() < deadline) {
            if (stopping) return null;
            ProbeResult result = probeHealth();
            if (result.state() == ProbeState.HEALTHY) {
                return result;
            }
            if (result.state() == ProbeState.UNHEALTHY_LISTENER
                    && result.pid() > 0L
                    && launchPid > 0L
                    && result.pid() != launchPid) {
                throw new IllegalStateException("ollama port owner changed during startup");
            }
            long remaining = Math.max(0L, deadline - startupRuntime.nowMillis());
            if (remaining == 0L) {
                break;
            }
            startupRuntime.sleepMillis(Math.min(step, remaining));
        }
        return null;
    }

    private void selectCudaVisibleDevice() {
        String explicit = normalizeCudaVisibleDevice(cudaVisibleDevice);
        if (explicit != null) {
            cudaVisibleDevice = explicit;
            cudaPinReason = "configured_uuid";
            if (!startupRuntime.discoverGpuUuids(LOCAL_COMMAND_TIMEOUT_MS).contains(explicit)) {
                cudaPinReason = "configured_uuid_unavailable";
                throw new IllegalStateException("GPU_UNAVAILABLE");
            }
            return;
        }
        String inherited = trimToNull(envString("CUDA_VISIBLE_DEVICES", null));
        if (inherited != null) {
            cudaPinReason = "inherited_device_selection";
            // Keep the existing explicit CPU selection, but never interpret host ordinals as stable identity.
            if (!"-1".equals(inherited)) {
                cudaVisibleDevice = normalizeCudaVisibleDevice(inherited);
                if (!startupRuntime.discoverGpuUuids(LOCAL_COMMAND_TIMEOUT_MS).contains(cudaVisibleDevice)) {
                    cudaPinReason = "configured_uuid_unavailable";
                    throw new IllegalStateException("GPU_UNAVAILABLE");
                }
            }
            return;
        }
        if (!cudaAutoDiscover) {
            cudaPinReason = "auto_discovery_disabled";
            return;
        }

        Set<String> valid = new LinkedHashSet<>();
        for (String candidate : startupRuntime.discoverGpuUuids(LOCAL_COMMAND_TIMEOUT_MS)) {
            try {
                String normalized = normalizeCudaVisibleDevice(candidate);
                if (normalized != null) {
                    valid.add(normalized);
                }
            } catch (IllegalArgumentException ignored) {
                traceSuppressed("ollama.cudaDiscovery", ignored);
            }
        }
        if (valid.size() == 1) {
            cudaVisibleDevice = valid.iterator().next();
            cudaPinReason = "auto_discovered_uuid";
        } else if (valid.isEmpty()) {
            cudaPinReason = "auto_discovery_unavailable";
        } else {
            cudaPinReason = "auto_discovery_ambiguous";
        }
    }

    private void failForOccupiedListener(ProbeResult result) {
        log.error("[AWX][ollama][startup] status=failed reason=unhealthy_listener_occupied pid={} processName={} port={} portState={} probeReason={} action=none",
                Math.max(0L, result.pid()), safeProcessName(result.processName()), result.port(),
                result.state().traceValue(), SafeRedactor.traceLabelOrFallback(result.reason(), "unknown"));
        handleStartupFailure("unhealthy_listener_occupied",
                new IllegalStateException("configured Ollama port is occupied by an unhealthy listener"));
    }

    private boolean warmupOllama() {
        if (operatorRouteUnavailable()) return false;
        transition(State.SERVER_READY, "");
        TraceStore.put("localLlm.startup.serverReady", true);
        serverStartElapsedMs = elapsedMs();
        TraceStore.put("localLlm.process.owned", ownedProcess.get() != null);
        if (!warmupEnabled) {
            traceWarmupSkipped();
            return true;
        }
        String failureReason = "MODEL_LOAD_FAILED";
        long warmupStarted = startupRuntime.nowMillis();
        int attempts = halfOpen ? 1 : 2;
        for (int attempt = 0; attempt < attempts && !stopping; attempt++) {
            if (operatorRouteUnavailable()) return false;
            try {
                transition(State.MODEL_WARMING, "");
                TraceStore.put("localLlm.recovery.attempt", attempt + 1);
                warmupOnce();
                String observedFailure = observedProcessFailure.get();
                if (observedFailure != null) throw new IOException(observedFailure);
                if (stopping || operatorRouteUnavailable()) return false;
                modelReady = true;
                warmupVerifiedAtMillis = startupRuntime.nowMillis();
                gpuRestartUsed = false;
                transition(State.READY, "");
                recoverySuccesses.get(lastRecoveryReason).incrementAndGet();
                lastRecoveryReason = "STARTUP";
                warmupElapsedMs = Math.max(0L, startupRuntime.nowMillis() - warmupStarted);
                TraceStore.put("localLlm.model.warmup", "ok");
                TraceStore.put("localLlm.recovery.result", "ready");
                return true;
            } catch (Exception failure) {
                warmupElapsedMs = Math.max(0L, startupRuntime.nowMillis() - warmupStarted);
                failureReason = classifyFailure(failure);
                lastRecoveryReason = failureReason;
                // Retrying or restarting immediately cannot change the model's memory requirement.
                if ("MODEL_NOT_FOUND".equals(failureReason) || "INSUFFICIENT_MEMORY".equals(failureReason)) break;
                if (attempt + 1 < attempts) {
                    try {
                        startupRuntime.sleepMillis(Math.min(1000L, boundedMillis(healthCheckInterval, 1000)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        if (operatorRouteUnavailable()) return false;
        if (!stopping && !restartInProgress && !"MODEL_NOT_FOUND".equals(failureReason)
                && !"INSUFFICIENT_MEMORY".equals(failureReason)
                && ownedProcess.get() != null && reserveRestart(failureReason)) {
            restartInProgress = true;
            try {
                terminateOwnedProcess("startup_failure");
                if (ownedProcess.get() == null) startNow();
                else enterCooldown("RUNNER_EXITED");
            } finally {
                restartInProgress = false;
            }
            return modelReady;
        }
        if (!stopping) enterCooldown(failureReason);
        return false;
    }

    /** Embedding warmup is a separate, non-gating check that only applies to a managed-local provider. */
    private boolean embedWarmupApplies() {
        if (!warmupEmbed) {
            return false;
        }
        if ("ollama".equalsIgnoreCase(trimToNull(embeddingProvider))) {
            return true;
        }
        // Under a foreign embedding provider the bundled YAML default only mirrors
        // embedding.model; only an explicitly-sourced warmup.embed-model is a real
        // instruction to warm the managed server. Value equality cannot separate an
        // explicit same-value setting from the inherited default, so the decision
        // follows the property origin, not the resolved string.
        return warmupEmbedModelExplicit() && trimToNull(warmupEmbedModel) != null;
    }

    /**
     * True when local-llm.warmup.embed-model comes from a user source rather than
     * the packaged placeholder chain. Property sources are ordered by precedence,
     * so the first-defining source alone decides origin - its raw form does not
     * matter, an explicit ${...} reference set by the user is still explicit.
     * Only when the winner is the packaged chain itself
     * (${LOCAL_LLM_WARMUP_EMBED_MODEL:${embedding.model:...}}) does the dedicated
     * env var it references supply the effective value.
     */
    private boolean warmupEmbedModelExplicit() {
        if (!(env instanceof org.springframework.core.env.ConfigurableEnvironment configurable)) {
            String embedModel = trimToNull(warmupEmbedModel);
            return embedModel != null && !embedModel.equals(trimToNull(embeddingModel));
        }
        for (org.springframework.core.env.PropertySource<?> source : configurable.getPropertySources()) {
            Object raw = source.getProperty("local-llm.warmup.embed-model");
            if (raw != null) {
                if (raw instanceof String text && text.contains("${LOCAL_LLM_WARMUP_EMBED_MODEL")) {
                    return env.containsProperty("LOCAL_LLM_WARMUP_EMBED_MODEL");
                }
                return true;
            }
        }
        return env.containsProperty("LOCAL_LLM_WARMUP_EMBED_MODEL");
    }

    private boolean warmupOnce() throws IOException {
        warmupReturnedDim = 0;
        warmupReason = "not_observed";
        embedVerified = false;
        if (!warmupEnabled) {
            traceWarmupSkipped();
            log.info("[AWX][ollama][warmup] enabled=false status=skipped");
            return true;
        }
        String model = trimToNull(warmupModel);
        if (model == null) {
            traceWarmup("failed", 0, "warmup_model_missing");
            throw new IOException("MODEL_NOT_FOUND");
        }

        try {
            modelPresent = containsModel(getJson("/api/tags", boundedMillis(healthCheckAttemptTimeout, 2000)), model);
            TraceStore.put("localLlm.model.present", modelPresent);
            if (!modelPresent && warmupPull) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("stream", false);
                postJson("/api/pull", body, warmupTimeoutMs);
                modelPresent = containsModel(getJson("/api/tags", 2000), model);
                log.info("[AWX][ollama][warmup] step=pull status=ok modelHash={} modelLength={}",
                        SafeRedactor.hashValue(model), model.length());
            }

            if (!modelPresent) throw new IOException("MODEL_NOT_FOUND");
            if (warmupShow) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                postJson("/api/show", body, warmupTimeoutMs);
                log.info("[AWX][ollama][warmup] step=show status=ok modelHash={} modelLength={}",
                        SafeRedactor.hashValue(model), model.length());
            }

            // Chat readiness is the managed-route gate and always uses the chat model.
            {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
                body.put("stream", false);
                body.put("options", Map.of("num_predict", 1));
                if (hasText(warmupKeepAlive)) body.put("keep_alive", warmupKeepAlive);
                JsonNode response = postJson("/api/chat", body, warmupTimeoutMs);
                if (!response.path("done").asBoolean() || !response.path("message").isObject()) {
                    throw new IOException("MODEL_LOAD_FAILED");
                }
                traceWarmup("ok", 0, "chat");
            }
            embedVerified = false;
            if (embedWarmupApplies()) {
                String embedModel = trimToNull(warmupEmbedModel);
                if (embedModel == null) {
                    warmupReason = "embed_model_missing";
                    log.warn("[AWX][ollama][warmup] step=embed status=skipped reason=embed_model_missing");
                } else {
                    try {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("model", embedModel);
                        body.put("input", "ping");
                        if (warmupDimensions > 0) {
                            body.put("dimensions", warmupDimensions);
                        }
                        String keepAlive = trimToNull(warmupKeepAlive);
                        if (keepAlive != null) {
                            body.put("keep_alive", keepAlive);
                        }
                        JsonNode root = postJson("/api/embed", body, warmupTimeoutMs);
                        int dim = embeddingDimension(root);
                        warmupReturnedDim = Math.max(0, dim);
                        if (dim <= 0) {
                            warmupReason = "embedding_empty";
                            log.warn("[AWX][ollama][warmup] step=embed status=failed reason=embedding_empty");
                        } else if (warmupDimensions > 0 && dim != warmupDimensions) {
                            warmupReason = "embedding_dimension_mismatch";
                            log.warn("[AWX][ollama][warmup] step=embed status=failed reason=embedding_dimension_mismatch targetDim={} returnedDim={}",
                                    warmupDimensions, dim);
                        } else {
                            embedVerified = true;
                            traceWarmup("ok", dim, "embed");
                            log.info("[AWX][ollama][warmup] step=embed status=ok modelHash={} modelLength={} targetDim={} returnedDim={}",
                                    SafeRedactor.hashValue(embedModel), embedModel.length(), warmupDimensions, dim);
                        }
                    } catch (Exception embedFailure) {
                        warmupReason = "embed_request_failed";
                        traceSuppressed("ollama.warmup.embed", embedFailure);
                    }
                }
            }
            JsonNode runningModels = null;
            try {
                runningModels = getJson("/api/ps", 2000);
                TraceStore.put("localLlm.model.running", containsModel(runningModels, model));
            } catch (IOException psUnavailable) {
                TraceStore.put("localLlm.model.running", "not_observed");
            }
            String inherited = trimToNull(envString("CUDA_VISIBLE_DEVICES", null));
            boolean allocationObserved = false;
            if (runningModels != null) {
                for (JsonNode row : runningModels.path("models")) {
                    JsonNode vram = row.path("size_vram");
                    if ((model.equals(row.path("name").asText()) || model.equals(row.path("model").asText()))
                            && vram.isIntegralNumber() && vram.canConvertToLong() && vram.asLong() > 0) {
                        allocationObserved = true;
                    }
                }
            }
            TraceStore.put("localLlm.model.gpuAllocationObserved", allocationObserved);
            gpuAllocationObserved = allocationObserved;
            Set<String> observed = hasText(cudaVisibleDevice)
                    ? startupRuntime.serverGpuUuids(launchPid, LOCAL_COMMAND_TIMEOUT_MS) : Set.of();
            gpuRoleVerified = allocationObserved && hasText(cudaVisibleDevice) && observed.contains(cudaVisibleDevice);
            exclusivePlacementVerified = gpuRoleVerified && observed.size() == 1;
            TraceStore.put("localLlm.model.gpuAttribution", gpuRoleVerified ? "server_runner_observed" : "not_observed");
            TraceStore.put("localLlm.model.exclusivePlacement", exclusivePlacementVerified);
            // A missing sensor is not a failed inference. GPU evidence remains an independent verdict.
            if (!allocationObserved && !gpuRoleVerified && "not_observed".equals(warmupReason)) {
                warmupReason = "gpu_allocation_evidence_needed";
            }
            return true;
        } catch (Exception e) {
            traceWarmup("failed", warmupReturnedDim, "not_observed".equals(warmupReason) ? "warmup_request_failed" : warmupReason);
            traceSuppressed("ollama.warmup", e);
            if (e instanceof IOException io) throw io;
            throw new IOException(classifyFailure(e));
        }
    }

    private static boolean containsModel(JsonNode root, String model) {
        for (JsonNode row : root.path("models")) {
            if (model.equals(row.path("name").asText()) || model.equals(row.path("model").asText())) return true;
        }
        return false;
    }

    private JsonNode getJson(String path, int timeoutMs) throws IOException {
        return startupRuntime.getJson(ollamaBaseUrl() + path, timeoutMs);
    }

    private JsonNode postJson(String path, Map<String, Object> body, long timeoutMs) throws IOException {
        return startupRuntime.postJson(ollamaBaseUrl() + path, body, timeoutMs);
    }

    private static JsonNode requestJson(String url, Map<String, Object> body, long timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(body == null ? "GET" : "POST");
            conn.setConnectTimeout((int) Math.min(2000L, Math.max(1L, timeoutMs)));
            conn.setReadTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeoutMs)));
            conn.setDoOutput(body != null);
            conn.setRequestProperty("Content-Type", "application/json");
            if (body != null) {
                byte[] bytes = MAPPER.writeValueAsBytes(body);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String response = readBounded(conn.getErrorStream());
                throw new IOException(classifyFailure(new IOException(response)) + " " + safeUrlDiagnostic(url)
                        + " failed status=" + code
                        + " bodyHash=" + bodyHash(response)
                        + " bodyLength=" + bodyLength(response));
            }
            // JSON catalogs and embeddings exceed the diagnostic error-body limit.
            // Keep successful responses bounded without parsing a silently truncated document.
            try (InputStream stream = conn.getInputStream()) {
                int limit = 2 * 1024 * 1024;
                byte[] response = stream.readNBytes(limit + 1);
                if (response.length > limit) throw new IOException("MODEL_LOAD_FAILED response_too_large");
                return response.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(response);
            }
        } finally {
            conn.disconnect();
        }
    }

    private int embeddingDimension(JsonNode root) {
        JsonNode first = root.path("embeddings").path(0);
        return first.isArray() ? first.size() : 0;
    }

    private void handleStartupFailure(String reason, Exception e) {
        traceStartupSnapshot("failed", reason);
        log.error("[AWX][ollama][startup] status=failed reason={} reused={} launchAttempted={} pid={} processName={} port={} portState={} elapsedMs={} healthAttempts={} stderrLog={} host={} hostHash={} error={}",
                reason, reused, launchAttempted, launchPid, safeProcessName(lastProbe.processName()),
                lastProbe.port(), lastProbe.state().traceValue(), elapsedMs(), healthAttemptCount,
                safeLogPath(stderrLogPath), safeOllamaHostForLog(ollamaHost), safeOllamaHostHash(ollamaHost), shortErr(e));
        terminateOwnedProcess("startup_failure");
        if (!stopping) enterCooldown(switch (reason) {
            case "unhealthy_listener_occupied" -> "PORT_CONFLICT";
            case "start_executable_not_found" -> "EXECUTABLE_NOT_FOUND";
            case "health_timeout" -> "START_TIMEOUT";
            default -> classifyFailure(e);
        });
        if (failFast) {
            throw new StartupFailureException(reason, e);
        }
    }

    private static String classifyFailure(Throwable failure) {
        String reason = "MODEL_LOAD_FAILED";
        boolean memoryFailure = false;
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            String text = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
            // A concrete device loss remains decisive even alongside a memory symptom.
            if (text.contains("gpu is lost") || text.contains("gpu_device_lost")
                    || text.contains("gpu_unavailable")
                    || text.contains("invalid main_gpu selection") || text.contains("no cuda capable device")) return "GPU_UNAVAILABLE";
            memoryFailure |= hasMemoryFailureSignal(text);
            // Inspect nested causes before treating a generic CUDA/runner wrapper as decisive.
            if (!"MODEL_LOAD_FAILED".equals(reason)) continue;
            if (text.contains("cuda error")) reason = "GPU_UNAVAILABLE";
            else if (text.contains("model_not_found")) reason = "MODEL_NOT_FOUND";
            else if (cause instanceof java.net.SocketTimeoutException || text.contains("timed out")) reason = "MODEL_LOAD_TIMEOUT";
            else if (text.contains("llama-server process has terminated")
                    || text.contains("llama-server process no longer running")
                    || (text.contains("runner") && (text.contains("exit") || text.contains("terminated")))) reason = "RUNNER_EXITED";
        }
        return memoryFailure ? "INSUFFICIENT_MEMORY" : reason;
    }

    private static boolean hasMemoryFailureSignal(String text) {
        var matches = MEMORY_FAILURE_SIGNAL.matcher(text);
        while (matches.find()) {
            String prefix = text.substring(Math.max(0, matches.start() - 64), matches.start());
            if (!NEGATED_MEMORY_PREFIX.matcher(prefix).find()
                    && !FALSE_MEMORY_SUFFIX.matcher(text.substring(matches.end())).find()) return true;
        }
        return false;
    }

    private void transition(State next, String reason) {
        state = next;
        recoveryReason = reason;
        if (next != State.READY) modelReady = false;
        else fallbackUsed = false;
        TraceStore.put("localLlm.startup.state", next.name());
        TraceStore.put("localLlm.model.ready", modelReady);
        TraceStore.put("localLlm.process.owned", ownedProcess.get() != null);
        TraceStore.put("localLlm.recovery.reason", reason);
    }

    private boolean reserveRestart(String reason) {
        long now = startupRuntime.nowMillis();
        while (!restartTimes.isEmpty() && now - restartTimes.peekFirst() >= 300_000L) restartTimes.removeFirst();
        if (restartTimes.size() >= 3 || ("GPU_UNAVAILABLE".equals(reason) && gpuRestartUsed)) return false;
        if ("GPU_UNAVAILABLE".equals(reason)) gpuRestartUsed = true;
        restartTimes.addLast(now);
        restartCount++;
        TraceStore.put("localLlm.recovery.attempt", restartCount);
        return true;
    }

    private void enterCooldown(String reason) {
        if (stopping || operatorRouteUnavailable()) return;
        transition(State.COOLDOWN, reason);
        lastRecoveryReason = RECOVERY_REASONS.contains(reason) ? reason : "MODEL_LOAD_FAILED";
        recoveryFailures.get(lastRecoveryReason).incrementAndGet();
        cooldownUntil = safeDeadline(startupRuntime.nowMillis(), cooldownMs);
        TraceStore.put("localLlm.recovery.result", "cooldown");
        startupRuntime.schedule(() -> {
            if (operatorRouteUnavailable()) return;
            if (!stopping && state == State.COOLDOWN && recoveryInFlight.compareAndSet(false, true)) {
                restartInProgress = true;
                halfOpen = true;
                try {
                    observedProcessFailure.set(null);
                    ProbeResult probe = probeHealth();
                    if (probe.state() == ProbeState.HEALTHY && routingIdentityEstablished && executionIdentityCurrent()) warmupOllama();
                    else if (ownedProcess.get() == null) startNow();
                    else enterCooldown(recoveryReason);
                } finally {
                    restartInProgress = false;
                    halfOpen = false;
                    finishRecoveryAttempt();
                }
            }
        }, cooldownMs);
    }

    public void requestRecovery(String reason) {
        if (operatorRouteUnavailable()) return;
        if (!enabled || !autostart || stopping || state == State.COOLDOWN) return;
        String safeReason = switch (reason) {
            case "GPU_UNAVAILABLE", "GPU_DEVICE_LOST" -> "GPU_UNAVAILABLE";
            case "RUNNER_EXITED" -> "RUNNER_EXITED";
            case "INSUFFICIENT_MEMORY" -> "INSUFFICIENT_MEMORY";
            default -> "MODEL_LOAD_FAILED";
        };
        observedProcessFailure.set(safeReason);
        lastRecoveryReason = safeReason;
        transition(State.DEGRADED, safeReason);
        if (!recoveryInFlight.compareAndSet(false, true)) return;
        startupRuntime.execute(() -> {
            try {
                if (stopping || operatorRouteUnavailable()) return;
                if (!"INSUFFICIENT_MEMORY".equals(safeReason)
                        && ownedProcess.get() != null && reserveRestart(safeReason)) {
                    restartInProgress = true;
                    terminateOwnedProcess("startup_failure");
                    if (ownedProcess.get() == null) startNow();
                    else enterCooldown("RUNNER_EXITED");
                } else enterCooldown(safeReason);
            } finally {
                restartInProgress = false;
                finishRecoveryAttempt();
            }
        });
    }

    private void finishRecoveryAttempt() {
        recoveryInFlight.set(false);
        String pending = observedProcessFailure.get();
        // A reader can report failure after the final warmup check but before this release.
        if (pending != null && state == State.DEGRADED) requestRecovery(pending);
    }

    /** This postprocessor runs before ordinary configuration bean injection. */
    private boolean operatorRouteUnavailable() {
        if (env == null || !enabled || !autostart || stopping) return false;
        String reason;
        try {
            LlmRouterProperties routes = Binder.get(env).bind("llmrouter", LlmRouterProperties.class).orElse(null);
            String endpoint = configuredOllamaHost == null ? ollamaBaseUrl()
                    : "http://" + normalizeOllamaHostForEnv(configuredOllamaHost);
            var failure = LocalLlmGatewaySecurity.routePolicyFailure(routes, warmupModel, warmupModel, endpoint);
            if (failure == null) return false;
            reason = failure.reasonCode();
        } catch (RuntimeException invalidPolicy) {
            reason = "route_mapping_unconfirmed";
        }
        observedProcessFailure.set(null);
        transition(State.DISABLED, reason);
        TraceStore.put("localLlm.recovery.result", reason);
        traceStartupSnapshot("skipped", reason);
        traceWarmupSkipped();
        return true;
    }

    public boolean managesEndpoint(String baseUrl) {
        if (!enabled || !autostart || baseUrl == null) return false;
        try {
            URI candidate = URI.create(baseUrl);
            URI configured = URI.create(configuredOllamaHost == null ? ollamaBaseUrl()
                    : "http://" + normalizeOllamaHostForEnv(configuredOllamaHost));
            URI active = URI.create(ollamaBaseUrl());
            return sameEndpoint(configured, candidate) || sameEndpoint(active, candidate);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    public boolean isAvailable(String baseUrl) {
        return !managesEndpoint(baseUrl) || ((state == State.READY || (!warmupEnabled && state == State.SERVER_READY))
                && executionIdentityCurrent());
    }

    /** Resolve at HTTP dispatch, including for clients built before asynchronous startup completed. */
    public String resolveServiceUrl(String requested) {
        if (!managesEndpoint(requested)) return requested;
        if (!isAvailable(requested)) {
            throw new IllegalStateException("managed_ollama_not_ready");
        }
        URI input = URI.create(requested);
        URI active = URI.create(ollamaBaseUrl());
        if (input.getUserInfo() != null || !"http".equals(input.getScheme())) return requested;
        try {
            String resolved = new URI(active.getScheme(), null, active.getHost(), active.getPort(),
                    input.getPath(), input.getQuery(), null).toString();
            TraceStore.put("localLlm.route.endpointHash", SafeRedactor.hashValue(resolved));
            TraceStore.put("localLlm.route.serverPid", launchPid);
            TraceStore.put("localLlm.route.serverStartedAtEpochMs", serverStartedAtMillis);
            return resolved;
        } catch (java.net.URISyntaxException invalid) { throw new IllegalArgumentException("invalid_local_endpoint"); }
    }

    private static boolean sameEndpoint(URI left, URI right) {
        return Objects.equals(left.getScheme(), right.getScheme())
                && Objects.equals(left.getHost(), right.getHost()) && left.getPort() == right.getPort();
    }

    private boolean executionIdentityCurrent() {
        if (serverStartedAtMillis <= 0) return true; // Service may respond with process diagnostics unavailable.
        if (startupRuntime.processStartedAt(launchPid) == serverStartedAtMillis) return true;
        warmupVerifiedAtMillis = -1;
        gpuRoleVerified = false;
        exclusivePlacementVerified = false;
        modelReady = false;
        requestRecovery("SERVER_DOWN");
        return false;
    }

    public void recordFallback(String reason) {
        fallbackCount.incrementAndGet();
        fallbackUsed = true;
        TraceStore.put("localLlm.router.fallback", reason);
    }

    private static Map<String, java.util.concurrent.atomic.AtomicLong> recoveryCounters() {
        Map<String, java.util.concurrent.atomic.AtomicLong> counters = new LinkedHashMap<>();
        for (String reason : RECOVERY_REASONS) counters.put(reason, new java.util.concurrent.atomic.AtomicLong());
        return Map.copyOf(counters);
    }

    @Override
    public void bindTo(io.micrometer.core.instrument.MeterRegistry registry) {
        io.micrometer.core.instrument.FunctionCounter.builder("local.llm.start.attempts", startAttempts, Number::doubleValue).register(registry);
        io.micrometer.core.instrument.FunctionCounter.builder("local.llm.restarts", this, m -> m.restartCount).register(registry);
        recoverySuccesses.forEach((reason, count) -> io.micrometer.core.instrument.FunctionCounter
                .builder("local.llm.recovery.success", count, Number::doubleValue).tag("reason", reason).register(registry));
        recoveryFailures.forEach((reason, count) -> io.micrometer.core.instrument.FunctionCounter
                .builder("local.llm.recovery.failure", count, Number::doubleValue).tag("reason", reason).register(registry));
        io.micrometer.core.instrument.FunctionCounter.builder("local.llm.fallback", fallbackCount, Number::doubleValue).register(registry);
        io.micrometer.core.instrument.Gauge.builder("local.llm.ready", this, m -> m.modelReady ? 1 : 0).register(registry);
        io.micrometer.core.instrument.Gauge.builder("local.llm.start.duration.ms", this, m -> m.serverStartElapsedMs).register(registry);
        io.micrometer.core.instrument.Gauge.builder("local.llm.warmup.duration.ms", this, m -> m.warmupElapsedMs).register(registry);
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("state", state.name());
        snapshot.put("enabled", enabled);
        snapshot.put("autostart", autostart);
        snapshot.put("serviceResponding", lastProbe != null && lastProbe.state() == ProbeState.HEALTHY);
        snapshot.put("warmupVerified", modelReady && warmupEnabled);
        snapshot.put("embedVerified", embedVerified);
        snapshot.put("embeddingProvider", trimToNull(embeddingProvider) == null ? "unset" : embeddingProvider.trim());
        snapshot.put("embedWarmupApplies", embedWarmupApplies());
        snapshot.put("warmupStatus", warmupStatus);
        snapshot.put("warmupReason", warmupReason);
        snapshot.put("warmupTargetDim", Math.max(0, warmupDimensions));
        snapshot.put("warmupReturnedDim", warmupReturnedDim);
        snapshot.put("warmupElapsedMs", warmupElapsedMs);
        snapshot.put("warmupVerifiedAtEpochMs", warmupVerifiedAtMillis);
        snapshot.put("warmupEvidenceAgeMs", warmupVerifiedAtMillis < 0 ? -1 : startupRuntime.nowMillis() - warmupVerifiedAtMillis);
        snapshot.put("reasonCode", recoveryReason);
        snapshot.put("host", safeOllamaHostForLog(ollamaHost));
        snapshot.put("port", effectiveOllamaPort());
        snapshot.put("pid", launchPid);
        snapshot.put("serverStartedAtEpochMs", serverStartedAtMillis);
        snapshot.put("gpuRoleVerified", gpuRoleVerified);
        snapshot.put("exclusivePlacementVerified", exclusivePlacementVerified);
        snapshot.put("gpuAllocationObserved", gpuAllocationObserved);
        snapshot.put("externalRoleStatus", externalRoleStatus);
        snapshot.put("owned", ownedProcess.get() != null);
        snapshot.put("modelConfigured", hasText(warmupModel));
        snapshot.put("modelPresent", modelPresent);
        snapshot.put("modelReady", modelReady);
        snapshot.put("attempt", restartCount);
        snapshot.put("fallbackUsed", fallbackUsed);
        snapshot.put("cooldownRemainingMs", Math.max(0L, cooldownUntil - startupRuntime.nowMillis()));
        return Map.copyOf(snapshot);
    }

    private void logSecretPresence() {
        log.info("[AWX][runtime-config][keys] OPENAI_API_KEY.present={} LLM_API_KEY.present={} "
                        + "NAVER_CLIENT_ID.present={} NAVER_CLIENT_SECRET.present={} NAVER_KEYS.present={}",
                hasConfiguredValue("OPENAI_API_KEY"),
                hasConfiguredValue("LLM_API_KEY"),
                hasConfiguredValue("NAVER_CLIENT_ID"),
                hasConfiguredValue("NAVER_CLIENT_SECRET"),
                hasConfiguredValue("NAVER_KEYS"));
    }

    private boolean hasConfiguredValue(String key) {
        String value = env == null ? null : env.getProperty(key);
        return value != null && !value.isBlank();
    }

    private String effectiveHealthCheckUrl() {
        String configured = trimToNull(healthCheckUrl);
        if (configuredOllamaHost != null && !Objects.equals(configuredOllamaHost, ollamaHost))
            return ollamaBaseUrl() + "/api/version";
        return configured != null ? configured : ollamaBaseUrl() + "/api/version";
    }

    private String ollamaBaseUrl() {
        String host = normalizeOllamaHostForEnv(ollamaHost);
        if (host.startsWith("http://") || host.startsWith("https://")) {
            int idx = host.indexOf("/api/");
            return idx >= 0 ? host.substring(0, idx) : stripTrailingSlash(host);
        }
        return "http://" + stripTrailingSlash(host);
    }

    private String effectiveStartCommand() {
        String cudaDevice = normalizeCudaVisibleDevice(cudaVisibleDevice);
        String explicit = trimToNull(startCommand);
        if (explicit != null) {
            if (cudaDevice != null) {
                throw new IllegalStateException("cuda_pin_conflicts_with_explicit_start_command");
            }
            return explicit;
        }
        return generatedWindowsStartCommand(ollamaHost, cudaDevice);
    }

    private void traceStartupSnapshot(String status, String reason) {
        TraceStore.put("localLlm.startup.enabled", enabled);
        TraceStore.put("localLlm.startup.autostart", autostart);
        TraceStore.put("localLlm.startup.status", SafeRedactor.traceLabelOrFallback(status, "unknown"));
        String safeReason = SafeRedactor.traceLabel(reason);
        if (safeReason != null && !safeReason.isBlank()) {
            TraceStore.put("localLlm.startup.reason", safeReason);
        }
        TraceStore.put("localLlm.startup.host", safeOllamaHostForLog(ollamaHost));
        TraceStore.put("localLlm.startup.hostHash", safeOllamaHostHash(ollamaHost));
        String healthUrl = effectiveHealthCheckUrl();
        TraceStore.put("localLlm.startup.healthUrlHost", safeUrlHost(healthUrl));
        TraceStore.put("localLlm.startup.healthUrlHash", safeUrlHash(healthUrl));
        TraceStore.put("localLlm.startup.healthUrlLength", safeUrlLength(healthUrl));
        TraceStore.put("localLlm.startup.warmupTargetDim", Math.max(0, warmupDimensions));
        String cudaDevice = normalizeCudaVisibleDevice(cudaVisibleDevice);
        TraceStore.put("localLlm.startup.cudaPinned", cudaDevice != null);
        TraceStore.put("localLlm.startup.cudaDeviceHash", cudaDevice == null ? "" : SafeRedactor.hashValue(cudaDevice));
        TraceStore.put("localLlm.startup.cudaDeviceLength", cudaDevice == null ? 0 : cudaDevice.length());
        TraceStore.put("localLlm.startup.cudaPinReason", SafeRedactor.traceLabelOrFallback(cudaPinReason, "unknown"));
        TraceStore.put("localLlm.startup.launchAttempted", launchAttempted);
        TraceStore.put("localLlm.startup.reused", reused);
        TraceStore.put("localLlm.startup.launchPid", Math.max(0L, launchPid));
        TraceStore.put("localLlm.startup.healthAttemptCount", Math.max(0, healthAttemptCount));
        TraceStore.put("localLlm.startup.elapsedMs", elapsedMs());
        TraceStore.put("localLlm.startup.port", Math.max(0, lastProbe.port()));
        TraceStore.put("localLlm.startup.portPid", Math.max(0L, lastProbe.pid()));
        TraceStore.put("localLlm.startup.portState", lastProbe.state().traceValue());
        TraceStore.put("localLlm.startup.portProcessName", safeProcessName(lastProbe.processName()));
        String logPath = stderrLogPath == null ? "" : stderrLogPath.toString();
        TraceStore.put("localLlm.startup.stderrLogPathHash", logPath.isBlank() ? "" : SafeRedactor.hashValue(logPath));
        TraceStore.put("localLlm.startup.stderrLogPathLength", logPath.length());
    }

    private void traceWarmupSkipped() {
        traceWarmup("skipped", 0, "disabled");
    }

    private void traceWarmup(String status, int returnedDim, String reason) {
        warmupStatus = status;
        warmupReason = reason;
        warmupReturnedDim = Math.max(0, returnedDim);
        TraceStore.put("localLlm.warmup.enabled", warmupEnabled);
        TraceStore.put("localLlm.warmup.status", SafeRedactor.traceLabelOrFallback(status, "unknown"));
        TraceStore.put("localLlm.warmup.modelHash", SafeRedactor.hashValue(warmupModel));
        TraceStore.put("localLlm.warmup.modelLength", warmupModel == null ? 0 : warmupModel.length());
        TraceStore.put("localLlm.warmup.targetDim", Math.max(0, warmupDimensions));
        TraceStore.put("localLlm.warmup.returnedDim", Math.max(0, returnedDim));
        String safeReason = SafeRedactor.traceLabel(reason);
        if (safeReason != null && !safeReason.isBlank()) {
            TraceStore.put("localLlm.warmup.reason", safeReason);
        }
    }

    static String generatedWindowsStartCommand(String host) {
        String h = normalizeOllamaHostForEnv(host);
        return "start \"Ollama " + h + "\" cmd.exe /k \"set OLLAMA_HOST=" + h + "&& ollama serve\"";
    }

    static String generatedWindowsStartCommand(String host, String cudaVisibleDevice) {
        String cudaDevice = normalizeCudaVisibleDevice(cudaVisibleDevice);
        if (cudaDevice == null) {
            return generatedWindowsStartCommand(host);
        }
        String h = normalizeOllamaHostForEnv(host);
        return "start \"Ollama " + h + "\" cmd.exe /k \"set \"OLLAMA_HOST=" + h
                + "\"&& set \"CUDA_VISIBLE_DEVICES=" + cudaDevice + "\"&& set \"OLLAMA_VULKAN=0\"&& ollama serve\"";
    }

    static String normalizeCudaVisibleDevice(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (!CUDA_VISIBLE_DEVICE_UUID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("invalid_cuda_visible_device");
        }
        return trimmed;
    }

    static String normalizeOllamaHostForEnv(String host) {
        String h = trimToNull(host);
        if (h == null) {
            return DEFAULT_OLLAMA_HOST_ENV;
        }
        if (h.startsWith("http://") || h.startsWith("https://")) {
            try {
                URI uri = URI.create(h);
                String uriHost = uri.getHost();
                if (uriHost != null && !uriHost.isBlank()) {
                    String normalizedHost = uriHost.contains(":") && !uriHost.startsWith("[")
                            ? "[" + uriHost + "]"
                            : uriHost;
                    int port = uri.getPort();
                    return safeOllamaHostEnvOrDefault(port >= 0 ? normalizedHost + ":" + port : normalizedHost);
                }
            } catch (Exception ignore) {
                traceSuppressed("ollama.hostUri", ignore);
                // fall through to string cleanup
            }
            h = h.replaceFirst("^https?://", "");
        }
        int slash = h.indexOf('/');
        return safeOllamaHostEnvOrDefault(slash >= 0 ? h.substring(0, slash) : h);
    }

    private static String safeOllamaHostEnvOrDefault(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return DEFAULT_OLLAMA_HOST_ENV;
        }
        boolean hasHostChar = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c)
                    || c == '.'
                    || c == '-'
                    || c == ':'
                    || c == '['
                    || c == ']';
            if (!allowed) {
                return DEFAULT_OLLAMA_HOST_ENV;
            }
            if (Character.isLetterOrDigit(c) || c == '[') {
                hasHostChar = true;
            }
        }
        return hasHostChar ? value : DEFAULT_OLLAMA_HOST_ENV;
    }

    private static String readBounded(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        byte[] buf = stream.readNBytes(4096);
        return new String(buf, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
    }

    private static String shortErr(Throwable t) {
        if (t == null) {
            return "";
        }
        String msg = t.getMessage();
        String s = msg == null || msg.isBlank()
                ? "startup_failure messagePresent=false"
                : "startup_failure messagePresent=true messageHash=" + SafeRedactor.hashValue(msg)
                + " messageLength=" + msg.length();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private static String bodyHash(String s) {
        return s == null || s.isBlank() ? "" : SafeRedactor.hashValue(s);
    }

    private static int bodyLength(String s) {
        return s == null ? 0 : s.length();
    }

    private static String safeUrlDiagnostic(String url) {
        return "urlHost=" + safeUrlHost(url)
                + " urlHash=" + safeUrlHash(url)
                + " urlLength=" + safeUrlLength(url);
    }

    private static String safeUrlHost(String url) {
        String value = trimToNull(url);
        if (value == null) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            int port = uri.getPort();
            return port > 0 ? host.toLowerCase(java.util.Locale.ROOT) + ":" + port : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ignore) {
            traceSuppressed("ollama.urlHost", ignore);
            return "";
        }
    }

    private static String safeUrlHash(String url) {
        String value = trimToNull(url);
        return value == null ? "" : SafeRedactor.hashValue(value);
    }

    private static int safeUrlLength(String url) {
        String value = trimToNull(url);
        return value == null ? 0 : value.length();
    }

    private static String safeOllamaHostForLog(String host) {
        String h = normalizeOllamaHostForEnv(host);
        int at = h.lastIndexOf('@');
        return at >= 0 && at + 1 < h.length() ? h.substring(at + 1) : h;
    }

    private static String safeOllamaHostHash(String host) {
        return SafeRedactor.hashValue(normalizeOllamaHostForEnv(host));
    }

    private static String stripTrailingSlash(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static int boundedMillis(Duration duration, int fallback) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return fallback;
        }
        return (int) Math.min(Integer.MAX_VALUE, duration.toMillis());
    }

    private long elapsedMs() {
        return Math.max(0L, startupRuntime.nowMillis() - startupStartedMs);
    }

    private int effectiveOllamaPort() {
        try {
            int port = URI.create(ollamaBaseUrl()).getPort();
            return port > 0 ? port : 11435;
        } catch (Exception ignored) {
            traceSuppressed("ollama.port", ignored);
            return 11435;
        }
    }

    private static long safeDeadline(long start, long duration) {
        long bounded = Math.max(1L, duration);
        return start > Long.MAX_VALUE - bounded ? Long.MAX_VALUE : start + bounded;
    }

    private static String safeProcessName(String value) {
        String name = trimToNull(value);
        if (name == null) {
            return "unknown";
        }
        try {
            Path fileName = Path.of(name).getFileName();
            if (fileName != null) {
                name = fileName.toString();
            }
        } catch (Exception ignored) {
            traceSuppressed("ollama.processName", ignored);
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    private static String safeLogPath(Path path) {
        if (path == null) {
            return "";
        }
        return path.normalize().toString().replace('\r', '_').replace('\n', '_');
    }

    private static boolean isExecutableMissing(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
            if (normalized.contains("createprocess error=2")
                    || normalized.contains("cannot run program") && normalized.contains("not found")
                    || normalized.contains("the system cannot find the file")) {
                return true;
            }
        }
        return false;
    }

    enum ProbeState {
        HEALTHY("healthy"),
        UNHEALTHY_LISTENER("unhealthy_listener"),
        NOT_LISTENING("not_listening");

        private final String traceValue;

        ProbeState(String traceValue) {
            this.traceValue = traceValue;
        }

        String traceValue() {
            return traceValue;
        }
    }

    record ProbeResult(ProbeState state, int httpStatus, long pid, String processName, int port, String reason) {
        ProbeResult {
            state = state == null ? ProbeState.NOT_LISTENING : state;
            processName = processName == null ? "" : processName;
            reason = reason == null ? "unknown" : reason;
            pid = Math.max(0L, pid);
            port = Math.max(0, port);
        }

        static ProbeResult notListening(int port, String reason) {
            return new ProbeResult(ProbeState.NOT_LISTENING, 0, 0L, "", port, reason);
        }
    }

    record LaunchRequest(List<String> command, Map<String, String> environment, Path stdoutLog, Path stderrLog) {
        LaunchRequest {
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
        }
    }

    enum TerminationStatus {
        ALREADY_EXITED("already_exited"),
        CAPTURED_HANDLES_TERMINATED("captured_handles_terminated"),
        STILL_ALIVE("still_alive"),
        INTERRUPTED_STILL_ALIVE("interrupted_still_alive"),
        TREE_SCOPE_INCOMPLETE("tree_scope_incomplete"),
        TERMINATION_ERROR("termination_error");

        private final String traceValue;

        TerminationStatus(String traceValue) {
            this.traceValue = traceValue;
        }

        String traceValue() {
            return traceValue;
        }
    }

    record TerminationResult(
            TerminationStatus status,
            boolean aliveAfter,
            boolean interrupted,
            int capturedHandleCount,
            int forcedHandleCount) {
        TerminationResult {
            status = status == null ? TerminationStatus.TERMINATION_ERROR : status;
            capturedHandleCount = Math.max(0, capturedHandleCount);
            forcedHandleCount = Math.max(0, forcedHandleCount);
        }
    }

    interface OwnedProcess {
        long pid();

        TerminationResult terminate(long gracefulMs, long forcedMs);
    }

    interface ExactProcessHandle {
        long pid();

        boolean isAlive();

        List<ExactProcessHandle> descendants();

        boolean destroy();

        boolean destroyForcibly();
    }

    interface ProcessTreeOps {
        ExactProcessHandle root(Process process);

        long nanoTime();

        void sleepMillis(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    @FunctionalInterface
    interface OwnedProcessFactory {
        OwnedProcess create(Process process, ProcessTreeOps ops, boolean treeScopeMayDetach);
    }

    record LaunchResult(OwnedProcess ownedProcess, Process process) {
        LaunchResult(OwnedProcess ownedProcess) { this(ownedProcess, null); }
        LaunchResult {
            ownedProcess = Objects.requireNonNull(ownedProcess, "ownedProcess");
        }

        long pid() {
            return Math.max(0L, ownedProcess.pid());
        }
    }

    static final class ProcessTreeOwnedProcess implements OwnedProcess {
        private static final long MAX_WAIT_SLICE_MS = 25L;
        private final ExactProcessHandle root;
        private final ProcessTreeOps ops;
        private final boolean treeScopeMayDetach;

        ProcessTreeOwnedProcess(
                ExactProcessHandle root,
                ProcessTreeOps ops,
                boolean treeScopeMayDetach) {
            this.root = Objects.requireNonNull(root, "root");
            this.ops = Objects.requireNonNull(ops, "ops");
            this.treeScopeMayDetach = treeScopeMayDetach;
        }

        @Override
        public long pid() {
            return Math.max(0L, root.pid());
        }

        @Override
        public TerminationResult terminate(long gracefulMs, long forcedMs) {
            LinkedHashSet<ExactProcessHandle> descendants = new LinkedHashSet<>();
            if (Thread.currentThread().isInterrupted()) {
                return interruptedResult(descendants, 0);
            }
            try {
                refreshDescendants(descendants);
                if (!anyAlive(descendants)) {
                    return new TerminationResult(
                            TerminationStatus.ALREADY_EXITED, false, false,
                            capturedCount(descendants), 0);
                }

                signal(descendants, false);
                if (awaitStopped(descendants, gracefulMs)) {
                    return completedResult(descendants, 0);
                }

                refreshDescendants(descendants);
                int forcedCount = signal(descendants, true);
                if (awaitStopped(descendants, forcedMs)) {
                    return completedResult(descendants, forcedCount);
                }
                return new TerminationResult(
                        TerminationStatus.STILL_ALIVE, true, false,
                        capturedCount(descendants), forcedCount);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return interruptedResult(descendants, 0);
            } catch (RuntimeException failure) {
                return new TerminationResult(
                        TerminationStatus.TERMINATION_ERROR, true, false,
                        capturedCount(descendants), 0);
            }
        }

        private TerminationResult completedResult(
                LinkedHashSet<ExactProcessHandle> descendants,
                int forcedCount) {
            return new TerminationResult(
                    treeScopeMayDetach
                            ? TerminationStatus.TREE_SCOPE_INCOMPLETE
                            : TerminationStatus.CAPTURED_HANDLES_TERMINATED,
                    false, false, capturedCount(descendants), forcedCount);
        }

        private TerminationResult interruptedResult(
                LinkedHashSet<ExactProcessHandle> descendants,
                int forcedCount) {
            return new TerminationResult(
                    TerminationStatus.INTERRUPTED_STILL_ALIVE,
                    true, true, capturedCount(descendants), forcedCount);
        }

        private void refreshDescendants(LinkedHashSet<ExactProcessHandle> descendants) {
            List<ExactProcessHandle> observed = root.descendants();
            if (observed == null) {
                return;
            }
            for (ExactProcessHandle handle : observed) {
                if (handle != null && handle != root) {
                    descendants.add(handle);
                }
            }
        }

        private int signal(LinkedHashSet<ExactProcessHandle> descendants, boolean force) {
            int signalled = 0;
            for (ExactProcessHandle handle : descendants) {
                if (handle.isAlive()) {
                    if (force) {
                        handle.destroyForcibly();
                    } else {
                        handle.destroy();
                    }
                    signalled++;
                }
            }
            if (root.isAlive()) {
                if (force) {
                    root.destroyForcibly();
                } else {
                    root.destroy();
                }
                signalled++;
            }
            return signalled;
        }

        private boolean awaitStopped(
                LinkedHashSet<ExactProcessHandle> descendants,
                long timeoutMs) throws InterruptedException {
            long boundedMs = Math.max(0L, timeoutMs);
            long started = ops.nanoTime();
            long timeoutNanos = boundedMs > Long.MAX_VALUE / 1_000_000L
                    ? Long.MAX_VALUE
                    : boundedMs * 1_000_000L;
            while (anyAlive(descendants)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("owned process termination interrupted");
                }
                long remainingNanos = remainingNanos(started, timeoutNanos, ops.nanoTime());
                if (remainingNanos == 0L) {
                    return false;
                }
                long remainingMs = ceilNanosToMillis(remainingNanos);
                ops.sleepMillis(Math.min(MAX_WAIT_SLICE_MS, remainingMs));
            }
            return true;
        }

        private static long remainingNanos(long started, long timeoutNanos, long now) {
            if (timeoutNanos <= 0L) {
                return 0L;
            }
            long elapsed = now - started;
            if (elapsed < 0L) {
                return timeoutNanos;
            }
            return elapsed >= timeoutNanos ? 0L : timeoutNanos - elapsed;
        }

        private static long ceilNanosToMillis(long nanos) {
            long wholeMillis = nanos / 1_000_000L;
            return Math.max(1L, wholeMillis + (nanos % 1_000_000L == 0L ? 0L : 1L));
        }

        private boolean anyAlive(LinkedHashSet<ExactProcessHandle> descendants) {
            for (ExactProcessHandle handle : descendants) {
                if (handle.isAlive()) {
                    return true;
                }
            }
            return root.isAlive();
        }

        private int capturedCount(LinkedHashSet<ExactProcessHandle> descendants) {
            return descendants.size() + 1;
        }
    }

    interface StartupRuntime {
        default long processStartedAt(long pid) { return 0; }
        default Set<String> serverGpuUuids(long pid, int timeoutMs) { return Set.of(); }
        default void onFailure(java.util.function.Consumer<String> listener) { }
        default void execute(Runnable work) { work.run(); }
        default void schedule(Runnable work, long delayMs) { }
        default void close() { }
        default JsonNode getJson(String url, int timeoutMs) throws IOException { return requestJson(url, null, timeoutMs); }
        default JsonNode postJson(String url, Map<String, Object> body, long timeoutMs) throws IOException {
            return requestJson(url, body, timeoutMs);
        }
        ProbeResult probe(String healthUrl, int timeoutMs);

        String resolveOllamaExecutable(int timeoutMs);

        List<String> discoverGpuUuids(int timeoutMs);

        LaunchResult launch(LaunchRequest request) throws IOException;

        long nowMillis();

        void sleepMillis(long millis) throws InterruptedException;
    }

    private record SystemExactProcessHandle(ProcessHandle delegate) implements ExactProcessHandle {
        private SystemExactProcessHandle {
            delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public long pid() {
            return Math.max(0L, delegate.pid());
        }

        @Override
        public boolean isAlive() {
            return delegate.isAlive();
        }

        @Override
        public List<ExactProcessHandle> descendants() {
            return delegate.descendants()
                    .map(SystemExactProcessHandle::new)
                    .map(handle -> (ExactProcessHandle) handle)
                    .toList();
        }

        @Override
        public boolean destroy() {
            return delegate.destroy();
        }

        @Override
        public boolean destroyForcibly() {
            return delegate.destroyForcibly();
        }
    }

    private static final class SystemProcessTreeOps implements ProcessTreeOps {
        @Override
        public ExactProcessHandle root(Process process) {
            return new SystemExactProcessHandle(Objects.requireNonNull(process, "process").toHandle());
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void sleepMillis(long millis) throws InterruptedException {
            Thread.sleep(Math.max(1L, millis));
        }
    }

    static final class SystemStartupRuntime implements StartupRuntime {
        @Override public long processStartedAt(long pid) {
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .flatMap(p -> p.info().startInstant()).map(java.time.Instant::toEpochMilli).orElse(0L);
        }

        @Override public Set<String> serverGpuUuids(long pid, int timeoutMs) {
            long before = processStartedAt(pid);
            if (before <= 0) return Set.of();
            try {
                Set<Long> descendants = new LinkedHashSet<>();
                ProcessHandle.of(pid).ifPresent(p -> p.descendants().forEach(c -> descendants.add(c.pid())));
                CommandResult result = runCommand(List.of("nvidia-smi", "--query-compute-apps=gpu_uuid,pid",
                        "--format=csv,noheader,nounits"), timeoutMs);
                if (result.exitCode() != 0 || before != processStartedAt(pid)) return Set.of();
                Set<String> observed = new LinkedHashSet<>();
                for (String line : result.output().split("\\R")) {
                    String[] cells = line.split(",");
                    if (cells.length == 2 && CUDA_VISIBLE_DEVICE_UUID.matcher(cells[0].trim()).matches()
                            && descendants.contains(Long.parseLong(cells[1].trim()))) observed.add(cells[0].trim());
                }
                return Set.copyOf(observed);
            } catch (Exception unavailable) { return Set.of(); }
        }
        private final AtomicReference<Process> activeOutputProcess = new AtomicReference<>();
        private volatile java.util.function.Consumer<String> failureListener = ignored -> { };
        @Override public void onFailure(java.util.function.Consumer<String> listener) { failureListener = listener; }
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(work -> {
            Thread thread = new Thread(work, "ollama-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
        private final Deque<String> recentReasons = new ArrayDeque<>();
        private final ProcessStarter processStarter;
        private final OwnedProcessFactory ownedProcessFactory;
        private final ProcessTreeOps processTreeOps;

        @Override
        public void execute(Runnable work) {
            try { executor.execute(work); } catch (RejectedExecutionException stopped) { }
        }

        @Override
        public void schedule(Runnable work, long delayMs) {
            try { executor.schedule(work, delayMs, TimeUnit.MILLISECONDS); } catch (RejectedExecutionException stopped) { }
        }

        @Override
        public void close() { executor.shutdownNow(); }

        SystemStartupRuntime() {
            this(ProcessBuilder::start,
                    (process, ops, treeScopeMayDetach) -> new ProcessTreeOwnedProcess(
                            ops.root(process), ops, treeScopeMayDetach),
                    new SystemProcessTreeOps());
        }

        SystemStartupRuntime(
                ProcessStarter processStarter,
                OwnedProcessFactory ownedProcessFactory,
                ProcessTreeOps processTreeOps) {
            this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
            this.ownedProcessFactory = Objects.requireNonNull(ownedProcessFactory, "ownedProcessFactory");
            this.processTreeOps = Objects.requireNonNull(processTreeOps, "processTreeOps");
        }

        @Override
        public ProbeResult probe(String healthUrl, int timeoutMs) {
            int port = portFromUrl(healthUrl);
            VersionProbe version = probeVersion(healthUrl, timeoutMs);
            ListenerInfo listener = findListener(port, timeoutMs);
            if (version.healthy()) {
                return new ProbeResult(ProbeState.HEALTHY, version.httpStatus(), listener.pid(),
                        listener.processName(), port, "version_ok");
            }
            if (listener.listening()) {
                return new ProbeResult(ProbeState.UNHEALTHY_LISTENER, version.httpStatus(), listener.pid(),
                        listener.processName(), port, version.reason());
            }
            return new ProbeResult(ProbeState.NOT_LISTENING, version.httpStatus(), 0L, "", port, version.reason());
        }

        @Override
        public String resolveOllamaExecutable(int timeoutMs) {
            List<String> command = isWindows()
                    ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "$c=Get-Command ollama -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1; if($null -ne $c){[Console]::Out.Write($c.Source)}")
                    : List.of("which", "ollama");
            try {
                CommandResult result = runCommand(command, timeoutMs);
                for (String line : result.output().split("\\R")) {
                    String candidate = trimToNull(line);
                    if (candidate == null) {
                        continue;
                    }
                    try {
                        if (Files.isRegularFile(Path.of(candidate))) {
                            return candidate;
                        }
                    } catch (Exception ignored) {
                        traceSuppressed("ollama.executablePath", ignored);
                    }
                }
            } catch (Exception ignored) {
                traceSuppressed("ollama.resolveExecutable", ignored);
            }
            String localAppData = System.getenv("LOCALAPPDATA");
            if (isWindows() && hasText(localAppData)) {
                Path candidate = Path.of(localAppData, "Programs", "Ollama", "ollama.exe");
                if (Files.isRegularFile(candidate)) return candidate.toString();
            }
            return null;
        }

        @Override
        public List<String> discoverGpuUuids(int timeoutMs) {
            try {
                CommandResult result = runCommand(List.of(
                        "nvidia-smi",
                        "--query-gpu=uuid",
                        "--format=csv,noheader,nounits"), timeoutMs);
                Set<String> unique = new LinkedHashSet<>();
                for (String line : result.output().split("\\R")) {
                    // Error text can contain a lost device UUID; only complete inventory rows count.
                    String candidate = line.trim();
                    if (CUDA_VISIBLE_DEVICE_UUID.matcher(candidate).matches()) unique.add(candidate);
                }
                return List.copyOf(unique);
            } catch (Exception ignored) {
                traceSuppressed("ollama.cudaDiscovery", ignored);
                return List.of();
            }
        }

        @Override
        public LaunchResult launch(LaunchRequest request) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(request.command());
            builder.environment().putAll(request.environment());
            builder.redirectErrorStream(true);
            Process process = processStarter.start(builder);
            boolean treeScopeMayDetach = shellBacked(request.command());
            try {
                OwnedProcess owned = ownedProcessFactory.create(process, processTreeOps, treeScopeMayDetach);
                activeOutputProcess.set(process);
                Thread reader = new Thread(() -> drainProcessOutput(process), "ollama-process-output");
                reader.setDaemon(true);
                reader.start();
                return new LaunchResult(owned, process);
            } catch (RuntimeException | Error transferFailure) {
                rollbackTransferredProcess(process, treeScopeMayDetach, transferFailure);
                throw transferFailure;
            }
        }

        private void drainProcessOutput(Process process) {
            InputStream input = process.getInputStream();
            if (input == null) return;
            try (input) {
                byte[] bytes = new byte[4096];
                String tail = "";
                int read;
                while ((read = input.read(bytes)) != -1) {
                    String text = tail + new String(bytes, 0, read, StandardCharsets.UTF_8);
                    String reason = classifyFailure(new IOException(text));
                    // Retain categorical output only; raw prompts and responses never enter the buffer.
                    synchronized (recentReasons) {
                        if (recentReasons.size() == 64) recentReasons.removeFirst();
                        recentReasons.addLast(reason);
                    }
                    if (activeOutputProcess.get() == process && process.isAlive()
                            && ("GPU_UNAVAILABLE".equals(reason) || "RUNNER_EXITED".equals(reason)
                            || "INSUFFICIENT_MEMORY".equals(reason))) {
                        failureListener.accept(reason);
                    }
                    tail = text.substring(Math.max(0, text.length() - 128));
                }
            } catch (IOException closed) {
                traceSuppressed("ollama.processOutput", closed);
            }
        }

        private void rollbackTransferredProcess(
                Process process,
                boolean treeScopeMayDetach,
                Throwable transferFailure) {
            try {
                new ProcessTreeOwnedProcess(
                        processTreeOps.root(process), processTreeOps, treeScopeMayDetach)
                        .terminate(OWNED_PROCESS_GRACEFUL_STOP_MS, OWNED_PROCESS_FORCED_STOP_MS);
            } catch (Throwable rollbackFailure) {
                try {
                    TraceStore.put("localLlm.shutdown.transferRollback", "termination_error");
                } catch (RuntimeException traceFailure) {
                    // Preserve the original ownership-transfer failure even if diagnostics fail.
                }
                transferFailure.addSuppressed(
                        new IllegalStateException("owned_process_transfer_rollback_failed"));
            }
        }

        private static boolean shellBacked(List<String> command) {
            if (command == null || command.isEmpty()) {
                return true;
            }
            String first = command.get(0);
            if (first == null || first.isBlank()) {
                return true;
            }
            String executable;
            try {
                Path name = Path.of(first).getFileName();
                executable = name == null ? first : name.toString();
            } catch (RuntimeException invalidPath) {
                executable = first;
            }
            String normalized = executable.toLowerCase(Locale.ROOT);
            return normalized.equals("cmd")
                    || normalized.equals("cmd.exe")
                    || normalized.equals("sh")
                    || normalized.equals("bash")
                    || normalized.equals("zsh")
                    || normalized.equals("powershell")
                    || normalized.equals("powershell.exe")
                    || normalized.equals("pwsh")
                    || normalized.equals("pwsh.exe");
        }

        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleepMillis(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }

        private static VersionProbe probeVersion(String healthUrl, int timeoutMs) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(healthUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(Math.max(1, timeoutMs));
                connection.setReadTimeout(Math.max(1, timeoutMs));
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String response = readBounded(stream);
                if (status < 200 || status >= 300) {
                    return new VersionProbe(false, status, "http_status");
                }
                JsonNode root = response.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(response);
                String version = root.path("version").asText("").trim();
                return version.isBlank()
                        ? new VersionProbe(false, status, "version_invalid")
                        : new VersionProbe(true, status, "version_ok");
            } catch (Exception ignored) {
                traceSuppressed("ollama.isServiceRunning", ignored);
                return new VersionProbe(false, 0, "connect_failed");
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private static ListenerInfo findListener(int port, int timeoutMs) {
            if (port <= 0) {
                return ListenerInfo.none();
            }
            if (isWindows()) {
                String script = "$c=Get-NetTCPConnection -State Listen -LocalPort " + port
                        + " -ErrorAction SilentlyContinue | Select-Object -First 1;"
                        + "if($null -ne $c){$p=Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue;"
                        + "$n=if($null -ne $p){$p.ProcessName}else{'unknown'};"
                        + "[Console]::Out.Write(([string]$c.OwningProcess)+'|'+$n)}";
                try {
                    CommandResult result = runCommand(List.of(
                            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), timeoutMs);
                    String output = trimToNull(result.output());
                    if (output != null) {
                        String[] parts = output.split("\\|", 2);
                        long pid = Long.parseLong(parts[0].trim());
                        String name = parts.length > 1 ? parts[1].trim() : "unknown";
                        return new ListenerInfo(true, pid, name);
                    }
                } catch (Exception ignored) {
                    traceSuppressed("ollama.portOwner", ignored);
                }
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), Math.max(1, timeoutMs));
                return new ListenerInfo(true, 0L, "unknown");
            } catch (Exception ignored) {
                return ListenerInfo.none();
            }
        }

        private static int portFromUrl(String value) {
            try {
                URI uri = URI.create(value);
                return uri.getPort() > 0 ? uri.getPort() : 11435;
            } catch (Exception ignored) {
                return 11435;
            }
        }

        private static CommandResult runCommand(List<String> command, int timeoutMs) throws IOException {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> drainBounded(process.getInputStream(), output), "ollama-command-output");
            reader.setDaemon(true);
            reader.start();
            boolean completed;
            try {
                completed = process.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    process.waitFor(500L, TimeUnit.MILLISECONDS);
                }
                reader.join(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("local command interrupted", interrupted);
            }
            int exitCode = completed ? process.exitValue() : -1;
            return new CommandResult(completed, exitCode, output.toString(StandardCharsets.UTF_8));
        }

        private static void drainBounded(InputStream input, ByteArrayOutputStream output) {
            byte[] buffer = new byte[4096];
            int stored = 0;
            try (input) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    int keep = Math.min(read, Math.max(0, 65536 - stored));
                    if (keep > 0) {
                        output.write(buffer, 0, keep);
                        stored += keep;
                    }
                }
            } catch (IOException ignored) {
                traceSuppressed("ollama.commandOutput", ignored);
            }
        }
    }

    private record VersionProbe(boolean healthy, int httpStatus, String reason) {
    }

    private record ListenerInfo(boolean listening, long pid, String processName) {
        private static ListenerInfo none() {
            return new ListenerInfo(false, 0L, "");
        }
    }

    private record CommandResult(boolean completed, int exitCode, String output) {
    }

    private static final class StartupFailureException extends IllegalStateException {
        private StartupFailureException(String reason, Throwable cause) {
            super("Local Ollama startup failed: " + SafeRedactor.traceLabelOrFallback(reason, "unknown"), cause);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = "local_llm_suppressed";
        TraceStore.put("localLlm.suppressed.stage", safeStage);
        TraceStore.put("localLlm.suppressed.errorType", errorType);
        TraceStore.put("localLlm.suppressed." + safeStage, true);
        TraceStore.put("localLlm.suppressed." + safeStage + ".errorType", errorType);
    }

    private boolean envBool(String key, boolean fallback) {
        if (env == null) {
            return fallback;
        }
        Boolean value = env.getProperty(key, Boolean.class);
        return value == null ? fallback : value;
    }

    private int envInt(String key, int fallback) {
        if (env == null) {
            return fallback;
        }
        Integer value = env.getProperty(key, Integer.class);
        return value == null ? fallback : value;
    }

    private long envLong(String key, long fallback) {
        if (env == null) {
            return fallback;
        }
        Long value = env.getProperty(key, Long.class);
        return value == null ? fallback : value;
    }

    private Duration envDuration(String key, Duration fallback) {
        if (env == null) {
            return fallback;
        }
        Duration value = env.getProperty(key, Duration.class);
        return value == null ? fallback : value;
    }

    private String envString(String key, String fallback) {
        if (env == null) {
            return fallback;
        }
        String value = env.getProperty(key);
        return value == null ? fallback : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    static java.util.List<String> splitCommand(String cmd) {
        java.util.List<String> out = new java.util.ArrayList<>();
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
