package ai.abandonware.nova.orch.router;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.LlmRouterProperties.ModelConfig;
import com.example.lms.infra.selection.SelectionCoordinate;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyException;
import com.example.lms.infra.selection.SelectionEntropyMode;
import com.example.lms.infra.selection.SelectionEntropyReason;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.telemetry.MlaBreadcrumb;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal UCB1-style chooser + cooldown health gate for llmrouter models.
 *
 * <p>Behavior:
 * <ul>
 *   <li>{@code llmrouter.<key>} -> direct mapping</li>
 *   <li>{@code llmrouter.auto} / {@code llmrouter} -> bandit pick across all models</li>
 *   <li>Failures push an arm into cooldown for {@code llmrouter.cooldown-ms}</li>
 * </ul>
 */
public class LlmRouterBandit {

    private static final Logger log = LoggerFactory.getLogger(LlmRouterBandit.class);
    private static final double UCB_TIE_EPSILON = 1.0e-12d;
    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparing(LlmRouterBandit::normalizedModelKey)
                    .thenComparing(LlmRouterBandit::normalizedProviderKey)
                    .thenComparing(candidate -> normalizeKey(candidate.key()));

    public record Selected(String key, ModelConfig cfg) {
    }

    @FunctionalInterface
    public interface RouteEligibilityFilter {
        boolean eligible(String key, ModelConfig cfg);

        static RouteEligibilityFilter always() {
            return (key, cfg) -> true;
        }
    }

    static final class Arm {
        final AtomicLong pulls = new AtomicLong(0L);
        final AtomicLong successes = new AtomicLong(0L);
        final AtomicLong lastFailAt = new AtomicLong(0L);

        boolean inCooldown(long nowMs, long cooldownMs) {
            if (cooldownMs <= 0L) {
                return false;
            }
            long lf = lastFailAt.get();
            return lf > 0L && (nowMs - lf) < cooldownMs;
        }
    }

    private final LlmRouterProperties props;
    private final ConcurrentHashMap<String, Arm> arms = new ConcurrentHashMap<>();

    public LlmRouterBandit(LlmRouterProperties props) {
        this.props = props;
    }

    /**
     * Picks an endpoint/model config for a requested logical model id.
     */
    public Selected pick(String requestedModelId) {
        return pick(requestedModelId, RouteEligibilityFilter.always(), "route:primary", 0L);
    }

    /**
     * Picks an endpoint/model config using an optional runtime eligibility filter.
     * Direct llmrouter.<key> requests keep fail-closed semantics and are not filtered
     * out before validation.
     */
    public Selected pick(String requestedModelId, RouteEligibilityFilter eligibilityFilter) {
        return pick(requestedModelId, eligibilityFilter, "route:primary", 0L);
    }

    public Selected pick(
            String requestedModelId,
            RouteEligibilityFilter eligibilityFilter,
            String actorKey,
            long attemptOrdinal) {
        return pickResolved(requestedModelId, eligibilityFilter, actorKey, attemptOrdinal);
    }

    private Selected pickResolved(
            String requestedModelId,
            RouteEligibilityFilter eligibilityFilter,
            String actorKey,
            long attemptOrdinal) {
        if (props == null || !props.isEnabled()) {
            traceSkip("disabled");
            return null;
        }
        Map<String, ModelConfig> models = props.getModels();
        if (models == null || models.isEmpty()) {
            traceSkip("no_models");
            return null;
        }

        String directKey = extractKey(requestedModelId);
        if (directKey != null) {
            ModelConfig cfg = models.get(directKey);
            if (cfg == null) {
                traceSkip("direct_model_missing");
                return null;
            }
            return selected(directKey, cfg, "direct", 0.0d, "");
        }

        if (!isAuto(requestedModelId)) {
            traceSkip("not_router_model");
            return null;
        }

        return pickAuto(
                models,
                eligibilityFilter == null ? RouteEligibilityFilter.always() : eligibilityFilter,
                actorKey,
                attemptOrdinal);
    }

    /** Records success/failure for cooldown and bandit scoring. */
    public void recordOutcome(String key, boolean success, long latencyMs) {
        recordOutcome(key, success, latencyMs, success ? LlmFailureClass.NONE : LlmFailureClass.UNKNOWN);
    }

