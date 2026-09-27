package com.example.lms.service.rag.learn;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CFVM(9-tile) -> TopK/KAllocation 자동 튜닝(온라인 밴딧) 구현체.
 *
 * <p>현재 단계는 "학습형 오케스트레이션"으로 가기 위한 뼈대이며:
 * <ul>
 *   <li>Context(쿼리 복잡도/recency/officialOnly/intent)를 9개의 tile로 매핑</li>
 *   <li>각 tile에서 후보 KPlan(arm)들 중 UCB1 + epsilon-greedy로 선택</li>
 *   <li>Fail-soft이며, FailurePatternOrchestrator의 cooldown을 안전 오버라이드로 반영</li>
 * </ul>
 */
@Component
public class CfvmKAllocationTuner {

    private static final Logger log = LoggerFactory.getLogger(CfvmKAllocationTuner.class);

    /** 작은 action-space를 유지해야 온라인 학습이 안정적이다. */
    public enum Arm {
        BASE,
        WEB_HEAVY,
        VECTOR_HEAVY,
        KG_HEAVY,
        COST_SAVER
    }

    public record Decision(
            String policy,
            int tile,
            String key,
            String arm,
            KAllocator.KPlan baseline,
            KAllocator.KPlan plan,
            String ctx,
            double valueScore,
            double optimismScore,
            String resourceTier,
            double optimismDamping,
            String selectionMode
    ) {
    }

    private final CfvmKallocLearningProperties props;
    private final CfvmBanditStore store;
    private final ObjectProvider<RagFailureBlackboxService> blackboxProvider;

    public CfvmKAllocationTuner(CfvmKallocLearningProperties props, CfvmBanditStore store) {
        this(props, store, null);
    }

    @Autowired
    public CfvmKAllocationTuner(CfvmKallocLearningProperties props,
                                CfvmBanditStore store,
                                ObjectProvider<RagFailureBlackboxService> blackboxProvider) {
        this.props = props;
        this.store = store;
        this.blackboxProvider = blackboxProvider;
    }

    /**
     * Decide a tuned KPlan.
     *
     * @param settings KAllocator settings (maxTotalK, minPerSource, kStep, recencyKeywords)
     * @param input    intent/queryText/officialOnly
     * @param cx       query complexity gate level
     * @param failures failure-pattern orchestrator (cooldown signal)
     */
    public Decision decide(KAllocator.Settings settings,
                           KAllocator.Input input,
                           QueryComplexityGate.Level cx,
                           FailurePatternOrchestrator failures) {
        return decide(settings, input, cx, failures, 0.30d, 0.30d, "MEDIUM");
    }

