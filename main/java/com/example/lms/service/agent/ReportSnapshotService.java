package com.example.lms.service.agent;

import com.example.lms.artplate.ArtPlateSpec;
import com.example.lms.artplate.NineArtPlateGate;
import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.cfvm.RawSlotExtractor;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.dto.agent.CfvmSnapshotDto;
import com.example.lms.dto.agent.GatesSummaryDto;
import com.example.lms.dto.agent.HypernovaFusionDto;
import com.example.lms.dto.agent.MoeDecisionDto;
import com.example.lms.dto.agent.OverdriveStatusDto;
import com.example.lms.dto.agent.ReasonDto;
import com.example.lms.dto.agent.ScoreEventDto;
import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.llm.LowRankWhiteningTransform;
import com.example.lms.moe.RgbStrategySelector;
import com.example.lms.search.TraceStore;
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.guard.CitationGate;
import com.example.lms.service.rag.fusion.WeightedPowerMeanFuser;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ReportSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(ReportSnapshotService.class);

    private static final List<String> CFVM_TRACE_KEYS = List.of(
            "extremez.risk.primaryCause",
            "extremez.activation.reason",
            "learning.validation.decision",
            "learning.error.hotspot",
            "cfvm.boltzmannTemp",
            "cfvm.tempSource",
            "cfvm.snapshot.saved",
            "cfvm.snapshot.vectorWrite.enabled",
            "cfvm.snapshot.vectorWrite.skipped",
            "cfvm.failureRecovery.triggered",
            "cfvm.failureRecovery.failureClass",
            "cfvm.failureRecovery.failureWeight",
            "cfvm.failureRecovery.cancelTrueDowngraded",
            "cfvm.failureRecovery.timeoutCondition",
            "cfvm.failureRecovery.boltzmannWeight",
            "cfvm.failureRecovery.retrievalOrderAdjusted",
            "cfvm.failureRecovery.snapshot.saved",
            "cfvm.rawBuffer.weightMode",
            "cfvm.kalloc.recovery.applied");

    private static final List<String> CIH_RAG_TRACE_KEYS = List.of(
            "cihRag.activeFileCount",
            "cihRag.skippedFileCount",
            "cihRag.iqrIterations",
            "cihRag.iqrDisabledReason",
            "cihRag.biEncoderApplied",
            "cihRag.biEncoderDisabledReason",
            "cihRag.onnxRerankApplied",
            "cihRag.onnxRerankDisabledReason",
            "cihRag.dppApplied",
            "cihRag.dppDisabledReason",
            "cihRag.mlaBreadcrumbCount",
            "cihRag.breadcrumb.queryRedacted",
            "cihRag.implementationStage");

    private static final List<String> MATRYOSHKA_TRACE_KEYS = List.of(
            "embed.matryoshka.slice.actual",
            "embed.matryoshka.slice.target",
            "embed.matryoshka.slice.reductionRatio",
            "embed.matryoshka.slice.expectedDistanceOpsRatio",
            "embed.matryoshka.slice.expectedDistanceOpsSpeedup",
            "embed.matryoshka.rawDim",
            "embed.matryoshka.targetDim",
            "embed.matryoshka.dimensionReduction",
            "embed.matryoshka.strategy",
            "embed.matryoshka.tagHash",
            "embed.matryoshka.tagLength",
            "embed.matryoshka.config.parse.suppressed.stage",
            "embed.matryoshka.config.parse.suppressed.errorType",
            "embed.matryoshka.suppressed.stage");

    private static final List<String> LOCAL_LLM_TRACE_KEYS = List.of(
            "localLlm.startup.enabled",
            "localLlm.startup.autostart",
            "localLlm.startup.status",
            "localLlm.startup.reason",
            "localLlm.startup.hostHash",
            "localLlm.startup.healthUrlHash",
            "localLlm.startup.healthUrlLength",
            "localLlm.startup.warmupTargetDim",
            "localLlm.warmup.enabled",
            "localLlm.warmup.status",
            "localLlm.warmup.modelHash",
            "localLlm.warmup.modelLength",
            "localLlm.warmup.targetDim",
            "localLlm.warmup.returnedDim",
            "localLlm.warmup.reason");

    private static final List<String> MODEL_GUARD_TRACE_KEYS = List.of(
            "llm.modelGuard.triggered",
            "llm.modelGuard.mode",
            "llm.modelGuard.endpoint",
            "llm.modelGuard.failReason",
            "llm.modelGuard.requestedModelHash",
            "llm.modelGuard.requestedModelLength",
            "llm.modelGuard.substituteChatModelHash",
            "llm.modelGuard.substituteChatModelLength");

    private static final List<String> HYPERNOVA_TRACE_KEYS = List.of(
            "hypernova.twpmP",
            "hypernova.twpmP.max",
            "hypernova.twpmP.maxBounded",
            "hypernova.cvarFusedScore",
            "hypernova.cvarAlpha",
            "hypernova.cvarPhi",
            "hypernova.clampApplied",
            "hypernova.dppApplied",
            "hypernova.dppInputCount",
            "hypernova.dppOutputCount",
            "hypernova.dppDisabledReason",
            "hypernova.sourceScoreScaleMismatchCount",
            "hypernova.sourceScoreScaleMismatchPolicy",
            "hypernova.whitening.applied",
            "hypernova.whitening.method",
            "hypernova.whitening.provider",
            "hypernova.whitening.disabledReason",
            "nova.hypernova.riskK.used",
            "nova.hypernova.riskK.candidateCount",
            "nova.hypernova.riskK.totalK",
            "nova.hypernova.riskK.alloc.sum",
            "hypernova.riskKAlloc");

    private static final List<String> ROUTING_PLAN_TRACE_KEYS = List.of(
            "routing.executionPlan.primaryMode",
            "routing.executionPlan.triggers",
            "routing.executionPlan.extremeZ",
            "routing.executionPlan.overdrive",
            "routing.executionPlan.hypernova",
            "routing.executionPlan.applied",
            "routing.executionPlan.applied.primaryMode",
            "routing.executionPlan.applied.triggers",
            "routing.executionPlan.applied.stages",
            "routing.executionPlan.applied.extremeZ",
            "routing.executionPlan.applied.overdrive",
            "routing.executionPlan.applied.hypernova",
            "routing.executionPlan.applied.dualGearMode",
            "routing.executionPlan.applied.gachaVariantCount",
            "specialMode.conflict.suppressed",
            "specialMode.priority",
            "specialMode.conflict.extremeZ.suppressed",
            "specialMode.conflict.extremeZ.primaryMode",
            "specialMode.conflict.overdrive.suppressed");

    private static final List<String> RETRIEVAL_ORDER_TRACE_KEYS = List.of(
            "retrievalOrder.lastSetBy",
            "retrievalOrder.lastOrder",
            "retrievalOrder.lastOrderSize",
            "retrievalOrder.authority.owner",
            "retrievalOrder.authority.suppressedOwner",
            "retrievalOrder.authority.reason",
            "retrievalOrder.authority.suppressedReason",
            "retrieval.order.strategy.selected",
            "retrieval.order.strategy.applied",
            "retrieval.order.strategy.failSoft",
            "retrieval.order.strategy.reason",
            "retrieval.order.cfvm.applied",
            "retrieval.order.cfvm.reason",
            "retrieval.order.failpattern.applied",
            "retrieval.order.failpattern.source",
            "retrieval.order.failpattern.reason",
            "retrieval.order.vectorDeprioritized",
            "retrieval.order.vectorQuarantineRatio");

    private final DebugEventStore debugEventStore;
    private final RawMatrixBuffer rawMatrixBuffer;
    private final RgbStrategySelector rgbStrategySelector;
    private final NineArtPlateGate artPlateGate;
    private final OverdriveGuard overdriveGuard;
    private final WeightedPowerMeanFuser weightedPowerMeanFuser;
    private final FinalSigmoidGate finalSigmoidGate;
    private final CitationGate citationGate;
    private final LowRankWhiteningTransform whiteningTransform;
    private final MemoryReinforcementService memoryReinforcementService;
    private final double dppDefaultLambda;
    private final int dppDefaultK;
    private final int citationMinCount;
    private final boolean citationRequireOfficial;

    @Autowired
    public ReportSnapshotService(
            DebugEventStore debugEventStore,
            ObjectProvider<RawMatrixBuffer> rawMatrixBufferProvider,
            ObjectProvider<RgbStrategySelector> rgbStrategySelectorProvider,
            ObjectProvider<NineArtPlateGate> artPlateGateProvider,
            ObjectProvider<OverdriveGuard> overdriveGuardProvider,
            ObjectProvider<WeightedPowerMeanFuser> weightedPowerMeanFuserProvider,
            ObjectProvider<FinalSigmoidGate> finalSigmoidGateProvider,
            ObjectProvider<CitationGate> citationGateProvider,
            ObjectProvider<LowRankWhiteningTransform> whiteningTransformProvider,
            ObjectProvider<MemoryReinforcementService> memoryReinforcementServiceProvider,
            @Value("${selfask.branch-quality.dpp.min-lambda:0.35}") double dppDefaultLambda,
            @Value("${selfask.branch-quality.dpp.pool-max:24}") int dppDefaultK,
            @Value("${guard.citation.min_count:2}") int citationMinCount,
            @Value("${guard.citation.require_official:true}") boolean citationRequireOfficial) {
        this(
                debugEventStore,
                rawMatrixBufferProvider == null ? null : rawMatrixBufferProvider.getIfAvailable(),
                rgbStrategySelectorProvider == null ? null : rgbStrategySelectorProvider.getIfAvailable(),
                artPlateGateProvider == null ? null : artPlateGateProvider.getIfAvailable(),
                overdriveGuardProvider == null ? null : overdriveGuardProvider.getIfAvailable(),
                weightedPowerMeanFuserProvider == null ? null : weightedPowerMeanFuserProvider.getIfAvailable(),
                finalSigmoidGateProvider == null ? null : finalSigmoidGateProvider.getIfAvailable(),
                citationGateProvider == null ? null : citationGateProvider.getIfAvailable(),
                whiteningTransformProvider == null ? null : whiteningTransformProvider.getIfAvailable(),
                memoryReinforcementServiceProvider == null ? null : memoryReinforcementServiceProvider.getIfAvailable(),
                dppDefaultLambda,
                dppDefaultK,
                citationMinCount,
                citationRequireOfficial);
    }

    public ReportSnapshotService(
            DebugEventStore debugEventStore,
            RawMatrixBuffer rawMatrixBuffer,
            RgbStrategySelector rgbStrategySelector,
            NineArtPlateGate artPlateGate,
            OverdriveGuard overdriveGuard,
            WeightedPowerMeanFuser weightedPowerMeanFuser,
            FinalSigmoidGate finalSigmoidGate,
            CitationGate citationGate,
            double dppDefaultLambda,
            int dppDefaultK,
            int citationMinCount,
            boolean citationRequireOfficial) {
        this(debugEventStore, rawMatrixBuffer, rgbStrategySelector, artPlateGate, overdriveGuard,
                weightedPowerMeanFuser, finalSigmoidGate, citationGate, null,
                dppDefaultLambda, dppDefaultK, citationMinCount, citationRequireOfficial);
    }

    public ReportSnapshotService(
            DebugEventStore debugEventStore,
            RawMatrixBuffer rawMatrixBuffer,
            RgbStrategySelector rgbStrategySelector,
            NineArtPlateGate artPlateGate,
            OverdriveGuard overdriveGuard,
            WeightedPowerMeanFuser weightedPowerMeanFuser,
            FinalSigmoidGate finalSigmoidGate,
            CitationGate citationGate,
            LowRankWhiteningTransform whiteningTransform,
            double dppDefaultLambda,
            int dppDefaultK,
            int citationMinCount,
            boolean citationRequireOfficial) {
        this(debugEventStore, rawMatrixBuffer, rgbStrategySelector, artPlateGate, overdriveGuard,
                weightedPowerMeanFuser, finalSigmoidGate, citationGate, whiteningTransform, null,
                dppDefaultLambda, dppDefaultK, citationMinCount, citationRequireOfficial);
    }

    public ReportSnapshotService(
            DebugEventStore debugEventStore,
            RawMatrixBuffer rawMatrixBuffer,
            RgbStrategySelector rgbStrategySelector,
            NineArtPlateGate artPlateGate,
            OverdriveGuard overdriveGuard,
            WeightedPowerMeanFuser weightedPowerMeanFuser,
            FinalSigmoidGate finalSigmoidGate,
            CitationGate citationGate,
            LowRankWhiteningTransform whiteningTransform,
            MemoryReinforcementService memoryReinforcementService,
            double dppDefaultLambda,
            int dppDefaultK,
            int citationMinCount,
            boolean citationRequireOfficial) {
        this.debugEventStore = debugEventStore == null ? new DebugEventStore() : debugEventStore;
        this.rawMatrixBuffer = rawMatrixBuffer;
        this.rgbStrategySelector = rgbStrategySelector;
        this.artPlateGate = artPlateGate;
        this.overdriveGuard = overdriveGuard;
        this.weightedPowerMeanFuser = weightedPowerMeanFuser;
        this.finalSigmoidGate = finalSigmoidGate;
        this.citationGate = citationGate;
        this.whiteningTransform = whiteningTransform;
        this.memoryReinforcementService = memoryReinforcementService;
        this.dppDefaultLambda = dppDefaultLambda;
        this.dppDefaultK = Math.max(1, dppDefaultK);
        this.citationMinCount = Math.max(0, citationMinCount);
        this.citationRequireOfficial = citationRequireOfficial;
    }

    public CfvmSnapshotDto cfvmSnapshot() {
        Map<String, Object> trace = traceSnapshot();
        String signature = SafeRedactor.safeMessage(RawSlotExtractor.signature(trace), 600);
        long patternId = RawSlotExtractor.patternIdFromTrace(trace);
        int bufferSize = rawMatrixBuffer == null ? 0 : rawMatrixBuffer.size();
        CfvmSnapshotDto dto = new CfvmSnapshotDto(patternId, signature, bufferSize, traceSubset(CFVM_TRACE_KEYS));
        emit(DebugProbeType.AGENT_REPORT_CFVM, "agent_report_cfvm_snapshot", "ReportSnapshotService.cfvmSnapshot",
                Map.of("bufferSize", bufferSize, "patternId", patternId));
        return dto;
    }

    public MoeDecisionDto moeDecision() {
        RgbStrategySelector.Decision decision = rgbStrategySelector == null ? null : rgbStrategySelector.getLastDecision();
        RgbStrategySelector.ScoreCard scoreCard = decision == null ? null : decision.scoreCard();
        List<String> fallbacks = decision == null || decision.fallbackStrategies() == null
                ? List.of()
                : decision.fallbackStrategies().stream().filter(Objects::nonNull).map(Enum::name).toList();
        List<ScoreEventDto> events = scoreCard == null || scoreCard.scoreEvents() == null
                ? List.of()
                : scoreCard.scoreEvents().stream()
                        .filter(Objects::nonNull)
                        .map(e -> new ScoreEventDto(safeLabel(e.rule(), "unknown"), e.redDelta(), e.greenDelta(), e.blueDelta()))
                        .toList();
        List<ReasonDto> reasons = decision == null || decision.reasons() == null
                ? List.of()
                : decision.reasons().stream().filter(Objects::nonNull).map(ReportSnapshotService::reasonDto).toList();
        MoeDecisionDto dto = new MoeDecisionDto(
                decision == null || decision.primaryStrategy() == null ? "UNKNOWN" : decision.primaryStrategy().name(),
                fallbacks,
                scoreCard == null ? 0 : scoreCard.redScore(),
                scoreCard == null ? 0 : scoreCard.greenScore(),
                scoreCard == null ? 0 : scoreCard.blueScore(),
                events,
                reasons,
                selectedPlate(),
                toDouble(TraceStore.get("artplate.selector.score"), 0.0d),
                safeLabel(TraceStore.get("artplate.selector.rollout.reason"), "unknown"),
                toInt(TraceStore.get("artplate.selector.rollout.percent"), 0));
        emit(DebugProbeType.AGENT_REPORT_MOE, "agent_report_moe_decision", "ReportSnapshotService.moeDecision",
                Map.of("primaryStrategy", dto.primaryStrategy(), "fallbackCount", dto.fallbackStrategies().size()));
        return dto;
    }

    public Map<String, Object> artplateStatus() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("selectedPlate", selectedPlate());
        out.put("plateScore", toDouble(TraceStore.get("artplate.selector.score"), 0.0d));
        out.put("rolloutReason", safeLabel(TraceStore.get("artplate.selector.rollout.reason"), "unknown"));
        out.put("rolloutPercent", toInt(TraceStore.get("artplate.selector.rollout.percent"), 0));
        out.put("traceKeys", sanitizeMap(TraceStore.getByPrefix("artplate.")));
        emit(DebugProbeType.AGENT_REPORT_MOE, "agent_report_artplate_status", "ReportSnapshotService.artplateStatus",
                Map.of("selectedPlate", out.get("selectedPlate")));
        return out;
    }

    public OverdriveStatusDto overdriveStatus() {
        double fallbackThreshold = overdriveGuard == null ? 0.55d : overdriveGuard.getScoreThreshold();
        OverdriveStatusDto dto = new OverdriveStatusDto(
                toBoolean(TraceStore.get("overdrive.activated"), false),
                toDouble(TraceStore.get("overdrive.score"), 0.0d),
                toDouble(TraceStore.get("overdrive.score.threshold.effective"), fallbackThreshold),
                toBoolean(TraceStore.get("overdrive.aggressive"), false),
                safeLabel(TraceStore.get("overdrive.reason"), "unknown"),
                toInt(TraceStore.get("overdrive.candidates.count"), 0),
                toDouble(TraceStore.get("overdrive.sparse.score"), 0.0d),
                toDouble(TraceStore.get("overdrive.authority.avg"), 0.0d),
                toDouble(TraceStore.get("overdrive.contradiction.mean"), 0.0d),
                toDouble(TraceStore.get("overdrive.error.rate"), 0.0d),
                sanitizeValue("overdrive.blackbox.riskScore", TraceStore.get("overdrive.blackbox.riskScore")),
                safeLabel(TraceStore.get("overdrive.blackbox.restoreAction"), ""));
        emit(DebugProbeType.AGENT_REPORT_OVERDRIVE, "agent_report_overdrive_status",
                "ReportSnapshotService.overdriveStatus",
                Map.of("activated", dto.activated(), "candidateCount", dto.candidateCount(), "reason", dto.reason()));
        return dto;
    }

    public HypernovaFusionDto hypernovaFusion() {
        HypernovaFusionDto dto = new HypernovaFusionDto(
                traceOrFuserWeight("rag.fusion.weights.web", weightedPowerMeanFuser == null ? 1.0d : weightedPowerMeanFuser.getWebWeight()),
                traceOrFuserWeight("rag.fusion.weights.vector", weightedPowerMeanFuser == null ? 0.8d : weightedPowerMeanFuser.getVectorWeight()),
                traceOrFuserWeight("rag.fusion.weights.memory", weightedPowerMeanFuser == null ? 0.6d : weightedPowerMeanFuser.getMemoryWeight()),
                toDouble(TraceStore.get("selfask.branchQuality.dpp.lambda"), dppDefaultLambda),
                toInt(TraceStore.get("selfask.branchQuality.dpp.poolTopK"), dppDefaultK),
                whiteningTransform != null,
                "BiEncoder -> CrossEncoder -> DPP");
        emit(DebugProbeType.AGENT_REPORT_HYPERNOVA, "agent_report_hypernova_fusion",
                "ReportSnapshotService.hypernovaFusion",
                Map.of("webWeight", dto.webWeight(), "vectorWeight", dto.vectorWeight(), "memoryWeight", dto.memoryWeight()));
        return dto;
    }

    public Map<String, Object> extremezStatus() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        long explodeCount = TraceStore.getLong("extremez.explode.count");
        boolean wired = toBoolean(TraceStore.get("extremez.systemHandler.wired"), false);
        boolean triggerActivated = toBoolean(TraceStore.get("extremez.trigger.activate"), false);
        boolean executeActivated = toBoolean(TraceStore.get("extremez.execute.activated"), false);
        boolean hasActiveRuntimeTrace = wired
                || TraceStore.get("extremez.systemHandler.plan.primaryMode") != null
                || TraceStore.get("extremez.trigger.plan.primaryMode") != null
                || TraceStore.get("extremez.execute.subQueryCount") != null;
        out.put("available", true);
        out.put("stubMode", !hasActiveRuntimeTrace && explodeCount == 0L);
        out.put("runtimeMode", hasActiveRuntimeTrace ? "burst-handler" : "legacy-explode");
        out.put("wired", wired);
        out.put("plannerWired", toBoolean(TraceStore.get("extremez.systemHandler.plannerWired"), false));
        out.put("webRetrieverWired", toBoolean(TraceStore.get("extremez.systemHandler.webRetrieverWired"), false));
        out.put("ragServiceWired", toBoolean(TraceStore.get("extremez.systemHandler.ragServiceWired"), false));
        out.put("rrfWired", toBoolean(TraceStore.get("extremez.systemHandler.rrfWired"), false));
        out.put("authorityScorerWired", toBoolean(TraceStore.get("extremez.systemHandler.authorityScorerWired"), false));
        out.put("primaryMode", safeLabel(firstNonNull(
                TraceStore.get("extremez.systemHandler.plan.primaryMode"),
                TraceStore.get("extremez.trigger.plan.primaryMode")), "UNKNOWN"));
        out.put("triggerActivated", triggerActivated);
        out.put("executeActivated", executeActivated);
        out.put("subQueryCount", TraceStore.getLong("extremez.execute.subQueryCount"));
        out.put("rawCount", TraceStore.getLong("extremez.execute.rawCount"));
        out.put("refinedCount", TraceStore.getLong("extremez.execute.refinedCount"));
        out.put("cancelShieldWrapped", toBoolean(TraceStore.get("extremeZ.cancelShieldWrapped"), false));
        out.put("timeBudgetConsumedMs", TraceStore.getLong("extremeZ.timeBudgetConsumedMs"));
        out.put("explodeCount", explodeCount);
        out.put("riskPrimaryCause", safeLabel(TraceStore.get("extremez.risk.primaryCause"), ""));
        out.put("activationReason", safeLabel(firstNonNull(
                TraceStore.get("extremez.activation.reason"),
                TraceStore.get("extremez.systemHandler.plan.reason"),
                TraceStore.get("extremez.trigger.reason")), ""));
        out.put("traceKeys", traceSubset(CFVM_TRACE_KEYS));
        emit(DebugProbeType.AGENT_REPORT_EXTREMEZ, "agent_report_extremez_status",
                "ReportSnapshotService.extremezStatus",
                Map.of("available", true,
                        "stubMode", out.get("stubMode"),
                        "runtimeMode", out.get("runtimeMode"),
                        "primaryMode", out.get("primaryMode"),
                        "triggerActivated", triggerActivated,
                        "executeActivated", executeActivated));
        return out;
    }

    public Map<String, Object> memoryReinforcement() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (memoryReinforcementService == null) {
            out.put("available", false);
            out.put("reason", "memory_reinforcement_not_wired");
            emit(DebugProbeType.AGENT_REPORT_TRACE, "agent_report_memory_reinforcement",
                    "ReportSnapshotService.memoryReinforcement",
                    Map.of("available", false, "reason", out.get("reason")));
            return out;
        }
        out.put("available", true);
        out.putAll(sanitizeMap(memoryReinforcementService.getReinforcementStats()));
        emit(DebugProbeType.AGENT_REPORT_TRACE, "agent_report_memory_reinforcement",
                "ReportSnapshotService.memoryReinforcement",
                Map.of("available", true, "memoryEnabled", out.getOrDefault("memoryEnabled", false)));
        return out;
    }

    public GatesSummaryDto gatesSummary() {
        GatesSummaryDto dto = new GatesSummaryDto(
                finalSigmoidGate == null ? 0.70d : finalSigmoidGate.getThreshold(),
                finalSigmoidGate == null || finalSigmoidGate.getMode() == null ? "UNKNOWN" : finalSigmoidGate.getMode().name(),
                finalSigmoidGate != null && finalSigmoidGate.isAggressiveMode(),
                citationMinCount,
                citationRequireOfficial,
                citationGate != null,
                TraceStore.getLong("gate.pass.count"),
                TraceStore.getLong("gate.block.count"),
                TraceStore.getLong("gate.warn.count"));
        emit(DebugProbeType.AGENT_REPORT_GATES, "agent_report_gates_summary",
                "ReportSnapshotService.gatesSummary",
                Map.of("sigmoidMode", dto.sigmoidMode(), "citationMinCount", dto.citationMinCount()));
        return dto;
    }

    public Map<String, Object> traceKpi() {
        LinkedHashMap<String, Long> byProbe = new LinkedHashMap<>();
        long total = 0L;
        for (DebugProbeType probe : DebugProbeType.values()) {
            if (!probe.name().startsWith("AGENT_REPORT_")) {
                continue;
            }
            long count = debugEventStore.listByProbe(probe, 500).size();
            if (count > 0L) {
                byProbe.put(probe.name(), count);
                total += count;
            }
        }

        Map<String, Object> trace = traceSnapshot();
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("traceKeyCount", trace.size());
        out.put("overdriveKeyCount", TraceStore.getByPrefix("overdrive.").size());
        out.put("webKeyCount", TraceStore.getByPrefix("web.").size());
        out.put("gateKeyCount", TraceStore.getByPrefix("gate.").size());
        out.put("cihRagKeyCount", TraceStore.getByPrefix("cihRag.").size());
        out.put("matryoshkaKeyCount", TraceStore.getByPrefix("embed.matryoshka.").size());
        out.put("localLlmKeyCount", TraceStore.getByPrefix("localLlm.").size());
        out.put("modelGuardKeyCount", TraceStore.getByPrefix("llm.modelGuard.").size());
        out.put("agentReportEventCount", total);
        out.put("agentReportEventsByProbe", byProbe);
        out.put("boosterMode", traceSubset(List.of(
                "boosterMode.active",
                "boosterMode.excludedModes",
                "boosterMode.priority",
                "boosterMode.exclusionReason")));
        out.put("retrievalOrder", traceSubset(RETRIEVAL_ORDER_TRACE_KEYS));
        out.put("routingPlan", traceSubset(ROUTING_PLAN_TRACE_KEYS));
        out.put("hypernovaKeyCount", TraceStore.getByPrefix("hypernova.").size()
                + TraceStore.getByPrefix("nova.hypernova.").size());
        out.put("hypernova", traceSubset(HYPERNOVA_TRACE_KEYS));
        out.put("cihRag", traceSubset(CIH_RAG_TRACE_KEYS));
        out.put("matryoshka", traceSubset(MATRYOSHKA_TRACE_KEYS));
        out.put("localLlm", traceSubset(LOCAL_LLM_TRACE_KEYS));
        out.put("modelGuard", traceSubset(MODEL_GUARD_TRACE_KEYS));
        emit(DebugProbeType.AGENT_REPORT_TRACE, "agent_report_trace_kpi",
                "ReportSnapshotService.traceKpi",
                Map.of("agentReportEventCount", total, "traceKeyCount", trace.size()));
        return out;
    }

    private String selectedPlate() {
        Object traced = TraceStore.get("artplate.selector.selected");
        if (traced != null) {
            return safeLabel(traced, "");
        }
        ArtPlateSpec last = artPlateGate == null ? null : artPlateGate.getLastSelected();
        return last == null ? "" : safeLabel(last.id(), "");
    }

    private static ReasonDto reasonDto(RgbStrategySelector.Reason reason) {
        return new ReasonDto(
                safeLabel(reason.tag(), "unknown"),
                reason.priority(),
                reason.appliesTo() == null ? "UNKNOWN" : reason.appliesTo().name());
    }

    private Map<String, Object> traceSubset(List<String> keys) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> trace = traceSnapshot();
        for (String key : keys) {
            if (trace.containsKey(key)) {
                out.put(key, sanitizeValue(key, trace.get(key)));
            }
        }
        return out;
    }

    private static Map<String, Object> traceSnapshot() {
        return TraceStore.getAll();
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey();
            out.put(sanitizeMapKey(key), sanitizeValue(key, entry.getValue()));
        }
        return out;
    }

    private static String sanitizeMapKey(String key) {
        if (SafeRedactor.isRestrictedKey(key)) {
            return SafeRedactor.hashValue(key);
        }
        return safeLabel(key, "field");
    }

    private static Object sanitizeValue(String key, Object value) {
        if ("boosterMode.exclusionReason".equals(key)) {
            String label = value == null ? null : String.valueOf(value).replace('>', '_');
            return safeLabel(label, "unknown");
        }
        if ("boosterMode.priority".equals(key)
                || "specialMode.conflict.suppressed".equals(key)
                || "specialMode.priority".equals(key)) {
            String label = value == null ? null : String.valueOf(value)
                    .replace('>', '_')
                    .replace(',', '_');
            return safeLabel(label, "unknown");
        }
        if ("boosterMode.active".equals(key)
                || "retrievalOrder.lastSetBy".equals(key)
                || "retrievalOrder.authority.owner".equals(key)
                || "retrievalOrder.authority.suppressedOwner".equals(key)
                || "retrievalOrder.authority.reason".equals(key)
                || "retrievalOrder.authority.suppressedReason".equals(key)
                || "routing.executionPlan.primaryMode".equals(key)
                || "routing.executionPlan.applied.primaryMode".equals(key)
                || "routing.executionPlan.applied.dualGearMode".equals(key)
                || "specialMode.conflict.extremeZ.primaryMode".equals(key)) {
            return safeLabel(value, "unknown");
        }
        if ("boosterMode.excludedModes".equals(key) && value instanceof List<?> list) {
            return list.stream()
                    .map(item -> safeLabel(item, "unknown"))
                    .toList();
        }
        if (("routing.executionPlan.triggers".equals(key)
                || "routing.executionPlan.applied.triggers".equals(key)
                || "routing.executionPlan.applied.stages".equals(key))
                && value instanceof List<?> list) {
            return list.stream()
                    .map(item -> safeLabel(item, "unknown"))
                    .toList();
        }
        if ("retrievalOrder.lastOrder".equals(key) && value instanceof List<?> list) {
            return list.stream()
                    .map(item -> safeLabel(item, "unknown"))
                    .toList();
        }
        if ("retrieval.order.strategy.reason".equals(key)
                || "retrieval.order.cfvm.reason".equals(key)
                || "retrieval.order.failpattern.source".equals(key)
                || "retrieval.order.failpattern.reason".equals(key)) {
            return safeLabel(value, "unknown");
        }
        if ("cfvm.tempSource".equals(key)
                || "cfvm.rawBuffer.weightMode".equals(key)) {
            return safeLabel(value, "unknown");
        }
        if ("cfvm.snapshot.vectorWrite.skipped".equals(key)) {
            return safeLabel(value, "unknown");
        }
        if ("hypernova.dppDisabledReason".equals(key)) {
            return safeOptionalLabel(value);
        }
        if ("hypernova.sourceScoreScaleMismatchPolicy".equals(key)) {
            return safeLabel(value, "unknown");
        }
        if ("cihRag.breadcrumb.queryRedacted".equals(key)) {
            return toBoolean(value, false);
        }
        if ((key.startsWith("cihRag.") && key.endsWith("DisabledReason"))
                || "cihRag.implementationStage".equals(key)
                || "localLlm.startup.status".equals(key)
                || "localLlm.startup.reason".equals(key)
                || "localLlm.warmup.status".equals(key)
                || "localLlm.warmup.reason".equals(key)
                || "embed.matryoshka.dimensionReduction".equals(key)
                || "embed.matryoshka.strategy".equals(key)
                || "embed.matryoshka.config.parse.suppressed.stage".equals(key)
                || "embed.matryoshka.config.parse.suppressed.errorType".equals(key)
                || "embed.matryoshka.suppressed.stage".equals(key)
                || "llm.modelGuard.mode".equals(key)
                || "llm.modelGuard.failReason".equals(key)) {
            return safeLabel(value, "unknown");
        }
        if ("llm.modelGuard.endpoint".equals(key)) {
            return safeEndpointPath(value);
        }
        return SafeRedactor.diagnosticValue(key, value, 500);
    }

    private static String safeLabel(Object value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }

    private static String safeOptionalLabel(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return "";
        }
        return safeLabel(value, "unknown");
    }

    private static String safeEndpointPath(Object value) {
        if (value == null) {
            return "unknown";
        }
        String text = String.valueOf(value).trim();
        if (text.matches("/v1/[A-Za-z0-9_./:-]{1,120}")) {
            return text;
        }
        return safeLabel(value, "unknown");
    }

    private static boolean toBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0d;
        }
        String s = String.valueOf(value).trim();
        if (s.isBlank()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignore) {
            log.debug("[ReportSnapshot] numeric int fallback errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(ignore)), String.valueOf(ignore).length());
            return fallback;
        }
    }

    private static double toDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            double parsed = value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ignore) {
            log.debug("[ReportSnapshot] numeric double fallback errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(ignore)), String.valueOf(ignore).length());
            return fallback;
        }
    }

    private static double traceOrFuserWeight(String key, double fallback) {
        return toDouble(TraceStore.get(key), fallback);
    }

    private void emit(DebugProbeType probe, String fingerprint, String where, Map<String, Object> data) {
        debugEventStore.emit(
                probe,
                DebugEventLevel.INFO,
                fingerprint,
                "Agent report requested",
                where,
                data,
                null);
    }
}
