package com.abandonware.ai.agent.orchestrator;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.orchestrator.NodeType
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.orchestrator.NodeType
role: config
*/
public enum NodeType {
    PLAN,
    TOOL,
    CRITIC,
    SYNTH
}