    /** Records success/failure with failure-class aware cooldown handling. */
    public void recordOutcome(String key, boolean success, long latencyMs, LlmFailureClass failureClass) {
        if (key == null || key.isBlank()) {
            return;
        }
        Arm arm = arms.computeIfAbsent(key, k -> new Arm());
        arm.pulls.incrementAndGet();
        if (success) {
            arm.successes.incrementAndGet();
        } else if (failureClass != LlmFailureClass.CANCELLED_NEUTRAL
                && failureClass != LlmFailureClass.RESPONSE_MODEL_UNVERIFIED) {
            arm.lastFailAt.set(System.currentTimeMillis());
        }
        LlmFailureClass safeFailureClass = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
        MlaBreadcrumb.appendLlmReward(
                key,
                success,
                latencyMs,
                safeFailureClass.name().toLowerCase(java.util.Locale.ROOT));
        TraceStore.put("cihRag.ucb1Reward", success ? 1 : 0);
        TraceStore.put("llm.router.rewardSignal", success ? 1.0d : 0.0d);

        if (log.isDebugEnabled()) {
            log.debug("[llmrouter] outcome key={} success={} failureClass={} latencyMs={} pulls={} wins={} lastFailAt={}"
                    , key, success, safeFailureClass, latencyMs, arm.pulls.get(), arm.successes.get(), arm.lastFailAt.get());
        }
    }

