package com.example.lms.service.disambiguation;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;

import com.example.lms.infra.resilience.IrregularityProfiler;
import com.example.lms.infra.resilience.FriendShieldPatternDetector;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.infra.resilience.AuxDownTracker;
import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.infra.resilience.AuxBlockedReason;
import com.example.lms.infra.resilience.NoiseRoutingGate;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.debug.DebugEventLevel;

import com.example.lms.service.correction.DomainTermDictionary;
import com.example.lms.service.llm.LlmClient;
import com.example.lms.search.NoiseClipper;
import com.example.lms.search.TraceStore;
import com.example.lms.prompt.DisambiguationPromptBuilder;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.transform.QueryTransformerSubQueryFallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 단일 LLM 호출로 사용자의 질의를 분석하여 {@link DisambiguationResult}로 돌려주는 서비스.
 * <p>
 * 과거 버전에서는 게임/교육 이외의 일반 질의를 {@link NonGameEntityHeuristics} 로
 * 차단하거나, {@link DomainTermDictionary} 에 등록된 용어를 발견하면 LLM을
 * 우회(bypass)하는 방식이었으나, 이제는 다음 원칙을 따른다.
 * <ul>
 *     <li>NonGameEntityHeuristics 는 "소프트 힌트"로만 사용하고, 절대 질의를 차단하지 않는다.</li>
 *     <li>DomainTermDictionary 에서 찾은 보호어는 LLM 프롬프트에 힌트(seed)로만 제공한다.</li>
 *     <li>항상 LLM을 한 번 호출해 JSON → {@link DisambiguationResult} 로 역직렬화한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class QueryDisambiguationService {

    private static final Logger log = LoggerFactory.getLogger(QueryDisambiguationService.class);

    private final LlmClient llmClient;
    private final ObjectMapper om; // spring-boot-starter-json 기본 Bean
    private final DomainTermDictionary domainTermDictionary;
    private final DisambiguationPromptBuilder promptBuilder;
    private final NoiseClipper noiseClipper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IrregularityProfiler irregularityProfiler;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FailurePatternOrchestrator failurePatterns;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DebugEventStore debugEventStore;

    @Value("${nova.orch.enabled:true}")
    private boolean novaOrchEnabled;

    @Value("${nova.orch.query-transformer.enabled:true}")
    private boolean novaOrchQueryTransformerEnabled;

    private static double boundedDoubleProperty(String key, double fallback, String stage) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return Double.isFinite(parsed) ? Math.max(0.0d, Math.min(1.0d, parsed)) : fallback;
        } catch (NumberFormatException ignore) {
            traceSuppressed(stage, ignore);
            return fallback;
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null ? "unknown" : failure.getClass().getSimpleName();
        try {
            TraceStore.put("disambig.suppressed." + safeStage, true);
            TraceStore.put("disambig.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[Disambiguation] suppressed trace failed stage={} errorType={}",
                    safeStage, traceFailure.getClass().getSimpleName());
        }
    }

    /**
     * 사용자 질의와 최근 대화 히스토리를 기반으로 질의를 해석한다.
     *
     * @param query   사용자의 원본 질의
     * @param history 포맷팅된 최근 대화 히스토리 (가장 오래된 것부터)
     * @return LLM이 생성한 {@link DisambiguationResult}, 실패 시 fallback 결과
     */
    public DisambiguationResult clarify(String query, List<String> history) {
        if (query == null || query.isBlank()) {
            return fallback("");
        }
        final String queryHash = SafeRedactor.hashValue(query);
        final int queryLength = query.length();

        // 1) 일반 도메인(비 게임/교육) 탐지 시 더 이상 차단하지 않고, 로그만 남긴다.
        if (NonGameEntityHeuristics.containsSuspiciousPair(query)) {
            log.debug("[Disambig] NonGameEntityHeuristics hit (SOFT): queryHash={} queryLength={}",
                    queryHash, queryLength);
        }

        // 2) 보호어 기반 seed 생성 (LLM 호출을 우회하지 않는다)
        DisambiguationResult seed = null;
        Set<String> protectedTerms = Collections.emptySet();
        try {
            protectedTerms = domainTermDictionary.findKnownTerms(query);
            if (protectedTerms != null && !protectedTerms.isEmpty()) {
                seed = createSeedResult(query, protectedTerms);
                log.debug("[Disambig] seed created from protected terms: {}", protectedTerms);
            }
        } catch (Exception e) {
            log.debug("[Disambig] DomainTermDictionary lookup failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }

        // Query hygiene: definitional/meaning questions should not pay an extra LLM round-trip.
        // This reduces blank-response fallbacks and improves latency/stability.
        if (shouldSkipLlmForDefinitionalQuery(query, history)) {
            return (seed != null) ? seed : fallback(query);
        }
        if (shouldSkipLlmForDiagnosticSmokeQuery(query, history)) {
            traceDisambiguationSkip("diagnostic_smoke", query);
            return (seed != null) ? seed : fallback(query);
        }

        // UAW: Request-scoped aux degradation flags should block additional disambiguation LLM calls.
        // Even if this service is invoked, we do not want to fan out to more auxiliary LLM requests.
        try {
            var gctx = GuardContextHolder.getOrDefault();
            boolean shouldBlock = gctx != null && (gctx.isAuxHardDown() || gctx.isAuxDegraded()
                    || gctx.isCheapSearchMode()
                    || gctx.isStrikeMode() || gctx.isCompressionMode() || gctx.isBypassMode());
            if (shouldBlock) {
                AuxBlockedReason reason = AuxBlockedReason.fromContext(gctx);

                // NoiseGate: COMPRESSION-only disambiguation blocks can be a false positive.
                // Allow a small escape probability so the stage can still run sometimes.
                boolean noiseEscaped = false;
                double noiseEscapeP = 0.0;
                double noiseRoll = 1.0;
                if (reason == AuxBlockedReason.COMPRESSION) {
                    try {
                        boolean stageNoiseEnabled = Boolean.parseBoolean(System.getProperty("orch.noiseGate.disambig.compression.enabled", "true"));
                        if (stageNoiseEnabled) {
                            double irr = (gctx != null) ? gctx.getIrregularityScore() : 0.0;
                            double max = boundedDoubleProperty(
                                    "orch.noiseGate.disambig.compression.escapeP.max", 0.12d,
                                    "noiseGate.max.parse");
                            double min = boundedDoubleProperty(
                                    "orch.noiseGate.disambig.compression.escapeP.min", 0.02d,
                                    "noiseGate.min.parse");
                            double t = Math.min(1.0, Math.max(0.0, (irr - 0.35) / 0.45));
                            double escapeP = max + (min - max) * t;

                            NoiseRoutingGate.GateDecision gd = NoiseRoutingGate.decideEscape("disambig.compression", escapeP, gctx);
                            noiseEscaped = gd.escape();
                            noiseEscapeP = gd.escapeP();
                            noiseRoll = gd.roll();
                        }
                    } catch (Throwable ignore) {
                        traceSuppressed("noiseGate.compression", ignore);
                        log.debug("[Disambig] fail-soft stage={}", "noiseGate.compression");
                    }
                }

                if (!noiseEscaped) {
                    try {
                        AuxBlockTracker.markStageBlocked("disambiguation", reason, "QueryDisambiguationService.clarify", NightmareKeys.DISAMBIGUATION_CLARIFY);
                        TraceStore.putIfAbsent("aux.disambiguation", "blocked:" + reason.code());
                        TraceStore.put("aux.disambiguation.skipped", true);
                        TraceStore.put("aux.disambiguation.skipReason", reason.code());
                    } catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("auxBlocked.trace", ignore); log.debug("[Disambig] fail-soft stage={}", "auxBlocked.trace"); }
                    log.debug("[Disambig] blocked by request-scoped signal ({}), using deterministic path. queryHash={} queryLength={}",
                            reason.code(), queryHash, queryLength);
                    return seed != null ? seed : fallback(query);
                }

                // Noise escape breadcrumbs (best-effort)
                try {
                    TraceStore.put("disambiguation.noiseEscape", true);
                    TraceStore.put("disambiguation.noiseEscape.escapeP", noiseEscapeP);
                    TraceStore.put("disambiguation.noiseEscape.roll", noiseRoll);
                } catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("noiseEscape.trace", ignore); log.debug("[Disambig] fail-soft stage={}", "noiseEscape.trace"); }
                try {
                    java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
                    meta.put("reason", reason.code());
                    meta.put("escapeP", noiseEscapeP);
                    meta.put("roll", noiseRoll);
                    AuxBlockTracker.markStageNoiseOverride(
                            "disambiguation",
                            "QueryDisambiguationService.noiseEscape(" + reason.code() + ")",
                            noiseEscapeP,
                            meta);
                } catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("noiseEscape.auxOverride", ignore); log.debug("[Disambig] fail-soft stage={}", "noiseEscape.auxOverride"); }
            }
        } catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("auxBlockCheck.outer", ignore); log.debug("[Disambig] fail-soft stage={}", "auxBlockCheck.outer"); }

        // ✅ Aux-down 조기 공유
        // [PATCH] Disambiguation은 "선택적 보조단"이다.
        // 보조단 브레이커 open을 hard-down/highRisk로 전파하면 STRIKE/BYPASS 남발로 품질이 급락한다.
        // (ERRORS_A: aux_down_hard → STRIKE/BYPASS → rerank OFF → web 상위 1~2개 고정)
        // 따라서 breaker-open은 degraded + 작은 irregularity bump로만 기록하고, 즉시 deterministic path로 우회한다.
        if (nightmareBreaker != null && novaOrchEnabled && novaOrchQueryTransformerEnabled) {
            boolean auxDown = nightmareBreaker.isAnyOpen(
                    NightmareKeys.QUERY_TRANSFORMER_RUN_LLM,
                    NightmareKeys.DISAMBIGUATION_CLARIFY,
                    NightmareKeys.FAST_LLM_COMPLETE
            );
            if (auxDown) {
                // [PATCH] disambiguation breaker-open => degraded only (no hard-down/highRisk)
                try {
                    AuxDownTracker.markSoft("disambiguation", "breaker-open");
                } catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("breakerOpen.auxDownSoft", ignore); log.debug("[Disambig] fail-soft stage={}", "breakerOpen.auxDownSoft"); }
                if (irregularityProfiler != null) {
                    irregularityProfiler.bump(GuardContextHolder.getOrDefault(), 0.05, "disambiguation_breaker_open");
                }
                try {
                    TraceStore.put("aux.disambiguation", "aux-down");
                    TraceStore.put("aux.disambiguation.skipped", true);
                } catch (Exception ignore) { DisambiguationTraceSuppressions.trace("breakerOpen.trace", ignore); log.debug("[Disambig] fail-soft stage={}", "breakerOpen.trace"); }
                                if (debugEventStore != null) {
                    String qh = "";
                    try {
                        qh = org.apache.commons.codec.digest.DigestUtils.sha1Hex(String.valueOf(query));
                    } catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("breakerOpen.queryHash", ignore); log.debug("[Disambig] fail-soft stage={}", "breakerOpen.queryHash"); }
                    debugEventStore.emit(
                            DebugProbeType.QUERY_TRANSFORMER,
                            DebugEventLevel.WARN,
                            "disambiguation.aux_down.breaker_open",
                            "Disambiguation skipped due to aux-down (breaker open)",
                            "QueryDisambiguationService.clarify",
                            java.util.Map.of(
                                    "queryHash", qh,
                                    "hasSeed", seed != null,
                                    "breakers", java.util.List.of(
                                            NightmareKeys.QUERY_TRANSFORMER_RUN_LLM,
                                            NightmareKeys.DISAMBIGUATION_CLARIFY,
                                            NightmareKeys.FAST_LLM_COMPLETE)),
                            null);
                }
                log.warn("[Disambig] aux-down detected, returning fallback immediately. queryHash={} queryLength={}",
                        queryHash, queryLength);
                return seed != null ? seed : fallback(query);
            }
        }


        // ✅ Failure-pattern cooldown: when disambiguation has recently been failing (blank/timeout),
        // skip the LLM hop and fall back deterministically to keep orchestration latency stable.
        if (failurePatterns != null) {
            try {
                if (failurePatterns.isCoolingDown("disambig")) {
                    try {
                        TraceStore.put("disambig.cooldown", true);
                    } catch (Exception ignore) { DisambiguationTraceSuppressions.trace("cooldown.trace", ignore); log.debug("[Disambig] fail-soft stage={}", "cooldown.trace"); }
                    if (irregularityProfiler != null) {
                        irregularityProfiler.bump(GuardContextHolder.getOrDefault(), 0.10, "disambiguation_cooldown");
                    }
                    if (debugEventStore != null) {
                        try {
                            String qh = org.apache.commons.codec.digest.DigestUtils.sha1Hex(String.valueOf(query));
                            debugEventStore.emit(
                                    DebugProbeType.QUERY_TRANSFORMER,
                                    DebugEventLevel.INFO,
                                    "disambiguation.cooldown.skip",
                                    "Disambiguation skipped due to failure-pattern cooldown",
                                    "QueryDisambiguationService.clarify",
                                    java.util.Map.of("queryHash", qh, "hasSeed", seed != null),
                                    null);
                        } catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("cooldown.debugEvent", ignore); log.debug("[Disambig] fail-soft stage={}", "cooldown.debugEvent"); }
                    }
                    return (seed != null) ? seed : fallback(query);
                }
            } catch (Exception ignore) {
                DisambiguationTraceSuppressions.trace("cooldown.outer", ignore);
                log.debug("[Disambig] fail-soft stage={}", "cooldown.outer");
            }
        }

        // 3) 범용 프롬프트 생성
        String queryDisambiguationPrompt = promptBuilder.buildUniversal(query, history, seed);

        // ✅ 서킷 오픈 시 즉시 우회 (보조 단계가 전체 오케스트레이션을 끌지 않게)
        NightmareBreaker.CallPermit permit = null;
        boolean permitCompleted = false;
        boolean modelResponseReceived = false;
        long modelStarted = 0L;
        if (nightmareBreaker != null) {
            try {
                permit = nightmareBreaker.acquire(NightmareKeys.DISAMBIGUATION_CLARIFY, "disambiguation");
            } catch (NightmareBreaker.OpenCircuitException oce) {
                DisambiguationTraceSuppressions.trace("breakerOpen.exception", oce);
                log.debug("[Disambig] fail-soft stage={}", "breakerOpen.exception");
                // [PATCH] 보조단 open은 degraded만.
                try {
                    AuxDownTracker.markSoft("disambiguation", "breaker-open", oce);
                } catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("breakerOpen.exceptionSoft", ignore); log.debug("[Disambig] fail-soft stage={}", "breakerOpen.exceptionSoft"); }
                if (irregularityProfiler != null) {
                    irregularityProfiler.bump(GuardContextHolder.getOrDefault(), 0.05, "disambiguation_breaker_open");
                }
                try {
                    TraceStore.put("aux.disambiguation", "breaker-open");
                    TraceStore.put("aux.disambiguation.skipped", true);
                } catch (Exception ignore) { DisambiguationTraceSuppressions.trace("breakerOpen.exceptionTrace", ignore); log.debug("[Disambig] fail-soft stage={}", "breakerOpen.exceptionTrace"); }
                if (debugEventStore != null) {
                    try {
                        String qh = org.apache.commons.codec.digest.DigestUtils.sha1Hex(String.valueOf(query));
                        debugEventStore.emit(
                                DebugProbeType.QUERY_TRANSFORMER,
                                DebugEventLevel.WARN,
                                "disambiguation.breaker_open",
                                "Disambiguation bypassed because breaker is open",
                                "QueryDisambiguationService.clarify",
                                java.util.Map.of("queryHash", qh, "hasSeed", seed != null),
                                oce);
                    } catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("breakerOpen.debugEvent", ignore); log.debug("[Disambig] fail-soft stage={}", "breakerOpen.debugEvent"); }
                }
                return fallback(query);
            }
        }

        // 4) LLM 호출 및 JSON 파싱
        try {
            // 단계별 key로 breaker 상태를 공유해야 aux-down 신호가 오케스트레이션까지 전파된다.
            modelStarted = System.nanoTime();
            String raw = permit == null
                    ? llmClient.complete(queryDisambiguationPrompt)
                    : llmClient.completeWithPermit(permit, "disambiguation", queryDisambiguationPrompt);
            modelResponseReceived = true;
            if (raw == null || raw.isBlank()) {
                if (permit != null) {
                    permit.completeBlank("disambiguation");
                    permitCompleted = true;
                }
                log.warn("[Disambig] LLM returned blank response, falling back. queryHash={} queryLength={}",
                        queryHash, queryLength);
                if (debugEventStore != null) {
                    try {
                        String qh = org.apache.commons.codec.digest.DigestUtils.sha1Hex(String.valueOf(query));
                        debugEventStore.emit(
                                DebugProbeType.QUERY_TRANSFORMER,
                                DebugEventLevel.WARN,
                                "disambiguation.blank",
                                "Disambiguation LLM returned blank; falling back",
                                "QueryDisambiguationService.clarify",
                                java.util.Map.of("queryHash", qh),
                                null);
                    } catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("blank.debugEvent", ignore); log.debug("[Disambig] fail-soft stage={}", "blank.debugEvent"); }
                }

                if (irregularityProfiler != null) {
					irregularityProfiler.bump(GuardContextHolder.getOrDefault(), 0.15, "disambiguation_blank");
                }

				// ✅ blank는 "soft 관측"으로만 남기고, 오케스트레이션 STRIKE를 유발하지 않음
				try {
					AuxDownTracker.markSoft("disambiguation", "blank");
				} catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("blank.auxDownSoft", ignore); log.debug("[Disambig] fail-soft stage={}", "blank.auxDownSoft"); }

                // If we have a deterministic seed (protected terms), prefer it over a weak fallback.
                traceQueryRewriteFallback(query, "disambiguation-blank-fallback");
                return (seed != null) ? seed : fallback(query);
            }

            if (FriendShieldPatternDetector.looksLikeSilentFailure(raw)) {
                if (permit != null) {
                    permit.completeSilentFailure("disambiguation", "friendshield");
                    permitCompleted = true;
                }
                AuxDownTracker.markSoft("disambiguation", "friendshield");
                traceQueryRewriteFallback(query, "disambiguation-friendshield-fallback");
                return (seed != null) ? seed : fallback(query);
            }

            String cleaned = sanitizeJson(raw);

            DisambiguationResult r = om.readValue(cleaned, DisambiguationResult.class);
            if (r == null) {
                if (permit != null) {
                    permit.completeSilentFailure("disambiguation", "null-result");
                    permitCompleted = true;
                }
                log.warn("[Disambig] ObjectMapper produced null result, falling back. queryHash={} queryLength={}",
                        queryHash, queryLength);
                return fallback(query);
            }

            // 5) 필드 보정 및 기본값 설정
            if (r.getRewrittenQuery() == null || r.getRewrittenQuery().isBlank()) {
                r.setRewrittenQuery(query);
            }
            if (r.getConfidence() == null || r.getConfidence().isBlank()) {
                r.setConfidence("medium");
            }
            if (r.getDetectedCategory() == null || r.getDetectedCategory().isBlank()) {
                r.setDetectedCategory("UNKNOWN");
            }
            if (r.getAttributes() == null) {
                r.setAttributes(Collections.emptyMap());
            }

            if (permit != null) {
                permit.completeSuccess(Math.max(0L, (System.nanoTime() - modelStarted) / 1_000_000L));
                permitCompleted = true;
            }
            return r;
        } catch (Exception e) {
            if (permit != null && !permitCompleted) {
                NightmareBreaker.FailureKind kind = NightmareBreaker.classify(e);
                if (kind == NightmareBreaker.FailureKind.INTERRUPTED
                        || e instanceof java.util.concurrent.CancellationException) {
                    permit.completeCancelled(e, "disambiguation");
                } else if (modelResponseReceived) {
                    permit.completeSilentFailure("disambiguation", "invalid-json");
                } else {
                    permit.completeFailure(kind, e, "disambiguation");
                }
            }
            log.warn("[Disambig] LLM disambiguation failed, falling back. queryHash={} queryLength={} errorHash={} errorLength={}",
                    queryHash, queryLength, SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            if (irregularityProfiler != null) {
                // [PATCH] disambiguation 실패는 optional stage로 취급: highRisk 플래그는 올리지 않음
                irregularityProfiler.bump(GuardContextHolder.getOrDefault(), 0.05, "disambiguation_failed");
            }

			// UAW: exception is soft degrade signal for request-scoped routing.
			try {
				AuxDownTracker.markSoft("disambiguation", "exception", e);
			} catch (Throwable ignore) { DisambiguationTraceSuppressions.trace("exception.auxDownSoft", ignore); log.debug("[Disambig] fail-soft stage={}", "exception.auxDownSoft"); }

            traceQueryRewriteFallback(query, "disambiguation-exception-fallback");
            return fallback(query);
        }
    }

    private void traceQueryRewriteFallback(String query, String reason) {
        try {
            QueryTransformerSubQueryFallback.threeAxisFallback(query, reason, 3);
        } catch (Throwable ignore) {
            DisambiguationTraceSuppressions.trace("queryRewriteFallback.trace", ignore);
            log.debug("[Disambig] fail-soft stage={}", "queryRewriteFallback.trace");
        }
    }

    /**
     * DomainTermDictionary 에서 발견한 보호어를 기반으로 간단한 seed 결과를 생성한다.
     * 이 seed 는 프롬프트에 힌트로만 사용된다.
     */
    private DisambiguationResult createSeedResult(String query, Set<String> terms) {
        DisambiguationResult r = new DisambiguationResult();
        r.setRewrittenQuery(query);

        if (terms != null && !terms.isEmpty()) {
            String first = terms.iterator().next();
            r.setTargetObject(first);
        }

        r.setDetectedCategory("DICTIONARY_TERM");
        r.setConfidence("high");
        r.setScore(1.0);
        return r;
    }

    /**
     * LLM 호출 실패 시 사용할 안전한 기본 결과.
     */
    
    /**
     * LLM이 JSON 응답을 ```json ... ``` 형태의 코드펜스로 감싸서 반환하는
     * 경우를 대비해, 앞뒤 코드펜스를 제거하고 공백을 정리한다.
     */
    private static String sanitizeJson(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.startsWith("```")) {
            // opening fence with optional language specifier
            t = t.replaceFirst("^```(?:json)?\\s*", "");
            // closing fence
            t = t.replaceFirst("\\s*```\\s*$", "");
        }
        return t.strip();
    }

    /**
     * Query hygiene shortcut:
     * For short definitional/meaning questions, the LLM disambiguation hop tends to
     * add cost/latency and sometimes returns blanks. For these, we skip the LLM and
     * go straight to a deterministic path.
     */
    private boolean shouldSkipLlmForDefinitionalQuery(String query, List<String> history) {
        if (query == null) return true;
        String q = query.trim();
        if (q.isEmpty()) return true;

        String lower = q.toLowerCase(Locale.ROOT);

        boolean definitional =
                lower.contains("뭐냐") || lower.contains("뭐야") || lower.contains("뭔데")
                        || lower.contains("뜻") || lower.contains("의미") || lower.contains("정의")
                        || lower.contains("무슨 뜻") || lower.contains("무슨 의미")
                        || lower.startsWith("what is ") || lower.startsWith("what's ")
                        || lower.contains(" definition") || lower.contains(" meaning")
                        // 제품/스펙/가격류(특히 한글) → disambiguation LLM이 빈 응답/타임아웃을 유발하는 케이스가 있어 skip 대상에 포함
                        || lower.contains("사양") || lower.contains("스펙") || lower.contains("spec") || lower.contains("specs")
                        || lower.contains("가격") || lower.contains("price")
                        || lower.contains("출시") || lower.contains("출시일") || lower.contains("release date")
                        || lower.contains("무게") || lower.contains("크기") || lower.contains("배터리") || lower.contains("카메라");

        if (!definitional) return false;

        // Conservative: only skip for short queries. Longer queries often contain context.
        return q.length() <= 40;
    }

    private boolean shouldSkipLlmForDiagnosticSmokeQuery(String query, List<String> history) {
        if (query == null) return false;
        String q = query.trim();
        if (q.isEmpty()) return false;
        String lower = q.toLowerCase(Locale.ROOT);

        boolean diagnostic =
                lower.contains("스모크")
                        || lower.contains("smoke")
                        || lower.contains("heartbeat")
                        || lower.contains("헬스체크")
                        || lower.contains("health check")
                        || lower.contains("디버그")
                        || lower.contains("debug");
        boolean localUiOrRoute =
                lower.contains("ui")
                        || lower.contains("브라우저")
                        || lower.contains("browser")
                        || lower.contains("챗봇")
                        || lower.contains("chat")
                        || lower.contains("rag/auto")
                        || lower.contains("설정")
                        || lower.contains("상태")
                        || lower.contains("경로")
                        || lower.contains("route");
        if (!diagnostic || !localUiOrRoute) return false;

        // Diagnostic smoke/status prompts should stay cheap and deterministic.
        return q.length() <= 140;
    }

    private static void traceDisambiguationSkip(String reason, String query) {
        try {
            TraceStore.put("aux.disambiguation.skipped", true);
            TraceStore.put("aux.disambiguation.skipReason",
                    SafeRedactor.traceLabelOrFallback(reason, "shortcut"));
            TraceStore.put("aux.disambiguation.skipQueryHash", SafeRedactor.hashValue(query));
            TraceStore.put("aux.disambiguation.skipQueryLength", query == null ? 0 : query.length());
            if ("diagnostic_smoke".equals(reason)) {
                TraceStore.put("aux.queryTransformer.diagnosticSmokeScope", true);
                TraceStore.putIfAbsent("aux.queryTransformer.skipped", true);
                TraceStore.putIfAbsent("aux.queryTransformer.skipReason", "diagnostic_smoke");
                TraceStore.putIfAbsent("aux.queryTransformer.skipQueryHash", SafeRedactor.hashValue(query));
                TraceStore.putIfAbsent("aux.queryTransformer.skipQueryLength", query == null ? 0 : query.length());
            }
        } catch (RuntimeException ex) {
            traceSuppressed("diagnosticSkip.trace", ex);
        }
    }

	private DisambiguationResult fallback(String query) {
        // Backward-compatible alias for the all-rounder fallback.
        return fallbackAllRounder(query);
    }

    /**
     * Fail-soft fallback used when the LLM disambiguation step returns blank or throws.
     * Goal: keep tile/domain signals stable so downstream routing (search, alias, sections) doesn't collapse.
     */
    private DisambiguationResult fallbackAllRounder(String userQuery) {
        String q = userQuery == null ? "" : userQuery;
        String cleaned = q;
        try {
            if (noiseClipper != null) {
                cleaned = noiseClipper.clip(q);
            }
        } catch (Exception ignored) {
            DisambiguationTraceSuppressions.trace("fallbackAllRounder.noiseClip", ignored);
            log.debug("[Disambig] fail-soft stage={}", "fallbackAllRounder.noiseClip");
            cleaned = q;
        }
        cleaned = cleaned == null ? "" : cleaned.trim();

        String lower = cleaned.toLowerCase(Locale.ROOT);

        // 1) 기관/지원금/훈련 관련
        if (containsOrgKeywords(lower)) {
            String target = pickFirstPresent(cleaned, List.of("메이크인", "makein", "국민취업지원", "국취제", "고용센터"));
            if (target.isBlank()) target = cleaned;
            DisambiguationResult r = new DisambiguationResult();
            r.setRewrittenQuery(cleaned.isBlank() ? q : cleaned);
            r.setDetectedCategory("EDUCATION");
            r.setConfidence(cleaned.isBlank() ? "low" : "medium");
            r.setScore(0.6);
            r.setTargetObject(target);
            r.setAttributes(Map.of("fallback", "true", "org.hint", "true"));
            return r;
        }

        // 2) 게임/원신 관련
        if (containsGameKeywords(lower)) {
            // MERGE_HOOK:PROJ_AGENT::DISAMBIG_ALIAS_MAKIBA_V1
            // Canonicalize known alias/typo: 마키바 -> 마비카 (Genshin: Mavuika)
            if (cleaned.contains("마키바") && !cleaned.contains("마비카")) {
                cleaned = cleaned.replace("마키바", "마비카");
                lower = cleaned.toLowerCase(java.util.Locale.ROOT);
            }
            String target = pickFirstPresent(cleaned, List.of("원신", "genshin", "스커크", "skirk", "마비카", "마키바", "mavuika"));
            if (target.isBlank()) target = cleaned;
            DisambiguationResult r = new DisambiguationResult();
            r.setRewrittenQuery(cleaned.isBlank() ? q : cleaned);
            r.setDetectedCategory("GAME");
            r.setConfidence(cleaned.isBlank() ? "low" : "medium");
            r.setScore(0.6);
            r.setTargetObject(target);
            r.setAttributes(Map.of("fallback", "true", "game.hint", "true"));
            return r;
        }

        // 3) 기본 폴백 (GENERAL)
        DisambiguationResult r = new DisambiguationResult();
        r.setRewrittenQuery(cleaned.isBlank() ? q : cleaned);
        r.setDetectedCategory("GENERAL");
        r.setConfidence(cleaned.isBlank() ? "low" : "low");
        r.setScore(cleaned.isBlank() ? 0.0 : 0.4);
        r.setTargetObject(cleaned.isBlank() ? q : cleaned);
        r.setAttributes(Map.of("fallback", "true"));
        return r;
    }

    private static boolean containsOrgKeywords(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return lower.contains("취업지원")
                || lower.contains("국민취업지원")
                || lower.contains("국취")
                || lower.contains("위탁기관")
                || lower.contains("고용센터")
                || lower.contains("직업훈련")
                || lower.contains("내일배움")
                || lower.contains("청년")
                || lower.contains("ncs")
                || lower.contains("hrd")
                || lower.contains("메이크인")
                || lower.contains("makein");
    }

    private static boolean containsGameKeywords(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return lower.contains("원신")
                || lower.contains("genshin")
                || lower.contains("스커크")
                || lower.contains("skirk")
                || lower.contains("마비카")
                || lower.contains("마키바")
                || lower.contains("mavuika")
                || lower.contains("성유물")
                || lower.contains("파티")
                || lower.contains("빌드")
                || lower.contains("공략");
    }

    private static String pickFirstPresent(String query, List<String> candidates) {
        if (query == null || candidates == null) return "";
        for (String c : candidates) {
            if (c == null || c.isBlank()) continue;
            if (query.contains(c)) return c;
        }
        return "";
    }

    // 과거 버전과의 호환성을 위해 남겨 두지만, 더 이상 사용하지 않는 우회 경로입니다.
    @SuppressWarnings("unused")
    private DisambiguationResult bypass(String query) {
        DisambiguationResult r = new DisambiguationResult();
        r.setRewrittenQuery(query);
        r.setConfidence("high");
        r.setScore(1.0);
        return r;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
}
