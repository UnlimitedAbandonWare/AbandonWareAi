package com.example.lms.api;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.plan.PlanHints;
import com.example.lms.search.TraceStore;
import com.example.lms.search.policy.SearchPolicyDecision;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.policy.SearchPolicyMode;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.util.FutureTechDetector;
import dev.langchain4j.rag.content.Content;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Early, count-only admission boundary for the public chat and RAG APIs.
 * This guard deliberately performs no session, attachment, retrieval, or model work.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PublicRequestBudgetGuard extends OncePerRequestFilter {

    private static final Pattern DEEP_PROBE_PREFIX = Pattern.compile(
            "^\\s*DEEP\\s+검색\\s+점검\\s*:\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<String> PUBLIC_JSON_PATHS = Set.of(
            "/api/chat", "/api/chat/sync", "/api/chat/stream", "/api/chat/cancel", "/api/chat/ack",
            "/api/rag/query", "/api/rag/probe", "/v1/tasks/ask", "/v1/tasks/ask/async");
    private static final Set<String> ALLOWED_IMAGE_MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp");
    private static final AtomicInteger BODY_READER_THREAD_SEQUENCE = new AtomicInteger();

    @Value("${public.request-budget.max-json-body-bytes:9437184}")
    private int maxJsonBodyBytes = 9 * 1024 * 1024;
    @Value("${public.request-budget.max-time-budget-ms:120000}")
    private long maxTimeBudgetMs = 120_000L;
    @Value("${addons.budget.default-ms:1500}")
    private long defaultTimeBudgetMs = 1_500L;
    @Value("${public.request-budget.body-read.workers:4}")
    private int bodyReadWorkers = 4;
    @Value("${public.request-budget.body-read.queue-capacity:32}")
    private int bodyReadQueueCapacity = 32;

    private volatile ThreadPoolExecutor bodyReadExecutor;
    @Value("${conversate.enabled:false}")
    private boolean conversateEnabled;

    @Value("${public.request-budget.chat.max-message-chars:32768}")
    private int maxMessageChars = 32_768;
    @Value("${public.request-budget.chat.max-system-prompt-chars:16384}")
    private int maxSystemPromptChars = 16_384;
    @Value("${public.request-budget.chat.max-history-items:64}")
    private int maxHistoryItems = 64;
    @Value("${public.request-budget.chat.max-history-content-chars:16384}")
    private int maxHistoryContentChars = 16_384;
    @Value("${public.request-budget.chat.max-traits:32}")
    private int maxTraits = 32;
    @Value("${public.request-budget.chat.max-providers:8}")
    private int maxProviders = 8;
    @Value("${public.request-budget.chat.max-search-scopes:8}")
    private int maxSearchScopes = 8;
    @Value("${public.request-budget.chat.max-collection-item-chars:256}")
    private int maxCollectionItemChars = 256;
    @Value("${public.request-budget.chat.max-attachment-ids:32}")
    private int maxAttachmentIds = 32;
    @Value("${public.request-budget.chat.max-attachment-id-chars:256}")
    private int maxAttachmentIdChars = 256;
    @Value("${public.request-budget.chat.max-image-base64-chars:8388608}")
    private int maxImageBase64Chars = 8 * 1024 * 1024;
    @Value("${public.request-budget.chat.max-search-queries:8}")
    private int maxSearchQueries = 8;
    @Value("${public.request-budget.chat.max-tokens:32768}")
    private int maxTokens = 32_768;
    @Value("${public.request-budget.chat.max-memory-tokens:32768}")
    private int maxMemoryTokens = 32_768;
    @Value("${public.request-budget.chat.max-rag-tokens:32768}")
    private int maxRagTokens = 32_768;
    @Value("${public.request-budget.chat.max-total-token-budget:65536}")
    private int maxTotalTokenBudget = 65_536;
    @Value("${public.request-budget.chat.max-top-k:100}")
    private int maxTopK = 100;

    @Value("${public.request-budget.rag.max-query-chars:8192}")
    private int maxQueryChars = 8_192;
    @Value("${public.request-budget.rag.max-seed-items:64}")
    private int maxSeedItems = 64;
    @Value("${public.request-budget.rag.max-seed-content-chars:16384}")
    private int maxSeedContentChars = 16_384;
    @Value("${public.request-budget.rag.max-seed-metadata-entries:32}")
    private int maxSeedMetadataEntries = 32;
    @Value("${public.request-budget.rag.max-seed-metadata-item-chars:1024}")
    private int maxSeedMetadataItemChars = 1_024;
    @Value("${public.request-budget.rag.max-noise-domains:64}")
    private int maxNoiseDomains = 64;
    @Value("${public.request-budget.max-retrieval-work:384}")
    private int maxRetrievalWork = 384;
    @Value("${public.request-budget.chat.max-provider-work:4096}")
    private int maxProviderWork = 4_096;
    @Value("${rag.langgraph.mode:OFF}")
    private String ragOrchestratorMode = "OFF";
    @Value("${rag.latest-tech.enabled:true}")
    private boolean latestTechEnabled = true;
    @Value("${rag.latest-tech.auto-disable-vector:true}")
    private boolean latestTechAutoDisableVector = true;

    @Autowired(required = false)
    private PlanHintApplier planHintApplier;

    @Autowired(required = false)
    private SearchPolicyEngine searchPolicyEngine = new SearchPolicyEngine();

    @PostConstruct
    void validateConfiguration() {
        requireConfigured("max-json-body-bytes", maxJsonBodyBytes, 1, 32 * 1024 * 1024);
        requireConfigured("max-time-budget-ms", maxTimeBudgetMs, 1L, 3_600_000L);
        requireConfigured("addons.budget.default-ms", defaultTimeBudgetMs, 1L, 3_600_000L);
        requireConfigured("body-read.workers", bodyReadWorkers, 1, 64);
        requireConfigured("body-read.queue-capacity", bodyReadQueueCapacity, 1, 1_024);
        requireConfigured("chat.max-message-chars", maxMessageChars, 1, 1_000_000);
        requireConfigured("chat.max-system-prompt-chars", maxSystemPromptChars, 1, 1_000_000);
        requireConfigured("chat.max-history-items", maxHistoryItems, 1, 10_000);
        requireConfigured("chat.max-history-content-chars", maxHistoryContentChars, 1, 1_000_000);
        requireConfigured("chat.max-traits", maxTraits, 1, 10_000);
        requireConfigured("chat.max-providers", maxProviders, 1, 1_000);
        requireConfigured("chat.max-search-scopes", maxSearchScopes, 1, 1_000);
        requireConfigured("chat.max-collection-item-chars", maxCollectionItemChars, 1, 65_536);
        requireConfigured("chat.max-attachment-ids", maxAttachmentIds, 1, 10_000);
        requireConfigured("chat.max-attachment-id-chars", maxAttachmentIdChars, 1, 65_536);
        requireConfigured("chat.max-image-base64-chars", maxImageBase64Chars, 1, 24 * 1024 * 1024);
        requireConfigured("chat.max-search-queries", maxSearchQueries, 1, 1_000);
        requireConfigured("chat.max-tokens", maxTokens, 1, 262_144);
        requireConfigured("chat.max-memory-tokens", maxMemoryTokens, 1, 262_144);
        requireConfigured("chat.max-rag-tokens", maxRagTokens, 1, 262_144);
        requireConfigured("chat.max-total-token-budget", maxTotalTokenBudget, 1, 786_432);
        requireConfigured("chat.max-top-k", maxTopK, 1, 10_000);
        requireConfigured("rag.max-query-chars", maxQueryChars, 1, 1_000_000);
        requireConfigured("rag.max-seed-items", maxSeedItems, 1, 10_000);
        requireConfigured("rag.max-seed-content-chars", maxSeedContentChars, 1, 1_000_000);
        requireConfigured("rag.max-seed-metadata-entries", maxSeedMetadataEntries, 1, 10_000);
        requireConfigured("rag.max-seed-metadata-item-chars", maxSeedMetadataItemChars, 1, 1_000_000);
        requireConfigured("rag.max-noise-domains", maxNoiseDomains, 1, 10_000);
        requireConfigured("max-retrieval-work", maxRetrievalWork, 1, 1_000_000);
        requireConfigured("chat.max-provider-work", maxProviderWork, 1, 10_000_000);
        String mode = ragOrchestratorMode == null ? "" : ragOrchestratorMode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("OFF", "SHADOW", "PRIMARY").contains(mode)) {
            throw new IllegalStateException("Invalid public request budget setting: rag.langgraph.mode");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request == null
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !(PUBLIC_JSON_PATHS.contains(publicPath(request)) || isAssist(request));
    }

    private boolean isAssist(HttpServletRequest request) {
        return conversateEnabled && publicPath(request).startsWith("/api/assist/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        int bodyLimit = isAssist(request) ? Math.min(maxJsonBodyBytes, 16384) : maxJsonBodyBytes;
        if (publicPath(request).startsWith("/v1/tasks/")) bodyLimit = Math.min(bodyLimit, 1024 * 1024);
        traceStart("public_api", "raw_body");
        Long budgetMs = validateTimeBudgetHeader(request, response, started);
        if (budgetMs == null) return;
        TimeBudget previous = TimeBudgetContext.get();
        TimeBudget requestBudget = new TimeBudget(budgetMs);
        TimeBudgetContext.set(requestBudget);
        try {
            long declared = request.getContentLengthLong();
            if (declared > bodyLimit) {
                rejectBody(response, declared, started);
                return;
            }
            ServletInputStream input = request.getInputStream();
            Future<byte[]> bodyRead;
            try {
                ThreadPoolExecutor executor = bodyReadExecutor();
                final int boundedLimit = bodyLimit;
                bodyRead = executor.submit(() -> input.readNBytes(boundedLimit + 1));
                TraceStore.put("public.request.budget.bodyReadActive", executor.getActiveCount());
                TraceStore.put("public.request.budget.bodyReadQueueDepth", executor.getQueue().size());
            } catch (RejectedExecutionException saturated) {
                closeQuietly(input);
                rejectBodyExecutorSaturated(response, started);
                return;
            }
            byte[] body;
            try {
                long remainingBeforeReadMs = requestBudget.remainingMillis();
                if (remainingBeforeReadMs <= 0L) {
                    cancelBodyRead(bodyRead, input);
                    rejectDeadline(response, started);
                    return;
                }
                body = bodyRead.get(Math.max(1L, remainingBeforeReadMs), TimeUnit.MILLISECONDS);
            } catch (TimeoutException expired) {
                cancelBodyRead(bodyRead, input);
                rejectDeadline(response, started);
                return;
            } catch (InterruptedException cancelled) {
                cancelBodyRead(bodyRead, input);
                Thread.currentThread().interrupt();
                rejectBodyReadCancelled(response, started);
                return;
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new ServletException("public request body read failed", cause);
            }
            if (body.length > bodyLimit) {
                rejectBody(response, body.length, started);
                return;
            }
            long remainingMs = requestBudget.remainingMillis();
            if (remainingMs <= 0L) {
                rejectDeadline(response, started);
                return;
            }
            TraceStore.put("public.request.budget.bodyBytes", body.length);
            TraceStore.put("public.request.budget.timeBudgetRemainingMs", remainingMs);
            request.setAttribute("chat.admission.bodySha256",
                    org.apache.commons.codec.digest.DigestUtils.sha256Hex(body));
            if (request.getHeader("Idempotency-Key") != null
                    && Set.of("/api/chat", "/api/chat/sync", "/api/chat/stream").contains(publicPath(request))) {
                try { request.setAttribute("chat.admission.semanticSha256", SemanticRequestFingerprint.body(body)); }
                catch (IOException invalid) {
                    response.setStatus(400); response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"invalid_idempotent_request\"}"); return;
                }
            }
            chain.doFilter(new CachedBodyRequest(request, body), response);
        } finally {
            if (previous == null) TimeBudgetContext.clear();
            else TimeBudgetContext.set(previous);
        }
    }

    private ThreadPoolExecutor bodyReadExecutor() {
        ThreadPoolExecutor current = bodyReadExecutor;
        if (current != null) return current;
        synchronized (this) {
            current = bodyReadExecutor;
            if (current == null) {
                current = new ThreadPoolExecutor(
                        bodyReadWorkers,
                        bodyReadWorkers,
                        1L,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(bodyReadQueueCapacity),
                        task -> {
                            Thread thread = new Thread(
                                    null,
                                    task,
                                    "public-body-reader-" + BODY_READER_THREAD_SEQUENCE.incrementAndGet(),
                                    0L,
                                    false);
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
                current.allowCoreThreadTimeOut(true);
                bodyReadExecutor = current;
            }
            return current;
        }
    }

    private void cancelBodyRead(Future<byte[]> bodyRead, ServletInputStream input) {
        bodyRead.cancel(true);
        closeQuietly(input);
        ThreadPoolExecutor executor = bodyReadExecutor;
        if (executor != null) executor.purge();
    }

    private static void closeQuietly(ServletInputStream input) {
        try {
            input.close();
        } catch (IOException closeFailure) {
            TraceStore.put("public.request.budget.bodyCloseFailed", true);
            TraceStore.put("public.request.budget.bodyCloseFailed.count",
                    TraceStore.nextSequence("public.request.budget.bodyCloseFailed"));
            TraceStore.put("public.request.budget.bodyCloseFailed.errorType",
                    closeFailure.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdownBodyReadExecutor() {
        ThreadPoolExecutor executor = bodyReadExecutor;
        if (executor == null) return;
        executor.shutdownNow();
        try {
            executor.awaitTermination(1L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Long validateTimeBudgetHeader(
            HttpServletRequest request,
            HttpServletResponse response,
            long started) throws IOException {
        String header = request.getHeader("X-Budget-Ms");
        if (header == null) {
            long effectiveDefault = Math.min(defaultTimeBudgetMs, maxTimeBudgetMs);
            TraceStore.put("public.request.budget.timeBudgetMs", effectiveDefault);
            return effectiveDefault;
        }
        if (header.isBlank()) {
            rejectHeader(response, "public_time_budget_invalid", started);
            return null;
        }
        long requested;
        try {
            requested = Long.parseLong(header.trim());
        } catch (NumberFormatException invalid) {
            rejectHeader(response, "public_time_budget_invalid", started);
            return null;
        }
        if (requested <= 0L || requested > maxTimeBudgetMs) {
            rejectHeader(response, "public_time_budget_invalid", started);
            return null;
        }
        TraceStore.put("public.request.budget.timeBudgetMs", requested);
        return requested;
    }

    public void validateChat(ChatRequestDto request) {
        validateChat(request, "raw", false);
    }

    public void validateChatForStream(ChatRequestDto request, boolean attach) {
        validateChat(request, "raw", attach);
    }

    public void validateChatEffective(ChatRequestDto request) {
        validateChat(request, "effective", false);
    }

    public void validateChatProjected(ChatRequestDto request,
                                      PlanHints planHints,
                                      boolean effectiveUseWeb,
                                      boolean effectiveUseRag) {
        validateChatProjected(request, planHints, null, effectiveUseWeb, effectiveUseRag);
    }

    public void validateChatProjected(ChatRequestDto request,
                                      PlanHints planHints,
                                      String selectedPlanId,
                                      boolean effectiveUseWeb,
                                      boolean effectiveUseRag) {
        PlanHints resolvedPlan = resolveChatPlan(planHints, selectedPlanId);
        validateChat(request, "projected", false,
                new ChatBudgetProjection(resolvedPlan, effectiveUseWeb, effectiveUseRag));
    }

    private void validateChat(ChatRequestDto request, String phase, boolean allowBlankMessage) {
        validateChat(request, phase, allowBlankMessage, null);
    }

    private void validateChat(ChatRequestDto request,
                              String phase,
                              boolean allowBlankMessage,
                              ChatBudgetProjection projection) {
        long started = System.nanoTime();
        traceStart("chat", phase);
        if (request == null) {
            reject(HttpStatus.BAD_REQUEST, "chat_request_required", started);
        }
        traceChatShape(request);
        if (!allowBlankMessage && (request.getMessage() == null || request.getMessage().isBlank())) {
            reject(HttpStatus.BAD_REQUEST, "chat_message_required", started);
        }
        requireLength(request.getMessage(), maxMessageChars, "chat_message_too_large", started);
        requireLength(request.getSystemPrompt(), maxSystemPromptChars, "chat_system_prompt_too_large", started);
        requireLength(request.getModel(), maxCollectionItemChars, "chat_model_too_large", started);
        requireLength(request.getMode(), maxCollectionItemChars, "chat_mode_too_large", started);
        requireLength(request.getMemoryMode(), maxCollectionItemChars, "chat_memory_mode_too_large", started);
        requireLength(request.getInputType(), maxCollectionItemChars, "chat_input_type_too_large", started);
        requireLength(request.getDomainProfile(), maxCollectionItemChars, "chat_domain_profile_too_large", started);
        requireLength(request.getProfile(), maxCollectionItemChars, "chat_profile_too_large", started);
        requireLength(request.getGuardLevel(), maxCollectionItemChars, "chat_guard_level_too_large", started);
        requireCollection(request.getTraits(), maxTraits, maxCollectionItemChars,
                "chat_traits_count_exceeded", "chat_trait_too_large", started);
        requireCollection(request.getWebProviders(), maxProviders, maxCollectionItemChars,
                "chat_provider_count_exceeded", "chat_provider_too_large", started);
        requireCollection(request.getSearchScopes(), maxSearchScopes, maxCollectionItemChars,
                "chat_search_scope_count_exceeded", "chat_search_scope_too_large", started);
        requireCollection(request.getRoleScope(), maxSearchScopes, maxCollectionItemChars,
                "chat_role_scope_count_exceeded", "chat_role_scope_too_large", started);
        requireCollection(request.getAttachmentIds(), maxAttachmentIds, maxAttachmentIdChars,
                "chat_attachment_count_exceeded", "chat_attachment_id_too_large", started);
        requireLength(request.getImageBase64(), maxImageBase64Chars, "chat_image_too_large", started);
        requireLength(request.getImageMediaType(), maxCollectionItemChars,
                "chat_image_media_type_unsupported", started);
        validateImagePayload(request, maxImageBase64Chars, started);

        List<ChatRequestDto.Message> history = request.getHistory();
        if (size(history) > maxHistoryItems) {
            reject(HttpStatus.PAYLOAD_TOO_LARGE, "chat_history_count_exceeded", started);
        }
        if (history != null) {
            for (ChatRequestDto.Message item : history) {
                if (item == null) continue;
                requireLength(item.getRole(), 32, "chat_history_role_too_large", started);
                requireLength(item.getContent(), maxHistoryContentChars,
                        "chat_history_content_too_large", started);
            }
        }

        finiteRange(request.getTemperature(), 0.0d, 2.0d, "chat_temperature_invalid", started);
        finiteRange(request.getTopP(), 0.0d, 1.0d, "chat_top_p_invalid", started);
        finiteRange(request.getFrequencyPenalty(), -2.0d, 2.0d,
                "chat_frequency_penalty_invalid", started);
        finiteRange(request.getPresencePenalty(), -2.0d, 2.0d,
                "chat_presence_penalty_invalid", started);

        int requestedMaxTokens = positiveBounded(request.getMaxTokens(), maxTokens,
                "chat_max_tokens_invalid", started);
        int requestedMemoryTokens = positiveBounded(request.getMaxMemoryTokens(), maxMemoryTokens,
                "chat_max_memory_tokens_invalid", started);
        int requestedRagTokens = positiveBounded(request.getMaxRagTokens(), maxRagTokens,
                "chat_max_rag_tokens_invalid", started);
        long totalTokens = safeAdd(requestedMaxTokens, requestedMemoryTokens, requestedRagTokens);
        if (totalTokens > maxTotalTokenBudget) {
            reject(HttpStatus.TOO_MANY_REQUESTS, "chat_model_budget_exceeded", started);
        }

        PlanHints plan = projection == null ? null : projection.planHints();
        boolean useRag = projection == null
                ? Boolean.TRUE.equals(request.getUseRag())
                : projection.effectiveUseRag();
        boolean useWeb = projection == null
                ? Boolean.TRUE.equals(request.getUseWebSearch())
                : projection.effectiveUseWeb();
        if (plan != null && Boolean.FALSE.equals(plan.allowRag())) useRag = false;
        if (plan != null && Boolean.FALSE.equals(plan.allowWeb())) useWeb = false;
        if (projection != null
                && latestTechEnabled
                && latestTechAutoDisableVector
                && FutureTechDetector.isFutureTechQuery(request.getMessage())) {
            useWeb = true;
            useRag = false;
        }
        boolean precisionSearch = Boolean.TRUE.equals(request.getPrecisionSearch());
        boolean webAxisActive = useRag
                || useWeb
                || Boolean.TRUE.equals(request.getPrecisionSearch());
        int requestedWebTopK = webAxisActive
                ? positiveBounded(request.getWebTopK(), maxTopK, "chat_web_top_k_invalid", started)
                : nonNegativeBounded(request.getWebTopK(), maxTopK, "chat_web_top_k_invalid", started);
        int precisionTopK = precisionSearch
                ? nullablePositiveBounded(request.getPrecisionTopK(), maxTopK,
                        "chat_precision_top_k_invalid", started)
                : nonNegativeBounded(request.getPrecisionTopK(), maxTopK,
                        "chat_precision_top_k_invalid", started);
        int searchQueries = nonNegativeBounded(request.getSearchQueries(), maxSearchQueries,
                "chat_search_queries_invalid", started);

        int webTopK = requestedWebTopK;
        int ragTopK = requestedWebTopK;
        if (plan != null) {
            if (useWeb) webTopK = Math.max(webTopK,
                    projectedChatPlanTopK(plan.webTopK(), "chat_plan_web_top_k_invalid", started));
            if (useRag) ragTopK = Math.max(ragTopK,
                    projectedChatPlanTopK(plan.vecTopK(), "chat_plan_vector_top_k_invalid", started));
        }
        SearchPolicyDecision searchPolicy = projection == null || !webAxisActive
                ? null
                : projectSearchPolicy(request);
        if (searchPolicy != null) {
            if (useWeb) webTopK = Math.max(webTopK,
                    searchPolicyEngine.tuneTopK(webTopK, searchPolicy));
            if (useRag) ragTopK = Math.max(ragTopK,
                    searchPolicyEngine.tuneVecTopK(ragTopK, searchPolicy));
        }

        long retrievalWork = 0L;
        long branchCount = 0L;
        if (useRag) {
            retrievalWork = safeAdd(retrievalWork, ragTopK);
            branchCount = safeAdd(branchCount, 1L);
        }
        boolean liveWebSearch = useWeb
                && request.getSearchMode() != SearchMode.OFF;
        if (liveWebSearch) {
            retrievalWork = safeAdd(retrievalWork, webTopK);
            branchCount = safeAdd(branchCount, 1L);
        }
        if (precisionSearch) {
            retrievalWork = safeAdd(retrievalWork, precisionTopK > 0 ? precisionTopK : webTopK);
            branchCount = safeAdd(branchCount, 1L);
        }
        long queryMultiplier = projection == null
                ? Math.max(1, searchQueries + 1L)
                : projectedChatQueryCount(request, plan, searchPolicy, webAxisActive, searchQueries);
        retrievalWork = safeMultiply(retrievalWork, queryMultiplier);
        branchCount = safeMultiply(branchCount, queryMultiplier);
        long modeMultiplier = 1L;
        if (isDeepMode(request)) modeMultiplier = safeMultiply(modeMultiplier, 2L);
        if (Boolean.TRUE.equals(request.getAccumulation())) modeMultiplier = safeMultiply(modeMultiplier, 2L);
        retrievalWork = safeMultiply(retrievalWork, modeMultiplier);
        branchCount = safeMultiply(branchCount, modeMultiplier);

        int callsPerPhase = liveWebSearch ? ChatApiController.maxWebSearchCallsPerPhase(request.getMessage()) : 0;
        long providerWork;
        if (projection == null) {
            long providerBranches = safeMultiply(2L, 2L, callsPerPhase);
            providerWork = safeMultiply(providerBranches, webTopK, queryMultiplier, modeMultiplier);
        } else {
            int hybridAttemptMultiplier = containsHangul(request.getMessage()) ? 12 : 6;
            providerWork = safeMultiply(callsPerPhase, hybridAttemptMultiplier,
                    webTopK, queryMultiplier, modeMultiplier);
        }
        traceBudget(totalTokens, retrievalWork, providerWork, branchCount,
                aggregateChatItems(request), requestedWebTopK,
                Math.max(Math.max(webTopK, ragTopK), precisionTopK), liveWebSearch ? 2 : 0);
        if (retrievalWork > maxRetrievalWork) {
            reject(HttpStatus.TOO_MANY_REQUESTS, "chat_retrieval_budget_exceeded", started);
        }
        if (providerWork > maxProviderWork) {
            reject(HttpStatus.TOO_MANY_REQUESTS, "chat_provider_budget_exceeded", started);
        }
        accept(started);
    }

    public String requireRagProbeQuery(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String query) {
            return query;
        }
        long started = System.nanoTime();
        traceStart("rag", "raw");
        reject(HttpStatus.BAD_REQUEST, "rag_body_invalid", started);
        return "";
    }

    private PlanHints resolveChatPlan(PlanHints planHints, String selectedPlanId) {
        if (planHints != null || selectedPlanId == null || selectedPlanId.isBlank()) {
            return planHints;
        }
        long started = System.nanoTime();
        traceStart("chat", "projected");
        if (planHintApplier == null) {
            return null;
        }
        try {
            PlanHints loaded = planHintApplier.load(selectedPlanId);
            if (loaded == null) {
                reject(HttpStatus.SERVICE_UNAVAILABLE, "chat_plan_budget_unavailable", started);
            }
            return loaded;
        } catch (Rejection rejection) {
            throw rejection;
        } catch (RuntimeException planFailure) {
            reject(HttpStatus.SERVICE_UNAVAILABLE, "chat_plan_budget_unavailable", started);
            return null;
        }
    }

    private int projectedChatPlanTopK(Integer value, String reason, long started) {
        if (value == null || value <= 0) return 0;
        return positivePrimitiveBounded(value, maxTopK, reason, started);
    }

    private SearchPolicyDecision projectSearchPolicy(ChatRequestDto request) {
        SearchPolicyEngine engine = searchPolicyEngine == null ? new SearchPolicyEngine() : searchPolicyEngine;
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            if (request.getSearchMode() != null) {
                meta.put("searchMode", request.getSearchMode().name());
            }
            return engine.decide(request.getMessage(), meta);
        } catch (RuntimeException policyFailure) {
            TraceStore.put("public.request.budget.searchPolicyFallback", true);
            TraceStore.put("public.request.budget.searchPolicyFallback.errorType",
                    policyFailure.getClass().getSimpleName());
            return null;
        }
    }

    private long projectedChatQueryCount(ChatRequestDto request,
                                         PlanHints plan,
                                         SearchPolicyDecision policy,
                                         boolean retrievalActive,
                                         int searchQueries) {
        int clientQueries = Math.max(1, searchQueries + 1);
        if (!retrievalActive) return clientQueries;

        int planQueries = 2;
        if (plan != null && plan.queryBurstCount() != null && plan.queryBurstCount() > 0) {
            planQueries = Math.max(2, Math.min(32, plan.queryBurstCount()));
        }
        int workflowQueries;
        if (request.getSearchMode() == SearchMode.FORCE_LIGHT) {
            workflowQueries = 1;
        } else if (policy == null) {
            workflowQueries = 32;
        } else if (policy.mode() == SearchPolicyMode.OFF) {
            SearchPolicyEngine engine = searchPolicyEngine == null ? new SearchPolicyEngine() : searchPolicyEngine;
            workflowQueries = engine.tunePlannerMaxQueries(planQueries, policy);
        } else {
            workflowQueries = policy.maxFinalQueries();
        }
        if (request.getSearchMode() == SearchMode.FORCE_LIGHT) {
            workflowQueries = 1;
        } else {
            workflowQueries = Math.max(planQueries, workflowQueries);
        }
        workflowQueries = Math.max(1, Math.min(32, workflowQueries));
        long projected = Math.max(clientQueries, workflowQueries);
        if (plan != null && Boolean.TRUE.equals(plan.extremeZEnabled())) {
            int extremeQueries = plan.queryBurstCount() != null && plan.queryBurstCount() > 0
                    ? Math.max(1, Math.min(32, plan.queryBurstCount()))
                    : 12;
            projected = safeAdd(projected, extremeQueries);
        }
        return projected;
    }

    private static boolean containsHangul(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= '\uAC00' && ch <= '\uD7A3')
                    || (ch >= '\u1100' && ch <= '\u11FF')
                    || (ch >= '\u3130' && ch <= '\u318F')) {
                return true;
            }
        }
        return false;
    }

    private record ChatBudgetProjection(
            PlanHints planHints,
            boolean effectiveUseWeb,
            boolean effectiveUseRag) {
    }

    public void validateRag(QueryRequest request) {
        long started = System.nanoTime();
        traceStart("rag", "raw");
        if (request == null || request.query == null || request.query.isBlank()) {
            reject(HttpStatus.BAD_REQUEST, "rag_query_required", started);
        }
        requireLength(request.query, maxQueryChars, "rag_query_too_large", started);
        finiteRange(request.diversityLambda, 0.0d, 1.0d, "rag_diversity_lambda_invalid", started);
        boolean retrievalEnabled = !request.seedOnly
                && (request.useWeb || request.useVector || request.useKg || request.useBm25);
        boolean fallbackTopKRequired = retrievalEnabled
                || (request.seedWeb != null && !request.seedWeb.isEmpty())
                || (request.seedVector != null && !request.seedVector.isEmpty());
        int topK = fallbackTopKRequired
                ? positivePrimitiveBounded(request.topK, maxTopK, "rag_top_k_invalid", started)
                : nonNegativePrimitiveBounded(request.topK, maxTopK, "rag_top_k_invalid", started);
        axisTopK(request.webTopK, topK,
                !request.seedOnly && request.useWeb, "rag_web_top_k_invalid", started);
        axisTopK(request.vectorTopK, topK,
                !request.seedOnly && request.useVector, "rag_vector_top_k_invalid", started);
        axisTopK(request.kgTopK, topK,
                !request.seedOnly && request.useKg, "rag_kg_top_k_invalid", started);

        long seedCount = safeAdd(size(request.seedWeb), size(request.seedVector), size(request.seedCandidates));
        if (seedCount > maxSeedItems) {
            reject(HttpStatus.PAYLOAD_TOO_LARGE, "rag_seed_count_exceeded", started);
        }
        validateSeedCandidates(request.seedCandidates, started);
        validateSeedContents(request.seedWeb, started);
        validateSeedContents(request.seedVector, started);
        requireCollection(request.noiseDomains, maxNoiseDomains, maxCollectionItemChars,
                "rag_noise_domain_count_exceeded", "rag_noise_domain_too_large", started);
        requireLength(request.expectedDomain, maxCollectionItemChars, "rag_expected_domain_too_large", started);
        requireLength(request.planId, maxCollectionItemChars, "rag_plan_id_too_large", started);
        requireLength(request.threadId, maxCollectionItemChars, "rag_thread_id_too_large", started);
        requireLength(request.jamminiMode, maxCollectionItemChars, "rag_mode_too_large", started);
        requireLength(request.memoryProfile, maxCollectionItemChars, "rag_memory_profile_too_large", started);
        requireLength(request.seedMode, maxCollectionItemChars, "rag_seed_mode_too_large", started);

        RagBudgetProjection projection = projectRagBudget(request, topK, started);

        long retrievalWork = request.seedOnly ? seedCount : 0L;
        if (!request.seedOnly) {
            if (projection.useWeb()) retrievalWork = safeAdd(retrievalWork, projection.webTopK());
            if (projection.useVector()) {
                int fallbackVectorTopK = projection.useWeb() && request.vectorTopK == null
                        ? Math.max(projection.vectorTopK() * 2, 10)
                        : projection.vectorTopK();
                retrievalWork = safeAdd(
                        retrievalWork,
                        fallbackVectorTopK,
                        projection.vectorTopK());
            }
            if (projection.useKg()) retrievalWork = safeAdd(retrievalWork, projection.kgTopK());
            if (projection.useBm25()) retrievalWork = safeAdd(retrievalWork, projection.topK());
            if (projection.enableSelfAsk()) retrievalWork = safeMultiply(retrievalWork, 2L);
            if (request.deepResearch) retrievalWork = safeMultiply(retrievalWork, 2L);
            if (request.aggressive || isAggressiveMode(request.jamminiMode)) {
                retrievalWork = safeMultiply(retrievalWork, 2L);
            }
            if ("SHADOW".equalsIgnoreCase(ragOrchestratorMode)
                    || "PRIMARY".equalsIgnoreCase(ragOrchestratorMode)) {
                retrievalWork = safeMultiply(retrievalWork, 2L);
            }
        }
        long branchCount = request.seedOnly ? seedCount : projectedBranchCount(projection, request);
        if (!request.seedOnly && ("SHADOW".equalsIgnoreCase(ragOrchestratorMode)
                || "PRIMARY".equalsIgnoreCase(ragOrchestratorMode))) {
            branchCount = safeMultiply(branchCount, 2L);
        }
        int effectiveTopK = Math.max(projection.topK(),
                Math.max(projection.webTopK(), Math.max(projection.vectorTopK(), projection.kgTopK())));
        traceBudget(0L, retrievalWork, 0L, branchCount,
                seedCount + size(request.noiseDomains), request.topK, effectiveTopK, 0);
        if (retrievalWork > maxRetrievalWork) {
            reject(HttpStatus.TOO_MANY_REQUESTS, "rag_retrieval_budget_exceeded", started);
        }
        accept(started);
    }

    private static long projectedBranchCount(RagBudgetProjection projection, QueryRequest request) {
        long branches = 0L;
        if (projection.useWeb()) branches++;
        if (projection.useVector()) branches++;
        if (projection.useKg()) branches++;
        if (projection.useBm25()) branches++;
        if (projection.enableSelfAsk()) branches = safeMultiply(branches, 2L);
        if (request.deepResearch) branches = safeMultiply(branches, 2L);
        if (request.aggressive || isAggressiveMode(request.jamminiMode)) {
            branches = safeMultiply(branches, 2L);
        }
        return branches;
    }

    private RagBudgetProjection projectRagBudget(QueryRequest request, int topK, long started) {
        RagGraphBudgetProjection graph = projectRagGraphBudget(request, topK);
        topK = graph.topK();
        boolean useWeb = request.useWeb;
        boolean useVector = request.useVector;
        boolean useKg = request.useKg;
        boolean enableSelfAsk = graph.enableSelfAsk();
        Integer requestedWebTopK = graph.webTopK();
        Integer requestedVectorTopK = graph.vectorTopK();
        Integer requestedKgTopK = graph.kgTopK();

        if (!request.seedOnly && planHintApplier != null) {
            final PlanHints plan;
            try {
                plan = planHintApplier.load(request.planId);
            } catch (RuntimeException planFailure) {
                reject(HttpStatus.SERVICE_UNAVAILABLE, "rag_plan_budget_unavailable", started);
                return null;
            }
            if (plan != null) {
                OrchestrationHints hints = OrchestrationHints.defaults();
                hints.setAllowWeb(useWeb);
                hints.setAllowRag(useVector || useKg);
                hints.setEnableSelfAsk(enableSelfAsk);
                try {
                    planHintApplier.applyToHintsAndMeta(plan, hints, new LinkedHashMap<>());
                } catch (RuntimeException planFailure) {
                    reject(HttpStatus.SERVICE_UNAVAILABLE, "rag_plan_budget_unavailable", started);
                }
                if (Boolean.FALSE.equals(plan.allowWeb())) useWeb = false;
                if (Boolean.FALSE.equals(plan.allowRag())) {
                    useVector = false;
                    useKg = false;
                }
                if (requestedWebTopK == null && positive(plan.webTopK())) {
                    requestedWebTopK = plan.webTopK();
                } else if (requestedWebTopK == null
                        && plan.kSchedule() != null && !plan.kSchedule().isEmpty()
                        && positive(plan.kSchedule().get(0))) {
                    requestedWebTopK = plan.kSchedule().get(0);
                }
                if (requestedVectorTopK == null && positive(plan.vecTopK())) {
                    requestedVectorTopK = plan.vecTopK();
                }
                if (requestedKgTopK == null && positive(plan.kgTopK())) {
                    requestedKgTopK = plan.kgTopK();
                    useKg = true;
                }
                enableSelfAsk = hints.isEnableSelfAsk();
            }
        }

        int webTopK = projectedAxisTopK(requestedWebTopK, topK,
                "rag_web_top_k_invalid", started);
        int vectorTopK = projectedAxisTopK(requestedVectorTopK, topK,
                "rag_vector_top_k_invalid", started);
        int kgTopK = requestedKgTopK != null && requestedKgTopK > 0
                ? positivePrimitiveBounded(requestedKgTopK, maxTopK,
                        "rag_kg_top_k_invalid", started)
                : Math.min(50, Math.max(12, Math.max(1, topK) * 2));
        return new RagBudgetProjection(
                useWeb, useVector, useKg, request.useBm25, enableSelfAsk,
                topK, webTopK, vectorTopK, kgTopK);
    }

    private RagGraphBudgetProjection projectRagGraphBudget(QueryRequest request, int topK) {
        Integer webTopK = request.webTopK;
        Integer vectorTopK = request.vectorTopK;
        Integer kgTopK = request.kgTopK;
        boolean selfAsk = request.enableSelfAsk;
        if (!"SHADOW".equalsIgnoreCase(ragOrchestratorMode)
                && !"PRIMARY".equalsIgnoreCase(ragOrchestratorMode)) {
            return new RagGraphBudgetProjection(topK, webTopK, vectorTopK, kgTopK, selfAsk);
        }

        String signals = String.join(" ",
                String.valueOf(request.jamminiMode),
                String.valueOf(request.memoryProfile),
                String.valueOf(request.planId)).toLowerCase(java.util.Locale.ROOT);
        boolean strike = signals.contains("strike") || signals.contains("bypass");
        boolean strict = !strike && (signals.contains("strict") || request.whitelistOnly);
        boolean relaxed = !strike && !strict && (signals.contains("relaxed")
                || signals.contains("brave")
                || signals.contains("free")
                || signals.contains("wild")
                || request.aggressive
                || request.deepResearch);
        if (strike) {
            selfAsk = false;
            topK = Math.max(4, Math.min(8, topK));
        } else if (strict) {
            selfAsk = false;
            topK = Math.max(4, topK);
        } else if (relaxed) {
            selfAsk = true;
            topK = Math.max(10, topK);
            if (webTopK == null) webTopK = Math.max(10, topK);
            if (vectorTopK == null) vectorTopK = Math.max(6, topK / 2);
            if (kgTopK == null) kgTopK = Math.max(3, topK / 3);
        }
        return new RagGraphBudgetProjection(topK, webTopK, vectorTopK, kgTopK, selfAsk);
    }

    private int projectedAxisTopK(Integer requested, int fallback, String reason, long started) {
        return requested != null && requested > 0
                ? positivePrimitiveBounded(requested, maxTopK, reason, started)
                : Math.max(1, fallback);
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private record RagBudgetProjection(
            boolean useWeb,
            boolean useVector,
            boolean useKg,
            boolean useBm25,
            boolean enableSelfAsk,
            int topK,
            int webTopK,
            int vectorTopK,
            int kgTopK) {
    }

    private record RagGraphBudgetProjection(
            int topK,
            Integer webTopK,
            Integer vectorTopK,
            Integer kgTopK,
            boolean enableSelfAsk) {
    }

    private static boolean isAggressiveMode(String mode) {
        if (mode == null) return false;
        return switch (mode.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "s2", "brave", "free", "zero_break" -> true;
            default -> false;
        };
    }

    private int axisTopK(Integer requested, int fallback, boolean active, String reason, long started) {
        if (requested == null) return fallback;
        return active
                ? positivePrimitiveBounded(requested, maxTopK, reason, started)
                : nonNegativePrimitiveBounded(requested, maxTopK, reason, started);
    }

    private static long aggregateChatItems(ChatRequestDto request) {
        return safeAdd(size(request.getHistory()), size(request.getTraits()), size(request.getWebProviders()),
                size(request.getSearchScopes()), size(request.getRoleScope()), size(request.getAttachmentIds()));
    }

    private static void traceChatShape(ChatRequestDto request) {
        TraceStore.put("public.request.budget.messageCount", request.getMessage() == null ? 0L : 1L);
        TraceStore.put("public.request.budget.historyCount", (long) size(request.getHistory()));
        TraceStore.put("public.request.budget.attachmentCount", (long) size(request.getAttachmentIds()));
    }

    private static void validateImagePayload(ChatRequestDto request,
                                             int maxEncodedChars,
                                             long started) {
        String rawBase64 = request.getImageBase64();
        TraceStore.put("public.request.budget.imagePayloadRedacted", true);
        TraceStore.put("public.request.budget.imageEncodedChars", rawBase64 == null ? 0 : rawBase64.length());
        TraceStore.put("public.request.budget.imageDecodedBytes", 0);
        TraceStore.put("public.request.budget.imageMediaType", "NONE");
        if (rawBase64 == null) {
            return;
        }

        String encoded = rawBase64.trim();
        if (encoded.isEmpty()) {
            reject(HttpStatus.BAD_REQUEST, "chat_image_empty", started);
        }
        if (encoded.regionMatches(true, 0, "data:", 0, "data:".length())) {
            reject(HttpStatus.BAD_REQUEST, "chat_image_data_uri_not_allowed", started);
        }

        String mediaType = request.resolvedImageMediaType();
        if (!ALLOWED_IMAGE_MEDIA_TYPES.contains(mediaType)) {
            reject(HttpStatus.BAD_REQUEST, "chat_image_media_type_unsupported", started);
        }

        long estimatedDecodedBytes = estimatedDecodedBytes(encoded);
        long maxDecodedBytes = estimatedDecodedBytesForEncodedLimit(maxEncodedChars);
        if (estimatedDecodedBytes > maxDecodedBytes) {
            reject(HttpStatus.PAYLOAD_TOO_LARGE, "chat_image_too_large", started);
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            reject(HttpStatus.BAD_REQUEST, "chat_image_base64_invalid", started);
            return;
        }
        if (decoded.length == 0) {
            reject(HttpStatus.BAD_REQUEST, "chat_image_empty", started);
        }
        if (!hasDeclaredImageSignature(decoded, mediaType)) {
            reject(HttpStatus.BAD_REQUEST, "image_signature_mismatch", started);
        }
        TraceStore.put("public.request.budget.imageMediaType", imageMediaTypeLabel(mediaType));
        TraceStore.put("public.request.budget.imageDecodedBytes", decoded.length);
    }

    private static String imageMediaTypeLabel(String mediaType) {
        return switch (mediaType) {
            case "image/png" -> "PNG";
            case "image/jpeg" -> "JPEG";
            case "image/webp" -> "WEBP";
            default -> "NONE";
        };
    }

    private static long estimatedDecodedBytes(String encoded) {
        int padding = 0;
        int length = encoded.length();
        if (length > 0 && encoded.charAt(length - 1) == '=') {
            padding++;
        }
        if (length > 1 && encoded.charAt(length - 2) == '=') {
            padding++;
        }
        return Math.max(0L, ((long) length + 3L) / 4L * 3L - padding);
    }

    private static long estimatedDecodedBytesForEncodedLimit(int maxEncodedChars) {
        return ((long) maxEncodedChars + 3L) / 4L * 3L;
    }

    private static boolean hasDeclaredImageSignature(byte[] decoded, String mediaType) {
        return switch (mediaType) {
            case "image/png" -> startsWith(decoded, new int[] {
                    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            });
            case "image/jpeg" -> startsWith(decoded, new int[] {0xff, 0xd8, 0xff});
            case "image/webp" -> decoded.length >= 12
                    && decoded[0] == 'R' && decoded[1] == 'I'
                    && decoded[2] == 'F' && decoded[3] == 'F'
                    && decoded[8] == 'W' && decoded[9] == 'E'
                    && decoded[10] == 'B' && decoded[11] == 'P';
            default -> false;
        };
    }

    private static boolean startsWith(byte[] decoded, int[] signature) {
        if (decoded.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((decoded[i] & 0xff) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static void traceStart(String endpoint, String phase) {
        TraceStore.put("public.request.budget.endpoint", endpoint);
        TraceStore.put("public.request.budget.phase", phase);
        TraceStore.put("public.request.budget.rejectReason", null);
        TraceStore.put("public.request.budget.status", null);
        TraceStore.put("public.request.budget.accepted", null);
        TraceStore.put("public.request.budget.requestedTokens", null);
        TraceStore.put("public.request.budget.retrievalWork", null);
        TraceStore.put("public.request.budget.providerWork", null);
        TraceStore.put("public.request.budget.branchCount", null);
        TraceStore.put("public.request.budget.requestedTopK", null);
        TraceStore.put("public.request.budget.effectiveTopK", null);
        TraceStore.put("public.request.budget.effectiveProviderCount", null);
        TraceStore.put("public.request.budget.itemCount", null);
        TraceStore.put("public.request.budget.messageCount", null);
        TraceStore.put("public.request.budget.historyCount", null);
        TraceStore.put("public.request.budget.attachmentCount", null);
        TraceStore.put("public.request.budget.imagePayloadRedacted", null);
        TraceStore.put("public.request.budget.imageEncodedChars", null);
        TraceStore.put("public.request.budget.imageDecodedBytes", null);
        TraceStore.put("public.request.budget.imageMediaType", null);
        TraceStore.put("public.request.budget.searchPolicyFallback", null);
        TraceStore.put("public.request.budget.searchPolicyFallback.errorType", null);
        if ("raw_body".equals(phase)) {
            TraceStore.put("public.request.budget.bodyBytes", null);
        }
    }

    private static void traceBudget(long tokens,
                                    long retrievalWork,
                                    long providerWork,
                                    long branchCount,
                                    long itemCount,
                                    int requestedTopK,
                                    int effectiveTopK,
                                    int effectiveProviderCount) {
        TraceStore.put("public.request.budget.requestedTokens", tokens);
        TraceStore.put("public.request.budget.retrievalWork", retrievalWork);
        TraceStore.put("public.request.budget.providerWork", providerWork);
        TraceStore.put("public.request.budget.branchCount", branchCount);
        TraceStore.put("public.request.budget.itemCount", itemCount);
        TraceStore.put("public.request.budget.requestedTopK", Math.max(0, requestedTopK));
        TraceStore.put("public.request.budget.effectiveTopK", Math.max(0, effectiveTopK));
        TraceStore.put("public.request.budget.effectiveProviderCount", Math.max(0, effectiveProviderCount));
    }

    private static void accept(long started) {
        TraceStore.put("public.request.budget.accepted", true);
        TraceStore.put("public.request.budget.elapsedMs", elapsedMillis(started));
    }

    private static void reject(HttpStatus status, String reason, long started) {
        TraceStore.put("public.request.budget.accepted", false);
        TraceStore.put("public.request.budget.rejectReason", reason);
        TraceStore.put("public.request.budget.status", status.value());
        TraceStore.put("public.request.budget.elapsedMs", elapsedMillis(started));
        throw new Rejection(status, reason);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static void requireLength(String value, int max, String reason, long started) {
        if (value != null && value.length() > Math.max(0, max)) {
            reject(HttpStatus.PAYLOAD_TOO_LARGE, reason, started);
        }
    }

    private static void requireCollection(List<String> values, int maxCount, int maxItemChars,
                                          String countReason, String itemReason, long started) {
        if (size(values) > Math.max(0, maxCount)) {
            reject(HttpStatus.PAYLOAD_TOO_LARGE, countReason, started);
        }
        if (values != null) {
            for (String value : values) requireLength(value, maxItemChars, itemReason, started);
        }
    }

    private static void finiteRange(Double value, double min, double max, String reason, long started) {
        if (value != null && (!Double.isFinite(value) || value < min || value > max)) {
            reject(HttpStatus.BAD_REQUEST, reason, started);
        }
    }

    private static void finiteRange(double value, double min, double max, String reason, long started) {
        if (!Double.isFinite(value) || value < min || value > max) {
            reject(HttpStatus.BAD_REQUEST, reason, started);
        }
    }

    private static int positiveBounded(Integer value, int max, String reason, long started) {
        if (value == null) return 0;
        return positivePrimitiveBounded(value, max, reason, started);
    }

    private static int nullablePositiveBounded(Integer value, int max, String reason, long started) {
        if (value == null) return 0;
        return positivePrimitiveBounded(value, max, reason, started);
    }

    private static int positivePrimitiveBounded(int value, int max, String reason, long started) {
        if (value <= 0 || value > Math.max(1, max)) {
            reject(HttpStatus.BAD_REQUEST, reason, started);
        }
        return value;
    }

    private static int nonNegativePrimitiveBounded(int value, int max, String reason, long started) {
        if (value < 0 || value > Math.max(0, max)) {
            reject(HttpStatus.BAD_REQUEST, reason, started);
        }
        return value;
    }

    private static int nonNegativeBounded(Integer value, int max, String reason, long started) {
        if (value == null) return 0;
        if (value < 0 || value > Math.max(0, max)) {
            reject(HttpStatus.BAD_REQUEST, reason, started);
        }
        return value;
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static long safeAdd(long... values) {
        long total = 0L;
        for (long value : values) {
            if (value > 0L && total > Long.MAX_VALUE - value) return Long.MAX_VALUE;
            total += value;
        }
        return total;
    }

    private static long safeMultiply(long... values) {
        long product = 1L;
        for (long value : values) {
            if (value <= 0L) return 0L;
            if (product > Long.MAX_VALUE / value) return Long.MAX_VALUE;
            product *= value;
        }
        return product;
    }

    private static boolean isDeepMode(ChatRequestDto request) {
        if (request.getSearchMode() == SearchMode.FORCE_DEEP) return true;
        SearchMode requested = request.getSearchMode();
        return (requested == null || requested == SearchMode.AUTO)
                && request.getMessage() != null
                && DEEP_PROBE_PREFIX.matcher(request.getMessage()).find();
    }

    private void validateSeedCandidates(List<Doc> docs, long started) {
        if (docs == null) return;
        for (Doc doc : docs) {
            if (doc == null) continue;
            requireLength(doc.id, maxSeedMetadataItemChars, "rag_seed_content_too_large", started);
            requireLength(doc.title, maxSeedContentChars, "rag_seed_content_too_large", started);
            requireLength(doc.snippet, maxSeedContentChars, "rag_seed_content_too_large", started);
            requireLength(doc.source, maxSeedMetadataItemChars, "rag_seed_content_too_large", started);
            if (!Double.isFinite(doc.score)) {
                reject(HttpStatus.BAD_REQUEST, "rag_seed_score_invalid", started);
            }
            validateSeedMetadata(doc.meta, started);
        }
    }

    private void validateSeedContents(List<Content> contents, long started) {
        if (contents == null) return;
        for (Content content : contents) {
            if (content == null || content.textSegment() == null) continue;
            requireLength(content.textSegment().text(), maxSeedContentChars,
                    "rag_seed_content_too_large", started);
            if (content.textSegment().metadata() != null) {
                validateSeedMetadata(content.textSegment().metadata().toMap(), started);
            }
        }
    }

    private void validateSeedMetadata(Map<String, ?> metadata, long started) {
        if (metadata == null) return;
        if (metadata.size() > maxSeedMetadataEntries) {
            reject(HttpStatus.PAYLOAD_TOO_LARGE, "rag_seed_metadata_count_exceeded", started);
        }
        MetadataBudget budget = new MetadataBudget();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            validateMetadataKey(entry.getKey(), budget, started);
            validateMetadataValue(entry.getValue(), 1, budget, started);
        }
    }

    private void validateMetadataKey(String key, MetadataBudget budget, long started) {
        requireLength(key, maxSeedMetadataItemChars, "rag_seed_metadata_too_large", started);
        budget.addChars(key == null ? 0 : key.length(), maxSeedContentChars, started);
    }

    private void validateMetadataValue(Object value, int depth, MetadataBudget budget, long started) {
        if (value == null) return;
        if (depth > 4) {
            reject(HttpStatus.PAYLOAD_TOO_LARGE, "rag_seed_metadata_depth_exceeded", started);
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> nested : map.entrySet()) {
                budget.addNode(maxSeedMetadataEntries, started);
                validateMetadataKey(String.valueOf(nested.getKey()), budget, started);
                validateMetadataValue(nested.getValue(), depth + 1, budget, started);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object nested : iterable) {
                budget.addNode(maxSeedMetadataEntries, started);
                validateMetadataValue(nested, depth + 1, budget, started);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                budget.addNode(maxSeedMetadataEntries, started);
                validateMetadataValue(Array.get(value, i), depth + 1, budget, started);
            }
            return;
        }
        if (value instanceof Number number
                && (number instanceof Double || number instanceof Float)
                && !Double.isFinite(number.doubleValue())) {
            reject(HttpStatus.BAD_REQUEST, "rag_seed_metadata_invalid", started);
        }
        String scalar = String.valueOf(value);
        requireLength(scalar, maxSeedMetadataItemChars, "rag_seed_metadata_too_large", started);
        budget.addChars(scalar.length(), maxSeedContentChars, started);
    }

    private static final class MetadataBudget {
        private int nodes;
        private long chars;

        private void addNode(int maxNodes, long started) {
            nodes++;
            if (nodes > maxNodes) {
                reject(HttpStatus.PAYLOAD_TOO_LARGE, "rag_seed_metadata_count_exceeded", started);
            }
        }

        private void addChars(int count, int maxChars, long started) {
            chars = safeAdd(chars, Math.max(0, count));
            if (chars > maxChars) {
                reject(HttpStatus.PAYLOAD_TOO_LARGE, "rag_seed_metadata_too_large", started);
            }
        }
    }

    private static String publicPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri == null) return "";
        if (context != null && !context.isBlank() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    private static void requireConfigured(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalStateException("Invalid public request budget setting: " + name);
        }
    }

    private static void requireConfigured(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalStateException("Invalid public request budget setting: " + name);
        }
    }

    private static void rejectHeader(HttpServletResponse response, String reason, long started)
            throws IOException {
        TraceStore.put("public.request.budget.accepted", false);
        TraceStore.put("public.request.budget.rejectReason", reason);
        TraceStore.put("public.request.budget.status", HttpStatus.BAD_REQUEST.value());
        TraceStore.put("public.request.budget.elapsedMs", elapsedMillis(started));
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":400,\"reasonCode\":\"" + reason + "\"}");
    }

    private static void rejectBody(HttpServletResponse response, long observedBytes, long started)
            throws IOException {
        TraceStore.put("public.request.budget.accepted", false);
        TraceStore.put("public.request.budget.bodyBytes", Math.max(0L, observedBytes));
        TraceStore.put("public.request.budget.rejectReason", "public_request_body_too_large");
        TraceStore.put("public.request.budget.status", HttpStatus.PAYLOAD_TOO_LARGE.value());
        TraceStore.put("public.request.budget.elapsedMs", elapsedMillis(started));
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":413,\"reasonCode\":\"public_request_body_too_large\"}");
    }

    private static void rejectBodyExecutorSaturated(HttpServletResponse response, long started)
            throws IOException {
        String reason = "public_body_executor_saturated";
        TraceStore.put("public.request.budget.accepted", false);
        TraceStore.put("public.request.budget.rejectReason", reason);
        TraceStore.put("public.request.budget.status", HttpStatus.TOO_MANY_REQUESTS.value());
        TraceStore.put("public.request.budget.elapsedMs", elapsedMillis(started));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":429,\"reasonCode\":\"" + reason + "\"}");
    }

    private static void rejectBodyReadCancelled(HttpServletResponse response, long started)
            throws IOException {
        String reason = "public_request_cancelled";
        TraceStore.put("public.request.budget.accepted", false);
        TraceStore.put("public.request.budget.rejectReason", reason);
        TraceStore.put("public.request.budget.status", HttpStatus.REQUEST_TIMEOUT.value());
        TraceStore.put("public.request.budget.elapsedMs", elapsedMillis(started));
        response.setStatus(HttpStatus.REQUEST_TIMEOUT.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":408,\"reasonCode\":\"" + reason + "\"}");
    }

    private static void rejectDeadline(HttpServletResponse response, long started) throws IOException {
        String reason = "public_request_deadline_exhausted";
        TraceStore.put("public.request.budget.accepted", false);
        TraceStore.put("public.request.budget.rejectReason", reason);
        TraceStore.put("public.request.budget.status", HttpStatus.REQUEST_TIMEOUT.value());
        TraceStore.put("public.request.budget.elapsedMs", elapsedMillis(started));
        response.setStatus(HttpStatus.REQUEST_TIMEOUT.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"status\":408,\"reasonCode\":\"" + reason + "\"}");
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener readListener) {
                    if (readListener == null) return;
                    try {
                        if (isFinished()) readListener.onAllDataRead();
                        else readListener.onDataAvailable();
                    } catch (IOException ex) {
                        readListener.onError(ex);
                    }
                }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int off, int len) { return input.read(bytes, off, len); }
            };
        }

        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
    }

    public int getMaxMessageChars() { return maxMessageChars; }
    public void setMaxMessageChars(int value) { maxMessageChars = value; }
    public int getMaxHistoryItems() { return maxHistoryItems; }
    public int getMaxImageBase64Chars() { return maxImageBase64Chars; }
    int attachmentLookaheadLimit() { return maxAttachmentIds + 1; }
    public void setMaxImageBase64Chars(int value) { maxImageBase64Chars = value; }
    public void setMaxAttachmentIds(int value) { maxAttachmentIds = value; }
    public void setMaxTotalTokenBudget(int value) { maxTotalTokenBudget = value; }
    public void setMaxRetrievalWork(int value) { maxRetrievalWork = value; }
    public void setMaxProviderWork(int value) { maxProviderWork = value; }
    public int getMaxQueryChars() { return maxQueryChars; }
    public void setMaxSeedItems(int value) { maxSeedItems = value; }
    public void setMaxJsonBodyBytes(int value) { maxJsonBodyBytes = value; }
    public void setMaxTimeBudgetMs(long value) { maxTimeBudgetMs = value; }
    public void setBodyReadWorkers(int value) { bodyReadWorkers = value; }
    public void setBodyReadQueueCapacity(int value) { bodyReadQueueCapacity = value; }

    public static final class Rejection extends ResponseStatusException {
        private final HttpStatus status;
        private final String reasonCode;

        private Rejection(HttpStatus status, String reasonCode) {
            super(status, reasonCode);
            this.status = status;
            this.reasonCode = reasonCode;
            getBody().setProperty("reasonCode", reasonCode);
        }

        public HttpStatus status() { return status; }
        public String reasonCode() { return reasonCode; }
    }
}
