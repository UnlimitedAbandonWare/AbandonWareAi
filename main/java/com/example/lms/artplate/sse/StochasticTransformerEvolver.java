package com.example.lms.artplate.sse;

import com.example.lms.artplate.ArtPlateSpec;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class StochasticTransformerEvolver {

    private static final Logger log = LoggerFactory.getLogger(StochasticTransformerEvolver.class);

    private final StochasticEvolverConfig config;

    public StochasticTransformerEvolver(StochasticEvolverConfig config) {
        this.config = config == null ? StochasticEvolverConfig.defaultConfig() : config;
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    public SseResult perturb(ArtPlateSpec base, SseSessionState state, SseScoreCard scoreCard) {
        SseSessionState safeState = state == null ? SseSessionState.initial() : state;
        if (!config.enabled()) {
            TraceStore.put("sse.enabled", false);
            return new SseResult(base, safeState);
        }
        if (base == null) {
            TraceStore.put("sse.enabled", true);
            TraceStore.put("sse.skipReason", "null_base");
            return new SseResult(null, safeState);
        }
        SseScoreCard card = scoreCard == null
                ? new SseScoreCard(safeState.lastScore(), safeState.lastScore(), safeState.consecutivePenalty(),
                safeState.iteration())
                : scoreCard;
        try {
            return doPerturb(base, safeState, card);
        } catch (RuntimeException ex) {
            TraceStore.put("sse.bypassReason", "exception_fail_soft");
            TraceStore.put("sse.bypassErrorHash", SafeRedactor.hashValue(messageOf(ex)));
            log.debug("[SSE] fail-soft errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
            return new SseResult(base, safeState);
        }
    }

    private SseResult doPerturb(ArtPlateSpec base, SseSessionState state, SseScoreCard card) {
        if (resetLimitExceeded(state)) {
            TraceStore.put("sse.enabled", true);
            TraceStore.put("sse.source", "fallback_to_deterministic");
            TraceStore.put("sse.bypassReason", "max_reset_exceeded");
            TraceStore.put("sse.consecutivePenalty", state.consecutivePenalty());
            TraceStore.put("sse.resetCount", state.consecutivePenalty());
            TraceStore.put("sse.guard.accepted", false);
            return new SseResult(base, SseSessionState.initial());
        }
        SsePhase phase = resolvePhase(state);
        double lr = effectiveLearningRate(card);
        double step = computeStep(state, phase, lr);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double directionSign = phase == SsePhase.RESET ? 1.0d : direction(state, card, rng);
        ArtPlateSpec mutated = phase == SsePhase.RESET
                ? resetSpec(base)
                : perturbSpec(base, phase, step, directionSign, rng);

        int nextPenalty = card.isLowPenalty(config.lowPenaltyThreshold())
                ? state.consecutivePenalty() + 1
                : 0;
        SseSessionState nextState = new SseSessionState(
                state.iteration() + 1,
                card.currentScore(),
                nextPenalty,
                phase,
                directionSign);
        traceResult(base, mutated, phase, step, lr, card, nextState,
                card.isHighReward(config.highRewardThreshold()),
                card.isLowPenalty(config.lowPenaltyThreshold()));
        return new SseResult(mutated, nextState);
    }

    private SsePhase resolvePhase(SseSessionState state) {
        if (resetAllowed(state)) {
            return SsePhase.RESET;
        }
        if (state.iteration() >= config.stagnationThreshold()) {
            return SsePhase.EXPLORE;
        }
        return SsePhase.EXPLOIT;
    }

    private boolean resetAllowed(SseSessionState state) {
        if (state.consecutivePenalty() < 2) {
            return false;
        }
        int resetAttempt = state.consecutivePenalty() - 1;
        return resetAttempt <= config.maxResetCount();
    }

    private boolean resetLimitExceeded(SseSessionState state) {
        if (state.consecutivePenalty() < 2) {
            return false;
        }
        int resetAttempt = state.consecutivePenalty() - 1;
        return resetAttempt > config.maxResetCount();
    }

    private double effectiveLearningRate(SseScoreCard card) {
        if (card.isHighReward(config.highRewardThreshold())) {
            return clamp(config.learningRate() * config.lrBoostFactor(), 0.0d, 1.0d);
        }
        if (card.isLowPenalty(config.lowPenaltyThreshold())) {
            return clamp(config.learningRate() * config.lrDecayFactor(), 0.0d, 1.0d);
        }
        return clamp(config.learningRate(), 0.0d, 1.0d);
    }

    private double computeStep(SseSessionState state, SsePhase phase, double lr) {
        if (phase == SsePhase.RESET) {
            return 0.0d;
        }
        double expansion = 1.0d + Math.sqrt(Math.max(0, state.iteration())) * config.noiseScale();
        if (phase == SsePhase.EXPLOIT) {
            expansion = 1.0d + 0.2d * Math.sqrt(Math.max(0, state.iteration())) * config.noiseScale();
        }
        return clamp(lr * expansion, 0.0d, 2.0d);
    }

    private ArtPlateSpec perturbSpec(
            ArtPlateSpec base,
            SsePhase phase,
            double step,
            double direction,
            ThreadLocalRandom rng) {
        double noiseScale = phase == SsePhase.EXPLORE ? config.noiseScale() : config.noiseScale() * 0.3d;
        double noise = Math.abs(gaussianNoise(rng, noiseScale));
        double signedStep = direction * Math.max(step, noise);

        return new ArtPlateSpec(
                safeId(base, phase),
                base.intent(),
                clampInt(base.webTopK() + intDelta(signedStep, 1), 1, 16),
                clampInt(base.vecTopK() + intDelta(signedStep, 2), 1, 32),
                base.allowMemory(),
                base.kgOn(),
                clampInt(base.webBudgetMs() + (int) Math.round(signedStep * 200.0d), 300, 5000),
                clampInt(base.vecBudgetMs() + (int) Math.round(signedStep * 200.0d), 300, 5000),
                base.domainAllow(),
                clamp(base.noveltyFloor() + signedStep * 0.05d, 0.10d, 0.80d),
                clamp(base.authorityFloor() + signedStep * 0.05d, 0.20d, 0.90d),
                base.includeHistory(),
                base.includeDraft(),
                base.includePrevAnswer(),
                base.modelCandidates(),
                base.crossEncoderOn(),
                base.minEvidence(),
                base.minDistinctSources(),
                clamp(base.wAuthority() + signedStep * 0.03d, 0.10d, 0.60d),
                clamp(base.wNovelty() + signedStep * 0.03d, 0.10d, 0.50d),
                clamp(base.wFd() + signedStep * 0.03d, 0.10d, 0.50d),
                clamp(base.wMatch() + signedStep * 0.03d, 0.10d, 0.60d));
    }

    private ArtPlateSpec resetSpec(ArtPlateSpec base) {
        return new ArtPlateSpec(
                safeId(base, SsePhase.RESET),
                base.intent(),
                clampInt(base.webTopK(), 1, 16),
                clampInt(base.vecTopK(), 1, 32),
                base.allowMemory(),
                base.kgOn(),
                clampInt(base.webBudgetMs(), 300, 5000),
                clampInt(base.vecBudgetMs(), 300, 5000),
                base.domainAllow(),
                clamp(base.noveltyFloor(), 0.10d, 0.80d),
                clamp(base.authorityFloor(), 0.20d, 0.90d),
                base.includeHistory(),
                base.includeDraft(),
                base.includePrevAnswer(),
                base.modelCandidates(),
                base.crossEncoderOn(),
                base.minEvidence(),
                base.minDistinctSources(),
                clamp(base.wAuthority(), 0.10d, 0.60d),
                clamp(base.wNovelty(), 0.10d, 0.50d),
                clamp(base.wFd(), 0.10d, 0.50d),
                clamp(base.wMatch(), 0.10d, 0.60d));
    }

    private double direction(SseSessionState state, SseScoreCard card, ThreadLocalRandom rng) {
        double previousDirection = normalizeDirection(state == null ? 1.0d : state.directionSign());
        if (card.isHighReward(config.highRewardThreshold())) {
            return previousDirection;
        }
        if (card.isLowPenalty(config.lowPenaltyThreshold())) {
            return -previousDirection;
        }
        if (card.delta() > 0.0d) {
            return previousDirection;
        }
        return rng.nextBoolean() ? 1.0d : -1.0d;
    }

    private static int intDelta(double signedStep, int unit) {
        if (signedStep == 0.0d) {
            return 0;
        }
        int magnitude = Math.max(1, (int) Math.round(Math.abs(signedStep) * 10.0d * Math.max(1, unit)));
        return signedStep > 0.0d ? magnitude : -magnitude;
    }

    private static double gaussianNoise(ThreadLocalRandom rng, double scale) {
        double sum = 0.0d;
        for (int i = 0; i < 6; i++) {
            sum += rng.nextDouble();
        }
        return (sum - 3.0d) * scale;
    }

    private static void traceResult(
            ArtPlateSpec base,
            ArtPlateSpec mutated,
            SsePhase phase,
            double step,
            double lr,
            SseScoreCard card,
            SseSessionState nextState,
            boolean highReward,
            boolean lowPenalty) {
        TraceStore.put("sse.enabled", true);
        TraceStore.put("sse.source", "sse_block");
        TraceStore.put("sse.phase", phase.name());
        TraceStore.put("sse.iteration", nextState.iteration());
        TraceStore.put("sse.step", rounded(step));
        TraceStore.put("sse.lr", rounded(lr));
        TraceStore.put("sse.delta", rounded(card.delta()));
        TraceStore.put("sse.consecutivePenalty", nextState.consecutivePenalty());
        TraceStore.put("sse.resetCount", phase == SsePhase.RESET ? nextState.consecutivePenalty() : 0);
        TraceStore.put("sse.directionSign", nextState.directionSign());
        TraceStore.put("sse.basePlateId", SafeRedactor.traceLabelOrFallback(base == null ? "" : base.id(), ""));
        TraceStore.put("sse.mutatedPlateId", SafeRedactor.traceLabelOrFallback(mutated == null ? "" : mutated.id(), ""));
        TraceStore.put("sse.highReward", highReward);
        TraceStore.put("sse.lowPenalty", lowPenalty);
        TraceStore.put("sse.guard.accepted", true);
    }

    private static String safeId(ArtPlateSpec base, SsePhase phase) {
        String baseId = SafeRedactor.traceLabelOrFallback(base == null ? "" : base.id(), "AP_UNKNOWN");
        return baseId + "_SSE_" + phase.name();
    }

    private static double rounded(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static double clamp(double value, double low, double high) {
        if (!Double.isFinite(value)) {
            return low;
        }
        return Math.max(low, Math.min(high, value));
    }

    private static int clampInt(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static double normalizeDirection(double value) {
        if (!Double.isFinite(value) || value == 0.0d) {
            return 1.0d;
        }
        return value < 0.0d ? -1.0d : 1.0d;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }

    public record SseResult(ArtPlateSpec mutatedSpec, SseSessionState nextState) {
    }
}