    private Selected pickAuto(
            Map<String, ModelConfig> models,
            RouteEligibilityFilter eligibilityFilter,
            String actorKey,
            long attemptOrdinal) {
        try {
            final long now = System.currentTimeMillis();
            final long cooldownMs = Math.max(0L, props.getCooldownMs());

            List<Candidate> candidates = new ArrayList<>();

            // 1) Candidate set: enabled, weight>0 and not in cooldown.
            for (Map.Entry<String, ModelConfig> e : models.entrySet()) {
                if (e == null) {
                    continue;
                }
                String key = e.getKey();
                ModelConfig cfg = e.getValue();
                if (key == null || key.isBlank() || cfg == null) {
                    continue;
                }
                if (!cfg.isEnabled() || !hasValidWeight(cfg)) {
                    continue;
                }
                if (!eligibilityFilter.eligible(key, cfg)) {
                    continue;
                }

                Arm arm = arms.computeIfAbsent(key, k -> new Arm());
                if (arm.inCooldown(now, cooldownMs)) {
                    continue;
                }
                candidates.add(new Candidate(key, cfg, arm));
            }
            candidates.sort(CANDIDATE_ORDER);

            // 2) If all are in cooldown, ignore cooldown and use weight>0.
            if (candidates.isEmpty()) {
                for (Map.Entry<String, ModelConfig> e : models.entrySet()) {
                    if (e == null) {
                        continue;
                    }
                    String key = e.getKey();
                    ModelConfig cfg = e.getValue();
                    if (key == null || key.isBlank() || cfg == null) {
                        continue;
                    }
                    if (!cfg.isEnabled() || !hasValidWeight(cfg)) {
                        continue;
                    }
                    if (!eligibilityFilter.eligible(key, cfg)) {
                        continue;
                    }

                    Arm arm = arms.computeIfAbsent(key, k -> new Arm());
                    candidates.add(new Candidate(key, cfg, arm));
                }
                candidates.sort(CANDIDATE_ORDER);
            }

            if (candidates.isEmpty()) {
                traceSkip("no_eligible_models");
                return null;
            }

            // 3) Exploration: the first canonical never-tried arm.
            List<Candidate> untried = candidates.stream()
                    .filter(candidate -> candidate.arm.pulls.get() == 0L)
                    .toList();
            if (!untried.isEmpty()) {
                if (untried.size() > 1) {
                    recordStableTie(
                            "llm-router.cold-start-tie",
                            actorKey,
                            attemptOrdinal,
                            untried,
                            0);
                }
                Candidate first = untried.get(0);
                return selected(first.key, first.cfg, "explore", 0.0d, "");
            }

            // 4) UCB1-ish score.
            long totalPulls = 0L;
            for (Candidate c : candidates) {
                totalPulls += Math.max(1L, c.arm.pulls.get());
            }
            double logTotal = Math.log(Math.max(1d, (double) totalPulls));

            List<ScoredCandidate> scored = new ArrayList<>();
            for (Candidate c : candidates) {
                long n = Math.max(1L, c.arm.pulls.get());
                double mean = c.arm.successes.get() / (double) n;
                double bonus = Math.sqrt(2.0d * logTotal / (double) n);
                double prior = clamp01(c.cfg.getWeight()) * 0.01d; // tiny tie-breaker
                double score = mean + bonus + prior;
                if (Double.isFinite(score)) {
                    scored.add(new ScoredCandidate(c, score, n, bonus));
                }
            }

            if (scored.isEmpty()) {
                return pickWeightedRandom(candidates, actorKey, attemptOrdinal);
            }

            double highestScore = scored.stream()
                    .mapToDouble(ScoredCandidate::score)
                    .max()
                    .orElse(Double.NEGATIVE_INFINITY);
            List<ScoredCandidate> exactTies = scored.stream()
                    .filter(candidate -> Math.abs(candidate.score() - highestScore) <= UCB_TIE_EPSILON)
                    .sorted(Comparator.comparing(ScoredCandidate::candidate, CANDIDATE_ORDER))
                    .toList();
            ScoredCandidate chosen = exactTies.get(0);
            Candidate best = chosen.candidate();
            double bestScore = chosen.score();
            long bestSampleCount = chosen.sampleCount();
            double bestExplorationBonus = chosen.explorationBonus();
            if (exactTies.size() > 1) {
                recordStableTie(
                        "llm-router.ucb-tie",
                        actorKey,
                        attemptOrdinal,
                        exactTies.stream().map(ScoredCandidate::candidate).toList(),
                        0);
            }

            TraceStore.put("llm.router.arm", SafeRedactor.traceLabelOrFallback(best.key, "unknown"));
            TraceStore.put("llm.router.ucbScore", bestScore);
            TraceStore.put("llm.router.ucb1.score", bestScore);
            TraceStore.put("llm.router.arm.sampleCount", bestSampleCount);
            TraceStore.put("llm.router.arm.explorationBonus", bestExplorationBonus);
            TraceStore.put("llm.router.policy", "ucb1");
            return selected(best.key, best.cfg, "exploit", bestScore, "");
        } catch (SelectionEntropyException ex) {
            if (GuardContextHolder.getOrDefault().selectionEntropy().mode()
                    == SelectionEntropyMode.REPLAY) {
                throw ex;
            }
            traceSkip("pick_auto_error");
            log.debug("[llmrouter] pickAuto fail-soft: errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
            return null;
        } catch (Exception ex) {
            traceSkip("pick_auto_error");
            log.debug("[llmrouter] pickAuto fail-soft: errorHash={} errorLength={}",
                    com.example.lms.trace.SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
            return null;
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }

    Selected pickWeightedRandom(
            List<Candidate> candidates,
            String actorKey,
            long attemptOrdinal) {
        List<Candidate> canonicalCandidates = new ArrayList<>(candidates);
        canonicalCandidates.sort(CANDIDATE_ORDER);
        if (canonicalCandidates.isEmpty()) {
            traceSkip("no_eligible_models");
            return null;
        }

        GuardContext context = GuardContextHolder.getOrDefault();
        SelectionEntropy entropy = context.selectionEntropy();
        SelectionDecisionLedger ledger = context.selectionDecisionLedger();
        SelectionCoordinate coordinate = new SelectionCoordinate(
                "llm-router.weighted-exploration", actorKey, attemptOrdinal, 0L);
        List<String> candidateKeys = canonicalCandidates.stream()
                .map(LlmRouterBandit::ledgerKey)
                .toList();
        double total = 0d;
        for (Candidate c : canonicalCandidates) {
            total += Math.max(0d, c.cfg.getWeight());
        }

        if (total <= 0d) {
            ledger.record(
                    SelectionDecisionLedger.Lane.ROUTER,
                    coordinate,
                    candidateKeys,
                    0,
                    "zero_weight_first_stable",
                    false,
                    false);
            Candidate c = canonicalCandidates.get(0);
            return selected(c.key, c.cfg, "explore", 0.0d, "");
        }
        if (!Double.isFinite(total)) {
            throw new SelectionEntropyException(SelectionEntropyReason.DERIVATION_INVALID);
        }

        double target = entropy.unitInterval(coordinate) * total;
        int selectedIndex = weightedIndex(canonicalCandidates, target);
        ledger.record(
                SelectionDecisionLedger.Lane.ROUTER,
                coordinate,
                candidateKeys,
                selectedIndex,
                "",
                true,
                false);
        Candidate chosen = canonicalCandidates.get(selectedIndex);
        return selected(chosen.key, chosen.cfg, "explore", 0.0d, "");
    }

    List<Candidate> candidatesForTest() {
        if (props == null || !props.isEnabled() || props.getModels() == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, ModelConfig> entry : props.getModels().entrySet()) {
            if (entry == null) {
                continue;
            }
            String key = entry.getKey();
            ModelConfig cfg = entry.getValue();
            if (key == null || key.isBlank() || cfg == null
                    || !cfg.isEnabled() || !hasValidWeight(cfg)) {
                continue;
            }
            candidates.add(new Candidate(key, cfg, arms.get(key)));
        }
        candidates.sort(CANDIDATE_ORDER);
        return List.copyOf(candidates);
    }

    private static int weightedIndex(List<Candidate> candidates, double target) {
        double acc = 0d;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            acc += Math.max(0d, c.cfg.getWeight());
            if (acc >= target) {
                return i;
            }
        }
        return candidates.size() - 1;
    }

    private static void recordStableTie(
            String decisionKey,
            String actorKey,
            long attemptOrdinal,
            List<Candidate> candidates,
            int selectedIndex) {
        SelectionDecisionLedger ledger =
                GuardContextHolder.getOrDefault().selectionDecisionLedger();
        ledger.record(
                SelectionDecisionLedger.Lane.ROUTER,
                new SelectionCoordinate(decisionKey, actorKey, attemptOrdinal, 0L),
                candidates.stream().map(LlmRouterBandit::ledgerKey).toList(),
                selectedIndex,
                "",
                false,
                true);
    }

    private static String ledgerKey(Candidate candidate) {
        return normalizedModelKey(candidate) + '\0'
                + normalizedProviderKey(candidate) + '\0'
                + normalizeKey(candidate.key());
    }

    private static String normalizedModelKey(Candidate candidate) {
        return normalizeKey(candidate == null || candidate.cfg() == null
                ? null
                : candidate.cfg().getName());
    }

    private static String normalizedProviderKey(Candidate candidate) {
        return normalizeKey(candidate == null || candidate.cfg() == null
                ? null
                : candidate.cfg().getProvider());
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasValidWeight(ModelConfig cfg) {
        double weight = cfg.getWeight();
        return Double.isFinite(weight) && weight > 0.0d;
    }

    private static Selected selected(String key, ModelConfig cfg) {
        return selected(key, cfg, "direct", 0.0d, "");
    }

    private static Selected selected(String key, ModelConfig cfg, String mode, double ucbScore, String skipReason) {
        String safeKey = SafeRedactor.traceLabelOrFallback(key, "unknown");
        TraceStore.put("cihRag.routedModel", SafeRedactor.traceLabelOrFallback(key, "unknown"));
        TraceStore.put("cihRag.ucb1Reward", -1);
        TraceStore.put("llm.router.selected", safeKey);
        TraceStore.put("llm.router.mode", SafeRedactor.traceLabelOrFallback(mode, "unknown"));
        TraceStore.put("llm.router.ucb1.score", Double.isFinite(ucbScore) ? ucbScore : 0.0d);
        TraceStore.put("llm.router.skipReason", SafeRedactor.traceLabelOrFallback(skipReason, ""));
        return new Selected(key, cfg);
    }

    private static void traceSkip(String reason) {
        TraceStore.put("llm.router.skipReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        TraceStore.put("llm.router.mode", "skipped");
        TraceStore.put("llm.router.ucb1.score", 0.0d);
    }

    /**
     * @return key for direct routing, or null for non-llmrouter model ids / auto.
     */
    public static String extractKey(String modelId) {
        if (modelId == null) {
            return null;
        }
        String s = modelId.trim();
        if (!s.toLowerCase().startsWith("llmrouter.")) {
            return null;
        }
        String rest = s.substring("llmrouter.".length());
        int colon = rest.indexOf(':');
        if (colon > 0) {
            rest = rest.substring(0, colon);
        }
        rest = rest.trim();
        if (rest.isEmpty() || rest.equalsIgnoreCase("auto")) {
            return null;
        }
        return rest;
    }

    private static boolean isAuto(String modelId) {
        if (modelId == null) {
            return false;
        }
        String s = modelId.trim().toLowerCase();
        return Objects.equals(s, "llmrouter")
                || Objects.equals(s, "llmrouter.auto")
                || Objects.equals(s, "llmrouter.");
    }

    private static double clamp01(double v) {
        if (v < 0.0d) {
            return 0.0d;
        }
        if (v > 1.0d) {
            return 1.0d;
        }
        return v;
    }

    record Candidate(String key, ModelConfig cfg, Arm arm) {
    }

    private record ScoredCandidate(
            Candidate candidate,
            double score,
            long sampleCount,
            double explorationBonus) {
    }
}
