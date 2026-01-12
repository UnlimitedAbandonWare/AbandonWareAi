package com.example.lms.service.rag.handler;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.search.TraceStore;
import com.example.lms.search.probe.EvidenceSignals;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.service.rag.learn.CfvmKAllocationTuner;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.config.alias.NineTileAliasCorrector;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.service.ner.LLMNamedEntityExtractor;
import com.example.lms.service.rag.knowledge.UniversalLoreRegistry;
import com.example.lms.uaw.thumbnail.UawThumbnailProperties;
import com.example.lms.service.rag.*;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.retriever.OcrRetriever;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.strategy.RetrievalOrderService.Source;
import com.example.lms.telemetry.SseEventPublisher;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.query.Query;
import com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.example.lms.guard.rulebreak.RuleBreakContext;
import com.example.lms.service.rag.handler.RuleBreakRetrievalDecorator;

/**
 * A retrieval handler chain that dynamically determines the order of invocation
 * for
 * Web, Vector, and Knowledge Graph sources based on the query characteristics.
 * <p>
 * The chain executes a number of preparatory stages (memory reload, self-ask,
 * analyze, adaptive web search) before delegating to the dynamic order plan
 * provided by {@link RetrievalOrderService}. Each stage is fail-soft: any
 * exception thrown by an individual handler is caught and logged, allowing the
 * remaining stages to continue unhindered. After exhausting the dynamic plan
 * or filling the accumulator to the configured limit, an optional repair
 * handler may post-process results.
 */
@RequiredArgsConstructor
@org.springframework.stereotype.Component

public class DynamicRetrievalHandlerChain implements RetrievalHandler {

    @org.springframework.beans.factory.annotation.Value("${rag.diversity.enabled:true}")
    private boolean diversityEnabled;
    @org.springframework.beans.factory.annotation.Value("${rag.diversity.lambda:0.7}")
    private double mmrLambda;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SseEventPublisher sse;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NineTileAliasCorrector aliasCorrector;

    @Autowired(required = false)
    private FaultMaskingLayerMonitor faultMaskingLayerMonitor;

    @Autowired(required = false)
    private OcrRetriever ocrRetriever;

    @Value("${rag.ocr.enabled:false}")
    private boolean ocrEnabled;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CfvmKAllocationTuner cfvmKallocTuner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FailurePatternOrchestrator failurePatterns;

    // private Object sse; // removed duplicate

    private static final Logger log = LoggerFactory.getLogger(DynamicRetrievalHandlerChain.class);

    public enum MemoryMode {
        JAMMINI_MEMORY, // stable, memory-enabled
        EXPLORATION // free-form, memory-disabled
    }

    private MemoryMode memoryMode = MemoryMode.JAMMINI_MEMORY;

    public DynamicRetrievalHandlerChain withMemoryMode(MemoryMode mode) {
        this.memoryMode = (mode != null ? mode : MemoryMode.JAMMINI_MEMORY);
        return this;
    }

    public MemoryMode getMemoryMode() {
        return memoryMode;
    }

    private final MemoryHandler memoryHandler;
    private final SelfAskWebSearchRetriever selfAsk;
    private final AnalyzeWebSearchRetriever analyze;
    private final AdaptiveWebSearchHandler adaptiveWeb;
    private final WebSearchRetriever web;
    private final AuthorityScorer authorityScorer;
    private final LangChainRAGService rag;
    private final EvidenceRepairHandler repair;
    private final QueryComplexityGate gate;
    private final KnowledgeGraphHandler kg;
    private final RetrievalOrderService orderService;
    private final WeightedReciprocalRankFuser fuser;
    private final LLMNamedEntityExtractor nerExtractor;
    private final UniversalLoreRegistry loreRegistry;
    private final UawThumbnailProperties uawThumbnailProperties;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.risk.RiskScorer riskScorer;

    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    @Value("${rag.search.top-k:5}")
    private int topK;

    // ---- Dynamic K-Allocation settings (retrieval.kalloc.*) ----
    @Value("${retrieval.kalloc.enabled:false}")
    private boolean kallocEnabled;
    @Value("${retrieval.kalloc.policy:balanced}")
    private String kallocPolicy;
    @Value("${retrieval.kalloc.max-total-k:24}")
    private int kallocMaxTotalK;
    @Value("${retrieval.kalloc.min-per-source:2}")
    private int kallocMinPerSource;
    @Value("${retrieval.kalloc.k-step:4}")
    private int kallocKStep;
    @Value("${retrieval.kalloc.recency-keywords:최근,오늘,업데이트,발표,발매,release}")
    private String kallocRecencyKeywordsCsv;

