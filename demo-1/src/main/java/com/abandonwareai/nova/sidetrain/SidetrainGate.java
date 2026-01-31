package com.abandonwareai.nova.sidetrain;

import org.springframework.stereotype.Component;

@Component
public class SidetrainGate {
    private final SidetrainAuditor auditor;

    public SidetrainGate(SidetrainAuditor auditor) {
        this.auditor = auditor;
    }

    public boolean passes(String originalAnswer, String detourAnswer, String needleAnswer) {
        return auditor.consistent(originalAnswer, detourAnswer, needleAnswer);
    }
}
