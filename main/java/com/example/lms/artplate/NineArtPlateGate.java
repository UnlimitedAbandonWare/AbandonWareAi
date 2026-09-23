package com.example.lms.artplate;

import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.artplate.sse.SseScoreCard;
import com.example.lms.artplate.sse.SseSessionState;
import com.example.lms.artplate.sse.StochasticTransformerEvolver;
import com.example.lms.moe.RgbStrategySelector;
import com.example.lms.llm.ModelCapabilities;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;




@Component
public class NineArtPlateGate {

    private static final Logger log = LoggerFactory.getLogger(NineArtPlateGate.class);

    private final ArtPlateRegistry reg;
    private final ArtPlateEvolver evolver;
    private final RawMatrixBuffer rawMatrixBuffer;
    private volatile ArtPlateSpec lastSelected;
    @Autowired(required = false)
    private StochasticTransformerEvolver sseEvolver;
    private final ConcurrentMap<String, AtomicReference<SseRuntimeState>> sseRuntimeStates =
            new ConcurrentHashMap<>();

    @Autowired
    public NineArtPlateGate(ArtPlateRegistry reg, ArtPlateEvolver evolver, RawMatrixBuffer rawMatrixBuffer) {
        this.reg = reg;
        this.evolver = evolver == null ? new ArtPlateEvolver() : evolver;
        this.rawMatrixBuffer = rawMatrixBuffer;
    }

    public NineArtPlateGate(ArtPlateRegistry reg, ArtPlateEvolver evolver) {
        this(reg, evolver, null);
    }

    public NineArtPlateGate(ArtPlateRegistry reg) {
        this(reg, new ArtPlateEvolver());
    }

