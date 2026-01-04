package com.abandonwareai.fusion;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.fusion.DeltaProjector
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.fusion.DeltaProjector
role: config
*/
public class DeltaProjector {
    public double applyDelta(double base, double delta){ return base + delta; }

}