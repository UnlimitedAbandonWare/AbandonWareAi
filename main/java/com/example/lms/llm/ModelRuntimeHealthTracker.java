package com.example.lms.llm;

import org.springframework.stereotype.Component;

import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.SafeChatMessageLog;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ModelRuntimeHealthTracker {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.agent.GroqFreeTierGuard groqFreeTierGuard;

    private com.example.lms.agent.GroqFreeTierGuard.Reservation reserveGroq(dev.langchain4j.http.client.HttpRequest request) {
        if(!com.example.lms.agent.GroqFreeTierGuard.isGroq(request.url()))return null;
        if(groqFreeTierGuard==null)throw new IllegalStateException("groq_free_guard_unavailable");
        try {
            var body=REQUEST_ATTEMPT_MESSAGE_MAPPER.readTree(request.body());
            String key=request.headers().entrySet().stream().filter(e->"authorization".equalsIgnoreCase(e.getKey()))
                    .flatMap(e->e.getValue().stream()).findFirst().orElse("");
            if(key.startsWith("Bearer "))key=key.substring(7);
            long output=body.path("max_completion_tokens").asLong(body.path("max_tokens").asLong(0));
            if(output<=0)throw new IllegalStateException("groq_output_bound_required");
            return groqFreeTierGuard.reserve(body.path("model").asText(),key,
                    request.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+output,0);
        }catch(java.io.IOException denied){throw new IllegalStateException("groq_free_admission_denied");}
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.debug.ApiFailureRecorder apiFailureRecorder;

    public void recordApiFailure(String url, String model, Throwable failure) {
        try { if (apiFailureRecorder != null) apiFailureRecorder.recordException(url, model, failure); }
        catch (RuntimeException ignored) { org.slf4j.LoggerFactory.getLogger(ModelRuntimeHealthTracker.class).warn("[API_FAILURE] observation_unavailable"); }
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.config.LocalLlmProcessManager localLlmProcessManager;

    public String resolveServiceEndpoint(String configuredUrl) {
        return localLlmProcessManager == null ? configuredUrl : localLlmProcessManager.resolveServiceUrl(configuredUrl);
    }

    private dev.langchain4j.http.client.HttpRequest resolveServiceRequest(dev.langchain4j.http.client.HttpRequest request) {
        String resolved = resolveServiceEndpoint(request.url());
        if (java.util.Objects.equals(resolved, request.url())) return request;
        return dev.langchain4j.http.client.HttpRequest.builder().method(request.method()).url(resolved)
                .headers(request.headers()).body(request.body()).build();
    }

    public static final String REQUEST_TIMELINE_TRACE_KEY = "llm.requestTimelineId";
    public static final String REQUEST_LIFECYCLE_TRACE_KEY = "llm.request.lifecycle";
    private final ThreadLocal<ClientAttempt> currentClientAttempt = new ThreadLocal<>();
    private static final ThreadLocal<DirectReceipt> directReceipt = new ThreadLocal<>();

    /** Opt-in for one local Display verification; never retains raw headers, keys, or bodies. */
    public static DirectReceipt beginDirectOpenAiReceipt() { return new DirectReceipt(); }
    public static final class DirectReceipt implements AutoCloseable {
        private final DirectReceipt previous=directReceipt.get();
        private final Map<String,Object> values=new LinkedHashMap<>();
        private DirectReceipt(){values.put("httpAttemptCount",0);values.put("providerRequestId","not_observed");directReceipt.set(this);}
        void started(String url){
            var uri=java.net.URI.create(url);
            if(!"https".equals(uri.getScheme())||!"api.openai.com".equals(uri.getHost())||uri.getUserInfo()!=null
                    ||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPort()==-1||uri.getPort()==443)
                    ||!"/v1/chat/completions".equals(uri.getPath()))throw new IllegalArgumentException("direct_openai_endpoint_required");
            if(!Integer.valueOf(0).equals(values.get("httpAttemptCount")))throw new IllegalStateException("direct_retry_blocked");
            values.put("httpAttemptCount",1);
        }
        void received(int status,Map<String,List<String>> headers){
            values.put("httpStatus",status);
            headers.forEach((name,items)->{
                if(items==null||items.isEmpty()||items.get(0)==null)return;
                String value=items.get(0);
                if(name.equalsIgnoreCase("x-request-id")&&value.matches("req_[A-Za-z0-9_-]{1,128}"))values.put("providerRequestId",value);
                if((name.equalsIgnoreCase("openai-organization")||name.equalsIgnoreCase("openai-project"))&&value.length()<=256)
                    values.put(name.toLowerCase(Locale.ROOT)+"Hash",org.apache.commons.codec.digest.DigestUtils.sha256Hex(value));
            });
        }
        void failed(RuntimeException failure){
            if(failure instanceof dev.langchain4j.exception.HttpException http){
                values.put("httpStatus",http.statusCode());
                String body=http.getMessage();
                if(body!=null&&body.length()<=16384)try{
                    var error=REQUEST_ATTEMPT_MESSAGE_MAPPER.readTree(body).path("error");
                    for(String field:List.of("code","type","param")){
                        String value=error.path(field).asText("");
                        if(Set.of("invalid_api_key","insufficient_quota","invalid_request_error","rate_limit_exceeded","model_not_found",
                                "unsupported_parameter","unsupported_value","max_tokens","max_completion_tokens","temperature","model","response_format").contains(value))values.put("error_"+field,value);
                    }
                }catch(Exception ignored){/* Deliberately discard the body. */}
            }
        }
        public Map<String,Object> snapshot(){return Map.copyOf(values);}
        @Override public void close(){if(previous==null)directReceipt.remove();else directReceipt.set(previous);}
    }
    public static final String REQUEST_ENDPOINT_CAPTURE_TRACE_KEY = "llm.requestTimeline.captureEndpoint";

    private static final Set<String> SEED_LOCAL_CHAT_MODELS = Set.copyOf(java.util.List.of(
            ModelCapabilities.DEFAULT_LOCAL_CHAT_MODEL.toLowerCase(Locale.ROOT),
            ModelCapabilities.DEFAULT_LOCAL_FAST_MODEL.toLowerCase(Locale.ROOT),
            ModelCapabilities.DEFAULT_LOCAL_JUDGE_MODEL.toLowerCase(Locale.ROOT),
            ModelCapabilities.DEFAULT_LOCAL_CODER_MODEL.toLowerCase(Locale.ROOT),
            ModelCapabilities.DEFAULT_LOCAL_VISION_MODEL.toLowerCase(Locale.ROOT)));
    private static final AtomicLong LAST_LOCAL_SUCCESS_EPOCH_MS = new AtomicLong(0L);
    private static final Set<String> REQUEST_TIMELINE_PHASES = Set.of(
            "dispatch", "pending", "final_boundary", "terminal");
    private static final Set<String> REQUEST_TIMELINE_TERMINALS = Set.of(
            "none", "success", "cancelled", "timeout", "error", "request_budget_exhausted",
            "configuration_error", "upstream_5xx", "blank_response", "output_limit_reached", "model_unavailable",
            "generated_persisted_delivery_pending", "generated_persisted_delivery_failed",
            "content_filter", "refusal", "responses_failed", "responses_cancelled",
            "responses_not_complete", "responses_contract_error", "responses_tools_unsupported");
    private static final Set<String> REQUEST_ROUTE_OWNERS = Set.of("factory", "router");
    private static final Set<String> REQUEST_ROUTE_PROTOCOLS = Set.of(
            "openai_chat_completions", "openai_completions", "openai_responses", "ollama_native", "unknown");
    private static final Set<String> REQUEST_ATTEMPT_ROLES = Set.of("primary", "fallback");
    private static final Set<String> REQUEST_ATTEMPT_OUTCOMES = Set.of("success", "failed", "cancelled", "partial");
    private static final Set<String> REQUEST_ATTEMPT_FAILURE_CLASSES = Set.of(
            "none", "auth_missing", "bad_request", "health_down", "model_missing", "vram_oom", "gpu_device_lost", "timeout_soft",
            "soft_circuit_open", "rate_limit_cooldown", "cancelled_neutral", "provider_error",
            "context_too_small", "embedding_dim_mismatch", "local_unsupported_managed_rag",
            "stream_error", "response_model_unverified", "disabled", "unknown");
    private static final Set<String> ENDPOINT_SPECIFIC_TERMINALS = Set.of(
            "upstream_5xx", "model_unavailable");
    private static final long ENDPOINT_PROBE_MAX_AGE_MS = 300_000L;
    static final int REQUEST_ATTEMPT_LEDGER_CAPACITY = 8;
    private static final String VERCEL_AI_GATEWAY_RESPONSES_URL =
            "https://ai-gateway.vercel.sh/v1/responses";
    private static final String VERCEL_AI_GATEWAY_ENDPOINT_LABEL = "ai-gateway.vercel.sh";
    private static final String VERCEL_ZAI_MODEL = "zai/glm-5.2";
    private static final String VERCEL_ZAI_PROVIDER = "zai";
    private static final String VERCEL_GATEWAY_RECEIPT_SOURCE = "vercel_ai_gateway_routing";
    private static final ObjectMapper REQUEST_ATTEMPT_MESSAGE_MAPPER = new ObjectMapper();
    private static final ObjectMapper REQUEST_ATTEMPT_CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final Logger REQUEST_PROOF_LOG = LoggerFactory.getLogger(
            ModelRuntimeHealthTracker.class.getName() + ".requestProof");

    private final ConcurrentHashMap<Key, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RouteHealthKey, RouteSnapshot> routeSnapshots = new ConcurrentHashMap<>();
    private final Object endpointQuarantineLock = new Object();
    private final LinkedHashMap<EndpointKey, EndpointQuarantine> endpointQuarantines =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Object requestTimelineLock = new Object();
    private final LinkedHashMap<String, RequestTimeline> requestTimelines = new LinkedHashMap<>();
    private final RequestTimelineRetentionPolicy requestTimelineRetentionPolicy =
            new RequestTimelineRetentionPolicy();
    private final ThreadLocal<RequestAttemptThreadCounter> requestAttemptThreadCounter =
            ThreadLocal.withInitial(RequestAttemptThreadCounter::new);
    private final ThreadLocal<RequestAttemptAppContext> requestAttemptAppContext = new ThreadLocal<>();

    /**
     * Start one application-owned request timeline. Raw correlation values are
     * hashed before the bounded process-local state is updated.
     */
    public String beginRequestTimeline(String requestId, String sessionId) {
        String timelineId = UUID.randomUUID().toString();
        RequestTimeline timeline = new RequestTimeline(
                timelineId,
                requestTimelineHash(requestId, timelineId + ":request"),
                requestTimelineHash(sessionId, timelineId + ":session"));
        synchronized (requestTimelineLock) {
            requestTimelineRetentionPolicy.retain(requestTimelines, timelineId, timeline);
        }
        // Copy-on-write rows let a snapshot copy an in-flight timeline without waiting for completion.
        com.example.lms.search.TraceStore.put(REQUEST_LIFECYCLE_TRACE_KEY, timeline.lifecycle);
        com.example.lms.search.TraceStore.put("llm.request.lifecycleDropped", timeline.lifecycleDropped);
        com.example.lms.search.TraceStore.put("llm.request.clientStartCoverage", "instrumented_partial");
        observeRunCancellation(timelineId, com.example.lms.service.chat.ChatRunExecutionContext.current());
        return timelineId;
    }

    private void observeRunCancellation(String timelineId, com.example.lms.service.chat.ChatRunExecutionContext run) {
        if (run == null || timelineId == null || timelineId.isBlank()) return;
        String runHash = run.redactedRunIdentity();
        run.observeCancellation(timelineId, at -> {
            synchronized (requestTimelineLock) {
                RequestTimeline timeline = requestTimelines.get(timelineId);
                if (timeline == null || timeline.cancellationAtEpochMs != 0) return;
                timeline.cancellationAtEpochMs = at;
                appendLifecycle(timelineId, runHash, 0, 0, "none", "cancel_accepted", "application_cancellation", at);
            }
        });
    }

    /** Starts an application-owned attempt identity, not a claim that bytes reached a socket or server. */
    public ClientAttempt beginClientAttempt(String role) {
        Object rawId = com.example.lms.search.TraceStore.get(REQUEST_TIMELINE_TRACE_KEY);
        String timelineId = rawId == null ? "" : String.valueOf(rawId);
        RequestAttemptAppContext appReservation = requestAttemptAppContext.get();
        if (appReservation == null || !timelineId.equals(appReservation.timelineId())
                || !appReservation.firstClientReservation().compareAndSet(true, false)) {
            reserveRequestInferenceAttempt(timelineId);
        }
        var run = com.example.lms.service.chat.ChatRunExecutionContext.current();
        observeRunCancellation(timelineId, run);
        int logical = 0;
        int sequence = 0;
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline != null) {
                RequestAttemptAppContext app = requestAttemptAppContext.get();
                logical = app != null && timelineId.equals(app.timelineId())
                        ? app.logicalCallOrdinal() : timeline.beginLogicalCall();
                sequence = ++timeline.clientAttemptTotal;
            }
        }
        ClientAttempt attempt = new ClientAttempt(timelineId, run, logical, sequence,
                REQUEST_ATTEMPT_ROLES.contains(role) ? role : "primary", currentClientAttempt.get());
        currentClientAttempt.set(attempt);
        attempt.event("application_call_intent", "application", System.currentTimeMillis());
        return attempt;
    }

    /** Immutable bounded event copy. Late terminals remain distinct from new post-cancel starts. */
    public List<Map<String, Object>> redactedRequestLifecycle(String timelineId) {
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            return timeline == null ? List.of() : List.copyOf(timeline.lifecycle);
        }
    }

    private void appendLifecycle(String timelineId, String runHash, int logical, int attempt, String role,
                                 String event, String boundary, long at) {
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline == null) return;
            int eventSequence = ++timeline.lifecycleTotal;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requestHash", timeline.requestHash);
            row.put("requestExecutionHash", SafeRedactor.hashValue(timelineId));
            row.put("runHash", runHash);
            row.put("logicalCallOrdinal", logical);
            row.put("attemptSequence", attempt);
            row.put("eventSequence", eventSequence);
            row.put("observedAtEpochMs", at);
            row.put("elapsedMs", Math.max(0L, (System.nanoTime() - timeline.startedNanos) / 1_000_000L));
            row.put("event", event);
            row.put("boundary", boundary);
            row.put("role", role);
            row.put("afterCancel", timeline.cancellationAtEpochMs > 0 && !"cancel_accepted".equals(event));
            row.put("providerReceiptObserved", false);
            if (timeline.lifecycle.size() < 128) timeline.lifecycle.add(Map.copyOf(row));
            else timeline.lifecycleDropped.incrementAndGet();
            try {
                REQUEST_PROOF_LOG.info("[LLM_REQUEST_LIFECYCLE] requestHash={} executionHash={} runHash={} "
                                + "logicalCallOrdinal={} attemptSequence={} eventSequence={} event={} boundary={} "
                                + "observedAtEpochMs={} afterCancel={} dropped={} providerReceiptObserved=false",
                        timeline.requestHash, row.get("requestExecutionHash"), runHash, logical, attempt,
                        eventSequence, event, boundary, at, row.get("afterCancel"), timeline.lifecycleDropped);
            } catch (RuntimeException ignored) { /* observation is fail-soft */ }
        }
    }

    public final class ClientAttempt implements AutoCloseable {
        private final String timelineId;
        private final com.example.lms.service.chat.ChatRunExecutionContext run;
        private final int logical;
        private final int sequence;
        private final String role;
        private final ClientAttempt previous;
        private final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile String boundary = "application";
        private boolean closed;

        private ClientAttempt(String timelineId, com.example.lms.service.chat.ChatRunExecutionContext run,
                              int logical, int sequence, String role, ClientAttempt previous) {
            this.timelineId = timelineId; this.run = run; this.logical = logical;
            this.sequence = sequence; this.role = role; this.previous = previous;
        }

        public void started(String clientBoundary) {
            boundary = "spring_client_request_commit".equals(clientBoundary)
                    ? clientBoundary : "http_client_execute";
            Runnable accepted = () -> event("http_client_started", boundary, System.currentTimeMillis());
            if (Thread.currentThread().isInterrupted() || (run != null && !run.admitCall(accepted))) {
                event("call_blocked", boundary, System.currentTimeMillis());
                throw new java.util.concurrent.CancellationException("exact chat run cancelled before HTTP attempt");
            }
            if (run == null) accepted.run();
        }

        public void finished(Throwable failure) {
            if (!completed.compareAndSet(false, true)) return;
            event(failure == null ? "http_client_completed"
                    : LlmGatewayFailureClassifier.isCancellation(failure) ? "http_client_cancelled" : "http_client_failed",
                    boundary, System.currentTimeMillis());
        }

        private void event(String event, String boundary, long at) {
            appendLifecycle(timelineId, run == null ? "hash:unknown" : run.redactedRunIdentity(),
                    logical, sequence, role, event, boundary, at);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) currentClientAttempt.remove(); else currentClientAttempt.set(previous);
            synchronized (requestTimelineLock) {
                RequestTimeline timeline = requestTimelines.get(timelineId);
                if (timeline != null) {
                    com.example.lms.search.TraceStore.put(REQUEST_LIFECYCLE_TRACE_KEY, timeline.lifecycle);
                    com.example.lms.search.TraceStore.put("llm.request.lifecycleDropped", timeline.lifecycleDropped);
                    com.example.lms.search.TraceStore.put("llm.request.clientStartCoverage", "instrumented_partial");
                }
            }
        }
    }

    /** Keeps the installed HTTP implementation and timeout policy; each SDK retry invokes execute separately. */
    public dev.langchain4j.http.client.HttpClientBuilder observedHttpClientBuilder(String role) {
        var delegate = dev.langchain4j.http.client.HttpClientBuilderLoader.loadHttpClientBuilder();
        return new dev.langchain4j.http.client.HttpClientBuilder() {
            public java.time.Duration connectTimeout() { return delegate.connectTimeout(); }
            public dev.langchain4j.http.client.HttpClientBuilder connectTimeout(java.time.Duration value) {
                delegate.connectTimeout(value); return this;
            }
            public java.time.Duration readTimeout() { return delegate.readTimeout(); }
            public dev.langchain4j.http.client.HttpClientBuilder readTimeout(java.time.Duration value) {
                delegate.readTimeout(value); return this;
            }
            public dev.langchain4j.http.client.HttpClient build() {
                var client = delegate.build();
                return new dev.langchain4j.http.client.HttpClient() {
                    public dev.langchain4j.http.client.SuccessfulHttpResponse execute(dev.langchain4j.http.client.HttpRequest request) {
                        var groq=reserveGroq(request);
                        try (ClientAttempt attempt = beginClientAttempt(role);
                             var cancellation = com.example.lms.service.chat.ChatRunExecutionContext.interruptibleCall("jdk_http")) {
                            try {
                                attempt.started("http_client_execute");
                                var resolved = resolveServiceRequest(request);
                                var receipt = directReceipt.get();
                                if(receipt!=null)receipt.started(resolved.url());
                                var response = client.execute(resolved);
                                if(groq!=null)groqFreeTierGuard.observe(groq,response.statusCode(),response.headers());
                                if(receipt!=null)receipt.received(response.statusCode(),response.headers());
                                attempt.finished(null);
                                return response;
                            } catch (RuntimeException failure) {
                                if(groq!=null)groqFreeTierGuard.observe(groq,failure instanceof dev.langchain4j.exception.HttpException h?h.statusCode():0,Map.of());
                                recordApiFailure(request.url(), com.example.lms.debug.ApiFailureRecorder.requestModel(request.body()), failure);
                                var receipt = directReceipt.get();
                                if(receipt!=null)receipt.failed(failure);
                                attempt.finished(failure); throw failure;
                            }
                        }
                    }
                    public void execute(dev.langchain4j.http.client.HttpRequest request,
                                        dev.langchain4j.http.client.sse.ServerSentEventParser parser,
                                        dev.langchain4j.http.client.sse.ServerSentEventListener listener) {
                        // Streaming Groq has no bounded token/header receipt in this adapter yet.
                        if(com.example.lms.agent.GroqFreeTierGuard.isGroq(request.url()))throw new IllegalStateException("groq_streaming_not_admitted");
                        com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
                        com.example.lms.search.TraceStore.put("llm.request.asyncClientStartCoverage", "not_observed");
                        var recorded = new java.util.concurrent.atomic.AtomicBoolean();
                        var observed = new dev.langchain4j.http.client.sse.ServerSentEventListener() {
                            public void onOpen(dev.langchain4j.http.client.SuccessfulHttpResponse response) { listener.onOpen(response); }
                            public void onEvent(dev.langchain4j.http.client.sse.ServerSentEvent event) { listener.onEvent(event); }
                            public void onClose() { listener.onClose(); }
                            public void onError(Throwable failure) {
                                if (recorded.compareAndSet(false, true)) recordApiFailure(request.url(), com.example.lms.debug.ApiFailureRecorder.requestModel(request.body()), failure);
                                listener.onError(failure);
                            }
                        };
                        try { client.execute(resolveServiceRequest(request), parser, observed); }
                        catch (RuntimeException failure) {
                            if (recorded.compareAndSet(false, true)) recordApiFailure(request.url(), com.example.lms.debug.ApiFailureRecorder.requestModel(request.body()), failure);
                            throw failure;
                        }
                    }
                };
            }
        };
    }

    /** Record one allowlisted phase. Unknown IDs and invalid transitions fail soft. */
    public void recordRequestPhase(
            String timelineId,
            String phase,
            String modelId,
            String endpointLabel,
            String terminalClass) {
        if (timelineId == null || timelineId.isBlank()) {
            return;
        }
        String safePhase = canonicalTimelineValue(phase);
        if (!REQUEST_TIMELINE_PHASES.contains(safePhase)) {
            return;
        }
        String safeModelOrFinalHash = "final_boundary".equals(safePhase)
                ? requestTimelineProofHash(modelId)
                : requestTimelineModelHash(modelId);
        if ("final_boundary".equals(safePhase) && "hash:unknown".equals(safeModelOrFinalHash)) {
            return;
        }
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline != null) {
                timeline.record(
                        safePhase,
                        safeModelOrFinalHash,
                        requestTimelineEndpoint(endpointLabel),
                        requestTimelineTerminal(terminalClass));
            }
        }
    }

    /** Return an immutable redacted copy; never exposes raw request/session/model. */
    public List<Map<String, Object>> redactedRequestTimeline(String timelineId) {
        if (timelineId == null || timelineId.isBlank()) {
            return List.of();
        }
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            return timeline == null ? List.of() : timeline.redactedRows();
        }
    }

    /**
     * Convert route metadata to an immutable redacted descriptor before a
     * request-scoped model wrapper retains it.
     */
    public RequestAttemptRoute redactedRequestAttemptRoute(
            String routeKey,
            String modelId,
            String endpointLabel,
            String protocol) {
        String safeProtocol = canonicalTimelineValue(protocol);
        if (!REQUEST_ROUTE_PROTOCOLS.contains(safeProtocol)) {
            safeProtocol = "unknown";
        }
        return new RequestAttemptRoute(
                requestTimelineModelHash(routeKey),
                requestTimelineModelHash(modelId),
                requestTimelineEndpoint(endpointLabel),
                safeProtocol);
    }

    /**
     * Append one completed model invocation to the separate attempt ledger.
     * Controller-owned request terminal state and post-terminal diagnostic
     * probes are intentionally not changed here.
     */
    public void recordRequestAttempt(
            String timelineId,
            String role,
            RequestAttemptRoute route,
            String outcome,
            String failureClass,
            String terminalClass,
            long elapsedMs) {
        recordRequestAttemptEvidence(
                timelineId,
                role,
                route,
                outcome,
                failureClass,
                terminalClass,
                elapsedMs,
                "hash:unknown",
                "hash:unknown",
                0,
                0,
                "hash:unknown",
                0,
                0,
                0,
                0,
                true,
                false,
                false,
                false,
                false,
                false);
    }

    /**
     * Append one request-correlated, count/hash-only provider exchange. The
     * caller must pass only pre-hashed payload values; raw prompt, options, and
     * response content are never accepted or retained by this ledger.
     */
    public void recordRequestAttemptEvidence(
            String timelineId,
            String role,
            RequestAttemptRoute route,
            String outcome,
            String failureClass,
            String terminalClass,
            long elapsedMs,
            String promptHash,
            String optionsHash,
            int promptItemCount,
            int optionItemCount,
            String responseHash,
            int responseCharCount,
            int promptUtf8ByteCount,
            int optionsUtf8ByteCount,
            int responseUtf8ByteCount,
            boolean modelAdapterAttemptObserved,
            boolean clientHttpExchangeObserved,
            boolean clientHttpResponseObserved,
            boolean providerAttemptObserved,
            boolean wireAttemptObserved,
            boolean responseObserved) {
        recordRequestAttemptEvidence(
                timelineId,
                role,
                route,
                outcome,
                failureClass,
                terminalClass,
                elapsedMs,
                promptHash,
                optionsHash,
                promptItemCount,
                optionItemCount,
                responseHash,
                responseCharCount,
                promptUtf8ByteCount,
                optionsUtf8ByteCount,
                responseUtf8ByteCount,
                "hash:unknown",
                0,
                "hash:unknown",
                0,
                modelAdapterAttemptObserved,
                clientHttpExchangeObserved,
                clientHttpResponseObserved,
                providerAttemptObserved,
                wireAttemptObserved,
                responseObserved);
    }

    /**
     * Append application-boundary proof plus exact direct-HTTP body proof to
     * one physical-attempt row. All payload arguments are already hashed;
     * neither namespace accepts raw prompt, response, or HTTP body content.
     */
    public void recordRequestAttemptEvidence(
            String timelineId,
            String role,
            RequestAttemptRoute route,
            String outcome,
            String failureClass,
            String terminalClass,
            long elapsedMs,
            String promptHash,
            String optionsHash,
            int promptItemCount,
            int optionItemCount,
            String responseHash,
            int responseCharCount,
            int promptUtf8ByteCount,
            int optionsUtf8ByteCount,
            int responseUtf8ByteCount,
            String httpRequestBodyHash,
            int httpRequestBodyUtf8ByteCount,
            String httpResponseBodyHash,
            int httpResponseBodyUtf8ByteCount,
            boolean modelAdapterAttemptObserved,
            boolean clientHttpExchangeObserved,
            boolean clientHttpResponseObserved,
            boolean providerAttemptObserved,
            boolean wireAttemptObserved,
            boolean responseObserved) {
        String safeRole = canonicalTimelineValue(role);
        String safeOutcome = canonicalTimelineValue(outcome);
        String safeFailureClass = canonicalTimelineValue(failureClass);
        if (timelineId == null || timelineId.isBlank()
                || route == null
                || !REQUEST_ATTEMPT_ROLES.contains(safeRole)
                || !REQUEST_ATTEMPT_OUTCOMES.contains(safeOutcome)) {
            return;
        }
        if (!REQUEST_ATTEMPT_FAILURE_CLASSES.contains(safeFailureClass)) {
            safeFailureClass = "unknown";
        }
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline != null) {
                int attemptTotalBefore = timeline.attemptTotal;
                int attemptDroppedBefore = timeline.attemptDropped;
                int acceptedAttemptCountBefore = timeline.attempts.size();
                RequestAttemptAppContext appContext = requestAttemptAppContext.get();
                ClientAttempt activeClient = currentClientAttempt.get();
                int logicalCallOrdinal = appContext != null
                        && timelineId.equals(appContext.timelineId())
                        && appContext.logicalCallOrdinal() > 0
                        ? appContext.logicalCallOrdinal()
                        : activeClient != null && timelineId.equals(activeClient.timelineId)
                                ? activeClient.logical : timeline.beginLogicalCall();
                // Legacy caller booleans are client-owned assertions. Only the
                // controlled receiver seam below may promote provider/wire proof.
                timeline.recordAttempt(
                        logicalCallOrdinal,
                        safeRole,
                        route,
                        safeOutcome,
                        safeFailureClass,
                        requestTimelineTerminal(terminalClass),
                        Math.min(Math.max(0L, elapsedMs), 86_400_000L),
                        requestTimelineProofHash(promptHash),
                        requestTimelineProofHash(optionsHash),
                        Math.min(Math.max(0, promptItemCount), 1_024),
                        Math.min(Math.max(0, optionItemCount), 64),
                        requestTimelineProofHash(responseHash),
                        Math.min(Math.max(0, responseCharCount), 50_000_000),
                        Math.min(Math.max(0, promptUtf8ByteCount), 50_000_000),
                        Math.min(Math.max(0, optionsUtf8ByteCount), 1_000_000),
                        Math.min(Math.max(0, responseUtf8ByteCount), 50_000_000),
                        requestTimelineProofHash(httpRequestBodyHash),
                        Math.min(Math.max(0, httpRequestBodyUtf8ByteCount), 50_000_000),
                        requestTimelineProofHash(httpResponseBodyHash),
                        Math.min(Math.max(0, httpResponseBodyUtf8ByteCount), 50_000_000),
                        modelAdapterAttemptObserved,
                        clientHttpExchangeObserved,
                        clientHttpResponseObserved,
                        responseObserved);
                if (timeline.attemptTotal > attemptTotalBefore) {
                    requestAttemptThreadCounter.get().record(timelineId);
                }
                if (timeline.attempts.size() > acceptedAttemptCountBefore) {
                    ClientAttempt clientAttempt = currentClientAttempt.get();
                    if (clientAttempt != null && timelineId.equals(clientAttempt.timelineId)) {
                        timeline.attemptClientSequences.put(timeline.attemptTotal, clientAttempt.sequence);
                    }
                    logAcceptedRequestAttempt(
                            timeline,
                            timeline.attempts.get(timeline.attempts.size() - 1));
                } else if (timeline.attemptDropped > attemptDroppedBefore) {
                    logDroppedRequestAttemptState(timeline);
                }
            }
        }
    }

    private static void logAcceptedRequestAttempt(RequestTimeline timeline, RequestAttempt attempt) {
        try {
            REQUEST_PROOF_LOG.info(
                    "[LLM_REQUEST_PROOF] requestHash={} rowAccepted=true sequence={} logicalCallOrdinal={} "
                            + "attemptOrdinal={} role={} outcome={} failureClass={} "
                            + "terminalClass={} promptHash={} optionsHash={} responseHash={} "
                            + "promptItems={} optionItems={} promptUtf8Bytes={} optionsUtf8Bytes={} "
                            + "responseUtf8Bytes={} httpRequestBodyHash={} httpRequestBodyUtf8Bytes={} "
                            + "httpResponseBodyHash={} httpResponseBodyUtf8Bytes={} "
                            + "elapsedMs={} attemptTotal={} attemptDropped={} "
                            + "adapterAttempt={} clientHttpExchange={} clientHttpResponse={} "
                            + "evidenceBoundary={} providerReceiptObserved={} providerReceiptSource={} "
                            + "providerAttempt={} wireAttempt={} responseObserved={} clientAttemptSequence={}",
                    timeline.requestHash,
                    attempt.sequence(),
                    attempt.logicalCallOrdinal(),
                    attempt.attemptOrdinal(),
                    attempt.role(),
                    attempt.outcome(),
                    attempt.failureClass(),
                    attempt.terminalClass(),
                    attempt.promptHash(),
                    attempt.optionsHash(),
                    attempt.responseHash(),
                    attempt.promptItemCount(),
                    attempt.optionItemCount(),
                    attempt.promptUtf8ByteCount(),
                    attempt.optionsUtf8ByteCount(),
                    attempt.responseUtf8ByteCount(),
                    attempt.httpRequestBodyHash(),
                    attempt.httpRequestBodyUtf8ByteCount(),
                    attempt.httpResponseBodyHash(),
                    attempt.httpResponseBodyUtf8ByteCount(),
                    attempt.elapsedMs(),
                    timeline.attemptTotal,
                    timeline.attemptDropped,
                    attempt.modelAdapterAttemptObserved(),
                    attempt.clientHttpExchangeObserved(),
                    attempt.clientHttpResponseObserved(),
                    attempt.evidenceBoundary(),
                    attempt.providerReceiptObserved(),
                    attempt.providerReceiptSource(),
                    attempt.providerAttemptObserved(),
                    attempt.wireAttemptObserved(),
                    attempt.responseObserved(),
                    timeline.attemptClientSequences.getOrDefault(attempt.sequence(), 0));
        } catch (RuntimeException ignored) {
            // Proof logging must never change the provider call outcome.
        }
    }

    private static void logDroppedRequestAttemptState(RequestTimeline timeline) {
        try {
            REQUEST_PROOF_LOG.info(
                    "[LLM_REQUEST_PROOF] requestHash={} rowAccepted=false attemptTotal={} attemptDropped={}",
                    timeline.requestHash,
                    timeline.attemptTotal,
                    timeline.attemptDropped);
        } catch (RuntimeException ignored) {
            // Count-only overflow evidence must not change the provider call outcome.
        }
    }

    /** Return an immutable redacted copy of the bounded per-request attempt ledger. */
    public List<Map<String, Object>> redactedRequestAttemptLedger(String timelineId) {
        if (timelineId == null || timelineId.isBlank()) {
            return List.of();
        }
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            return timeline == null ? List.of() : timeline.redactedAttemptRows();
        }
    }

    /**
     * Promote one exact client-HTTP row only when a controlled receiver observed
     * the same request and response bytes. Package scope keeps this test seam
     * unavailable to ordinary application/provider clients.
     */
    boolean recordControlledProviderReceipt(
            String timelineId,
            int logicalCallOrdinal,
            int attemptOrdinal,
            String httpRequestBodyHash,
            int httpRequestBodyUtf8ByteCount,
            String httpResponseBodyHash,
            int httpResponseBodyUtf8ByteCount) {
        String safeRequestBodyHash = requestTimelineProofHash(httpRequestBodyHash);
        String safeResponseBodyHash = requestTimelineProofHash(httpResponseBodyHash);
        if (timelineId == null || timelineId.isBlank()
                || logicalCallOrdinal <= 0
                || attemptOrdinal <= 0
                || !isExactSha256(safeRequestBodyHash)
                || !isExactSha256(safeResponseBodyHash)
                || httpRequestBodyUtf8ByteCount <= 0
                || httpRequestBodyUtf8ByteCount > 50_000_000
                || httpResponseBodyUtf8ByteCount <= 0
                || httpResponseBodyUtf8ByteCount > 50_000_000) {
            return false;
        }
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline == null || !timeline.recordControlledProviderReceipt(
                    logicalCallOrdinal,
                    attemptOrdinal,
                    safeRequestBodyHash,
                    httpRequestBodyUtf8ByteCount,
                    safeResponseBodyHash,
                    httpResponseBodyUtf8ByteCount)) {
                return false;
            }
            logControlledProviderReceipt(timeline, logicalCallOrdinal, attemptOrdinal);
            return true;
        }
    }

    /**
     * Promote the one exact client-HTTP row attested by a controlled receiver.
     * Package scope keeps this production bridge inside the concrete local-provider
     * adapter boundary; ambiguous or partial body matches fail closed.
     */
    boolean recordControlledProviderReceiptByHttpBodies(
            String timelineId,
            String protocol,
            String httpRequestBodyHash,
            int httpRequestBodyUtf8ByteCount,
            String httpResponseBodyHash,
            int httpResponseBodyUtf8ByteCount) {
        return recordProviderReceiptByHttpBodies(
                timelineId,
                protocol,
                null,
                null,
                "controlled_http_server",
                httpRequestBodyHash,
                httpRequestBodyUtf8ByteCount,
                httpResponseBodyHash,
                httpResponseBodyUtf8ByteCount);
    }

    /**
     * Promote one exact client-HTTP row only when the trusted Vercel AI Gateway
     * returned coherent, bounded routing metadata for the configured Z.ai model.
     * Ordinary caller booleans, HTTP 200 alone, loopback endpoints, and raw bodies
     * cannot enter this receipt boundary.
     */
    public boolean recordAttestedVercelGatewayReceipt(
            String timelineId,
            String endpointUrl,
            String protocol,
            String provider,
            int providerStatusCode,
            int totalProviderAttemptCount,
            String httpRequestBodyHash,
            int httpRequestBodyUtf8ByteCount,
            String httpResponseBodyHash,
            int httpResponseBodyUtf8ByteCount) {
        if (!VERCEL_AI_GATEWAY_RESPONSES_URL.equals(endpointUrl)
                || !"openai_responses".equals(canonicalTimelineValue(protocol))
                || !VERCEL_ZAI_PROVIDER.equals(canonicalTimelineValue(provider))
                || providerStatusCode != 200
                || totalProviderAttemptCount <= 0
                || totalProviderAttemptCount > REQUEST_ATTEMPT_LEDGER_CAPACITY) {
            return false;
        }
        return recordProviderReceiptByHttpBodies(
                timelineId,
                protocol,
                VERCEL_AI_GATEWAY_ENDPOINT_LABEL,
                requestTimelineModelHash(VERCEL_ZAI_MODEL),
                VERCEL_GATEWAY_RECEIPT_SOURCE,
                httpRequestBodyHash,
                httpRequestBodyUtf8ByteCount,
                httpResponseBodyHash,
                httpResponseBodyUtf8ByteCount);
    }

    private boolean recordProviderReceiptByHttpBodies(
            String timelineId,
            String protocol,
            String requiredEndpointLabel,
            String requiredModelHash,
            String receiptSource,
            String httpRequestBodyHash,
            int httpRequestBodyUtf8ByteCount,
            String httpResponseBodyHash,
            int httpResponseBodyUtf8ByteCount) {
        String safeProtocol = canonicalTimelineValue(protocol);
        String safeRequestBodyHash = requestTimelineProofHash(httpRequestBodyHash);
        String safeResponseBodyHash = requestTimelineProofHash(httpResponseBodyHash);
        if (timelineId == null || timelineId.isBlank()
                || !REQUEST_ROUTE_PROTOCOLS.contains(safeProtocol)
                || "unknown".equals(safeProtocol)
                || !isExactSha256(safeRequestBodyHash)
                || !isExactSha256(safeResponseBodyHash)
                || httpRequestBodyUtf8ByteCount <= 0
                || httpRequestBodyUtf8ByteCount > 50_000_000
                || httpResponseBodyUtf8ByteCount <= 0
                || httpResponseBodyUtf8ByteCount > 50_000_000) {
            return false;
        }
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline == null || timeline.terminal) {
                return false;
            }
            RequestAttempt candidate = null;
            for (RequestAttempt attempt : timeline.attempts) {
                if (attempt.route() == null
                        || !safeProtocol.equals(attempt.route().protocol())
                        || (requiredEndpointLabel != null
                                && !requiredEndpointLabel.equals(attempt.route().endpointLabel()))
                        || (requiredModelHash != null
                                && !requiredModelHash.equals(attempt.route().modelHash()))
                        || !"client_http_response".equals(attempt.evidenceBoundary())
                        || attempt.providerReceiptObserved()
                        || !attempt.clientHttpResponseObserved()
                        || !safeRequestBodyHash.equals(attempt.httpRequestBodyHash())
                        || httpRequestBodyUtf8ByteCount != attempt.httpRequestBodyUtf8ByteCount()
                        || !safeResponseBodyHash.equals(attempt.httpResponseBodyHash())
                        || httpResponseBodyUtf8ByteCount != attempt.httpResponseBodyUtf8ByteCount()) {
                    continue;
                }
                if (candidate != null) {
                    return false;
                }
                candidate = attempt;
            }
            if (candidate == null || !timeline.recordProviderReceipt(
                    candidate.logicalCallOrdinal(),
                    candidate.attemptOrdinal(),
                    safeRequestBodyHash,
                    httpRequestBodyUtf8ByteCount,
                    safeResponseBodyHash,
                    httpResponseBodyUtf8ByteCount,
                    receiptSource)) {
                return false;
            }
            logProviderReceipt(
                    timeline,
                    candidate.logicalCallOrdinal(),
                    candidate.attemptOrdinal(),
                    receiptSource);
            return true;
        }
    }

    private static void logControlledProviderReceipt(
            RequestTimeline timeline,
            int logicalCallOrdinal,
            int attemptOrdinal) {
        logProviderReceipt(timeline, logicalCallOrdinal, attemptOrdinal, "controlled_http_server");
    }

    private static void logProviderReceipt(
            RequestTimeline timeline,
            int logicalCallOrdinal,
            int attemptOrdinal,
            String receiptSource) {
        try {
            REQUEST_PROOF_LOG.info(
                    "[LLM_PROVIDER_RECEIPT] requestHash={} logicalCallOrdinal={} attemptOrdinal={} "
                            + "evidenceBoundary=provider_receive providerReceiptObserved=true "
                            + "providerReceiptSource={} providerAttempt=true wireAttempt=true",
                    timeline.requestHash,
                    logicalCallOrdinal,
                    attemptOrdinal,
                    receiptSource);
        } catch (RuntimeException ignored) {
            // Receipt logging must never change the controlled receiver result.
        }
    }

    /** Enable one shared inference ceiling without restarting prior request work. */
    public void limitRequestInferenceAttempts(String timelineId, int limit) {
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline == null) throw inferenceLimitFailure();
            int bounded = Math.max(1, Math.min(4, limit));
            if (timeline.inferenceAttemptLimit == 0) {
                timeline.inferenceAttemptLimit = bounded;
                timeline.inferenceAttemptTotal = Math.max(timeline.attemptTotal, timeline.clientAttemptTotal);
            } else timeline.inferenceAttemptLimit = Math.min(timeline.inferenceAttemptLimit, bounded);
        }
    }

    private boolean reserveRequestInferenceAttempt(String timelineId) {
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline == null || timeline.inferenceAttemptLimit == 0) return false;
            com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
            var budget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
            if (budget != null && budget.remainingMillis() <= 0) {
                com.example.lms.search.TraceStore.put("llm.gateway.fallback.skippedReason", "request_deadline_exhausted");
                throw new com.example.lms.llm.gateway.LlmGatewayException(
                        "Inference deadline exhausted", LlmFailureClass.TIMEOUT_SOFT, "failover_exhausted");
            }
            if (timeline.inferenceAttemptTotal >= timeline.inferenceAttemptLimit) throw inferenceLimitFailure();
            timeline.inferenceAttemptTotal++;
            com.example.lms.search.TraceStore.put("llm.gateway.attemptCount", timeline.inferenceAttemptTotal);
            return true;
        }
    }

    private void releaseUnstartedLocalInferenceReservation(String timelineId, RuntimeException failure) {
        if (!(failure instanceof com.example.lms.llm.gateway.LlmGatewayException gatewayFailure)
                || !"local_endpoint_open".equals(gatewayFailure.reasonCode())) return;
        RequestAttemptAppContext app = requestAttemptAppContext.get();
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline != null && app != null && timelineId.equals(app.timelineId())
                    && app.firstClientReservation().compareAndSet(true, false)) {
                timeline.inferenceAttemptTotal = Math.max(0, timeline.inferenceAttemptTotal - 1);
                com.example.lms.search.TraceStore.put("llm.gateway.attemptCount", timeline.inferenceAttemptTotal);
            }
        }
    }

    private static com.example.lms.llm.gateway.LlmGatewayException inferenceLimitFailure() {
        com.example.lms.search.TraceStore.put("llm.gateway.fallback.skippedReason", "request_attempt_limit");
        REQUEST_PROOF_LOG.info("[llm-failover] outcome=blocked cause=request_attempt_limit");
        return new com.example.lms.llm.gateway.LlmGatewayException(
                "Request inference attempts exhausted", LlmFailureClass.SOFT_CIRCUIT_OPEN, "failover_exhausted");
    }

    /** Return this thread's count-only attempt marker for nested adapter ownership checks. */
    public int currentThreadRequestAttemptTotal(String timelineId) {
        if (timelineId == null || timelineId.isBlank()) {
            return 0;
        }
        return requestAttemptThreadCounter.get().total(timelineId);
    }

    /**
     * Decorate a model at the application boundary with request-scoped,
     * count/hash-only proof. The options fingerprint is captured eagerly so
     * this wrapper never retains a raw options map.
     */
    public ChatModel decorateRequestAttempt(
            ChatModel delegate,
            String role,
            RequestAttemptRoute route,
            Map<String, ?> options) {
        if (delegate == null || delegate instanceof RequestAttemptRecordingChatModel) {
            return delegate;
        }
        return new RequestAttemptRecordingChatModel(
                this,
                delegate,
                canonicalTimelineValue(role),
                route,
                requestAttemptOptionsFingerprint(options));
    }

    /**
     * Build the single complete application option envelope used by every
     * request-attempt boundary. Missing owned values are explicit rather than
     * omitted, and sorted keys make insertion order irrelevant before hashing.
     */
    public static Map<String, Object> requestAttemptOptionEnvelope(
            String provider,
            String model,
            String protocol,
            Map<String, ?> ownedOptions) {
        SortedMap<String, Object> envelope = new TreeMap<>();
        envelope.put("fallbackEnabled", "unknown");
        envelope.put("fallbackKey", "unknown");
        envelope.put("frequencyPenalty", "unknown");
        envelope.put("maxOutputTokens", "unknown");
        envelope.put("maxRetries", "unknown");
        envelope.put("maxTokens", "unknown");
        envelope.put("model", requestAttemptOptionValue(model));
        envelope.put("presencePenalty", "unknown");
        envelope.put("protocol", requestAttemptOptionValue(protocol));
        envelope.put("provider", requestAttemptOptionValue(provider));
        envelope.put("temperature", "unknown");
        envelope.put("timeoutMs", "unknown");
        envelope.put("topP", "unknown");
        if (ownedOptions != null) {
            for (String key : List.of(
                    "fallbackEnabled", "fallbackKey", "frequencyPenalty", "maxOutputTokens",
                    "maxRetries", "maxTokens", "presencePenalty", "temperature", "timeoutMs", "topP")) {
                if (ownedOptions.containsKey(key)) {
                    envelope.put(key, requestAttemptOptionValue(ownedOptions.get(key)));
                }
            }
        }
        return java.util.Collections.unmodifiableSortedMap(envelope);
    }

    private static Object requestAttemptOptionValue(Object value) {
        if (value == null) {
            return "unknown";
        }
        if (value instanceof String stringValue && stringValue.isBlank()) {
            return "unknown";
        }
        return value;
    }

    /**
     * Resolve the current application-boundary fingerprint for a direct HTTP
     * adapter. When no wrapper owns the thread, compute the same canonical
     * proof from the adapter's messages and complete local option envelope.
     * The returned value contains hashes and counts only.
     */
    public RequestAttemptAppEvidence currentOrComputeRequestAttemptAppEvidence(
            String timelineId,
            List<ChatMessage> messages,
            Map<String, ?> fallbackOptions) {
        RequestAttemptAppContext current = requestAttemptAppContext.get();
        if (current != null && current.timelineId().equals(timelineId)) {
            return current.evidence();
        }
        return requestAttemptAppEvidence(
                requestAttemptMessageFingerprint(messages),
                requestAttemptOptionsFingerprint(fallbackOptions));
    }

    /**
     * Compute route-local application evidence without consulting a pending
     * outer context. Alternate/fallback and disabled routes use this seam so a
     * same-timeline primary cannot donate a different options identity.
     */
    public RequestAttemptAppEvidence computeRequestAttemptAppEvidence(
            List<ChatMessage> messages,
            Map<String, ?> options) {
        return requestAttemptAppEvidence(
                requestAttemptMessageFingerprint(messages),
                requestAttemptOptionsFingerprint(options));
    }

    private RequestAttemptAppContext installRequestAttemptAppContext(
            String timelineId,
            RequestAttemptAppEvidence evidence) {
        RequestAttemptAppContext previous = requestAttemptAppContext.get();
        int logicalCallOrdinal = previous != null && timelineId.equals(previous.timelineId())
                ? previous.logicalCallOrdinal()
                : beginRequestLogicalCall(timelineId);
        requestAttemptAppContext.set(new RequestAttemptAppContext(
                timelineId, logicalCallOrdinal, evidence,
                previous != null && timelineId.equals(previous.timelineId())
                        ? previous.firstClientReservation()
                        : new java.util.concurrent.atomic.AtomicBoolean(reserveRequestInferenceAttempt(timelineId))));
        return previous;
    }

    private int beginRequestLogicalCall(String timelineId) {
        if (timelineId == null || timelineId.isBlank()) {
            return 0;
        }
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            return timeline == null ? 0 : timeline.beginLogicalCall();
        }
    }

    private void restoreRequestAttemptAppContext(RequestAttemptAppContext previous) {
        if (previous == null) {
            requestAttemptAppContext.remove();
        } else {
            requestAttemptAppContext.set(previous);
        }
    }

    /** Build a type-preserving disabled-model evidence hook without retaining raw options. */
    public ExpectedFailureAttemptEvidence expectedFailureAttemptEvidence(
            String role,
            RequestAttemptRoute route,
            Map<String, ?> options) {
        return new ExpectedFailureAttemptEvidence(
                this,
                canonicalTimelineValue(role),
                route,
                requestAttemptOptionsFingerprint(requestAttemptOptionEnvelope(
                        optionString(options, "provider"),
                        optionString(options, "model"),
                        optionString(options, "protocol"),
                        options)));
    }

    private static String optionString(Map<String, ?> options, String key) {
        Object value = options == null ? null : options.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Enrich an existing pending row with the physical route selected by the
     * component that actually built the model. Values are allowlisted, hashed,
     * or reduced to host[:port] before storage.
     */
    public void recordRequestSelection(
            String timelineId,
            String routeOwner,
            String selectedModelId,
            String endpointLabel,
            String protocol,
            boolean ambiguousRouteAttempts) {
        recordRequestSelection(
                timelineId,
                routeOwner,
                null,
                null,
                selectedModelId,
                endpointLabel,
                protocol,
                false,
                ambiguousRouteAttempts);
    }

    /**
     * Enrich a request timeline with one redacted-by-construction health route.
     * Raw endpoint and route-key values are converted to fixed hashes before the
     * timeline lock is entered and are never retained by the descriptor.
     */
    public void recordRequestSelection(
            String timelineId,
            String routeOwner,
            String provider,
            String routeKey,
            String selectedModelId,
            String endpointLabel,
            String protocol,
            boolean responseModelVerificationRequired,
            boolean ambiguousRouteAttempts) {
        String safeOwner = canonicalTimelineValue(routeOwner);
        if (timelineId == null || timelineId.isBlank() || !REQUEST_ROUTE_OWNERS.contains(safeOwner)) {
            return;
        }
        String safeProtocol = canonicalTimelineValue(protocol);
        if (!REQUEST_ROUTE_PROTOCOLS.contains(safeProtocol)) {
            safeProtocol = "unknown";
        }
        RouteDescriptor routeDescriptor = requestRouteDescriptor(
                safeOwner,
                provider,
                routeKey,
                selectedModelId,
                endpointLabel,
                safeProtocol,
                responseModelVerificationRequired);
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline != null) {
                timeline.enrichSelection(
                        safeOwner,
                        requestTimelineModelHash(selectedModelId),
                        requestTimelineEndpoint(endpointLabel),
                        safeProtocol,
                        routeDescriptor,
                        ambiguousRouteAttempts);
            }
        }
    }

    /** Return only the immutable safe route key associated with one timeline. */
    public Optional<RouteHealthKey> requestRouteHealthKey(String timelineId) {
        if (timelineId == null || timelineId.isBlank()) {
            return Optional.empty();
        }
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            return timeline == null || timeline.routeDescriptor == null
                    ? Optional.empty()
                    : Optional.of(timeline.routeDescriptor.key());
        }
    }

    /** Record a request-scoped, redacted endpoint probe used only by the repair gate. */
    public void recordEndpointProbe(
            String timelineId,
            String endpointLabel,
            String modelId,
            String protocol,
            boolean success) {
        recordEndpointProbe(timelineId, endpointLabel, modelId, protocol, success, System.currentTimeMillis());
    }

    /**
     * Timestamped overload for callers that ingest probe evidence produced by a
     * bounded diagnostic step. Raw URLs and model IDs are never retained.
     */
    public void recordEndpointProbe(
            String timelineId,
            String endpointLabel,
            String modelId,
            String protocol,
            boolean success,
            long observedAtEpochMs) {
        if (timelineId == null || timelineId.isBlank() || observedAtEpochMs <= 0L) {
            return;
        }
        String safeEndpoint = requestTimelineEndpoint(endpointLabel);
        String safeProtocol = canonicalTimelineValue(protocol);
        if ("unknown".equals(safeEndpoint) || !REQUEST_ROUTE_PROTOCOLS.contains(safeProtocol)) {
            return;
        }
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            if (timeline != null) {
                timeline.recordProbe(new EndpointProbe(
                        safeEndpoint,
                        requestTimelineModelHash(modelId),
                        safeProtocol,
                        success,
                        observedAtEpochMs));
            }
        }
    }

    /**
     * Fail-closed decision contract for a future repair pass. This method never
     * performs a repair; it only releases the evidence gate when causality is
     * exact and request-scoped.
     */
    public EndpointRepairReadiness endpointRepairReadiness(String timelineId) {
        if (timelineId == null || timelineId.isBlank()) {
            return EndpointRepairReadiness.hold("timeline_missing");
        }
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = requestTimelines.get(timelineId);
            return timeline == null
                    ? EndpointRepairReadiness.hold("timeline_missing")
                    : timeline.endpointRepairReadiness(System.currentTimeMillis());
        }
    }

    public static boolean isSeedLocalChatModel(String modelId) {
        String model = canonical(modelId);
        return model != null && SEED_LOCAL_CHAT_MODELS.contains(model);
    }

    /**
     * Build an immutable route identity. Every exposed component is already a
     * bounded label or a non-reversible hash; invalid endpoint/provider/model
     * input fails closed with {@code null} rather than falling back to the legacy
     * provider/model key.
     */
    public RouteHealthKey routeHealthKey(
            String provider,
            String endpointLabel,
            String modelId,
            String context) {
        String safeProvider = canonicalRouteProvider(provider);
        String endpointHash = endpointIdentityHash(endpointLabel);
        String safeModel = canonicalRouteModel(modelId);
        String safeContext = canonicalRouteContext(context);
        if (safeProvider == null
                || "unknown".equals(safeProvider)
                || "unknown".equals(endpointHash)
                || safeModel == null
                || safeContext == null) {
            return null;
        }
        return new RouteHealthKey(safeProvider, endpointHash, safeModel, safeContext);
    }

    /**
     * Transport/adapter success is deliberately attempt-only. The application
     * attempt ledger owns transport evidence; this method cannot mutate route
     * health or the JVM-local semantic-success clock.
     */
    public void recordAttemptSuccess(RouteHealthKey route) {
        // Intentionally no route-health mutation.
    }

    /** Compatibility overload for active transport callbacks. */
    public void recordAttemptSuccess(
            String provider,
            String modelId,
            OpenAiEndpointCompatibility.Endpoint endpoint) {
        // Intentionally no route-health mutation.
    }

    /** Record one explicit failure against exactly one immutable route key. */
    public void recordRouteFailure(RouteHealthKey route, String reason) {
        if (route == null) {
            return;
        }
        String safeReason = sanitizeReason(reason);
        OpenAiEndpointCompatibility.Endpoint endpoint = endpointFromContext(route.context());
        routeSnapshots.compute(route, (key, old) -> {
            long successes = old == null ? 0L : old.successCount();
            long failures = old == null ? 1L : old.failureCount() + 1L;
            return new RouteSnapshot(
                    key.model(),
                    key.provider(),
                    endpoint,
                    key.endpointHash(),
                    key.context(),
                    successes,
                    failures,
                    safeReason,
                    false);
        });
    }

    /**
     * Attribute a workflow failure only when the current bounded timeline has
     * one unambiguous selected route whose model matches the failed model.
     */
    public boolean recordCurrentRequestRouteFailure(String modelId, String reason) {
        Object rawTimelineId = com.example.lms.search.TraceStore.get(REQUEST_TIMELINE_TRACE_KEY);
        String timelineId = rawTimelineId == null ? "" : String.valueOf(rawTimelineId).trim();
        return recordRequestRouteFailure(timelineId, modelId, reason);
    }

    public boolean recordRequestRouteFailure(String timelineId, String modelId, String reason) {
        RouteDescriptor descriptor;
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = timelineId == null ? null : requestTimelines.get(timelineId);
            if (timeline == null
                    || timeline.routeDescriptor == null
                    || timeline.ambiguousRouteAttempts
                    || timeline.attemptCount != 1) {
                return false;
            }
            descriptor = timeline.routeDescriptor;
        }
        if (!descriptor.key().model().equals(canonicalRouteModel(modelId))) {
            return false;
        }
        recordRouteFailure(descriptor.key(), reason);
        return true;
    }

    /**
     * Controller-owned semantic boundary. One timeline can be evaluated once;
     * missing/ambiguous route identity, model mismatch, unknown policy, and every
     * non-final terminal state fail closed without mutating health.
     */
    public boolean recordSemanticOutcome(
            String timelineId,
            String finalModelId,
            SemanticOutcome outcome) {
        RouteDescriptor descriptor;
        synchronized (requestTimelineLock) {
            RequestTimeline timeline = timelineId == null ? null : requestTimelines.get(timelineId);
            if (timeline == null || timeline.semanticOutcomeRecorded) {
                return false;
            }
            timeline.semanticOutcomeRecorded = true;
            if (timeline.routeDescriptor == null
                    || timeline.ambiguousRouteAttempts
                    || timeline.attemptCount != 1) {
                return false;
            }
            descriptor = timeline.routeDescriptor;
        }
        if (!descriptor.key().model().equals(canonicalRouteModel(finalModelId))
                || !semanticOutcomeAccepted(descriptor.responseModelVerificationRequired(), outcome)) {
            return false;
        }
        recordRouteSuccess(descriptor.key(), descriptor.endpoint());
        return true;
    }

    public boolean isPromotable(RouteHealthKey route) {
        if (route == null) {
            return false;
        }
        RouteSnapshot snapshot = routeSnapshots.get(route);
        return snapshot != null && snapshot.successCount() > 0L && snapshot.lastSuccess();
    }

    public Optional<RouteSnapshot> snapshot(RouteHealthKey route) {
        return route == null ? Optional.empty() : Optional.ofNullable(routeSnapshots.get(route));
    }

    /**
     * Explicit legacy semantic adapter. Active transport callbacks must use
     * {@link #recordAttemptSuccess(String, String, OpenAiEndpointCompatibility.Endpoint)}
     * instead.
     */
    public void recordSuccess(String provider, String modelId, OpenAiEndpointCompatibility.Endpoint endpoint) {
        String model = canonical(modelId);
        String displayModel = displayModel(modelId, model);
        String providerName = canonicalProvider(provider);
        if (model == null || providerName == null) {
            return;
        }
        OpenAiEndpointCompatibility.Endpoint ep = endpoint == null
                ? OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS
                : endpoint;
        if ("local".equals(providerName)) {
            recordLocalSuccessSignal();
        }
        snapshots.compute(new Key(providerName, model), (key, old) -> {
            long successes = old == null ? 1L : old.successCount + 1L;
            long failures = old == null ? 0L : old.failureCount;
            return new Snapshot(displayModel, providerName, ep, successes, failures, "ok", true);
        });
    }

    public static void recordLocalSuccessSignal() {
        LAST_LOCAL_SUCCESS_EPOCH_MS.set(System.currentTimeMillis());
    }

    public static boolean hasRecentLocalSuccess(java.time.Duration maxAge) {
        long last = LAST_LOCAL_SUCCESS_EPOCH_MS.get();
        if (last <= 0L) {
            return false;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - last);
        long maxAgeMs = maxAge == null ? java.time.Duration.ofMinutes(30).toMillis() : Math.max(0L, maxAge.toMillis());
        return ageMs <= maxAgeMs;
    }

    public void recordFailure(String provider, String modelId, OpenAiEndpointCompatibility.Endpoint endpoint, String reason) {
        String model = canonical(modelId);
        String displayModel = displayModel(modelId, model);
        String providerName = canonicalProvider(provider);
        if (model == null || providerName == null) {
            return;
        }
        OpenAiEndpointCompatibility.Endpoint ep = endpoint == null
                ? OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS
                : endpoint;
        String safeReason = sanitizeReason(reason);
        snapshots.compute(new Key(providerName, model), (key, old) -> {
            long successes = old == null ? 0L : old.successCount;
            long failures = old == null ? 1L : old.failureCount + 1L;
            return new Snapshot(displayModel, providerName, ep, successes, failures, safeReason, false);
        });
    }

    /**
     * Open the process-local quarantine for one normalized provider endpoint.
     * The map key and all public evidence retain only an endpoint hash.
     */
    public void recordEndpointDeviceLoss(
            String provider,
            String endpointLabel,
            EndpointQuarantinePolicy policy,
            long observedAtEpochMs) {
        EndpointQuarantinePolicy effectivePolicy = EndpointQuarantinePolicy.effective(policy);
        EndpointKey key = endpointKey(provider, endpointLabel);
        if (!effectivePolicy.enabled() || key == null || observedAtEpochMs <= 0L) {
            return;
        }
        synchronized (endpointQuarantineLock) {
            EndpointQuarantine quarantine = endpointQuarantines.get(key);
            if (quarantine == null) {
                evictEndpointIfNeeded(effectivePolicy.maxEndpoints());
                quarantine = new EndpointQuarantine();
                endpointQuarantines.put(key, quarantine);
            }
            quarantine.open(
                    "gpu_device_lost",
                    observedAtEpochMs,
                    effectivePolicy.hardCooldownMs());
        }
    }

    /**
     * Track runner termination separately from hard device loss. A single
     * termination remains observational unless a fresh hardware-missing signal
     * is supplied or the bounded count is reached inside its window.
     */
    public void recordEndpointRunnerTermination(
            String provider,
            String endpointLabel,
            boolean hardwareMissing,
            EndpointQuarantinePolicy policy,
            long observedAtEpochMs) {
        EndpointQuarantinePolicy effectivePolicy = EndpointQuarantinePolicy.effective(policy);
        EndpointKey key = endpointKey(provider, endpointLabel);
        if (!effectivePolicy.enabled() || key == null || observedAtEpochMs <= 0L) {
            return;
        }
        synchronized (endpointQuarantineLock) {
            EndpointQuarantine quarantine = endpointQuarantines.get(key);
            if (quarantine == null) {
                evictEndpointIfNeeded(effectivePolicy.maxEndpoints());
                quarantine = new EndpointQuarantine();
                endpointQuarantines.put(key, quarantine);
            }
            quarantine.recordRunnerTermination(
                    observedAtEpochMs,
                    effectivePolicy.runnerFailureWindowMs());
            if (hardwareMissing
                    || quarantine.runnerFailureCount >= effectivePolicy.runnerFailureThreshold()) {
                quarantine.open(
                        "gpu_runner_terminated",
                        observedAtEpochMs,
                        effectivePolicy.hardCooldownMs());
            } else if (quarantine.state == EndpointState.CLOSED) {
                quarantine.lastReason = "gpu_runner_terminated_observed";
            }
        }
    }

    /** Consecutive failures are scoped to the actual model on the failed endpoint. */
    public void recordEndpointTransientFailure(String provider, String endpointLabel, String modelId,
            LlmFailureClass failure, EndpointQuarantinePolicy policy, long observedAtEpochMs) {
        EndpointQuarantinePolicy effective = EndpointQuarantinePolicy.effective(policy);
        EndpointKey key = endpointKey(provider, endpointLabel);
        if (!effective.enabled() || key == null || observedAtEpochMs <= 0 || modelId == null
                || !(failure == LlmFailureClass.TIMEOUT_SOFT || failure == LlmFailureClass.HEALTH_DOWN
                || failure == LlmFailureClass.VRAM_OOM || failure == LlmFailureClass.PROVIDER_ERROR)) return;
        synchronized (endpointQuarantineLock) {
            EndpointQuarantine quarantine = endpointQuarantines.get(key);
            if (quarantine == null) {
                evictEndpointIfNeeded(effective.maxEndpoints());
                quarantine = new EndpointQuarantine();
                endpointQuarantines.put(key, quarantine);
            }
            String modelHash = SafeRedactor.hashValue(modelId.trim().toLowerCase(Locale.ROOT));
            if (!quarantine.transientFailures.containsKey(modelHash) && quarantine.transientFailures.size() >= 64)
                quarantine.transientFailures.remove(quarantine.transientFailures.keySet().iterator().next());
            FailureWindow window = quarantine.transientFailures.computeIfAbsent(modelHash, ignored -> new FailureWindow());
            window.record(observedAtEpochMs, effective.runnerFailureWindowMs());
            if (window.count >= effective.transientFailureThreshold() && quarantine.state == EndpointState.CLOSED) {
                quarantine.open("local_" + failure.name().toLowerCase(Locale.ROOT), observedAtEpochMs,
                        effective.transientCooldownMs());
            }
        }
    }

    public void recordEndpointModelSuccess(String provider, String endpointLabel, String modelId) {
        EndpointKey key = endpointKey(provider, endpointLabel);
        if (key == null || modelId == null) return;
        synchronized (endpointQuarantineLock) {
            EndpointQuarantine quarantine = endpointQuarantines.get(key);
            if (quarantine != null) quarantine.transientFailures.remove(
                    SafeRedactor.hashValue(modelId.trim().toLowerCase(Locale.ROOT)));
        }
    }

    /** Cancellation releases only its own permit and is never a GPU failure. */
    public void releaseEndpointAccess(EndpointAccess access) {
        if (access == null || !access.halfOpenPermit()) return;
        synchronized (endpointQuarantineLock) {
            EndpointQuarantine quarantine = endpointQuarantines.get(new EndpointKey(access.provider(), access.endpointHash()));
            if (quarantine != null && quarantine.state == EndpointState.HALF_OPEN
                    && quarantine.generation == access.generation() && quarantine.halfOpenProbeInFlight) {
                quarantine.halfOpenProbeInFlight = false;
                quarantine.generation++;
            }
        }
    }

    /** Return a hash-only snapshot without creating or mutating endpoint state. */
    public Optional<EndpointSnapshot> endpointSnapshot(
            String provider,
            String endpointLabel,
            long observedAtEpochMs) {
        EndpointKey key = endpointKey(provider, endpointLabel);
        if (key == null) {
            return Optional.empty();
        }
        synchronized (endpointQuarantineLock) {
            EndpointQuarantine quarantine = endpointQuarantines.get(key);
            return quarantine == null
                    ? Optional.empty()
                    : Optional.of(quarantine.snapshot(key.endpointHash(), observedAtEpochMs));
        }
    }

    /**
     * Decide whether one GPU-primary generation may reach a local endpoint.
     * In ENFORCE mode an OPEN endpoint is blocked until cooldown expiry, and
     * exactly one caller receives the HALF_OPEN permit. OBSERVE mode reports
     * the same would-block decision without changing route execution.
     */
    public EndpointAccess acquireEndpointAccess(
            String provider,
            String endpointLabel,
            EndpointQuarantinePolicy policy,
            long observedAtEpochMs) {
        EndpointQuarantinePolicy effectivePolicy = EndpointQuarantinePolicy.effective(policy);
        String providerName = canonicalProvider(provider);
        EndpointKey key = endpointKey(provider, endpointLabel);
        String safeProvider = SafeRedactor.traceLabelOrFallback(providerName, "unknown");
        String endpointHash = key == null ? "unknown" : key.endpointHash();
        if (!effectivePolicy.enabled()) {
            return EndpointAccess.allow(
                    safeProvider, endpointHash, EndpointState.CLOSED, "disabled", 0L, 0L, false);
        }
        if (key == null || observedAtEpochMs <= 0L) {
            return new EndpointAccess(
                    !effectivePolicy.enforce(),
                    true,
                    false,
                    EndpointState.CLOSED,
                    safeProvider,
                    endpointHash,
                    "endpoint_invalid",
                    0L,
                    0L);
        }
        synchronized (endpointQuarantineLock) {
            EndpointQuarantine quarantine = endpointQuarantines.get(key);
            if (quarantine == null || quarantine.state == EndpointState.CLOSED) {
                return EndpointAccess.allow(
                        safeProvider,
                        endpointHash,
                        EndpointState.CLOSED,
                        "closed",
                        0L,
                        quarantine == null ? 0L : quarantine.generation,
                        false);
            }
            if (quarantine.halfOpenProbeInFlight && observedAtEpochMs >= quarantine.probeDeadlineEpochMs) {
                quarantine.open("half_open_probe_timeout", observedAtEpochMs, quarantine.cooldownMs);
            }
            long retryAfterMs = Math.max(0L, quarantine.retryAfterEpochMs - observedAtEpochMs);
            if (quarantine.state == EndpointState.OPEN && retryAfterMs > 0L) {
                return new EndpointAccess(
                        !effectivePolicy.enforce(),
                        true,
                        false,
                        EndpointState.OPEN,
                        safeProvider,
                        endpointHash,
                        effectivePolicy.enforce() ? "open_blocked" : "open_observed",
                        retryAfterMs,
                        quarantine.generation);
            }
            if (quarantine.state == EndpointState.OPEN) {
                quarantine.state = EndpointState.HALF_OPEN;
            }
            if (!quarantine.halfOpenProbeInFlight && observedAtEpochMs < quarantine.nextProbeEpochMs) {
                return new EndpointAccess(!effectivePolicy.enforce(), true, false, EndpointState.HALF_OPEN,
                        safeProvider, endpointHash, "recovery_stabilizing",
                        quarantine.nextProbeEpochMs - observedAtEpochMs, quarantine.generation);
            }
            if (!quarantine.halfOpenProbeInFlight) {
                quarantine.halfOpenProbeInFlight = true;
                quarantine.generation++;
                quarantine.probeDeadlineEpochMs = saturatedAdd(observedAtEpochMs, effectivePolicy.probeTimeoutMs());
                return EndpointAccess.allow(
                        safeProvider,
                        endpointHash,
                        EndpointState.HALF_OPEN,
                        "half_open_probe",
                        0L,
                        quarantine.generation,
                        true);
            }
            return new EndpointAccess(
                    !effectivePolicy.enforce(),
                    true,
                    false,
                    EndpointState.HALF_OPEN,
                    safeProvider,
                    endpointHash,
                    effectivePolicy.enforce() ? "half_open_busy" : "half_open_busy_observed",
                    0L,
                    quarantine.generation);
        }
    }

    /**
     * Complete a previously granted HALF_OPEN GPU-primary permit. Calls that
     * do not carry that permit (including same-endpoint CPU fallback success)
     * intentionally cannot mutate endpoint recovery state.
     */
    public void completeEndpointAccess(
            EndpointAccess access,
            boolean gpuPrimarySuccess,
            EndpointQuarantinePolicy policy,
            long observedAtEpochMs) {
        EndpointQuarantinePolicy effectivePolicy = EndpointQuarantinePolicy.effective(policy);
        if (!effectivePolicy.enabled()
                || access == null
                || !access.halfOpenPermit()
                || observedAtEpochMs <= 0L) {
            return;
        }
        String providerName = canonicalProvider(access.provider());
        if (providerName == null
                || access.endpointHash() == null
                || !access.endpointHash().matches("hash:[0-9a-f]{12}")) {
            return;
        }
        EndpointKey key = new EndpointKey(providerName, access.endpointHash());
        synchronized (endpointQuarantineLock) {
            EndpointQuarantine quarantine = endpointQuarantines.get(key);
            if (quarantine == null
                    || quarantine.state != EndpointState.HALF_OPEN
                    || !quarantine.halfOpenProbeInFlight
                    || quarantine.generation != access.generation()) {
                return;
            }
            if (!gpuPrimarySuccess || observedAtEpochMs >= quarantine.probeDeadlineEpochMs) {
                quarantine.open(
                        gpuPrimarySuccess ? "half_open_probe_timeout" : "half_open_probe_failed",
                        observedAtEpochMs,
                        quarantine.cooldownMs);
                return;
            }
            quarantine.halfOpenProbeInFlight = false;
            quarantine.consecutiveGpuPrimarySuccesses++;
            if (quarantine.stableSinceEpochMs == 0L) quarantine.stableSinceEpochMs = observedAtEpochMs;
            if (quarantine.consecutiveGpuPrimarySuccesses >= effectivePolicy.recoverySuccesses()
                    && observedAtEpochMs - quarantine.stableSinceEpochMs >= effectivePolicy.recoveryStableMs()) {
                quarantine.state = EndpointState.CLOSED;
                quarantine.lastReason = "recovered";
                quarantine.retryAfterEpochMs = 0L;
                quarantine.runnerFailureCount = 0;
                quarantine.nextProbeEpochMs = 0;
                REQUEST_PROOF_LOG.info("[local-circuit] state=CLOSED reason=recovered");
            } else {
                quarantine.lastReason = "gpu_primary_recovery_progress";
                quarantine.nextProbeEpochMs = saturatedAdd(observedAtEpochMs, effectivePolicy.healthSampleIntervalMs());
            }
        }
    }

    private void evictEndpointIfNeeded(int maxEndpoints) {
        int capacity = Math.max(1, maxEndpoints);
        while (endpointQuarantines.size() >= capacity) {
            EndpointKey removable = endpointQuarantines.entrySet().stream()
                    .filter(entry -> entry.getValue().state == EndpointState.CLOSED)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(endpointQuarantines.keySet().iterator().next());
            endpointQuarantines.remove(removable);
        }
    }

    private static EndpointKey endpointKey(String provider, String endpointLabel) {
        String providerName = canonicalProvider(provider);
        String endpointHash = endpointIdentityHash(endpointLabel);
        return providerName == null || "unknown".equals(endpointHash)
                ? null
                : new EndpointKey(providerName, endpointHash);
    }

    /**
     * Normalize to scheme, host, and effective port before hashing. Paths,
     * user-info, query parameters, and fragments never enter retained state.
     */
    public static String endpointIdentityHash(String endpointLabel) {
        if (endpointLabel == null || endpointLabel.isBlank()) {
            return "unknown";
        }
        try {
            URI uri = URI.create(endpointLabel.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || host.isBlank()) {
                return "unknown";
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            String normalized = scheme + "|" + host + "|" + port;
            return SafeRedactor.traceLabelOrFallback(SafeRedactor.hashValue(normalized), "unknown");
        } catch (IllegalArgumentException ex) {
            return "unknown";
        }
    }

    private RouteDescriptor requestRouteDescriptor(
            String routeOwner,
            String provider,
            String routeKey,
            String modelId,
            String endpointLabel,
            String protocol,
            boolean responseModelVerificationRequired) {
        String routeKeyHash = SafeRedactor.hashValue(routeKey);
        if (routeKeyHash == null || !routeKeyHash.matches("hash:[0-9a-f]{12}")) {
            return null;
        }
        String context = routeOwner + ":" + protocol + ":" + routeKeyHash;
        RouteHealthKey key = routeHealthKey(provider, endpointLabel, modelId, context);
        if (key == null) {
            return null;
        }
        return new RouteDescriptor(
                key,
                endpointFromProtocol(protocol),
                responseModelVerificationRequired);
    }

    private static boolean semanticOutcomeAccepted(
            boolean responseModelVerificationRequired,
            SemanticOutcome outcome) {
        if (outcome == null
                || !outcome.visibleAnswerPresent()
                || outcome.terminalState() != SemanticTerminalState.COMPLETED
                || outcome.verificationPolicy() == null
                || !outcome.hardGuardAccepted()
                || !outcome.persistenceAccepted()
                || !outcome.deliveryAccepted()) {
            return false;
        }
        if (responseModelVerificationRequired) {
            return outcome.verificationPolicy() == VerificationPolicy.REQUIRED
                    && outcome.verifierAccepted();
        }
        return outcome.verificationPolicy() == VerificationPolicy.NOT_REQUIRED
                || (outcome.verificationPolicy() == VerificationPolicy.REQUIRED
                        && outcome.verifierAccepted());
    }

    private void recordRouteSuccess(
            RouteHealthKey route,
            OpenAiEndpointCompatibility.Endpoint endpoint) {
        if (route == null) {
            return;
        }
        OpenAiEndpointCompatibility.Endpoint safeEndpoint = endpoint == null
                ? OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS
                : endpoint;
        if (isLocalRouteProvider(route.provider())) {
            recordLocalSuccessSignal();
        }
        routeSnapshots.compute(route, (key, old) -> {
            long successes = old == null ? 1L : old.successCount() + 1L;
            long failures = old == null ? 0L : old.failureCount();
            return new RouteSnapshot(
                    key.model(),
                    key.provider(),
                    safeEndpoint,
                    key.endpointHash(),
                    key.context(),
                    successes,
                    failures,
                    "ok",
                    true);
        });
    }

    private static boolean isLocalRouteProvider(String provider) {
        return provider != null
                && (provider.equals("local")
                        || provider.equals("ollama")
                        || provider.startsWith("local_"));
    }

    private static OpenAiEndpointCompatibility.Endpoint endpointFromProtocol(String protocol) {
        return switch (canonicalTimelineValue(protocol)) {
            case "openai_responses" -> OpenAiEndpointCompatibility.Endpoint.RESPONSES;
            case "openai_completions" -> OpenAiEndpointCompatibility.Endpoint.COMPLETIONS;
            default -> OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS;
        };
    }

    private static OpenAiEndpointCompatibility.Endpoint endpointFromContext(String context) {
        if (context != null && context.contains(":openai_responses:")) {
            return OpenAiEndpointCompatibility.Endpoint.RESPONSES;
        }
        if (context != null && context.contains(":openai_completions:")) {
            return OpenAiEndpointCompatibility.Endpoint.COMPLETIONS;
        }
        return OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS;
    }

    public boolean isPromotable(String provider, String modelId) {
        String model = canonical(modelId);
        String providerName = canonicalProvider(provider);
        if (model == null || providerName == null) {
            return false;
        }
        if ("local".equals(providerName)) {
            if (!ModelCapabilities.isLocalChatModelId(model)) {
                return false;
            }
            Snapshot snapshot = snapshots.get(new Key(providerName, model));
            if (snapshot != null && !snapshot.lastSuccess && isBlockingReason(snapshot.lastReason)) {
                return false;
            }
            return isSeedLocalChatModel(model) || (snapshot != null && snapshot.successCount > 0);
        }
        Snapshot snapshot = snapshots.get(new Key(providerName, model));
        return snapshot != null && snapshot.successCount > 0 && snapshot.lastSuccess;
    }

    public Optional<Snapshot> snapshot(String provider, String modelId) {
        String model = canonical(modelId);
        String providerName = canonicalProvider(provider);
        if (model == null || providerName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshots.get(new Key(providerName, model)));
    }

    public Map<String, Object> redactedSnapshot(String provider, String modelId) {
        Optional<Snapshot> snapshot = snapshot(provider, modelId);
        if (snapshot.isEmpty()) {
            return Map.of();
        }
        Snapshot s = snapshot.get();
        return toPublicMap(s);
    }

    public List<Map<String, Object>> redactedSnapshots() {
        List<Snapshot> values = new ArrayList<>(snapshots.values());
        values.sort(Comparator.comparing(Snapshot::provider, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Snapshot::model, String.CASE_INSENSITIVE_ORDER));
        List<Map<String, Object>> out = new ArrayList<>(values.size() + routeSnapshots.size());
        values.stream().map(ModelRuntimeHealthTracker::toPublicMap).forEach(out::add);
        List<RouteSnapshot> routes = new ArrayList<>(routeSnapshots.values());
        routes.sort(Comparator.comparing(RouteSnapshot::provider, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RouteSnapshot::model, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RouteSnapshot::endpointHash)
                .thenComparing(RouteSnapshot::context));
        routes.stream().map(ModelRuntimeHealthTracker::toPublicMap).forEach(out::add);
        return List.copyOf(out);
    }

    private static Map<String, Object> toPublicMap(Snapshot s) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("model", publicLabel(s.model));
        out.put("provider", publicLabel(s.provider));
        out.put("endpoint", s.endpoint.name().toLowerCase(Locale.ROOT));
        out.put("successCount", s.successCount);
        out.put("failureCount", s.failureCount);
        out.put("sampleCount", sampleCount(s));
        out.put("failurePressure", failurePressure(s));
        out.put("routingHint", routingHint(s));
        out.put("lastReason", s.lastReason);
        out.put("lastSuccess", s.lastSuccess);
        return out;
    }

    private static Map<String, Object> toPublicMap(RouteSnapshot s) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("model", publicLabel(s.model()));
        out.put("provider", publicLabel(s.provider()));
        out.put("endpoint", s.endpoint().name().toLowerCase(Locale.ROOT));
        out.put("endpointHash", s.endpointHash());
        out.put("context", s.context());
        out.put("routeScoped", true);
        out.put("successCount", s.successCount());
        out.put("failureCount", s.failureCount());
        out.put("sampleCount", sampleCount(s));
        out.put("failurePressure", failurePressure(s));
        out.put("routingHint", routingHint(s));
        out.put("lastReason", s.lastReason());
        out.put("lastSuccess", s.lastSuccess());
        return out;
    }

    private static long sampleCount(Snapshot s) {
        return Math.max(0L, s.successCount) + Math.max(0L, s.failureCount);
    }

    private static long sampleCount(RouteSnapshot s) {
        return Math.max(0L, s.successCount()) + Math.max(0L, s.failureCount());
    }

    private static double failurePressure(Snapshot s) {
        long samples = sampleCount(s);
        if (samples <= 0L) {
            return 0.0d;
        }
        double pressure = Math.max(0L, s.failureCount) / (double) samples;
        if (!s.lastSuccess && isBlockingReason(s.lastReason)) {
            pressure = Math.max(pressure, 0.75d);
        }
        return Math.round(Math.min(1.0d, pressure) * 10000.0d) / 10000.0d;
    }

    private static double failurePressure(RouteSnapshot s) {
        long samples = sampleCount(s);
        if (samples <= 0L) {
            return 0.0d;
        }
        double pressure = Math.max(0L, s.failureCount()) / (double) samples;
        if (!s.lastSuccess() && isBlockingReason(s.lastReason())) {
            pressure = Math.max(pressure, 0.75d);
        }
        return Math.round(Math.min(1.0d, pressure) * 10000.0d) / 10000.0d;
    }

    private static String routingHint(Snapshot s) {
        double pressure = failurePressure(s);
        if (pressure >= 0.50d || (!s.lastSuccess && isBlockingReason(s.lastReason))) {
            return "llm_route_degrade";
        }
        if (pressure > 0.0d) {
            return "observe";
        }
        return "healthy";
    }

    private static String routingHint(RouteSnapshot s) {
        double pressure = failurePressure(s);
        if (pressure >= 0.50d || (!s.lastSuccess() && isBlockingReason(s.lastReason()))) {
            return "llm_route_degrade";
        }
        if (pressure > 0.0d) {
            return "observe";
        }
        return "healthy";
    }

    private static String publicLabel(String value) {
        return SafeRedactor.traceLabelOrFallback(value, "unknown");
    }

    private static boolean isBlockingReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String r = reason.toLowerCase(Locale.ROOT);
        return r.contains("endpoint")
                || r.contains("mismatch")
                || r.contains("unsupported")
                || r.contains("not_installed")
                || r.contains("model_not_found")
                || r.contains("http_400")
                || r.contains("http_404")
                || r.contains("upstream_5xx")
                || r.contains("blank_response")
                || r.contains("http_5")
                || r.contains("service_unavailable")
                || r.contains("overloaded");
    }

    private static String sanitizeReason(String reason) {
        String known = knownBlockingReasonLabel(reason);
        if (known != null) {
            return known;
        }
        String label = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        String value = label.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            value = "unknown";
        }
        value = value.replaceAll("[^a-z0-9_.:-]", "_");
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static String knownBlockingReasonLabel(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String normalized = reason.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_");
        if (normalized.contains("endpoint") && normalized.contains("mismatch")) {
            return "endpoint_mismatch";
        }
        if (normalized.contains("unsupported")) {
            return "unsupported";
        }
        if (normalized.contains("not_installed")) {
            return "not_installed";
        }
        if (normalized.contains("model_not_found")) {
            return "model_not_found";
        }
        if (normalized.contains("http_400")) {
            return "http_400";
        }
        if (normalized.contains("http_404")) {
            return "http_404";
        }
        if (normalized.contains("blank_response")) {
            return "blank_response";
        }
        return null;
    }

    private static String canonicalProvider(String provider) {
        if (provider == null) {
            return null;
        }
        String p = provider.trim().toLowerCase(Locale.ROOT);
        return p.isBlank() ? null : p;
    }

    private static String canonical(String modelId) {
        String model = ModelCapabilities.canonicalModelName(modelId);
        if (model == null) {
            return null;
        }
        model = model.trim().toLowerCase(Locale.ROOT);
        return model.isBlank() ? null : model;
    }

    private static String displayModel(String modelId, String canonicalFallback) {
        String model = ModelCapabilities.canonicalModelName(modelId);
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        return canonicalFallback;
    }

    private static String canonicalRouteProvider(String provider) {
        String value = canonicalProvider(provider);
        if (value == null) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(value, "unknown").toLowerCase(Locale.ROOT);
    }

    private static String canonicalRouteModel(String modelId) {
        String value = canonical(modelId);
        if (value == null) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(value, "unknown").toLowerCase(Locale.ROOT);
    }

    private static String canonicalRouteContext(String context) {
        if (context == null || context.isBlank()) {
            return null;
        }
        String value = context.trim().toLowerCase(Locale.ROOT);
        if (value.equals("ollama_native") || value.equals("legacy")) {
            return value;
        }
        if (value.matches(
                "(?:router|factory):(?:openai_chat_completions|openai_completions|openai_responses|ollama_native):hash:[0-9a-f]{12}")) {
            return value;
        }
        String hash = SafeRedactor.hashValue(value);
        return hash != null && hash.matches("hash:[0-9a-f]{12}") ? hash : null;
    }

    private static String requestTimelineHash(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw;
        String hash = SafeRedactor.hashValue(value);
        return hash == null || hash.isBlank() ? "hash:unknown" : hash;
    }

    private static String requestTimelineModelHash(String modelId) {
        String hash = SafeRedactor.hashValue(modelId);
        return hash == null || hash.isBlank() ? "unknown" : hash;
    }

    private static String requestTimelineProofHash(String value) {
        if (value == null) {
            return "hash:unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("sha256:[0-9a-f]{64}")) {
            return normalized;
        }
        return normalized.matches("hash:[0-9a-f]{1,64}") ? normalized : "hash:unknown";
    }

    private static boolean isExactSha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static String requestTimelineEndpoint(String endpointLabel) {
        if (endpointLabel == null || endpointLabel.isBlank()) {
            return "unknown";
        }
        String value = endpointLabel.trim();
        try {
            URI uri = value.contains("://") ? URI.create(value) : null;
            if (uri != null && uri.getHost() != null && !uri.getHost().isBlank()) {
                String host = uri.getHost().toLowerCase(Locale.ROOT);
                return uri.getPort() >= 0 ? host + ":" + uri.getPort() : host;
            }
        } catch (IllegalArgumentException ignore) {
            // Fall through to the strict host-label allowlist.
        }
        String label = value.toLowerCase(Locale.ROOT);
        if (label.length() <= 120
                && label.matches("(?:localhost|[a-z0-9-]+(?:\\.[a-z0-9-]+)+)(?::[0-9]{1,5})?")) {
            return label;
        }
        return "unknown";
    }

    private static String requestTimelineTerminal(String terminalClass) {
        String value = canonicalTimelineValue(terminalClass);
        return REQUEST_TIMELINE_TERMINALS.contains(value) ? value : "error";
    }

    private static String canonicalTimelineValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static RequestAttemptFingerprint requestAttemptMessageFingerprint(List<ChatMessage> messages) {
        int itemCount = messages == null ? 0 : messages.size();
        try {
            List<Map<String, String>> canonical = new ArrayList<>();
            if (messages != null) {
                for (ChatMessage message : messages) {
                    LinkedHashMap<String, String> row = new LinkedHashMap<>();
                    row.put("role", requestAttemptMessageRole(message));
                    row.put("content", requestAttemptMessageContent(message));
                    canonical.add(row);
                }
            }
            return requestAttemptFingerprint(REQUEST_ATTEMPT_MESSAGE_MAPPER, canonical, itemCount);
        } catch (RuntimeException ignored) {
            return new RequestAttemptFingerprint("hash:unknown", itemCount, 0);
        }
    }

    private static RequestAttemptFingerprint requestAttemptOptionsFingerprint(Map<String, ?> options) {
        Map<String, ?> safeOptions = options == null ? Map.of() : options;
        return requestAttemptFingerprint(REQUEST_ATTEMPT_CANONICAL_MAPPER, safeOptions, safeOptions.size());
    }

    private static RequestAttemptAppEvidence requestAttemptAppEvidence(
            RequestAttemptFingerprint prompt,
            RequestAttemptFingerprint options) {
        return new RequestAttemptAppEvidence(
                prompt.hash(),
                options.hash(),
                prompt.itemCount(),
                options.itemCount(),
                prompt.utf8ByteCount(),
                options.utf8ByteCount());
    }

    private static RequestAttemptFingerprint requestAttemptFingerprint(
            ObjectMapper mapper,
            Object value,
            int itemCount) {
        try {
            String canonicalJson = mapper.writeValueAsString(value);
            int utf8Length = canonicalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            return new RequestAttemptFingerprint(
                    SafeRedactor.hashValue(canonicalJson),
                    Math.max(0, itemCount),
                    utf8Length);
        } catch (Exception ignored) {
            return new RequestAttemptFingerprint("hash:unknown", Math.max(0, itemCount), 0);
        }
    }

    private static String requestAttemptMessageRole(ChatMessage message) {
        if (message instanceof SystemMessage) {
            return "system";
        }
        if (message instanceof UserMessage) {
            return "user";
        }
        if (message instanceof AiMessage) {
            return "assistant";
        }
        return message == null || message.type() == null
                ? "unknown"
                : canonicalTimelineValue(String.valueOf(message.type()));
    }

    private static String requestAttemptMessageContent(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text() == null ? "" : systemMessage.text();
        }
        if (message instanceof UserMessage userMessage) {
            StringBuilder safe = new StringBuilder();
            boolean firstText = true;
            for (Content content : userMessage.contents()) {
                if (content instanceof TextContent textContent) {
                    if (!firstText) {
                        safe.append('\n');
                    }
                    safe.append(textContent.text() == null ? "" : textContent.text());
                    firstText = false;
                } else if (!(content instanceof ImageContent)) {
                    safe.append("\n[content:OTHER]");
                }
            }
            SafeChatMessageLog.Summary summary = SafeChatMessageLog.summarize(List.of(userMessage));
            if (summary.imagePresent()) {
                safe.append("\n[image:")
                        .append(summary.imageMediaType())
                        .append(':')
                        .append(summary.decodedImageBytes())
                        .append(']');
            }
            return safe.toString();
        }
        if (message instanceof AiMessage aiMessage) {
            return aiMessage.text() == null ? "" : aiMessage.text();
        }
        return message == null ? "" : "[message:" + requestAttemptMessageRole(message) + "]";
    }

    private static final class RequestTimeline {
        private int inferenceAttemptLimit;
        private int inferenceAttemptTotal;
        private final String timelineId;
        private final String requestHash;
        private final String sessionHash;
        private final long startedNanos = System.nanoTime();
        private final LinkedHashMap<String, Map<String, Object>> rows = new LinkedHashMap<>();
        private String latestModelHash = "unknown";
        private String latestEndpointLabel = "unknown";
        private String routeOwner = "unknown";
        private String selectedModelHash = "unknown";
        private String protocol = "unknown";
        private RouteDescriptor routeDescriptor;
        private String finalHash = "hash:unknown";
        private boolean ambiguousRouteAttempts;
        private int attemptCount;
        private long selectedAtEpochMs;
        private long terminalAtEpochMs;
        private String terminalClass = "none";
        private final LinkedHashMap<String, EndpointProbe> probes = new LinkedHashMap<>();
        private final List<RequestAttempt> attempts = new ArrayList<>();
        private final LinkedHashMap<Integer, Integer> attemptOrdinals = new LinkedHashMap<>();
        private int logicalCallTotal;
        private int attemptTotal;
        private int attemptDropped;
        private int clientAttemptTotal;
        private int lifecycleTotal;
        private final java.util.concurrent.atomic.AtomicInteger lifecycleDropped = new java.util.concurrent.atomic.AtomicInteger();
        private long cancellationAtEpochMs;
        private final List<Map<String, Object>> lifecycle = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Map<Integer, Integer> attemptClientSequences = new LinkedHashMap<>();
        private boolean terminal;
        private boolean semanticOutcomeRecorded;

        private RequestTimeline(String timelineId, String requestHash, String sessionHash) {
            this.timelineId = timelineId;
            this.requestHash = requestHash;
            this.sessionHash = sessionHash;
        }

        private void record(String phase, String modelHash, String endpointLabel, String terminalClass) {
            if (terminal) {
                return;
            }
            if ("final_boundary".equals(phase)) {
                if (!rows.containsKey("pending")
                        || rows.containsKey("final_boundary")
                        || "hash:unknown".equals(modelHash)) {
                    return;
                }
                finalHash = modelHash;
                putRow("final_boundary", "none");
                return;
            }
            boolean enrichPending = "pending".equals(phase) && rows.containsKey("pending");
            if (rows.containsKey(phase) && !enrichPending) {
                return;
            }
            if ("pending".equals(phase) && !rows.containsKey("dispatch")) {
                return;
            }
            if ("terminal".equals(phase) && !rows.containsKey("pending")) {
                return;
            }
            if (!"unknown".equals(modelHash)
                    && (!enrichPending || "unknown".equals(latestModelHash))) {
                latestModelHash = modelHash;
            }
            if (!"unknown".equals(endpointLabel)
                    && (!enrichPending || "unknown".equals(latestEndpointLabel))) {
                latestEndpointLabel = endpointLabel;
            }
            if (enrichPending) {
                Map<String, Object> current = rows.get("pending");
                if (current != null
                        && latestModelHash.equals(current.get("modelHash"))
                        && latestEndpointLabel.equals(current.get("endpointLabel"))) {
                    return;
                }
            }
            if ("terminal".equals(phase)) {
                this.terminalClass = terminalClass;
                this.terminalAtEpochMs = System.currentTimeMillis();
            }
            putRow(phase, terminalClass);
            terminal = "terminal".equals(phase);
        }

        private void enrichSelection(
                String routeOwner,
                String selectedModelHash,
                String endpointLabel,
                String protocol,
                RouteDescriptor routeDescriptor,
                boolean ambiguousRouteAttempts) {
            if (terminal || !rows.containsKey("pending") || "unknown".equals(endpointLabel)) {
                return;
            }
            if (!"unknown".equals(this.latestEndpointLabel)
                    && !this.latestEndpointLabel.equals(endpointLabel)) {
                this.ambiguousRouteAttempts = true;
                this.attemptCount = Math.max(2, this.attemptCount + 1);
                putRow("pending", "none");
                return;
            }
            this.routeOwner = routeOwner;
            this.selectedModelHash = selectedModelHash;
            this.latestEndpointLabel = endpointLabel;
            this.protocol = protocol;
            if (routeDescriptor != null) {
                this.routeDescriptor = routeDescriptor;
            }
            this.attemptCount = Math.max(1, this.attemptCount);
            this.ambiguousRouteAttempts |= ambiguousRouteAttempts;
            this.selectedAtEpochMs = System.currentTimeMillis();
            putRow("pending", "none");
        }

        private void recordProbe(EndpointProbe probe) {
            probes.put(probe.endpointLabel(), probe);
            while (probes.size() > 8) {
                probes.remove(probes.keySet().iterator().next());
            }
        }

        private int beginLogicalCall() {
            logicalCallTotal++;
            return logicalCallTotal;
        }

        private void recordAttempt(
                int logicalCallOrdinal,
                String role,
                RequestAttemptRoute route,
                String outcome,
                String failureClass,
                String terminalClass,
                long elapsedMs,
                String promptHash,
                String optionsHash,
                int promptItemCount,
                int optionItemCount,
                String responseHash,
                int responseCharCount,
                int promptUtf8ByteCount,
                int optionsUtf8ByteCount,
                int responseUtf8ByteCount,
                String httpRequestBodyHash,
                int httpRequestBodyUtf8ByteCount,
                String httpResponseBodyHash,
                int httpResponseBodyUtf8ByteCount,
                boolean modelAdapterAttemptObserved,
                boolean clientHttpExchangeObserved,
                boolean clientHttpResponseObserved,
                boolean responseObserved) {
            if (terminal || !rows.containsKey("pending") || logicalCallOrdinal <= 0) {
                return;
            }
            attemptTotal++;
            if (attempts.size() >= REQUEST_ATTEMPT_LEDGER_CAPACITY) {
                attemptDropped++;
                return;
            }
            int attemptOrdinal = attemptOrdinals.getOrDefault(logicalCallOrdinal, 0) + 1;
            attemptOrdinals.put(logicalCallOrdinal, attemptOrdinal);
            attempts.add(new RequestAttempt(
                    attemptTotal,
                    logicalCallOrdinal,
                    attemptOrdinal,
                    role,
                    route,
                    outcome,
                    failureClass,
                    terminalClass,
                    elapsedMs,
                    promptHash,
                    optionsHash,
                    promptItemCount,
                    optionItemCount,
                    responseHash,
                    responseCharCount,
                    promptUtf8ByteCount,
                    optionsUtf8ByteCount,
                    responseUtf8ByteCount,
                    httpRequestBodyHash,
                    httpRequestBodyUtf8ByteCount,
                    httpResponseBodyHash,
                    httpResponseBodyUtf8ByteCount,
                    evidenceBoundary(
                            modelAdapterAttemptObserved,
                            clientHttpExchangeObserved,
                            clientHttpResponseObserved),
                    false,
                    "not_observed",
                    modelAdapterAttemptObserved,
                    clientHttpExchangeObserved,
                    clientHttpResponseObserved,
                    false,
                    false,
                    responseObserved));
        }

        private boolean recordControlledProviderReceipt(
                int logicalCallOrdinal,
                int attemptOrdinal,
                String httpRequestBodyHash,
                int httpRequestBodyUtf8ByteCount,
                String httpResponseBodyHash,
                int httpResponseBodyUtf8ByteCount) {
            return recordProviderReceipt(
                    logicalCallOrdinal,
                    attemptOrdinal,
                    httpRequestBodyHash,
                    httpRequestBodyUtf8ByteCount,
                    httpResponseBodyHash,
                    httpResponseBodyUtf8ByteCount,
                    "controlled_http_server");
        }

        private boolean recordProviderReceipt(
                int logicalCallOrdinal,
                int attemptOrdinal,
                String httpRequestBodyHash,
                int httpRequestBodyUtf8ByteCount,
                String httpResponseBodyHash,
                int httpResponseBodyUtf8ByteCount,
                String receiptSource) {
            if (terminal || attemptDropped != 0 || attemptTotal != attempts.size()) {
                return false;
            }
            int matchingIndex = -1;
            for (int i = 0; i < attempts.size(); i++) {
                RequestAttempt attempt = attempts.get(i);
                if (attempt.logicalCallOrdinal() == logicalCallOrdinal
                        && attempt.attemptOrdinal() == attemptOrdinal
                        && attempt.httpRequestBodyHash().equals(httpRequestBodyHash)
                        && attempt.httpRequestBodyUtf8ByteCount() == httpRequestBodyUtf8ByteCount
                        && attempt.httpResponseBodyHash().equals(httpResponseBodyHash)
                        && attempt.httpResponseBodyUtf8ByteCount() == httpResponseBodyUtf8ByteCount) {
                    if (matchingIndex >= 0) {
                        return false;
                    }
                    matchingIndex = i;
                }
            }
            if (matchingIndex < 0) {
                return false;
            }
            RequestAttempt attempt = attempts.get(matchingIndex);
            if (!"client_http_response".equals(attempt.evidenceBoundary())
                    || attempt.providerReceiptObserved()
                    || attempt.providerAttemptObserved()
                    || attempt.wireAttemptObserved()
                    || !attempt.modelAdapterAttemptObserved()
                    || !attempt.clientHttpExchangeObserved()
                    || !attempt.clientHttpResponseObserved()) {
                return false;
            }
            attempts.set(matchingIndex, attempt.withProviderReceipt(receiptSource));
            return true;
        }

        private static String evidenceBoundary(
                boolean modelAdapterAttemptObserved,
                boolean clientHttpExchangeObserved,
                boolean clientHttpResponseObserved) {
            if (clientHttpResponseObserved) {
                return "client_http_response";
            }
            if (clientHttpExchangeObserved) {
                return "client_http_exchange";
            }
            return modelAdapterAttemptObserved ? "model_adapter" : "not_observed";
        }

        private EndpointRepairReadiness endpointRepairReadiness(long nowEpochMs) {
            if (!"router".equals(routeOwner) || "unknown".equals(selectedModelHash)
                    || "unknown".equals(latestEndpointLabel) || "unknown".equals(protocol)) {
                return EndpointRepairReadiness.hold("selection_missing");
            }
            if (!hasExplicitPort(latestEndpointLabel)) {
                return EndpointRepairReadiness.hold("endpoint_port_missing", latestEndpointLabel,
                        routeOwner, protocol, attemptCount);
            }
            if (ambiguousRouteAttempts || attemptCount != 1) {
                return EndpointRepairReadiness.hold("ambiguous_route_attempts", latestEndpointLabel,
                        routeOwner, protocol, attemptCount);
            }
            if (!terminal) {
                return EndpointRepairReadiness.hold("terminal_missing", latestEndpointLabel,
                        routeOwner, protocol, attemptCount);
            }
            if (!ENDPOINT_SPECIFIC_TERMINALS.contains(terminalClass)) {
                return EndpointRepairReadiness.hold("terminal_not_endpoint_specific", latestEndpointLabel,
                        routeOwner, protocol, attemptCount);
            }
            EndpointProbe selectedProbe = probes.get(latestEndpointLabel);
            if (selectedProbe == null) {
                return EndpointRepairReadiness.hold("probe_missing", latestEndpointLabel,
                        routeOwner, protocol, attemptCount);
            }
            long evidenceNotBeforeEpochMs = Math.max(selectedAtEpochMs, terminalAtEpochMs);
            if (selectedProbe.observedAtEpochMs() < evidenceNotBeforeEpochMs) {
                return EndpointRepairReadiness.hold("probe_predates_terminal", latestEndpointLabel,
                        routeOwner, protocol, attemptCount);
            }
            if (!probeMatches(selectedProbe, nowEpochMs) || selectedProbe.success()) {
                return EndpointRepairReadiness.hold("probe_stale_or_mismatch", latestEndpointLabel,
                        routeOwner, protocol, attemptCount);
            }
            boolean alternateSuccess = probes.values().stream()
                    .anyMatch(probe -> !latestEndpointLabel.equals(probe.endpointLabel())
                            && probe.success()
                            && probeMatches(probe, nowEpochMs));
            if (!alternateSuccess) {
                boolean alternateObserved = probes.values().stream()
                        .anyMatch(probe -> !latestEndpointLabel.equals(probe.endpointLabel()));
                boolean alternatePredatesTerminal = probes.values().stream()
                        .anyMatch(probe -> !latestEndpointLabel.equals(probe.endpointLabel())
                                && probe.success()
                                && selectedModelHash.equals(probe.modelHash())
                                && protocol.equals(probe.protocol())
                                && probe.observedAtEpochMs() < evidenceNotBeforeEpochMs);
                return EndpointRepairReadiness.hold(
                        alternatePredatesTerminal
                                ? "probe_predates_terminal"
                                : alternateObserved ? "probe_stale_or_mismatch" : "probe_missing",
                        latestEndpointLabel,
                        routeOwner,
                        protocol,
                        attemptCount);
            }
            return new EndpointRepairReadiness(
                    "READY", "exact_evidence", latestEndpointLabel, routeOwner, protocol, attemptCount);
        }

        private boolean probeMatches(EndpointProbe probe, long nowEpochMs) {
            long ageMs = nowEpochMs - probe.observedAtEpochMs();
            return ageMs >= 0L
                    && ageMs <= ENDPOINT_PROBE_MAX_AGE_MS
                    && probe.observedAtEpochMs() >= Math.max(selectedAtEpochMs, terminalAtEpochMs)
                    && selectedModelHash.equals(probe.modelHash())
                    && protocol.equals(probe.protocol());
        }

        private static boolean hasExplicitPort(String endpointLabel) {
            int separator = endpointLabel == null ? -1 : endpointLabel.lastIndexOf(':');
            if (separator <= 0 || separator >= endpointLabel.length() - 1) {
                return false;
            }
            try {
                int port = Integer.parseInt(endpointLabel.substring(separator + 1));
                return port > 0 && port <= 65_535;
            } catch (NumberFormatException ignore) {
                return false;
            }
        }

        private void putRow(String phase, String terminalClass) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("timelineId", timelineId);
            row.put("requestHash", requestHash);
            row.put("sessionHash", sessionHash);
            row.put("phase", phase);
            row.put("modelHash", latestModelHash);
            row.put("endpointLabel", latestEndpointLabel);
            row.put("routeOwner", routeOwner);
            row.put("selectedModelHash", selectedModelHash);
            row.put("protocol", protocol);
            row.put("attemptCount", attemptCount);
            row.put("ambiguousRouteAttempts", ambiguousRouteAttempts);
            row.put("elapsedMs", Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
            row.put("terminalClass", "terminal".equals(phase) ? terminalClass : "none");
            if ("final_boundary".equals(phase)) {
                row.put("finalHash", finalHash);
            }
            rows.put(phase, Map.copyOf(row));
        }

        private List<Map<String, Object>> redactedRows() {
            return List.copyOf(rows.values());
        }

        private List<Map<String, Object>> redactedAttemptRows() {
            List<Map<String, Object>> out = new ArrayList<>(attempts.size());
            for (RequestAttempt attempt : attempts) {
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("timelineId", timelineId);
                row.put("requestHash", requestHash);
                row.put("sessionHash", sessionHash);
                row.put("sequence", attempt.sequence());
                row.put("clientAttemptSequence", attemptClientSequences.getOrDefault(attempt.sequence(), 0));
                row.put("logicalCallOrdinal", attempt.logicalCallOrdinal());
                row.put("attemptOrdinal", attempt.attemptOrdinal());
                row.put("role", attempt.role());
                row.put("routeKeyHash", attempt.route().routeKeyHash());
                row.put("modelHash", attempt.route().modelHash());
                row.put("endpointLabel", attempt.route().endpointLabel());
                row.put("protocol", attempt.route().protocol());
                row.put("outcome", attempt.outcome());
                row.put("failureClass", attempt.failureClass());
                row.put("terminalClass", attempt.terminalClass());
                row.put("elapsedMs", attempt.elapsedMs());
                row.put("promptHash", attempt.promptHash());
                row.put("optionsHash", attempt.optionsHash());
                row.put("promptItemCount", attempt.promptItemCount());
                row.put("optionItemCount", attempt.optionItemCount());
                row.put("responseHash", attempt.responseHash());
                row.put("responseCharCount", attempt.responseCharCount());
                row.put("promptUtf8ByteCount", attempt.promptUtf8ByteCount());
                row.put("optionsUtf8ByteCount", attempt.optionsUtf8ByteCount());
                row.put("responseUtf8ByteCount", attempt.responseUtf8ByteCount());
                row.put("httpRequestBodyHash", attempt.httpRequestBodyHash());
                row.put("httpRequestBodyUtf8ByteCount", attempt.httpRequestBodyUtf8ByteCount());
                row.put("httpResponseBodyHash", attempt.httpResponseBodyHash());
                row.put("httpResponseBodyUtf8ByteCount", attempt.httpResponseBodyUtf8ByteCount());
                row.put("evidenceBoundary", attempt.evidenceBoundary());
                row.put("providerReceiptObserved", attempt.providerReceiptObserved());
                row.put("providerReceiptSource", attempt.providerReceiptSource());
                row.put("modelAdapterAttemptObserved", attempt.modelAdapterAttemptObserved());
                row.put("clientHttpExchangeObserved", attempt.clientHttpExchangeObserved());
                row.put("clientHttpResponseObserved", attempt.clientHttpResponseObserved());
                row.put("providerAttemptObserved", attempt.providerAttemptObserved());
                row.put("wireAttemptObserved", attempt.wireAttemptObserved());
                row.put("responseObserved", attempt.responseObserved());
                row.put("attemptTotal", attemptTotal);
                row.put("attemptDropped", attemptDropped);
                out.add(Map.copyOf(row));
            }
            return List.copyOf(out);
        }
    }

    private static final class RequestAttemptThreadCounter {
        private String timelineId;
        private int total;

        private int total(String expectedTimelineId) {
            return expectedTimelineId.equals(timelineId) ? total : 0;
        }

        private void record(String recordedTimelineId) {
            if (!recordedTimelineId.equals(timelineId)) {
                timelineId = recordedTimelineId;
                total = 0;
            }
            total++;
        }
    }

    private static final class RequestAttemptRecordingChatModel implements ChatModel {
        private static final LlmGatewayFailureClassifier FAILURE_CLASSIFIER =
                new LlmGatewayFailureClassifier();
        private final ModelRuntimeHealthTracker tracker;
        private final ChatModel delegate;
        private final String role;
        private final RequestAttemptRoute route;
        private final RequestAttemptFingerprint options;

        private RequestAttemptRecordingChatModel(
                ModelRuntimeHealthTracker tracker,
                ChatModel delegate,
                String role,
                RequestAttemptRoute route,
                RequestAttemptFingerprint options) {
            this.tracker = tracker;
            this.delegate = delegate;
            this.role = role;
            this.route = route;
            this.options = options;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return doChat(request.messages(), request);
        }

        private ChatResponse doChat(List<ChatMessage> messages, ChatRequest request) {
            Object rawTimelineId = com.example.lms.search.TraceStore.get(REQUEST_TIMELINE_TRACE_KEY);
            String timelineId = rawTimelineId == null ? "" : String.valueOf(rawTimelineId).trim();
            if (timelineId.isBlank() || route == null) {
                return invokeDelegate(messages, request);
            }
            RequestAttemptFingerprint prompt = requestAttemptMessageFingerprint(messages);
            RequestAttemptAppEvidence appEvidence = requestAttemptAppEvidence(prompt, options);
            RequestAttemptAppContext previousContext = tracker.installRequestAttemptAppContext(timelineId, appEvidence);
            int attemptTotalBefore = tracker.currentThreadRequestAttemptTotal(timelineId);
            long startedNanos = System.nanoTime();
            try {
                ChatResponse response = invokeDelegate(messages, request);
                if (tracker.currentThreadRequestAttemptTotal(timelineId) == attemptTotalBefore) {
                    String responseText = response == null || response.aiMessage() == null
                            ? null
                            : response.aiMessage().text();
                    boolean responseObserved = responseText != null && !responseText.isBlank();
                    tracker.recordRequestAttemptEvidence(
                            timelineId,
                            role,
                            route,
                            responseText == null || responseText.isBlank() ? "failed" : "success",
                            responseText == null || responseText.isBlank() ? "unknown" : "none",
                            responseText == null || responseText.isBlank() ? "error" : "success",
                            elapsedSince(startedNanos),
                            prompt.hash(),
                            options.hash(),
                            prompt.itemCount(),
                            options.itemCount(),
                            responseObserved ? SafeRedactor.hashValue(responseText) : "hash:unknown",
                            responseObserved ? responseText.length() : 0,
                            prompt.utf8ByteCount(),
                            options.utf8ByteCount(),
                            responseObserved
                                    ? responseText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                                    : 0,
                            true,
                            false,
                            false,
                            false,
                            false,
                            responseObserved);
                }
                return response;
            } catch (RuntimeException ex) {
                tracker.releaseUnstartedLocalInferenceReservation(timelineId, ex);
                if (tracker.currentThreadRequestAttemptTotal(timelineId) == attemptTotalBefore) {
                    LlmFailureClass failureClass = FAILURE_CLASSIFIER.classify(ex);
                    String outcome = failureClass == LlmFailureClass.CANCELLED_NEUTRAL
                            ? "cancelled"
                            : "failed";
                    tracker.recordRequestAttemptEvidence(
                            timelineId,
                            role,
                            route,
                            outcome,
                            failureClass.name().toLowerCase(Locale.ROOT),
                            attemptTerminalClass(failureClass),
                            elapsedSince(startedNanos),
                            prompt.hash(), options.hash(), prompt.itemCount(), options.itemCount(), "hash:unknown", 0,
                            prompt.utf8ByteCount(), options.utf8ByteCount(), 0,
                            true, false, false, false, false, false);
                }
                throw ex;
            } finally {
                tracker.restoreRequestAttemptAppContext(previousContext);
            }
        }

        /** Forward the full request; a legacy list-only delegate keeps its original entry point. */
        private ChatResponse invokeDelegate(List<ChatMessage> messages, ChatRequest request) {
            if (request == null || messages == null || messages.isEmpty()) {
                return delegate.chat(messages);
            }
            try {
                return delegate.chat(request);
            } catch (RuntimeException failure) {
                if (isMissingDoChatContract(failure)) {
                    return delegate.chat(messages);
                }
                throw failure;
            }
        }

        private static boolean isMissingDoChatContract(RuntimeException failure) {
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

        private static long elapsedSince(long startedNanos) {
            return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
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
    }

    private record RequestAttemptFingerprint(String hash, int itemCount, int utf8ByteCount) {
    }

    /** Hash/count-only application proof shared with direct HTTP adapters. */
    public record RequestAttemptAppEvidence(
            String promptHash,
            String optionsHash,
            int promptItemCount,
            int optionItemCount,
            int promptUtf8ByteCount,
            int optionsUtf8ByteCount) {
    }

    private record RequestAttemptAppContext(
            String timelineId,
            int logicalCallOrdinal,
            RequestAttemptAppEvidence evidence,
            java.util.concurrent.atomic.AtomicBoolean firstClientReservation) {
    }

    /** Type-preserving hook used by concrete disabled-model implementations. */
    public static final class ExpectedFailureAttemptEvidence {
        private final ModelRuntimeHealthTracker tracker;
        private final String role;
        private final RequestAttemptRoute route;
        private final RequestAttemptFingerprint options;

        private ExpectedFailureAttemptEvidence(
                ModelRuntimeHealthTracker tracker,
                String role,
                RequestAttemptRoute route,
                RequestAttemptFingerprint options) {
            this.tracker = tracker;
            this.role = role;
            this.route = route;
            this.options = options;
        }

        public void record(List<ChatMessage> messages, String responseText) {
            if (tracker == null || route == null) {
                return;
            }
            Object rawTimelineId = com.example.lms.search.TraceStore.get(REQUEST_TIMELINE_TRACE_KEY);
            String timelineId = rawTimelineId == null ? "" : String.valueOf(rawTimelineId).trim();
            if (timelineId.isBlank()) {
                return;
            }
            RequestAttemptAppEvidence appEvidence = requestAttemptAppEvidence(
                    requestAttemptMessageFingerprint(messages), options);
            tracker.recordRequestAttemptEvidence(
                    timelineId, role, route, "failed", "disabled", "configuration_error", 0L,
                    appEvidence.promptHash(), appEvidence.optionsHash(),
                    appEvidence.promptItemCount(), appEvidence.optionItemCount(),
                    "hash:unknown", 0,
                    appEvidence.promptUtf8ByteCount(), appEvidence.optionsUtf8ByteCount(), 0,
                    false, false, false, false, false, false);
        }
    }

    /** Immutable descriptor containing redacted values only. */
    public static final class RequestAttemptRoute {
        private final String routeKeyHash;
        private final String modelHash;
        private final String endpointLabel;
        private final String protocol;

        private RequestAttemptRoute(
                String routeKeyHash,
                String modelHash,
                String endpointLabel,
                String protocol) {
            this.routeKeyHash = routeKeyHash;
            this.modelHash = modelHash;
            this.endpointLabel = endpointLabel;
            this.protocol = protocol;
        }

        public String routeKeyHash() {
            return routeKeyHash;
        }

        public String modelHash() {
            return modelHash;
        }

        public String endpointLabel() {
            return endpointLabel;
        }

        public String protocol() {
            return protocol;
        }
    }

    private record RequestAttempt(
            int sequence,
            int logicalCallOrdinal,
            int attemptOrdinal,
            String role,
            RequestAttemptRoute route,
            String outcome,
            String failureClass,
            String terminalClass,
            long elapsedMs,
            String promptHash,
            String optionsHash,
            int promptItemCount,
            int optionItemCount,
            String responseHash,
            int responseCharCount,
            int promptUtf8ByteCount,
            int optionsUtf8ByteCount,
            int responseUtf8ByteCount,
            String httpRequestBodyHash,
            int httpRequestBodyUtf8ByteCount,
            String httpResponseBodyHash,
            int httpResponseBodyUtf8ByteCount,
            String evidenceBoundary,
            boolean providerReceiptObserved,
            String providerReceiptSource,
            boolean modelAdapterAttemptObserved,
            boolean clientHttpExchangeObserved,
            boolean clientHttpResponseObserved,
            boolean providerAttemptObserved,
            boolean wireAttemptObserved,
            boolean responseObserved) {
        private RequestAttempt withProviderReceipt(String receiptSource) {
            return new RequestAttempt(
                    sequence,
                    logicalCallOrdinal,
                    attemptOrdinal,
                    role,
                    route,
                    outcome,
                    failureClass,
                    terminalClass,
                    elapsedMs,
                    promptHash,
                    optionsHash,
                    promptItemCount,
                    optionItemCount,
                    responseHash,
                    responseCharCount,
                    promptUtf8ByteCount,
                    optionsUtf8ByteCount,
                    responseUtf8ByteCount,
                    httpRequestBodyHash,
                    httpRequestBodyUtf8ByteCount,
                    httpResponseBodyHash,
                    httpResponseBodyUtf8ByteCount,
                    "provider_receive",
                    true,
                    receiptSource,
                    modelAdapterAttemptObserved,
                    clientHttpExchangeObserved,
                    clientHttpResponseObserved,
                    true,
                    true,
                    responseObserved);
        }
    }

    private record EndpointProbe(
            String endpointLabel,
            String modelHash,
            String protocol,
            boolean success,
            long observedAtEpochMs) {
    }

    public record EndpointRepairReadiness(
            String status,
            String reason,
            String endpointLabel,
            String routeOwner,
            String protocol,
            int attemptCount) {
        private static EndpointRepairReadiness hold(String reason) {
            return hold(reason, "unknown", "unknown", "unknown", 0);
        }

        private static EndpointRepairReadiness hold(
                String reason,
                String endpointLabel,
                String routeOwner,
                String protocol,
                int attemptCount) {
            return new EndpointRepairReadiness(
                    "HOLD", reason, endpointLabel, routeOwner, protocol, attemptCount);
        }
    }

    public enum SemanticTerminalState {
        COMPLETED,
        CANCELLED,
        FAILED,
        DELIVERY_PENDING
    }

    public enum VerificationPolicy {
        REQUIRED,
        NOT_REQUIRED
    }

    /** Count/boolean/enum-only input; raw answer content is never accepted. */
    public record SemanticOutcome(
            boolean visibleAnswerPresent,
            SemanticTerminalState terminalState,
            VerificationPolicy verificationPolicy,
            boolean verifierAccepted,
            boolean hardGuardAccepted,
            boolean persistenceAccepted,
            boolean deliveryAccepted) {
    }

    /** Immutable key whose components are safe labels or non-reversible hashes. */
    public static final class RouteHealthKey {
        private final String provider;
        private final String endpointHash;
        private final String model;
        private final String context;

        private RouteHealthKey(String provider, String endpointHash, String model, String context) {
            this.provider = provider;
            this.endpointHash = endpointHash;
            this.model = model;
            this.context = context;
        }

        public String provider() {
            return provider;
        }

        public String endpointHash() {
            return endpointHash;
        }

        public String model() {
            return model;
        }

        public String context() {
            return context;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RouteHealthKey that)) {
                return false;
            }
            return provider.equals(that.provider)
                    && endpointHash.equals(that.endpointHash)
                    && model.equals(that.model)
                    && context.equals(that.context);
        }

        @Override
        public int hashCode() {
            return Objects.hash(provider, endpointHash, model, context);
        }

        @Override
        public String toString() {
            return "RouteHealthKey[provider=" + provider
                    + ", endpointHash=" + endpointHash
                    + ", model=" + model
                    + ", context=" + context + "]";
        }
    }

    public record RouteSnapshot(
            String model,
            String provider,
            OpenAiEndpointCompatibility.Endpoint endpoint,
            String endpointHash,
            String context,
            long successCount,
            long failureCount,
            String lastReason,
            boolean lastSuccess) {
    }

    private record RouteDescriptor(
            RouteHealthKey key,
            OpenAiEndpointCompatibility.Endpoint endpoint,
            boolean responseModelVerificationRequired) {
    }

    private record Key(String provider, String model) {
    }

    private record EndpointKey(String provider, String endpointHash) {
    }

    public enum EndpointState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public record EndpointQuarantinePolicy(
            boolean enabled,
            boolean enforce,
            long hardCooldownMs,
            long runnerFailureWindowMs,
            int runnerFailureThreshold,
            int recoverySuccesses,
            int maxEndpoints,
            int transientFailureThreshold,
            long transientCooldownMs,
            long recoveryStableMs,
            long probeTimeoutMs,
            long healthSampleIntervalMs) {

        /** Retain the old explicit policy contract for existing integrations. */
        public EndpointQuarantinePolicy(boolean enabled, boolean enforce, long hardCooldownMs,
                long runnerFailureWindowMs, int runnerFailureThreshold, int recoverySuccesses, int maxEndpoints) {
            this(enabled, enforce, hardCooldownMs, runnerFailureWindowMs, runnerFailureThreshold,
                    recoverySuccesses, maxEndpoints, 3, 60_000, 0, 60_000, 0);
        }

        public EndpointQuarantinePolicy {
            hardCooldownMs = Math.max(1L, hardCooldownMs);
            runnerFailureWindowMs = Math.max(1L, runnerFailureWindowMs);
            runnerFailureThreshold = Math.max(1, runnerFailureThreshold);
            recoverySuccesses = Math.max(1, recoverySuccesses);
            maxEndpoints = Math.max(1, Math.min(256, maxEndpoints));
            transientFailureThreshold = Math.max(1, transientFailureThreshold);
            transientCooldownMs = Math.max(1, transientCooldownMs);
            recoveryStableMs = Math.max(0, recoveryStableMs);
            probeTimeoutMs = Math.max(1, probeTimeoutMs);
            healthSampleIntervalMs = Math.max(0, healthSampleIntervalMs);
        }

        public static EndpointQuarantinePolicy disabled() {
            return new EndpointQuarantinePolicy(false, false, 600_000L, 60_000L, 2, 2, 16);
        }

        private static EndpointQuarantinePolicy effective(EndpointQuarantinePolicy policy) {
            return policy == null ? disabled() : policy;
        }
    }

    public record EndpointSnapshot(
            EndpointState state,
            String endpointHash,
            String lastReason,
            long failureCount,
            long openedAtEpochMs,
            long retryAfterEpochMs,
            long retryAfterMs,
            int consecutiveGpuPrimarySuccesses,
            boolean halfOpenProbeInFlight,
            int runnerFailureCount) {
    }

    public record EndpointAccess(
            boolean allowed,
            boolean wouldBlock,
            boolean halfOpenPermit,
            EndpointState state,
            String provider,
            String endpointHash,
            String decision,
            long retryAfterMs,
            long generation) {

        private static EndpointAccess allow(
                String provider,
                String endpointHash,
                EndpointState state,
                String decision,
                long retryAfterMs,
                long generation,
                boolean halfOpenPermit) {
            return new EndpointAccess(
                    true,
                    false,
                    halfOpenPermit,
                    state,
                    provider,
                    endpointHash,
                    decision,
                    Math.max(0L, retryAfterMs),
                    generation);
        }
    }

    private static final class EndpointQuarantine {
        private EndpointState state = EndpointState.CLOSED;
        private String lastReason = "none";
        private long failureCount;
        private long openedAtEpochMs;
        private long retryAfterEpochMs;
        private int consecutiveGpuPrimarySuccesses;
        private boolean halfOpenProbeInFlight;
        private int runnerFailureCount;
        private long runnerWindowStartedAtEpochMs;
        private long generation;
        private long probeDeadlineEpochMs;
        private long stableSinceEpochMs;
        private long nextProbeEpochMs;
        private long cooldownMs;
        private final Map<String, FailureWindow> transientFailures = new LinkedHashMap<>();

        private void open(String reason, long observedAtEpochMs, long cooldownMs) {
            state = EndpointState.OPEN;
            lastReason = reason;
            failureCount++;
            openedAtEpochMs = observedAtEpochMs;
            retryAfterEpochMs = saturatedAdd(observedAtEpochMs, cooldownMs);
            consecutiveGpuPrimarySuccesses = 0;
            halfOpenProbeInFlight = false;
            stableSinceEpochMs = 0;
            nextProbeEpochMs = 0;
            this.cooldownMs = cooldownMs;
            generation++;
            REQUEST_PROOF_LOG.warn("[local-circuit] state=OPEN reason={} cooldownMs={}", reason, cooldownMs);
        }

        private void recordRunnerTermination(long observedAtEpochMs, long windowMs) {
            if (runnerWindowStartedAtEpochMs <= 0L
                    || observedAtEpochMs - runnerWindowStartedAtEpochMs > windowMs
                    || observedAtEpochMs < runnerWindowStartedAtEpochMs) {
                runnerWindowStartedAtEpochMs = observedAtEpochMs;
                runnerFailureCount = 1;
            } else {
                runnerFailureCount++;
            }
        }

        private EndpointSnapshot snapshot(String endpointHash, long observedAtEpochMs) {
            long retryAfterMs = Math.max(0L, retryAfterEpochMs - Math.max(0L, observedAtEpochMs));
            return new EndpointSnapshot(
                    state,
                    endpointHash,
                    lastReason,
                    failureCount,
                    openedAtEpochMs,
                    retryAfterEpochMs,
                    retryAfterMs,
                    consecutiveGpuPrimarySuccesses,
                    halfOpenProbeInFlight && observedAtEpochMs < probeDeadlineEpochMs,
                    runnerFailureCount);
        }
    }

    private static final class FailureWindow {
        private long startedAt;
        private int count;

        private void record(long now, long windowMs) {
            if (startedAt == 0 || now < startedAt || now - startedAt > windowMs) {
                startedAt = now;
                count = 1;
            } else {
                count++;
            }
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public record Snapshot(
            String model,
            String provider,
            OpenAiEndpointCompatibility.Endpoint endpoint,
            long successCount,
            long failureCount,
            String lastReason,
            boolean lastSuccess) {
    }
}