    /**
     * Decide plate based on a light-weight PlateContext derived from the current request/session.
     * Keep logic simple; evolver can override via A/B.
     */
    public ArtPlateSpec decide(PlateContext ctx) {
        ArtPlateSpec base = moeStrategyBasePlate();
        if (base == null) {
            base = baseDecision(ctx);
        }
        Candidate scored = bestCandidate(ctx, base);
        ArtPlateSpec candidate = proposeCandidate(scored.plate(), scored.scoreCard());
        ArtPlateEvolver.RolloutDecision rollout;
        ArtPlateSpec selected;
        try {
            rollout = evolver.abTest(candidate, scored.scoreCard());
            selected = evolver.shouldRouteToCandidate(rollout, rolloutKey(ctx, base, scored.plate()))
                    ? rollout.candidate()
                    : base;
        } catch (RuntimeException ex) {
            TraceStore.put("moe.evolver.error", ex == null ? "unknown" : ex.getClass().getSimpleName());
            log.debug("[NineArtPlateGate] Art plate rollout fail-soft. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
            rollout = new ArtPlateEvolver.RolloutDecision(candidate, 0.0d, 0, false, "evolver_error");
            selected = base != null ? base : (scored.plate() != null ? scored.plate() : emergencyCostSaverPlate());
        }
        lastSelected = selected;
        traceSelection(ctx, base, candidate, selected, rollout, scored.scoreCard(), scored.evaluatedCount());
        return selected;
    }

    public ArtPlateSpec getLastSelected() {
        return lastSelected;
    }

    private ArtPlateSpec baseDecision(PlateContext ctx) {
        // Prefer high authority web when evidence is already decent
        if (ctx.authority() > 0.65 && ctx.evidenceCount() >= 2) {
            return plateOrFallback("AP1_AUTH_WEB");
        }
        // Repeated session questions + memory available
        if (ctx.sessionRecur() > 2 && ctx.memoryGate() > 0.5) {
            return plateOrFallback("AP4_MEM_HARVEST");
        }
        // Vector recall need
        if (ctx.vectorGate() > ctx.webGate() && ctx.recallNeed() > 0.6) {
            return plateOrFallback("AP3_VEC_DENSE");
        }
        // If signals indicate noise or low confidence, take safe fallback
        if (ctx.noisy() || ctx.authority() < 0.25) {
            return plateOrFallback("AP7_SAFE_FALLBACK");
        }

            // Heuristic: if web search is explicitly requested and webGate is strong, prefer AP1
            if (ctx.useWeb() && ctx.webGate() >= 0.55 && ctx.recallNeed() <= 0.60) {
                return plateOrFallback("AP1_AUTH_WEB");
            }

        // Default to a frugal plate
        return plateOrFallback("AP9_COST_SAVER");
    }

    private ArtPlateSpec plateOrFallback(String id) {
        Optional<ArtPlateSpec> plate = reg.get(id);
        if (plate.isPresent()) {
            return plate.get();
        }

        ArtPlateSpec fallback = reg.get("AP7_SAFE_FALLBACK")
                .or(() -> reg.get("AP9_COST_SAVER"))
                .or(() -> reg.all().stream().filter(java.util.Objects::nonNull).findFirst())
                .orElseGet(NineArtPlateGate::emergencyCostSaverPlate);
        traceMissingPlateFallback(id, fallback);
        return fallback;
    }

    private static ArtPlateSpec emergencyCostSaverPlate() {
        return new ArtPlateSpec(
                "AP9_COST_SAVER", "chat",
                2, 3, false, false,
                500, 800,
                List.of(),
                0.15, 0.40,
                true, false, false,
                List.of(ModelCapabilities.DEFAULT_LOCAL_FAST_MODEL),
                false, 1, 1,
                0.5, 0.3, 0.7, 0.3);
    }

    private void traceMissingPlateFallback(String id, ArtPlateSpec fallback) {
        String safeId = safeLabel(id, "unknown");
        String safeFallback = safePlateId(fallback);
        TraceStore.put("artplate.selector.fallbackReason", "missing_plate:" + safeId);
        TraceStore.put("artplate.selector.fallbackTarget", safeFallback);
        log.warn("[NineArtPlateGate] Missing art plate {}, using fallback {}.", safeId, safeFallback);
    }

    private ArtPlateSpec moeStrategyBasePlate() {
        Object raw = TraceStore.get("moe.strategy.primary");
        if (raw == null) {
            return null;
        }
        try {
            RgbStrategySelector.Strategy strategy = RgbStrategySelector.Strategy.valueOf(
                    String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT));
            String alias = safeLabel(strategy.artPlateAlias(), "");
            TraceStore.put("artplate.gate.moeStrategy.alias", alias);
            return reg.get(alias).orElse(null);
        } catch (IllegalArgumentException ex) {
            TraceStore.put("artplate.gate.moeStrategy.error", "bridge_fail_soft");
            log.debug("[NineArtPlateGate] MoE bridge fail-soft. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
            return null;
        }
    }

    private ArtPlateEvolver.ScoreCard scoreCard(PlateContext ctx, ArtPlateSpec candidate) {
        if (ctx == null) {
            return ArtPlateEvolver.ScoreCard.neutral();
        }
        double authority = ctx.authority();
        double novelty = Math.max(ctx.recallNeed(), Math.max(ctx.webGate(), ctx.vectorGate()));
        double fusionDiversity = Math.max(ctx.webGate(), Math.max(ctx.vectorGate(), ctx.memoryGate()));
        double match = plateMatch(ctx, candidate);
        double latencyPenalty = candidate == null
                ? 0.10d
                : ((double) Math.max(0, candidate.webBudgetMs() + candidate.vecBudgetMs())) / 8_000.0d;
        double errorPenalty = ctx.noisy() ? 0.45d : (authority < 0.25d ? 0.20d : 0.03d);
        int samples = Math.max(0, ctx.evidenceCount() * 5 + ctx.sessionRecur());
        return new ArtPlateEvolver.ScoreCard(
                authority,
                novelty,
                fusionDiversity,
                match,
                latencyPenalty,
                errorPenalty,
                samples);
    }

    private Candidate bestCandidate(PlateContext ctx, ArtPlateSpec base) {
        ArtPlateEvolver.ScoreCard baseCard = scoreCard(ctx, base);
        Candidate best = new Candidate(base, baseCard, 0);
        int evaluatedCount = 0;
        for (ArtPlateSpec candidate : reg.all()) {
            if (candidate == null) {
                continue;
            }
            evaluatedCount++;
            ArtPlateEvolver.ScoreCard card = scoreCard(ctx, candidate);
            if (card.composite() > best.scoreCard().composite() + 0.0001d) {
                best = new Candidate(candidate, card, evaluatedCount);
            }
        }
        return new Candidate(best.plate(), best.scoreCard(), evaluatedCount);
    }

    private ArtPlateSpec proposeCandidate(ArtPlateSpec baseCandidate, ArtPlateEvolver.ScoreCard scoreCard) {
        ArtPlateSpec candidate = baseCandidate;
        try {
            String stateKey = sseStateKey(baseCandidate);
            AtomicReference<SseRuntimeState> runtimeRef = sseRuntimeStates.computeIfAbsent(
                    stateKey,
                    ignored -> new AtomicReference<>(SseRuntimeState.initial()));
            SseRuntimeState currentRuntime = runtimeRef.get();
            if (currentRuntime == null) {
                currentRuntime = SseRuntimeState.initial();
            }
            SseSessionState currentState = currentRuntime.sessionState() == null
                    ? SseSessionState.initial()
                    : currentRuntime.sessionState();
            ArtPlateEvolver.ScoreCard safeScoreCard =
                    scoreCard == null ? ArtPlateEvolver.ScoreCard.neutral() : scoreCard;
            double currentScore = safeScoreCard.composite();
            SseScoreCard sseScoreCard = new SseScoreCard(
                    currentRuntime.lastScore(),
                    currentScore,
                    currentState.consecutivePenalty(),
                    currentState.iteration());
            ArtPlateEvolver.SseProposal proposed = evolver.proposeWithSse(
                    proposalBucket(baseCandidate),
                    baseCandidate,
                    sseEvolver,
                    currentState,
                    sseScoreCard);
            if (proposed != null && proposed.candidate().isPresent()) {
                candidate = proposed.candidate().get();
            }
            SseSessionState nextState = proposed != null && proposed.nextState() != null
                    ? proposed.nextState()
                    : currentState;
            runtimeRef.set(new SseRuntimeState(nextState, currentScore));
            TraceStore.put("sse.stateScope", "plate");
            TraceStore.put("sse.stateKey", stateKey);
        } catch (RuntimeException ex) {
            TraceStore.put("moe.evolver.error", ex == null ? "unknown" : ex.getClass().getSimpleName());
            TraceStore.put("artplate.propose.skipReason", "exception_fail_soft");
            log.debug("[NineArtPlateGate] Art plate propose fail-soft. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
        }
        TraceStore.put("artplate.propose.mutated", candidate != baseCandidate);
        return candidate;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    private ArtPlateEvolver.PlateFailureBucket proposalBucket(ArtPlateSpec baseCandidate) {
        ArtPlateEvolver.PlateFailureBucket cfvmBucket = ArtPlateCfvmBucketMapper.detect(rawMatrixBuffer);
        if (cfvmBucket != null) {
            return cfvmBucket;
        }
        if (baseCandidate == null) {
            return ArtPlateEvolver.PlateFailureBucket.NO_EVIDENCE;
        }
        String id = baseCandidate.id();
        if ("AP7_SAFE_FALLBACK".equals(id)) {
            return ArtPlateEvolver.PlateFailureBucket.LOW_AUTHORITY;
        }
        return ArtPlateEvolver.PlateFailureBucket.NO_EVIDENCE;
    }

    private static double plateMatch(PlateContext ctx, ArtPlateSpec candidate) {
        if (ctx == null || candidate == null) {
            return 0.50d;
        }
        return switch (candidate.id()) {
            case "AP1_AUTH_WEB" -> ctx.useWeb() && ctx.authority() >= candidate.authorityFloor() ? 0.95d : 0.55d;
            case "AP2_FRESH_WEB" -> ctx.useWeb() && ctx.webGate() >= 0.70d && ctx.recallNeed() >= 0.65d ? 0.90d : 0.55d;
            case "AP3_VEC_DENSE" -> ctx.useRag() && ctx.vectorGate() >= ctx.webGate() ? 0.85d : 0.55d;
            case "AP4_MEM_HARVEST" -> ctx.memoryGate() > 0.50d ? 0.85d : 0.50d;
            case "AP5_KG_REASON" -> ctx.useRag()
                    && candidate.kgOn()
                    && ctx.vectorGate() >= 0.70d
                    && ctx.recallNeed() >= 0.70d
                    && ctx.webGate() < 0.65d ? 0.94d : 0.58d;
            case "AP6_LONG_POLISH" -> ctx.evidenceCount() >= 4
                    && ctx.sessionRecur() >= 3
                    && ctx.recallNeed() >= 0.60d
                    && !ctx.noisy() ? 0.90d : 0.52d;
            case "AP7_SAFE_FALLBACK" -> ctx.noisy() || ctx.authority() < 0.25d ? 0.65d : 0.45d;
            case "AP8_CONTRA_FACT" -> ctx.useWeb()
                    && ctx.useRag()
                    && ctx.webGate() >= 0.65d
                    && ctx.vectorGate() >= 0.60d
                    && ctx.recallNeed() >= 0.75d ? 0.93d : 0.55d;
            case "AP10_TRAIN_RAG" -> ctx.useRag()
                    && !ctx.useWeb()
                    && ctx.vectorGate() >= 0.70d
                    && ctx.memoryGate() >= 0.55d
                    && ctx.recallNeed() >= 0.75d
                    && ctx.evidenceCount() <= 2 ? 0.98d : 0.56d;
            case "AP9_COST_SAVER" -> ctx.recallNeed() <= 0.55d && !ctx.noisy() ? 0.75d : 0.45d;
            default -> 0.60d;
        };
    }

    private static void traceSelection(
            PlateContext ctx,
            ArtPlateSpec base,
            ArtPlateSpec candidate,
            ArtPlateSpec selected,
            ArtPlateEvolver.RolloutDecision rollout,
            ArtPlateEvolver.ScoreCard scoreCard,
            int evaluatedCount) {
        double score = scoreCard == null ? 0.0d : scoreCard.composite();
        String selectedId = safePlateId(selected);
        TraceStore.put("artplate.selector.base", safePlateId(base));
        TraceStore.put("artplate.selector.candidate", safePlateId(candidate));
        TraceStore.put("artplate.selector.selected", selectedId);
        TraceStore.put("artplate.selector.score", score);
        TraceStore.put("artplate.selected.id", selectedId);
        TraceStore.put("artplate.selected.score", score);
        TraceStore.put("artplate.gate.evaluated", Math.max(0, evaluatedCount));
        TraceStore.put("artplate.gate.skipReason", selectedId.isBlank() ? "no_plate_selected" : "");
        TraceStore.put("artplate.selector.rollout.percent", rollout == null ? 0 : rollout.rolloutPercent());
        TraceStore.put("artplate.selector.rollout.reason",
                safeLabel(rollout == null ? "missing_rollout" : rollout.reason(), "unknown"));
        TraceStore.put("artplate.selector.rollout.promote", rollout != null && rollout.promote());
        String moeAbSlot = abSlot(selected, rollout);
        TraceStore.put("moe.selectedPlate", safePlateId(selected));
        TraceStore.put("moe.evolverCandidatePlateId", safePlateId(candidate));
        TraceStore.put("moe.gate.plateRegistered", !selectedId.isBlank());
        TraceStore.put("moe.abSlot", moeAbSlot);
        TraceStore.put("moe.evolver.abSlot", moeAbSlot);
        TraceStore.put("moe.signalVector", signalVector(ctx));
    }

    private static String abSlot(ArtPlateSpec selected, ArtPlateEvolver.RolloutDecision rollout) {
        if (rollout == null) {
            return "unknown";
        }
        return safePlateId(selected).equals(safePlateId(rollout.candidate())) ? "experiment" : "control";
    }

    private static Map<String, Object> signalVector(PlateContext ctx) {
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put("present", ctx != null);
        if (ctx == null) {
            return signal;
        }
        signal.put("useWeb", ctx.useWeb());
        signal.put("useRag", ctx.useRag());
        signal.put("sessionRecur", Math.max(0, ctx.sessionRecur()));
        signal.put("evidenceCount", Math.max(0, ctx.evidenceCount()));
        signal.put("authority", boundedSignal(ctx.authority()));
        signal.put("noisy", ctx.noisy());
        signal.put("webGate", boundedSignal(ctx.webGate()));
        signal.put("vectorGate", boundedSignal(ctx.vectorGate()));
        signal.put("memoryGate", boundedSignal(ctx.memoryGate()));
        signal.put("recallNeed", boundedSignal(ctx.recallNeed()));
        return signal;
    }

    private static double boundedSignal(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    public void apply(Object chain, ArtPlateSpec p) {
        if (chain instanceof PlateTarget target) {
            apply(target, p);
            return;
        }
        if (chain != null && p != null) {
            log.warn("[NineArtPlateGate] Plate {} NOT applied to {} (target does not implement PlateTarget).",
                    safePlateId(p), chain.getClass().getSimpleName());
        }
    }

    public void apply(PlateTarget target, ArtPlateSpec p) {
        if (target == null || p == null) return;
        target.setWebTopK(p.webTopK());
        target.setVectorTopK(p.vecTopK());
        target.setBudgetsMs(p.webBudgetMs(), p.vecBudgetMs());
        target.enableMemory(p.allowMemory());
        target.enableKg(p.kgOn());
        target.setDomainAllow(p.domainAllow());
        target.setMinEvidence(p.minEvidence(), p.minDistinctSources());
        target.enableCrossEncoder(p.crossEncoderOn());
        log.debug("[NineArtPlateGate] Applied {} to {} (topK web={}, vec={}, budget web={}ms, vec={}ms)",
                safePlateId(p), target.getClass().getSimpleName(), p.webTopK(), p.vecTopK(), p.webBudgetMs(), p.vecBudgetMs());
    }

    private static String safePlateId(ArtPlateSpec plate) {
        return safeLabel(plate == null ? "" : plate.id(), "");
    }

    private static String rolloutKey(PlateContext ctx, ArtPlateSpec base, ArtPlateSpec candidate) {
        return String.join("|",
                safePlateId(base),
                safePlateId(candidate),
                ctx == null ? "0" : String.valueOf(Math.max(0, ctx.sessionRecur())),
                ctx == null ? "0" : String.valueOf(Math.max(0, ctx.evidenceCount())),
                bucket(ctx == null ? 0.0d : ctx.authority()),
                bucket(ctx == null ? 0.0d : ctx.webGate()),
                bucket(ctx == null ? 0.0d : ctx.vectorGate()),
                bucket(ctx == null ? 0.0d : ctx.memoryGate()),
                bucket(ctx == null ? 0.0d : ctx.recallNeed()),
                String.valueOf(ctx != null && ctx.noisy()));
    }

    private static String bucket(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        int pct = (int) Math.round(Math.max(0.0d, Math.min(1.0d, value)) * 100.0d);
        return String.valueOf(pct);
    }

    private static String sseStateKey(ArtPlateSpec baseCandidate) {
        String plateId = safePlateId(baseCandidate);
        return plateId.isBlank() ? "AP_UNKNOWN" : plateId;
    }

    private static String safeLabel(String value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }

    public interface PlateTarget {
        void setWebTopK(int topK);
        void setVectorTopK(int topK);
        void setBudgetsMs(int webBudgetMs, int vecBudgetMs);
        void enableMemory(boolean enabled);
        void enableKg(boolean enabled);
        void setDomainAllow(List<String> domains);
        void setMinEvidence(int minEvidence, int minDistinctSources);
        void enableCrossEncoder(boolean enabled);
    }

    private record Candidate(ArtPlateSpec plate, ArtPlateEvolver.ScoreCard scoreCard, int evaluatedCount) {
    }

    private record SseRuntimeState(SseSessionState sessionState, double lastScore) {
        private static SseRuntimeState initial() {
            return new SseRuntimeState(SseSessionState.initial(), 0.0d);
        }
    }
}
