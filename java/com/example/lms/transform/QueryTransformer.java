package com.example.lms.transform;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;

import com.example.lms.infra.resilience.FriendShieldPatternDetector;
import com.example.lms.infra.resilience.AuxDownTracker;
import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.infra.resilience.AuxBlockedReason;
import com.example.lms.infra.resilience.NoiseRoutingGate;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
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

import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;

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
    /** “을지대학교” / “eulji” 등 원치 않는 단어 패턴 */
    private static final Pattern UNWANTED_WORD_PATTERN = Pattern.compile("(?i)(을지대학교|eulji)");
    /** site eulji ac kr /* ... *&#47; 형태 도메인-스코프 프리픽스 */
    private static final Pattern DOMAIN_SCOPE_PREFIX = Pattern.compile("(?i)^\\s*(site\\s+)?\\S+\\s+ac\\s+kr\\b");
    /** 원문 보존 보호어: 원문에 있으면 금지어(오인어)로 변형하지 않도록 방어 */
    private static final Map<String, Set<String>> PROTECTED_TERMS = Map.of(
            "원신", Set.of("원숭이", "monkey"));

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
            // best-effort
        }

        try {
            TraceStore.put("qtx.softCooldown.reason", reason);
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
            // best-effort
        }

        // DebugProbe is optional. This is best-effort only.
        try {
            java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
            dd.put("reason", reason);
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
                    dd.put("message", m.length() > 240 ? (m.substring(0, 240) + "…") : m);
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
            // fail-soft
        }
    }

    private static boolean looksLikeModelLoading(Throwable e) {
        Throwable root = unwrap(e);
        String msg = null;
        try {
            msg = (root != null ? root.getMessage() : (e != null ? e.getMessage() : null));
        } catch (Throwable ignore) {
            msg = null;
        }
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        // Common patterns: "loading model", "model is loading", "server loading model"
        return (m.contains("loading") && m.contains("model"))
                || m.contains("model is loading")
                || m.contains("server loading model")
                || m.contains("warming up")
                || (m.contains("initializing") && m.contains("model"))
                || m.contains("not ready");
    }

    private boolean isSoftTransientForQtx(NightmareBreaker.FailureKind kind, boolean modelLoading) {
        if (modelLoading) {
            return true;
        }
        if (kind == null) {
            return true;
        }
        // QTX is optional: treat these as soft failures to avoid breaker over-tripping.
        return kind == NightmareBreaker.FailureKind.TIMEOUT
                || kind == NightmareBreaker.FailureKind.HTTP_5XX
                || kind == NightmareBreaker.FailureKind.REJECTED
                || kind == NightmareBreaker.FailureKind.RATE_LIMIT;
    }

    private long qtxSoftCooldownMsFor(NightmareBreaker.FailureKind kind, boolean modelLoading) {
        long base = Math.max(0L, (qtxSoftCooldownBaseMs > 0L ? qtxSoftCooldownBaseMs : llmTimeoutOpenHintMs));
        if (base <= 0L) {
            base = 3000L;
        }
        if (kind == NightmareBreaker.FailureKind.RATE_LIMIT) {
            // Keep 429 cooldown modest for this optional stage.
            return Math.max(1500L, Math.min(base, 10000L));
        }
        if (modelLoading) {
            // Model warm-up/loading is typically short. Keep this shallow so the
            // optional stage doesn't get stuck in long degraded windows.
            return Math.min(4000L, Math.max(1200L, base));
        }
        if (kind == NightmareBreaker.FailureKind.TIMEOUT) {
            // Treat TIMEOUT as transient for this optional stage; keep shallow.
            return Math.min(4000L, Math.max(1200L, base));
        }
        return base;
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
            // fail-soft
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
            return cached;
        }

        // UAW: Request-scoped aux degradation/strike/compression should block
        // additional LLM calls.
        // Cache hits are still allowed (no new external call).
        BypassDecision bd = computeBypassDecision();
        if (bd.bypass()) {
            AuxBlockedReason reason = bd.reason();
            String reasonCode = (reason != null && reason.code() != null) ? reason.code() : "unknown";
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
                TraceStore.put("queryTransformer.reason", reasonCode);
                TraceStore.put("queryTransformer.trigger", bd.trigger());

                TraceStore.putIfAbsent("aux.queryTransformer", "degraded:" + reasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded", Boolean.TRUE);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.reason", reasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.trigger", bd.trigger());
                TraceStore.inc("aux.queryTransformer.degraded.count");

            } catch (Throwable ignore) {
            }

            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("trigger", bd.trigger());
                dd.put("reason", reasonCode);
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
            }
            timeoutMs = floorMs;
        }

        timeoutMs = applyMinLiveBudgetCap(timeoutMs);

        CompletableFuture<String> fut = inflight.computeIfAbsent(prompt, p -> {
            CompletableFuture<String> created = new CompletableFuture<>();
            ExecutorService ex = (llmFastExecutor != null) ? llmFastExecutor : ForkJoinPool.commonPool();

            try {
                Future<?> task = ex.submit(ContextPropagation.wrap(() -> {
                    try {
                        String out = runLLM(p);
                        created.complete(out == null ? "" : out);
                    } catch (Throwable t) {
                        if (faultMaskingLayerMonitor != null) {
                            faultMaskingLayerMonitor.record("query-transformer:runLLM", t, p, "swallowed");
                        }
                        created.complete("");
                    }
                }));
                inflightTasks.put(p, task);
                // ✅ UAW: Force Kill (좀비 inflight 강제 차단)
                long killMs = Math.max(1L, inflightTimeoutMs);
                CompletableFuture.runAsync(ContextPropagation.wrap(() -> {
                    if (created.isDone())
                        return;
                    Future<?> toCancel = inflightTasks.get(p);
                    if (toCancel != null) {
                        toCancel.cancel(false);
                    }

                    // MERGE_HOOK:PROJ_AGENT::QT_FORCEKILL_MARKSOFT_V1
                    // QueryTransformer는 옵션 전처리 단계이므로 force-kill을 aux-down으로 과대 승격하지 않는다.
                    AuxDownTracker.markSoft("query-transformer:cachedLlm", "force-kill");
                    try {
                        TraceStore.put("aux.queryTransformer", "force-kill");
                        TraceStore.put("queryTransformer.forceKill", true);
                    } catch (Exception ignore) {
                    }
                    if (nightmareBreaker != null) {
                        // Force-kill is local zombie cleanup. Do NOT trip the breaker here,
                        // otherwise we can create cancellation-toxicity cascades (cancel ->
                        // breaker-open -> aux blank).
                        try {
                            TraceStore.put("queryTransformer.forceKill.breakerTripSuppressed", true);
                        } catch (Exception ignore) {
                            // best-effort
                        }
                    }
                    created.complete("");
                }), CompletableFuture.delayedExecutor(killMs, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
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
            try {
                TraceStore.put("aux.queryTransformer", "hint-timeout");
                TraceStore.put("queryTransformer.softTimeout", true);
                TraceStore.put("queryTransformer.softTimeoutMs", timeoutMs);
                TraceStore.inc("aux.queryTransformer.hintTimeout.count");
            } catch (Exception ignore) {
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
                // fail-soft
            }
            return "";
        } catch (InterruptedException ie) {
            // Interrupt Hygiene: clear flag to avoid poisoning request thread
            Thread.interrupted();
            Future<?> task = inflightTasks.get(prompt);
            if (task != null)
                task.cancel(false);
            // 조용히 우회 - breaker/faultmask 중복 기록하지 않음
            AuxDownTracker.markSoft("query-transformer:cachedLlm", "interrupted");
            try {
                TraceStore.put("aux.queryTransformer", "interrupted");
                TraceStore.put("queryTransformer.interrupted", true);
            } catch (Exception ignore) {
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
                // fail-soft
            }
            return "";
        }

        catch (ExecutionException ee) {
            try {
                java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                dd.put("key", NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
                dd.put("promptLen", (prompt != null ? prompt.length() : 0));
                dd.put("promptSha1", com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                emitQtx(
                        DebugEventLevel.WARN,
                        "qtx.exec_exception",
                        "QueryTransformer execution failure (fail-soft)",
                        "QueryTransformer.cachedLlm",
                        dd,
                        (ee.getCause() != null ? ee.getCause() : ee));
            } catch (Throwable ignore) {
                // fail-soft
            }
            return "";
        } catch (Exception e) {
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
                // fail-soft
            }
            return "";
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

        try {
            if (!novaOrchEnabled || !novaOrchQueryTransformerEnabled) {
                disabled = true;
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        try {
            breakerOpen = nightmareBreaker != null
                    && nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
        } catch (Throwable ignore) {
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
                // fail-soft
            }
        }

        // MERGE_HOOK:PROJ_AGENT::QT_BYPASS_ON_FAILURE_PATTERN_COOLDOWN_V2
        if (!disabled && !breakerOpen && failurePatterns != null) {
            try {
                failureCooldown = failurePatterns.isCoolingDown("qtx");
            } catch (Exception ignore) {
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
                // fail-soft
            }
        }

        AuxBlockedReason ctxReason = AuxBlockedReason.fromContext(ctx);
        boolean ctxBypass = ctx != null && ((ctx.isAuxDegraded() || ctx.isAuxHardDown())
                || ctx.isStrikeMode() || ctx.isCompressionMode() || ctx.isBypassMode());

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
        } catch (Throwable ignore) {
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
        if (nightmareBreaker != null) {
            try {
                nightmareBreaker.checkOpenOrThrow(breakerKey);
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
                    // fail-soft
                }
                return "";
            }
        }

        // Prompt-length guard (fail-soft): if the prompt is too large, bypass aux LLM
        // to avoid HttpTimeoutException -> breaker-open cascades.
        if (prompt == null || prompt.isBlank()) {
            TraceStore.put("qtx.prompt.empty", true);
            return "";
        }
        if (llmMaxPromptChars > 0 && prompt.length() > llmMaxPromptChars) {
            String reasonCode = "prompt_len";
            AuxDownTracker.markSoft("query-transformer:runLLM", reasonCode);
            try {
                TraceStore.putIfAbsent("qtx.prompt.sha1",
                        com.abandonware.ai.agent.integrations.TextUtils.sha1(prompt));
                TraceStore.putIfAbsent("qtx.prompt.len", prompt.length());
                TraceStore.putIfAbsent("aux.queryTransformer", "degraded:" + reasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded", Boolean.TRUE);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.reason", reasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.degraded.trigger", "prompt_len_guard");
                TraceStore.inc("aux.queryTransformer.degraded.count");
            } catch (Throwable ignore) {
                // fail-soft
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
                // fail-soft
            }
            return "";
        }
        long started = System.nanoTime();
        try {
            String text = chatModel.chat(List.of(
                    SystemMessage.from("""
                            간결하고 한 줄로만 응답하세요.
                            - 고유명사에 대해 확실히 알지 못하는 속성은 절대로 추측하거나 창작하지 마세요.
                            - 모르는 경우 원문의 표현만 약간 정리하거나, 검색에 도움이 되는 일반적인 키워드만 제안하세요.
                            - 존재하지 않는 조직명, 집단, 세계관 설정을 만들어내지 마세요.
                            """),
                    UserMessage.from(prompt))).aiMessage().text();

            // 빈 응답은 "옵션(QTX) 단계"에서는 soft-failure로 처리:
            // - breaker 과민 트립을 막고
            // - 짧은 soft-cooldown으로 step-down 라우팅
            if (text == null || text.isBlank()) {
                AuxDownTracker.markSoft("query-transformer:runLLM", "blank");
                startQtxSoftCooldown("blank", NightmareBreaker.FailureKind.UNKNOWN, null,
                        Math.max(0L, (qtxSoftCooldownBaseMs > 0L ? qtxSoftCooldownBaseMs : llmTimeoutOpenHintMs)));
                try {
                    TraceStore.putIfAbsent("qtx.blank", true);
                    TraceStore.inc("qtx.blank.count");
                } catch (Throwable ignore) {
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
                    // fail-soft
                }
                return "";
            }

            // FriendShield(회피/사과/정보없음) 패턴은 QTX에서 silent-failure로 간주:
            // breaker를 열지 않고 soft-cooldown으로 잠시 우회한다.
            if (FriendShieldPatternDetector.looksLikeSilentFailure(text)) {
                AuxDownTracker.markSoft("query-transformer:runLLM", "friendshield");
                startQtxSoftCooldown("friendshield", NightmareBreaker.FailureKind.UNKNOWN, null,
                        Math.max(0L, (qtxSoftCooldownBaseMs > 0L ? qtxSoftCooldownBaseMs : llmTimeoutOpenHintMs)));
                try {
                    TraceStore.putIfAbsent("qtx.friendshield", true);
                    TraceStore.inc("qtx.friendshield.count");
                } catch (Throwable ignore) {
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
                    // fail-soft
                }
                return "";
            }

            if (nightmareBreaker != null) {
                nightmareBreaker.recordSuccess(breakerKey, elapsedMs(started));
            // Success: clear soft-cooldown streak so we can recover quickly.
            try {
                qtxSoftCooldownStreak.set(0);
            } catch (Throwable ignore) {
                // best-effort
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
                    TraceStore.put("qtx.llm.fastBaseUrl", baseUrl);
                    TraceStore.put("qtx.llm.fastModel", model);
                    TraceStore.put("qtx.llm.curlHint", curlHint);

                    log.error(
                            "[QueryTransformer] LLM rejected request (model is required). fastBaseUrl={} fastModel={} curlHint={}",
                            baseUrl,
                            model,
                            curlHint);

                    try {
                        java.util.Map<String, Object> dd = new java.util.LinkedHashMap<>();
                        dd.put("key", breakerKey);
                        dd.put("kind", kind.name());
                        dd.put("fastBaseUrl", baseUrl);
                        dd.put("fastModel", model);
                        dd.put("curlHint", curlHint);
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
                        // fail-soft
                    }
                } catch (Throwable ignore) {
                    // best-effort
                }
            }

            // MERGE_HOOK:PROJ_AGENT::QT_INTERRUPT_CANCEL_SILENT_V1
            if (kind == NightmareBreaker.FailureKind.INTERRUPTED) {
                // pooled worker 오염 방지: interrupt 플래그를 정리 (cancellation/teardown signal)
                Thread.interrupted();
                // QueryTransformer는 옵션 단계이며, interrupt는 대부분 내부 취소/정리 신호다.
                // breaker/faultmask를 중복 기록하지 않고 조용히 우회한다.
                AuxDownTracker.markSoft("query-transformer:runLLM", "canceled");
                try {
                    TraceStore.put("aux.queryTransformer", "canceled");
                    TraceStore.put("queryTransformer.canceled", true);
                } catch (Exception ignore) {
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
                    // fail-soft
                }
                return "";
            }

            if (faultMaskingLayerMonitor != null) {
                faultMaskingLayerMonitor.record("query-transformer:runLLM", e, prompt, "caught-and-fallback");
            }

            // Soft-failure ladder: for transient errors (model loading / timeout / 5xx /
            // 429),
            // do NOT trip the breaker in this optional stage. Instead, start a short
            // cooldown
            // and let the pipeline proceed with the original query.
            boolean modelLoading = looksLikeModelLoading(e);
            if (isSoftTransientForQtx(kind, modelLoading)) {
                long cdMs = qtxSoftCooldownMsFor(kind, modelLoading);
                startQtxSoftCooldown(
                        modelLoading ? "model_loading" : ("soft_" + kind.name().toLowerCase(java.util.Locale.ROOT)),
                        kind, e, cdMs);
                AuxDownTracker.markSoft("query-transformer:runLLM",
                        "soft-" + kind.name().toLowerCase(java.util.Locale.ROOT));
                try {
                    TraceStore.put("qtx.softFailure", true);
                    TraceStore.put("qtx.softFailure.kind", kind.name());
                    TraceStore.put("qtx.softFailure.modelLoading", modelLoading);
                    TraceStore.put("qtx.softFailure.cooldownMs", cdMs);
                } catch (Throwable ignore) {
                    // best-effort
                }
                return "";
            }

            if (nightmareBreaker != null) {
                Long hintMs = (kind == NightmareBreaker.FailureKind.TIMEOUT && llmTimeoutOpenHintMs > 0)
                        ? llmTimeoutOpenHintMs
                        : null;
                nightmareBreaker.recordFailure(breakerKey, kind, e, prompt, hintMs);
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
                // fail-soft
            }
            return "";
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String buildCurlHint(String baseUrl, String model) {
        String b = (baseUrl == null || baseUrl.isBlank()) ? "<baseUrl>" : baseUrl.trim();
        String m = (model == null || model.isBlank()) ? "<model>" : model.trim();

        // Base URL should normally end with "/v1" for OpenAI-compatible servers.
        String endpoint = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        endpoint = endpoint + "/chat/completions";

        // No secrets; caller can add auth headers if needed.
        return "curl -sS -X POST '" + endpoint + "' -H 'Content-Type: application/json' "
                + "-d '{\"model\":\"" + escapeJson(m)
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}'";
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static NightmareBreaker.FailureKind classifyLlmFailure(Throwable t) {
        Throwable root = unwrap(t);

        if (root instanceof InterruptedException
                || root instanceof InterruptedIOException
                || root instanceof CancellationException) {
            return NightmareBreaker.FailureKind.INTERRUPTED;
        }

        if (root instanceof HttpTimeoutException
                || root instanceof SocketTimeoutException
                || root instanceof java.util.concurrent.TimeoutException) {
            return NightmareBreaker.FailureKind.TIMEOUT;
        }

        if (root instanceof RejectedExecutionException) {
            return NightmareBreaker.FailureKind.REJECTED;
        }

        // Prefer shared classifier for HttpException + common OpenAI-compatible error
        // bodies.
        try {
            NightmareBreaker.FailureKind shared = NightmareBreaker.classify(root);
            if (shared != null && shared != NightmareBreaker.FailureKind.UNKNOWN) {
                return shared;
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        // 메시지/클래스명 기반 폴백 (라이브러리 예외 타입 변화에 대응)
        String cn = root.getClass().getName();
        if (cn.endsWith("RateLimitException") || cn.toLowerCase().contains("ratelimit")) {
            return NightmareBreaker.FailureKind.RATE_LIMIT;
        }
        if (cn.endsWith("TimeoutException") && !cn.startsWith("java.")) {
            return NightmareBreaker.FailureKind.TIMEOUT;
        }

        String msg = root.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("model is required")
                    || lower.contains("must provide a model")
                    || (lower.contains("model parameter") && lower.contains("required"))
                    || (lower.contains("missing required parameter") && lower.contains("model"))) {
                return NightmareBreaker.FailureKind.CONFIG;
            }
            if (msg.contains("429") || lower.contains("too many requests")) {
                return NightmareBreaker.FailureKind.RATE_LIMIT;
            }
            if (lower.contains("timed out")) {
                return NightmareBreaker.FailureKind.TIMEOUT;
            }
        }

        return NightmareBreaker.FailureKind.UNKNOWN;
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 16 && cur != null; i++) {
            if (cur instanceof ExecutionException || cur instanceof CompletionException) {
                cur = cur.getCause();
                continue;
            }
            if (cur instanceof RuntimeException && cur.getCause() != null) {
                cur = cur.getCause();
                continue;
            }
            break;
        }
        return (cur != null) ? cur : t;
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
                // best-effort
            }
            if (recovered == null || recovered.isBlank()) {
                try {
                    TraceStore.put("qtx.normalized.blank", true);
                } catch (Throwable ignore) {
                    // best-effort
                }
                return List.of();
            }
            normalizedQuery = recovered;
            try {
                TraceStore.put("qtx.normalized.blankRecovered", true);
                TraceStore.put("qtx.normalized.blankRecovered.source", "guardContext.userQuery");
            } catch (Throwable ignore) {
                // best-effort
            }

            // Debug timeline event (shows up in evidence-list diagnostics).
            try {
                String rr = recovered;
                if (rr.length() > 120)
                    rr = rr.substring(0, 120);
                OrchTrace.appendEvent(OrchTrace.newEvent(
                        "aux", "queryTransformer", "normalizedRecovered",
                        java.util.Map.of("source", "guardContext.userQuery", "recovered", rr)));
            } catch (Throwable ignore) {
                // best-effort
            }
        }
        // 1) 알파벳숫자 복합 토큰(K8Plus, A7X 등)을 그대로 묶어 모호성 감소
        String preProcessed = preserveCompoundTokens(normalizedQuery.trim());
        String q = dict.getOrDefault(preProcessed, preProcessed);
        /* ① LLM 맞춤법 교정 */
        q = correctWithLLM(context, q);

        /* ② LLM 다중-제안(최대 3개) 불필요 변형 필터링 */
        List<String> variants = filterUnwantedVariants(
                generateVariantsWithLLM(q), normalizedQuery);

        /* ③ 원본·교정·변형 합치기 */
        List<String> out = Stream.concat(Stream.of(normalizedQuery, q), variants.stream())
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
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

    /** LLM 한 번 호출해 맞춤법을 교정한다 */
    private String correctWithLLM(String ctx, String q) {
        try {
            // Build the correction prompt using the centralised prompt builder
            String prompt = QUERY_KEYWORD_PROMPT_BUILDER.buildCorrectionPrompt(q);

            // 캐시를 먼저 조회하고 없으면 채운다.
            String ans = cachedLlm(prompt);
            ans = cleanUp(ans); // 불필요 토큰 제거

            /* +콜론/화살표 구분이 여전히 남아 있으면 오른쪽만 취함 */
            if (ans.matches(".*[:：→>-].+")) {
                ans = ans.replaceFirst(".*[:：→>-]\\s*", "");
            }
            return (ans != null && !ans.isBlank()) ? ans : q;
        } catch (Exception e) {
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
        String prompt = QUERY_KEYWORD_PROMPT_BUILDER
                .buildCognitiveVariantsPrompt(cs, subject, baseQuery, MAX_VARIANTS);
        String ans = cachedLlm(prompt);
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
        List<String> buffed = raw.stream().map(q -> boostWithIntent(q, intent)).toList();
        return dedupBySimilarity(buffed, 0.86);
    }

    /** ✨ subject 앵커 지원 버전 */
    private List<String> generateVariantsWithLLM(String q, @Nullable String subject) {
        try {
            // Build the keyword variants prompt using the prompt builder
            String prompt = QUERY_KEYWORD_PROMPT_BUILDER
                    .buildKeywordVariantsPrompt(q, subject, MAX_VARIANTS);
            String ans = cachedLlm(prompt);
            if (ans == null || ans.isBlank()) {
                // LLM이 응답하지 못했으면 deterministic cheap-path로 우회
                return cheapVariantsFallback(q, subject);
            }

            List<String> raw = Arrays.stream(ans.split("\r?\n"))
                    .map(this::cleanUp)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());

            // ✨ 짧은 고유명사 기반 질의(예: "원신 마비카")인 경우,
            // - 원본 질의를 항상 포함하고
            // - 원문에 없던 위험 속성 키워드를 붙인 변이는 버린다.
            if (isNamedEntityLike(q)) {
                List<String> filtered = raw.stream()
                        .filter(v -> isEntitySafeVariant(q, v))
                        .collect(Collectors.toList());

                if (filtered.isEmpty()) {
                    // 모든 변이가 위험하면 원문만 사용
                    return q == null || q.isBlank()
                            ? List.of()
                            : List.of(q.trim());
                }

                // 결과에 원문이 없으면 맨 앞에 추가
                String base = q == null ? "" : q.trim();
                boolean hasOriginal = !base.isEmpty() && filtered.stream()
                        .anyMatch(v -> v.equalsIgnoreCase(base));

                List<String> out = new ArrayList<>();
                if (!base.isEmpty() && !hasOriginal) {
                    out.add(base);
                }
                out.addAll(filtered);

                // subject가 주어졌다면 subject 토큰과 전혀 겹치지 않는 변이는 제외
                if (subject != null && !subject.isBlank()) {
                    Set<String> subjTokens = tokens(subject);
                    out = out.stream()
                            .filter(s -> !Collections.disjoint(tokens(s), subjTokens))
                            .collect(Collectors.toList());
                }

                return out.stream()
                        .distinct()
                        .limit(MAX_VARIANTS)
                        .toList();
            }

            // 일반 질의의 경우: 기존 로직 유지 + subject 기반 필터링만 적용
            return raw.stream()
                    // ✨ subject가 있으면, subject 토큰과 최소 하나는 겹치도록 필터링
                    .filter(s -> subject == null || !Collections.disjoint(tokens(s), tokens(subject)))
                    .distinct()
                    .limit(MAX_VARIANTS)
                    .toList();
        } catch (Exception e) {
            return cheapVariantsFallback(q, subject);
        }
    }

    /**
     * Deterministic cheap-path fallback when aux LLM is unavailable
     * (timeout/open/blank).
     *
     * <p>
     * Always keeps the original query and adds a small number of anchor-based
     * variants
     * to keep web-search quality acceptable even under degraded conditions.
     * </p>
     */
    private List<String> cheapVariantsFallback(String q, @Nullable String subject) {
        q = sanitize(q);
        subject = sanitize(subject);
        if (q == null || q.isBlank()) {
            // Recover from GuardContext.userQuery so we still provide at least one variant.
            try {
                com.example.lms.service.guard.GuardContext gc = com.example.lms.service.guard.GuardContextHolder
                        .getOrDefault();
                String uq = (gc == null) ? null : gc.getUserQuery();
                if (uq != null && !uq.isBlank()) {
                    q = sanitize(uq);
                    TraceStore.put("qtx.cheapFallback.recovered", true);
                    TraceStore.put("qtx.cheapFallback.recovered.source", "guardContext.userQuery");

                    try {
                        String rr = q;
                        if (rr.length() > 120)
                            rr = rr.substring(0, 120);
                        OrchTrace.appendEvent(OrchTrace.newEvent(
                                "aux", "queryTransformer", "cheapFallbackRecovered",
                                java.util.Map.of("source", "guardContext.userQuery", "recovered", rr)));
                    } catch (Throwable ignore) {
                        // best-effort
                    }
                }
            } catch (Throwable ignore) {
                // best-effort
            }

            if (q == null || q.isBlank()) {
                return List.of();
            }
        }

        // Feature toggle / bean optionality
        if (!novaOrchEnabled || !novaOrchQueryTransformerCheapEnabled || anchorNarrower == null) {
            return List.of(q);
        }

        int limit = Math.max(1, Math.min(MAX_VARIANTS, cheapVariants));

        // aux-degraded 상태면 fan-out을 2개로 제한
        GuardContext gc = GuardContextHolder.get();
        if (gc != null && gc.isAuxDown()) {
            limit = Math.min(limit, 2);
        }

        List<String> protectedTerms;
        try {
            protectedTerms = PROTECTED_TERMS.keySet().stream().filter(q::contains).toList();
        } catch (Exception ignored) {
            protectedTerms = List.of();
        }

        AnchorNarrower.Anchor anchor = anchorNarrower.pick(q, protectedTerms, null);
        List<String> candidates = anchorNarrower.cheapVariants(q, anchor);

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String c : candidates) {
            String s = sanitize(c);
            if (s == null || s.isBlank())
                continue;
            if (subject != null && !subject.isBlank() && Collections.disjoint(tokens(s), tokens(subject))) {
                continue;
            }
            out.add(s);
            if (out.size() >= limit)
                break;
        }
        if (out.isEmpty()) {
            return List.of(q);
        }
        return new ArrayList<>(out);
    }

    /**
     * Single-string sanitize helper for cheapVariantsFallback - null/blank check
     * and trim
     */
    private static String sanitize(String s) {
        if (s == null)
            return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

        // [PATCH src111_merge15/merge15] When userPrompt is unexpectedly blank
        // (e.g., upstream normalization produced an empty string), recover from
        // GuardContext.userQuery so we always have at least one usable variant.
        if (p == null || p.isBlank()) {
            try {
                com.example.lms.service.guard.GuardContext gc = com.example.lms.service.guard.GuardContextHolder
                        .getOrDefault();
                String uq = (gc == null) ? null : gc.getUserQuery();
                if (uq != null && !uq.isBlank()) {
                    p = uq.trim();
                    TraceStore.put("qtx.userPrompt.recovered", true);
                    TraceStore.put("qtx.userPrompt.recovered.source", "guardContext.userQuery");
                    // Debug timeline event (shows up in evidence-list diagnostics).
                    try {
                        String rr = p;
                        if (rr.length() > 120)
                            rr = rr.substring(0, 120);
                        OrchTrace.appendEvent(OrchTrace.newEvent(
                                "aux", "queryTransformer", "userPromptRecovered",
                                java.util.Map.of("source", "guardContext.userQuery", "recovered", rr)));
                    } catch (Throwable ignore) {
                        // best-effort
                    }
                }
            } catch (Throwable ignore) {
                // best-effort
            }
        }

        // 람다에서 사용할 effectively final 변수
        final String finalP = p;

        // ✅ base 변환을 먼저 수행 (여기서 soft-timeout이 나면 auxDown이 찍힘)
        List<String> baseRaw = transform("", p);
        boolean bypassExtras = shouldBypassAuxLlm();

        // bypassExtras=true면 intent 분류, subQuery 생성 등 추가 LLM 호출 스킵
        QueryIntent intent = bypassExtras ? QueryIntent.GENERAL_KNOWLEDGE : classifyIntent(p);
        Set<String> promptTokens = tokenize(p);

        List<String> base = (baseRaw == null ? List.<String>of() : baseRaw).stream()
                .map(q -> boostWithIntent(q, intent))
                .toList();

        boolean complex = !bypassExtras && isComplex(p);
        List<String> subQs = complex ? generateSubQueries(p) : List.of();

        List<String> boosted = (assistantAnswer == null ? Stream.<String>empty()
                : hintExtractor.extractHints(assistantAnswer).stream())
                .limit(MAX_HINTS)
                .map(this::cleanUp)
                .filter(h -> !Collections.disjoint(tokens(h), promptTokens))
                .map(h -> boostWithIntent(h, intent))
                .collect(Collectors.toList());

        List<String> merged = Stream.of(base, subQs, boosted)
                .flatMap(Collection::stream)
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .filter(s -> !DOMAIN_SCOPE_PREFIX.matcher(s).find())
                .filter(s -> !UNWANTED_WORD_PATTERN.matcher(s).find()
                        || UNWANTED_WORD_PATTERN.matcher(finalP).find())
                .filter(s -> subject == null || !Collections.disjoint(tokens(s), tokens(subject)))
                .distinct()
                .toList();

        return merged.isEmpty()
                ? List.of(p.trim())
                : dedupBySimilarity(merged, 0.86);
    }

    /* ───────── token helper ───────── */
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{Nd}]+");

    private static Set<String> tokens(String s) {
        if (s == null)
            return Set.of();
        return Arrays.stream(
                NON_ALNUM.matcher(s.toLowerCase(Locale.ROOT))
                        .replaceAll(" ")
                        .trim()
                        .split("\\s+"))
                .filter(t -> t.length() > 1)
                .collect(Collectors.toSet());
    }

    /**
     * 질문이 '짧은 고유명사 위주'인지 대략 판정한다.
     * 예: "원신 마비카", "마비카 속성" 등은 true,
     * 긴 설명형 질문이나 복합 문장은 false.
     */
    private boolean isNamedEntityLike(String q) {
        if (q == null)
            return false;
        String trimmed = q.strip();
        if (trimmed.length() < 2)
            return false;

        String lower = trimmed.toLowerCase(Locale.ROOT);
        // 한국어 의문사/설명형 패턴이 섞여 있으면 일반 질문으로 취급
        boolean hasWh = lower.contains("왜")
                || lower.contains("어떻게")
                || lower.contains("무엇")
                || lower.contains("무슨")
                || lower.contains("설명")
                || lower.contains("알려줘")
                || lower.startsWith("explain");

        if (hasWh)
            return false;

        Set<String> tokenSet = tokens(trimmed);
        int tokenCount = tokenSet.size();
        return tokenCount > 0 && tokenCount <= 3;
    }

    /**
     * 원래 질의에 없던 위험 속성 키워드를 새로 붙였는지 검사한다.
     * 예: 원본에 '번개'가 없는데 변이에만 '번개'가 있으면 false.
     */
    private boolean isEntitySafeVariant(String original, String variant) {
        if (variant == null || variant.isBlank())
            return false;
        if (original == null)
            return true;

        Set<String> origTokens = tokens(original);
        Set<String> varTokens = tokens(variant);

        List<String> risky = List.of(
                "번개", "전기", "얼음", "바람", "물", "불", "빛", "어둠",
                "성우", "더빙", "나레이터",
                "비밀", "조직", "단체", "길드",
                "원소", "속성", "직업", "클래스");

        for (String r : risky) {
            if (varTokens.contains(r) && !origTokens.contains(r)) {
                return false;
            }
        }
        return true;
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
        String prompt = QUERY_KEYWORD_PROMPT_BUILDER
                .buildIntentBuffPrompt(base, intent, MAX_DYNAMIC_BUFFS);
        String ans = cachedLlm(prompt);
        if (ans == null || ans.isBlank())
            return List.of();

        return Arrays.stream(ans.split("\\r?\\n"))
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .limit(MAX_DYNAMIC_BUFFS)
                .toList();
    }

    /*
     * -------------------------------------------------------
     * unwanted word / domain-scope 변형 필터
     * -------------------------------------------------------
     */
    private List<String> filterUnwantedVariants(List<String> variants, String original) {
        boolean originalContainsUnwanted = UNWANTED_WORD_PATTERN.matcher(original).find();
        return variants.stream()
                // site eulji ac kr /* ... */ 같은 변형 제거
                .filter(v -> !DOMAIN_SCOPE_PREFIX.matcher(v).find())
                // “을지대학교” 키워드가 원문에 없으면 제외
                .filter(v -> originalContainsUnwanted
                        || !UNWANTED_WORD_PATTERN.matcher(v).find())
                // 보호어 위반(원신→원숭이 등) 변형 제거
                .filter(v -> !violatesProtectedTerms(original, v))
                .toList();
    }

    /** 보호어 위반 여부: 원문 토큰에 보호 키가 있고, 변형 토큰에 금지 토큰이 있으면 true */
    private boolean violatesProtectedTerms(String original, String variant) {
        Set<String> oTok = tokenize(original);
        Set<String> vTok = tokenize(variant);
        for (var e : PROTECTED_TERMS.entrySet()) {
            if (oTok.contains(e.getKey())) {
                for (String banned : e.getValue()) {
                    if (vTok.contains(banned))
                        return true;
                }
            }
        }
        return false;
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
        String prompt = QUERY_KEYWORD_PROMPT_BUILDER.buildIntentClassificationPrompt(query);
        String result = cachedLlm(prompt);
        if (result == null || result.isBlank())
            return QueryIntent.GENERAL_KNOWLEDGE;
        try {
            return QueryIntent.valueOf(result.trim()
                    .replaceAll("[^A-Za-z_]", "")
                    .toUpperCase(Locale.ROOT));
        } catch (Exception e) {
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
        String prompt = QUERY_KEYWORD_PROMPT_BUILDER.buildSubQueriesPrompt(question);
        String ans = cachedLlm(prompt);
        if (ans == null || ans.isBlank())
            return List.of();
        return Arrays.stream(ans.split("\\r?\\n"))
                .map(this::cleanUp)
                .filter(s -> s != null && !s.isBlank())
                .limit(3)
                .toList();
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
        if (userPrompt == null || userPrompt.isBlank() || maxSites <= 0) {
            return List.of();
        }
        String dom = (domain == null) ? "GENERAL" : domain.name();
        String lang = (locale == null || locale.getLanguage() == null) ? "" : locale.getLanguage();

        String prompt = """
                You are an assistant that suggests a small list of high-authority websites to search.
                Task: Given the user's question, propose up to %d domain names that are likely to host primary/official information.

                Rules:
                - Output ONLY domain names (no scheme, no path, no bullet labels). One domain per line.
                - Prefer: official vendor docs/support, government, standards bodies, universities, reputable orgs.
                - Avoid: social media, forums, content farms.
                - If uncertain, include general high-quality references (e.g., wikipedia.org) as fallbacks.

                User language hint: %s
                Domain hint: %s

                User question:
                %s
                """
                .formatted(maxSites, lang, dom, userPrompt.trim());

        String raw = cachedLlm(prompt);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> lines = parseLines(raw);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String l : lines) {
            String n = normalizeDomain(l);
            if (n == null || n.isBlank())
                continue;
            if (seen.add(n)) {
                out.add(n);
                if (out.size() >= maxSites)
                    break;
            }
        }
        TraceStore.put("probe.needle.llm.sites", out);
        return out;
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
        if (userPrompt == null || userPrompt.isBlank() || maxQueries <= 0) {
            return List.of();
        }

        String dom = (domain == null) ? "GENERAL" : domain.name();
        String lang = (locale == null || locale.getLanguage() == null) ? "" : locale.getLanguage();

        List<String> sites = (siteHints == null) ? List.of()
                : siteHints.stream().map(QueryTransformer::normalizeDomain)
                        .filter(s -> s != null && !s.isBlank())
                        .distinct()
                        .limit(8)
                        .toList();

        String sitesBlock = sites.isEmpty() ? "(none)" : String.join("\n", sites);
        String plannedBlock = (alreadyPlanned == null || alreadyPlanned.isEmpty()) ? "(none)"
                : String.join("\n", alreadyPlanned);

        EvidenceSignals sig = (signals != null) ? signals : EvidenceSignals.empty();

        String prompt = """
                You are generating *needle probe* web search queries.

                Goal: fetch high-authority evidence with minimal burst.
                Output up to %d queries.

                Constraints (MUST follow):
                - One query per line. No numbering. No explanations.
                - Each query MUST be <= 10 words.
                - Each query MUST include exactly one token: site:DOMAIN
                - Choose DOMAIN only from the allowed list below.
                - Avoid duplicates and avoid semantic duplicates of existing queries.

                Allowed domains:
                %s

                Existing queries to avoid:
                %s

                User language hint: %s
                Domain hint: %s

                Evidence weakness signals:
                - docCount=%d
                - authorityAvg=%.2f
                - coverageScore=%.2f
                - duplicateRatio=%.2f

                User question:
                %s
                """.formatted(
                maxQueries,
                sitesBlock,
                plannedBlock,
                lang,
                dom,
                sig.docCount(),
                sig.authorityAvg(),
                sig.coverageScore(),
                sig.duplicateRatio(),
                userPrompt.trim());

        String raw = cachedLlm(prompt);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<String> lines = parseLines(raw);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String l : lines) {
            String q = cleanUp(l);
            if (q == null || q.isBlank())
                continue;

            // Ensure the query uses an allowed site: token.
            if (!q.contains("site:")) {
                if (!sites.isEmpty()) {
                    q = (q + " site:" + sites.get(0)).trim();
                }
            }

            // If the LLM used a domain not in allowed set, rewrite to first allowed.
            if (!sites.isEmpty() && q.contains("site:")) {
                String chosen = extractSiteToken(q);
                if (chosen != null && !chosen.isBlank()) {
                    String normChosen = normalizeDomain(chosen);
                    if (normChosen != null && !sites.contains(normChosen)) {
                        q = q.replace("site:" + chosen, "site:" + sites.get(0));
                    }
                }
            }

            q = enforceMaxWords(q, 10);
            String norm = q.replaceAll("\\s+", " ").trim();
            if (norm.isBlank())
                continue;
            if (!seen.add(norm))
                continue;
            out.add(norm);
            if (out.size() >= maxQueries)
                break;
        }

        TraceStore.put("probe.needle.llm.queries", out);
        return out;
    }

    private static List<String> parseLines(String raw) {
        if (raw == null)
            return List.of();
        return Arrays.stream(raw.split("\\r?\\n"))
                .map(s -> s == null ? "" : s.trim())
                .map(s -> s.replaceFirst("^[\\-*\\d\\.)]+\\s*", "").trim())
                .filter(s -> !s.isBlank())
                .toList();
    }

    private static String normalizeDomain(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        if (t.isBlank())
            return null;
        // remove scheme
        t = t.replaceFirst("^https?://", "");
        // remove leading www.
        if (t.startsWith("www."))
            t = t.substring(4);
        // remove paths/query
        int slash = t.indexOf('/');
        if (slash > 0)
            t = t.substring(0, slash);
        int q = t.indexOf('?');
        if (q > 0)
            t = t.substring(0, q);
        // strip surrounding punctuation
        t = t.replaceAll("[^a-zA-Z0-9.-]", "");
        if (t.isBlank() || !t.contains("."))
            return null;
        return t.toLowerCase(Locale.ROOT);
    }

    private static String extractSiteToken(String q) {
        if (q == null)
            return null;
        int idx = q.indexOf("site:");
        if (idx < 0)
            return null;
        String rest = q.substring(idx + "site:".length()).trim();
        if (rest.isBlank())
            return null;
        String[] parts = rest.split("\\s+");
        return parts.length > 0 ? parts[0].trim() : null;
    }

    private static String enforceMaxWords(String s, int maxWords) {
        if (s == null)
            return "";
        String[] parts = s.trim().split("\\s+");
        if (parts.length <= maxWords)
            return s.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < maxWords; i++) {
            if (i > 0)
                sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString().trim();
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