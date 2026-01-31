package com.abandonware.ai.agent.observability;


/** Canonical span names used across the agent. */
public final class SpanNames {
    private SpanNames() {}
    public static final String PLAN = "plan";
    public static final String RAG_RETRIEVE = "rag.retrieve";
    public static final String CRITIC = "critic";
    public static final String SYNTH = "synth";
    public static final String JOBS_ENQUEUE = "jobs.enqueue";
    public static String tool(String id) { return "tool:" + id; }
}