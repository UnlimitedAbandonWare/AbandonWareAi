package com.example.lms.probe;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.probe.dto.CandidateDTO;
import com.example.lms.probe.dto.SearchProbeRequest;
import com.example.lms.probe.dto.SearchProbeResponse;
import com.example.lms.probe.dto.StageSnapshot;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class ProbeConfig {

    @Bean
    ProbePipeline probePipeline(UnifiedRagOrchestrator orchestrator,
                               NightmareBreaker nightmareBreaker) {

        return (SearchProbeRequest req) -> {
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

            return resp;
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
}
