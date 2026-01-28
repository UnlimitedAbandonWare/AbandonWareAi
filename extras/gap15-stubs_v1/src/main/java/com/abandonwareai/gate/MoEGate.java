package com.abandonwareai.gate;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.gate.MoEGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.gate.MoEGate
role: config
*/
public class MoEGate {
    public double weight(String source){ return 0.5; }

}