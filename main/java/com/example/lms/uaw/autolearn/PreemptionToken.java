package com.example.lms.uaw.autolearn;

/**
 * Lightweight cancellation/preemption signal.
 *
 * <p>We cannot reliably cancel an in-flight LLM call, but we can abort between steps.
 */
@FunctionalInterface
public interface PreemptionToken {
    boolean shouldAbort();
}