    @Override
    public void handle(Query query, List<Content> accumulator) {
        if (accumulator == null) {
            return;
        }
        // 1. Session memory load (fail-soft)
        Long sessionId = null;
        try {
            if (query != null && query.metadata() != null) {
                java.util.Map<String, Object> md = toMap(query.metadata());
                Object sidObj = md.get(LangChainRAGService.META_SID);
                if (sidObj != null) {
                    sessionId = Long.parseLong(String.valueOf(sidObj));
                }
            }
        } catch (Exception ignore) {
            // ignore errors
        }


com.example.lms.service.guard.GuardContext gctx = com.example.lms.service.guard.GuardContextHolder.get();
boolean forceNoMemory = gctx != null && (gctx.isSensitiveTopic() || gctx.planBool("memory.forceOff", false));

        if (!forceNoMemory && memoryMode == MemoryMode.JAMMINI_MEMORY && sessionId != null) {
            try {
                String hist = memoryHandler.loadForSession(sessionId);
                if (hist != null && !hist.isBlank()) {
                    accumulator.add(Content.from(hist));
                    // Early-cut removed: do not return when reaching topK at this stage
                }
            } catch (Exception e) {
                log.warn("[Memory] {}", e.toString());
            }
        } else if (memoryMode == MemoryMode.EXPLORATION) {
            log.debug("[DynamicRHC] Exploration mode active - skipping memory load");
        }
        // 2. Determine query text for downstream heuristics
        String qText = (query != null && query.text() != null) ? query.text().trim() : "";
        if (aliasCorrector != null && !qText.isBlank()) {
            try {
                Map<String, Object> aliasCtx = new HashMap<>();
                if (query != null && query.metadata() != null) {
                    java.util.Map<String, Object> md = toMap(query.metadata());
                    Object domain = md.get("intent.domain");
                    if (domain == null) {
                        domain = md.get("vp.topTile");
                    }
                    if (domain != null) {
                        aliasCtx.put("intent.domain", domain);
                    }
                }
                qText = aliasCorrector.correct(qText, Locale.KOREAN, aliasCtx);
            } catch (Exception e) {
                log.debug("[Alias] NineTile alias correction failed, continuing with original query", e);
            }
        }

        // Apply alias-corrected query text to downstream retrievers (previously only
        // used for heuristics)
        Query effectiveQuery = query;
        try {
            if (effectiveQuery == null) {
                effectiveQuery = new Query(qText);
            } else if (qText != null && !qText.isBlank() && !qText.equals(effectiveQuery.text())) {
                effectiveQuery = new Query(qText, effectiveQuery.metadata());
            }
        } catch (Exception ignore) {
            effectiveQuery = query;
        }

        // Dynamic K-Allocation: fill per-source topK hints into Query metadata if
        // missing.
        // This makes retrieval orchestration consistent with
        // PlanHintApplier/OrchestrationHints.
        try {
            if (kallocEnabled && effectiveQuery != null) {
                java.util.Map<String, Object> md = toMap(effectiveQuery.metadata());
                boolean changed = false;

                // per-request explicit values win
                boolean hasWebK = metaInt(md, "webTopK", -1) > 0;
                boolean hasVecK = (metaInt(md, "vectorTopK", -1) > 0) || (metaInt(md, "vecTopK", -1) > 0);
                boolean hasKgK = metaInt(md, "kgTopK", -1) > 0;

                if (!(hasWebK && hasVecK && hasKgK)) {
                    String intent = "";
                    try {
                        Object it = md.get("intent.domain");
                        if (it == null)
                            it = md.get("intent");
                        if (it != null)
                            intent = String.valueOf(it);
                    } catch (Exception ignore2) {
                    }
                    boolean officialOnly = metaBool(md, "officialSourcesOnly", false)
                            || metaBool(md, "officialOnly", false);

                    com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.KPlan planK = __decideKPlan(
                            intent,
                            qText, officialOnly, md);
                    if (planK != null) {
                        if (!hasWebK) {
                            md.put("webTopK", planK.webK);
                            changed = true;
                        }
                        if (!hasVecK) {
                            md.put("vectorTopK", planK.vectorK);
                            changed = true;
                        }
                        if (!hasKgK) {
                            md.put("kgTopK", planK.kgK);
                            changed = true;
                        }
                        if (changed) {
                            md.put("kalloc.poolLimit", planK.poolLimit);
                            TraceStore.put("retrieval.kalloc.plan", planK.toString());
                            // rebuild Query with updated metadata
                            effectiveQuery = new Query(effectiveQuery.text(),
                                    dev.langchain4j.data.document.Metadata.from(md));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[KAlloc] skip: {}", e.toString());
        }
        // MERGE_HOOK:PROJ_AGENT::LORE_FAST_MATCH_INJECTION_V1
        // [Lore] Inject YAML-based domain knowledge (local, high-confidence)
        // - Fast path: direct keyword match (no NER / no LLM dependency)
        // - Slow path: LLM NER → lore lookup (only when aux is healthy)
        boolean allowLoreNer = true;
        boolean loreInjected = false;

        // 모드 체크
        try {
            java.util.Map<String, Object> md = toMap(effectiveQuery != null ? effectiveQuery.metadata() : null);
            boolean nightmareMode = metaBool(md, "nightmareMode", false);
            boolean auxLlmDown = metaBool(md, "auxLlmDown", false);
            boolean strikeMode = metaBool(md, "strikeMode", false);
            boolean compressionMode = metaBool(md, "compressionMode", false);
            if (nightmareMode || auxLlmDown || strikeMode || compressionMode) {
                allowLoreNer = false;
            }
        } catch (Exception ignore) {
        }

        // 1) Fast match: 항상 시도 (local-only, cheap)
        if (loreRegistry != null) {
            try {
                java.util.List<com.example.lms.service.rag.knowledge.UniversalLoreRegistry.DomainKnowledge> lores = loreRegistry
                        .findLoreInText(qText);
                if (lores != null && !lores.isEmpty()) {
                    loreInjected = true;
                    TraceStore.put("lore.fastMatch.count", lores.size());
                    log.info("[Lore] Fast-match injecting {} entries (no-NER)", lores.size());
                    for (com.example.lms.service.rag.knowledge.UniversalLoreRegistry.DomainKnowledge lore : lores) {
                        String entityName = (lore.getNames() != null && !lore.getNames().isEmpty())
                                ? lore.getNames().get(0)
                                : "";
                        accumulator.add(Content.from(
                                TextSegment.from(
                                        "[Lore: " + lore.getDomain() + "] " + lore.getContent(),
                                        Metadata.from(java.util.Map.of("source", "UniversalLore", "entity",
                                                entityName)))));
                    }
                }
            } catch (Exception e) {
                log.debug("[Lore] fast-match skip: {}", e.toString());
            }
        }

        // 2) NER match: Fast에서 못 찾았고 aux가 정상일 때만
        if (!loreInjected && allowLoreNer && nerExtractor != null && loreRegistry != null) {
            try {
                java.util.List<String> entities = nerExtractor.extract(qText);
                if (entities != null && !entities.isEmpty()) {
                    java.util.List<com.example.lms.service.rag.knowledge.UniversalLoreRegistry.DomainKnowledge> lores = loreRegistry
                            .findLore(entities);
                    if (lores != null && !lores.isEmpty()) {
                        TraceStore.put("lore.nerMatch.count", lores.size());
                        log.info("[Lore] NER injecting {} entries for entities: {}", lores.size(), entities);
                        for (com.example.lms.service.rag.knowledge.UniversalLoreRegistry.DomainKnowledge lore : lores) {
                            String entityName = (lore.getNames() != null && !lore.getNames().isEmpty())
                                    ? lore.getNames().get(0)
                                    : "";
                            accumulator.add(Content.from(
                                    TextSegment.from(
                                            "[Lore: " + lore.getDomain() + "] " + lore.getContent(),
                                            Metadata.from(java.util.Map.of("source", "UniversalLore", "entity",
                                                    entityName)))));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Lore] Failed to inject lore", e);
                mask("[Lore]", e, qText);
            }
        }
        // 3. Self-Ask stage (only when gate signals it)
        boolean needSelf = false;
        try {
            needSelf = gate != null && gate.needsSelfAsk(qText);
        } catch (Exception e) {
            log.warn("[SelfAskGate] {}", e.toString());
            mask("[SelfAskGate]", e, qText);
        }
        // OrchestrationHints: per-request override
        try {
            java.util.Map<String, Object> md = toMap(effectiveQuery != null ? effectiveQuery.metadata() : null);
            boolean nightmareMode = metaBool(md, "nightmareMode", false);
            boolean auxLlmDown = metaBool(md, "auxLlmDown", false);
            boolean strikeMode = metaBool(md, "strikeMode", false);
            boolean compressionMode = metaBool(md, "compressionMode", false);
            boolean enableSelfAskHint = metaBool(md, "enableSelfAsk", true);
            if (!enableSelfAskHint || nightmareMode || auxLlmDown || strikeMode || compressionMode) {
                needSelf = false;
            }
        } catch (Exception ignore) {
        }
        if (needSelf) {
            try {
                add(accumulator, selfAsk.retrieve(effectiveQuery));
                // Early-cut removed: continue gathering evidence instead of returning
            } catch (Exception e) {
                log.warn("[SelfAsk] {}", e.toString());
                mask("[SelfAsk]", e, qText);
            }
        }
        // 4. Analyze stage (only when gate signals it)
        boolean needAnalyze = false;
        try {
            needAnalyze = gate != null && gate.needsAnalyze(qText);
        } catch (Exception e) {
            log.warn("[AnalyzeGate] {}", e.toString());
            mask("[AnalyzeGate]", e, qText);
        }
        // OrchestrationHints: per-request override
        try {
            java.util.Map<String, Object> md = toMap(effectiveQuery != null ? effectiveQuery.metadata() : null);
            boolean nightmareMode = metaBool(md, "nightmareMode", false);
            boolean auxLlmDown = metaBool(md, "auxLlmDown", false);
            boolean strikeMode = metaBool(md, "strikeMode", false);
            boolean enableAnalyzeHint = metaBool(md, "enableAnalyze", true);
            // COMPRESSION is a budget/latency-saving mode; do not hard-disable Analyze
            // unless
            // the system is truly in STRIKE or the aux LLM is down.
            if (!enableAnalyzeHint || nightmareMode || auxLlmDown || strikeMode) {
                needAnalyze = false;
            }
        } catch (Exception ignore) {
        }
        if (needAnalyze) {
            try {
                add(accumulator, analyze.retrieve(effectiveQuery));
                // Early-cut removed: continue gathering evidence instead of returning
            } catch (Exception e) {
                log.warn("[Analyze] {}", e.toString());
                mask("[Analyze]", e, qText);
            }
        }
        // 5. Adaptive web search stage
        if (adaptiveWeb != null) {
            try {
                adaptiveWeb.handle(effectiveQuery, accumulator);
                // Early-cut removed: do not return here; allow subsequent stages
            } catch (Exception e) {
                log.warn("[AdaptiveWeb] {}", e.toString());
                mask("[AdaptiveWeb]", e, qText);
            }
        }
        // 6. Dynamic order execution: Web / Vector / KG as decided by orderService
        java.util.List<Source> plan;
        try {
            plan = orderService.decideOrder(qText);
        } catch (Exception e) {
            // fallback to default order
            log.warn("[OrderService] decide failed; using default", e);
            mask("[OrderService]", e, qText);
            plan = List.of(Source.WEB, Source.VECTOR, Source.KG);
        }
        // plan override: metaHints["retrieval.order"] = ["kg","vector","web"] 등
        try {
            java.util.Map<String, Object> md = toMap(effectiveQuery != null ? effectiveQuery.metadata() : null);
            java.util.List<Source> override = parseOrderOverride(md);
            if (override != null && !override.isEmpty()) {
                plan = override;
                TraceStore.put("retrieval.order.override", override.toString());
            }
        } catch (Exception ignore) {
        }

        // OrchestrationHints: allowWeb/allowRag per-request switches
        try {
            java.util.Map<String, Object> md = toMap(effectiveQuery != null ? effectiveQuery.metadata() : null);
            boolean allowWeb = metaBool(md, "allowWeb", true);
            boolean allowRag = metaBool(md, "allowRag", true);
            if (!allowWeb) {
                plan = plan.stream().filter(s -> s != Source.WEB).toList();
            }
            if (!allowRag) {
                plan = plan.stream().filter(s -> s != Source.VECTOR).toList();
            }
        } catch (Exception ignore) {
        }

        // SSE event for order decision
        try {
            _sse("ORDER_DECISION", new SseEventPublisher.Payload()
                    .kv("steps", plan)
                    .kv("reason", "heuristic")
                    .build());
        } catch (Exception e) {
            log.debug("ORDER_DECISION SSE skipped: {}", e.toString());
        }
        log.debug("[ORDER_DECISION] plan={} reason={}", plan, "heuristic");

        // Collect per-source results for fusion
        java.util.List<Content> webRes = new java.util.ArrayList<>();
        java.util.List<Content> vecRes = new java.util.ArrayList<>();
        java.util.List<Content> kgRes = new java.util.ArrayList<>();

        for (Source src : plan) {
            // Early-cut removed: do not break when accumulator reaches topK; continue
            // through all sources
            try {
                switch (src) {
                    case WEB -> {
                        var r = web.retrieve(effectiveQuery);
                        if (r != null)
                            webRes.addAll(r);
                    }
                    case VECTOR -> {
                        // UAW Thumbnail recall: treat previously generated thumbnails (stored in KB)
                        // as a high-priority vector source.
                        //
                        // Motivation: when users ask something that we have recently thumbnailed,
                        // this gives a fast, stable, high-signal context chunk before broader
                        // vector/web.
                        java.util.List<Content> thumbRes = java.util.Collections.emptyList();
                        try {
                            if (uawThumbnailProperties != null && uawThumbnailProperties.isRecallEnabled()
                                    && uawThumbnailProperties.getRecallTopK() > 0) {
                                thumbRes = rag.retrieveGlobalKbDomain(
                                        effectiveQuery,
                                        uawThumbnailProperties.getKnowledgeDomain(),
                                        uawThumbnailProperties.getRecallTopK(),
                                        uawThumbnailProperties.getRecallMinScore(),
                                        uawThumbnailProperties.getRecallPoolK());
                            }
                        } catch (Exception e) {
                            log.debug("[UAW_THUMB] recall fail-soft: {}", e.toString());
                            mask("[UAW_THUMB recall]", e, qText);
                        }

                        // Trace / diagnostics
                        try {
                            TraceStore.put("uaw.thumb.recall.enabled",
                                    uawThumbnailProperties != null && uawThumbnailProperties.isRecallEnabled());
                            TraceStore.put("uaw.thumb.recall.domain",
                                    uawThumbnailProperties != null ? uawThumbnailProperties.getKnowledgeDomain()
                                            : null);
                            TraceStore.put("uaw.thumb.recall.hits", thumbRes == null ? 0 : thumbRes.size());
                            if (thumbRes != null && !thumbRes.isEmpty()) {
                                java.util.List<String> ents = new java.util.ArrayList<>();
                                for (Content c : thumbRes) {
                                    try {
                                        String e = c.textSegment().metadata().getString("kb_entity");
                                        if (e != null && !e.isBlank()) {
                                            ents.add(e);
                                        }
                                    } catch (Exception ignore) {
                                    }
                                }
                                if (!ents.isEmpty()) {
                                    TraceStore.put("uaw.thumb.recall.entities", ents);
                                }
                            }
                        } catch (Exception ignore) {
                        }

                        java.util.Set<String> seen = new java.util.HashSet<>();
                        if (thumbRes != null) {
                            for (Content c : thumbRes) {
                                try {
                                    seen.add(c.textSegment().text());
                                } catch (Exception ignore) {
                                }
                            }
                            vecRes.addAll(thumbRes);
                        }

                        // OCR axis: optionally add OCR-derived context before vector retrieval.
                        if (ocrEnabled && ocrRetriever != null) {
                            try {
                                var ocr = ocrRetriever.retrieve(effectiveQuery);
                                if (ocr != null) {
                                    for (Content c : ocr) {
                                        try {
                                            String t = c.textSegment().text();
                                            if (t == null || t.isBlank() || !seen.add(t)) {
                                                continue;
                                            }
                                        } catch (Exception ignore) {
                                        }
                                        vecRes.add(c);
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("[OCR] fail-soft: {}", e.toString());
                                mask("[OCR]", e, qText);
                            }
                        }

                        ContentRetriever vector = rag.asContentRetriever(pineconeIndexName);
                        var r = vector.retrieve(effectiveQuery);
                        if (r != null) {
                            for (Content c : r) {
                                try {
                                    String t = c.textSegment().text();
                                    if (t == null || t.isBlank() || !seen.add(t)) {
                                        continue;
                                    }
                                } catch (Exception ignore) {
                                }
                                vecRes.add(c);
                            }
                        }
                    }
                    case KG -> {
                        if (kg != null) {
                            var r = kg.retrieve(effectiveQuery);
                            if (r != null)
                                kgRes.addAll(r);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] fail-soft: {}", src, e.toString());
                mask("[" + src + "]", e, qText);
            }
        }
        // Apply weighted RRF fusion across available sources
        java.util.List<java.util.List<Content>> sources = new java.util.ArrayList<>();
        if (!webRes.isEmpty())
            sources.add(webRes);
        if (!vecRes.isEmpty())
            sources.add(vecRes);
        if (!kgRes.isEmpty())
            sources.add(kgRes);

        if (!sources.isEmpty()) {

            // RDI-based deceleration: shrink topK when the risk signal is high
            int usedTopK = topK;
            try {
                if (riskScorer != null) {
                    java.util.List<Content> union = new java.util.ArrayList<>(
                            webRes.size() + vecRes.size() + kgRes.size());
                    union.addAll(webRes);
                    union.addAll(vecRes);
                    union.addAll(kgRes);
                    int rdi = riskScorer.computeRdi(new com.example.risk.ListingContext(union));
                    int orig = usedTopK;
                    usedTopK = adjustTopK(orig, rdi);
                    try {
                        _sse("RISK_DECEL", new SseEventPublisher.Payload()
                                .kv("rdi", rdi).kv("topK.orig", orig).kv("topK.used", usedTopK).build());
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception e) {
                log.debug("[RISK_DECEL] {}", e.toString());
            }

            var fused = fuser.fuse(sources, usedTopK);
            add(accumulator, fused);
            try {
                _sse("FUSION_APPLIED", new SseEventPublisher.Payload()
                        .kv("sizes", java.util.Map.of("web", webRes.size(), "vec", vecRes.size(), "kg", kgRes.size()))
                        .kv("topK", topK).build());
            } catch (Exception ignore) {
            }
        }
        // 7. Repair stage (post-processing)
        try {
            if (repair != null) {
                add(accumulator, repair.retrieve(effectiveQuery));
            }
        } catch (Exception e) {
            log.warn("[Repair] {}", e.toString());
        }

        // 8. Evidence signals (learning/reward features & needle trigger observability)
        try {
            qText = (effectiveQuery != null ? effectiveQuery.text() : (query != null ? query.text() : ""));
            EvidenceSignals sig = EvidenceSignals.compute(qText, accumulator, authorityScorer);

            TraceStore.put("cfvm.sig.authorityAvg", sig.authorityAvg());
            TraceStore.put("cfvm.sig.coverage", sig.coverageScore());
            TraceStore.put("cfvm.sig.dupRatio", sig.duplicateRatio());
            TraceStore.put("cfvm.sig.docCount", sig.docCount());

            java.util.Map<String, Object> md = toMap(effectiveQuery != null ? effectiveQuery.metadata()
                    : (query != null ? query.metadata() : null));
            boolean needle = metaBool(md, "probe.needle", false);
            java.util.Map<String, Object> sample = new java.util.HashMap<>();
            sample.put("q", qText);
            sample.put("needle", needle);
            sample.put("authorityAvg", sig.authorityAvg());
            sample.put("coverage", sig.coverageScore());
            sample.put("dupRatio", sig.duplicateRatio());
            sample.put("docCount", sig.docCount());
            TraceStore.append("cfvm.sig.samples", sample);
        } catch (Exception ignore) {
        }
    }

    private static void add(List<Content> target, List<Content> source) {
        if (source != null && !source.isEmpty()) {
            target.addAll(source);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> toMap(Object meta) {
        if (meta == null)
            return java.util.Map.of();

        if (meta instanceof java.util.Map<?, ?> raw) {
            java.util.Map<String, Object> out = new java.util.HashMap<>();
            for (java.util.Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() != null)
                    out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }

        try {
            java.lang.reflect.Method m = meta.getClass().getMethod("asMap");
            Object v = m.invoke(meta);
            if (v instanceof java.util.Map<?, ?> m2) {
                java.util.Map<String, Object> out = new java.util.HashMap<>();
                for (java.util.Map.Entry<?, ?> e : m2.entrySet()) {
                    if (e.getKey() != null)
                        out.put(String.valueOf(e.getKey()), e.getValue());
                }
                return out;
            }
        } catch (NoSuchMethodException e) {
            try {
                java.lang.reflect.Method m2 = meta.getClass().getMethod("map");
                Object v = m2.invoke(meta);
                if (v instanceof java.util.Map<?, ?> m3) {
                    java.util.Map<String, Object> out = new java.util.HashMap<>();
                    for (java.util.Map.Entry<?, ?> e2 : m3.entrySet()) {
                        if (e2.getKey() != null)
                            out.put(String.valueOf(e2.getKey()), e2.getValue());
                    }
                    return out;
                }
            } catch (Exception ex) {
                return java.util.Map.of();
            }
        } catch (Exception ex) {
            return java.util.Map.of();
        }
        return java.util.Map.of();
    }

    private static boolean metaBool(java.util.Map<String, Object> meta, String key, boolean def) {
        if (meta == null)
            return def;
        Object v = meta.get(key);
        if (v instanceof Boolean b)
            return b;
        if (v instanceof Number n)
            return n.intValue() != 0;
        if (v instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("true") || t.equals("1") || t.equals("yes"))
                return true;
            if (t.equals("false") || t.equals("0") || t.equals("no"))
                return false;
        }
        return def;
    }

    private static int metaInt(java.util.Map<String, Object> meta, String key, int def) {
        if (meta == null)
            return def;
        Object v = meta.get(key);
        if (v == null)
            return def;
        if (v instanceof Number n)
            return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception ignore) {
                return def;
            }
        }
        return def;
    }

    private static java.util.List<String> splitCsv(String csv, java.util.List<String> fallback) {
        if (csv == null)
            return fallback;
        String t = csv.trim();
        if (t.isEmpty())
            return fallback;

        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : t.split(",")) {
            String s = (p == null) ? "" : p.trim();
            if (!s.isEmpty())
                out.add(s);
        }
        return out.isEmpty() ? fallback : out;
    }

    // [HARDENING] ensure SID metadata is present on every query
    private dev.langchain4j.rag.query.Query ensureSidMetadata(dev.langchain4j.rag.query.Query original,
            String sessionKey) {
        var md = original.metadata() != null
                ? original.metadata()
                : dev.langchain4j.data.document.Metadata.from(
                        java.util.Map.of(com.example.lms.service.rag.LangChainRAGService.META_SID, sessionKey));
        // Directly construct a new Query with the updated metadata. LangChain4j 1.0.x
        // exposes a public constructor taking text and metadata, so we avoid the
        // deprecated
        // builder API and any reflective fallback.
        return new dev.langchain4j.rag.query.Query(original.text(), md);
    }

    /** Piecewise topK shrink based on RDI heuristic. */
    private int adjustTopK(int base, int rdi) {
        if (base <= 1)
            return base;
        if (rdi >= 70) {
            return Math.max(1, (int) Math.floor(base * 0.6));
        } else if (rdi >= 40) {
            return Math.max(1, (int) Math.floor(base * 0.8));
        }
        return base;
    }

    /**
     * 표준 접근자: 체인 구성 스텝을 불변 리스트로 노출.
     * 내부 구현 차이를 흡수하기 위해 List 타입 필드를 탐색합니다.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public java.util.List<Object> getSteps() {
        // Try common field names
        for (String fName : new String[] { "steps", "handlers", "chain" }) {
            try {
                java.lang.reflect.Field f = this.getClass().getDeclaredField(fName);
                f.setAccessible(true);
                Object v = f.get(this);
                if (v instanceof java.util.List) {
                    return java.util.Collections.unmodifiableList((java.util.List) v);
                }
            } catch (NoSuchFieldException ignore) {
            } catch (ReflectiveOperationException e) {
                // ignore and continue
            }
        }
        // Fallback: first List-typed field in declared fields
        try {
            for (java.lang.reflect.Field f : this.getClass().getDeclaredFields()) {
                if (java.util.List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(this);
                    if (v instanceof java.util.List) {
                        return java.util.Collections.unmodifiableList((java.util.List) v);
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            // ignore
        }
        return java.util.Collections.emptyList();
    }

    // === Dynamic K-Allocation Hook (auto-injected) ===
    // Compute per-source topK before invoking web/vector/KG retrievers.
    private com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.KPlan __decideKPlan(String intent,
            String query, boolean officialOnly, java.util.Map<String, Object> md) {
        com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.Settings ks = new com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.Settings();

        // Bind settings from application.yml (retrieval.kalloc.*)
        ks.enabled = this.kallocEnabled;
        if (this.kallocPolicy != null && !this.kallocPolicy.isBlank()) {
            ks.policy = this.kallocPolicy.trim();
        }
        ks.maxTotalK = this.kallocMaxTotalK;
        ks.minPerSource = this.kallocMinPerSource;
        ks.kStep = this.kallocKStep;
        ks.recencyKeywords = splitCsv(this.kallocRecencyKeywordsCsv, ks.recencyKeywords);

        if (!ks.enabled) {
            return null;
        }

        com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator allocator = new com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator(
                ks);

        com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.Input in = new com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.Input(
                intent, query, officialOnly);

        // Baseline heuristic plan (always available)
        com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.KPlan base = allocator.decide(in);

        // Query complexity is an explicit signal we can feed into higher-level tuning.
        QueryComplexityGate.Level cx = QueryComplexityGate.Level.AMBIGUOUS;
        try {
            if (this.gate != null) {
                cx = this.gate.assess(query);
            }
        } catch (Exception ignored) {
            // fail-soft
        }

        // Optional: CFVM-based online tuner (TopK/KAllocation auto-tuning)
        if (cfvmKallocTuner != null) {
            try {
                CfvmKAllocationTuner.Decision d = cfvmKallocTuner.decide(ks, in, cx, failurePatterns);
                if (d != null && d.plan() != null) {
                    // annotate (best-effort): both Query metadata and TraceStore for downstream
                    // inspection + learning feedback
                    try {
                        if (md != null) {
                            md.put("cfvm.kalloc.tile", d.tile());
                            md.put("cfvm.kalloc.key", d.key());
                            md.put("cfvm.kalloc.arm", d.arm());
                            md.put("cfvm.kalloc.policy", d.policy());
                            md.put("cfvm.kalloc.cx", String.valueOf(cx));
                            md.put("cfvm.kalloc.base", String.valueOf(d.baseline()));
                            md.put("cfvm.kalloc.final", String.valueOf(d.plan()));
                        }
                    } catch (Exception ignored2) {
                        // fail-soft
                    }
                    TraceStore.put("cfvm.kalloc.tile", d.tile());
                    TraceStore.put("cfvm.kalloc.key", d.key());
                    TraceStore.put("cfvm.kalloc.arm", d.arm());
                    TraceStore.put("cfvm.kalloc.policy", d.policy());
                    TraceStore.put("cfvm.kalloc.cx", String.valueOf(cx));
                    TraceStore.put("cfvm.kalloc.base", String.valueOf(d.baseline()));
                    TraceStore.put("cfvm.kalloc.final", String.valueOf(d.plan()));
                    return d.plan();
                }
            } catch (Exception e) {
                log.debug("[CFVM-KAlloc] tuner skip: {}", e.toString());
            }
        }

        // default: baseline
        TraceStore.put("cfvm.kalloc.cx", String.valueOf(cx));
        TraceStore.put("cfvm.kalloc.final", String.valueOf(base));
        return base;
    }

    // Usage example (pseudo):
    // KPlan plan = __decideKPlan(request.intent(), request.query(),
    // request.officialSourcesOnly());
    // webTopK = plan.webK; vectorTopK = plan.vectorK; kgTopK = plan.kgK;
    // === /Dynamic K-Allocation Hook ===

    private void _sse(String type, Object payload) {
        if (sse == null)
            return;
        try {
            sse.emit(type, payload);
        } catch (Exception e) {
            log.debug("SSE emit skipped: {}", e.toString());
        }
    }

    private void _sse(String type, Object payload, java.util.Map<String, Object> meta) {
        if (sse == null)
            return;
        try {
            if (payload instanceof SseEventPublisher.Payload) {
                SseEventPublisher.Payload p = (SseEventPublisher.Payload) payload;
                p.kv("meta", meta);
                sse.emit(type, p.build());
            } else {
                sse.emit(type, new SseEventPublisher.Payload()
                        .kv("payload", payload)
                        .kv("meta", meta)
                        .build());
            }
        } catch (Exception e) {
            log.debug("SSE emit skipped: {}", e.toString());
        }
    }

    // PATCH_MARKER: DynamicRetrievalHandlerChain updated per latest spec.
    private static java.util.List<Source> parseOrderOverride(java.util.Map<String, Object> md) {
        if (md == null)
            return null;
        Object v = md.get("retrieval.order");
        if (v == null)
            v = md.get("retrievalOrder");
        if (v == null)
            v = md.get("order");
        if (!(v instanceof java.util.List<?> list))
            return null;

        java.util.List<Source> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o == null)
                continue;
            String s = String.valueOf(o).trim().toLowerCase(java.util.Locale.ROOT);
            switch (s) {
                case "web" -> out.add(Source.WEB);
                case "vector", "rag" -> out.add(Source.VECTOR);
                case "kg", "graph" -> out.add(Source.KG);
                default -> {
                }
            }
        }
        return out;
    }

    private void mask(String stage, Throwable t, String note) {
        try {
            if (faultMaskingLayerMonitor != null) {
                faultMaskingLayerMonitor.record(stage, t, note);
            }
        } catch (Exception ignore) {
            // fail-soft
        }
    }
}