    public Decision decide(KAllocator.Settings settings,
                           KAllocator.Input input,
                           QueryComplexityGate.Level cx,
                           FailurePatternOrchestrator failures,
                           double valueScore,
                           double optimismScore,
                           String resourceTier) {
        if (props == null || !props.isEnabled()) {
            traceSkip("disabled");
            return null;
        }
        if (settings == null || input == null) {
            traceSkip("missing_input");
            return null;
        }
        double normalizedValueScore = clamp(valueScore, 0.0d, 1.0d);
        double normalizedOptimismScore = clamp(optimismScore, 0.0d, 1.0d);
        String normalizedTier = normalizeTier(resourceTier, normalizedValueScore);
        double optimismDamping = clamp(props.getOptimismDamping(), 0.0d, 1.0d);

        // 1) baseline heuristic (always available)
        KAllocator allocator = new KAllocator(settings);
        KAllocator.KPlan base = allocator.decide(input);
        if (base == null) {
            traceSkip("baseline_unavailable");
            return null;
        }

        // 2) context -> CFVM 9-tile key
        boolean recency = containsAny(input.queryText, settings.recencyKeywords);
        int tile = tileIndex(input.intent, cx, recency, input.officialSourcesOnly);
        String tileKey = "cfvm9:t" + tile;
        String ctxStr = "intent=" + safe(input.intent)
                + "|cx=" + (cx == null ? "?" : cx.name())
                + "|recency=" + (recency ? "1" : "0")
                + "|official=" + (input.officialSourcesOnly ? "1" : "0")
                + "|resourceTier=" + normalizedTier
                + "|valueScore=" + normalizedValueScore
                + "|optimismScore=" + normalizedOptimismScore;

        // 3) complexity-aware totalK scaling (옵션)
        KAllocator.KPlan scaledBase = maybeScaleTotalK(base, settings, cx);

        // 4) build candidate arms around baseline
        EnumMap<Arm, KAllocator.KPlan> candidates = buildCandidates(scaledBase, settings);

        // 5) safety override: if a source is cooling down, shift budget away
        if (props.isOverrideOnCooldown() && failures != null) {
            boolean webCool = safeCooldown(failures, "web");
            boolean vecCool = safeCooldown(failures, "vector");
            boolean kgCool = safeCooldown(failures, "kg");
            if (webCool || vecCool || kgCool) {
                for (Map.Entry<Arm, KAllocator.KPlan> e : candidates.entrySet()) {
                    e.setValue(clampCooling(e.getValue(), settings, webCool, vecCool, kgCool));
                }
            }
        }

        // 6) choose arm (cold-start safe UCB1, then optional Boltzmann softmax)
        ArmSelection selection = selectArm(tileKey, candidates, normalizedValueScore, normalizedTier, optimismDamping);
        Arm chosen = selection.arm();
        KAllocator.KPlan plan = candidates.getOrDefault(chosen, scaledBase);
        plan = applyBlackboxOverride(plan, settings);

        Decision decision = new Decision(
                selection.policy(),
                tile,
                tileKey,
                chosen.name(),
                scaledBase,
                plan,
                ctxStr,
                normalizedValueScore,
                normalizedOptimismScore,
                normalizedTier,
                optimismDamping,
                selection.mode()
        );
        traceDecision(decision);
        return decision;
    }

    private KAllocator.KPlan applyBlackboxOverride(KAllocator.KPlan plan, KAllocator.Settings settings) {
        if (plan == null || settings == null) {
            return plan;
        }
        refreshBlackbox("CfvmKAllocationTuner.decide");
        Map<String, Object> trace;
        try {
            trace = TraceStore.getAll();
        } catch (Throwable ignore) {
            log.debug("[CFVM-KAlloc] fail-soft stage=trace.snapshot err=trace-failure"); trace = Map.of();
        }
        double risk = blackboxEffectiveRisk(trace);
        String action = safe(String.valueOf(trace.getOrDefault("blackbox.risk.restoreAction", "")));
        String failure = safe(String.valueOf(trace.getOrDefault("blackbox.risk.dominantFailure", "")));
        double traceAnchorPressure = traceAnchorPressure(trace);
        String traceAnchorRouteHint = traceAnchorRouteHint(trace);
        String traceAnchorAction = actionForTraceAnchor(traceAnchorRouteHint);
        if (risk < RagFailureBlackboxService.HIGH_RISK_THRESHOLD || action.isBlank() || "observe_only".equals(action)) {
            if (traceAnchorPressure >= RagFailureBlackboxService.HIGH_RISK_THRESHOLD && !traceAnchorAction.isBlank()) {
                risk = traceAnchorPressure;
                action = traceAnchorAction;
                failure = "trace_anchor";
            } else {
                traceBlackboxOverride(risk, action, failure, false, traceAnchorRouteHint, traceAnchorPressure);
                return plan;
            }
        }

        KAllocator.KPlan adjusted = plan;
        boolean applied = true;
        int step = Math.max(1, settings.kStep);
        switch (action) {
            case "disable_provider_failsoft", "web_await_bypass" ->
                    adjusted = clampCooling(plan, settings, true, false, false);
            case "vector_quarantine" ->
                    adjusted = clampCooling(plan, settings, false, true, false);
            case "anchor_compression_topup" ->
                    adjusted = normalize(applyDelta(plan, step, 0, 0), settings);
            case "brave_mode" ->
                    adjusted = normalize(applyDelta(plan, step, Math.max(1, step / 2), -step), settings);
            case "llm_route_degrade", "safe_path_bypass" -> {
                if (risk >= 0.85d) {
                    adjusted = normalize(new KAllocator.KPlan(
                            settings.minPerSource,
                            settings.minPerSource,
                            settings.minPerSource,
                            settings.maxTotalK), settings);
                } else {
                    applied = false;
                }
            }
            case "evidence_gate_strict", "cooldown_reorder" ->
                    adjusted = normalize(plan, settings);
            default -> applied = false;
        }
        traceBlackboxOverride(risk, action, failure, applied, traceAnchorRouteHint, traceAnchorPressure);
        return adjusted == null ? plan : adjusted;
    }

