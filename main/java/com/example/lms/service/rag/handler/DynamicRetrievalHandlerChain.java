package com.example.lms.service.rag.handler;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.search.TraceStore;
import com.example.lms.search.probe.BranchQualityProbe;
import com.example.lms.search.probe.EvidenceSignals;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.service.rag.consensus.ThreeLaneRiskConsensus;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.service.rag.learn.CfvmKAllocationTuner;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.lms.config.alias.NineTileAliasCorrector;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.orchestration.ExecutionPlan;
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

    @Value("${retrieval.kalloc.max-source-share:0.65}")
    private double kallocMaxSourceShare;

    private int searchFailureRecoveryMaxSubqueries = 4;
    private long searchFailureRecoveryTimeoutMs = 750L;
    private int selfAskLaneGateCitationMin = 3;
    private boolean selfAskLaneGateEnabled = true;
    private double selfAskLaneGateMinEvidenceRate = 0.45d;
    private double selfAskLaneGateMaxDuplicateRate = 0.70d;
    private double selfAskLaneGateMaxContradictionRate = 0.55d;
    private double selfAskLaneGateMinConfidence = 0.50d;
    private double selfAskLaneGateBypassConfidence = 0.25d;
    private int selfAskLaneGateCompressTopK = 3;
    private int selfAskLaneGateContradictionMaxPairs = 3;
    private double selfAskLaneGateMinStrongCitationRate = 0.50d;
    private int selfAskLaneGateMinDistinctCitations = 2;
    private double selfAskLaneGateMinCrossLaneSupport = 0.25d;
    private double selfAskLaneGateMaxGrandasContradictionRate = 0.40d;
    private double selfAskLaneGateAnchorCompressionMinGain = 0.10d;
    private double selfAskLaneGatePromptMinReadiness = 0.62d;
    private double selfAskLaneGateTailProbeMinSignal = 0.70d;
    private double selfAskLaneGateTailProbeMinAuthority = 0.65d;
    private double selfAskLaneGateTailProbeMaxContradictionRate = 0.25d;
    private double selfAskLaneGateTailProbeStrictMinAuthority = 0.50d;

    private ContradictionScorer contradictionScorer;
    private BranchQualityProbe branchQualityProbe;
    private boolean selfAskBranchQualityEnabled = false;
    private double selfAskBranchQualityMinContextContribution = 0.35d;
    private double selfAskBranchQualityMaxRiskPenalty = 0.65d;
    private double selfAskBranchQualityMoeTemperature = 0.65d;
    private boolean selfAskBranchQualityDppEnabled = true;
    private double selfAskBranchQualityDppMinLambda = 0.35d;
    private double selfAskBranchQualityDppMaxLambda = 0.85d;
    private double selfAskBranchQualityDppMinPressure = 0.20d;
    private int selfAskBranchQualityDppPoolMultiplier = 3;
    private int selfAskBranchQualityDppPoolMax = 24;
    private boolean selfAskBranchQualityBlackboxPriorEnabled = true;

    @Autowired(required = false)
    private FinalSigmoidGate finalSigmoidGate;

    @Autowired(required = false)
    private DppDiversityReranker dppDiversityReranker;

    private final java.util.concurrent.ExecutorService recoveryExecutor =
            new ai.abandonware.nova.boot.exec.CancelShieldExecutorService(
                    java.util.concurrent.Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "ragRecovery");
                        t.setDaemon(true);
                        return t;
                    }), "ragRecovery");

    {
        TraceStore.put("rag.recovery.executor.source", "cancel_shield");
    }

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
        } catch (Exception e) {
            traceSuppressed("session.metadata", e);
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
                log.warn("[Memory] fail-soft errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                traceSuppressed("memory.load", e);
            }
        } else if (memoryMode == MemoryMode.EXPLORATION) {
            log.debug("[DynamicRHC] Exploration mode active - skipping memory load");
        }
        // 2. Determine query text for downstream heuristics
        String qText = (query != null && query.text() != null) ? query.text().trim() : "";
        if (aliasCorrector != null && !qText.isBlank()
                && metaBool(QueryUtils.metadata(query), "alias.corrector.enabled", true)) {
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
                log.debug("[Alias] NineTile alias correction failed, continuing with original query. errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                traceSuppressed("alias.correct", e);
            }
        }

        // Apply alias-corrected query text to downstream retrievers (previously only
        // used for heuristics)
        Query effectiveQuery = query;
        try {
            if (effectiveQuery == null) {
                effectiveQuery = new Query(qText);
            } else if (qText != null && !qText.isBlank() && !qText.equals(effectiveQuery.text())) {
                effectiveQuery = QueryUtils.rebuild(effectiveQuery, qText);
            }
        } catch (Exception e) {
            traceSuppressed("effectiveQuery.rebuild", e);
            effectiveQuery = query;
        }

        // Dynamic K-Allocation: fill per-source topK hints into Query metadata if
        // missing.
        // This makes retrieval orchestration consistent with
        // PlanHintApplier/OrchestrationHints.
        try {
            if (kallocEnabled && effectiveQuery != null) {
                java.util.Map<String, Object> md = toMap(QueryUtils.metadata(effectiveQuery));
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
                    } catch (Exception e) {
                        traceSuppressed("kplan.intentMetadata", e);
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
                            effectiveQuery = QueryUtils.rebuild(effectiveQuery, effectiveQuery.text(), md);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[KAlloc] skip errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            traceSuppressed("kalloc.plan", e);
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
        } catch (Exception e) {
            traceSuppressed("analyze.gateMetadata", e);
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
                        String entityName = firstNonBlank(lore.getNames());
                        accumulator.add(Content.from(
                                TextSegment.from(
                                        "[Lore: " + lore.getDomain() + "] " + lore.getContent(),
                                        Metadata.from(java.util.Map.of("source", "UniversalLore", "entity",
                                                entityName)))));
                    }
                }
            } catch (Exception e) {
                log.debug("[Lore] fast-match skip errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                traceSuppressed("lore.fastMatch", e);
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
                            String entityName = firstNonBlank(lore.getNames());
                            accumulator.add(Content.from(
                                    TextSegment.from(
                                            "[Lore: " + lore.getDomain() + "] " + lore.getContent(),
                                            Metadata.from(java.util.Map.of("source", "UniversalLore", "entity",
                                                    entityName)))));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Lore] Failed to inject lore errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                traceSuppressed("lore.nerInject", e);
                mask("[Lore]", e, qText);
            }
        }
        // 3. Self-Ask stage (only when gate signals it)
        boolean needSelf = false;
        try {
            needSelf = gate != null && gate.needsSelfAsk(qText);
        } catch (Exception e) {
            log.warn("[SelfAskGate] fail-soft errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            traceSuppressed("selfAsk.gate", e);
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
        } catch (Exception e) {
            traceSuppressed("selfAsk.gateMetadata", e);
        }
        if (needSelf) {
            try {
                add(accumulator, selfAsk.retrieve(effectiveQuery));
                // Early-cut removed: continue gathering evidence instead of returning
            } catch (Exception e) {
                log.warn("[SelfAsk] fail-soft errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                traceSuppressed("selfAsk.retrieve", e);
                mask("[SelfAsk]", e, qText);
            }
        }
        // 4. Analyze stage (only when gate signals it)
        boolean needAnalyze = false;
        try {
            needAnalyze = gate != null && gate.needsAnalyze(qText);
        } catch (Exception e) {
            log.warn("[AnalyzeGate] fail-soft errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            traceSuppressed("analyze.gate", e);
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
        } catch (Exception e) {
            traceSuppressed("analyze.gateMetadata", e);
        }
        if (needAnalyze) {
            try {
                add(accumulator, analyze.retrieve(effectiveQuery));
                // Early-cut removed: continue gathering evidence instead of returning
            } catch (Exception e) {
                log.warn("[Analyze] fail-soft errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                traceSuppressed("analyze.retrieve", e);
                mask("[Analyze]", e, qText);
            }
        }
        // 5. Adaptive web search stage
        if (adaptiveWeb != null) {
            try {
                adaptiveWeb.handle(effectiveQuery, accumulator);
                // Early-cut removed: do not return here; allow subsequent stages
            } catch (Exception e) {
                log.warn("[AdaptiveWeb] fail-soft errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                traceSuppressed("web.adaptive", e);
                mask("[AdaptiveWeb]", e, qText);
            }
        }
        // 6. Dynamic order execution: Web / Vector / KG as decided by orderService
        java.util.List<Source> plan;
        try {
            plan = orderService.decideOrder(qText);
        } catch (Exception e) {
            // fallback to default order
            log.warn("[OrderService] decide failed; using default. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            traceSuppressed("order.decide", e);
            mask("[OrderService]", e, qText);
            plan = List.of(Source.WEB, Source.VECTOR, Source.KG);
        }
        // plan override: metaHints["retrieval.order"] = ["kg","vector","web"] 등
        try {
            java.util.Map<String, Object> md = toMap(QueryUtils.metadata(effectiveQuery));
            java.util.List<Source> override = parseOrderOverride(md);
            if (override != null && !override.isEmpty()) {
                plan = override;
                TraceStore.put("retrieval.order.override", override.toString());
            }
        } catch (Exception e) {
            traceSuppressed("retrieval.orderOverride", e);
        }

        // OrchestrationHints: allowWeb/allowRag per-request switches
        try {
            java.util.Map<String, Object> md = toMap(QueryUtils.metadata(effectiveQuery));
            boolean allowWeb = metaBool(md, "allowWeb", true) && metaBool(md, "retrieval.web.enabled", true);
            boolean allowRag = metaBool(md, "allowRag", true);
            if (!allowWeb) {
                plan = plan.stream().filter(s -> s != Source.WEB).toList();
            }
            if (!allowRag) {
                plan = plan.stream().filter(s -> s != Source.VECTOR && s != Source.KG).toList();
            }
            if (!metaBool(md, "retrieval.vector.enabled", true)) {
                plan = plan.stream().filter(s -> s != Source.VECTOR).toList();
            }
        } catch (Exception e) {
            traceSuppressed("retrieval.allowSwitches", e);
        }

        TraceStore.put("chain.steps.planned", ExecutionPlan.DEFAULT_STAGES.size());

        // SSE event for order decision
        try {
            _sse("ORDER_DECISION", new SseEventPublisher.Payload()
                    .kv("steps", plan)
                    .kv("reason", "heuristic")
                    .build());
        } catch (Exception e) {
            log.debug("SSE emit skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            traceSuppressed("order.sse", e);
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
                            log.debug("[UAW_THUMB] recall fail-soft errorHash={} errorLength={}",
                                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                            traceSuppressed("thumbnail.recall", e);
                            mask("[UAW_THUMB recall]", e, qText);
                        }

                        // Trace / diagnostics
                        try {
                            TraceStore.put("uaw.thumb.recall.enabled",
                                    uawThumbnailProperties != null && uawThumbnailProperties.isRecallEnabled());
                            String recallDomain = uawThumbnailProperties != null
                                    ? uawThumbnailProperties.getKnowledgeDomain()
                                    : "";
                            TraceStore.put("uaw.thumb.recall.domainHash", SafeRedactor.hashValue(recallDomain));
                            TraceStore.put("uaw.thumb.recall.domainLength", recallDomain == null ? 0 : recallDomain.length());
                            TraceStore.put("uaw.thumb.recall.hits", thumbRes == null ? 0 : thumbRes.size());
                            if (thumbRes != null && !thumbRes.isEmpty()) {
                                java.util.List<String> ents = new java.util.ArrayList<>();
                                for (Content c : thumbRes) {
                                    try {
                                        String e = c.textSegment().metadata().getString("kb_entity");
                                        if (e != null && !e.isBlank()) {
                                            ents.add(e);
                                        }
                                    } catch (Exception e) {
                                        traceSuppressed("thumbnail.entities.metadata", e);
                                    }
                                }
                                if (!ents.isEmpty()) {
                                    TraceStore.put("uaw.thumb.recall.entities", ents);
                                }
                            }
                        } catch (Exception e) {
                            traceSuppressed("thumbnail.entities.trace", e);
                        }

                        java.util.Set<String> seen = new java.util.HashSet<>();
                        if (thumbRes != null) {
                            for (Content c : thumbRes) {
                                try {
                                    seen.add(c.textSegment().text());
                                } catch (Exception e) {
                                    traceSuppressed("thumbnail.seenText", e);
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
                                        } catch (Exception e) {
                                            traceSuppressed("ocr.seenText", e);
                                        }
                                        vecRes.add(c);
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("[OCR] fail-soft errorHash={} errorLength={}",
                                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                                traceSuppressed("ocr.retrieve", e);
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
                                } catch (Exception e) {
                                    traceSuppressed("vector.seenText", e);
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
                log.warn("[{}] fail-soft errorHash={} errorLength={}", src,
                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                recordRetrievalStage(String.valueOf(src), true, 0, e, true);
                traceSuppressed("source.retrieve", e);
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
                    } catch (Exception e) {
                        traceSuppressed("riskDecel.sse", e);
                    }
                }
            } catch (Exception e) {
                log.debug("[RISK_DECEL] fail-soft errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                traceSuppressed("riskDecel.compute", e);
            }

            var fused = fuser.fuse(sources, usedTopK);
            try {
                boolean requestDiversityEnabled = metaBool(toMap(QueryUtils.metadata(effectiveQuery)),
                        "diversity.dpp.enabled", true);
                if (diversityEnabled && requestDiversityEnabled && dppDiversityReranker != null && fused != null && fused.size() > 1) {
                    TraceStore.put("dpp.reranker.source", "injected");
                    fused = dppDiversityReranker.rerank(
                            new DppDiversityReranker.Config(mmrLambda, usedTopK),
                            fused,
                            qText,
                            usedTopK,
                            content -> content == null || content.textSegment() == null ? "" : content.textSegment().text(),
                            content -> 1.0d);
                } else {
                    TraceStore.put("dpp.reranker.source", dppDiversityReranker == null ? "not_wired" : "disabled");
                }
            } catch (Exception e) {
                traceSuppressed("dpp.rerank", e);
            }
            traceFusionScorecard(webRes, vecRes, kgRes, java.util.List.of(), fused);
            add(accumulator, fused);
            try {
                _sse("FUSION_APPLIED", new SseEventPublisher.Payload()
                        .kv("sizes", java.util.Map.of("web", webRes.size(), "vec", vecRes.size(), "kg", kgRes.size()))
                        .kv("topK", topK).build());
            } catch (Exception e) {
                traceSuppressed("fusionApplied.sse", e);
            }
        }
        // 7. Repair stage (post-processing)
        try {
            if (repair != null) {
                add(accumulator, repair.retrieve(effectiveQuery));
            }
        } catch (Exception e) {
            log.warn("[Repair] fail-soft errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            traceSuppressed("repair.retrieve", e);
        }

        applyFinalSigmoidGate(accumulator);

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
            sample.put("queryHash", SafeRedactor.hashValue(qText));
            sample.put("queryLength", qText == null ? 0 : qText.length());
            sample.put("needle", needle);
            sample.put("authorityAvg", sig.authorityAvg());
            sample.put("coverage", sig.coverageScore());
            sample.put("dupRatio", sig.duplicateRatio());
            sample.put("docCount", sig.docCount());
            TraceStore.append("cfvm.sig.samples", sample);
        } catch (Exception e) {
            traceSuppressed("cfvm.signature.sample", e);
        }
    }

    private static void add(List<Content> target, List<Content> source) {
        if (source != null && !source.isEmpty()) {
            target.addAll(source);
        }
    }

    private SelfAskLaneGateResult gateSelfAskLanes(List<Content> selfAskContents,
                                                   List<Content> webContents,
                                                   List<Content> vectorContents) {
        if (!selfAskLaneGateEnabled) {
            return new SelfAskLaneGateResult(selfAskContents == null ? List.of() : List.copyOf(selfAskContents));
        }
        List<Content> selfAskSafe = selfAskContents == null ? List.of() : selfAskContents;
        List<Content> support = new java.util.ArrayList<>();
        if (webContents != null) {
            support.addAll(webContents);
        }
        if (vectorContents != null) {
            support.addAll(vectorContents);
        }
        Map<String, EvidenceSignals.LaneEvidenceSignals> signalsByLane = EvidenceSignals.computeByLane(
                selfAskSafe,
                support,
                authorityScorer,
                contradictionScorer,
                Math.max(1, selfAskLaneGateContradictionMaxPairs));
        java.util.List<Content> fusion = new java.util.ArrayList<>();
        java.util.Map<String, Double> zero100Weights = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, EvidenceSignals.LaneEvidenceSignals> entry : signalsByLane.entrySet()) {
            String lane = entry.getKey();
            EvidenceSignals.LaneEvidenceSignals sig = signalsByLane.get(lane);
            List<Content> laneContents = laneContents(selfAskSafe, lane);
            LaneDecision decision = decideLane(lane, sig, laneContents, support);
            BranchQualityProbe.BranchQualityMetrics branchMetric = null;
            if (selfAskBranchQualityEnabled && branchQualityProbe != null) {
                branchMetric = branchQualityMetric(sig);
            }
            List<Content> promptContents = decision.promptEligible()
                    ? laneContents
                    : List.of();
            if ("ANCHOR_COMPRESS".equals(decision.action())) {
                promptContents = laneContents.stream()
                        .limit(Math.max(1, selfAskLaneGateCompressTopK))
                        .toList();
                TraceStore.put("selfask.compressor.wired", false);
                TraceStore.put("selfask.compressor.skipReason", "not_wired");
                TraceStore.put("selfask.compressor.appliedCount", 0);
                if (selfAskBranchQualityEnabled && branchQualityProbe != null) {
                    EvidenceSignals.LaneEvidenceSignals compressedSig = EvidenceSignals.computeLane(
                            lane,
                            promptContents,
                            support,
                            authorityScorer,
                            contradictionScorer,
                            Math.max(1, selfAskLaneGateContradictionMaxPairs));
                    BranchQualityProbe.BranchQualityMetrics compressedMetric = branchQualityMetric(compressedSig);
                    branchMetric = compressedMetric;
                }
            }
            traceLaneGate(lane, decision.action(), decision.reason(), promptContents.size(), sig);
            if (branchMetric != null) {
                traceBranchQuality(lane, branchMetric);
            }
            List<Content> laneFusion = "BYPASS_ROUTING".equals(decision.action()) || "STRICT_VERIFY".equals(decision.action())
                    ? List.of()
                    : laneContents;
            for (Content content : laneFusion) {
                fusion.add(withLaneGateMetadata(content, decision, branchMetric, promptContents.contains(content)));
            }
            String safeLane = safeLaneId(lane);
            double baseWeight = branchMetric == null ? zero100LaneMultiplier(safeLane)
                    : Math.max(0.05d, branchQualityWeight(branchMetric));
            baseWeight = configuredLaneWeight(TraceStore.get("zero100.branch.weights"), safeLane, baseWeight);
            ThreeLaneRiskConsensus.Decision consensusDecision =
                    zero100RiskConsensusDecision(safeLane, sig, branchMetric, baseWeight);
            traceZero100RiskConsensus(consensusDecision);
            zero100Weights.put(safeLane, consensusDecision.adjustedRrfWeight());
        }
        traceZero100Consensus(zero100Weights);
        return new SelfAskLaneGateResult(fusion);
    }

    private LaneDecision decideLane(String lane,
                                    EvidenceSignals.LaneEvidenceSignals sig,
                                    List<Content> laneContents,
                                    List<Content> support) {
        String safeLane = safeLaneId(lane);
        if ("RC".equals(safeLane) && (sig == null || sig.crossLaneSupportRate() < selfAskLaneGateMinCrossLaneSupport)) {
            return new LaneDecision("BYPASS_ROUTING", "relation_support_missing", false);
        }
        if (sig != null
                && sig.contradictionRate() > selfAskLaneGateTailProbeMaxContradictionRate
                && sig.authorityAvg() >= selfAskLaneGateTailProbeStrictMinAuthority
                && sig.tailSignal() >= selfAskLaneGateTailProbeMinSignal) {
            return new LaneDecision("STRICT_VERIFY", "tail_strict_contradiction", false);
        }
        boolean tailProbe = authorityScorer != null
                && sig != null
                && sig.tailSignal() >= selfAskLaneGateTailProbeMinSignal
                && sig.authorityAvg() >= selfAskLaneGateTailProbeMinAuthority;
        if (tailProbe) {
            boolean promptReady = sig.crossLaneSupportRate() >= selfAskLaneGateMinCrossLaneSupport
                    && sig.grandasReadiness() >= selfAskLaneGatePromptMinReadiness
                    && sig.contradictionRate() <= selfAskLaneGateTailProbeMaxContradictionRate;
            return new LaneDecision("TAIL_PROBE",
                    promptReady ? "tail_probe_prompt_pass" : "tail_probe_fusion_only",
                    promptReady);
        }
        boolean duplicateHeavy = sig != null && sig.duplicateRate() > selfAskLaneGateMaxDuplicateRate;
        if (duplicateHeavy && (sig == null || sig.contradictionRate() <= selfAskLaneGateMaxContradictionRate)) {
            return new LaneDecision("ANCHOR_COMPRESS", "duplicate_pressure", true);
        }
        boolean thresholdPass = sig != null
                && sig.evidenceRate() >= selfAskLaneGateMinEvidenceRate
                && sig.strongCitationRate() >= selfAskLaneGateMinStrongCitationRate
                && sig.distinctCitationCount() >= selfAskLaneGateMinDistinctCitations
                && sig.duplicateRate() <= selfAskLaneGateMaxDuplicateRate
                && sig.contradictionRate() <= selfAskLaneGateMaxContradictionRate
                && sig.confidence() >= selfAskLaneGateMinConfidence
                && (sig.crossLaneSupportRate() >= selfAskLaneGateMinCrossLaneSupport || support == null || support.isEmpty());
        if (thresholdPass) {
            return new LaneDecision("NORMAL", "threshold_pass", true);
        }
        if (sig != null && sig.confidence() <= selfAskLaneGateBypassConfidence) {
            return new LaneDecision("BYPASS_ROUTING", "confidence_low", false);
        }
        return new LaneDecision("TAIL_PROBE", "tail_probe_fusion_only", false);
    }

    private void traceLaneGate(String lane,
                               String action,
                               String reason,
                               int promptEligibleCount,
                               EvidenceSignals.LaneEvidenceSignals sig) {
        /*
         * Source-contract anchors:
         * TraceStore.put("selfask.laneGate." + lane + ".reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
         * event.put("reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
         */
        String safe = safeLaneKey(lane);
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("lane", safeLaneId(lane));
        event.put("action", SafeRedactor.traceLabelOrFallback(action, "unknown"));
        event.put("reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        event.put("promptEligibleCount", Math.max(0, promptEligibleCount));
        TraceStore.put("selfask.laneGate." + safe + ".action", SafeRedactor.traceLabelOrFallback(action, "unknown"));
        TraceStore.put("selfask.laneGate." + safe + ".reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        TraceStore.put("selfask.laneGate." + safe + ".promptEligibleCount", Math.max(0, promptEligibleCount));
        if (sig != null) {
            TraceStore.put("selfask.laneGate." + safe + ".crossLaneSupportRate", sig.crossLaneSupportRate());
            TraceStore.put("selfask.laneGate." + safe + ".tailSignal", sig.tailSignal());
        }
        TraceStore.append("selfask.laneGate.events", event);
    }

    private BranchQualityProbe.BranchQualityMetrics branchQualityMetric(EvidenceSignals.LaneEvidenceSignals sig) {
        if (branchQualityProbe == null) {
            return null;
        }
        return branchQualityProbe.evaluateLane(sig, branchQualityThresholds());
    }

    private BranchQualityProbe.Thresholds branchQualityThresholds() {
        return new BranchQualityProbe.Thresholds(
                selfAskBranchQualityMinContextContribution,
                selfAskBranchQualityMaxRiskPenalty,
                selfAskBranchQualityMoeTemperature,
                mmrLambda,
                selfAskBranchQualityDppMinLambda,
                selfAskBranchQualityDppMaxLambda,
                Math.max(1, topK),
                0.70d);
    }

    private void traceBranchQuality(String lane, BranchQualityProbe.BranchQualityMetrics branchMetric) {
        if (branchMetric == null) {
            return;
        }
        String safe = safeLaneKey(lane);
        TraceStore.put("selfask.branchQuality." + safe + ".action",
                branchMetric.action() == null ? "PASS" : branchMetric.action().name());
        TraceStore.put("selfask.branchQuality." + safe + ".reason",
                SafeRedactor.traceLabelOrFallback(branchMetric.reason(), "unknown"));
    }

    private int branchQualityFusionTopK(List<BranchQualityProbe.BranchQualityMetrics> metrics, int baseTopK) {
        int base = Math.max(1, baseTopK);
        if (!selfAskBranchQualityDppEnabled || branchQualityProbe == null || metrics == null || metrics.isEmpty()) {
            return base;
        }
        double pressure = branchQualityProbe.diversityPressure(metrics);
        if (pressure < selfAskBranchQualityDppMinPressure) {
            return base;
        }
        return Math.min(Math.max(base, selfAskBranchQualityDppPoolMax),
                Math.max(base, base * Math.max(1, selfAskBranchQualityDppPoolMultiplier)));
    }

    private double branchQualityWeight(BranchQualityProbe.BranchQualityMetrics metric) {
        if (metric == null) {
            return 1.0d;
        }
        double weight = metric.rrfWeight();
        if (!selfAskBranchQualityBlackboxPriorEnabled || !traceTruthy("blackbox.risk.graphRecommendation.applied")) {
            return weight;
        }
        Object stack = TraceStore.get("blackbox.risk.anchorStack");
        if (stack instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map) {
                    String lane = SafeRedactor.traceLabelOrFallback(map.get("lane"), "");
                    if (safeLaneId(metric.branchId()).equals(safeLaneId(lane))) {
                        TraceStore.put("selfask.branchQuality.blackboxPrior.appliedLane", safeLaneId(metric.branchId()));
                        return Math.max(0.05d, weight * 0.80d);
                    }
                }
            }
        }
        return weight;
    }

    private Content withLaneGateMetadata(Content content,
                                         LaneDecision decision,
                                         BranchQualityProbe.BranchQualityMetrics branchMetric,
                                         boolean promptEligible) {
        if (content == null || content.textSegment() == null) {
            return content;
        }
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(content.textSegment().metadata().toMap());
        metadata.put("promptEligible", String.valueOf(promptEligible));
        metadata.put("selfask_lane_gate_reason", SafeRedactor.traceLabelOrFallback(decision.reason(), "unknown"));
        if (branchMetric != null) {
            metadata.put("branch_quality_branch_id", safeLaneId(branchMetric.branchId()));
            metadata.put("branch_quality_intent_axis", SafeRedactor.traceLabelOrFallback(branchMetric.intentAxis(), "unknown"));
            metadata.put("branch_quality_reason", SafeRedactor.traceLabelOrFallback(branchMetric.reason(), "unknown"));
            metadata.put("branch_quality_rrf_weight", Math.max(1.0d, branchQualityWeight(branchMetric)));
        }
        return Content.from(TextSegment.from(content.textSegment().text(), Metadata.from(metadata)));
    }

    private static List<Content> laneContents(List<Content> contents, String lane) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        String safeLane = safeLaneId(lane);
        return contents.stream()
                .filter(content -> safeLane.equals(safeLaneId(laneOf(content))))
                .toList();
    }

    private static String laneOf(Content content) {
        try {
            if (content == null || content.textSegment() == null || content.textSegment().metadata() == null) {
                return "unknown";
            }
            Object lane = content.textSegment().metadata().toMap().get("retrieval_lane");
            if (lane == null) {
                lane = content.textSegment().metadata().toMap().get("query_branch");
            }
            return lane == null ? "unknown" : String.valueOf(lane);
        } catch (Exception e) {
            traceSuppressed("lane.metadata", e);
            return "unknown";
        }
    }

    private void traceZero100Consensus(Map<String, Double> weights) {
        if (!traceTruthy("zero100.enabled") || !traceTruthy("zero100.consensus.enabled")) {
            return;
        }
        Map<String, Double> safeWeights = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            safeWeights.put(entry.getKey(), entry.getValue());
        }
        int coverage = safeWeights.size();
        int minCoverage = traceInt("zero100.riskConsensus.minLaneCoverage", 1);
        TraceStore.put("zero100.consensus.rrfWeights", safeWeights);
        TraceStore.put("zero100.consensus.laneCoverage", coverage);
        TraceStore.put("zero100.consensus.status", coverage < minCoverage ? "LOW_COVERAGE_FAILSOFT" : "READY");
    }

    private ThreeLaneRiskConsensus.Decision zero100RiskConsensusDecision(
            String lane,
            EvidenceSignals.LaneEvidenceSignals sig,
            BranchQualityProbe.BranchQualityMetrics branchMetric,
            double baseWeight) {
        ThreeLaneRiskConsensus.Config config = new ThreeLaneRiskConsensus.Config(
                traceTruthy("zero100.riskConsensus.enabled"),
                traceDouble("zero100.riskConsensus.riskPenaltyLambda", 0.45d),
                traceInt("zero100.riskConsensus.minLaneCoverage", 2),
                traceDouble("zero100.riskConsensus.minRrfWeight", 0.05d),
                traceDouble("zero100.riskConsensus.maxRrfWeight", 1.25d));
        return ThreeLaneRiskConsensus.evaluate(
                lane,
                sig,
                branchMetric,
                baseWeight,
                zero100RiskConsensusLaneRdi(lane, sig),
                config);
    }

    private static void traceZero100RiskConsensus(ThreeLaneRiskConsensus.Decision decision) {
        if (decision == null) {
            return;
        }
        String safeLane = safeLaneKey(decision.lane());
        Map<String, Object> payload = new java.util.LinkedHashMap<>(decision.tracePayload());
        payload.putAll(zero100RiskConsensusFailureSignals(decision.lane()));
        TraceStore.append("zero100.consensus.laneRisk.events", payload);
        TraceStore.put("zero100.consensus." + safeLane + ".laneRisk", decision.laneRisk());
        TraceStore.put("zero100.consensus." + safeLane + ".riskMultiplier", decision.riskMultiplier());
        TraceStore.put("zero100.consensus." + safeLane + ".adjustedRrfWeight", decision.adjustedRrfWeight());
    }

    private static double zero100RiskConsensusLaneRdi(String lane, EvidenceSignals.LaneEvidenceSignals sig) {
        String safeLane = safeLaneId(lane);
        double configured = traceDouble("zero100.riskConsensus.laneRdi." + safeLane, 0.0d);
        double providerDisabled = providerDisabledSignalPresent() ? 0.65d : 0.0d;
        double afterFilter = afterFilterStarvationSignalPresent() ? 0.55d : 0.0d;
        double contradiction = Math.max(
                sig == null ? 0.0d : sig.contradictionRate(),
                Math.max(traceDouble("ml.risk.rewrite.components.contradiction"),
                        Math.max(traceDouble("overdrive.contradiction.mean"),
                                traceDouble("rag.contradiction.score"))));
        double latency = Math.max(traceDouble("ml.risk.rewrite.components.latencyPressure"),
                Math.max(traceDouble("timeBudget.latencyPenalty"),
                        traceDouble("zero100.timeBudget.guard.latencyPenalty")));
        return clamp01(Math.max(Math.max(configured, providerDisabled), Math.max(afterFilter, Math.max(contradiction, latency))));
    }

    private static Map<String, Object> zero100RiskConsensusFailureSignals(String lane) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("failureLane", safeLaneId(lane));
        out.put("providerDisabled", providerDisabledSignalPresent());
        out.put("afterFilterStarvation", afterFilterStarvationSignalPresent());
        out.put("contradictionPressure", round4(Math.max(traceDouble("ml.risk.rewrite.components.contradiction"),
                Math.max(traceDouble("overdrive.contradiction.mean"), traceDouble("rag.contradiction.score")))));
        out.put("latencyPressure", round4(Math.max(traceDouble("ml.risk.rewrite.components.latencyPressure"),
                Math.max(traceDouble("timeBudget.latencyPenalty"), traceDouble("zero100.timeBudget.guard.latencyPenalty")))));
        return out;
    }

    private static boolean providerDisabledSignalPresent() {
        if (TraceStore.get("selfask.3way.api.disabledReason") != null) {
            return true;
        }
        for (String provider : new String[]{"naver", "tavily", "brave", "serpapi"}) {
            Object reason = TraceStore.get("web." + provider + ".failureReason");
            if (reason != null && "provider-disabled".equalsIgnoreCase(String.valueOf(reason).trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean afterFilterStarvationSignalPresent() {
        return afterFilterStarved("naver")
                || afterFilterStarved("tavily")
                || afterFilterStarved("brave")
                || afterFilterStarved("serpapi")
                || traceTruthy("rag.eval.afterFilterStarvation");
    }

    private static double configuredLaneWeight(Object configured, String lane, double fallback) {
        if (configured instanceof Map<?, ?> map) {
            Object value = map.get(lane);
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            if (value != null) {
                try {
                    return Double.parseDouble(String.valueOf(value).trim());
                } catch (NumberFormatException e) {
                    traceSuppressed("zero100.consensusLane", e);
                }
            }
        }
        return fallback;
    }

    private static String safeLaneKey(String lane) {
        return safeLaneId(lane).toLowerCase(java.util.Locale.ROOT);
    }

    private static String safeLaneId(String lane) {
        String raw = lane == null ? "" : lane.trim();
        if (raw.equals("BQ") || raw.equals("ER") || raw.equals("RC")) {
            return raw;
        }
        String label = SafeRedactor.traceLabelOrFallback(raw, "unknown");
        if (label.startsWith("hash:")) {
            return label.replace(':', '_');
        }
        String upper = label.toUpperCase(java.util.Locale.ROOT);
        return upper.equals("BQ") || upper.equals("ER") || upper.equals("RC") ? upper : label;
    }

    private record LaneDecision(String action, String reason, boolean promptEligible) {
    }

    private static final class SelfAskLaneGateResult {
        private final List<Content> fusionContents;

        private SelfAskLaneGateResult(List<Content> fusionContents) {
            this.fusionContents = fusionContents == null ? List.of() : List.copyOf(fusionContents);
        }

        private List<Content> fusionContents() {
            return fusionContents;
        }
    }

    private void recordRetrievalStage(String stageName, boolean attempted, int outCount,
                                      Throwable error, boolean fallbackUsed) {
        String stage = normalizeStageName(stageName);
        String exceptionType = error == null ? "" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
        String failureClass = error == null ? (fallbackUsed ? "fallback" : "") : classifyFailure(error);
        try {
            if (attempted) {
                TraceStore.inc("drhc.execute.stagesAttempted");
                TraceStore.inc("chain.steps.executed");
            }
            if (error == null) {
                TraceStore.inc("drhc.execute.stagesSucceeded");
            } else {
                TraceStore.inc("chain.steps.failed");
            }
            TraceStore.put("retrieval.stage." + stage + ".attempted", attempted);
            TraceStore.put("retrieval.stage." + stage + ".outCount", Math.max(0, outCount));
            TraceStore.put("retrieval.stage." + stage + ".exceptionType", exceptionType);
            TraceStore.put("retrieval.stage." + stage + ".failureClass", failureClass);
            TraceStore.put("retrieval.stage." + stage + ".fallbackUsed", fallbackUsed);
            if (error != null && fallbackUsed) {
                TraceStore.put("drhc.handler." + stage + ".failed", true);
                TraceStore.put("drhc.handler." + stage + ".failureClass", failureClass);
            }
            java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("stageName", stage);
            event.put("attempted", attempted);
            event.put("outCount", Math.max(0, outCount));
            event.put("exceptionType", exceptionType);
            event.put("failureClass", failureClass);
            event.put("fallbackUsed", fallbackUsed);
            TraceStore.append("retrieval.stage.events", event);
        } catch (Exception ex) {
            traceSuppressed("stage.trace", ex);
        }
        if (faultMaskingLayerMonitor != null && error != null) {
            mask("retrieval.stage." + stage, error, "stage=" + stage);
        }
    }

    private static String classifyFailure(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = root.getClass().getSimpleName();
        String lowerName = name.toLowerCase(Locale.ROOT);
        String msg = root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        if (root instanceof java.util.concurrent.CancellationException
                || root instanceof InterruptedException
                || lowerName.contains("cancel")
                || lowerName.contains("interrupt")
                || msg.contains("cancelled")
                || msg.contains("canceled")
                || msg.contains("interrupted")) {
            return "cancelled";
        }
        if (msg.contains("timeout") || lowerName.contains("timeout")) {
            return "timeout";
        }
        if (msg.contains("rate limit") || msg.contains("429")) {
            return "rate-limit";
        }
        if (msg.contains("credential") || msg.contains("api key") || msg.contains("unauthorized")) {
            return "provider-disabled";
        }
        return "silent-failure";
    }

    private static String safeFailureClass(Throwable error) {
        String failureClass = classifyFailure(error);
        return SafeRedactor.traceLabelOrFallback(failureClass == null || failureClass.isBlank() ? "none" : failureClass,
                "unknown");
    }

    private static java.util.Map<String, Object> toMap(Object meta) {
        java.util.Map<String, Object> raw = QueryUtils.metadata(meta);
        if (raw == null || raw.isEmpty()) {
            return new java.util.LinkedHashMap<>();
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (restrictedMetadataKey(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Number n && !Double.isFinite(n.doubleValue())) {
                continue;
            }
            out.put(key, value);
        }
        return out;
    }

    private static boolean restrictedMetadataKey(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String k = key.trim().toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
        return k.equals("content")
                || k.equals("rawcontent")
                || k.equals("rawquery")
                || k.equals("queryraw")
                || SafeRedactor.isRestrictedKey(key);
    }

    private static boolean metaBool(java.util.Map<String, Object> meta, String key, boolean def) {
        if (meta == null)
            return def;
        Object v = meta.get(key);
        if (v instanceof Boolean b)
            return b;
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? n.intValue() != 0 : def;
        }
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
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? n.intValue() : def;
        }
        if (v instanceof String s) {
            try {
                double parsed = Double.parseDouble(s.trim());
                return Double.isFinite(parsed) ? (int) parsed : def;
            } catch (NumberFormatException e) {
                traceSuppressed("metaInt.parse", e);
                return def;
            }
        }
        return def;
    }

    private static double metaDouble(java.util.Map<String, Object> meta, String key, double def) {
        if (meta == null)
            return def;
        Object v = meta.get(key);
        if (v == null)
            return def;
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? numeric : def;
        }
        if (v instanceof String s) {
            try {
                double parsed = Double.parseDouble(s.trim());
                return Double.isFinite(parsed) ? parsed : def;
            } catch (NumberFormatException e) {
                traceSuppressed("metaDouble.parse", e);
                return def;
            }
        }
        return def;
    }

    private static boolean traceTruthy(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) && numeric != 0.0d;
        }
        if (value instanceof String s) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            return t.equals("true") || t.equals("1") || t.equals("yes");
        }
        return false;
    }

    private static long traceLong(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? n.longValue() : 0L;
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            traceSuppressed("traceLong", e);
            return 0L;
        }
    }

    private static double traceDouble(String key) {
        Object value = TraceStore.get(key);
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? numeric : 0.0d;
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : 0.0d;
        } catch (NumberFormatException e) {
            traceSuppressed("traceDouble", e);
            return 0.0d;
        }
    }

    private static int traceInt(String key, int defaultValue) {
        Object value = TraceStore.get(key);
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? n.intValue() : defaultValue;
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? (int) parsed : defaultValue;
        } catch (NumberFormatException e) {
            traceSuppressed("traceInt", e);
            return defaultValue;
        }
    }

    private static double traceDouble(String key, double defaultValue) {
        Object value = TraceStore.get(key);
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? numeric : defaultValue;
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            traceSuppressed("traceDouble.default", e);
            return defaultValue;
        }
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static double zero100LaneMultiplier(String lane) {
        if (lane == null || lane.isBlank()) {
            return 1.0d;
        }
        try {
            double traced = Double.parseDouble(String.valueOf(TraceStore.get("zero100.laneMultiplier." + lane)).trim());
            return Double.isFinite(traced) ? traced : 1.0d;
        } catch (NumberFormatException e) {
            traceSuppressed("zero100.laneMultiplier", e);
            return 1.0d;
        }
    }

    private static String normalizeStageName(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return "unknown";
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        boolean stable = lower.matches("[a-z0-9_.-]{1,80}")
                && !lower.contains("ownertoken")
                && !lower.contains("api_key")
                && !lower.contains("apikey")
                && !lower.contains("authorization")
                && !lower.contains("cookie")
                && !lower.contains("secret")
                && !lower.matches(".*sk-[a-z0-9_-]{20,}.*");
        if (stable) {
            return lower;
        }
        String hash = SafeRedactor.hash12(text);
        return "hash_" + (hash == null ? "unknown" : hash);
    }

    private static String firstNonBlank(java.util.List<String> values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String searchFailureReason(Object primary,
                                       java.util.List<Content> accumulator,
                                       int primaryCount,
                                       int webCount,
                                       int vectorCount,
                                       int kgCount) {
        String reason = null;
        if (afterFilterStarved("naver") || afterFilterStarved("tavily") || afterFilterStarved("brave")
                || afterFilterStarved("serpapi")) {
            reason = "after_filter_starvation";
        } else if (traceTruthy("web.tavily.zeroResults") || traceTruthy("web.naver.zeroResults")
                || traceTruthy("web.brave.zeroResults") || traceTruthy("web.serpapi.zeroResults")) {
            reason = "zero_result";
        } else {
            int total = Math.max(0, primaryCount) + Math.max(0, webCount) + Math.max(0, vectorCount)
                    + Math.max(0, kgCount) + (accumulator == null ? 0 : accumulator.size());
            if (primary == null && total == 0) {
                reason = "zero_result";
            }
        }
        if (reason != null) {
            TraceStore.put("rag.recovery.reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        }
        return reason;
    }

    private static boolean afterFilterStarved(String provider) {
        long returned = traceLong("web." + provider + ".returnedCount");
        long after = traceLong("web." + provider + ".afterFilterCount");
        return returned > 0 && after == 0;
    }

    private java.util.List<String> recoverySubqueries(String originalQuery, Query rewrittenQuery) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        addRecoverySubquery(out, originalQuery);
        addRecoverySubquery(out, rewrittenQuery == null ? null : rewrittenQuery.text());
        while (out.size() > Math.max(1, searchFailureRecoveryMaxSubqueries)) {
            String last = null;
            for (String item : out) {
                last = item;
            }
            if (last == null) {
                break;
            }
            out.remove(last);
        }
        TraceStore.put("rag.recovery.subqueryCount", out.size());
        return new java.util.ArrayList<>(out);
    }

    private static void addRecoverySubquery(java.util.LinkedHashSet<String> out, String queryText) {
        if (out == null || queryText == null) {
            return;
        }
        String text = queryText.trim();
        if (!text.isBlank()) {
            out.add(text);
        }
    }

    private java.util.Map<String, Object> recoveryEventPayload(String breadcrumbId,
                                                               String reason,
                                                               String strategy,
                                                               java.util.List<String> lanes,
                                                               int originalCount,
                                                               int recoveredCount,
                                                               java.util.List<String> subqueries,
                                                               int subqueryCount,
                                                               String originalQuery,
                                                               String rewrittenQuery) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("breadcrumb_id", SafeRedactor.traceLabelOrFallback(breadcrumbId, "rag-recovery"));
        payload.put("zero_result_reason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        payload.put("strategy", SafeRedactor.traceLabelOrFallback(strategy, "unknown"));
        payload.put("lanes", lanes == null ? java.util.List.of() : lanes.stream()
                .map(v -> SafeRedactor.traceLabelOrFallback(v, "lane"))
                .toList());
        payload.put("original_count", Math.max(0, originalCount));
        payload.put("recovered_count", Math.max(0, recoveredCount));
        payload.put("subquery_count", Math.max(0, subqueryCount));
        payload.put("subquery_hashes", subqueries == null ? java.util.List.of() : subqueries.stream()
                .map(SafeRedactor::hashValue)
                .toList());
        payload.put("query_original", queryDiagnostic(originalQuery));
        payload.put("query_rewritten", queryDiagnostic(rewrittenQuery));
        return payload;
    }

    private static java.util.Map<String, Object> queryDiagnostic(String queryText) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        String text = queryText == null ? "" : queryText;
        out.put("present", !text.isBlank());
        out.put("length", text.length());
        out.put("hash", SafeRedactor.hashValue(text));
        return out;
    }

    private void emitRecoveryBreadcrumb(String step, java.util.Map<String, Object> payload, Throwable error) {
        try {
            java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("kind", "rag.pipeline");
            event.put("phase", "recovery");
            event.put("stage", "search_failure");
            event.put("step", SafeRedactor.traceLabelOrFallback(step, "unknown"));
            event.put("status", error == null ? "ok" : "error");
            event.put("component", "DynamicRetrievalHandlerChain.runSearchFailureRecovery");
            event.put("input", java.util.Map.of(
                    "mode", "active_gated",
                    "queryHash", SafeRedactor.traceLabelOrFallback(TraceStore.get("rag.recovery.queryOriginalHash"), "unknown")));
            event.put("failure", java.util.Map.of(
                    "reasonCode", SafeRedactor.traceLabelOrFallback(payload == null ? null : payload.get("zero_result_reason"), "unknown"),
                    "errorClass", safeFailureClass(error)));
            event.put("control", java.util.Map.of(
                    "action", "recovery",
                    "breadcrumbId", SafeRedactor.traceLabelOrFallback(payload == null ? null : payload.get("breadcrumb_id"), "rag-recovery")));
            event.put("metrics", java.util.Map.of(
                    "recoveredCount", payload == null ? 0 : Math.max(0, metaInt(payload, "recovered_count", 0)),
                    "subqueryCount", payload == null ? 0 : Math.max(0, metaInt(payload, "subquery_count", 0))));
            TraceStore.append("orch.events.v1", event);
        } catch (Exception e) {
            traceSuppressed("recovery.event", e);
        }
    }

    private java.util.List<Content> collectRecoveryBuckets(java.util.List<? extends java.util.concurrent.Future<?>> futures,
                                                           String breadcrumbId,
                                                           String strategy) {
        java.util.List<Content> recovered = new java.util.ArrayList<>();
        if (futures == null || futures.isEmpty()) {
            return recovered;
        }
        for (java.util.concurrent.Future<?> future : futures) {
            if (future == null) {
                continue;
            }
            try {
                Object value = future.get(Math.max(1L, searchFailureRecoveryTimeoutMs),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                if (value instanceof java.util.Collection<?> items) {
                    for (Object item : items) {
                        if (item instanceof Content content) {
                            recovered.add(content);
                        }
                    }
                } else if (value instanceof Content content) {
                    recovered.add(content);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(false);
                TraceStore.put("rag.recovery.cancelMode", "no_interrupt");
                TraceStore.put("rag.recovery.timeout.reason", "future_timeout");
                traceSuppressed("recovery.futureTimeout", e);
            } catch (java.util.concurrent.CancellationException e) {
                traceSuppressed("recovery.future", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                TraceStore.put("rag.recovery.cancelMode", "no_interrupt");
                traceSuppressed("recovery.future", e);
            } catch (java.util.concurrent.ExecutionException e) {
                traceSuppressed("recovery.futureJoin", e);
            }
        }
        TraceStore.put("rag.recovery.recoveredCount", recovered.size());
        return recovered;
    }

    private void traceFusionScorecard(java.util.List<Content> web,
                                      java.util.List<Content> vector,
                                      java.util.List<Content> kg,
                                      java.util.List<Content> selfAsk,
                                      java.util.List<Content> fused) {
        try {
            int webSize = sizeOf(web);
            int vectorSize = sizeOf(vector);
            int kgSize = sizeOf(kg);
            int selfAskSize = sizeOf(selfAsk);
            int inputTotal = webSize + vectorSize + kgSize + selfAskSize;
            int fusedCount = sizeOf(fused);
            int kgRetained = countRetained(fused, kg);
            java.util.Map<String, Object> scorecard = new java.util.LinkedHashMap<>();
            scorecard.put("web", webSize);
            scorecard.put("vector", vectorSize);
            scorecard.put("kg", kgSize);
            scorecard.put("selfAsk", selfAskSize);
            scorecard.put("inputTotal", inputTotal);
            scorecard.put("fusedCount", fusedCount);
            scorecard.put("kgInputShare", inputTotal == 0 ? 0.0d : ((double) kgSize) / inputTotal);
            scorecard.put("kgRetainedCount", kgRetained);
            scorecard.put("kgRetainedShare", kgSize == 0 ? 0.0d : ((double) kgRetained) / kgSize);
            FusionScoreDiagnostics.putScoreMeans(scorecard, web, vector, kg);
            TraceStore.put("rag.fusion.scorecard", scorecard);
            TraceStore.put("rag.fusion.sizes.selfask", selfAskSize);
            TraceStore.put("rag.fusion.sizes.web", webSize);
            TraceStore.put("rag.fusion.sizes.vector", vectorSize);
            TraceStore.put("rag.fusion.sizes.kg", kgSize);
            TraceStore.put("rag.fusion.weights.kg", kgSize == 0 ? 0.0d : ((double) kgRetained) / kgSize);
            TraceStore.put("rag.fusion.final.kgCount", kgRetained);
            TraceStore.put("rag.fusion.final.totalCount", fusedCount);
            FusionScoreDiagnostics.traceScoreMeans(scorecard);
        } catch (Exception e) {
            traceSuppressed("fusion.scorecard", e);
        }
    }

    private static int sizeOf(java.util.List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static int countRetained(java.util.List<Content> fused, java.util.List<Content> kg) {
        if (fused == null || fused.isEmpty() || kg == null || kg.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Content content : fused) {
            if (kg.contains(content)) {
                count++;
            }
        }
        return count;
    }

    private void applyFinalSigmoidGate(java.util.List<Content> accumulator) {
        if (finalSigmoidGate == null || accumulator == null) {
            return;
        }
        try {
            double compositeScore = accumulator.isEmpty() ? 0.0d : 1.0d;
            double policyRisk = 0.0d;
            boolean hasStrongEvidence = accumulator.size() >= Math.max(1, selfAskLaneGateCitationMin);
            FinalSigmoidGate.GateResult result = finalSigmoidGate.check(compositeScore, policyRisk, hasStrongEvidence);
            TraceStore.put("retrieval.finalSigmoidGate.applied", true);
            TraceStore.put("retrieval.finalSigmoidGate.result", result == null ? "UNKNOWN" : result.name());
            if (result == FinalSigmoidGate.GateResult.BLOCK && accumulator.size() > 3) {
                int original = accumulator.size();
                accumulator.subList(3, accumulator.size()).clear();
                TraceStore.put("retrieval.finalSigmoidGate.blockTrim.count", original - accumulator.size());
            }
        } catch (Exception e) {
            traceSuppressed("sigmoid.promptGate", e);
        }
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
        java.util.Map<String, Object> md = QueryUtils.metadata(original);
        md.put(com.example.lms.service.rag.LangChainRAGService.META_SID, sessionKey);
        return QueryUtils.buildQuery(original.text(), md);
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
    public java.util.List<Object> getSteps() {
        java.util.List<Object> steps = new java.util.ArrayList<>();
        addStep(steps, memoryHandler);
        addStep(steps, selfAsk);
        addStep(steps, analyze);
        addStep(steps, adaptiveWeb);
        addStep(steps, web);
        addStep(steps, rag);
        addStep(steps, kg);
        addStep(steps, repair);
        return java.util.Collections.unmodifiableList(steps);
    }

    private static void addStep(java.util.List<Object> steps, Object step) {
        if (step != null) {
            steps.add(step);
        }
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
        ks.maxSourceShare = this.kallocMaxSourceShare;
        ks.recencyKeywords = splitCsv(this.kallocRecencyKeywordsCsv, ks.recencyKeywords);

        if (!ks.enabled) {
            return null;
        }

        com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator allocator = new com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator(
                ks);

        double[] artPlateTails = KAllocArtPlateBridge.tails(md);
        com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.Input in = new com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.Input(
                intent, query, officialOnly, 0.0d, 0.0d, 0.0d,
                artPlateTails[0], artPlateTails[1], artPlateTails[2]);

        // Baseline heuristic plan (always available)
        com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator.KPlan base = allocator.decide(in);

        // Query complexity is an explicit signal we can feed into higher-level tuning.
        QueryComplexityGate.Level cx = QueryComplexityGate.Level.AMBIGUOUS;
        try {
            if (this.gate != null) {
                cx = this.gate.assess(query);
            }
        } catch (Exception e) {
            traceSuppressed("queryComplexity.assess", e);
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
                    } catch (Exception e) {
                        traceSuppressed("cfvm.kalloc.metadata", e);
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
                log.debug("[KAlloc] resource hints skip errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
                traceSuppressed("resourceHints", e);
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
            log.debug("SSE emit skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            traceSuppressed("ragEvent.trace", e);
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
            log.debug("SSE emit skipped. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            traceSuppressed("ragEvent.trace", e);
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
        } catch (Exception e) {
            traceSuppressed("faultMask.record", e);
            // fail-soft
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        String safeStage = normalizeStageName(stage);
        RetrievalHandlerTraceSuppressions.traceSuppressed(log, "DynamicRetrievalHandlerChain", safeStage, error);
        String errorType = error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
        TraceStore.put("dynamicRetrieval.suppressed.stage", safeStage);
        TraceStore.put("dynamicRetrieval.suppressed.errorType", safeFailureClass(error));
        TraceStore.put("dynamicRetrieval.suppressed." + safeStage, true);
        TraceStore.put("dynamicRetrieval.suppressed." + safeStage + ".errorType", errorType);
    }

    private static void traceSuppressed(Logger logger, String component, String stage, Throwable error) {
        RetrievalHandlerTraceSuppressions.traceSuppressed(logger, component, stage, error);
    }

    @SuppressWarnings("unused")
    private static void dynamicRetrievalHandlerStageAnchors(Throwable e) {
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "session.metadata", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "memory.load", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "alias.correct", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "effectiveQuery.rebuild", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "resourceHints", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "kplan.intentMetadata", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "kalloc.plan", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "loreNer.gateMetadata", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "lore.nerInject", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "selfAsk.gate", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "selfAsk.gateMetadata", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "selfAsk.retrieve", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "analyze.gate", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "analyze.gateMetadata", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "analyze.retrieve", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "web.adaptive", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "order.decide", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "retrieval.orderOverride", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "retrieval.allowSwitches", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "order.sse", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "thumbnail.recall", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "thumbnail.entities.metadata", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "thumbnail.entities.trace", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "thumbnail.seenText", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "ocr.seenText", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "ocr.retrieve", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "vector.seenText", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "source.retrieve", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "riskDecel.sse", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "riskDecel.compute", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "fusionApplied.sse", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "repair.retrieve", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "cfvm.sampleTrace", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "recovery.signals", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "recovery.future", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "recovery.futureTimeout", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "recovery.futureJoin", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "recovery.cancelTrace", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "recovery.annotateDoc", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "recovery.event", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "query.text", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "traceLong", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "traceDouble", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "zero100.consensusLane", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "zero100.laneMultiplier", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "traceInt", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "traceDouble.default", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "sigmoid.promptGate", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "sigmoid.tailProbe", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "lane.metadata", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "blackboxPrior.trace", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "branchQuality.trace", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "fusion.scorecard", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "ragEvent.trace", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "debugEvent.emit", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "metaInt.parse", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "metaDouble.parse", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "hypernovaPlan.trace", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "rewriteRisk.trace", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "queryComplexity.assess", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "cfvm.kalloc.metadata", e);
        traceSuppressed(log, "DynamicRetrievalHandlerChain", "faultMask.record", e);
    }

    @SuppressWarnings("unused")
    /*
     * Source-contract anchors for safe emergent/branch reasons:
     * SafeRedactor.traceLabelOrFallback(risk.emergentAdjustment().reason(), "unknown")
     * putIfChanged(md, "resource.riskEmergentReason", safeEmergentReason)
     * TraceStore.put("resource.riskEmergentReason", safeEmergentReason);
     * TraceStore.put("ml.risk.emergent.reason", safeEmergentReason);
     * "reason", safeEmergentReason
     * metadata.put("branch_quality_reason",
     * SafeRedactor.traceLabelOrFallback(branchMetric.reason(), "unknown")
     */
    private static final String RISK_EMERGENT_SOURCE_CONTRACT = String.join("\n",
            "String safeEmergentReason = SafeRedactor.traceLabelOrFallback(risk.emergentAdjustment().reason(), \"unknown\");",
            "putIfChanged(md, \"resource.riskEmergentReason\", safeEmergentReason)",
            "TraceStore.put(\"resource.riskEmergentReason\", safeEmergentReason);",
            "TraceStore.put(\"ml.risk.emergent.reason\", safeEmergentReason);",
            "\"reason\", safeEmergentReason",
            "metadata.put(\"branch_quality_reason\", SafeRedactor.traceLabelOrFallback(branchMetric.reason(), \"unknown\"));");
    }
