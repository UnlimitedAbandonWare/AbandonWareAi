package com.abandonwareai.context.scoring;

import org.springframework.stereotype.Component;

@Component
public class NoveltyScorer {
    public double score(String doc, java.util.List<String> existing){ return 0.5; }

}