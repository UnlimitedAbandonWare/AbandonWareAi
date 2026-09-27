package com.example.lms.search;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;

import java.util.List;

/**
 * Per-request deadline/cancellation debug probe. Each budget-gated stage
 * appends a compact event to {@link TraceStore} under {@link #KEY} so a
 * finished or rejected request can show exactly where its budget went.
 *
 * Event format: {@code stage|remain=<ms>|event} — {@code remain=-1} when no
 * request-scoped {@link TimeBudget} exists, and {@code remain=0!} marks a
 * cooperatively cancelled budget. The timeline is bounded and fail-soft: it
 * never throws and never blocks the caller.
 */
public final class DeadlineProbe {

    public static final String KEY = "deadline.timeline";
    private static final int MAX_EVENTS = 64;

    private DeadlineProbe() {
    }

    /** Stage entry: records remaining budget at the boundary. */
    public static void enter(String stage) {
        record(stage, "enter");
    }

    /** Stage finished normally. */
    public static void finish(String stage) {
        record(stage, "finish");
    }

    /** Stage skipped without starting work; reason is the termination cause. */
    public static void skip(String stage, String reason) {
        record(stage, "skip:" + (reason == null || reason.isBlank() ? "unknown" : reason));
    }

    /** Stage cancelled (cooperative or transport-level). */
    public static void cancel(String stage) {
        record(stage, "cancelled");
    }

    private static void record(String stage, String event) {
        try {
            TimeBudget budget = TimeBudgetContext.get();
            long remaining = budget != null ? budget.remainingMillis() : -1L;
            boolean cancelled = budget != null && budget.cancelled();
            String entry = stage + "|remain=" + remaining + (cancelled ? "!" : "") + "|" + event;
            Object existing = TraceStore.context()
                    .computeIfAbsent(KEY, k -> new java.util.concurrent.CopyOnWriteArrayList<String>());
            if (existing instanceof List<?> list && list.size() < MAX_EVENTS) {
                @SuppressWarnings("unchecked")
                List<String> timeline = (List<String>) list;
                timeline.add(entry);
            }
        } catch (RuntimeException ignored) {
            // debug telemetry only — never break the request path
        }
    }

    /** Ordered events recorded so far for this request; empty when none. */
    @SuppressWarnings("unchecked")
    public static List<String> timeline() {
        Object value = TraceStore.context().get(KEY);
        return value instanceof List<?> ? (List<String>) value : List.of();
    }
}
