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

    /** QueryTransformer (aux LLM) pipeline and bypass/timeout decisions. */
    QUERY_TRANSFORMER,

    /** NightmareBreaker circuit-breaker events (blank/timeout/open/half-open). */
    NIGHTMARE_BREAKER,

    /** Executor service lifecycle & cancel-shield events. */
    EXECUTOR,

    /** Reactor/WebClient pipeline (onErrorDropped, timeout noise). */
    REACTOR
}
