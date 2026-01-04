/* 
//* Extracted formula module for orchestration
//* Source zip: src111_merge15 - 2025-10-20T134617.846.zip
//* Source path: app/src/main/java/com/abandonware/ai/agent/integrations/math/BodeClamp.java
//* Extracted: 2025-10-20T15:26:37.349586Z
//*/
package com.abandonware.ai.agent.integrations.math;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.math.BodeClamp
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.math.BodeClamp
role: config
*/
public class BodeClamp {
    public double clamp(double x, double w){
        double k = Math.max(1e-6, w);
        return x / (1.0 + Math.abs(x)/k);
    }
}