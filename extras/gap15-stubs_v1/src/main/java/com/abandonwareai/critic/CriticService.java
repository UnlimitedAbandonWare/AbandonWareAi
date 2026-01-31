package com.abandonwareai.critic;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.critic.CriticService
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.critic.CriticService
role: config
*/
public class CriticService {
    public boolean isLowQuality(double score, int citations){ return score<0.7 || citations<2; }

}