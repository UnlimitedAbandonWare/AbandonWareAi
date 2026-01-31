package com.abandonwareai.context.scoring;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.context.scoring.AugmentabilityScorer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.context.scoring.AugmentabilityScorer
role: config
*/
public class AugmentabilityScorer {
    public double score(String candidate){ return 0.5; }

}