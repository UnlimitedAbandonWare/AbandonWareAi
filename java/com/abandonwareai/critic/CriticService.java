package com.abandonwareai.critic;

import org.springframework.stereotype.Component;

@Component
public class CriticService {
    public boolean isLowQuality(double score, int citations){ return score<0.7 || citations<2; }

}