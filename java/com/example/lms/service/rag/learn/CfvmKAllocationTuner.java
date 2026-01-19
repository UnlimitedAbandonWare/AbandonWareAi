package com.example.lms.service.rag.learn;

import ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator;
import com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator;
import com.example.lms.service.rag.QueryComplexityGate;
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
            String ctx
    ) {
    }

    private final CfvmKallocLearningProperties props;
    private final CfvmBanditStore store;

    public CfvmKAllocationTuner(CfvmKallocLearningProperties props, CfvmBanditStore store) {
        this.props = props;
        this.store = store;
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
        if (props == null || !props.isEnabled()) {
            return null;
        }
        if (settings == null || input == null) {
            return null;
        }

        // 1) baseline heuristic (always available)
        KAllocator allocator = new KAllocator(settings);
        KAllocator.KPlan base = allocator.decide(input);
        if (base == null) {
            return null;
        }

        // 2) context -> CFVM 9-tile key
        boolean recency = containsAny(input.queryText, settings.recencyKeywords);
        int tile = tileIndex(input.intent, cx, recency, input.officialSourcesOnly);
        String tileKey = "cfvm9:t" + tile;
        String ctxStr = "intent=" + safe(input.intent)
                + "|cx=" + (cx == null ? "?" : cx.name())
                + "|recency=" + (recency ? "1" : "0")
                + "|official=" + (input.officialSourcesOnly ? "1" : "0");

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

        // 6) choose arm (UCB1 + epsilon-greedy)
        Arm chosen = selectArm(tileKey, candidates);
        KAllocator.KPlan plan = candidates.getOrDefault(chosen, scaledBase);

        return new Decision(
                "cfvm_ucb1",
                tile,
                tileKey,
                chosen.name(),
                scaledBase,
                plan,
                ctxStr
        );
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
    }

    // === arm selection ===

    private Arm selectArm(String tileKey, EnumMap<Arm, KAllocator.KPlan> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Arm.BASE;
        }

        // epsilon exploration
        double eps = clamp(props.getEpsilon(), 0.0, 1.0);
        if (ThreadLocalRandom.current().nextDouble() < eps) {
            return randomArm(candidates);
        }

        // UCB1 selection
        double c = Math.max(0.0, props.getUcbC());

        long totalN = 0L;
        for (Arm a : candidates.keySet()) {
            CfvmBanditStore.ArmStats st = store.arm(tileKey, a.name());
            totalN += Math.max(0L, st.n);
        }
        totalN = Math.max(1L, totalN);

        Arm best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Arm a : candidates.keySet()) {
            CfvmBanditStore.ArmStats st = store.arm(tileKey, a.name());
            long n = Math.max(0L, st.n);
            if (n == 0L) {
                // force try unvisited arms
                return a;
            }
            double mean = st.mean();
            double bonus = c * Math.sqrt(2.0 * Math.log((double) totalN) / (double) n);
            double score = mean + bonus;
            if (score > bestScore) {
                bestScore = score;
                best = a;
            }
        }
        return best != null ? best : Arm.BASE;
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
            return false;
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
        return s == null ? "" : s.trim();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
