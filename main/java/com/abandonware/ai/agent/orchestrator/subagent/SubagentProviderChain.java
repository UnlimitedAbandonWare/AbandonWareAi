package com.abandonware.ai.agent.orchestrator.subagent;

import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Executes one task against an ordered provider list with bounded fallback and circuits. */
public final class SubagentProviderChain {

    private static final long DEFAULT_COOLDOWN_MS = 60_000L;
    private static final int DEFAULT_MAX_RETRIES = 1;
    private static final long DEFAULT_RETRY_BACKOFF_MS = 50L;
    private static final Pattern TRANSIENT_5XX = Pattern.compile(
            "(?i)(?:http(?:status|_status)?|status)[\\s:=_-]*5\\d\\d");

    private final List<SubagentProvider> providers;
    private final LlmGatewayFailureClassifier classifier;
    private final long cooldownNanos;
    private final LongSupplier nanoTime;
    private final int maxRetries;
    private final long retryBackoffMs;
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();

    public SubagentProviderChain(List<SubagentProvider> providers,
                                 LlmGatewayFailureClassifier classifier) {
        this(providers, classifier, DEFAULT_COOLDOWN_MS, System::nanoTime,
                DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF_MS);
    }

    public SubagentProviderChain(List<SubagentProvider> providers,
                                 LlmGatewayFailureClassifier classifier,
                                 long cooldownMs,
                                 LongSupplier nanoTime) {
        this(providers, classifier, cooldownMs, nanoTime,
                DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF_MS);
    }

    public SubagentProviderChain(List<SubagentProvider> providers,
                                 LlmGatewayFailureClassifier classifier,
                                 long cooldownMs,
                                 LongSupplier nanoTime,
                                 int maxRetries,
                                 long retryBackoffMs) {
        List<SubagentProvider> ordered = new ArrayList<>(providers == null ? List.of() : providers);
        ordered.removeIf(Objects::isNull);
        ordered.sort(Comparator.comparingInt(SubagentProvider::order).thenComparing(SubagentProvider::id));
        this.providers = List.copyOf(ordered);
        this.classifier = classifier == null ? new LlmGatewayFailureClassifier() : classifier;
        this.cooldownNanos = Math.max(0L, cooldownMs) * 1_000_000L;
        this.nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
        this.maxRetries = Math.max(0, Math.min(1, maxRetries));
        this.retryBackoffMs = Math.max(0L, retryBackoffMs);
    }

    /** Read-only provider and circuit snapshot. This method never calls {@link SubagentProvider#execute}. */
    public List<ProviderStatus> status() {
        long now = nanoTime.getAsLong();
        List<ProviderStatus> rows = new ArrayList<>();
        for (SubagentProvider provider : providers) {
            String providerId = safeProviderId(provider.id());
            boolean available = false;
            String reasonCode;
            try {
                SubagentProvider.Availability availability = provider.availability();
                available = availability != null && availability.available();
                reasonCode = availability == null ? "availability_missing" : safeReason(availability.reasonCode());
            } catch (RuntimeException failure) {
                reasonCode = "availability_failed";
            }
            rows.add(new ProviderStatus(
                    providerId,
                    provider.order(),
                    available,
                    reasonCode,
                    circuitState(providerId, now),
                    provider.singleAttemptPerFlow()));
        }
        return List.copyOf(rows);
    }

    public SubagentResult execute(SubagentTask task,
                                  AttemptBudget attemptBudget,
                                  long deadlineNanos,
                                  Consumer<SubagentResult.Attempt> observer) {
        return execute(task, attemptBudget, deadlineNanos, observer, ignored -> true);
    }

