package com.abandonwareai.nova.sidetrain;

import org.springframework.stereotype.Component;

@Component
public class NeedleProbeExecutor {
    public String keyFactProbe(String originalQuestion, String answer) {
        return "Confirm the key fact extracted from: " + originalQuestion;
    }
}
