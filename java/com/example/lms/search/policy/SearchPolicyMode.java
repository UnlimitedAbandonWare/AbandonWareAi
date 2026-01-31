package com.example.lms.search.policy;

/**
 * High-level search policy mode.
 *
 * <p>This is intentionally coarse-grained. The goal is to give the orchestrator and
 * retrievers a stable knob for adjusting query planning (slicing/expansion) and
 * retrieval breadth (topK) without entangling domain-specific heuristics.
 */
public enum SearchPolicyMode {
    /** Disable policy adjustments (use planner defaults only). */
    OFF,

    /** Default balanced behaviour. */
    BALANCED,

    /** Prefer precision: fewer variants, smaller topK. */
    PRECISION,

    /** Prefer recall: more variants, larger topK. */
    RECALL,

    /** Prefer disambiguation: add clarifying variants & slightly larger breadth. */
    DISAMBIGUATE
}
