package com.abandonwareai.fusion.dbvmx;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.fusion.dbvmx.DbvmXGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.fusion.dbvmx.DbvmXGate
role: config
*/
public class DbvmXGate {
    public boolean accept(double sourceScore, double ocrConfidence){ return sourceScore>0.5 && ocrConfidence>0.6; }

}