package com.example.lms.artplate;

public record PlateContext(
        boolean useWeb,
        boolean useRag,
        int sessionRecur,
        int evidenceCount,
        double authority,
        boolean noisy,
        double webGate,
        double vectorGate,
        double memoryGate,
        double recallNeed) {
}
