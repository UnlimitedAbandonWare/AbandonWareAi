package com.abandonware.ai.addons.flow;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;



public class FlowJoiner {
    private static final Logger log = Logger.getLogger(FlowJoiner.class.getName());

    private final AtomicReference<Double> p = new AtomicReference<>(1.0);
    private final AtomicReference<Double> r = new AtomicReference<>(1.0);
    private final AtomicReference<Double> c = new AtomicReference<>(1.0);
    private final AtomicReference<Double> y = new AtomicReference<>(1.0);
    private final AtomicReference<Double> k = new AtomicReference<>(1.0);

    public void mark(FlowStage stage, double prob) {
        prob = Math.max(0.0, Math.min(1.0, prob));
        switch (stage) {
            case PLAN -> p.set(prob);
            case RETRIEVE -> r.set(prob);
            case CRITICIZE -> c.set(prob);
            case SYNTHESIZE -> y.set(prob);
            case DELIVER -> k.set(prob);
        }
    }

    public FlowHealthScore score() {
        double z = 1.4*p.get() + 1.6*r.get() + 1.2*c.get() + 1.2*y.get() + 1.0*k.get() - 3.0;
        double safe = 1.0 / (1.0 + Math.exp(-z));
        return new FlowHealthScore(p.get(), r.get(), c.get(), y.get(), k.get(), safe);
    }

    public <T> T withFallback(Supplier<T> primary, Supplier<T> fallback, double threshold) {
        try {
            T val = primary.get();
            var s = score();
            log.fine(() -> "[flow] safeScore=" + s.safeScore());
            if (s.below(threshold)) {
                log.info("[flow] fallback activated due to low health");
                return fallback.get();
            }
            return val;
        } catch (RuntimeException ex) {
            log.warning("[flow] primary failed -> fallback: " + ex.getMessage());
            return fallback.get();
        }
    }
}