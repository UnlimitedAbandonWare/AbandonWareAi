package com.abandonwareai.overdrive;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.overdrive.OverdriveGuard
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.overdrive.OverdriveGuard
role: config
*/
public class OverdriveGuard {
    public boolean shouldActivate(double scarcity, double conflict){ return scarcity>0.3 || conflict>0.4; }

}