    private void refreshBlackbox(String where) {
        try {
            RagFailureBlackboxService service = blackboxProvider == null ? null : blackboxProvider.getIfAvailable();
            if (service != null) {
                service.currentOrRefresh(where);
            }
        } catch (Throwable ignore) {
            log.debug("[CFVM-KAlloc] fail-soft stage=blackbox.refresh err=silent-failure");
        }
    }

    private static double blackboxEffectiveRisk(Map<String, Object> trace) {
        return clamp(Math.max(
                asDouble(trace == null ? null : trace.get("blackbox.risk.riskScore")),
                asDouble(trace == null ? null : trace.get("blackbox.risk.priorityScore"))), 0.0d, 1.0d);
    }

    private static double traceAnchorPressure(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return 0.0d;
        }
        double pressure = Math.max(
                Math.max(asDouble(trace.get("ablation.traceAnchor.maxExpectedDelta")), asDouble(trace.get("ablation.traceAnchor.routeCorrectionNeed"))),
                Math.max(asDouble(trace.get("q_anchor_drop_pressure")), asDouble(trace.get("q_route_correction_need"))));
        pressure = Math.max(pressure, nestedDouble(trace, "blackbox.risk.traceAnchor", "routeCorrectionNeed"));
        pressure = Math.max(pressure, nestedDouble(trace, "blackbox.risk.traceAnchor", "expectedDelta"));
        pressure = Math.max(pressure, nestedDouble(trace, "blackbox.risk.matrix", "q_anchor_drop_pressure"));
        pressure = Math.max(pressure, nestedDouble(trace, "blackbox.risk.matrix", "q_route_correction_need"));
        pressure = Math.max(pressure, asDouble(trace.get("cfvm.recovery.failureWeight")));
        pressure = Math.max(pressure, asDouble(trace.get("cfvm.kalloc.recovery.failureWeight")));
        return clamp(pressure, 0.0d, 1.0d);
    }

    private static String traceAnchorRouteHint(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return "";
        }
        String hint = safe(String.valueOf(trace.getOrDefault("ablation.traceAnchor.topRouteHint", "")));
        if (!hint.isBlank()) {
            return hint;
        }
        hint = safe(String.valueOf(trace.getOrDefault("cfvm.recovery.routeHint", "")));
        if (!hint.isBlank()) {
            return hint;
        }
        hint = safe(String.valueOf(trace.getOrDefault("cfvm.kalloc.traceAnchor.routeHint", "")));
        if (!hint.isBlank()) {
            return hint;
        }
        return safe(nestedString(trace, "blackbox.risk.traceAnchor", "routeHint"));
    }

    private static String actionForTraceAnchor(String routeHint) {
        return switch (safe(routeHint).toLowerCase(Locale.ROOT)) {
            case "brave_mode" -> "brave_mode";
            case "fail_soft_fallback" -> "disable_provider_failsoft";
            case "recovery" -> "anchor_compression_topup";
            default -> "";
        };
    }

    private static double nestedDouble(Map<String, Object> trace, String mapKey, String valueKey) {
        Object raw = trace == null ? null : trace.get(mapKey);
        if (!(raw instanceof Map<?, ?> map)) {
            return 0.0d;
        }
        return asDouble(map.get(valueKey));
    }

    private static String nestedString(Map<String, Object> trace, String mapKey, String valueKey) {
        Object raw = trace == null ? null : trace.get(mapKey);
        if (!(raw instanceof Map<?, ?> map)) {
            return "";
        }
        Object value = map.get(valueKey);
        return value == null ? "" : String.valueOf(value);
    }

    private static void traceBlackboxOverride(double risk, String action, String failure, boolean applied,
                                              String traceAnchorRouteHint, double traceAnchorPressure) {
        try {
            String safeAction = safe(action);
            String safeFailure = safe(failure);
            String safeRouteHint = safe(traceAnchorRouteHint);
            TraceStore.put("cfvm.kalloc.blackbox.riskScore", risk);
            TraceStore.put("cfvm.kalloc.blackbox.action", safeAction);
            TraceStore.put("cfvm.kalloc.blackbox.dominantFailure", safeFailure);
            TraceStore.put("cfvm.kalloc.blackbox.applied", applied);
            TraceStore.put("cfvm.kalloc.traceAnchor.routeHint", safeRouteHint);
            TraceStore.put("cfvm.kalloc.traceAnchor.pressure", traceAnchorPressure);
            TraceStore.put("cfvm.kalloc.traceAnchor.applied", applied && traceAnchorPressure >= RagFailureBlackboxService.HIGH_RISK_THRESHOLD);
            TraceStore.put("cfvm.kalloc.recovery.applied",
                    applied
                            && traceAnchorPressure >= RagFailureBlackboxService.HIGH_RISK_THRESHOLD
                            && !safe(String.valueOf(TraceStore.get("cfvm.recovery.routeHint"))).isBlank());
        } catch (RuntimeException ignore) {
            log.debug("[CFVM-KAlloc] fail-soft stage=blackbox.trace err=silent-failure");
        }
    }

    /** Update bandit stats with a scalar reward. */
    public void feedback(String tileKey, String armName, double reward) {
        if (props == null || !props.isEnabled()) {
            return;
        }
        if (tileKey == null || tileKey.isBlank()) {
            return;
        }
        if (armName == null || armName.isBlank()) {
            armName = Arm.BASE.name();
        }
        double r = clamp(reward, props.getMinReward(), props.getMaxReward());
        store.update(tileKey, armName, r);
        try {
            TraceStore.put("cfvm.kalloc.feedback.key", SafeRedactor.traceLabelOrFallback(tileKey, "tile"));
            TraceStore.put("cfvm.kalloc.feedback.arm", SafeRedactor.traceLabelOrFallback(armName, "arm"));
            TraceStore.put("cfvm.kalloc.feedback.reward", r);
        } catch (RuntimeException ignore) {
            log.debug("[CFVM-KAlloc] fail-soft stage=feedback.trace err=silent-failure");
        }
    }

    // === arm selection ===

    private record ArmSelection(Arm arm, String policy, String mode) {
    }

    private ArmSelection selectArm(String tileKey, EnumMap<Arm, KAllocator.KPlan> candidates,
                                   double valueScore, String resourceTier, double optimismDamping) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArmSelection(Arm.BASE, "cfvm_ucb1", "fallback");
        }
        boolean dampColdStart = "HIGH".equals(resourceTier) || "CRITICAL".equals(resourceTier);
        double bonusScale = clamp(1.0d - optimismDamping * valueScore, 0.0d, 1.0d);

        // epsilon exploration
        double eps = clamp(props.getEpsilon(), 0.0, 1.0);
        traceSelectionKnobs(eps);
        if (ThreadLocalRandom.current().nextDouble() < eps) {
            return new ArmSelection(randomArm(candidates), "cfvm_ucb1", "epsilon");
        }

        // UCB1 selection
        double c = Math.max(0.0, props.getUcbC());

        long totalN = 0L;
        for (Arm a : candidates.keySet()) {
            CfvmBanditStore.ArmStats st = store.arm(tileKey, a.name());
            totalN += Math.max(0L, st.n);
        }
        totalN = Math.max(1L, totalN);

        EnumMap<Arm, Double> scores = new EnumMap<>(Arm.class);
        Arm best = null;
        double bestScore = -Double.MAX_VALUE;
        boolean allVisited = true;
        for (Arm a : candidates.keySet()) {
            CfvmBanditStore.ArmStats st = store.arm(tileKey, a.name());
            long n = Math.max(0L, st.n);
            if (n == 0L) {
                allVisited = false;
                if (!dampColdStart) {
                    // force try unvisited arms for low/medium-value requests
                    return new ArmSelection(a, "cfvm_ucb1", "cold_start");
                }
                double coldStartScore = 0.0d + c * bonusScale;
                scores.put(a, coldStartScore);
                if (coldStartScore > bestScore) {
                    bestScore = coldStartScore;
                    best = a;
                }
                continue;
            }
            double mean = st.mean();
            double bonus = c * Math.sqrt(2.0 * Math.log((double) totalN) / (double) n);
            double score = mean + bonus * bonusScale;
            scores.put(a, score);
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        Arm fallback = best != null ? best : Arm.BASE;
        if (props.isBoltzmannEnabled() && allVisited) {
            return selectBoltzmann(scores, fallback);
        }
        return new ArmSelection(fallback, "cfvm_ucb1", "ucb1");
    }

    private ArmSelection selectBoltzmann(EnumMap<Arm, Double> scores, Arm fallback) {
        if (scores == null || scores.isEmpty()) {
            return new ArmSelection(fallback == null ? Arm.BASE : fallback, "cfvm_ucb1", "ucb1");
        }
        double temperature = clampFinite(props.getBoltzmannTemperature(), 0.0d, 10.0d);
        Arm best = fallback == null ? Arm.BASE : fallback;
        double bestScore = scores.getOrDefault(best, -Double.MAX_VALUE);
        for (Map.Entry<Arm, Double> e : scores.entrySet()) {
            double score = finiteOr(e.getValue(), -Double.MAX_VALUE);
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        if (temperature <= 0.0d) {
            traceBoltzmann(best, best, temperature, 1.0d, 0.0d);
            return new ArmSelection(best, "cfvm_boltzmann_ucb1", "boltzmann");
        }

        EnumMap<Arm, Double> probabilities = new EnumMap<>(Arm.class);
        double sum = 0.0d;
        for (Map.Entry<Arm, Double> e : scores.entrySet()) {
            double shifted = (finiteOr(e.getValue(), -Double.MAX_VALUE) - bestScore) / temperature;
            double weight = Math.exp(Math.max(-60.0d, Math.min(60.0d, shifted)));
            probabilities.put(e.getKey(), weight);
            sum += weight;
        }
        if (!(sum > 0.0d) || !Double.isFinite(sum)) {
            traceBoltzmann(best, best, temperature, 1.0d, 0.0d);
            return new ArmSelection(best, "cfvm_boltzmann_ucb1", "boltzmann");
        }

        double draw = ThreadLocalRandom.current().nextDouble();
        double cumulative = 0.0d;
        Arm chosen = best;
        double chosenProbability = 0.0d;
        double entropy = 0.0d;
        for (Map.Entry<Arm, Double> e : probabilities.entrySet()) {
            double p = e.getValue() / sum;
            probabilities.put(e.getKey(), p);
            if (p > 0.0d) {
                entropy -= p * Math.log(p);
            }
            if (chosenProbability == 0.0d && (cumulative + p) >= draw) {
                chosen = e.getKey();
                chosenProbability = p;
            }
            cumulative += p;
        }
        if (chosenProbability == 0.0d) {
            chosenProbability = probabilities.getOrDefault(chosen, 0.0d);
        }
        traceBoltzmann(chosen, best, temperature, chosenProbability, entropy);
        return new ArmSelection(chosen, "cfvm_boltzmann_ucb1", "boltzmann");
    }

    private static double finiteOr(Double value, double fallback) {
        if (value == null || !Double.isFinite(value)) {
            return fallback;
        }
        return value;
    }

    private static double clampFinite(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return clamp(value, min, max);
    }

    private static void traceBoltzmann(Arm chosen, Arm best, double temperature, double chosenProbability, double entropy) {
        TraceStore.put("cfvm.kalloc.selectionMode", "boltzmann");
        TraceStore.put("cfvm.kalloc.boltzmann.temperature", temperature);
        TraceStore.put("cfvm.kalloc.boltzmann.bestArm", best == null ? Arm.BASE.name() : best.name());
        TraceStore.put("cfvm.kalloc.boltzmann.chosenArm", chosen == null ? Arm.BASE.name() : chosen.name());
        TraceStore.put("cfvm.kalloc.chosenArm", chosen == null ? Arm.BASE.name() : chosen.name());
        TraceStore.put("cfvm.kalloc.boltzmann.chosenProbability", chosenProbability);
        TraceStore.put("cfvm.kalloc.boltzmann.entropy", entropy);
    }

    private static void traceSelectionKnobs(double epsilon) {
        try {
            TraceStore.put("cfvm.kalloc.epsilon", epsilon);
        } catch (RuntimeException ignore) {
            log.debug("[CFVM-KAlloc] fail-soft stage=selection-knobs.trace err=trace-failure");
        }
    }

    private Arm randomArm(EnumMap<Arm, KAllocator.KPlan> candidates) {
        List<Arm> arms = new ArrayList<>(candidates.keySet());
        if (arms.isEmpty()) {
            return Arm.BASE;
        }
        int idx = ThreadLocalRandom.current().nextInt(arms.size());
        return arms.get(idx);
    }

    // === candidates ===

    private EnumMap<Arm, KAllocator.KPlan> buildCandidates(KAllocator.KPlan base, KAllocator.Settings s) {
        EnumMap<Arm, KAllocator.KPlan> m = new EnumMap<>(Arm.class);
        m.put(Arm.BASE, base);

        int step = Math.max(1, s.kStep);

        // Heavier on one source, lighter on others (keeps total within maxTotalK)
        m.put(Arm.WEB_HEAVY, normalize(applyDelta(base, +step, -step / 2, -step / 2), s));
        m.put(Arm.VECTOR_HEAVY, normalize(applyDelta(base, -step / 2, +step, -step / 2), s));
        m.put(Arm.KG_HEAVY, normalize(applyDelta(base, -step / 2, -step / 2, +step), s));

        // Cost saver: minimum per source
        m.put(Arm.COST_SAVER, normalize(new KAllocator.KPlan(s.minPerSource, s.minPerSource, s.minPerSource, s.maxTotalK), s));

        return m;
    }

    private KAllocator.KPlan applyDelta(KAllocator.KPlan p, int dWeb, int dVec, int dKg) {
        if (p == null) {
            return p;
        }
        int w = p.webK + dWeb;
        int v = p.vectorK + dVec;
        int k = p.kgK + dKg;
        return new KAllocator.KPlan(w, v, k, p.poolLimit);
    }

    private static void traceSkip(String reason) {
        try {
            TraceStore.put("cfvm.kalloc.skipReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        } catch (Throwable ignore) {
            log.debug("[CFVM-KAlloc] fail-soft stage=skip.trace err=trace-failure");
        }
    }

    private static void traceDecision(Decision decision) {
        if (decision == null) {
            return;
        }
        try {
            TraceStore.put("cfvm.kalloc.policy", decision.policy());
            TraceStore.put("cfvm.kalloc.tile", decision.tile());
            TraceStore.put("cfvm.kalloc.key", decision.key());
            TraceStore.put("cfvm.kalloc.arm", decision.arm());
            TraceStore.put("cfvm.kalloc.chosenArm", decision.arm());
            TraceStore.put("cfvm.kalloc.ctxHash", SafeRedactor.hashValue(decision.ctx()));
            TraceStore.put("cfvm.kalloc.ctxLength", decision.ctx() == null ? 0 : decision.ctx().length());
            TraceStore.put("cfvm.kalloc.valueScore", decision.valueScore());
            TraceStore.put("cfvm.kalloc.optimismScore", decision.optimismScore());
            TraceStore.put("cfvm.kalloc.resourceTier", decision.resourceTier());
            TraceStore.put("cfvm.kalloc.optimismDamping", decision.optimismDamping());
            TraceStore.put("cfvm.kalloc.selectionMode", decision.selectionMode());
            putPlan("cfvm.kalloc.baseline", decision.baseline());
            putPlan("cfvm.kalloc.plan", decision.plan());
        } catch (Throwable ignore) {
            log.debug("[CFVM-KAlloc] fail-soft stage=decision.trace err=trace-failure");
        }
    }

    private static void putPlan(String prefix, KAllocator.KPlan plan) {
        if (plan == null) {
            return;
        }
        TraceStore.put(prefix + ".webK", plan.webK);
        TraceStore.put(prefix + ".vectorK", plan.vectorK);
        TraceStore.put(prefix + ".kgK", plan.kgK);
        TraceStore.put(prefix + ".poolLimit", plan.poolLimit);
    }

    /** Ensure bounds + totalK constraint. */
    private KAllocator.KPlan normalize(KAllocator.KPlan p, KAllocator.Settings s) {
        if (p == null || s == null) {
            return p;
        }
        int min = Math.max(0, s.minPerSource);
        int maxTotal = Math.max(min * 3, s.maxTotalK);

        int w = Math.max(min, p.webK);
        int v = Math.max(min, p.vectorK);
        int k = Math.max(min, p.kgK);

        int sum = w + v + k;
        if (sum > maxTotal) {
            int over = sum - maxTotal;
            // reduce from the largest buckets first, but never below min
            for (int i = 0; i < 3 && over > 0; i++) {
                if (w >= v && w >= k && w > min) {
                    int dec = Math.min(over, w - min);
                    w -= dec;
                    over -= dec;
                } else if (v >= w && v >= k && v > min) {
                    int dec = Math.min(over, v - min);
                    v -= dec;
                    over -= dec;
                } else if (k > min) {
                    int dec = Math.min(over, k - min);
                    k -= dec;
                    over -= dec;
                }
            }
        }

        return new KAllocator.KPlan(w, v, k, maxTotal);
    }

    // === cooldown override ===

    private boolean safeCooldown(FailurePatternOrchestrator failures, String source) {
        try {
            return failures.isCoolingDown(source);
        } catch (Exception ignored) {
            log.debug("[CFVM-KAlloc] fail-soft stage=cooldown.check err=silent-failure"); return false;
        }
    }

    private KAllocator.KPlan clampCooling(KAllocator.KPlan p, KAllocator.Settings s,
                                          boolean webCool,
                                          boolean vecCool,
                                          boolean kgCool) {
        if (p == null || s == null) {
            return p;
        }
        int min = Math.max(0, s.minPerSource);

        int ow = p.webK;
        int ov = p.vectorK;
        int ok = p.kgK;
        int originalSum = ow + ov + ok;

        int w = webCool ? min : ow;
        int v = vecCool ? min : ov;
        int k = kgCool ? min : ok;

        int sum = w + v + k;
        int budget = Math.min(Math.max(min * 3, s.maxTotalK), originalSum);
        int delta = budget - sum;

        // redistribute freed budget to non-cooled sources (vector -> kg -> web 순)
        if (delta > 0) {
            if (!vecCool) {
                v += delta;
                delta = 0;
            } else if (!kgCool) {
                k += delta;
                delta = 0;
            } else if (!webCool) {
                w += delta;
                delta = 0;
            }
        }

        return normalize(new KAllocator.KPlan(w, v, k, s.maxTotalK), s);
    }

    // === complexity scaling ===

    private KAllocator.KPlan maybeScaleTotalK(KAllocator.KPlan base, KAllocator.Settings s, QueryComplexityGate.Level cx) {
        if (!props.isScaleTotalKByComplexity() || base == null || s == null) {
            return base;
        }
        double scale = switch (cx == null ? QueryComplexityGate.Level.AMBIGUOUS : cx) {
            case SIMPLE -> props.getSimpleScale();
            case AMBIGUOUS -> props.getAmbiguousScale();
            case COMPLEX -> props.getComplexScale();
        };
        scale = clamp(scale, 0.25, 1.50);

        int maxTotal = Math.max(s.minPerSource * 3, s.maxTotalK);
        int target = (int) Math.round(maxTotal * scale);
        target = Math.max(s.minPerSource * 3, Math.min(maxTotal, target));

        return scaleToTotal(base, target, s);
    }

    private KAllocator.KPlan scaleToTotal(KAllocator.KPlan p, int targetTotal, KAllocator.Settings s) {
        if (p == null || s == null) {
            return p;
        }
        int min = Math.max(0, s.minPerSource);
        targetTotal = Math.max(min * 3, targetTotal);

        int w = Math.max(min, p.webK);
        int v = Math.max(min, p.vectorK);
        int k = Math.max(min, p.kgK);

        int sum = w + v + k;
        if (sum <= 0) {
            return new KAllocator.KPlan(min, min, min, targetTotal);
        }
        if (sum == targetTotal) {
            return new KAllocator.KPlan(w, v, k, targetTotal);
        }

        // proportional scaling
        double ratio = (double) targetTotal / (double) sum;
        w = Math.max(min, (int) Math.round(w * ratio));
        v = Math.max(min, (int) Math.round(v * ratio));
        k = Math.max(min, (int) Math.round(k * ratio));

        // fix rounding drift
        int drift = targetTotal - (w + v + k);
        if (drift != 0) {
            // push drift into the least cooled bias: vector -> kg -> web
            if (drift > 0) {
                v += drift;
            } else {
                // reduce from largest
                int over = -drift;
                for (int i = 0; i < 3 && over > 0; i++) {
                    if (v >= w && v >= k && v > min) {
                        int dec = Math.min(over, v - min);
                        v -= dec;
                        over -= dec;
                    } else if (k >= w && k >= v && k > min) {
                        int dec = Math.min(over, k - min);
                        k -= dec;
                        over -= dec;
                    } else if (w > min) {
                        int dec = Math.min(over, w - min);
                        w -= dec;
                        over -= dec;
                    }
                }
            }
        }

        return normalize(new KAllocator.KPlan(w, v, k, targetTotal), s);
    }

    // === tile mapping ===

    private int tileIndex(String intent, QueryComplexityGate.Level cx, boolean recency, boolean officialOnly) {
        int cxCode = switch (cx == null ? QueryComplexityGate.Level.AMBIGUOUS : cx) {
            case SIMPLE -> 0;
            case AMBIGUOUS -> 1;
            case COMPLEX -> 2;
        };
        int h = Objects.hash(safe(intent), cxCode, recency ? 1 : 0, officialOnly ? 1 : 0);
        return Math.floorMod(h, 9);
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String q = text.toLowerCase(Locale.ROOT);
        for (String k : keywords) {
            if (k == null || k.isBlank()) continue;
            if (q.contains(k.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String s) {
        return s == null ? "" : SafeRedactor.traceLabel(s);
    }

    private static String normalizeTier(String tier, double valueScore) {
        String t = tier == null ? "" : tier.trim().toUpperCase(Locale.ROOT);
        if (t.equals("LOW") || t.equals("MEDIUM") || t.equals("HIGH") || t.equals("CRITICAL")) {
            return t;
        }
        if (valueScore >= 0.90d) return "CRITICAL";
        if (valueScore >= 0.65d) return "HIGH";
        if (valueScore <= 0.25d) return "LOW";
        return "MEDIUM";
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double asDouble(Object value) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (!Double.isFinite(parsed)) {
                TraceStore.put("cfvm.kalloc.suppressed.asDouble", true);
                TraceStore.put("cfvm.kalloc.suppressed.asDouble.errorType", "invalid_number");
                return 0.0d;
            }
            return parsed;
        }
        try { double parsed = Double.parseDouble(String.valueOf(value).trim()); if (!Double.isFinite(parsed)) { throw new NumberFormatException("non-finite"); } return parsed;
        } catch (NumberFormatException ignore) {
            TraceStore.put("cfvm.kalloc.suppressed.asDouble", true);
            TraceStore.put("cfvm.kalloc.suppressed.asDouble.errorType", "invalid_number");
            return 0.0d;
        }
    }
}
