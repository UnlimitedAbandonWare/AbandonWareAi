package ai.abandonware.nova.orch.router;

import ai.abandonware.nova.config.LlmRouterProperties;
import ai.abandonware.nova.config.LlmRouterProperties.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
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

    public record Selected(String key, ModelConfig cfg) {
    }

    private static final class Arm {
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
        if (props == null || !props.isEnabled()) {
            return null;
        }
        Map<String, ModelConfig> models = props.getModels();
        if (models == null || models.isEmpty()) {
            return null;
        }

        String directKey = extractKey(requestedModelId);
        if (directKey != null) {
            ModelConfig cfg = models.get(directKey);
            if (cfg == null) {
                return null;
            }
            return new Selected(directKey, cfg);
        }

        if (!isAuto(requestedModelId)) {
            return null;
        }

        return pickAuto(models);
    }

    /** Records success/failure for cooldown and bandit scoring. */
    public void recordOutcome(String key, boolean success, long latencyMs) {
        if (key == null || key.isBlank()) {
            return;
        }
        Arm arm = arms.computeIfAbsent(key, k -> new Arm());
        arm.pulls.incrementAndGet();
        if (success) {
            arm.successes.incrementAndGet();
        } else {
            arm.lastFailAt.set(System.currentTimeMillis());
        }

        if (log.isDebugEnabled()) {
            log.debug("[llmrouter] outcome key={} success={} latencyMs={} pulls={} wins={} lastFailAt={}"
                    , key, success, latencyMs, arm.pulls.get(), arm.successes.get(), arm.lastFailAt.get());
        }
    }

    private Selected pickAuto(Map<String, ModelConfig> models) {
        try {
            final long now = System.currentTimeMillis();
            final long cooldownMs = Math.max(0L, props.getCooldownMs());

            List<Candidate> candidates = new ArrayList<>();

            // 1) Candidate set: weight>0 and not in cooldown.
            for (Map.Entry<String, ModelConfig> e : models.entrySet()) {
                if (e == null) {
                    continue;
                }
                String key = e.getKey();
                ModelConfig cfg = e.getValue();
                if (key == null || key.isBlank() || cfg == null) {
                    continue;
                }
                if (cfg.getWeight() <= 0.0d) {
                    continue;
                }

                Arm arm = arms.computeIfAbsent(key, k -> new Arm());
                if (arm.inCooldown(now, cooldownMs)) {
                    continue;
                }
                candidates.add(new Candidate(key, cfg, arm));
            }

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
                    if (cfg.getWeight() <= 0.0d) {
                        continue;
                    }

                    Arm arm = arms.computeIfAbsent(key, k -> new Arm());
                    candidates.add(new Candidate(key, cfg, arm));
                }
            }

            if (candidates.isEmpty()) {
                return null;
            }

            // 3) Exploration: any never-tried arm.
            for (Candidate c : candidates) {
                if (c.arm.pulls.get() == 0L) {
                    return new Selected(c.key, c.cfg);
                }
            }

            // 4) UCB1-ish score.
            long totalPulls = 0L;
            for (Candidate c : candidates) {
                totalPulls += Math.max(1L, c.arm.pulls.get());
            }
            double logTotal = Math.log(Math.max(1d, (double) totalPulls));

            Candidate best = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (Candidate c : candidates) {
                long n = Math.max(1L, c.arm.pulls.get());
                double mean = c.arm.successes.get() / (double) n;
                double bonus = Math.sqrt(2.0d * logTotal / (double) n);
                double prior = clamp01(c.cfg.getWeight()) * 0.01d; // tiny tie-breaker
                double score = mean + bonus + prior;

                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }

            if (best == null) {
                return pickWeightedRandom(candidates);
            }

            return new Selected(best.key, best.cfg);
        } catch (Exception ex) {
            log.debug("[llmrouter] pickAuto fail-soft: {}", ex.toString());
            return null;
        }
    }

    private Selected pickWeightedRandom(List<Candidate> candidates) {
        double total = 0d;
        for (Candidate c : candidates) {
            total += Math.max(0d, c.cfg.getWeight());
        }

        if (total <= 0d) {
            Candidate c = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            return new Selected(c.key, c.cfg);
        }

        double r = ThreadLocalRandom.current().nextDouble(total);
        double acc = 0d;
        for (Candidate c : candidates) {
            acc += Math.max(0d, c.cfg.getWeight());
            if (acc >= r) {
                return new Selected(c.key, c.cfg);
            }
        }

        Candidate last = candidates.get(candidates.size() - 1);
        return new Selected(last.key, last.cfg);
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

    private record Candidate(String key, ModelConfig cfg, Arm arm) {
    }
}
