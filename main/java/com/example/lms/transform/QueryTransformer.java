package com.example.lms.transform;

import static com.example.lms.transform.QueryTransformerFailureSupport.*;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;

import com.example.lms.infra.resilience.FriendShieldPatternDetector;
import com.example.lms.infra.resilience.AuxDownTracker;
import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.infra.resilience.AuxBlockedReason;
import com.example.lms.infra.resilience.NoiseRoutingGate;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.search.SearchQueryConstraints;
import com.example.lms.trace.SafeRedactor;
import ai.abandonware.nova.orch.trace.OrchTrace;
import com.example.lms.orchestration.OrchStageKeys;
import com.example.lms.orchestration.StagePolicyProperties;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;

import java.util.*;
import java.util.Objects;
import java.util.regex.Pattern;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.lang.Nullable;
import java.util.regex.Matcher;
import java.time.Duration;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;
import com.example.lms.search.SmartQueryPlanner;
import com.example.lms.search.QueryHygieneFilter;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.infra.exec.ContextPropagation;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import com.example.lms.prompt.PromptContext;
import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.service.rag.pre.CognitiveState;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.probe.EvidenceSignals;

/**
 * 쿼리 오타를 교정해 주는 Transformer
 */
@Component
public class QueryTransformer {

    /** LLM 제안·힌트 개수 상한 */
    private static final int MAX_VARIANTS = 3; // generateVariantsWithLLM() 한도
    private static final int MAX_HINTS = 4; // LLM 힌트 상한 (configurable via search.llm.max-hints)

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QueryTransformer.class);

    /**
     * Centralised prompt builder used for constructing all LLM prompts. This avoids
     * assembling raw strings in multiple locations and ensures a single source
     * of truth for prompt wording.
     */
    private static final QueryKeywordPromptBuilder QUERY_KEYWORD_PROMPT_BUILDER = new QueryKeywordPromptBuilder();

    /*
     * ────────────────────────────────────────
     * 0. “원소 감지” ― 쿼리 Intent Enum
     * ────────────────────────────────────────
     */
    public enum QueryIntent {
        PRODUCT_SPEC, // 제품‧스펙‧가격
        LOCATION_RECOMMEND, // 맛집‧여행지
        TECHNICAL_HOW_TO, // 코딩·설정 방법
        PERSON_LOOKUP, // 인물 정보
        GENERAL_KNOWLEDGE // 그 외
    }

    // QueryTransformer.java ─ 클래스 필드 영역에 삽입
    /** ───── cleanUp()용 정규식 ───── */
    private static final Pattern CLEANUP_PREFIX_NUM = Pattern.compile("^[0-9]+[\\.:\\)]\\s*");
    private static final Pattern CLEANUP_PREFIX_BULLET = Pattern.compile("^[\\-*•·]\\s*");
    private static final Pattern CLEANUP_META = Pattern.compile("^(틀렸.*?[:：]\\s*|올바른\\s*(표기|표현)[:：]\\s*)");
    private static final Pattern CLEANUP_SPACES = Pattern.compile("[\\p{Z}\\s]{2,}");
    private static final Pattern CLEANUP_QUOTES = Pattern.compile("[\"“”'’`]+");
    /** 유사도 판정을 위한 정규화(한글/영문/숫자만 유지) */
    private static final Pattern NON_ALNUM_KO = Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{Nd}]");
    /* (선택) 프로젝트에서 유지할 소규모 오타 사전 - 빈맵이면 사용 안 함 */
    private final Map<String, String> dict;

    private final ChatModel chatModel;
    private final HintExtractor hintExtractor;
    /** LLM 호출 결과를 캐시하여 동일한 요청에 대한 비용과 지연을 줄인다. */
    private final Cache<String, String> llmCache;

    // Debug-only breadcrumbs (no secrets). Helps diagnose "model is required"
    // errors.
    @org.springframework.beans.factory.annotation.Value("${llm.fast.base-url:${llm.base-url:}}")
    private String fastBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${llm.fast.model:${llm.chat-model:}}")
    private String fastModelName;

    // Unified noise clipper for cleaning intermediate strings. Optional
    // injection because QueryTransformer may be used outside of a Spring
    // context during unit testing. When null, no additional normalisation
    // is applied.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.search.NoiseClipper noiseClipper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FailurePatternOrchestrator failurePatterns;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.infra.resilience.FaultMaskingLayerMonitor faultMaskingLayerMonitor;

    /** Structured debug events for fail-soft paths (optional). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DebugEventStore debugEventStore;

    // Stage-policy clamp (strong OFF switch) for expensive aux-LLM query rewriting.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StagePolicyProperties stagePolicy;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("llmFastExecutor")
    private ExecutorService llmFastExecutor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AnchorNarrower anchorNarrower;

    @org.springframework.beans.factory.annotation.Value("${nova.orch.enabled:true}")
    private boolean novaOrchEnabled;

    @org.springframework.beans.factory.annotation.Value("${nova.orch.query-transformer.enabled:true}")
    private boolean novaOrchQueryTransformerEnabled;

    /**
     * Cheap/deterministic query expansion should remain available even when the
     * LLM-backed
     * QueryTransformer is disabled or temporarily blocked (breaker-open /
     * aux.blocked).
     *
     * This is intentionally separated from
     * {@code nova.orch.query-transformer.enabled}.
     */
    @org.springframework.beans.factory.annotation.Value("${nova.orch.query-transformer.cheap.enabled:true}")
    private boolean novaOrchQueryTransformerCheapEnabled;

    @org.springframework.beans.factory.annotation.Value("${nova.orch.query-transformer.bypass-on-strike:true}")
    private boolean bypassOnStrike;

    @org.springframework.beans.factory.annotation.Value("${nova.orch.query-transformer.cheap-variants:3}")
    private int cheapVariants;

    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.inflight-timeout-ms:5000}")
    private long inflightTimeoutMs;

    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.timeout-ms-hint:1200}")
    private long llmTimeoutMsHint;

    // Floor for the caller-side hint-timeout to avoid immediate timeouts under tiny budgets.
    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.hint-timeout-floor-ms:350}")
    private long llmHintTimeoutFloorMs;

    // Guardrail: aux LLM calls are optional; do not allow unbounded prompt growth.
    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.max-prompt-chars:2400}")
    private int llmMaxPromptChars;

    // TIMEOUT -> breaker OPEN can cascade into starvation. Keep a small cap for
    // this optional stage.
    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.timeout-open-hint-ms:8000}")
    private long llmTimeoutOpenHintMs;

    // Base soft-cooldown for transient QTX failures (decoupled from breaker-open hint).
    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.soft-cooldown-base-ms:2000}")
    private long qtxSoftCooldownBaseMs;

    // Cap and decay window for QTX soft-cooldown backoff (prevents sticky degraded state).
    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.soft-cooldown-max-ms:6000}")
    private long qtxSoftCooldownMaxMs;

    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.soft-cooldown-decay-window-ms:8000}")
    private long qtxSoftCooldownDecayWindowMs;

    // Coalesce bursts of failures so a single root cause doesn't inflate streak too fast.
    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.soft-cooldown-coalesce-window-ms:750}")
    private long qtxSoftCooldownCoalesceWindowMs;


    /**
     * Optional "min live budget" cap for the QTX(runLLM) stage.
     *
     * <p>
     * If configured (>0), the caller-side hint-timeout (Future#get) is clamped to
     * this
     * value so that downstream stages keep enough time budget.
     * This is a fail-soft guardrail (QTX is an optional enhancer).
     * </p>
     */
    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.min-live-budget-ms:0}")
    private long llmMinLiveBudgetMs;

    /**
     * Local soft-cooldown window for transient QTX LLM failures (model loading /
     * timeouts).
     *
     * <p>
     * We intentionally avoid tripping the global breaker for these transitional
     * failures
     * because QueryTransformer is an optional stage. Instead we step down
     * (fail-soft)
     * for a short window to avoid thrash.
     * </p>
     */
    private final java.util.concurrent.atomic.AtomicLong qtxSoftCooldownUntilMs = new java.util.concurrent.atomic.AtomicLong(
            0L);

    private final java.util.concurrent.atomic.AtomicInteger qtxSoftCooldownStreak = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong lastQtxSoftCooldownAtEpochMs = new java.util.concurrent.atomic.AtomicLong(0L);

    // QueryTransformer is a singleton; protect streak/time updates against concurrent bursts.
    private final Object qtxSoftCooldownLock = new Object();

    /**
     * Optional per-call timeout override.
     *
     * <p>
     * NOTE: QueryTransformer is a singleton. We avoid mutating global fields for
     * per-request tuning; instead we use a ThreadLocal which is read on the
     * caller thread when computing {@code Future#get(timeout)}.
     * </p>
     */
    private final ThreadLocal<Long> llmTimeoutOverride = new ThreadLocal<>();

    // Inflight dedupe to avoid cache stampede for identical prompts.
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> inflightTasks = new ConcurrentHashMap<>();

    /**
     * Runs the supplied block with a temporary timeout hint.
     * This is best-effort and fail-soft.
     */
    public <T> T withLlmTimeoutMsHint(long timeoutMs, java.util.function.Supplier<T> supplier) {
        if (supplier == null) {
            return null;
        }
        Long prev = llmTimeoutOverride.get();
        if (timeoutMs > 0) {
            llmTimeoutOverride.set(timeoutMs);
        }
        try {
            return supplier.get();
        } finally {
            if (prev == null) {
                llmTimeoutOverride.remove();
            } else {
                llmTimeoutOverride.set(prev);
            }
        }
    }

    private long effectiveLlmTimeoutMsHint() {
        Long o = llmTimeoutOverride.get();
        if (o != null && o > 0) {
            return o;
        }
        return llmTimeoutMsHint;
    }

    private long applyMinLiveBudgetCap(long timeoutMs) {
        long cap = 0L;
        try {
            cap = Math.max(0L, llmMinLiveBudgetMs);
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.minLiveBudget", true);
            cap = 0L;
        }
        if (cap <= 0L) {
            return timeoutMs;
        }
        long before = Math.max(0L, timeoutMs);
        long after = Math.max(0L, Math.min(before, cap));
        if (after != before) {
            try {
                TraceStore.put("qtx.minLiveBudgetMs", cap);
                TraceStore.put("qtx.timeoutMs.cappedByMinLiveBudget", true);
                TraceStore.put("qtx.timeoutMs.before", before);
                TraceStore.put("qtx.timeoutMs.after", after);
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.minLiveBudgetTrace", true);
                // best-effort
            }
        }
        return after;
    }

    private long qtxSoftCooldownRemainingMs() {
        try {
            long until = qtxSoftCooldownUntilMs.get();
            long now = System.currentTimeMillis();
            return until > now ? (until - now) : 0L;
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.softCooldownRemaining", true);
            return 0L;
        }
    }

    public long getSoftCooldownRemainingMs() {
        return qtxSoftCooldownRemainingMs();
    }

    private void startQtxSoftCooldown(String reason, NightmareBreaker.FailureKind kind, Throwable err,
            long cooldownMs) {
        long baseMs = Math.max(0L, cooldownMs);
        if (baseMs <= 0L) {
            return;
        }

        long now = System.currentTimeMillis();
        int streak = 1;
        boolean decayed = false;
        boolean coalesced = false;
        long decayWindow = Math.max(0L, qtxSoftCooldownDecayWindowMs);
        long coalesceWindow = Math.max(0L, qtxSoftCooldownCoalesceWindowMs);

        try {
            synchronized (qtxSoftCooldownLock) {
                long last = lastQtxSoftCooldownAtEpochMs.get();
                if (last > 0L && decayWindow > 0L && (now - last) > decayWindow) {
                    qtxSoftCooldownStreak.set(0);
                    decayed = true;
                }

                // Coalesce rapid successive failures so a single root cause doesn't inflate streak too fast.
                if (last > 0L && coalesceWindow > 0L && (now - last) < coalesceWindow) {
                    coalesced = true;
                    int cur = qtxSoftCooldownStreak.get();
                    streak = Math.max(1, cur);
                    lastQtxSoftCooldownAtEpochMs.set(now);
                } else {
                    lastQtxSoftCooldownAtEpochMs.set(now);
                    streak = Math.max(1, qtxSoftCooldownStreak.incrementAndGet());
                }
            }
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.cooldownStreak", true);
            streak = 1;
        }

        // Exponential backoff (capped), plus small jitter.
        int capped = Math.min(Math.max(1, streak), 6);
        long backoffMs = baseMs * (1L << (capped - 1));

        long maxMs = qtxSoftCooldownMaxMs > 0L ? qtxSoftCooldownMaxMs : 6_000L;
        maxMs = Math.max(maxMs, baseMs);
        if (maxMs > 60_000L) {
            maxMs = 60_000L;
        }

        long jitter = 0L;
        try {
            long jitterCap = Math.min(500L, Math.max(0L, baseMs / 3));
            if (jitterCap > 0L) {
                jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0L, jitterCap + 1L);
            }
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.cooldownJitter", true);
            jitter = 0L;
        }

        long ms = Math.min(backoffMs + jitter, maxMs);
        if (ms <= 0L) {
            ms = Math.min(maxMs, Math.max(100L, baseMs));
        }

        long until = now + ms;
        try {
            qtxSoftCooldownUntilMs.getAndUpdate(prev -> Math.max(prev, until));
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.cooldownUntil", true);
            // best-effort
        }

        try {
            TraceStore.put("qtx.softCooldown.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            if (kind != null) {
                TraceStore.put("qtx.softCooldown.kind", kind.name());
            }
            TraceStore.put("qtx.softCooldown.baseMs", baseMs);
            TraceStore.put("qtx.softCooldown.ms", ms);
            TraceStore.put("qtx.softCooldown.untilMs", until);
            TraceStore.put("qtx.softCooldown.streak", streak);
            TraceStore.put("qtx.softCooldown.jitterMs", jitter);
            TraceStore.put("qtx.softCooldown.maxMs", maxMs);
            if (decayed) {
                TraceStore.put("qtx.softCooldown.decayed", true);
                TraceStore.put("qtx.softCooldown.decayWindowMs", decayWindow);
            }
            if (coalesced) {
                TraceStore.put("qtx.softCooldown.coalesced", true);
                TraceStore.put("qtx.softCooldown.coalesceWindowMs", coalesceWindow);
            }
            TraceStore.inc("qtx.softCooldown.count");
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.cooldownTrace", true);
            // best-effort
        }

        // DebugProbe is optional. This is best-effort only.
        try {
            java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
            dd.put("reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            dd.put("kind", (kind != null ? kind.name() : null));
            dd.put("baseMs", baseMs);
            dd.put("cooldownMs", ms);
            dd.put("streak", streak);
            dd.put("maxMs", maxMs);
            dd.put("jitterMs", jitter);
            if (decayed) {
                dd.put("decayed", true);
                dd.put("decayWindowMs", decayWindow);
            }
            if (err != null) {
                Throwable root = unwrap(err);
                dd.put("rootType", (root != null ? root.getClass().getName() : err.getClass().getName()));
                String m = (root != null ? root.getMessage() : err.getMessage());
                if (m != null && !m.isBlank()) {
                    dd.put("messageHash", SafeRedactor.hashValue(m));
                    dd.put("messageLength", m.length());
                }
            }
            emitQtx(
                    DebugEventLevel.INFO,
                    "qtx.softCooldown",
                    "QTX soft cooldown started (step-down routing)",
                    "QueryTransformer",
                    dd,
                    err);
        } catch (Throwable ignore) {
            try {
                TraceStore.inc("qtx.debugEvent.emit.failed");
                TraceStore.put("qtx.debugEvent.emit.failureClass", "qtx_debug_event_emit_failed");
            } catch (Throwable traceFailure) {
                log.trace("[QueryTransformer] DebugEventStore failure breadcrumb failed: {}",
                        traceFailure.getClass().getSimpleName());
            }
        }
    }

    private void emitQtx(DebugEventLevel level,
            String fingerprint,
            String message,
            String where,
            java.util.Map<String, Object> data,
            Throwable error) {
        DebugEventStore store = this.debugEventStore;
        if (store == null) {
            return;
        }
        try {
            store.emit(
                    DebugProbeType.QUERY_TRANSFORMER,
                    (level != null ? level : DebugEventLevel.INFO),
                    fingerprint,
                    message,
                    where,
                    data,
                    error);
        } catch (Throwable ignore) {
            try {
                TraceStore.inc("qtx.debugEvent.emit.failed");
                TraceStore.put("qtx.debugEvent.emit.failureClass", "qtx_debug_event_emit_failed");
            } catch (Throwable traceFailure) {
                log.trace("[QueryTransformer] DebugEventStore failure breadcrumb failed: {}",
                        traceFailure.getClass().getSimpleName());
            }
        }
    }

    /* LLM이 생성할 동적 버프 1회 한도 */
    private static final int MAX_DYNAMIC_BUFFS = 4;

    public QueryTransformer(ChatModel chatModel) {
        this(chatModel, Map.of(), null);
    }

    // MERGE_HOOK:PROJ_AGENT::QUERY_TRANSFORMER_CUSTOM_DICT_BEAN_V1
    // MERGE_HOOK:PROJ_AGENT::QUERYTRANSFORMER_CUSTOMDICT_INJECT_V1
    private static Map<String, String> resolveCustomDict(ObjectProvider<QueryTransformerCustomDict> provider) {
        if (provider == null)
            return Map.of();
        QueryTransformerCustomDict holder = provider.getIfAvailable();
        return (holder == null || holder.dict() == null) ? Map.of() : holder.dict();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public QueryTransformer(
            @org.springframework.beans.factory.annotation.Qualifier("fastChatModel") ChatModel chatModel,
            @org.springframework.beans.factory.annotation.Qualifier("queryTransformerCustomDict") ObjectProvider<QueryTransformerCustomDict> customDictProvider,
            @Nullable HintExtractor hintExtractor) {
        this(chatModel, resolveCustomDict(customDictProvider), hintExtractor);
    }

    /**
     * Non-Spring convenience constructor used by QueryTransformerConfig and tests.
     */
    public QueryTransformer(
            ChatModel chatModel,
            Map<String, String> customDict,
            @Nullable HintExtractor hintExtractor) {
        this.chatModel = chatModel;
        this.dict = customDict == null ? Map.of() : customDict;
        this.hintExtractor = hintExtractor == null ? new RegexHintExtractor() : hintExtractor;

        this.llmCache = Caffeine.newBuilder()
                .maximumSize(2048)
                .expireAfterWrite(Duration.ofMinutes(30))
                .build();
    }

    /** LangChain4j 1.0.1 표준 메시지 호출로 LLM을 실행 */
    /**
     * LLM 결과 캐시.
     *
     * <p>
     * 중요: 실패/빈 응답("")은 캐시에 저장하지 않는다(독성 캐시 방지).
     * </p>
     */
    private String cachedLlm(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }

        String cached = llmCache.getIfPresent(prompt);
        if (cached != null) {
            recordQtxCostZone("qtx.cache_hit", prompt, true, false);
            return cached;
        }
        recordQtxCostZone("qtx.aux_llm", prompt, false, true);

        // UAW: Request-scoped aux degradation/strike/compression should block
        // additional LLM calls.
        // Cache hits are still allowed (no new external call).
        BypassDecision bd = computeBypassDecision();
        if (bd.bypass()) {
            AuxBlockedReason reason = bd.reason();
            String reasonCode = (reason != null && reason.code() != null) ? reason.code() : "unknown";
            String safeReasonCode = SafeRedactor.traceLabelOrFallback(reasonCode, "unknown");
            try {
                // MERGE_HOOK:PROJ_AGENT::QTX_BYPASS_DEGRADED_V1
                // QueryTransformer is an *optional* enhancer. Treat bypass as cheap "degraded"
                // (not "blocked")
                // so aux.blocked contagion does not over-report false blocks.
                AuxDownTracker.markSoft("query-transformer:cachedLlm", "bypass:" + reasonCode);

                TraceStore.putIfAbsent("qtx.prompt.sha1",
                        com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                TraceStore.putIfAbsent("qtx.prompt.len", prompt.length());

                TraceStore.put("queryTransformer.mode", "degraded");
                TraceStore.put("queryTransformer.reason", safeReasonCode);
                TraceStore.put("queryTransformer.trigger", bd.trigger());

                TraceStore.putIfAbsent("aux.queryTransformer", "degraded:" + safeReasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded", Boolean.TRUE);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.reason", safeReasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.trigger", bd.trigger());
                TraceStore.inc("aux.queryTransformer.degraded.count");
                if (reason == AuxBlockedReason.AUX_DEGRADED) {
                    AuxBlockTracker.markStageBlocked(
                            AuxBlockTracker.STAGE_QUERY_TRANSFORMER,
                            reason,
                            "QueryTransformer.bypass",
                            NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
                }

            } catch (Throwable ignore) {
                traceSuppressed("cachedLlm.degradedTrace");
            }

            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("trigger", bd.trigger());
                dd.put("reason", safeReasonCode);
                dd.put("key", NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
                dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                emitQtx(
                        DebugEventLevel.INFO,
                        "qtx.degraded",
                        "QueryTransformer degraded (bypass, no aux LLM call)",
                        "QueryTransformer.cachedLlm",
                        dd,
                        null);
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.degradedDebug", true);
                // fail-soft
            }
            return "";
        }

        long hint = effectiveLlmTimeoutMsHint();
        long timeoutMs = Math.max(0L, (hint > 0 ? hint : 1200L));

        // Budget floor: prevent zero/near-zero hint-timeouts that create "hint-timeout -> degraded" cascades.
        long floorMs = Math.max(0L, llmHintTimeoutFloorMs);
        if (floorMs > 0 && timeoutMs < floorMs) {
            try {
                TraceStore.put("aux.queryTransformer.hintTimeout.floorApplied", true);
                TraceStore.put("aux.queryTransformer.hintTimeout.floorMs", floorMs);
                TraceStore.put("aux.queryTransformer.hintTimeout.beforeMs", timeoutMs);
            } catch (Throwable ignore) {
                traceSuppressed("hintTimeout.floorTrace");
            }
            timeoutMs = floorMs;
        }

        timeoutMs = applyMinLiveBudgetCap(timeoutMs);

        CompletableFuture<String> fut = inflight.computeIfAbsent(prompt, p -> {
            CompletableFuture<String> created = new CompletableFuture<>();
            ExecutorService ex = (llmFastExecutor != null) ? llmFastExecutor : ForkJoinPool.commonPool();

            try {
                // Keep the cancellable handle owned by QueryTransformer. Executor bean wrappers
                // intentionally shield submit() futures from interrupts, while execute() still
                // preserves their context propagation around this FutureTask.
                FutureTask<Void> task = new FutureTask<>(ContextPropagation.wrap(() -> {
                    try {
                        String out = runLLM(p);
                        created.complete(out == null ? "" : out);
                    } catch (Throwable t) {
                        recordCachedLlmWorkerThrowable(p, t);
                        if (faultMaskingLayerMonitor != null) {
                            faultMaskingLayerMonitor.record("query-transformer:runLLM", t,
                                    "promptSha1=" + com.abandonware.ai.agent.integrations.TextUtils.sha1(p),
                                    "swallowed");
                        }
                        created.complete("");
                    }
                }), null);
                inflightTasks.put(p, task);
                ex.execute(task);
                // ✅ UAW: Force Kill (좀비 inflight 강제 차단)
                long killMs = Math.max(1L, inflightTimeoutMs);
                CompletableFuture.runAsync(ContextPropagation.wrap(() -> {
                    if (created.isDone())
                        return;
                    Future<?> toCancel = inflightTasks.get(p);
                    if (toCancel != null) {
                        toCancel.cancel(true);
                    }

                    // MERGE_HOOK:PROJ_AGENT::QT_FORCEKILL_MARKSOFT_V1
                    // QueryTransformer는 옵션 전처리 단계이므로 force-kill을 aux-down으로 과대 승격하지 않는다.
                    AuxDownTracker.markSoft("query-transformer:cachedLlm", "force-kill");
                    try {
                        TraceStore.put("aux.queryTransformer", "force-kill");
                        TraceStore.put("queryTransformer.forceKill", true);
                    } catch (Exception ignore) {
                        traceSuppressed("forceKill.trace");
                    }
                    if (nightmareBreaker != null) {
                        // Force-kill is local zombie cleanup. Do NOT trip the breaker here,
                        // otherwise we can create cancellation-toxicity cascades (cancel ->
                        // breaker-open -> aux blank).
                        try {
                            TraceStore.put("queryTransformer.forceKill.breakerTripSuppressed", true);
                        } catch (Exception ignore) {
                            traceSuppressed("forceKill.breakerSuppressedTrace");
                        }
                    }
                    created.complete("");
                }), CompletableFuture.delayedExecutor(killMs, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                TraceStore.put("qtx.suppressed.forceKillSchedule", true);
                created.complete("");
            }

            // ✅ Single-flight 유지 + 백그라운드 완료 시 캐시에 남겨 다음 요청 가속
            created.whenComplete((r, e) -> {
                inflight.remove(p, created);
                inflightTasks.remove(p);
                if (r != null && !r.isBlank()) {
                    llmCache.put(p, r);
                }
            });
            return created;
        });

        try {
            String out = fut.get(timeoutMs + 200L, TimeUnit.MILLISECONDS);
            return (out == null) ? "" : out;
        } catch (TimeoutException te) {
            // MERGE_HOOK:PROJ_AGENT::QT_HINT_TIMEOUT_NO_CANCEL_V1
            // Soft hint-timeout:
            // - inflight를 cancel하지 않는다 (백그라운드 완료 → cache warm-up)
            // - breaker를 trip하지 않는다 (힌트 타임아웃은 실패가 아니라 '느림' 신호)
            // - QueryTransformer는 옵션 단계이므로 auxDegraded 플래그를 올리지 않는다
            AuxDownTracker.markSoft("query-transformer:cachedLlm", "hint-timeout");
            long cdMs = qtxSoftCooldownMsFor(
                    NightmareBreaker.FailureKind.TIMEOUT,
                    false,
                    qtxSoftCooldownBaseMs,
                    llmTimeoutOpenHintMs);
            startQtxSoftCooldown("hint_timeout", NightmareBreaker.FailureKind.TIMEOUT, te, cdMs);
            try {
                TraceStore.put("aux.queryTransformer", "hint-timeout");
                TraceStore.put("queryTransformer.softTimeout", true);
                TraceStore.put("queryTransformer.softTimeoutMs", timeoutMs);
                TraceStore.inc("aux.queryTransformer.hintTimeout.count");
            } catch (Exception ignore) {
                traceSuppressed("hintTimeout.trace");
            }

            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("timeoutMs", timeoutMs);
                dd.put("key", NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
                dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                emitQtx(
                        DebugEventLevel.INFO,
                        "qtx.hint_timeout",
                        "QueryTransformer hint-timeout (soft)",
                        "QueryTransformer.cachedLlm",
                        dd,
                        te);
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.hintTimeoutDebug", true);
                // fail-soft
            }
            return "";
        } catch (InterruptedException ie) {
            Future<?> task = inflightTasks.get(prompt);
            if (task != null)
                task.cancel(true);
            // Preserve upstream request cancellation after terminating this owned aux task.
            Thread.currentThread().interrupt();
            // 조용히 우회 - breaker/faultmask 중복 기록하지 않음
            AuxDownTracker.markSoft("query-transformer:cachedLlm", "interrupted");
            try {
                TraceStore.put("aux.queryTransformer", "interrupted");
                TraceStore.put("queryTransformer.interrupted", true);
            } catch (Exception ignore) {
                traceSuppressed("interrupted.trace");
            }

            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("key", NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
                dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                emitQtx(
                        DebugEventLevel.WARN,
                        "qtx.interrupted",
                        "QueryTransformer interrupted (fail-soft bypass)",
                        "QueryTransformer.cachedLlm",
                        dd,
                        ie);
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.interruptedDebug", true);
                // fail-soft
            }
            return "";
        }

        catch (ExecutionException ee) {
            Throwable root = ee.getCause() != null ? ee.getCause() : ee;
            NightmareBreaker.FailureKind kind = classifyLlmFailure(root);
            boolean modelLoading = looksLikeModelLoading(root);
            long cdMs = qtxSoftCooldownMsFor(kind, modelLoading, qtxSoftCooldownBaseMs, llmTimeoutOpenHintMs);
            startQtxSoftCooldown(modelLoading ? "model_loading" : "execution_exception", kind, root, cdMs);
            AuxDownTracker.markSoft("query-transformer:cachedLlm", "execution-exception");
            TraceStore.put("qtx.softFailure", true); TraceStore.put("qtx.softFailure.kind", kind.name());
            TraceStore.put("qtx.softFailure.modelLoading", modelLoading); TraceStore.put("qtx.softFailure.cooldownMs", cdMs);
            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("key", NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
                dd.put("kind", kind.name());
                dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                emitQtx(
                        DebugEventLevel.WARN,
                        "qtx.exec_exception",
                        "QueryTransformer execution failure (fail-soft)",
                        "QueryTransformer.cachedLlm",
                        dd,
                        root);
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.execExceptionDebug", true);
                // fail-soft
            }
            return "";
        } catch (Exception e) {
            TraceStore.put("qtx.suppressed.exception", true);
            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("key", NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
                dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                emitQtx(
                        DebugEventLevel.WARN,
                        "qtx.exception",
                        "QueryTransformer failure (fail-soft)",
                        "QueryTransformer.cachedLlm",
                        dd,
                        e);
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.exceptionDebug", true);
                // fail-soft
            }
            return "";
        }
    }

    private void recordCachedLlmWorkerThrowable(String prompt, Throwable failure) {
        Throwable root = unwrap(failure);
        NightmareBreaker.FailureKind kind = classifyLlmFailure(root);
        boolean modelLoading = looksLikeModelLoading(root);
        long cdMs = qtxSoftCooldownMsFor(kind, modelLoading, qtxSoftCooldownBaseMs, llmTimeoutOpenHintMs);
        startQtxSoftCooldown(modelLoading ? "model_loading" : "worker_throwable", kind, root, cdMs);
        AuxDownTracker.markSoft("query-transformer:cachedLlm", "worker-throwable");
        try {
            TraceStore.put("qtx.softFailure", true);
            TraceStore.put("qtx.softFailure.kind", kind.name());
            TraceStore.put("qtx.softFailure.modelLoading", modelLoading);
            TraceStore.put("qtx.softFailure.cooldownMs", cdMs);
            TraceStore.put("qtx.softFailure.source", "cachedLlm.worker");
            TraceStore.putIfAbsent("qtx.prompt.sha1",
                    com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
            TraceStore.putIfAbsent("qtx.prompt.len", prompt == null ? 0 : prompt.length());
        } catch (Throwable ignore) {
            traceSuppressed("cachedLlm.workerThrowableTrace");
        }
        try {
            java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
            dd.put("key", NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
            dd.put("kind", kind.name());
            dd.put("rootType", root == null ? null : root.getClass().getName());
            dd.put("promptLen", prompt == null ? 0 : prompt.length());
            dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
            emitQtx(
                    DebugEventLevel.WARN,
                    "qtx.worker_throwable",
                    "QueryTransformer worker failure (fail-soft)",
                    "QueryTransformer.cachedLlm",
                    dd,
                    null);
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.workerThrowableDebug", true);
        }
    }

    private void recordQtxCostZone(String zone, String prompt, boolean cacheHit, boolean attemptedCall) {
        String safeZone = (zone == null || zone.isBlank()) ? "qtx.unknown"
                : zone.trim().replaceAll("[^a-zA-Z0-9_.-]+", "_");
        int chars = prompt == null ? 0 : prompt.length();
        int tokens = chars <= 0 ? 0 : Math.max(1, (int) Math.ceil(chars / 4.0d));
        try {
            TraceStore.put("cost.zone." + safeZone + ".inputChars", chars);
            TraceStore.put("cost.zone." + safeZone + ".approxInputTokens", tokens);
            TraceStore.put("cost.zone." + safeZone + ".cacheHit", cacheHit);
            TraceStore.put("cost.zone." + safeZone + ".attemptedCall", attemptedCall);
            TraceStore.inc("cost.zone." + safeZone + ".calls");
            java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
            ev.put("zone", safeZone);
            ev.put("inputChars", chars);
            ev.put("approxInputTokens", tokens);
            ev.put("cacheHit", cacheHit);
            ev.put("attemptedCall", attemptedCall);
            if (prompt != null && !prompt.isBlank()) {
                ev.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
            }
            TraceStore.append("cost.zone.events", ev);
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.costZone", true);
            // cost tracing is best-effort
        }
    }

    private record BypassDecision(
            boolean bypass,
            AuxBlockedReason reason,
            String trigger,
            String modeLabel,
            boolean breakerOpen,
            boolean failureCooldown,
            boolean stagePolicyClamped) {
    }

    private BypassDecision computeBypassDecision() {
        boolean disabled = false;
        boolean breakerOpen = false;
        boolean failureCooldown = false;
        boolean stagePolicyClamped = false;
        boolean softCooldown = false;
        long softCooldownRemainingMs = 0L;

        GuardContext ctx = GuardContextHolder.getOrDefault();
        boolean cheapSearchScope = isCheapSearchModeScopeActive(ctx);

        try {
            if (!novaOrchEnabled || !novaOrchQueryTransformerEnabled) {
                disabled = true;
            }
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.orchEnabledRead", true);
            // best-effort
        }

        try {
            breakerOpen = nightmareBreaker != null
                    && nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.breakerRead", true);
            // best-effort
        }

        // QTX local soft cooldown (transient failures): step-down routing without
        // breaker trip.
        if (!disabled && !breakerOpen) {
            try {
                softCooldownRemainingMs = qtxSoftCooldownRemainingMs();
                softCooldown = softCooldownRemainingMs > 0;
                if (softCooldown) {
                    TraceStore.put("qtx.softCooldown.active", true);
                    TraceStore.put("qtx.softCooldown.remainingMs", softCooldownRemainingMs);
                }
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.softCooldownActiveTrace", true);
                // fail-soft
            }
        }

        // MERGE_HOOK:PROJ_AGENT::QT_BYPASS_ON_FAILURE_PATTERN_COOLDOWN_V2
        if (!disabled && !breakerOpen && failurePatterns != null) {
            try {
                failureCooldown = failurePatterns.isCoolingDown("qtx");
            } catch (Exception ignore) {
                TraceStore.put("qtx.suppressed.failurePatternCooldown", true);
                // fail-soft
            }
        }

        String modeLabel = "NORMAL";
        if (ctx != null) {
            if (ctx.isBypassMode()) {
                modeLabel = "BYPASS";
            } else if (ctx.isStrikeMode()) {
                modeLabel = "STRIKE";
            } else if (ctx.isCompressionMode()) {
                modeLabel = "COMPRESSION";
            } else if (ctx.isCheapSearchMode() || cheapSearchScope) {
                modeLabel = "CHEAP_SEARCH";
            }
        }

        // MERGE_HOOK:PROJ_AGENT::STAGE_POLICY_CLAMP_QUERY_TRANSFORMER_V2
        if (!disabled && !breakerOpen) {
            try {
                if (stagePolicy != null && stagePolicy.isEnabled()) {
                    boolean enabled = stagePolicy.isStageEnabled(OrchStageKeys.QUERY_TRANSFORMER, modeLabel, true);
                    TraceStore.put("qtx.stagePolicy.enabled", enabled);
                    if (!enabled) {
                        stagePolicyClamped = true;
                        TraceStore.put("qtx.stagePolicy.clamped", true);
                    }
                }
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.stagePolicyTrace", true);
                // fail-soft
            }
        }

        AuxBlockedReason ctxReason = AuxBlockedReason.fromContext(ctx);
        boolean ctxBypass = ctx != null && ((ctx.isAuxDegraded() || ctx.isAuxHardDown())
                || ctx.isCheapSearchMode()
                || ctx.isStrikeMode() || ctx.isCompressionMode() || ctx.isBypassMode());
        ctxBypass = ctxBypass || cheapSearchScope;

        boolean bypass = disabled || breakerOpen || failureCooldown || stagePolicyClamped || ctxBypass || softCooldown;

        // NoiseGate: in COMPRESSION-mode bypass (false positives), allow a small escape
        // probability
        // to run the optional stage. This helps prevent deterministic quality cliffs.
        boolean noiseEscaped = false;
        double noiseEscapeP = 0.0;
        double noiseRoll = 1.0;
        if (bypass && !disabled && !breakerOpen && !failureCooldown && !stagePolicyClamped && !softCooldown
                && ctxBypass && ctxReason == AuxBlockedReason.COMPRESSION) {
            try {
                boolean stageNoiseEnabled = Boolean
                        .parseBoolean(System.getProperty("orch.noiseGate.qtx.compression.enabled", "true"));
                if (stageNoiseEnabled) {
                    double irr = (ctx != null) ? ctx.getIrregularityScore() : 0.0;
                    double max = Double
                            .parseDouble(System.getProperty("orch.noiseGate.qtx.compression.escapeP.max", "0.18"));
                    double min = Double
                            .parseDouble(System.getProperty("orch.noiseGate.qtx.compression.escapeP.min", "0.04"));
                    double t = Math.min(1.0, Math.max(0.0, (irr - 0.35) / 0.45));
                    double escapeP = max + (min - max) * t;

                    NoiseRoutingGate.GateDecision gd = NoiseRoutingGate.decideEscape("qtx.compression", escapeP, ctx);
                    noiseEscaped = gd.escape();
                    noiseEscapeP = gd.escapeP();
                    noiseRoll = gd.roll();

                    if (noiseEscaped) {
                        bypass = false;
                    }
                }
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.noiseGate", true);
                // fail-soft
            }
        }

        AuxBlockedReason reason = AuxBlockedReason.UNKNOWN;
        String trigger = "none";

        if (disabled) {
            reason = AuxBlockedReason.DISABLED;
            trigger = "disabled";
        } else if (breakerOpen) {
            reason = AuxBlockedReason.BREAKER_OPEN;
            trigger = "breakerOpen";
        } else if (cheapSearchScope) {
            reason = AuxBlockedReason.CHEAP_SEARCH_MODE;
            trigger = "forceLightSearchMode";
        } else if (stagePolicyClamped) {
            reason = AuxBlockedReason.STAGE_POLICY_CLAMP;
            trigger = "stagePolicyClamp";
        } else if (softCooldown) {
            reason = AuxBlockedReason.FAILURE_COOLDOWN;
            trigger = "qtxSoftCooldown";
        } else if (failureCooldown) {
            reason = AuxBlockedReason.FAILURE_COOLDOWN;
            trigger = "failureCooldown";
        } else if (ctxBypass && ctxReason != null && ctxReason != AuxBlockedReason.UNKNOWN) {
            reason = ctxReason;
            trigger = "guardMode";
        } else {
            // Secondary breadcrumbs to avoid UNKNOWN where we only have string signals.
            try {
                String br = (ctx == null) ? null : ctx.getBypassReason();
                if (br != null && !br.isBlank()) {
                    String b = br.toLowerCase(Locale.ROOT);
                    if (b.contains("faultmask")) {
                        reason = AuxBlockedReason.FAULTMASK_SIGNAL;
                        trigger = "ctxBypassReason";
                    } else if (b.contains("irregularity")) {
                        reason = AuxBlockedReason.IRREGULARITY_SIGNAL;
                        trigger = "ctxBypassReason";
                    }
                }
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.ctxReason", true);
                // best-effort
            }
        }

        if (noiseEscaped) {
            try {
                TraceStore.put("qtx.noiseEscape", true);
                TraceStore.put("qtx.noiseEscape.escapeP", noiseEscapeP);
                TraceStore.put("qtx.noiseEscape.roll", noiseRoll);
                // Backward/forward compatible aliases (some UI expects dotted form)
                TraceStore.put("qtx.noise.escape.used", true);
                TraceStore.put("qtx.noise.escape.escapeP", noiseEscapeP);
                TraceStore.put("qtx.noise.escape.roll", noiseRoll);
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.noiseEscapeTrace", true);
                // best-effort
            }

            try {
                java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
                meta.put("reason", (ctxReason == null ? null : ctxReason.code()));
                meta.put("trigger", trigger);
                meta.put("breakerKey", NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
                meta.put("escapeP", noiseEscapeP);
                meta.put("roll", noiseRoll);
                AuxBlockTracker.markStageNoiseOverride(
                        "queryTransformer",
                        "QueryTransformer.noiseEscape(" + trigger + ")",
                        noiseEscapeP,
                        meta);
            } catch (Throwable ignore) {
                TraceStore.put("qtx.suppressed.noiseOverrideTrace", true);
                // best-effort
            }

            trigger = "noiseEscape(" + trigger + ")";
        }

        String vector = "d=" + disabled
                + " b=" + breakerOpen
                + " c=" + failureCooldown
                + " sc=" + softCooldown
                + " p=" + stagePolicyClamped
                + " ctx=" + (ctxReason == null ? "null" : ctxReason.code());

        try {
            TraceStore.put("qtx.bypass", bypass);
            TraceStore.put("qtx.bypass.trigger", trigger);
            TraceStore.put("qtx.bypass.reason", reason.code());
            TraceStore.put("qtx.bypass.modeLabel", modeLabel);
            TraceStore.put("qtx.bypass.vector", vector);
            if (bypass) {
                String safeReasonCode = SafeRedactor.traceLabelOrFallback(reason.code(), "unknown");
                TraceStore.putIfAbsent("queryTransformer.bypassed", Boolean.TRUE);
                TraceStore.putIfAbsent("queryTransformer.reason", safeReasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded", Boolean.TRUE);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.reason", safeReasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.trigger", trigger);
                TraceStore.inc("aux.queryTransformer.degraded.count");
            }
        } catch (Throwable ignore) {
            TraceStore.put("qtx.suppressed.bypassTrace", true);
            // tracing is best-effort
        }

        if ((bypass || reason != AuxBlockedReason.UNKNOWN) && log.isDebugEnabled()) {
            log.debug("[QTX] auxLlm decision bypass={} trigger={} reason={} mode={} vector={}",
                    bypass, trigger, reason.code(), modeLabel, vector);
        }

        return new BypassDecision(bypass, reason, trigger, modeLabel, breakerOpen, failureCooldown, stagePolicyClamped);
    }

    private boolean shouldBypassAuxLlm() {
        return computeBypassDecision().bypass();
    }

    private AuxBlockedReason bypassReason() {
        return computeBypassDecision().reason();
    }

    private void markAuxDown(String reason) {
        GuardContext ctx = GuardContextHolder.get();
        if (ctx == null)
            return;
        if (!ctx.isAuxDown()) {
            ctx.setAuxDown(true);
        }
        String prev = ctx.getBypassReason();
        if (prev == null || prev.isBlank()) {
            ctx.setBypassReason(reason);
        }
    }

    /** LangChain4j 표준 메시지 호출로 LLM을 실행 */
    private String runLLM(String prompt) {
        final String breakerKey = NightmareKeys.QUERY_TRANSFORMER_RUN_LLM;

        // 1) Breaker 체크: OPEN이면 즉시 우회
        // Prompt-length guard (fail-soft): if the prompt is too large, bypass aux LLM
        // to avoid HttpTimeoutException -> breaker-open cascades.
        if (prompt == null || prompt.isBlank()) {
            TraceStore.put("qtx.prompt.empty", true);
            return "";
        }
        if (llmMaxPromptChars > 0 && prompt.length() > llmMaxPromptChars) {
            String reasonCode = "prompt_len";
            String safeReasonCode = SafeRedactor.traceLabelOrFallback(reasonCode, "unknown");
            AuxDownTracker.markSoft("query-transformer:runLLM", reasonCode);
            try {
                TraceStore.putIfAbsent("qtx.prompt.sha1",
                        com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                TraceStore.putIfAbsent("qtx.prompt.len", prompt.length());
                TraceStore.putIfAbsent("aux.queryTransformer", "degraded:" + safeReasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded", Boolean.TRUE);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.reason", safeReasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.trigger", "prompt_len_guard");
                TraceStore.inc("aux.queryTransformer.degraded.count");
            } catch (Throwable ignore) {
                traceSuppressed("promptTooLong.degradedTrace");
            }
            TraceStore.put("qtx.prompt.tooLong", true);
            TraceStore.put("qtx.prompt.tooLong.len", prompt.length());
            TraceStore.put("qtx.prompt.tooLong.max", llmMaxPromptChars);
            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("key", breakerKey);
                dd.put("promptLen", prompt.length());
                dd.put("maxPromptChars", llmMaxPromptChars);
                dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                emitQtx(
                        DebugEventLevel.INFO,
                        "qtx.prompt_too_long",
                        "QueryTransformer bypass: prompt too long (fail-soft)",
                        "QueryTransformer.runLLM",
                        dd,
                        null);
            } catch (Throwable ignore) {
                traceSuppressed("promptTooLong.debug");
            }
            return "";
        }
        NightmareBreaker.CallPermit permit = null;
        if (nightmareBreaker != null) {
            try {
                permit = nightmareBreaker.acquire(breakerKey, "query-transformer-run-llm");
            } catch (NightmareBreaker.OpenCircuitException e) {
                // MERGE_HOOK:PROJ_AGENT::QT_BREAKEROPEN_SOFT_V1
                AuxDownTracker.markSoft("query-transformer:runLLM", "breaker-open");
                TraceStore.putIfAbsent("aux.queryTransformer", "degraded:breaker_open");
                TraceStore.putIfAbsent("aux.queryTransformer.degraded", Boolean.TRUE);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.reason", "breaker_open");
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.trigger", "nightmare_open");
                TraceStore.inc("aux.queryTransformer.degraded.count");
                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    dd.put("key", breakerKey);
                    dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                    dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                    emitQtx(
                            DebugEventLevel.INFO,
                            "qtx.breaker_open",
                            "QueryTransformer bypass: NightmareBreaker open",
                            "QueryTransformer.runLLM",
                            dd,
                            e);
                } catch (Throwable ignore) {
                    traceSuppressed("breakerOpen.debug");
                }
                return "";
            }
        }
        long started = System.nanoTime();
        String queryTransformerPromptForMessage = prompt;
        try {
            String text = chatModel.chat(List.of(
                    SystemMessage.from("""
                            간결하고 한 줄로만 응답하세요.
                            - 고유명사에 대해 확실히 알지 못하는 속성은 절대로 추측하거나 창작하지 마세요.
                            - 모르는 경우 원문의 표현만 약간 정리하거나, 검색에 도움이 되는 일반적인 키워드만 제안하세요.
                            - 존재하지 않는 조직명, 집단, 세계관 설정을 만들어내지 마세요.
                            """),
                    UserMessage.from(queryTransformerPromptForMessage))).aiMessage().text();

            // 빈 응답은 "옵션(QTX) 단계"에서는 soft-failure로 처리:
            // - breaker 과민 트립을 막고
            // - 짧은 soft-cooldown으로 step-down 라우팅
            if (text == null || text.isBlank()) {
                if (permit != null) {
                    permit.completeBlank("query-transformer-run-llm");
                }
                AuxDownTracker.markSoft("query-transformer:runLLM", "blank");
                startQtxSoftCooldown("blank", NightmareBreaker.FailureKind.UNKNOWN, null,
                        Math.max(0L, (qtxSoftCooldownBaseMs > 0L ? qtxSoftCooldownBaseMs : llmTimeoutOpenHintMs)));
                try {
                    TraceStore.putIfAbsent("qtx.blank", true);
                    TraceStore.inc("qtx.blank.count");
                } catch (Throwable ignore) {
                    traceSuppressed("blank.trace");
                }
                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    dd.put("key", breakerKey);
                    dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                    dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                    dd.put("cooldownMs", Math.max(0L, (qtxSoftCooldownBaseMs > 0L ? qtxSoftCooldownBaseMs : llmTimeoutOpenHintMs)));
                    emitQtx(
                            DebugEventLevel.WARN,
                            "qtx.blank",
                            "QueryTransformer blank response (soft-failure, step-down)",
                            "QueryTransformer.runLLM",
                            dd,
                            null);
                } catch (Throwable ignore) {
                    traceSuppressed("blank.debug");
                }
                return "";
            }

            // FriendShield(회피/사과/정보없음) 패턴은 QTX에서 silent-failure로 간주:
            // breaker를 열지 않고 soft-cooldown으로 잠시 우회한다.
            if (FriendShieldPatternDetector.looksLikeSilentFailure(text)) {
                if (permit != null) {
                    permit.completeSilentFailure("query-transformer-run-llm", "friendshield");
                }
                AuxDownTracker.markSoft("query-transformer:runLLM", "friendshield");
                startQtxSoftCooldown("friendshield", NightmareBreaker.FailureKind.UNKNOWN, null,
                        Math.max(0L, (qtxSoftCooldownBaseMs > 0L ? qtxSoftCooldownBaseMs : llmTimeoutOpenHintMs)));
                try {
                    TraceStore.putIfAbsent("qtx.friendshield", true);
                    TraceStore.inc("qtx.friendshield.count");
                } catch (Throwable ignore) {
                    traceSuppressed("friendshield.trace");
                }
                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    dd.put("key", breakerKey);
                    dd.put("reason", "friendshield");
                    dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                    dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                    dd.put("cooldownMs", Math.max(0L, (qtxSoftCooldownBaseMs > 0L ? qtxSoftCooldownBaseMs : llmTimeoutOpenHintMs)));
                    emitQtx(
                            DebugEventLevel.WARN,
                            "qtx.friendshield",
                            "QueryTransformer silent-failure pattern (friendshield, soft step-down)",
                            "QueryTransformer.runLLM",
                            dd,
                            null);
                } catch (Throwable ignore) {
                    traceSuppressed("friendshield.debug");
                }
                return "";
            }

            if (permit != null) {
                permit.completeSuccess(elapsedMs(started));
                // Success: clear soft-cooldown streak so we can recover quickly.
                try {
                    qtxSoftCooldownStreak.set(0);
                } catch (Throwable ignore) {
                    traceSuppressed("cooldownStreakReset");
                }
            }
            return text;
        } catch (Exception e) {
            // QueryTransformer는 '옵션' 전처리 단계이므로, 여기서 발생한 Interrupted를
            // 요청 스레드에 전파(재-interrupt)하면 이후 파이프라인이 연쇄적으로 깨질 수 있다.
            // (Error_ws.txt에서 관찰된 케이스: InterruptedException → breaker OPEN)
            // 따라서 LLM 실패는 분류/기록만 하고, 호출자는 원문 쿼리로 계속 진행하도록 한다.
            NightmareBreaker.FailureKind kind = classifyLlmFailure(e);

            // UAW: surface "model is required" (schema/config mismatch) explicitly.
            if (kind == NightmareBreaker.FailureKind.CONFIG) {
                try {
                    String baseUrl = com.example.lms.llm.OpenAiCompatBaseUrl.sanitize(fastBaseUrl);
                    String model = (fastModelName == null ? "" : fastModelName.trim());
                    String curlHint = buildCurlHint(baseUrl, model);

                    TraceStore.put("qtx.llm.error.code", "MODEL_REQUIRED");
                    TraceStore.put("qtx.llm.fastBaseUrlHash", SafeRedactor.hashValue(baseUrl)); TraceStore.put("qtx.llm.fastBaseUrlLength", baseUrl == null ? 0 : baseUrl.length());
                    TraceStore.put("qtx.llm.fastModelHash", SafeRedactor.hashValue(model)); TraceStore.put("qtx.llm.fastModelLength", model == null ? 0 : model.length());
                    TraceStore.put("qtx.llm.curlHintHash", SafeRedactor.hashValue(curlHint)); TraceStore.put("qtx.llm.curlHintLength", curlHint == null ? 0 : curlHint.length());

                    log.error(
                            "[QueryTransformer] LLM rejected request (model is required). fastBaseUrlHash={} fastBaseUrlLength={} fastModelHash={} fastModelLength={} curlHintHash={} curlHintLength={}",
                            SafeRedactor.hashValue(baseUrl), baseUrl == null ? 0 : baseUrl.length(), SafeRedactor.hashValue(model), model == null ? 0 : model.length(), SafeRedactor.hashValue(curlHint), curlHint == null ? 0 : curlHint.length());

                    try {
                        java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                        dd.put("key", breakerKey);
                        dd.put("kind", kind.name());
                        dd.put("fastBaseUrlHash", SafeRedactor.hashValue(baseUrl)); dd.put("fastBaseUrlLength", baseUrl == null ? 0 : baseUrl.length());
                        dd.put("fastModelHash", SafeRedactor.hashValue(model)); dd.put("fastModelLength", model == null ? 0 : model.length());
                        dd.put("curlHintHash", SafeRedactor.hashValue(curlHint)); dd.put("curlHintLength", curlHint == null ? 0 : curlHint.length());
                        dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                        dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                        emitQtx(
                                DebugEventLevel.ERROR,
                                "qtx.config.model_required",
                                "QueryTransformer LLM config mismatch: model is required",
                                "QueryTransformer.runLLM",
                                dd,
                                e);
                    } catch (Throwable ignore2) {
                        traceSuppressed("config.debug");
                    }
                } catch (Throwable ignore) {
                    traceSuppressed("config.trace");
                }
            }

            // MERGE_HOOK:PROJ_AGENT::QT_INTERRUPT_CANCEL_SILENT_V1
            if (kind == NightmareBreaker.FailureKind.INTERRUPTED) {
                if (permit != null) {
                    permit.completeCancelled(e, "query-transformer-run-llm");
                }
                // Preserve the cancellation signal until this owned task exits. The pool clears
                // worker state at its task boundary before reusing the thread.
                Thread.currentThread().interrupt();
                // QueryTransformer는 옵션 단계이며, interrupt는 대부분 내부 취소/정리 신호다.
                // breaker/faultmask를 중복 기록하지 않고 조용히 우회한다.
                AuxDownTracker.markSoft("query-transformer:runLLM", "canceled");
                try {
                    TraceStore.put("aux.queryTransformer", "canceled");
                    TraceStore.put("queryTransformer.canceled", true);
                } catch (Exception ignore) {
                    traceSuppressed("canceled.trace");
                }

                try {
                    java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                    dd.put("key", breakerKey);
                    dd.put("kind", kind.name());
                    dd.put("rootType",
                            (unwrap(e) != null ? unwrap(e).getClass().getSimpleName() : e.getClass().getSimpleName()));
                    dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                    dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                    emitQtx(
                            DebugEventLevel.WARN,
                            "qtx.canceled",
                            "QueryTransformer canceled/interrupted (fail-soft bypass)",
                            "QueryTransformer.runLLM",
                            dd,
                            e);
                } catch (Throwable ignore) {
                    traceSuppressed("canceled.debug");
                }
                return "";
            }

            if (faultMaskingLayerMonitor != null) {
                faultMaskingLayerMonitor.record("query-transformer:runLLM", e,
                        "promptSha1=" + com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt),
                        "caught-and-fallback");
            }

            boolean modelLoading = looksLikeModelLoading(e);
            if (isSoftTransientForQtx(kind, modelLoading)) {
                if (permit != null) {
                    Long hintMs = (kind == NightmareBreaker.FailureKind.TIMEOUT && llmTimeoutOpenHintMs > 0)
                            ? llmTimeoutOpenHintMs
                            : null;
                    permit.completeFailure(kind, e, "query-transformer-run-llm", hintMs);
                }
                long cdMs = qtxSoftCooldownMsFor(kind, modelLoading, qtxSoftCooldownBaseMs, llmTimeoutOpenHintMs);
                startQtxSoftCooldown(
                        modelLoading ? "model_loading" : ("soft_" + kind.name().toLowerCase(java.util.Locale.ROOT)),
                        kind, e, cdMs);
                AuxDownTracker.markSoft("query-transformer:runLLM",
                        "soft-" + kind.name().toLowerCase(java.util.Locale.ROOT));
                TraceStore.put("qtx.softFailure", true);
                TraceStore.put("qtx.softFailure.kind", kind.name());
                TraceStore.put("qtx.softFailure.modelLoading", modelLoading);
                TraceStore.put("qtx.softFailure.cooldownMs", cdMs);
                return "";
            }

            if (permit != null) {
                Long hintMs = (kind == NightmareBreaker.FailureKind.TIMEOUT && llmTimeoutOpenHintMs > 0)
                        ? llmTimeoutOpenHintMs
                        : null;
                permit.completeFailure(kind, e, "query-transformer-run-llm", hintMs);
            }

            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("key", breakerKey);
                dd.put("kind", kind.name());
                Throwable root = unwrap(e);
                dd.put("rootType", (root != null ? root.getClass().getName() : e.getClass().getName()));
                dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                emitQtx(
                        DebugEventLevel.WARN,
                        "qtx.runLLM.failure",
                        "QueryTransformer LLM failure (fail-soft)",
                        "QueryTransformer.runLLM",
                        dd,
                        e);
            } catch (Throwable ignore) {
                traceSuppressed("runLlmFailure.debug");
            }
            return "";
        }
    }

    public List<String> transform(String context, String normalizedQuery) {
        // NOTE: List.of(..) rejects null elements. For null/blank input we must
        // fail-soft
        // and return an empty list (caller should fall back to a base query).
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            // [PATCH src111_merge15/merge15] normalizedQuery can become blank under
            // failure-cooldown/normalization guards. Recover from GuardContext.userQuery
            // so we always have at least one usable variant.
            String recovered = null;
            try {
                com.example.lms.service.guard.GuardContext gc = com.example.lms.service.guard.GuardContextHolder
                        .getOrDefault();
                recovered = (gc == null) ? null : gc.getUserQuery();
            } catch (Throwable ignore) {
                traceSuppressed("normalized.recoverContext");
            }
            if (recovered == null || recovered.isBlank()) {
                try {
                    TraceStore.put("qtx.normalized.blank", true);
                } catch (Throwable ignore) {
                    traceSuppressed("normalized.blankTrace");
                }
                return List.of();
            }
            normalizedQuery = recovered;
            try {
                TraceStore.put("qtx.normalized.blankRecovered", true);
                TraceStore.put("qtx.normalized.blankRecovered.source", "guardContext.userQuery");
            } catch (Throwable ignore) {
                traceSuppressed("normalized.recoveredTrace");
            }

            // Debug timeline event (shows up in evidence-list diagnostics).
            try {
                OrchTrace.appendEvent(OrchTrace.newEvent(
                        "aux", "queryTransformer", "normalizedRecovered",
                        recoveredQueryTraceData("guardContext.userQuery", recovered)));
            } catch (Throwable ignore) {
                traceSuppressed("normalized.orchTrace");
            }
        }
        // 1) 알파벳숫자 복합 토큰(K8Plus, A7X 등)을 그대로 묶어 모호성 감소
        if (shouldSkipAuxForDiagnosticSmoke(normalizedQuery) || isDiagnosticSmokeScopeActive()) {
            traceDiagnosticSmokeSkip(normalizedQuery);
            return List.of(normalizedQuery.trim());
        }
        if (isCheapSearchModeScopeActive()) {
            traceCheapSearchModeSkip(normalizedQuery);
            return List.of(normalizedQuery.trim());
        }
        String preProcessed = preserveCompoundTokens(normalizedQuery.trim());
        final String constraintSource = normalizedQuery.trim();
        String q = dict.getOrDefault(preProcessed, preProcessed);
        /* ① LLM 맞춤법 교정 */
        q = correctWithLLM(context, q);

        /* ② LLM 다중-제안(최대 3개) 불필요 변형 필터링 */
        List<String> variants = QueryTransformerVariantSupport.filterUnwantedVariants(
                generateVariantsWithLLM(q), normalizedQuery);

        /* ③ 원본·교정·변형 합치기 */
        List<String> out = Stream.concat(Stream.of(normalizedQuery, q), variants.stream())
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .filter(s -> preservesQueryConstraints(constraintSource, s))
                .distinct()
                .toList();
        // 유사 문장(구두점/띄어쓰기만 다른 케이스) 제거
        List<String> dedup = dedupBySimilarity(out, 0.86);
        if (dedup == null || dedup.isEmpty()) {
            // Guarantee at least one variant so downstream retrieval never collapses.
            return List.of(normalizedQuery.trim());
        }
        return dedup;
    }

    /** 연속된 영문·숫자(선행·후행 소문자 suffix 포함)를 하나의 구로 래핑 */
    private static final Pattern COMPOUND_TOKEN = Pattern.compile("(?i)\\b([a-z]{1,4}\\d+[a-z]*|\\d+[a-z]{1,4})\\b");

    private String preserveCompoundTokens(String in) {
        Matcher m = COMPOUND_TOKEN.matcher(in);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "\"" + m.group(1) + "\"");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean shouldSkipAuxForDiagnosticSmoke(String query) {
        if (query == null) {
            return false;
        }
        String q = query.trim();
        if (q.isEmpty()) {
            return false;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        boolean diagnostic = lower.contains("스모크")
                || lower.contains("smoke")
                || lower.contains("heartbeat")
                || lower.contains("헬스체크")
                || lower.contains("health check")
                || lower.contains("디버그")
                || lower.contains("debug");
        boolean localUiOrRoute = lower.contains("ui")
                || lower.contains("브라우저")
                || lower.contains("browser")
                || lower.contains("챗봇")
                || lower.contains("chat")
                || lower.contains("rag/auto")
                || lower.contains("설정")
                || lower.contains("상태")
                || lower.contains("경로")
                || lower.contains("route");
        return diagnostic && localUiOrRoute && q.length() <= 140;
    }

    private static void traceDiagnosticSmokeSkip(String query) {
        try {
            TraceStore.put("aux.queryTransformer.diagnosticSmokeScope", Boolean.TRUE);
            TraceStore.put("aux.queryTransformer.skipped", Boolean.TRUE);
            TraceStore.put("aux.queryTransformer.skipReason", "diagnostic_smoke");
            TraceStore.put("aux.queryTransformer.skipQueryHash", SafeRedactor.hashValue(query));
            TraceStore.put("aux.queryTransformer.skipQueryLength", query == null ? 0 : query.length());
            TraceStore.put("queryTransformer.bypassed", Boolean.TRUE);
            TraceStore.put("queryTransformer.reason", "diagnostic_smoke");
        } catch (Throwable ignore) {
            traceSuppressed("diagnosticSmoke.skipTrace", ignore);
        }
    }

    private static boolean isCheapSearchModeScopeActive() {
        return isCheapSearchModeScopeActive(null);
    }

    private static boolean isCheapSearchModeScopeActive(@Nullable GuardContext ctx) {
        try {
            if (ctx != null && ctx.isCheapSearchMode()) {
                return true;
            }
            GuardContext current = GuardContextHolder.get();
            if (current != null && current.isCheapSearchMode()) {
                return true;
            }
        } catch (Throwable ignore) {
            traceSuppressed("cheapSearchMode.contextRead", ignore);
        }
        try {
            Object value = TraceStore.get("search.mode.lightAuxBypass");
            return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
        } catch (Throwable ignore) {
            traceSuppressed("cheapSearchMode.traceRead", ignore);
            return false;
        }
    }

    private static void traceCheapSearchModeSkip(String query) {
        try {
            TraceStore.put("aux.queryTransformer.skipped", Boolean.TRUE);
            TraceStore.put("aux.queryTransformer.skipReason", "cheap-search-mode");
            TraceStore.put("aux.queryTransformer.skipQueryHash", SafeRedactor.hashValue(query));
            TraceStore.put("aux.queryTransformer.skipQueryLength", query == null ? 0 : query.length());
            TraceStore.put("queryTransformer.bypassed", Boolean.TRUE);
            TraceStore.put("queryTransformer.reason", "cheap-search-mode");
            TraceStore.put("qtx.bypass", Boolean.TRUE);
            TraceStore.put("qtx.bypass.trigger", "forceLightSearchMode");
            TraceStore.put("qtx.bypass.reason", "cheap-search-mode");
            TraceStore.put("qtx.bypass.modeLabel", "CHEAP_SEARCH");
        } catch (Throwable ignore) {
            traceSuppressed("cheapSearchMode.skipTrace", ignore);
        }
    }

    private static boolean isDiagnosticSmokeScopeActive() {
        try {
            Object value = TraceStore.get("aux.queryTransformer.diagnosticSmokeScope");
            return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
        } catch (Throwable ignore) {
            traceSuppressed("diagnosticSmoke.scopeRead", ignore);
            return false;
        }
    }

    /** LLM 한 번 호출해 맞춤법을 교정한다 */
    private String correctWithLLM(String ctx, String q) {
        try {
            // Build the correction prompt using the centralised prompt builder
            String correctionPrompt = QUERY_KEYWORD_PROMPT_BUILDER.buildCorrectionPrompt(q);

            // 캐시를 먼저 조회하고 없으면 채운다.
            String ans = cachedLlm(correctionPrompt);
            ans = cleanUp(ans); // 불필요 토큰 제거

            /* +콜론/화살표 구분이 여전히 남아 있으면 오른쪽만 취함 */
            if (ans != null) {
                ans = ans.replaceFirst("(?i)^(?:corrected(?: query)?|correction|교정|수정)\\s*[:：]\\s*", "");
                ans = ans.replaceFirst("^.+?\\s+(?:->|→|>)\\s+", "");
            }
            return preservesQueryConstraints(q, ans) ? ans : q;
        } catch (Exception e) {
            traceSuppressed("correction.fallback", e);
            return q; // 실패 시 원본 유지
        }
    }

    /** LLM이 제시한 추가 검색어(최대 3개)를 반환 - 실패 시 빈 리스트 */
    private List<String> generateVariantsWithLLM(String q) {
        return generateVariantsWithLLM(q, null);
    }

    // ───────────────────────────────────────
    // CognitiveState 기반 확장
    // ───────────────────────────────────────
    public List<String> expandWithCognitiveState(PromptContext ctx, String baseQuery) {
        CognitiveState cs = ctx == null ? null : ctx.cognitiveState();
        if (cs == null)
            return generateVariantsWithLLM(baseQuery, ctx == null ? null : ctx.subject());
        String subject = ctx.subject();
        // Build the cognitive variants prompt via the prompt builder
        String cognitivePrompt = QUERY_KEYWORD_PROMPT_BUILDER
                .buildCognitiveVariantsPrompt(cs, subject, baseQuery, MAX_VARIANTS);
        String ans = cachedLlm(cognitivePrompt);
        if (ans == null || ans.isBlank()) {
            return generateVariantsWithLLM(baseQuery, subject);
        }
        List<String> raw = Arrays.stream(ans.split("\\r?\\n"))
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .limit(MAX_VARIANTS)
                .toList();
        // 의도 버프 얹기
        QueryIntent intent = classifyIntent(baseQuery);
        List<String> buffed = raw.stream().map(q -> boostWithIntent(q, intent))
                .filter(q -> preservesQueryConstraints(baseQuery, q)).toList();
        if (buffed.isEmpty()) return List.of(baseQuery);
        return dedupBySimilarity(buffed, 0.86);
    }

    /** ✨ subject 앵커 지원 버전 */
    private List<String> generateVariantsWithLLM(String q, @Nullable String subject) {
        try {
            // Build the keyword variants prompt using the prompt builder
            String variantPrompt = QUERY_KEYWORD_PROMPT_BUILDER
                    .buildKeywordVariantsPrompt(q, subject, MAX_VARIANTS);
            String ans = cachedLlm(variantPrompt);
            if (ans == null || ans.isBlank()) {
                // LLM이 응답하지 못했으면 deterministic cheap-path로 우회
                return fallbackVariants(q, subject);
            }

            List<String> raw = Arrays.stream(ans.split("\r?\n"))
                    .map(this::cleanUp)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());

            return QueryTransformerVariantSupport.selectLlmVariants(q, subject, raw, MAX_VARIANTS).stream()
                    .filter(v -> preservesQueryConstraints(q, v)).toList();
        } catch (Exception e) {
            traceSuppressed("variants.fallback", e);
            return fallbackVariants(q, subject);
        }
    }

    private List<String> fallbackVariants(String q, @Nullable String subject) {
        List<String> variants = QueryTransformerVariantSupport.cheapVariantsFallback(
                q,
                subject,
                novaOrchEnabled && novaOrchQueryTransformerCheapEnabled,
                anchorNarrower,
                cheapVariants,
                MAX_VARIANTS,
                QueryTransformer::recoveredQueryTraceData);
        return variants.stream().filter(v -> preservesQueryConstraints(q, v)).toList();
    }

    // ─────────────────────────────────────────────────────────────
    // 새 API: 사용자 질의 + GPT 답변에서 힌트를 섞어 검색용 다중 쿼리 생성
    // ─────────────────────────────────────────────────────────────
    public List<String> transformEnhanced(String userPrompt, @Nullable String assistantAnswer) {
        // 하위호환: subject 없이 호출되면 null로 위임
        return transformEnhanced(userPrompt, assistantAnswer, null);
    }

    /** ✨ Subject 앵커 지원 오버로드 */
    public List<String> transformEnhanced(String userPrompt,
            @Nullable String assistantAnswer,
            @Nullable String subject) {
        String p = defaultString(userPrompt);
        if (p == null || p.isBlank()) {
            try {
                com.example.lms.service.guard.GuardContext gc = com.example.lms.service.guard.GuardContextHolder
                        .getOrDefault();
                String uq = (gc == null) ? null : gc.getUserQuery();
                if (uq != null && !uq.isBlank()) {
                    p = uq.trim();
                    TraceStore.put("qtx.userPrompt.recovered", true);
                    TraceStore.put("qtx.userPrompt.recovered.source", "guardContext.userQuery");
                    try {
                        OrchTrace.appendEvent(OrchTrace.newEvent(
                                "aux", "queryTransformer", "userPromptRecovered",
                                recoveredQueryTraceData("guardContext.userQuery", p)));
                    } catch (Throwable ignore) {
                        traceSuppressed("userPrompt.recoveredOrchTrace");
                    }
                }
            } catch (Throwable ignore) {
                traceSuppressed("userPrompt.recoverContext");
            }
        }

        // 람다에서 사용할 effectively final 변수
        final String finalP = p;

        // ✅ base 변환을 먼저 수행 (여기서 soft-timeout이 나면 auxDown이 찍힘)
        if (p == null || p.isBlank()) {
            try {
                TraceStore.put("qtx.userPrompt.blank", true);
                TraceStore.put("qtx.userPrompt.blank.reason", "no_recovered_query");
            } catch (Throwable ignore) {
                traceSuppressed("userPrompt.blankTrace");
            }
            return List.of();
        }
        if (shouldSkipAuxForDiagnosticSmoke(p) || isDiagnosticSmokeScopeActive()) {
            traceDiagnosticSmokeSkip(p);
            return List.of(p.trim());
        }

        List<String> baseRaw = transform("", p);
        boolean bypassExtras = shouldBypassAuxLlm();
        if (bypassExtras) {
            String raw = p.trim();
            String reason = SafeRedactor.traceLabelOrFallback(TraceStore.get("qtx.bypass.trigger"), "bypass");
            TraceStore.putIfAbsent("queryTransformer.rawFallback", Boolean.TRUE);
            TraceStore.putIfAbsent("queryTransformer.rawFallback.reason", reason);
            TraceStore.putIfAbsent("queryTransformer.rawFallback.queryHash12", SafeRedactor.hash12(raw));
            TraceStore.putIfAbsent("queryTransformer.rawFallback.queryLength", raw.length());
            if (!raw.isEmpty() && (baseRaw == null || baseRaw.stream().noneMatch(raw::equals))) {
                List<String> withRaw = new ArrayList<>();
                withRaw.add(raw);
                if (baseRaw != null) {
                    withRaw.addAll(baseRaw);
                }
                baseRaw = withRaw;
            }
        }

        // bypassExtras=true면 intent 분류, subQuery 생성 등 추가 LLM 호출 스킵
        QueryIntent intent = bypassExtras ? QueryIntent.GENERAL_KNOWLEDGE : classifyIntent(p);
        Set<String> promptTokens = QueryTransformerVariantSupport.tokens(p);

        List<String> base = (baseRaw == null ? List.<String>of() : baseRaw).stream()
                .map(q -> boostWithIntent(q, intent))
                .toList();

        boolean complex = !bypassExtras && isComplex(p);
        List<String> subQs = complex ? generateSubQueries(p) : List.of();

        List<String> boosted = (assistantAnswer == null ? Stream.<String>empty()
                : hintExtractor.extractHints(assistantAnswer).stream())
                .limit(MAX_HINTS)
                .map(this::cleanUp)
                .filter(h -> !Collections.disjoint(QueryTransformerVariantSupport.tokens(h), promptTokens))
                .map(h -> boostWithIntent(h, intent))
                .collect(Collectors.toList());

        List<String> merged = Stream.of(base, subQs, boosted)
                .flatMap(Collection::stream)
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .filter(s -> !QueryTransformerVariantSupport.hasDomainScopePrefix(s))
                .filter(s -> QueryTransformerVariantSupport.allowedByUnwantedTerm(s, finalP))
                .filter(s -> QueryTransformerVariantSupport.overlapsSubject(s, subject))
                .filter(s -> preservesQueryConstraints(finalP, s))
                .distinct()
                .toList();

        return merged.isEmpty()
                ? List.of(p.trim())
                : dedupBySimilarity(merged, 0.86);
    }

    private String cleanUp(String s) {
        if (s == null)
            return null;
        String t = s;
        // Apply unified noise clipping before removing prefixes and quotes. This
        // handles common polite suffixes and duplicates across multiple call
        // sites. When the clipper is not available the original string is used.
        if (noiseClipper != null) {
            t = noiseClipper.clip(t);
        }
        t = CLEANUP_PREFIX_NUM.matcher(t).replaceFirst("");
        t = CLEANUP_PREFIX_BULLET.matcher(t).replaceFirst("");
        t = CLEANUP_META.matcher(t).replaceFirst("");
        t = CLEANUP_SPACES.matcher(t).replaceAll(" ");
        t = CLEANUP_QUOTES.matcher(t).replaceAll("");
        return t.trim();
    }

    /*
     * ────────────────────────────────────────
     * 2. Intent-aware 키워드 버프
     * ────────────────────────────────────────
     */
    /*
     * ─────────────────────────────────────────
     * 동적 버프 생성 - intent 문맥을 LLM에 질문
     * ─────────────────────────────────────────
     */
    private String boostWithIntent(String q, QueryIntent intent) {
        List<String> buffs = generateDynamicBuffs(q, intent);
        return buffs.isEmpty() ? q : (q + " " + String.join(" ", buffs));
    }

    private List<String> generateDynamicBuffs(String base, QueryIntent intent) {
        // Build the intent buff prompt using the prompt builder
        String intentBuffPrompt = QUERY_KEYWORD_PROMPT_BUILDER
                .buildIntentBuffPrompt(base, intent, MAX_DYNAMIC_BUFFS);
        String ans = cachedLlm(intentBuffPrompt);
        if (ans == null || ans.isBlank())
            return List.of();

        return Arrays.stream(ans.split("\\r?\\n"))
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .limit(MAX_DYNAMIC_BUFFS)
                .toList();
    }

    /*
     * ────────────────────────────────────────
     * 3. Intent 분류 LLM 호출
     * ────────────────────────────────────────
     */
    private QueryIntent classifyIntent(String query) {
        if (query == null || query.isBlank())
            return QueryIntent.GENERAL_KNOWLEDGE;
        // 알파벳·숫자 혼합 모델명(K8Plus 등)이 포함되면 제품-스펙으로 우선 분류
        if (COMPOUND_TOKEN.matcher(query).find()) {
            return QueryIntent.PRODUCT_SPEC;
        }
        // Build the classification prompt using the prompt builder
        String intentClassificationPrompt = QUERY_KEYWORD_PROMPT_BUILDER.buildIntentClassificationPrompt(query);
        String result = cachedLlm(intentClassificationPrompt);
        if (result == null || result.isBlank())
            return QueryIntent.GENERAL_KNOWLEDGE;
        try {
            return QueryIntent.valueOf(result.trim()
                    .replaceAll("[^A-Za-z_]", "")
                    .toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            traceSuppressed("classifyIntent.fallback", e);
            return QueryIntent.GENERAL_KNOWLEDGE; // fallback
        }
    }

    /*
     * ────────────────────────────────────────
     * 4. 복합 질문 감지 & 세부 쿼리 분해
     * ────────────────────────────────────────
     */
    private boolean isComplex(String q) {
        if (q == null)
            return false;
        // 쉼표·그리고·및 등으로 두 토픽 이상이면 복합
        return q.split("(,|그리고|및)").length >= 2 || q.length() > 40;
    }

    private List<String> generateSubQueries(String question) {
        // Build the sub queries prompt using the prompt builder
        String subQueriesPrompt = QUERY_KEYWORD_PROMPT_BUILDER.buildSubQueriesPrompt(question);
        String ans = cachedLlm(subQueriesPrompt);
        if (ans == null || ans.isBlank()) {
            return QueryTransformerSubQueryFallback.threeAxisFallback(
                    question,
                    "blank-llm-response",
                    3);
        }
        List<String> parsed = QueryTransformerSubQueryFallback.refineParsedSubQueries(
                Arrays.stream(ans.split("\\r?\\n"))
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .toList(),
                question,
                "llm-response",
                3);
        if (parsed.isEmpty()) {
            return QueryTransformerSubQueryFallback.threeAxisFallback(
                    question,
                    "empty-llm-lines",
                    3);
        }
        return parsed;
    }

    private static String defaultString(String s) {
        return (s == null) ? "" : s;
    }

    /*
     * ────────────────────────────────────────
     * 유사도 기반 중복 제거(Jaccard on tokens)
     * ────────────────────────────────────────
     */
    private List<String> dedupBySimilarity(List<String> inputs, double threshold) {
        List<String> kept = new ArrayList<>();
        List<Set<String>> keptTokens = new ArrayList<>();
        for (String s : inputs) {
            Set<String> tok = tokenize(s);
            boolean similar = false;
            for (Set<String> kt : keptTokens) {
                if (jaccard(kt, tok) >= threshold) {
                    similar = true;
                    break;
                }
            }
            if (!similar) {
                kept.add(s);
                keptTokens.add(tok);
            }
        }
        return kept;
    }

    /** ko/en 주어 미포함 쿼리에 앵커 보정 삽입 */
    public static List<String> sanitizeAnchored(
            List<String> input, int max, double jaccardThreshold,
            String subjectKo, String subjectEn) {

        List<String> base = QueryHygieneFilter.sanitize(input, max, jaccardThreshold);
        if (base.isEmpty())
            return base;

        String ko = Objects.toString(subjectKo, "").trim();
        String en = Objects.toString(subjectEn, "").trim();

        return base.stream().map(q -> {
            String l = q.toLowerCase();
            boolean hasKo = !ko.isBlank() && l.contains(ko.toLowerCase());
            boolean hasEn = !en.isBlank() && l.contains(en.toLowerCase());
            if (hasKo || hasEn)
                return q;
            String add = (ko.isBlank() ? "" : ko + " ") + (en.isBlank() ? "" : "\"" + en + "\" ");
            return (add + q).trim();
        }).distinct().toList();
    }

    // ─────────────────────────────────────────────────────────────
    // Needle Probe (2-pass retrieval) helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Suggest authority site hints (domain names only).
     *
     * <p>
     * Output format: one domain per line (no scheme, no paths).
     * Example: {@code wikipedia.org}
     * </p>
     */
    public List<String> suggestAuthoritySites(String userPrompt, QueryDomain domain, Locale locale, int maxSites) {
        return QueryTransformerNeedlePlanner.suggestAuthoritySites(
                userPrompt,
                domain,
                locale,
                maxSites,
                QUERY_KEYWORD_PROMPT_BUILDER,
                this::cachedLlm);
    }

    /**
     * Generate a tiny set of probe queries (1~2) that are intentionally short and
     * high precision.
     *
     * <p>
     * Output format: one query per line, no numbering.
     * </p>
     */
    public List<String> generateNeedleProbeQueries(
            String userPrompt,
            QueryDomain domain,
            EvidenceSignals signals,
            List<String> siteHints,
            List<String> alreadyPlanned,
            Locale locale,
            int maxQueries) {
        return QueryTransformerNeedlePlanner.generateNeedleProbeQueries(
                userPrompt,
                domain,
                signals,
                siteHints,
                alreadyPlanned,
                locale,
                maxQueries,
                QUERY_KEYWORD_PROMPT_BUILDER,
                this::cachedLlm,
                this::cleanUp);
    }

    public record ParsedQuery(String subject,
            String intent,
            List<String> constraints) {
    }

    private Set<String> tokenize(String s) {
        if (s == null)
            return Set.of();
        String t = NON_ALNUM_KO.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (t.isEmpty())
            return Set.of();
        return Arrays.stream(t.split("\\s+"))
                .filter(w -> !w.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty())
            return 1.0;
        if (a.isEmpty() || b.isEmpty())
            return 0.0;
        int inter = 0;
        for (String x : a)
            if (b.contains(x))
                inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    private static Map<String, Object> recoveredQueryTraceData(String source, String query) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source == null ? "" : source);
        out.put("queryHash12", Optional.ofNullable(SafeRedactor.hash12(query)).orElse(""));
        out.put("queryLength", query == null ? 0 : query.length());
        return out;
    }

    private static void traceSuppressed(String stage) {
        traceSuppressed(stage, null);
    }

    private static boolean preservesQueryConstraints(String original, String candidate) {
        boolean preserved = SearchQueryConstraints.preserves(original, candidate);
        if (!preserved) {
            TraceStore.inc("qtx.constraints.rejectedCount");
            TraceStore.put("qtx.constraints.reason", "explicit_constraint_loss");
        }
        return preserved;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = failure == null
                ? safeStage
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
        try {
            TraceStore.put("queryTransformer.suppressed", true);
            TraceStore.put("queryTransformer.suppressed.stage", safeStage);
            TraceStore.put("queryTransformer.suppressed." + safeStage, true);
            TraceStore.put("queryTransformer.suppressed.errorType", safeErrorType);
            TraceStore.put("queryTransformer.suppressed." + safeStage + ".errorType", safeErrorType);
        } catch (Throwable traceFailure) {
            log.debug("[QueryTransformer] suppressed trace write failed stage={} errorType={}",
                    safeStage,
                    traceFailure == null ? "unknown" : traceFailure.getClass().getSimpleName());
        }
        log.debug("[QueryTransformer] suppressed stage={} errorType={}", safeStage, safeErrorType);
    }

    // ─────────────────────────────────────────────────────────────
    // 경량 힌트 추출기(내장). 필요 시 바깥에서 교체 주입 가능.
    // ─────────────────────────────────────────────────────────────
    public interface HintExtractor {
        List<String> extractHints(String assistantAnswer);
    }

    public static class RegexHintExtractor implements HintExtractor {
        // 따옴표 안/고유명사 비슷한 조각/ ~전생 패턴
        private final Pattern p = Pattern.compile("[\"“](.+?)[\"”]|([A-Za-z가-힣0-9 ]전생)");

        @Override
        public List<String> extractHints(String text) {
            if (text == null)
                return List.of();
            Matcher m = p.matcher(text);
            List<String> out = new ArrayList<>();
            while (m.find()) {
                String g1 = m.group(1);
                String g2 = m.group(2);
                out.add(g1 != null ? g1 : (g2 != null ? g2 : ""));
            }
            return out.stream().filter(s -> s != null && !s.isBlank())
                    .distinct()
                    /* base(≤4) + boosted(≤MAX_HINTS) 의 총합 제한 */
                    .limit(MAX_VARIANTS + MAX_HINTS + 2)
                    .toList();
        }
    }

}
