package com.abandonwareai.nova.sidetrain;

import org.springframework.stereotype.Component;

@Component
public class DetourSynthesizer {
    public String rephrase(String q) {
        return "Could you restate and verify: " + q;
    }
}
