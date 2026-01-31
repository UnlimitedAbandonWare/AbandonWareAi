/* 
//* Extracted formula module for orchestration
//* Source zip: src111_merge15 - 2025-10-20T134617.846.zip
//* Source path: app/src/main/java/com/abandonware/ai/agent/service/rag/norm/IsotonicCalibrator.java
//* Extracted: 2025-10-20T15:26:37.243277Z
//*/
package com.abandonware.ai.agent.service.rag.norm;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.service.rag.norm.IsotonicCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.service.rag.norm.IsotonicCalibrator
role: config
*/
public class IsotonicCalibrator {
    public double apply(double x) { return Math.max(0.0, Math.min(1.0, x)); }
}