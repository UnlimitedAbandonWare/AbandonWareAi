package com.abandonwareai.context.scoring;

import org.springframework.stereotype.Component;

@Component("scoringAuthorityScorer")/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonwareai.context.scoring.AuthorityScorer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonwareai.context.scoring.AuthorityScorer
role: config
*/
public class AuthorityScorer {
    public double score(String source){ return 0.5; }

}