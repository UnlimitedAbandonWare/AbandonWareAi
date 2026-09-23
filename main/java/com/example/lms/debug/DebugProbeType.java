package com.example.lms.debug;

/**
 * Debug probe categories.
 *
 * <p>
 * Goal: make debugging "sharper" by letting callers tag events with an explicit
 * probe type, so the store can apply probe-specific enrichment and consumers
 * can filter quickly.
 * </p>
 */
public enum DebugProbeType {
    /** Generic instrumentation (default). */
    GENERIC,

    /**
     * HTTP inbound/outbound dump probes (headers/meta only; bodies are truncated).
     */
    HTTP,

    /** NAVER search HTTP + parsing pipeline. */
    NAVER_SEARCH,

    /** General web search pipeline (hybrid/aggregate). */
    WEB_SEARCH,

    /** Image generation job lifecycle, provider, storage, and access probes. */
    IMAGE_JOB,

    /**
     * Context propagation boundaries (MDC / sessionId / x-request-id / TraceStore).
     */
    CONTEXT_PROPAGATION,

    /** Guard context initialization / leaks. */
    GUARD_CONTEXT,

    /** Rule-break token evaluation and enforcement. */
    RULE_BREAK,

    /** Fault masking / fail-soft layers that swallow exceptions. */
    FAULT_MASK,

    /** Embedding pipeline including local/backup failover. */
    EMBEDDING,

    /** LLM provider/model guard and implicit fallbacks. */
    MODEL_GUARD,

    /** Prompt composition / PromptBuilder boundary. */
    PROMPT,

    /**
     * Orchestration / multi-step workflow (merge, commit, invariants, fallback).
     */
    ORCHESTRATION,

    /**
     * External evidence lanes such as Supabase, browser, computer-use, or
     * Superpowers context. These are supporting proof lanes, not execution
     * threads.
     */
    EXTERNAL_EVIDENCE,

    /** QueryTransformer (aux LLM) pipeline and bypass/timeout decisions. */
    QUERY_TRANSFORMER,

    /** NightmareBreaker circuit-breaker events (blank/timeout/open/half-open). */
    NIGHTMARE_BREAKER,

    /** Executor service lifecycle & cancel-shield events. */
    EXECUTOR,

    /** Reactor/WebClient pipeline (onErrorDropped, timeout noise). */
    REACTOR,

    /** AutoLearn sample validation, anomaly detection, and quarantine feedback. */
    AUTOLEARN,

    /** Agent report tooling: CFVM snapshot request. */
    AGENT_REPORT_CFVM,

    /** Agent report tooling: MoE / ArtPlate decision request. */
    AGENT_REPORT_MOE,

    /** Agent report tooling: Overdrive status request. */
    AGENT_REPORT_OVERDRIVE,

    /** Agent report tooling: HYPERNOVA fusion request. */
    AGENT_REPORT_HYPERNOVA,

    /** Agent report tooling: ExtremeZ status request. */
    AGENT_REPORT_EXTREMEZ,

    /** Agent report tooling: safety gate summary request. */
    AGENT_REPORT_GATES,

    /** Agent report tooling: TraceStore KPI request. */
    AGENT_REPORT_TRACE,

    /** Trace-memory fingerprint, virtual checkpoint, and recovery routing probes. */
    TRACE_MEMORY
}
