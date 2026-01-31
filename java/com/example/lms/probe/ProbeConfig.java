package com.example.lms.probe;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.probe.dto.CandidateDTO;
import com.example.lms.probe.dto.SearchProbeRequest;
import com.example.lms.probe.dto.SearchProbeResponse;
import com.example.lms.probe.dto.StageSnapshot;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.util.QueryTypeHeuristics;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            if (req != null && req.seed != null && !req.seed.isEmpty()) {
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
                        env != null && env.getProperty("gpt-search.brave.subscription-token") != null
                                && !env.getProperty("gpt-search.brave.subscription-token").isBlank());
                brave.params.put("keyPresent.gpt-search.brave.api-key",
                        env != null && env.getProperty("gpt-search.brave.api-key") != null
                                && !env.getProperty("gpt-search.brave.api-key").isBlank());
                boolean envGptSub = envPresent("GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN");
                boolean envBraveSub = envPresent("BRAVE_SUBSCRIPTION_TOKEN");
                boolean envGptApi = envPresent("GPT_SEARCH_BRAVE_API_KEY");
                boolean envBraveApi = envPresent("BRAVE_API_KEY");

                brave.params.put("envPresent.GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN", envGptSub);
                brave.params.put("envPresent.BRAVE_SUBSCRIPTION_TOKEN", envBraveSub);
                brave.params.put("envPresent.GPT_SEARCH_BRAVE_API_KEY", envGptApi);
                brave.params.put("envPresent.BRAVE_API_KEY", envBraveApi);

                // Probe checklist fields: ensure exactly ONE subscription token is set.
                brave.params.put("envPresent.subscriptionToken.single", (envGptSub ^ envBraveSub));
                brave.params.put("envPresent.subscriptionToken.winner",
                        envGptSub ? "GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN"
                                : (envBraveSub ? "BRAVE_SUBSCRIPTION_TOKEN" : null));
                brave.params.put("envPresent.apiKey.single", (envGptApi ^ envBraveApi));
                brave.params.put("envPresent.apiKey.winner",
                        envGptApi ? "GPT_SEARCH_BRAVE_API_KEY"
                                : (envBraveApi ? "BRAVE_API_KEY" : null));
            } catch (Exception ignore) {
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
            } catch (Exception ignore) {
            }
            resp.stages.add(guardCfg);

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
                }
            } catch (Exception ignore) {
            }
            resp.stages.add(ts);

            // 5) Console trace (probe-only): grep-friendly JSON + optional key=value(logfmt-ish) line
            try {
                boolean consoleEnabled = env == null || Boolean.parseBoolean(
                        env.getProperty("probe.search.console-trace.enabled", "true"));
                if (consoleEnabled) {
                    logProbeConsole(req, q, trace, useWeb, useRag, officialOnly, env, resp);
                }
            } catch (Exception ignore) {
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
            if (rid != null) ev.put("rid", rid);
            if (sid != null) ev.put("sid", sid);

            String qq = (q == null ? null : q.query);
            ev.put("q", trunc(oneLine(qq), 220));
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
            boolean envApiA = envPresent("GPT_SEARCH_BRAVE_API_KEY");
            boolean envApiB = envPresent("BRAVE_API_KEY");

            brave.put("envPresent.GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN", envSubA);
            brave.put("envPresent.BRAVE_SUBSCRIPTION_TOKEN", envSubB);
            brave.put("envPresent.GPT_SEARCH_BRAVE_API_KEY", envApiA);
            brave.put("envPresent.BRAVE_API_KEY", envApiB);
            brave.put("envPresent.subscriptionToken.single", (envSubA ^ envSubB));
            brave.put("envPresent.subscriptionToken.winner",
                    envSubA ? "GPT_SEARCH_BRAVE_SUBSCRIPTION_TOKEN"
                            : (envSubB ? "BRAVE_SUBSCRIPTION_TOKEN" : null));
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
            kpi.put("outCount", TraceStore.get("web.failsoft.outCount"));
            kpi.put("stageCountsSelectedFromOut", TraceStore.get("stageCountsSelectedFromOut"));
            kpi.put("cacheOnly.merged.count", TraceStore.get("cacheOnly.merged.count"));
            kpi.put("starvationFallback.trigger", TraceStore.get("starvationFallback.trigger"));
            kpi.put("poolSafeEmpty", TraceStore.get("poolSafeEmpty"));
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
            PROBE_TRACE.info("[probe.search] consoleTrace_error type={} msg={}.",
                    e.getClass().getSimpleName(), safeMsg(e.getMessage()));
        }
    }

    private static boolean envPresent(String name) {
        try {
            String v = System.getenv(name);
            return v != null && !v.isBlank();
        } catch (Exception ignore) {
            return false;
        }
    }

    private static boolean propPresent(Environment env, String key) {
        if (env == null || key == null || key.isBlank()) return false;
        try {
            String v = env.getProperty(key);
            return v != null && !v.isBlank();
        } catch (Exception ignore) {
            return false;
        }
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
        return trunc(oneLine(s), 180);
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
