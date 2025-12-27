package com.example.lms.transform;

import com.example.lms.infra.resilience.FriendShieldPatternDetector;
import com.example.lms.infra.resilience.AuxDownTracker;
import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.infra.resilience.AuxBlockedReason;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;

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

    // Unified noise clipper for cleaning intermediate strings. Optional
    // injection because QueryTransformer may be used outside of a Spring
    // context during unit testing. When null, no additional normalisation
    // is applied.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.search.NoiseClipper noiseClipper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.infra.resilience.FaultMaskingLayerMonitor faultMaskingLayerMonitor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("llmFastExecutor")
    private ExecutorService llmFastExecutor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AnchorNarrower anchorNarrower;

    @org.springframework.beans.factory.annotation.Value("${nova.orch.enabled:true}")
    private boolean novaOrchEnabled;

    @org.springframework.beans.factory.annotation.Value("${nova.orch.query-transformer.enabled:true}")
    private boolean novaOrchQueryTransformerEnabled;

    @org.springframework.beans.factory.annotation.Value("${nova.orch.query-transformer.bypass-on-strike:true}")
    private boolean bypassOnStrike;

    @org.springframework.beans.factory.annotation.Value("${nova.orch.query-transformer.cheap-variants:3}")
    private int cheapVariants;

    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.inflight-timeout-ms:2500}")
    private long inflightTimeoutMs;

    @org.springframework.beans.factory.annotation.Value("${query-transformer.llm.timeout-ms-hint:800}")
    private long llmTimeoutMsHint;

    // Inflight dedupe to avoid cache stampede for identical prompts.
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> inflightTasks = new ConcurrentHashMap<>();

    /* LLM이 생성할 동적 버프 1회 한도 */
    private static final int MAX_DYNAMIC_BUFFS = 4;

    public QueryTransformer(ChatModel chatModel) {
        this(chatModel, Map.of(), null);
    }

    @org.springframework.beans.factory.annotation.Autowired // ✅ 이 한 줄만 추가
    public QueryTransformer(
            @org.springframework.beans.factory.annotation.Qualifier("fastChatModel") ChatModel chatModel,
            @org.springframework.beans.factory.annotation.Qualifier("queryTransformerCustomDict") Map<String, String> customDict,
            @Nullable HintExtractor hintExtractor) {
        this.chatModel = chatModel;
        this.dict = (customDict != null) ? customDict : Map.of();
        this.hintExtractor = (hintExtractor != null) ? hintExtractor : new RegexHintExtractor();
        // 캐시는 5분 동안 결과를 보존하며 최대 1000개의 프롬프트를 저장한다.
        this.llmCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(1000)
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

        // UAW: Request-scoped aux degradation/strike/compression should block additional LLM calls.
        // Cache hits are still allowed (no new external call).
        if (shouldBypassAuxLlm()) {
            try {
                AuxBlockedReason reason = bypassReason();
                String reasonCode = reason.code();
                AuxBlockTracker.markStageBlocked("queryTransformer", reason, "QueryTransformer.cachedLlm", NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
                TraceStore.put("queryTransformer.mode", "blocked");
                TraceStore.put("queryTransformer.reason", reasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer", "blocked:" + reasonCode);
                TraceStore.putIfAbsent("aux.queryTransformer.blocked", Boolean.TRUE);
                TraceStore.putIfAbsent("aux.queryTransformer.blocked.reason", reasonCode);

            } catch (Throwable ignore) {
            }
            return "";
        }

        long timeoutMs = Math.max(0L, (llmTimeoutMsHint > 0 ? llmTimeoutMsHint : 800L));

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
                    if (created.isDone()) return;
                    Future<?> toCancel = inflightTasks.get(p);
                    if (toCancel != null) {
                        toCancel.cancel(true);
                    }
					AuxDownTracker.markDegraded("query-transformer:cachedLlm", "force-kill");
                    try {
                        TraceStore.put("aux.queryTransformer", "soft-timeout(force-kill)");
                    } catch (Exception ignore) {
                    }
                    if (nightmareBreaker != null) {
                        nightmareBreaker.recordTimeout(NightmareKeys.QUERY_TRANSFORMER_RUN_LLM, "cachedLlm", "force-kill");
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
            // ✅ 요청 단위로 "보조 LLM 느림" 신호를 공유 + cancel
            Future<?> task = inflightTasks.get(prompt);
            if (task != null) task.cancel(true);
            if (nightmareBreaker != null) {
                nightmareBreaker.recordTimeout(NightmareKeys.QUERY_TRANSFORMER_RUN_LLM, "cachedLlm", "soft-timeout");
            }
			AuxDownTracker.markDegraded("query-transformer:cachedLlm", "soft-timeout", te);
            TraceStore.put("aux.queryTransformer", "soft-timeout");
            if (faultMaskingLayerMonitor != null) {
                faultMaskingLayerMonitor.record("query-transformer:cachedLlm", te, prompt, "soft-timeout");
            }
            return "";
        } catch (InterruptedException ie) {
            // Interrupt Hygiene: clear flag to avoid poisoning request thread
            Thread.interrupted();
            Future<?> task = inflightTasks.get(prompt);
            if (task != null) task.cancel(true);
            if (nightmareBreaker != null) {
                nightmareBreaker.recordFailure(NightmareKeys.QUERY_TRANSFORMER_RUN_LLM,
                        NightmareBreaker.FailureKind.INTERRUPTED,
                        ie,
                        "cachedLlm");
            }
			AuxDownTracker.markDegraded("query-transformer:cachedLlm", "interrupted", ie);
            TraceStore.put("aux.queryTransformer", "interrupted");
            if (faultMaskingLayerMonitor != null) {
                faultMaskingLayerMonitor.record("query-transformer:cachedLlm", ie, prompt, "interrupted");
            }
            return "";
        } catch (ExecutionException ee) {
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private boolean shouldBypassAuxLlm() {
        if (!novaOrchEnabled || !novaOrchQueryTransformerEnabled) {
            return true;
        }
        if (nightmareBreaker != null && nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.QUERY_TRANSFORMER_RUN_LLM)) {
            return true;
        }
		GuardContext ctx = GuardContextHolder.getOrDefault();
		return ctx != null && ((ctx.isAuxDegraded() || ctx.isAuxHardDown())
				|| ctx.isStrikeMode() || ctx.isCompressionMode() || ctx.isBypassMode());
    }


        private AuxBlockedReason bypassReason() {
        boolean breakerOpen = false;

        try {
            if (!novaOrchEnabled || !novaOrchQueryTransformerEnabled) {
                return AuxBlockedReason.DISABLED;
            }
        } catch (Throwable ignore) {
        }

        try {
            breakerOpen = nightmareBreaker != null && nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.QUERY_TRANSFORMER_RUN_LLM);
        } catch (Throwable ignore) {
        }

        GuardContext ctx = GuardContextHolder.getOrDefault();
        return AuxBlockTracker.resolveReason(breakerOpen, ctx);
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
				AuxDownTracker.markHardDown("query-transformer:runLLM", "breaker-open", e);
                TraceStore.put("aux.queryTransformer", "breaker-open");
                return "";
            }
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

            // 빈 응답도 실패로 취급 (Silent Failure 방지)
            if (text == null || text.isBlank()) {
                if (nightmareBreaker != null) {
                    nightmareBreaker.recordBlank(breakerKey, prompt);
                }
                return "";
            }

            // FriendShield(회피/사과/정보없음) 패턴은 silent failure로 기록하고 우회
            if (FriendShieldPatternDetector.looksLikeSilentFailure(text)) {
                if (nightmareBreaker != null) {
                    nightmareBreaker.recordSilentFailure(breakerKey, prompt, "friendshield");
                }
                return "";
            }

            if (nightmareBreaker != null) {
                nightmareBreaker.recordSuccess(breakerKey, elapsedMs(started));
            }
            return text;
        } catch (Exception e) {
            // QueryTransformer는 '옵션' 전처리 단계이므로, 여기서 발생한 Interrupted를
            // 요청 스레드에 전파(재-interrupt)하면 이후 파이프라인이 연쇄적으로 깨질 수 있다.
            // (Error_ws.txt에서 관찰된 케이스: InterruptedException → breaker OPEN)
            // 따라서 LLM 실패는 분류/기록만 하고, 호출자는 원문 쿼리로 계속 진행하도록 한다.
            NightmareBreaker.FailureKind kind = classifyLlmFailure(e);

            if (kind == NightmareBreaker.FailureKind.INTERRUPTED) {
                // pooled worker 오염 방지: interrupt 플래그를 정리 (cancellation/teardown signal)
                Thread.interrupted();
            }

            if (faultMaskingLayerMonitor != null) {
                faultMaskingLayerMonitor.record("query-transformer:runLLM", e, prompt, "caught-and-fallback");
            }

            if (nightmareBreaker != null) {
                nightmareBreaker.recordFailure(breakerKey, kind, e, prompt);
            }
            return "";
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
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
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return List.of(normalizedQuery);
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
        return dedupBySimilarity(out, 0.86);
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
            return List.of();
        }

        // Feature toggle / bean optionality
        if (!novaOrchEnabled || !novaOrchQueryTransformerEnabled || anchorNarrower == null) {
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
                        || UNWANTED_WORD_PATTERN.matcher(p).find())
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