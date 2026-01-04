package com.abandonwareai.context;

import org.springframework.stereotype.Component;

@Component
public class PortfolioSynthesizer {
    public String synthesize(java.util.List<String> slices){ return String.join("\n", slices); }

}