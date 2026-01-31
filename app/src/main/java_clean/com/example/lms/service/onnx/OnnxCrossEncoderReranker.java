package com.example.lms.service.onnx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.example.lms.infra.budget.ExecutionBudget;

/**
 * Minimal, safe Cross-Encoder reranker wrapper.
 * - Applies a small concurrency gate (Semaphore)
 * - Observes a simple time budget and degrades gracefully
 * - Does not depend on ONNX runtime here (wired elsewhere)
 */
public class OnnxCrossEncoderReranker {
    private final Semaphore gate;
    private final boolean enabled;
    private final long acquireTimeoutMs;

    public OnnxCrossEncoderReranker() {
        int max = parseIntEnv("ONNX_SEMAPHORE_MAX", "onnx.semaphore.max", 2);
        this.gate = new Semaphore(Math.max(1, max));
        this.enabled = parseBoolEnv("ONNX_ENABLED", "onnx.enabled", true);
        this.acquireTimeoutMs = parseIntEnv("ONNX_ACQUIRE_TIMEOUT_MS", "onnx.acquire-timeout-ms", 50);
    }

    /** Fast-path: returns input if disabled or empty. */
    public <T> List<T> rerank(List<T> in) {
        if (in == null || in.isEmpty()) return in;

        return rerank(in, in == null ? 0 : in.size());
    }

    /** Rerank with a target topN cap. This stub just returns first N with safety gates. */
    public <T> List<T> rerank(List<T> in, int topN) {
        if (!enabled || in == null || in.isEmpty()) {
            return in == null ? java.util.Collections.emptyList() : new ArrayList<>(in.subList(0, Math.min(topN <= 0 ? in.size() : topN, in.size())));
        }
        boolean acquired = false;
        try {
            acquired = gate.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                // Busy -> degrade gracefully
                return new ArrayList<>(in.subList(0, Math.min(topN <= 0 ? in.size() : topN, in.size())));
            }
            int n = Math.min(topN <= 0 ? in.size() : topN, in.size());
            // TODO: wire actual ONNX scoring here. For now, keep stable order.
            return new ArrayList<>(in.subList(0, n));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new ArrayList<>(in.subList(0, Math.min(topN <= 0 ? in.size() : topN, in.size())));
        } finally {
            if (acquired) {
                gate.release();
            }
        }
    }

    /** Overload that observes a time budget; falls back when nearly exhausted. */
    public <T> List<T> rerankWithBudget(List<T> in, int topN, ExecutionBudget budget) {
        if (budget != null && budget.leftMillis() < acquireTimeoutMs) {
            return new ArrayList<>(in == null ? java.util.Collections.emptyList() : in.subList(0, Math.min(topN <= 0 ? in.size() : topN, in.size())));
        }
        return rerank(in, topN);
    }

    private static int parseIntEnv(String env, String prop, int def) {
        try {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) return Integer.parseInt(v.trim());
            v = System.getProperty(prop);
            if (v != null && !v.isBlank()) return Integer.parseInt(v.trim());
        } catch (Exception ignore) {}
        return def;
    }

    private static boolean parseBoolEnv(String env, String prop, boolean def) {
        try {
            String v = System.getenv(env);
            if (v != null && !v.isBlank()) return Boolean.parseBoolean(v.trim());
            v = System.getProperty(prop);
            if (v != null && !v.isBlank()) return Boolean.parseBoolean(v.trim());
        } catch (Exception ignore) {}
        return def;
    }
}