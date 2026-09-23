package com.abandonware.ai.agent.orchestrator.recovery;

public enum FailureClass {
    NONE,
    TRANSIENT,
    LOGIC,
    DATA,
    POLICY,
    BUDGET,
    UNKNOWN
}
