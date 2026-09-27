package com.example.lms.probe;

import ai.abandonware.nova.orch.ecosystem.EcosystemBufferPool;
import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.debug.AblationPenaltyBootDumper;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.moe.RgbStrategySelector;
import com.example.lms.orchestration.OrchestrationSignals;
import com.example.lms.probe.dto.CandidateDTO;
import com.example.lms.probe.dto.SearchProbeRequest;
import com.example.lms.probe.dto.SearchProbeResponse;
import com.example.lms.probe.dto.StageSnapshot;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.burst.ExtremeZSystemHandler;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.util.QueryTypeHeuristics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class ProbeConfig {

    private static final Logger PROBE_TRACE = LoggerFactory.getLogger("PROBE_SEARCH_TRACE");
    private static final ObjectMapper PROBE_MAPPER = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EcosystemBufferPool ecosystemBufferPool;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RetrievalOrderService retrievalOrderService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObjectProvider<ExtremeZSystemHandler> extremeZSystemHandlerProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObjectProvider<RgbStrategySelector> rgbStrategySelectorProvider;

    @Bean
    ProbePipeline probePipeline(UnifiedRagOrchestrator orchestrator,
                                NightmareBreaker nightmareBreaker,
                                Environment env) {

        return (SearchProbeRequest req) -> {
            TraceStore.clear();
            try {
                SearchProbeResponse resp = new SearchProbeResponse();

            // 0) NightmareBreaker 상태(OPEN/잔여시간/최근 실패종류) 스냅샷
            StageSnapshot nb = new StageSnapshot();
            nb.name = "nightmare:state";
            if (nightmareBreaker != null) {
                nb.params.put("query-transformer:runLLM", nightmareBreaker.inspect(NightmareKeys.QUERY_TRANSFORMER_RUN_LLM));
                nb.params.put("disambiguation:clarify", nightmareBreaker.inspect(NightmareKeys.DISAMBIGUATION_CLARIFY));
                nb.params.put("keyword-selection:select", nightmareBreaker.inspect(NightmareKeys.KEYWORD_SELECTION_SELECT));
                nb.params.put("llm-fast:complete", nightmareBreaker.inspect(NightmareKeys.FAST_LLM_COMPLETE));
                nb.params.put("chat:draft", nightmareBreaker.inspect(NightmareKeys.CHAT_DRAFT));
                nb.params.put("selfask:seed", nightmareBreaker.inspect(NightmareKeys.SELFASK_SEED));
                nb.params.put("selfask:followup", nightmareBreaker.inspect(NightmareKeys.SELFASK_FOLLOWUP));
                // Probe 자체 breaker 키(있을 때만)
                nb.params.put("probe:orchestrator", nightmareBreaker.inspect("probe:orchestrator"));
            }
            resp.stages.add(nb);

            // 1) Orchestrator 요청 구성
            UnifiedRagOrchestrator.QueryRequest q = new UnifiedRagOrchestrator.QueryRequest();
            q.query = (req == null || req.query == null) ? "" : req.query;
            int topK = (req == null ? 10 : Math.max(1, req.webTopK));
            q.topK = topK;

            boolean useWeb = true;
            boolean useRag = true;
            boolean officialOnly = false;
            if (req != null && req.flags != null) {
                useWeb = req.flags.useWeb;
                useRag = req.flags.useRag;
                officialOnly = req.flags.officialSourcesOnly;
                q.seedOnly = req.flags.seedOnly;
            }

            if (req != null && req.seedMode != null && !req.seedMode.isBlank()) {
                q.seedMode = req.seedMode;
            }
            boolean ecosystemBufferSeedMode = "ecosystem-buffer".equalsIgnoreCase(q.seedMode);
            Map<String, Object> ecosystemSeedParams = new LinkedHashMap<>();
            if (ecosystemBufferSeedMode) {
                ecosystemSeedParams.put("seedMode", q.seedMode);
                ecosystemSeedParams.put("enabled", ecosystemBufferSeedEnabled(env));
                ecosystemSeedParams.put("seedCount", req == null || req.seed == null ? 0 : req.seed.size());
                ecosystemSeedParams.put("chargedCount", 0);
                ecosystemSeedParams.put("poolSize", ecosystemBufferPool == null ? 0 : ecosystemBufferPool.poolSize());
            }
            if (req != null && req.seed != null && !req.seed.isEmpty()) {
                if (ecosystemBufferSeedMode) {
                    seedEcosystemBuffer(req, q, ecosystemSeedParams, env);
                } else {
                    java.util.List<UnifiedRagOrchestrator.Doc> seeds = new java.util.ArrayList<>();
                    String source = "candidates".equalsIgnoreCase(q.seedMode)
                            ? "SEED"
                            : ("web".equalsIgnoreCase(q.seedMode) ? "WEB" : "VECTOR");
                    int rank = 1;
                    for (CandidateDTO s : req.seed) {
                        if (s == null) continue;
                        UnifiedRagOrchestrator.Doc d = new UnifiedRagOrchestrator.Doc();
                        d.title = s.title;
                        d.snippet = s.snippet;
                        d.source = (s.source != null && !s.source.isBlank()) ? s.source : source;
                        d.score = s.score;
                        d.rank = (s.rank > 0 ? s.rank : rank++);
                        java.util.Map<String, Object> meta = new java.util.HashMap<>();
                        if (s.meta != null) meta.putAll(s.meta);
                        if (s.url != null && !s.url.isBlank()) meta.put("url", s.url);
                        meta.put("_seed", true);
                        d.meta = meta;
                        // Prefer explicit url, then id
                        d.id = (s.url != null && !s.url.isBlank()) ? s.url : s.id;
                        seeds.add(d);
                    }
                    q.seedCandidates = seeds;
                }
            }

            q.useWeb = useWeb;
            q.useVector = useRag;
            // Probe는 디폴트로 WEB/RAG만 비교하기 쉽게 설정
            q.useKg = false;
            q.useBm25 = false;

            // probe에서는 planId만 태깅해 둠(Plan DSL이 있을 경우 debug에 남음)
            q.planId = "probe.search.v1";

            // officialSourcesOnly → whitelistOnly (도메인 allowlist 기반)
            q.whitelistOnly = officialOnly;

            // Probe 결과를 다양하게 보기 위해 토글은 ON, 단 컴포넌트 없으면 자동 no-op
            q.enableDiversity = true;
            q.enableBiEncoder = true;
            q.enableOnnx = true;

            // 2) 실행 (NightmareBreaker로 감싸서 probe도 fail-soft)
            UnifiedRagOrchestrator.QueryTrace trace;
            if (nightmareBreaker != null) {
                trace = nightmareBreaker.execute(
                        "probe:orchestrator",
                        q.query,
                        () -> orchestrator.queryWithTrace(q),
                        t -> (t == null || t.response == null || t.response.results == null),
                        () -> {
                            UnifiedRagOrchestrator.QueryTrace fb = new UnifiedRagOrchestrator.QueryTrace();
                            fb.response = new UnifiedRagOrchestrator.QueryResponse();
                            fb.response.requestId = "probe-fallback";
                            fb.response.planApplied = q.planId;
                            fb.response.debug.put("fallback", "nightmareBreaker_open_or_error");
                            return fb;
                        });
            } else {
                trace = orchestrator.queryWithTrace(q);
            }

            // 3) Stage snapshots 구성
            addStage(resp, "seed:injected", trace == null ? null : trace.seed,
                    Map.of("seedMode", q.seedMode,
                            "seedOnly", q.seedOnly,
                            "seedCount", (q.seedCandidates == null ? 0 : q.seedCandidates.size())));
            if (ecosystemBufferSeedMode) {
                StageSnapshot ecosystemSeed = new StageSnapshot();
                ecosystemSeed.name = "ecosystem:buffer-seed";
                ecosystemSeed.params.putAll(ecosystemSeedParams);
                resp.stages.add(ecosystemSeed);
            }
            addStage(resp, "retrieval:pool", trace == null ? null : trace.pool,
                    Map.of("poolSize", (trace == null || trace.pool == null) ? 0 : trace.pool.size()));
            addStage(resp, "retrieval:web", trace == null ? null : trace.web,
                    Map.of("webTopK", topK, "officialOnly", officialOnly));
            addStage(resp, "retrieval:vector", trace == null ? null : trace.vector,
                    Map.of("vectorTopK", topK));
            addStage(resp, "retrieval:kg", trace == null ? null : trace.kg, Map.of());
            addStage(resp, "retrieval:bm25", trace == null ? null : trace.bm25, Map.of());
            addStage(resp, "fusion:rrf", trace == null ? null : trace.fused,
                    Map.of("k", topK,
                            "rrf.k", "rag.rrf.k(=config)",
                            "rrf.w.web", "rag.rrf.wWeb(=config)",
                            "rrf.w.vector", "rag.rrf.wVector(=config)"));

            addStage(resp, "rerank:biencoder", trace == null ? null : trace.biencoder,
                    Map.of("enabled", q.enableBiEncoder));
            addStage(resp, "rerank:dpp", trace == null ? null : trace.dpp,
                    Map.of("enabled", q.enableDiversity,
                            "lambda", q.diversityLambda));
            addStage(resp, "rerank:onnx", trace == null ? null : trace.onnx,
                    Map.of("enabled", q.enableOnnx));

            // 디버그 맵도 stage로 노출(너무 커질 수 있으니 Map 그대로)
            StageSnapshot dbg = new StageSnapshot();
            dbg.name = "debug:orchestrator";
            if (trace != null && trace.response != null && trace.response.debug != null) {
                dbg.params.putAll(new LinkedHashMap<>(trace.response.debug));
            }
            resp.stages.add(dbg);

            // Final results
            List<UnifiedRagOrchestrator.Doc> finals = (trace == null || trace.finalResults == null)
                    ? (trace == null || trace.response == null ? List.of() : trace.response.results)
                    : trace.finalResults;

            if (finals != null) {
                for (int i = 0; i < finals.size(); i++) {
                    UnifiedRagOrchestrator.Doc d = finals.get(i);
                    if (d == null) continue;
                    CandidateDTO c = new CandidateDTO();
                    c.id = d.id;
                    c.title = d.title;
                    c.snippet = d.snippet;
                    Object url = (d.meta == null) ? null : d.meta.get("url");
                    c.url = url != null ? String.valueOf(url) : (d.id != null && d.id.startsWith("http") ? d.id : null);
                    c.source = d.source;
                    c.score = d.score;
                    c.rank = (d.rank > 0 ? d.rank : i + 1);
                    c.meta = d.meta;
                    resp.finalResults.add(c);
                }
            }

            // 4) Probe-only snapshots (for diagnosing starvation / QTX blank / Brave disable / matryoshka slicing)
            StageSnapshot heur = new StageSnapshot();
            heur.name = "query:heuristics";
            String qq = (q.query == null) ? "" : q.query;
            heur.params.put("definitional", QueryTypeHeuristics.isDefinitional(qq));
            heur.params.put("entityLike", QueryTypeHeuristics.looksLikeEntityQuery(qq));
            resp.stages.add(heur);

            StageSnapshot brave = new StageSnapshot();
            brave.name = "brave:config";
            try {
                brave.params.put("gpt-search.brave.enabled", env == null ? null : env.getProperty("gpt-search.brave.enabled"));
                brave.params.put("nova.provider.brave.key.conflict", env == null ? null : env.getProperty("nova.provider.brave.key.conflict"));
                brave.params.put("keyPresent.gpt-search.brave.subscription-token",
                        hasUsableConfigValue(env, "gpt-search.brave.subscription-token"));
                brave.params.put("keyPresent.gpt-search.brave.api-key",
                        hasUsableConfigValue(env, "gpt-search.brave.api-key"));
                boolean envGptApi = envPresent("GPT_SEARCH_BRAVE_API_KEY");
                boolean envBraveApi = envPresent("BRAVE_API_KEY");
                boolean envBraveFree = envPresent("BRAVE_API_KEY_FREE");
                boolean envGptSub = envPresent("GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN");
                boolean envBraveSub = envPresent("BRAVE_SUBSCRIPTION_TOKEN");

                brave.params.put("envPresent.GPT_SEARCH_BRAVE_API_KEY", envGptApi);
                brave.params.put("envPresent.BRAVE_API_KEY", envBraveApi);
                brave.params.put("envPresent.BRAVE_API_KEY_FREE", envBraveFree);
                brave.params.put("envPresent.GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN", envGptSub);
                brave.params.put("envPresent.BRAVE_SUBSCRIPTION_TOKEN", envBraveSub);
                brave.params.put("envPresent.subscriptionToken.retired", envGptSub || envBraveSub);
                brave.params.put("envPresent.subscriptionToken.winner", null);
                brave.params.put("envPresent.apiKey.single", (envGptApi ^ envBraveApi));
                brave.params.put("envPresent.apiKey.winner",
                        envGptApi ? "GPT_SEARCH_BRAVE_API_KEY"
                                : (envBraveApi ? "BRAVE_API_KEY" : null));
            } catch (Exception e) {
                traceSkipped("brave_config", e);
            }
            resp.stages.add(brave);

            StageSnapshot guardCfg = new StageSnapshot();
            guardCfg.name = "guard:config";
            try {
                guardCfg.params.put("probe.search.console-trace.enabled",
                        env == null ? null : env.getProperty("probe.search.console-trace.enabled", "true"));
                guardCfg.params.put("probe.search.console-trace.kv.enabled",
                        env == null ? null : env.getProperty("probe.search.console-trace.kv.enabled", "false"));
                guardCfg.params.put("guard.detour.force-escalate.regen-llm.enabled",
                        env == null ? null : env.getProperty("guard.detour.force-escalate.regen-llm.enabled"));
                guardCfg.params.put("guard.detour.cheap-retry.regen-llm.enabled",
                        env == null ? null : env.getProperty("guard.detour.cheap-retry.regen-llm.enabled"));
            } catch (Exception e) {
                traceSkipped("guard_config", e);
            }
            resp.stages.add(guardCfg);

            seedHarmonyRouteTrace(q, nightmareBreaker);
            AblationPenaltyBootDumper.seedCurrentTrace();
            seedExtremeZHarmonyTrace();
            seedCfvmHarmonyTrace();
            seedMoeHarmonyTrace();

            StageSnapshot ts = new StageSnapshot();
            ts.name = "trace:selected";
            try {
                Map<String, Object> all = TraceStore.getAll();
                ts.params.put("trace.size", all == null ? 0 : all.size());
                if (all != null) {
                    // Keep this list small & stable for log grep / dashboards
                    for (String k : List.of(
                            "web.failsoft.outCount",
                            "stageCountsSelectedFromOut",
                            "web.failsoft.starvationFallback.trigger",
                            "starvationFallback.trigger",
                            "web.await.last",
                            "web.await.events",
                            "web.await.brave.disabledReason",
                            "web.brave.cooldown.effectiveDelayMs",
                            "aux.queryTransformer.degraded",
                            "aux.queryTransformer.degraded.reason",
                            "aux.queryTransformer.degraded.count",
                            "qtx.normalized.blankRecovered",
                            "qtx.cheapFallback.recovered",
                            "keywordSelection.fallback.seedSource",
                            "embed.actualDim",
                            "embed.targetDim",
                            "embed.matryoshka.sliced",
                            "embed.matryoshka.strategy",
                            "embed.matryoshka.slice.actual",
                            "embed.matryoshka.slice.target",
                            "embed.matryoshka.slice.reductionRatio",
                            "embed.matryoshka.slice.expectedDistanceOpsRatio",
                            "embed.matryoshka.slice.expectedDistanceOpsSpeedup",
                            "boosterMode.active",
                            "boosterMode.excludedModes",
                            "boosterMode.priority",
                            "boosterMode.conflictResolved",
                            "boosterMode.exclusionReason",
                            "routing.executionPlan.primaryMode",
                            "routing.executionPlan.triggers",
                            "routing.executionPlan.applied",
                            "routing.executionPlan.applied.primaryMode",
                            "routing.executionPlan.applied.triggers",
                            "routing.executionPlan.applied.stages",
                            "specialMode.conflict.suppressed",
                            "retrievalOrder.lastSetBy",
                            "retrievalOrder.lastOrder",
                            "retrievalOrder.authority.owner",
                            "retrievalOrder.authority.suppressedOwner",
                            "retrievalOrder.authority.reason",
                            "retrievalOrder.authority.suppressedReason",
                            "ablation.score.current",
                            "extremeZ.cancelShieldWrapped",
                            "extremeZ.timeBudgetConsumedMs",
                            "cfvm.boltzmannTemp",
                            "cfvm.tempAnnealApplied",
                            "moe.evolverPlateRegistered",
                            "moe.artplate.evolver.available",
                            "hypernova.twpmP",
                            "hypernova.cvarFusedScore",
                            "hypernova.cvarAlpha",
                            "hypernova.cvarPhi",
                            "hypernova.clampApplied",
                            "hypernova.dppApplied",
                            "hypernova.dppInputCount",
                            "hypernova.dppOutputCount",
                            "hypernova.sourceScoreScaleMismatchCount",
                            "hypernova.sourceScoreScaleMismatchPolicy",
                            "nova.hypernova.riskK.used",
                            "nova.hypernova.riskK.candidateCount",
                            "nova.hypernova.riskK.totalK",
                            "nova.hypernova.riskK.alloc.sum",
                            "hypernova.riskKAlloc",
                            "llm.modelGuard.triggered",
                            "llm.modelGuard.mode",
                            "llm.modelGuard.endpoint",
                            "llm.modelGuard.failReason",
                            "llm.modelGuard.requestedModelHash",
                            "llm.modelGuard.requestedModelLength",
                            "llm.modelGuard.substituteChatModelHash",
                            "llm.modelGuard.substituteChatModelLength",
                            "guard.forceEscalateOverDegrade",
                            "guard.forceEscalateOverDegrade.by",
                            "guard.detour.forceEscalate",
                            "guard.detour.forceEscalate.by",
                            "guard.detour.cheapRetry.forceEscalate",
                            "guard.detour.cheapRetry.forceEscalate.by",
                            "guard.detour.cheapRetry.regen.skip",
                            "guard.detour.cheapRetry.web.calls",
                            "guard.detour.cheapRetry.regen.calls",
                            "needle.triggered",
                            "needle.web.calls",
                            "web.failsoft.soakKpiJson.last"
                    )) {
                        if (all.containsKey(k)) {
                            ts.params.put(k, all.get(k));
                        }
                    }
                    putSelectedTraceValue(ts.params, "outCount", firstNonNull(
                            all.get("outCount"), all.get("web.failsoft.outCount")));
                    putSelectedTraceValue(ts.params, "cacheOnly.merged.count", firstNonNull(
                            all.get("cacheOnly.merged.count"), all.get("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count")));
                    int tracePoolItemCount = TraceStore.getPoolItems().size();
                    putSelectedTraceValue(ts.params, "tracePool.size", tracePoolSizeValue(firstNonNull(
                            all.get("tracePool.size"), all.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size")),
                            tracePoolItemCount));
                    putSelectedTraceValue(ts.params, "rescueMerge.used", tracePoolItemCount > 0 ? Boolean.TRUE : firstNonNull(
                            all.get("rescueMerge.used"), all.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used")));
                    putSelectedTraceValue(ts.params, "starvationFallback.used", firstNonNull(
                            all.get("starvationFallback.used"), all.get("web.failsoft.starvationFallback.used")));
                    putSelectedTraceLabel(ts.params, "starvationFallback.poolUsed", firstNonNull(
                            all.get("starvationFallback.poolUsed"), all.get("web.failsoft.starvationFallback.poolUsed")));
                    putSelectedTraceValue(ts.params, "starvationFallback.pool.safe.size", firstNonNull(
                            all.get("starvationFallback.pool.safe.size"), all.get("web.failsoft.starvationFallback.pool.safe.size")));
                    putSelectedTraceValue(ts.params, "starvationFallback.pool.dev.size", firstNonNull(
                            all.get("starvationFallback.pool.dev.size"), all.get("web.failsoft.starvationFallback.pool.dev.size")));
                    putSelectedTraceValue(ts.params, "starvationFallback.count", firstNonNull(
                            all.get("starvationFallback.count"), all.get("web.failsoft.starvationFallback.count")));
                    putSelectedTraceValue(ts.params, "starvationFallback.added", firstNonNull(
                            all.get("starvationFallback.added"), all.get("web.failsoft.starvationFallback.added")));
                    putSelectedTraceValue(ts.params, "starvationFallback.poolSafeEmpty", firstNonNull(
                            all.get("starvationFallback.poolSafeEmpty"), all.get("web.failsoft.starvationFallback.poolSafeEmpty"), all.get("poolSafeEmpty")));
                    putSelectedTraceValue(ts.params, "poolSafeEmpty", firstNonNull(
                            all.get("poolSafeEmpty"), all.get("starvationFallback.poolSafeEmpty"), all.get("web.failsoft.starvationFallback.poolSafeEmpty")));
                    putSelectedTraceValue(ts.params, "vectorFallback.used", firstNonNull(
                            all.get("vectorFallback.used"), all.get("retrieval.vectorFallback.used")));
                    putSelectedTraceValue(ts.params, "vectorFallback.reason", firstNonNull(
                            all.get("vectorFallback.reason"), all.get("retrieval.vectorFallback.reason")));
                    putSelectedTraceValue(ts.params, "vectorFallback.effectiveTopK", firstNonNull(
                            all.get("vectorFallback.effectiveTopK"), all.get("retrieval.vectorFallback.effectiveTopK")));
                    for (String key : List.of(
                            "ecosystem.recirculate.used",
                            "ecosystem.recirculate.count",
                            "ecosystem.recirculate.safe",
                            "ecosystem.recirculate.allUnverified",
                            "ecosystem.pool.size",
                            "ecosystem.recycled.total",
                            "ecosystem.ammonia.score",
                            "ecosystem.ammonia.quarantined",
                            "ecosystem.ammonia.safe",
                            "ecosystem.ammonia.threshold",
                            "ecosystem.ammonia.surgeBlocked")) {
                        putSelectedTraceValue(ts.params, key, all.get(key));
                    }
                    putSelectedTraceTrigger(ts.params, ecosystemFallbackTrigger(
                            firstNonNull(
                                    all.get("starvationFallback.trigger"),
                                    all.get("web.failsoft.starvationFallback.trigger"),
                                    all.get("web.failsoft.starvationFallback")),
                            all.get("ecosystem.recirculate.used"),
                            all.get("ecosystem.recirculate.safe")));
                    putSelectedTraceValue(ts.params, "starvationFallback.poolSafeEmpty", ecosystemPoolSafeEmpty(
                            firstNonNull(
                                    all.get("starvationFallback.poolSafeEmpty"),
                                    all.get("web.failsoft.starvationFallback.poolSafeEmpty"),
                                    all.get("poolSafeEmpty")),
                            all.get("ecosystem.recirculate.used"),
                            all.get("ecosystem.recirculate.safe")));
                    putSelectedTraceValue(ts.params, "poolSafeEmpty", ecosystemPoolSafeEmpty(
                            firstNonNull(
                                    all.get("poolSafeEmpty"),
                                    all.get("starvationFallback.poolSafeEmpty"),
                                    all.get("web.failsoft.starvationFallback.poolSafeEmpty")),
                            all.get("ecosystem.recirculate.used"),
                            all.get("ecosystem.recirculate.safe")));
                    for (String provider : List.of("naver", "brave", "serpapi", "tavily")) {
                        String prefix = "web." + provider + ".";
                        for (String suffix : List.of("skipped", "skipped.reason", "providerDisabled", "disabledReason",
                                "failureReason", "requestedCount", "returnedCount", "afterFilterCount",
                                "providerEmpty", "afterFilterStarved", "timeout", "timeoutMs",
                                "rateLimited", "retryAfterMs")) {
                            putSelectedTraceValue(ts.params, prefix + suffix, all.get(prefix + suffix));
                        }
                    }
                }
            } catch (Exception e) {
                traceSkipped("selected_trace", e);
            }
            resp.stages.add(ts);

            // 5) Console trace (probe-only): grep-friendly JSON + optional key=value(logfmt-ish) line
            try {
                boolean consoleEnabled = env == null || Boolean.parseBoolean(
                        env.getProperty("probe.search.console-trace.enabled", "true"));
                if (consoleEnabled) {
                    logProbeConsole(req, q, trace, useWeb, useRag, officialOnly, env, resp);
                }
            } catch (Exception e) {
                traceSkipped("console_trace", e);
            }

            if (ecosystemBufferSeedMode) {
                redactProbeCandidateBodies(resp);
            }
            return resp;
            } finally {
                TraceStore.clear();
            }
        };
    }

    @Bean
    SearchProbeService searchProbeService(ProbePipeline p) {
        return new DefaultSearchProbeService(p);
    }

    private void seedHarmonyRouteTrace(UnifiedRagOrchestrator.QueryRequest q, NightmareBreaker nightmareBreaker) {
        try {
            if (TraceStore.get("retrievalOrder.lastSetBy") == null && retrievalOrderService != null) {
                retrievalOrderService.decideOrder(q == null ? null : q.query);
            }
        } catch (RuntimeException error) {
            TraceStore.put("retrievalOrder.probe.search.failed", true);
            TraceStore.put("retrievalOrder.probe.search.errorType", error.getClass().getSimpleName());
        }
        try {
            if (TraceStore.get("boosterMode.active") == null) {
                OrchestrationSignals.compute(q == null ? null : q.query,
                        nightmareBreaker,
                        GuardContextHolder.getOrDefault());
            }
        } catch (RuntimeException error) {
            TraceStore.put("boosterMode.probe.search.failed", true);
            TraceStore.put("boosterMode.probe.search.errorType", error.getClass().getSimpleName());
        }
    }

    private void seedExtremeZHarmonyTrace() {
        try {
            ExtremeZSystemHandler handler = extremeZSystemHandlerProvider == null
                    ? null
                    : extremeZSystemHandlerProvider.getIfAvailable();
            if (handler != null) {
                handler.publishHarmonyTrace();
            }
        } catch (RuntimeException error) {
            TraceStore.put("extremeZ.harmonyTrace.failed", true);
            TraceStore.put("extremeZ.harmonyTrace.errorType", error.getClass().getSimpleName());
        }
    }

    private void seedCfvmHarmonyTrace() {
        try {
            RawMatrixBuffer buffer = rawMatrixBufferProvider == null
                    ? null
                    : rawMatrixBufferProvider.getIfAvailable();
            if (buffer != null) {
                buffer.publishHarmonyTrace();
            }
        } catch (RuntimeException error) {
            TraceStore.put("cfvm.harmonyTrace.failed", true);
            TraceStore.put("cfvm.harmonyTrace.errorType", error.getClass().getSimpleName());
        }
    }

    private void seedMoeHarmonyTrace() {
        try {
            RgbStrategySelector selector = rgbStrategySelectorProvider == null
                    ? null
                    : rgbStrategySelectorProvider.getIfAvailable();
            if (selector != null) {
                selector.publishHarmonyTrace();
            }
        } catch (RuntimeException error) {
            TraceStore.put("moe.harmonyTrace.failed", true);
            TraceStore.put("moe.harmonyTrace.errorType", error.getClass().getSimpleName());
        }
    }

    private static void addStage(SearchProbeResponse resp,
                                 String name,
                                 List<UnifiedRagOrchestrator.Doc> docs,
                                 Map<String, Object> params) {
        StageSnapshot st = new StageSnapshot();
        st.name = name;
        if (params != null && !params.isEmpty()) {
            st.params.putAll(params);
        }
        if (docs != null) {
            for (int i = 0; i < docs.size(); i++) {
                UnifiedRagOrchestrator.Doc d = docs.get(i);
                if (d == null) continue;
                CandidateDTO c = new CandidateDTO();
                c.id = d.id;
                c.title = d.title;
                c.snippet = d.snippet;
                Object url = (d.meta == null) ? null : d.meta.get("url");
                c.url = url != null ? String.valueOf(url) : (d.id != null && d.id.startsWith("http") ? d.id : null);
                c.source = d.source;
                c.score = d.score;
                c.rank = (d.rank > 0 ? d.rank : i + 1);
                c.meta = d.meta;
                st.candidates.add(c);
            }
        }
        resp.stages.add(st);
    }

    private static void logProbeConsole(SearchProbeRequest req,
                                        UnifiedRagOrchestrator.QueryRequest q,
                                        UnifiedRagOrchestrator.QueryTrace trace,
                                        boolean useWeb,
                                        boolean useRag,
                                        boolean officialOnly,
                                        Environment env,
                                        SearchProbeResponse resp) {
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("t", Instant.now().toString());

            String rid = firstNonBlank(
                    MDC.get("trace"),
                    MDC.get("traceId"),
                    TraceStore.getString("rid"),
                    TraceStore.getString("trace.id"));
            String sid = firstNonBlank(
                    MDC.get("sid"),
                    MDC.get("sessionId"),
                    TraceStore.getString("sid"));
            if (rid != null) ev.put("ridHash", SafeRedactor.hashValue(rid));
            if (sid != null) ev.put("sidHash", SafeRedactor.hashValue(sid));

            String qq = (q == null ? null : q.query);
            ev.put("queryHash", SafeRedactor.hashValue(qq));
            ev.put("queryLength", qq == null ? 0 : qq.length());
            ev.put("topK", q == null ? null : q.topK);
            ev.put("seedMode", q == null ? null : q.seedMode);
            ev.put("seedOnly", q != null && q.seedOnly);
            ev.put("seedCount", (q == null || q.seedCandidates == null) ? 0 : q.seedCandidates.size());

            Map<String, Object> flags = new LinkedHashMap<>();
            flags.put("useWeb", useWeb);
            flags.put("useRag", useRag);
            flags.put("officialOnly", officialOnly);
            ev.put("flags", flags);

            Map<String, Object> heur = new LinkedHashMap<>();
            String qStr = (qq == null ? "" : qq);
            heur.put("definitional", QueryTypeHeuristics.isDefinitional(qStr));
            heur.put("entityLike", QueryTypeHeuristics.looksLikeEntityQuery(qStr));
            ev.put("heuristics", heur);

            Map<String, Object> brave = new LinkedHashMap<>();
            brave.put("enabled", env == null ? null : env.getProperty("gpt-search.brave.enabled"));
            brave.put("keyConflict", env == null ? null : env.getProperty("nova.provider.brave.key.conflict"));
            brave.put("keyPresent.subscription-token", propPresent(env, "gpt-search.brave.subscription-token"));
            brave.put("keyPresent.api-key", propPresent(env, "gpt-search.brave.api-key"));

            boolean envSubA = envPresent("GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN");
            boolean envSubB = envPresent("BRAVE_SUBSCRIPTION_TOKEN");
            boolean envFree = envPresent("BRAVE_API_KEY_FREE");
            boolean envApiA = envPresent("GPT_SEARCH_BRAVE_API_KEY");
            boolean envApiB = envPresent("BRAVE_API_KEY");

            brave.put("envPresent.GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN", envSubA);
            brave.put("envPresent.BRAVE_SUBSCRIPTION_TOKEN", envSubB);
            brave.put("envPresent.BRAVE_API_KEY_FREE", envFree);
            brave.put("envPresent.subscriptionToken.winner", null);
            brave.put("envPresent.GPT_SEARCH_BRAVE_API_KEY", envApiA);
            brave.put("envPresent.BRAVE_API_KEY", envApiB);
            brave.put("envPresent.subscriptionToken.retired", envSubA || envSubB);
            brave.put("envPresent.apiKey.single", (envApiA ^ envApiB));
            brave.put("envPresent.apiKey.winner",
                    envApiA ? "GPT_SEARCH_BRAVE_API_KEY"
                            : (envApiB ? "BRAVE_API_KEY" : null));
            ev.put("brave", brave);

            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("probe.search.console-trace.enabled",
                    env == null ? null : env.getProperty("probe.search.console-trace.enabled", "true"));
            cfg.put("probe.search.console-trace.kv.enabled",
                    env == null ? null : env.getProperty("probe.search.console-trace.kv.enabled", "false"));
            cfg.put("guard.detour.force-escalate.regen-llm.enabled",
                    env == null ? null : env.getProperty("guard.detour.force-escalate.regen-llm.enabled"));
            cfg.put("guard.detour.cheap-retry.regen-llm.enabled",
                    env == null ? null : env.getProperty("guard.detour.cheap-retry.regen-llm.enabled"));
            ev.put("cfg", cfg);

            Map<String, Object> calls = new LinkedHashMap<>();
            calls.put("guard.detour.cheapRetry.web.calls", TraceStore.getLong("guard.detour.cheapRetry.web.calls"));
            calls.put("guard.detour.cheapRetry.regen.calls", TraceStore.getLong("guard.detour.cheapRetry.regen.calls"));
            calls.put("needle.web.calls", TraceStore.getLong("needle.web.calls"));
            ev.put("calls", calls);

            Map<String, Object> kpi = new LinkedHashMap<>();
            kpi.put("outCount", firstNonNull(TraceStore.get("outCount"), TraceStore.get("web.failsoft.outCount")));
            kpi.put("stageCountsSelectedFromOut", firstNonNull(
                    TraceStore.get("stageCountsSelectedFromOut"),
                    TraceStore.get("stageCountsSelectedFromOut.last"),
                    TraceStore.get("web.failsoft.stageCountsSelectedFromOut"),
                    TraceStore.get("web.failsoft.stageCountsSelectedFromOut.last")));
            kpi.put("cacheOnly.merged.count", firstNonNull(TraceStore.get("cacheOnly.merged.count"),
                    TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.merged.count")));
            int tracePoolItemCount = TraceStore.getPoolItems().size();
            kpi.put("tracePool.size", tracePoolSizeValue(firstNonNull(TraceStore.get("tracePool.size"),
                    TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size")),
                    tracePoolItemCount));
            kpi.put("rescueMerge.used", tracePoolItemCount > 0 ? Boolean.TRUE : firstNonNull(TraceStore.get("rescueMerge.used"),
                    TraceStore.get("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used")));
            kpi.put("starvationFallback.trigger", safeTrigger(ecosystemFallbackTrigger(
                    firstNonNull(
                            TraceStore.get("starvationFallback.trigger"),
                            TraceStore.get("web.failsoft.starvationFallback.trigger"),
                            TraceStore.get("web.failsoft.starvationFallback")),
                    TraceStore.get("ecosystem.recirculate.used"),
                    TraceStore.get("ecosystem.recirculate.safe"))));
            putKpiValue(kpi, "starvationFallback.used", firstNonNull(
                    TraceStore.get("starvationFallback.used"),
                    TraceStore.get("web.failsoft.starvationFallback.used")));
            putKpiLabel(kpi, "starvationFallback.poolUsed", firstNonNull(
                    TraceStore.get("starvationFallback.poolUsed"),
                    TraceStore.get("web.failsoft.starvationFallback.poolUsed")));
            putKpiValue(kpi, "starvationFallback.pool.safe.size", firstNonNull(
                    TraceStore.get("starvationFallback.pool.safe.size"),
                    TraceStore.get("web.failsoft.starvationFallback.pool.safe.size")));
            putKpiValue(kpi, "starvationFallback.pool.dev.size", firstNonNull(
                    TraceStore.get("starvationFallback.pool.dev.size"),
                    TraceStore.get("web.failsoft.starvationFallback.pool.dev.size")));
            putKpiValue(kpi, "starvationFallback.count", firstNonNull(
                    TraceStore.get("starvationFallback.count"),
                    TraceStore.get("web.failsoft.starvationFallback.count")));
            putKpiValue(kpi, "starvationFallback.added", firstNonNull(
                    TraceStore.get("starvationFallback.added"),
                    TraceStore.get("web.failsoft.starvationFallback.added")));
            kpi.put("poolSafeEmpty", ecosystemPoolSafeEmpty(
                    firstNonNull(TraceStore.get("poolSafeEmpty"),
                            TraceStore.get("starvationFallback.poolSafeEmpty"),
                            TraceStore.get("web.failsoft.starvationFallback.poolSafeEmpty")),
                    TraceStore.get("ecosystem.recirculate.used"),
                    TraceStore.get("ecosystem.recirculate.safe")));
            kpi.put("starvationFallback.poolSafeEmpty", ecosystemPoolSafeEmpty(
                    firstNonNull(
                            TraceStore.get("starvationFallback.poolSafeEmpty"),
                            TraceStore.get("web.failsoft.starvationFallback.poolSafeEmpty"),
                            TraceStore.get("poolSafeEmpty")),
                    TraceStore.get("ecosystem.recirculate.used"),
                    TraceStore.get("ecosystem.recirculate.safe")));
            for (String key : List.of(
                    "ecosystem.recirculate.used",
                    "ecosystem.recirculate.count",
                    "ecosystem.recirculate.safe",
                    "ecosystem.recirculate.allUnverified",
                    "ecosystem.pool.size",
                    "ecosystem.recycled.total",
                    "ecosystem.ammonia.score",
                    "ecosystem.ammonia.quarantined",
                    "ecosystem.ammonia.safe",
                    "ecosystem.ammonia.threshold",
                    "ecosystem.ammonia.surgeBlocked")) {
                putKpiValue(kpi, key, TraceStore.get(key));
            }
            putKpiValue(kpi, "vectorFallback.used", firstNonNull(
                    TraceStore.get("vectorFallback.used"),
                    TraceStore.get("retrieval.vectorFallback.used")));
            putKpiValue(kpi, "vectorFallback.reason", firstNonNull(
                    TraceStore.get("vectorFallback.reason"),
                    TraceStore.get("retrieval.vectorFallback.reason")));
            putKpiValue(kpi, "vectorFallback.effectiveTopK", firstNonNull(
                    TraceStore.get("vectorFallback.effectiveTopK"),
                    TraceStore.get("retrieval.vectorFallback.effectiveTopK")));
            for (String provider : List.of("naver", "brave", "serpapi", "tavily")) {
                String prefix = "web." + provider + ".";
                for (String suffix : List.of("skipped", "skipped.reason", "providerDisabled", "disabledReason",
                        "failureReason", "requestedCount", "returnedCount", "afterFilterCount",
                        "providerEmpty", "afterFilterStarved", "timeout", "timeoutMs",
                        "rateLimited", "retryAfterMs")) {
                    putKpiValue(kpi, prefix + suffix, TraceStore.get(prefix + suffix));
                }
            }
            ev.put("kpi", kpi);

            Map<String, Object> detour = new LinkedHashMap<>();
            detour.put("guard.forceEscalateOverDegrade", TraceStore.get("guard.forceEscalateOverDegrade"));
            detour.put("guard.forceEscalateOverDegrade.by", TraceStore.get("guard.forceEscalateOverDegrade.by"));
            detour.put("guard.detour.forceEscalate", TraceStore.get("guard.detour.forceEscalate"));
            detour.put("guard.detour.forceEscalate.by", TraceStore.get("guard.detour.forceEscalate.by"));
            detour.put("guard.detour.cheapRetry.forceEscalate", TraceStore.get("guard.detour.cheapRetry.forceEscalate"));
            detour.put("guard.detour.cheapRetry.forceEscalate.by", TraceStore.get("guard.detour.cheapRetry.forceEscalate.by"));
            detour.put("guard.detour.cheapRetry.regen.skip", TraceStore.get("guard.detour.cheapRetry.regen.skip"));
            ev.put("detour", detour);

            Map<String, Object> sizes = new LinkedHashMap<>();
            sizes.put("pool", (trace == null || trace.pool == null) ? 0 : trace.pool.size());
            sizes.put("web", (trace == null || trace.web == null) ? 0 : trace.web.size());
            sizes.put("vector", (trace == null || trace.vector == null) ? 0 : trace.vector.size());
            sizes.put("final", (resp == null || resp.finalResults == null) ? 0 : resp.finalResults.size());
            ev.put("sizes", sizes);

            PROBE_TRACE.info("{}", PROBE_MAPPER.writeValueAsString(ev));

            boolean kvEnabled = env != null && Boolean.parseBoolean(
                    env.getProperty("probe.search.console-trace.kv.enabled", "false"));
            if (kvEnabled) {
                PROBE_TRACE.info("kv {}", toLogfmt(ev));
            }
        } catch (Exception e) {
            traceSkipped("console_trace_emit", e);
            PROBE_TRACE.info("[probe.search] consoleTrace_error type={} msg={}.",
                    e.getClass().getSimpleName(), safeMsg(e.getMessage()));
        }
    }

    private static boolean envPresent(String name) {
        try {
            String v = System.getenv(name);
            return v != null && !v.isBlank();
        } catch (Exception ignore) {
            traceSkipped("env_present", ignore);
            return false;
        }
    }

    private static void traceSkipped(String stage, Throwable error) {
        PROBE_TRACE.debug("[probe.search] diagnostic stage skipped stage={} errorType={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                SafeRedactor.traceLabelOrFallback(errorType(error), "unknown"));
    }

    private static String errorType(Throwable error) {
        return error == null ? null : error.getClass().getSimpleName();
    }

    private static boolean propPresent(Environment env, String key) {
        return hasUsableConfigValue(env, key);
    }

    private static boolean hasUsableConfigValue(Environment env, String key) {
        if (env == null || key == null || key.isBlank()) return false;
        try {
            return !ConfigValueGuards.isMissing(env.getProperty(key));
        } catch (Exception ignore) {
            traceSkipped("config_value_guard", ignore);
            return false;
        }
    }

    private void seedEcosystemBuffer(SearchProbeRequest req,
                                     UnifiedRagOrchestrator.QueryRequest q,
                                     Map<String, Object> params,
                                     Environment env) {
        if (params == null) {
            return;
        }
        if (!ecosystemBufferSeedEnabled(env)) {
            params.put("disabledReason", "probe_seed_disabled");
            return;
        }
        if (ecosystemBufferPool == null) {
            params.put("disabledReason", "ecosystem_buffer_pool_missing");
            return;
        }
        List<String> raw = new java.util.ArrayList<>();
        if (req != null && req.seed != null) {
            for (CandidateDTO seed : req.seed) {
                String text = ecosystemSeedRaw(seed);
                if (text != null && !text.isBlank()) {
                    raw.add(text);
                }
            }
        }
        params.put("seedHash12", SafeRedactor.hash12(String.join("\n", raw)));
        int charged = ecosystemBufferPool.chargeRawAndTrace("probe:" + SafeRedactor.hash12(q == null ? null : q.query), raw);
        params.put("chargedCount", charged);
        params.put("poolSize", ecosystemBufferPool.poolSize());
        if (charged <= 0) {
            params.put("disabledReason", "no_seed_text");
        }
    }

    private static boolean ecosystemBufferSeedEnabled(Environment env) {
        return env != null && Boolean.parseBoolean(
                env.getProperty("probe.search.ecosystem-buffer-seed.enabled", "false"));
    }

    private static String ecosystemSeedRaw(CandidateDTO seed) {
        if (seed == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        if (seed.title != null && !seed.title.isBlank()) {
            sb.append(seed.title.trim());
        }
        if (seed.snippet != null && !seed.snippet.isBlank()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(seed.snippet.trim());
        }
        if (seed.url != null && !seed.url.isBlank()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(seed.url.trim());
        }
        return sb.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v == null) continue;
            String t = v.trim();
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) return null;
        for (Object v : values) {
            if (v != null) return v;
        }
        return null;
    }

    private static Object ecosystemFallbackTrigger(Object trigger, Object used, Object safeCount) {
        if (truthy(used) && positive(safeCount)) {
            return "ecosystem->NOFILTER_SAFE";
        }
        if (trigger != null && !String.valueOf(trigger).trim().isBlank()) {
            return trigger;
        }
        return trigger;
    }

    private static Object ecosystemPoolSafeEmpty(Object poolSafeEmpty, Object used, Object safeCount) {
        if (truthy(used) && positive(safeCount)) {
            return false;
        }
        return poolSafeEmpty;
    }

    private static Object tracePoolSizeValue(Object explicitSize, int itemCount) {
        if (itemCount <= 0) {
            return explicitSize;
        }
        if (explicitSize instanceof Number n) {
            return Math.max(itemCount, n.longValue());
        }
        String raw = explicitSize == null ? "" : String.valueOf(explicitSize).trim();
        if (raw.matches("\\d{1,18}")) {
            return Math.max(itemCount, Long.parseLong(raw));
        }
        return itemCount;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.longValue() != 0L;
        }
        String raw = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static boolean positive(Object value) {
        if (value instanceof Number n) {
            return n.longValue() > 0L;
        }
        String raw = value == null ? "" : String.valueOf(value).trim();
        return raw.matches("\\d{1,18}") && Long.parseLong(raw) > 0L;
    }

    private static void putKpiValue(Map<String, Object> kpi, String key, Object value) {
        if (kpi == null || key == null || key.isBlank() || value == null) return;
        Object safe = SafeRedactor.diagnosticValue(key, value, 160);
        if (safe != null) kpi.put(key, safe);
    }

    private static void putKpiLabel(Map<String, Object> kpi, String key, Object value) {
        if (kpi == null || key == null || key.isBlank()) return;
        String safe = safeTrigger(value);
        if (!safe.isBlank()) kpi.put(key, safe);
    }

    private static void putSelectedTraceValue(Map<String, Object> params, String key, Object value) {
        if (params == null || key == null || key.isBlank() || value == null) return;
        Object safe = SafeRedactor.diagnosticValue(key, value, 160);
        if (safe != null) params.put(key, safe);
    }

    private static void putSelectedTraceLabel(Map<String, Object> params, String key, Object value) {
        if (params == null || key == null || key.isBlank()) return;
        String safe = safeTrigger(value);
        if (!safe.isBlank()) params.put(key, safe);
    }

    private static void putSelectedTraceTrigger(Map<String, Object> params, Object value) {
        if (params == null) return;
        String safe = safeTrigger(value);
        if (!safe.isBlank()) {
            params.put("starvationFallback.trigger", safe);
        }
    }

    private static void redactProbeCandidateBodies(SearchProbeResponse resp) {
        if (resp == null) return;
        if (resp.stages != null) {
            for (StageSnapshot stage : resp.stages) {
                if (stage != null && stage.candidates != null && !stage.candidates.isEmpty()) {
                    stage.params.put("redactedCandidateCount", stage.candidates.size());
                    stage.candidates.clear();
                }
            }
        }
        if (resp.finalResults != null && !resp.finalResults.isEmpty()) {
            resp.finalResults.clear();
        }
    }

    private static String safeTrigger(Object value) {
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (raw.matches("[A-Za-z0-9_.:-]{1,80}(->[A-Za-z0-9_.:-]{1,80})?")) {
            return raw;
        }
        return SafeRedactor.traceLabelOrFallback(raw, "");
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String safeMsg(String s) {
        if (s == null) return "";
        return SafeRedactor.traceLabelOrFallback(oneLine(s), "");
    }

    /**
     * Grep-friendly key=value output (logfmt-ish). Nested maps are flattened using dotted keys.
     * Example: flags.useWeb=true heuristics.definitional=true kpi.outCount=3
     */
    private static String toLogfmt(Map<String, Object> ev) {
        if (ev == null || ev.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(512);
        appendLogfmt(sb, "", ev);
        // trim last space
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendLogfmt(StringBuilder sb, String prefix, Object value) {
        if (sb == null) return;
        if (value instanceof Map) {
            Map<Object, Object> m = (Map<Object, Object>) value;
            for (Map.Entry<Object, Object> e : m.entrySet()) {
                if (e == null) continue;
                String k = String.valueOf(e.getKey());
                String key = (prefix == null || prefix.isBlank()) ? k : (prefix + "." + k);
                appendLogfmt(sb, key, e.getValue());
            }
            return;
        }
        appendLogfmtPair(sb, prefix, value);
    }

    private static void appendLogfmtPair(StringBuilder sb, String key, Object value) {
        if (sb == null) return;
        if (key == null || key.isBlank()) return;
        String v = (value == null) ? "null" : String.valueOf(value);
        v = oneLine(v);
        sb.append(key).append('=');
        sb.append(escapeLogfmt(v));
        sb.append(' ');
    }

    private static String escapeLogfmt(String v) {
        if (v == null) return "null";
        boolean needsQuote = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '=' || c == '\\') {
                needsQuote = true;
                break;
            }
        }
        if (!needsQuote) return v;

        StringBuilder out = new StringBuilder(v.length() + 2);
        out.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
        return out.toString();
    }
}
