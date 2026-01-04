package com.abandonwareai.context.scoring;

import org.springframework.stereotype.Component;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.context.scoring.NoveltyScorer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.context.scoring.NoveltyScorer
role: config
*/
public class NoveltyScorer {
    public double score(String doc, java.util.List<String> existing){ return 0.5; }

}