    SubagentResult execute(SubagentTask task,
                           AttemptBudget attemptBudget,
                           long deadlineNanos,
                           Consumer<SubagentResult.Attempt> observer,
                           Predicate<String> providerAttemptAllowed) {
        Objects.requireNonNull(task, "task");
        AttemptBudget budget = attemptBudget == null ? new AttemptBudget() : attemptBudget;
        Consumer<SubagentResult.Attempt> safeObserver = observer == null ? ignored -> { } : observer;
        Predicate<String> safeAttemptAllowed = providerAttemptAllowed == null
                ? ignored -> false
                : providerAttemptAllowed;
        long started = nanoTime.getAsLong();
        List<SubagentResult.Attempt> attempts = new ArrayList<>();
        LlmFailureClass lastFailure = null;

        if (Thread.currentThread().isInterrupted()) {
            return result(task, SubagentResult.Status.CANCELLED, null, "none",
                    LlmFailureClass.CANCELLED_NEUTRAL, attempts, started);
        }

        for (int index = 0; index < providers.size(); index++) {
            SubagentProvider provider = providers.get(index);
            String providerId = safeProviderId(provider.id());
            boolean fallback = index > 0;
            long now = nanoTime.getAsLong();
            if (now >= deadlineNanos) {
                SubagentResult.Attempt timedOut = attempt(providerId,
                        SubagentResult.AttemptOutcome.TIMEOUT,
                        LlmFailureClass.TIMEOUT_SOFT, elapsedMs(started, now), fallback, "deadline_exhausted");
                record(attempts, safeObserver, timedOut);
                return result(task, SubagentResult.Status.TIMEOUT, null, "none",
                        LlmFailureClass.TIMEOUT_SOFT, attempts, started);
            }

            Object costPolicy = task.context().get("costPolicy");
            boolean invalidPolicy = costPolicy != null
                    && !"subscription_only".equals(costPolicy) && !"configured".equals(costPolicy);
            if (invalidPolicy || ("subscription_only".equals(costPolicy)
                    && !provider.supportsSubscriptionOnly())) {
                record(attempts, safeObserver, attempt(providerId,
                        SubagentResult.AttemptOutcome.SKIPPED, LlmFailureClass.DISABLED,
                        0L, fallback, invalidPolicy ? "invalid_cost_policy" : "cost_policy_excluded"));
                if (lastFailure == null) lastFailure = LlmFailureClass.DISABLED;
                continue;
            }

            String circuitReason = circuitReason(providerId, now);
            if (circuitReason != null) {
                SubagentResult.Attempt skipped = attempt(providerId,
                        SubagentResult.AttemptOutcome.SKIPPED,
                        "circuit_auth_blocked".equals(circuitReason)
                                ? LlmFailureClass.AUTH_MISSING
                                : LlmFailureClass.SOFT_CIRCUIT_OPEN,
                        0L, fallback, circuitReason);
                record(attempts, safeObserver, skipped);
                if (lastFailure == null) {
                    lastFailure = skipped.failureClass();
                }
                continue;
            }

            if (!attemptAllowed(safeAttemptAllowed, providerId)) {
                SubagentResult.Attempt skipped = attempt(providerId,
                        SubagentResult.AttemptOutcome.SKIPPED,
                        LlmFailureClass.DISABLED, 0L, fallback, "activation_not_allowed");
                record(attempts, safeObserver, skipped);
                if (lastFailure == null) {
                    lastFailure = LlmFailureClass.DISABLED;
                }
                continue;
            }

            SubagentProvider.Availability availability;
            try {
                availability = provider.availability(task);
            } catch (RuntimeException failure) {
                LlmFailureClass failureClass = classify(failure);
                if (failureClass == LlmFailureClass.CANCELLED_NEUTRAL) {
                    record(attempts, safeObserver, attempt(providerId,
                            SubagentResult.AttemptOutcome.CANCELLED,
                            failureClass, 0L, fallback, "availability_cancelled"));
                    return result(task, SubagentResult.Status.CANCELLED, null, "none",
                            failureClass, attempts, started);
                }
                openCircuit(providerId, failureClass, now);
                SubagentResult.Attempt failed = attempt(providerId,
                        SubagentResult.AttemptOutcome.FAILED, failureClass,
                        0L, fallback, circuitAwareReason(
                                providerId, now, categoricalReason(failure, "availability_failed")));
                record(attempts, safeObserver, failed);
                lastFailure = failureClass;
                continue;
            }
            if (availability == null || !availability.available()) {
                SubagentResult.Attempt skipped = attempt(providerId,
                        SubagentResult.AttemptOutcome.SKIPPED,
                        LlmFailureClass.DISABLED, 0L, fallback,
                        availability == null ? "availability_missing" : availability.reasonCode());
                record(attempts, safeObserver, skipped);
                if (lastFailure == null) {
                    lastFailure = LlmFailureClass.DISABLED;
                }
                continue;
            }

            // Readiness may block: reject stopped work before reserving the flow's attempt.
            if (Thread.currentThread().isInterrupted()) {
                record(attempts, safeObserver, attempt(providerId,
                        SubagentResult.AttemptOutcome.CANCELLED,
                        LlmFailureClass.CANCELLED_NEUTRAL, 0L, fallback, "readiness_interrupted"));
                return result(task, SubagentResult.Status.CANCELLED, null, "none",
                        LlmFailureClass.CANCELLED_NEUTRAL, attempts, started);
            }
            long readyAt = nanoTime.getAsLong();
            if (readyAt >= deadlineNanos) {
                record(attempts, safeObserver, attempt(providerId,
                        SubagentResult.AttemptOutcome.TIMEOUT,
                        LlmFailureClass.TIMEOUT_SOFT, elapsedMs(started, readyAt), fallback, "deadline_exhausted"));
                return result(task, SubagentResult.Status.TIMEOUT, null, "none",
                        LlmFailureClass.TIMEOUT_SOFT, attempts, started);
            }

            if (provider.singleAttemptPerFlow() && !budget.tryClaim(providerId)) {
                SubagentResult.Attempt skipped = attempt(providerId,
                        SubagentResult.AttemptOutcome.SKIPPED,
                        LlmFailureClass.DISABLED, 0L, fallback, "flow_attempt_limit");
                record(attempts, safeObserver, skipped);
                if (lastFailure == null) {
                    lastFailure = LlmFailureClass.DISABLED;
                }
                continue;
            }

            int retries = 0;
            while (true) {
                long selectedAt = nanoTime.getAsLong();
                SubagentResult.Attempt selected = attempt(providerId,
                        SubagentResult.AttemptOutcome.SELECTED,
                        LlmFailureClass.NONE, 0L, fallback,
                        retries == 0 ? "selected" : "retry_selected");
                record(attempts, safeObserver, selected);
                long remainingMs = remainingMs(deadlineNanos, selectedAt);
                if (remainingMs <= 0L) {
                    SubagentResult.Attempt timedOut = attempt(providerId,
                            SubagentResult.AttemptOutcome.TIMEOUT,
                            LlmFailureClass.TIMEOUT_SOFT, 0L, fallback, "deadline_exhausted");
                    record(attempts, safeObserver, timedOut);
                    return result(task, SubagentResult.Status.TIMEOUT, null, "none",
                            LlmFailureClass.TIMEOUT_SOFT, attempts, started);
                }

                boolean invocationStarted = false;
                try {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("cancelled_before_provider_call");
                    }
                    invocationStarted = true;
                    String output = provider.execute(task, remainingMs);
                    long finishedAt = nanoTime.getAsLong();
                    if (finishedAt >= deadlineNanos) {
                        openCircuit(providerId, LlmFailureClass.TIMEOUT_SOFT, finishedAt);
                        SubagentResult.Attempt timedOut = attempt(providerId,
                                SubagentResult.AttemptOutcome.TIMEOUT,
                                LlmFailureClass.TIMEOUT_SOFT,
                                elapsedMs(selectedAt, finishedAt), fallback, "deadline_exhausted");
                        record(attempts, safeObserver, timedOut);
                        return result(task, SubagentResult.Status.TIMEOUT, null, "none",
                                LlmFailureClass.TIMEOUT_SOFT, attempts, started);
                    }
                    if (output == null || output.isBlank()) {
                        LlmFailureClass failureClass = LlmFailureClass.PROVIDER_ERROR;
                        openCircuit(providerId, failureClass, finishedAt);
                        SubagentResult.Attempt failed = attempt(providerId,
                                SubagentResult.AttemptOutcome.FAILED,
                                failureClass, elapsedMs(selectedAt, finishedAt), fallback,
                                circuitAwareReason(providerId, finishedAt, "blank_output"));
                        record(attempts, safeObserver, failed);
                        lastFailure = failureClass;
                        break;
                    }
                    closeCircuit(providerId);
                    SubagentResult.Attempt success = attempt(providerId,
                            SubagentResult.AttemptOutcome.SUCCESS,
                            LlmFailureClass.NONE, elapsedMs(selectedAt, finishedAt), fallback, "success");
                    record(attempts, safeObserver, success);
                    return result(task, SubagentResult.Status.SUCCESS, output, providerId,
                            LlmFailureClass.NONE, attempts, started);
                } catch (Exception failure) {
                    long finishedAt = nanoTime.getAsLong();
                    LlmFailureClass failureClass = classify(failure);
                    if (failureClass == LlmFailureClass.CANCELLED_NEUTRAL) {
                        if (failure instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        SubagentResult.Attempt cancelled = attempt(providerId,
                                SubagentResult.AttemptOutcome.CANCELLED,
                                failureClass, elapsedMs(selectedAt, finishedAt), fallback,
                                invocationStarted ? "cancelled" : "cancelled_before_provider_call");
                        record(attempts, safeObserver, cancelled);
                        return result(task, SubagentResult.Status.CANCELLED, null, "none",
                                failureClass, attempts, started);
                    }

                    boolean retry = provider.retryAllowed() && retries < maxRetries
                            && retryableFailure(failure, failureClass)
                            && remainingMs(deadlineNanos, finishedAt) > retryBackoffMs;
                    if (retry) {
                        SubagentResult.Attempt failed = attempt(providerId,
                                SubagentResult.AttemptOutcome.FAILED,
                                failureClass, elapsedMs(selectedAt, finishedAt), fallback, "retry_scheduled");
                        record(attempts, safeObserver, failed);
                        retries++;
                        try {
                            if (retryBackoffMs > 0L) {
                                Thread.sleep(retryBackoffMs);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            SubagentResult.Attempt cancelled = attempt(providerId,
                                    SubagentResult.AttemptOutcome.CANCELLED,
                                    LlmFailureClass.CANCELLED_NEUTRAL,
                                    elapsedMs(selectedAt, nanoTime.getAsLong()), fallback,
                                    "retry_backoff_interrupted");
                            record(attempts, safeObserver, cancelled);
                            return result(task, SubagentResult.Status.CANCELLED, null, "none",
                                    LlmFailureClass.CANCELLED_NEUTRAL, attempts, started);
                        }
                        continue;
                    }

                    openCircuit(providerId, failureClass, finishedAt);
                    SubagentResult.Attempt failed = attempt(providerId,
                            SubagentResult.AttemptOutcome.FAILED,
                            failureClass, elapsedMs(selectedAt, finishedAt), fallback,
                            circuitAwareReason(
                                    providerId, finishedAt,
                                    categoricalReason(failure, "provider_failed")));
                    record(attempts, safeObserver, failed);
                    lastFailure = failureClass;
                    break;
                }
            }
        }

        return result(task, SubagentResult.Status.FAILED, null, "none",
                lastFailure == null ? LlmFailureClass.DISABLED : lastFailure, attempts, started);
    }

    private static boolean attemptAllowed(Predicate<String> policy, String providerId) {
        try {
            return policy.test(providerId);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private LlmFailureClass classify(Throwable failure) {
        if (failure instanceof LlmGatewayException gatewayFailure) {
            return gatewayFailure.failureClass();
        }
        if (failure instanceof CancellationException || failure instanceof InterruptedException) {
            return LlmFailureClass.CANCELLED_NEUTRAL;
        }
        LlmFailureClass classified = classifier.classify(failure);
        return classified == LlmFailureClass.NONE ? LlmFailureClass.UNKNOWN : classified;
    }

    private static String categoricalReason(Throwable failure, String fallback) {
        if (failure instanceof LlmGatewayException gatewayFailure
                && !"gateway_failure".equals(gatewayFailure.reasonCode())) {
            return safeReason(gatewayFailure.reasonCode());
        }
        return fallback;
    }

    private static boolean retryableFailure(Throwable failure, LlmFailureClass failureClass) {
        if (failureClass == LlmFailureClass.RATE_LIMIT_COOLDOWN) {
            return true;
        }
        if (failureClass != LlmFailureClass.HEALTH_DOWN) {
            return false;
        }
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 20) {
            if (current instanceof WebClientResponseException response
                    && response.getRawStatusCode() >= 500
                    && response.getRawStatusCode() < 600) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && TRANSIENT_5XX.matcher(message).find()) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    private String circuitReason(String providerId, long now) {
        CircuitState state = circuits.get(providerId);
        if (state == null) {
            return null;
        }
        if (state.authBlocked.get()) {
            return "circuit_auth_blocked";
        }
        return state.blockedUntilNanos.get() > now ? "circuit_cooldown" : null;
    }

    String circuitState(String providerId) {
        return circuitState(safeProviderId(providerId), nanoTime.getAsLong());
    }

    private String circuitState(String providerId, long now) {
        CircuitState state = circuits.get(providerId);
        if (state == null) {
            return "closed";
        }
        if (state.authBlocked.get()) {
            return "auth_blocked";
        }
        return state.blockedUntilNanos.get() > now ? "open" : "closed";
    }

    private String circuitAwareReason(String providerId, long now, String baseReason) {
        String state = circuitState(providerId, now);
        return "open".equals(state) || "auth_blocked".equals(state)
                ? baseReason
                : baseReason + "_no_circuit";
    }

    private void openCircuit(String providerId, LlmFailureClass failureClass, long now) {
        if (failureClass == null || failureClass == LlmFailureClass.NONE
                || failureClass == LlmFailureClass.CANCELLED_NEUTRAL
                || failureClass == LlmFailureClass.DISABLED) {
            return;
        }
        CircuitState state = circuits.computeIfAbsent(providerId, ignored -> new CircuitState());
        if (failureClass == LlmFailureClass.AUTH_MISSING) {
            state.authBlocked.set(true);
            return;
        }
        if (failureClass.hardBreakerFailure()
                || failureClass == LlmFailureClass.HEALTH_DOWN
                || failureClass == LlmFailureClass.TIMEOUT_SOFT
                || failureClass == LlmFailureClass.RATE_LIMIT_COOLDOWN) {
            state.blockedUntilNanos.set(saturatingAdd(now, cooldownNanos));
        }
    }

    private void closeCircuit(String providerId) {
        circuits.remove(providerId);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long remainingMs(long deadlineNanos, long now) {
        long remaining = deadlineNanos - now;
        if (remaining <= 0L) {
            return 0L;
        }
        return Math.max(1L, (remaining + 999_999L) / 1_000_000L);
    }

    private static long elapsedMs(long started, long finished) {
        return Math.max(0L, (finished - started) / 1_000_000L);
    }

    private SubagentResult result(SubagentTask task,
                                  SubagentResult.Status status,
                                  String output,
                                  String selectedProvider,
                                  LlmFailureClass failureClass,
                                  List<SubagentResult.Attempt> attempts,
                                  long started) {
        return new SubagentResult(task.requestId(), task.ordinal(), task.taskId(), task.role(),
                status, output, selectedProvider, failureClass, attempts,
                elapsedMs(started, nanoTime.getAsLong()));
    }

    private static SubagentResult.Attempt attempt(String provider,
                                                  SubagentResult.AttemptOutcome outcome,
                                                  LlmFailureClass failureClass,
                                                  long elapsedMs,
                                                  boolean fallback,
                                                  String reasonCode) {
        return new SubagentResult.Attempt(provider, outcome, failureClass,
                elapsedMs, fallback, safeReason(reasonCode));
    }

    private static void record(List<SubagentResult.Attempt> attempts,
                               Consumer<SubagentResult.Attempt> observer,
                               SubagentResult.Attempt attempt) {
        attempts.add(attempt);
        try {
            observer.accept(attempt);
        } catch (RuntimeException ignored) {
            // Observability must never change provider execution semantics.
        }
    }

    private static String safeProviderId(String providerId) {
        if (providerId == null || !providerId.matches("[a-z0-9][a-z0-9.-]{0,31}")) {
            return "unknown";
        }
        return providerId;
    }

    private static String safeReason(String reasonCode) {
        if (reasonCode == null || !reasonCode.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            return "unknown";
        }
        String label = SafeRedactor.traceLabelOrFallback(reasonCode, "unknown");
        return label.startsWith("hash:") ? "redacted" : label;
    }

    /** Per-request quota shared by independently executing tasks. */
    public static final class AttemptBudget {
        private final Set<String> claimed = ConcurrentHashMap.newKeySet();

        private boolean tryClaim(String providerId) {
            return claimed.add(providerId);
        }
    }

    public record ProviderStatus(
            String provider,
            int order,
            boolean available,
            String reasonCode,
            String circuitState,
            boolean singleAttemptPerFlow) {
    }

    private static final class CircuitState {
        private final AtomicBoolean authBlocked = new AtomicBoolean(false);
        private final AtomicLong blockedUntilNanos = new AtomicLong(0L);
    }
}
