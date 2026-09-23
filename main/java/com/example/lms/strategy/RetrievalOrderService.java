package com.example.lms.strategy;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.example.lms.agent.context.AgentDbContextProvider;
import com.example.lms.guard.rulebreak.RuleBreakContext;
import com.example.lms.guard.rulebreak.RuleBreakContextHolder;
import com.example.lms.guard.rulebreak.RuleBreakPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RetrievalOrderService {
    public enum Source { WEB, VECTOR, KG }

    private static final List<Source> DEFAULT_ORDER = List.of(Source.WEB, Source.VECTOR, Source.KG);
    private static final List<Source> VECTOR_FIRST = List.of(Source.VECTOR, Source.WEB, Source.KG);
    private static final List<Source> VECTOR_KG_WEB = List.of(Source.VECTOR, Source.KG, Source.WEB);
    private static final List<Source> KG_FIRST = List.of(Source.KG, Source.WEB, Source.VECTOR);
    private static final double CFVM_MIN_DOMINANT_WEIGHT = 0.50d;

    private final Object failurePatterns;

    @Autowired(required = false)
    private StrategySelectorService strategySelectorService;

    @Autowired(required = false)
    private AgentDbContextProvider agentDbContextProvider;

    @Value("${retrieval.order.mode:fixed}")
    private String mode;

    public RetrievalOrderService() {
        this((Object) null);
    }

    @Autowired
    public RetrievalOrderService(ObjectProvider<FailurePatternOrchestrator> failurePatterns) {
        this((Object) failurePatterns);
    }

    private RetrievalOrderService(Object failurePatterns) {
        this.failurePatterns = failurePatterns;
    }

    public List<Source> decideOrder(String queryText) {
        probeDbContext();
        String configured = mode == null ? "fixed" : mode.trim().toLowerCase(Locale.ROOT);
        if (configured.isBlank() || "fixed".equals(configured)) {
            return publishOrder("DEFAULT", DEFAULT_ORDER);
        }

        List<Source> planOrder = planDslOrder();
        if (planOrder != null) {
            TraceStore.put("retrievalOrder.authority.owner", "PLAN_DSL");
            TraceStore.put("retrievalOrder.authority.suppressedOwner", "MoE");
            TraceStore.put("retrievalOrder.authority.reason", "rulebreak_speed_first");
            TraceStore.put("retrievalOrder.authority.suppressedReason", "plan_dsl_preempts_strategy_selection");
            return publishOrder("PLAN_DSL", planOrder);
        }

        List<Source> guardOrder = guardOrder();
        if (guardOrder != null) {
            return publishOrder("PLAN_DSL", guardOrder);
        }

        List<Source> failPatternOrder = searchRecoveryOrder();
        if (failPatternOrder != null) {
            TraceStore.put("retrieval.order.failpattern.applied", Boolean.TRUE);
            TraceStore.put("cfvm.retrievalOrderAdjusted", Boolean.TRUE);
            TraceStore.put("cfvm.retrievalOrderDisabledReason", "");
            TraceStore.put("cfvm.recoveryPath", failPatternOrder.toString());
            return publishOrder("CFVM_FAILURE_PATTERN", failPatternOrder);
        }

        FailurePatternOrchestrator orchestrator = failurePatternOrchestrator();
        if (orchestrator == null) {
            TraceStore.put("retrieval.order.failurePatterns.available", Boolean.FALSE);
            TraceStore.put("retrieval.order.failurePatterns.reason", "failure_patterns_absent");
        } else {
            try {
                if (orchestrator.isCoolingDown("web")) {
                    TraceStore.put("retrieval.order.failpattern.cooldown.applied", Boolean.TRUE);
                    TraceStore.put("retrieval.order.failpattern.cooldown.source", "web");
                    TraceStore.put("cfvm.retrievalOrderAdjusted", Boolean.TRUE);
                    TraceStore.put("cfvm.retrievalOrderDisabledReason", "");
                    TraceStore.put("cfvm.recoveryPath", VECTOR_KG_WEB.toString());
                    return publishOrder("CFVM_FAILURE_PATTERN", VECTOR_KG_WEB);
                }
            } catch (RuntimeException ignored) {
                TraceStore.put("retrieval.order.suppressed.cooldown", true);
                TraceStore.put("retrieval.order.suppressed.cooldown.errorType", "cooldown_check_failed");
            }
        }

        List<Source> selected = strategyOrder(queryText);
        if (selected != null) {
            return selected;
        }

        List<Source> heuristic = heuristicOrder(queryText);
        return publishOrder("DEFAULT", heuristic);
    }

    public Map<Source, Integer> allocK(Map<Source, Double> reliability, int totalK, double temp) {
        EnumMap<Source, Integer> out = new EnumMap<>(Source.class);
        int budget = totalK <= 0 ? 6 : totalK;
        double temperature = temp <= 0.0d ? 0.7d : temp;
        Map<Source, Double> scores = reliability == null || reliability.isEmpty()
                ? Map.of(Source.WEB, 0.5d, Source.VECTOR, 0.35d, Source.KG, 0.15d)
                : reliability;
        double sum = scores.values().stream().mapToDouble(score -> Math.exp(score / temperature)).sum();
        int assigned = 0;
        for (Map.Entry<Source, Double> entry : scores.entrySet()) {
            int k = Math.max(1, (int) Math.round(budget * Math.exp(entry.getValue() / temperature) / sum));
            out.put(entry.getKey(), k);
            assigned += k;
        }
        while (assigned > budget && !out.isEmpty()) {
            Source source = out.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            out.put(source, out.get(source) - 1);
            assigned--;
        }
        while (assigned < budget && !out.isEmpty()) {
            Source source = out.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
            out.put(source, out.get(source) + 1);
            assigned++;
        }
        return out;
    }

    public boolean adjustFromCfvm(int activeTile, double[] weights) {
        if (weights == null || weights.length == 0) {
            TraceStore.put("retrievalOrder.cfvmSnapshotFound", Boolean.FALSE);
            TraceStore.put("cfvm.retrievalOrderAdjusted", Boolean.FALSE);
            TraceStore.put("cfvm.retrievalOrderDisabledReason", "cfvm_weights_unavailable");
            TraceStore.put("cfvm.recoveryPath", "[]");
            return false;
        }
        int dominantSlot = dominantSlot(activeTile, weights);
        double dominantWeight = weights[Math.max(0, Math.min(dominantSlot, weights.length - 1))];
        TraceStore.put("retrievalOrder.cfvmSnapshotFound", Boolean.TRUE);
        TraceStore.put("retrievalOrder.activeTile", activeTile);
        TraceStore.put("retrievalOrder.cfvmWeight", String.format(Locale.ROOT, "%.4f", dominantWeight));
        if (!Double.isFinite(dominantWeight) || dominantWeight < CFVM_MIN_DOMINANT_WEIGHT) {
            TraceStore.put("cfvm.retrievalOrderAdjusted", Boolean.FALSE);
            TraceStore.put("cfvm.retrievalOrderDisabledReason", "cfvm_weight_below_threshold");
            TraceStore.put("cfvm.recoveryPath", "[]");
            return false;
        }
        double web = laneWeight(weights, 0);
        double vector = laneWeight(weights, 1);
        double kg = laneWeight(weights, 2);
        if (!Double.isFinite(web + vector + kg)) {
            TraceStore.put("cfvm.retrievalOrderAdjusted", Boolean.FALSE);
            TraceStore.put("cfvm.retrievalOrderDisabledReason", "cfvm_weights_unavailable");
            TraceStore.put("cfvm.recoveryPath", "[]");
            return false;
        }
        List<Source> recoveryPath;
        if (dominantSlot >= 6 || (vector > web && vector >= kg)) {
            mode = "vector-first";
            recoveryPath = VECTOR_KG_WEB;
        } else if ((dominantSlot % 3) == 2 || (kg > web && kg > vector)) {
            mode = "kg-first";
            recoveryPath = KG_FIRST;
        } else {
            mode = "fixed";
            recoveryPath = DEFAULT_ORDER;
        }
        TraceStore.put("cfvm.retrievalOrderAdjusted", Boolean.TRUE);
        TraceStore.put("cfvm.retrievalOrderDisabledReason", "");
        TraceStore.put("retrievalOrder.cfvm.activeTile", activeTile);
        TraceStore.put("retrievalOrder.cfvm.dominantSlot", dominantSlot);
        TraceStore.put("cfvm.recoveryPath", recoveryPath.toString());
        TraceStore.put("cfvm.recoveryPathDisabledReason", "");
        publishOrder("CFVM", recoveryPath);
        return true;
    }

    private List<Source> planDslOrder() {
        try {
            RuleBreakContext ruleBreak = RuleBreakContextHolder.get();
            if (ruleBreak != null && ruleBreak.isValid() && ruleBreak.getPolicy() == RuleBreakPolicy.SPEED_FIRST) {
                return VECTOR_KG_WEB;
            }
        } catch (RuntimeException ignored) {
            TraceStore.put("retrieval.order.suppressed.ruleBreakContext", true);
            TraceStore.put("retrieval.order.suppressed.ruleBreakContext.errorType", "rulebreak_context_failed");
        }
        return null;
    }

    private List<Source> guardOrder() {
        try {
            GuardContext guard = GuardContextHolder.get();
            if (guard != null) {
                if (guard.isWebRateLimited() || guard.isStrikeMode() || guard.isCompressionMode() || guard.isBypassMode()) {
                    return VECTOR_KG_WEB;
                }
                if (guard.isOfficialOnly()) {
                    return DEFAULT_ORDER;
                }
            }
        } catch (RuntimeException ignored) {
            TraceStore.put("retrieval.order.suppressed.guardContext", true);
            TraceStore.put("retrieval.order.suppressed.guardContext.errorType", "guard_context_failed");
        }
        return null;
    }

    private List<Source> searchRecoveryOrder() {
        Object rawSource = TraceStore.get("failpattern.searchRecovery.source");
        String source = safeLabel(rawSource);
        if (source == null || source.isBlank()) {
            return null;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        if (!"web".equals(normalized) && !"tavily".equals(normalized)) {
            return null;
        }
        Object rawReason = TraceStore.get("failpattern.searchRecovery.reason");
        TraceStore.put("retrieval.order.failpattern.source", normalized);
        TraceStore.put("retrieval.order.failpattern.reason", safeLabel(rawReason));
        return VECTOR_KG_WEB;
    }

    private List<Source> strategyOrder(String queryText) {
        StrategySelectorService selector = strategySelectorService;
        if (selector == null) {
            return null;
        }
        try {
            StrategySelectorService.Strategy strategy = selector.selectForQuestion(queryText, null);
            if (strategy == null) {
                return null;
            }
            List<Source> order = switch (strategy) {
                case VECTOR_FIRST -> VECTOR_FIRST;
                case DEEP_DIVE_SELF_ASK -> List.of(Source.KG, Source.VECTOR, Source.WEB);
                case WEB_VECTOR_FUSION, WEB_FIRST -> DEFAULT_ORDER;
            };
            TraceStore.put("retrieval.order.strategy.selected", strategy.name());
            TraceStore.put("retrieval.order.strategy.applied", Boolean.TRUE);
            return publishOrder("MoE", order);
        } catch (RuntimeException ignored) {
            TraceStore.put("retrieval.order.strategy.failSoft", Boolean.TRUE);
            TraceStore.put("retrieval.order.strategy.reason", "strategy_selector_failed");
            return publishOrder("DEFAULT", DEFAULT_ORDER);
        }
    }

    private List<Source> heuristicOrder(String queryText) {
        String query = queryText == null ? "" : queryText.trim();
        if (query.length() >= 120) {
            return VECTOR_FIRST;
        }
        if (looksLikeFactoid(query)) {
            return KG_FIRST;
        }
        return DEFAULT_ORDER;
    }

    private void probeDbContext() {
        AgentDbContextProvider provider = agentDbContextProvider;
        if (provider == null) {
            return;
        }
        try {
            AgentDbContextProvider.MemorySnapshot memory = provider.memorySnapshot();
            Map<String, Long> counts = memory == null ? null : memory.statusCounts;
            long active = count(counts, "ACTIVE");
            long quarantined = count(counts, "QUARANTINED");
            if (active > 0L && quarantined > 0L) {
                double ratio = (double) quarantined / (double) active;
                TraceStore.put("retrieval.order.vectorQuarantineRatio", ratio);
                if (ratio >= 0.25d) {
                    TraceStore.put("retrieval.order.vectorDeprioritized", Boolean.TRUE);
                }
            }
        } catch (RuntimeException ignored) {
            TraceStore.put("retrieval.order.dbContext.failSoft", Boolean.TRUE);
            TraceStore.put("retrieval.order.dbContext.reason", "db_context_probe_failed");
        }
    }

    private FailurePatternOrchestrator failurePatternOrchestrator() {
        try {
            if (failurePatterns instanceof FailurePatternOrchestrator orchestrator) {
                return orchestrator;
            }
            if (failurePatterns instanceof ObjectProvider<?> provider) {
                Object value = provider.getIfAvailable();
                return value instanceof FailurePatternOrchestrator orchestrator ? orchestrator : null;
            }
        } catch (RuntimeException ignored) {
            TraceStore.put("retrieval.order.suppressed.cooldown", true);
            TraceStore.put("retrieval.order.suppressed.cooldown.errorType", "cooldown_check_failed");
        }
        return null;
    }

    private List<Source> publishOrder(String owner, List<Source> order) {
        List<Source> safeOrder = order == null || order.isEmpty() ? DEFAULT_ORDER : List.copyOf(order);
        Object previousOwner = TraceStore.get("retrievalOrder.lastSetBy");
        int setCount = traceInt("retrievalOrder.setCount") + 1;
        if (previousOwner != null && !String.valueOf(previousOwner).equals(owner)) {
            TraceStore.put("retrievalOrder.conflictDetected", Boolean.TRUE);
            TraceStore.put("retrievalOrder.conflictWinner", owner);
        }
        TraceStore.put("retrievalOrder.lastSetBy", owner);
        TraceStore.put("retrievalOrder.lastOrder", safeOrder.stream().map(Source::name).toList());
        TraceStore.put("retrievalOrder.lastOrderSize", safeOrder.size());
        TraceStore.put("retrievalOrder.setCount", setCount);
        return safeOrder;
    }

    private static int traceInt(String key) {
        Object raw = TraceStore.get(key);
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (RuntimeException ignored) {
            TraceStore.put("retrieval.order.suppressed.traceInt", Boolean.TRUE);
            TraceStore.put("retrieval.order.suppressed.traceInt.key", key);
            TraceStore.put("retrieval.order.suppressed.traceInt.errorType", "invalid_number");
            return 0;
        }
    }

    private static long count(Map<String, Long> counts, String key) {
        if (counts == null || key == null) {
            return 0L;
        }
        Long value = counts.get(key);
        return value == null ? 0L : Math.max(0L, value);
    }

    private static double laneWeight(double[] weights, int lane) {
        double sum = 0.0d;
        for (int i = lane; i < weights.length; i += 3) {
            double value = weights[i];
            if (!Double.isFinite(value)) {
                return Double.NaN;
            }
            sum += value;
        }
        return sum;
    }

    private static int dominantSlot(int activeTile, double[] weights) {
        if (activeTile >= 0 && activeTile < weights.length && Double.isFinite(weights[activeTile])) {
            return activeTile;
        }
        int best = 0;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < weights.length; i++) {
            double value = weights[i];
            if (Double.isFinite(value) && value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return best;
    }

    private static boolean looksLikeFactoid(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lowered = query.toLowerCase(Locale.ROOT);
        return lowered.matches(".*\\b(what|who|when|where|why|how)\\b.*")
                || (lowered.length() <= 60 && lowered.contains("?"));
    }

    private static String safeLabel(Object value) {
        if (value == null) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(value, "unknown");
    }
}
