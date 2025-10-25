package com.abandonwareai.context.scoring;

import org.springframework.stereotype.Component;

@Component
public class AuthorityScorer {
    public double score(String source){ return 0.5; }

}
