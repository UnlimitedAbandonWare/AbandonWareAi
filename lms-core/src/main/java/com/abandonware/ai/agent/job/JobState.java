package com.abandonware.ai.agent.job;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.job.JobState
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.job.JobState
role: config
*/
public enum JobState {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    DLQ
}