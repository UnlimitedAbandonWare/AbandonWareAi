package com.abandonware.ai.agent.orchestrator;


/**
 * Enumerates the different node types supported by the mini orchestrator.
 * PLAN nodes are used by a planner component to analyse the user's intent,
 * TOOL nodes invoke a registered tool, CRITIC nodes perform result
 * validation and SYNTH nodes merge intermediate results into a final
 * response.  Not all node types are implemented in this minimal
 * orchestrator; unsupported types are ignored.
 */
public enum NodeType {
    PLAN,
    TOOL,
    CRITIC,
    SYNTH
}