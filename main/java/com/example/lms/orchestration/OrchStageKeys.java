package com.example.lms.orchestration;

/**
 * Canonical orchestration stage keys.
 *
 * <p>These keys are used for:
 * <ul>
 *   <li>StagePolicy (OPTIONAL/CRITICAL) matching</li>
 *   <li>Parts build-up table emission (TraceStore)</li>
 * </ul>
 *
 * <p>Keep them stable: they are part of the diagnostics UX and may be referenced by external configs.
 */
public final class OrchStageKeys {
    private OrchStageKeys() {
    }

    public static final String PLAN_QUERY_PLANNER = "plan:queryPlanner";

    /** Query transformer (aux-LLM query rewriting / augmentation). */
    public static final String QUERY_TRANSFORMER = "query-transformer:auxLlm";

    public static final String RETRIEVAL_WEB = "retrieval:web";
    public static final String RETRIEVAL_VECTOR = "retrieval:vector";
    public static final String RETRIEVAL_SELF_ASK = "retrieval:selfAsk";
    public static final String RETRIEVAL_ANALYZE = "retrieval:analyze";

    public static final String RERANK_CROSS_ENCODER = "rerank:crossEncoder";
    public static final String VERIFY_FACT = "verify:factVerifier";

    public static final String GUARD_EVIDENCE = "guard:evidence";
    public static final String MEMORY_REINFORCEMENT = "memory:reinforcement";

    // UAW (background) stages
    public static final String UAW_AUTOLEARN = "uaw:autolearn";


    public static final String UAW_CURATION = "uaw:curation";
    public static final String UAW_SELF_CLEAN = "uaw:selfclean";
    public static final String BYPASS_ROUTING = "bypass:routing";
}
