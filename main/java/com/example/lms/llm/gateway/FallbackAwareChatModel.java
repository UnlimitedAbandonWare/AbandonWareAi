package com.example.lms.llm.gateway;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Function;
import java.util.function.Supplier;

public final class FallbackAwareChatModel implements com.example.lms.llm.NamedChatModel {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FallbackAwareChatModel.class);

    private static final String ROUTE_RESOLUTION_FAILURE_REASON_KEY =
            "llm.gateway.fallbackAware.routeResolutionFailureReason";
    private static final String ROUTE_RESOLUTION_FAILURE_COUNT_KEY =
            "llm.gateway.fallbackAware.routeResolutionFailureCount";
    private static final String ROUTE_RESOLUTION_FAILURE_REASON =
            "route_supplier_runtime_exception";
    // fallback이 구성된 요청에서 primary에 배정하는 요청 예산 슬라이스 비율(60%).
    private static final long PRIMARY_SLICE_NUMERATOR = 3L;
    private static final long PRIMARY_SLICE_DENOMINATOR = 5L;

    private final ChatModel primary;
    private final Supplier<ChatModel> fallbackSupplier;
    private final Function<LlmFailureClass, ResolvedFallback> fallbackResolver;
    private final LlmGatewayFailureClassifier classifier;
    private final LlmGatewayBreadcrumbPublisher breadcrumbs;
    private final String primaryKey;
    private final String fallbackKey;
    private final ModelRuntimeHealthTracker healthTracker;
    private final String requestTimelineId;
    private final ModelRuntimeHealthTracker.RequestAttemptRoute primaryRoute;
    private final Supplier<ModelRuntimeHealthTracker.RequestAttemptRoute> fallbackRouteSupplier;
    private NextFallbackResolver nextFallbackResolver;
    private int maxFallbackCalls = 1;
    private boolean apiFirstPolicy;

    public FallbackAwareChatModel withApiFirstPolicy() {
        this.apiFirstPolicy = true;
        return this;
    }
    private java.util.function.BiConsumer<String, String> maskedFallbackReporter = (from, to) -> { };

    /** Optional observer: original route stays on record when a fallback serves the user. */
    public FallbackAwareChatModel withMaskedFallbackReporter(java.util.function.BiConsumer<String, String> reporter) {
        this.maskedFallbackReporter = reporter == null ? (from, to) -> { } : reporter;
        return this;
    }

    @Override
    public String resolvedModelName() {
        // 폴백 래퍼의 식별자는 현재 primary가 대표한다 — 위임 체인을 재귀 해석한다.
        return com.example.lms.llm.NamedChatModel.resolve(primary);
    }

    @FunctionalInterface
    public interface NextFallbackResolver {
        ResolvedFallback resolve(LlmFailureClass failure, Set<String> usedRoutes);
    }

    public FallbackAwareChatModel(ChatModel primary, NextFallbackResolver resolver,
            LlmGatewayFailureClassifier classifier, LlmGatewayBreadcrumbPublisher breadcrumbs,
            String primaryKey, ModelRuntimeHealthTracker healthTracker, String requestTimelineId,
            ModelRuntimeHealthTracker.RequestAttemptRoute primaryRoute, int maxFallbackCalls) {
        this(primary, (Function<LlmFailureClass, ResolvedFallback>) null, classifier, breadcrumbs,
                primaryKey, healthTracker, requestTimelineId, primaryRoute);
        this.nextFallbackResolver = Objects.requireNonNull(resolver, "resolver");
        this.maxFallbackCalls = Math.max(1, Math.min(3, maxFallbackCalls));
    }

    public FallbackAwareChatModel(
            ChatModel primary,
            Supplier<ChatModel> fallbackSupplier,
            LlmGatewayFailureClassifier classifier,
            LlmGatewayBreadcrumbPublisher breadcrumbs,
            String primaryKey,
            String fallbackKey) {
        this(primary,
                fallbackSupplier,
                classifier,
                breadcrumbs,
                primaryKey,
                fallbackKey,
                null,
                null,
                null,
                null);
    }

    public FallbackAwareChatModel(
            ChatModel primary,
            Supplier<ChatModel> fallbackSupplier,
            LlmGatewayFailureClassifier classifier,
            LlmGatewayBreadcrumbPublisher breadcrumbs,
            String primaryKey,
            String fallbackKey,
            ModelRuntimeHealthTracker healthTracker,
            String requestTimelineId,
            ModelRuntimeHealthTracker.RequestAttemptRoute primaryRoute,
            Supplier<ModelRuntimeHealthTracker.RequestAttemptRoute> fallbackRouteSupplier) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallbackSupplier = fallbackSupplier;
        this.fallbackResolver = null;
        this.classifier = classifier == null ? new LlmGatewayFailureClassifier() : classifier;
        this.breadcrumbs = breadcrumbs;
        this.primaryKey = primaryKey;
        this.fallbackKey = fallbackKey;
        this.healthTracker = healthTracker;
        this.requestTimelineId = requestTimelineId;
        this.primaryRoute = primaryRoute;
        this.fallbackRouteSupplier = fallbackRouteSupplier;
    }

    public FallbackAwareChatModel(
            ChatModel primary,
            Function<LlmFailureClass, ResolvedFallback> fallbackResolver,
            LlmGatewayFailureClassifier classifier,
            LlmGatewayBreadcrumbPublisher breadcrumbs,
            String primaryKey,
            ModelRuntimeHealthTracker healthTracker,
            String requestTimelineId,
            ModelRuntimeHealthTracker.RequestAttemptRoute primaryRoute) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallbackSupplier = null;
        this.fallbackResolver = fallbackResolver;
        this.classifier = classifier == null ? new LlmGatewayFailureClassifier() : classifier;
        this.breadcrumbs = breadcrumbs;
        this.primaryKey = primaryKey;
        this.fallbackKey = null;
        this.healthTracker = healthTracker;
        this.requestTimelineId = requestTimelineId;
        this.primaryRoute = primaryRoute;
        this.fallbackRouteSupplier = null;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return doChat(request.messages(), request);
    }

    private ChatResponse doChat(List<ChatMessage> messages, ChatRequest request) {
        com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
        if (nextFallbackResolver != null && healthTracker != null && hasMatchingTimelineContext())
            healthTracker.limitRequestInferenceAttempts(requestTimelineId, 4);
        TimeBudget initialBudget = TimeBudgetContext.get();
        if (initialBudget != null && initialBudget.remainingMillis() <= 0) {
            TraceStore.put("llm.gateway.fallback.remainingMs", 0L);
            TraceStore.put("llm.gateway.fallback.skippedReason", "request_deadline_exhausted");
            throw terminalFailure(new LlmGatewayException("Inference deadline exhausted",
                    LlmFailureClass.TIMEOUT_SOFT, "request_deadline_exhausted"));
        }
        TraceStore.put("llm.gateway.fallback.count", 0);
        TraceStore.put("llm.gateway.fallback.started", false);
        // Freeze the retrieval/prompt boundary once, including conversation order and source IDs.
        List<ChatMessage> frozen = List.copyOf(messages);
        // fallback이 구성된 요청은 primary가 요청 예산 전체를 독식하지 못하게
        // 진입 시점 잔여 예산의 일부만 primary 슬라이스로 스코프 부여한다.
        boolean fallbackConfigured = fallbackSupplier != null || fallbackResolver != null || nextFallbackResolver != null;
        TimeBudget primaryBudget = null;
        long primarySliceMs = 0L;
        if (fallbackConfigured && initialBudget != null) {
            long remainingMs = Math.max(0L, initialBudget.remainingMillis());
            long sliceMs = (remainingMs * PRIMARY_SLICE_NUMERATOR) / PRIMARY_SLICE_DENOMINATOR;
            if (sliceMs > 0L && sliceMs < remainingMs) {
                primarySliceMs = sliceMs;
                primaryBudget = TimeBudget.untilNanoDeadline(System.nanoTime() + sliceMs * 1_000_000L);
            }
        }
        TraceStore.put("llm.gateway.fallback.timeSliceAllocatedMs", primarySliceMs);
        int primaryAttemptTotalBefore = requestAttemptTotal();
        long primaryStartedNanos = System.nanoTime();
        try {
            ChatResponse response;
            if (primaryBudget != null) {
                TimeBudgetContext.set(primaryBudget);
                try {
                    response = invokeModel(primary, frozen, request);
                } finally {
                    // 슬라이스는 primary 호출 구간에만 적용하고 즉시 부모 예산으로 복원한다.
                    TimeBudgetContext.set(initialBudget);
                }
            } else {
                response = invokeModel(primary, frozen, request);
            }
            requireAnswer(response);
            recordAttempt(
                    "primary",
                    primaryRoute,
                    LlmFailureClass.NONE,
                    elapsedMs(primaryStartedNanos),
                    primaryAttemptTotalBefore);
            recordCompletion(response, primaryKey, 0, elapsedMs(primaryStartedNanos));
            return response;
        } catch (RuntimeException ex) {
            LlmResponseTerminalException.rethrowIfPresent(ex);
            if (hasGatewayReason(ex, "failover_exhausted")) throw ex;
            LlmFailureClass failureClass = classifyFailure(ex);
            // 자체 부여 슬라이스 만료로 발생한 TIMEOUT_SOFT만 재생 가능 목록에 추가한다.
            boolean primarySliceExhausted = primaryBudget != null && primaryBudget.expired();
            boolean sameRequestRetryAllowed = fallbackAllowed(ex, failureClass)
                    || (primarySliceExhausted && failureClass == LlmFailureClass.TIMEOUT_SOFT
                            && !LlmGatewayFailureClassifier.isCancellation(ex)
                            && !LlmGatewayFailureClassifier.hasNonReplayableReason(ex));
            recordAttempt(
                    "primary",
                    primaryRoute,
                    failureClass,
                    elapsedMs(primaryStartedNanos),
                    primaryAttemptTotalBefore);
            TraceStore.put("llm.gateway.fallbackAware.primaryFailure", failureClass.name());
            if (apiFirstPolicy) TraceStore.put("llm.gateway.fallbackReason",
                    failureClass == LlmFailureClass.AUTH_MISSING ? "provider_auth_invalid"
                    : LlmGatewayFailureClassifier.hasQuotaFailure(ex) ? "provider_quota_exhausted"
                    : failureClass.name().toLowerCase(java.util.Locale.ROOT));
            TraceStore.put("llm.gateway.fallbackAware.sameRequestRetry",
                    fallbackConfigured && sameRequestRetryAllowed);
            TraceStore.put("llm.gateway.fallbackAware.primarySliceExhausted", primarySliceExhausted);
            if (breadcrumbs != null) {
                breadcrumbs.publishFailure(primaryKey, failureClass, ex);
            }
            if (!fallbackConfigured || !sameRequestRetryAllowed) {
                if (fallbackConfigured && !LlmGatewayFailureClassifier.isCancellation(ex)
                        && !LlmGatewayFailureClassifier.hasNonReplayableReason(ex)
                        && java.util.Set.of(LlmFailureClass.HEALTH_DOWN, LlmFailureClass.PROVIDER_ERROR,
                                LlmFailureClass.TIMEOUT_SOFT, LlmFailureClass.STREAM_ERROR,
                                LlmFailureClass.RESPONSE_MODEL_UNVERIFIED).contains(failureClass)) {
                    LlmGatewayException uncertain=new LlmGatewayException("Provider execution is unconfirmed",
                            failureClass,"provider_execution_uncertain");
                    uncertain.initCause(ex);
                    throw uncertain;
                }
                throw ex;
            }
            Set<String> usedRoutes = new LinkedHashSet<>();
            if (primaryKey != null) usedRoutes.add(primaryKey);
            for (int fallbackNumber = 0; fallbackNumber < maxFallbackCalls; fallbackNumber++) {
            if (primaryAttemptTotalBefore >= 0 && requestAttemptTotal() - primaryAttemptTotalBefore >= 4) {
                TraceStore.put("llm.gateway.fallback.skippedReason", "attempt_limit");
                break;
            }
            com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
            TimeBudget requestBudget = TimeBudgetContext.get();
            if (requestBudget != null) {
                long remainingMs = Math.max(0L, requestBudget.remainingMillis());
                TraceStore.put("llm.gateway.fallback.remainingMs", remainingMs);
                TraceStore.put("llm.gateway.fallback.started", false);
                if (remainingMs <= 0L) {
                    TraceStore.put("llm.gateway.fallback.skippedReason", "request_deadline_exhausted");
                    throw terminalFailure(ex);
                }
            }
            ResolvedFallback resolvedFallback = nextFallbackResolver == null ? resolveFallback(failureClass)
                    : nextFallbackResolver.resolve(failureClass, Set.copyOf(usedRoutes));
            com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
            if (resolvedFallback == null || resolvedFallback.model() == null) {
                TraceStore.put("llm.gateway.fallbackAware.sameRequestRetry", false);
                TraceStore.put("llm.gateway.fallback.started", false);
                TraceStore.put("llm.gateway.fallback.skippedReason", "no_eligible_fallback");
                throw terminalFailure(ex);
            }
            if (nextFallbackResolver != null && (resolvedFallback.routeKey() == null
                    || !usedRoutes.add(resolvedFallback.routeKey()))) {
                TraceStore.put("llm.gateway.fallback.skippedReason", "route_cycle");
                throw terminalFailure(ex);
            }
            if (breadcrumbs != null) {
                breadcrumbs.publishFallback(
                        primaryKey,
                        resolvedFallback.routeKey(),
                        failureClass,
                        nextFallbackResolver == null ? "same_request_retry_once" : "bounded_provider_failover");
            }
            ChatModel fallback = resolvedFallback.model();
            if (requestBudget != null) {
                long remainingMs = Math.max(0L, requestBudget.remainingMillis());
                TraceStore.put("llm.gateway.fallback.remainingMs", remainingMs);
                if (remainingMs <= 0L) {
                    TraceStore.put("llm.gateway.fallback.started", false);
                    TraceStore.put("llm.gateway.fallback.skippedReason", "request_deadline_exhausted");
                    throw terminalFailure(ex);
                }
            }
            TraceStore.put("llm.gateway.fallback.started", true);
            TraceStore.put("llm.gateway.fallback.skippedReason", "none");
            TraceStore.put("llm.gateway.fallback.count", fallbackNumber + 1);
            TraceStore.put("llm.gateway.fallback.selectedRoute",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback(resolvedFallback.routeKey(), "unknown"));
            LOG.info("[llm-failover] route={} fallbackCount={} cause={}",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback(resolvedFallback.routeKey(), "unknown"),
                    fallbackNumber + 1, failureClass.name());
            ModelRuntimeHealthTracker.RequestAttemptRoute fallbackRoute = resolvedFallback.route();
            int fallbackAttemptTotalBefore = requestAttemptTotal();
            long fallbackStartedNanos = System.nanoTime();
            try {
                com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
                ChatResponse response = invokeModel(fallback, frozen, request);
                requireAnswer(response);
                recordAttempt(
                        "fallback",
                        fallbackRoute,
                        LlmFailureClass.NONE,
                        elapsedMs(fallbackStartedNanos),
                        fallbackAttemptTotalBefore);
                TraceStore.put("llm.gateway.fallback.latencyMs", elapsedMs(fallbackStartedNanos));
                recordCompletion(response, resolvedFallback.routeKey(), fallbackNumber + 1, elapsedMs(primaryStartedNanos));
                try { maskedFallbackReporter.accept(primaryKey, resolvedFallback.routeKey()); }
                catch (RuntimeException ignored) { TraceStore.put("llm.gateway.fallback.maskedReportFailed", true); }
                return response;
            } catch (RuntimeException fallbackFailure) {
                LlmResponseTerminalException.rethrowIfPresent(fallbackFailure);
                if (hasGatewayReason(fallbackFailure, "failover_exhausted")) throw fallbackFailure;
                if (LlmGatewayFailureClassifier.isCancellation(fallbackFailure)) throw fallbackFailure;
                if (LlmGatewayFailureClassifier.hasNonReplayableReason(fallbackFailure, apiFirstPolicy)) throw fallbackFailure;
                recordAttempt(
                        "fallback",
                        fallbackRoute,
                        classifyFailure(fallbackFailure),
                        elapsedMs(fallbackStartedNanos),
                        fallbackAttemptTotalBefore);
                if (nextFallbackResolver == null || !fallbackAllowed(fallbackFailure, classifyFailure(fallbackFailure)))
                    throw terminalFailure(fallbackFailure, true);
                ex = fallbackFailure;
                failureClass = classifyFailure(fallbackFailure);
            }
            }
            throw terminalFailure(ex);
        }
    }

    /** Forward the full request; a legacy list-only delegate keeps its original entry point. */
    private static ChatResponse invokeModel(ChatModel model, List<ChatMessage> messages, ChatRequest request) {
        if (request == null || messages == null || messages.isEmpty()) {
            return model.chat(messages);
        }
        try {
            return model.chat(frozenRequestMessages(request, messages));
        } catch (RuntimeException failure) {
            if (isMissingDoChatContract(failure)) {
                return model.chat(messages);
            }
            throw failure;
        }
    }

    /** The request must carry the frozen conversation; a caller-mutated list can empty it mid-chain. */
    private static ChatRequest frozenRequestMessages(ChatRequest request, List<ChatMessage> frozen) {
        if (request.messages() == frozen) {
            return request;
        }
        ChatRequest.Builder builder = ChatRequest.builder().messages(frozen);
        if (request.parameters() != null) {
            builder.parameters(request.parameters());
        }
        return builder.build();
    }

    static boolean isMissingDoChatContract(RuntimeException failure) {
        if (failure == null
                || failure.getClass() != RuntimeException.class
                || !"Not implemented".equals(failure.getMessage())) {
            return false;
        }
        // Only the interface-default doChat produces this exact throw before any
        // transport; a provider error raised inside a real doChat must propagate.
        StackTraceElement[] frames = failure.getStackTrace();
        return frames.length > 0
                && "dev.langchain4j.model.chat.ChatModel".equals(frames[0].getClassName())
                && "doChat".equals(frames[0].getMethodName());
    }

    private static void requireAnswer(ChatResponse response) {
        com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
        if (response == null || response.aiMessage() == null || (!response.aiMessage().hasToolExecutionRequests()
                && (response.aiMessage().text() == null || response.aiMessage().text().isBlank())))
            throw new LlmGatewayException("Provider returned no answer", LlmFailureClass.PROVIDER_ERROR, "blank_response");
    }

    private static void recordCompletion(ChatResponse response, String route, int fallbacks, long latencyMs) {
        var successful = com.example.lms.dto.GenerationObservation.current();
        Object reason = TraceStore.get("llm.gateway.fallbackReason");
        if (reason == null && fallbacks > 0) reason = TraceStore.get("llm.gateway.fallbackAware.primaryFailure");
        com.example.lms.dto.GenerationObservation.capture(response, successful.observedProvider(),
                successful.routeId(), fallbacks, reason instanceof String s ? s : null).store();
        String label = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(route, "unknown");
        TraceStore.put("llm.gateway.selectedRoute", label);
        TraceStore.put("llm.gateway.latencyMs", latencyMs);
        Object requestAttempts = TraceStore.get("llm.gateway.attemptCount");
        TraceStore.put("llm.gateway.attemptCount", requestAttempts instanceof Number count
                ? Math.max(count.intValue(), fallbacks + 1) : fallbacks + 1);
        TraceStore.put("llm.gateway.fallback.succeeded", fallbacks > 0);
        LOG.info("[llm-failover] route={} fallbackCount={} outcome=success latencyMs={}", label, fallbacks, latencyMs);
    }

    private RuntimeException terminalFailure(RuntimeException failure) {
        return terminalFailure(failure, false);
    }

    private RuntimeException terminalFailure(RuntimeException failure, boolean fallbackAttempted) {
        if (nextFallbackResolver == null && !fallbackAttempted) return failure;
        LlmGatewayException exhausted = new LlmGatewayException("Bounded inference routes exhausted",
                classifyFailure(failure), "failover_exhausted");
        exhausted.initCause(failure);
        return exhausted;
    }

    private boolean fallbackAllowed(Throwable failure, LlmFailureClass failureClass) {
        if (apiFirstPolicy) {
            if (LlmGatewayFailureClassifier.isCancellation(failure)
                    || LlmGatewayFailureClassifier.hasNonReplayableReason(failure, true)) return false;
            return java.util.Set.of(LlmFailureClass.AUTH_MISSING, LlmFailureClass.HEALTH_DOWN,
                    LlmFailureClass.TIMEOUT_SOFT, LlmFailureClass.RATE_LIMIT_COOLDOWN,
                    LlmFailureClass.SOFT_CIRCUIT_OPEN, LlmFailureClass.GPU_DEVICE_LOST,
                    LlmFailureClass.VRAM_OOM).contains(failureClass);
        }
        return sameRequestFallbackAllowed(failure, failureClass);
    }

    private LlmFailureClass classifyFailure(Throwable failure) {
        if (failure instanceof LlmGatewayException gatewayFailure) {
            return gatewayFailure.failureClass();
        }
        return classifier.classify(failure);
    }

    private ModelRuntimeHealthTracker.RequestAttemptRoute fallbackRoute() {
        if (fallbackRouteSupplier == null) {
            return null;
        }
        try {
            return fallbackRouteSupplier.get();
        } catch (RuntimeException ignored) {
            TraceStore.put(
                    ROUTE_RESOLUTION_FAILURE_REASON_KEY,
                    ROUTE_RESOLUTION_FAILURE_REASON);
            incrementRouteResolutionFailureCount();
            return null;
        }
    }

    private ResolvedFallback resolveFallback(LlmFailureClass failureClass) {
        if (fallbackResolver != null) {
            return fallbackResolver.apply(failureClass);
        }
        if (fallbackSupplier == null) {
            return null;
        }
        ChatModel fallback = fallbackSupplier.get();
        return fallback == null
                ? null
                : new ResolvedFallback(fallback, fallbackKey, fallbackRoute());
    }

    private static void incrementRouteResolutionFailureCount() {
        TraceStore.context().compute(ROUTE_RESOLUTION_FAILURE_COUNT_KEY, (key, current) -> {
            long prior = current instanceof Number number
                    ? Math.max(0L, number.longValue())
                    : 0L;
            return prior == Long.MAX_VALUE ? Long.MAX_VALUE : prior + 1L;
        });
    }

    private void recordAttempt(
            String role,
            ModelRuntimeHealthTracker.RequestAttemptRoute route,
            LlmFailureClass failureClass,
            long elapsedMs,
            int attemptTotalBefore) {
        if (healthTracker == null || route == null || !hasMatchingTimelineContext()) {
            return;
        }
        if (attemptTotalBefore >= 0
                && healthTracker.currentThreadRequestAttemptTotal(requestTimelineId) > attemptTotalBefore) {
            return;
        }
        LlmFailureClass failure = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
        String outcome = failure == LlmFailureClass.NONE
                ? "success"
                : failure == LlmFailureClass.CANCELLED_NEUTRAL ? "cancelled" : "failed";
        healthTracker.recordRequestAttempt(
                requestTimelineId,
                role,
                route,
                outcome,
                failure.name().toLowerCase(java.util.Locale.ROOT),
                attemptTerminalClass(failure),
                elapsedMs);
    }

    private int requestAttemptTotal() {
        if (healthTracker == null || !hasMatchingTimelineContext()) {
            return -1;
        }
        return healthTracker.currentThreadRequestAttemptTotal(requestTimelineId);
    }

    private boolean hasMatchingTimelineContext() {
        if (requestTimelineId == null || requestTimelineId.isBlank()) {
            return false;
        }
        Object current = TraceStore.get(ModelRuntimeHealthTracker.REQUEST_TIMELINE_TRACE_KEY);
        return current != null && requestTimelineId.equals(String.valueOf(current));
    }

    private static String attemptTerminalClass(LlmFailureClass failureClass) {
        return switch (failureClass) {
            case NONE -> "success";
            case MODEL_MISSING -> "model_unavailable";
            case TIMEOUT_SOFT -> "timeout";
            case CANCELLED_NEUTRAL -> "cancelled";
            case AUTH_MISSING, BAD_REQUEST, CONTEXT_TOO_SMALL, EMBEDDING_DIM_MISMATCH,
                    LOCAL_UNSUPPORTED_MANAGED_RAG, DISABLED -> "configuration_error";
            default -> "error";
        };
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static boolean sameRequestFallbackAllowed(Throwable failure, LlmFailureClass failureClass) {
        if (LlmGatewayFailureClassifier.isCancellation(failure)
                || LlmGatewayFailureClassifier.hasNonReplayableReason(failure)) return false;
        // Classified device rejection means the runner could not start generation.
        // Generic HTTP errors, timeouts and zero tokens still do not prove non-execution.
        return failureClass == LlmFailureClass.GPU_DEVICE_LOST || failureClass == LlmFailureClass.VRAM_OOM
                || failureClass == LlmFailureClass.RATE_LIMIT_COOLDOWN && hasGatewayReason(failure,"local_backend_busy");
    }

    private static boolean hasGatewayReason(Throwable failure, String expectedReason) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 20) {
            if (current instanceof LlmGatewayException gatewayFailure
                    && expectedReason.equals(gatewayFailure.reasonCode())) {
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

    public record ResolvedFallback(
            ChatModel model,
            String routeKey,
            ModelRuntimeHealthTracker.RequestAttemptRoute route) {
    }
}
