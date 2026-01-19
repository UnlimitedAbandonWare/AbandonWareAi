package com.example.lms.nova.gate;

import java.util.List;



public class AutorunPreflightGate {
    public boolean allow(List<String> sources, String authorityTier) {
        return sources != null && !sources.isEmpty();
    }
}