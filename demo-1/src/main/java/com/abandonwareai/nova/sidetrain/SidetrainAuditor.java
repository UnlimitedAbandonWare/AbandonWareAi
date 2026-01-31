package com.abandonwareai.nova.sidetrain;

import org.springframework.stereotype.Component;

@Component
public class SidetrainAuditor {
    public boolean consistent(String original, String detour, String needle) {
        // naive heuristic for demo purposes
        return detour != null && needle != null && detour.length() > 20 && needle.length() > 20;
    }
}
