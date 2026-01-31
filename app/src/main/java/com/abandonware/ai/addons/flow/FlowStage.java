package com.abandonware.ai.addons.flow;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.flow.FlowStage
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.addons.flow.FlowStage
role: config
*/
public enum FlowStage { PLAN, RETRIEVE, CRITICIZE, SYNTHESIZE, DELIVER }