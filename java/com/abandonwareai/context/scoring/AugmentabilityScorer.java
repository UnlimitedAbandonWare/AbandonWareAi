package com.abandonwareai.context.scoring;

import org.springframework.stereotype.Component;

@Component
public class AugmentabilityScorer {
    public double score(String candidate){ return 0.5; }

}