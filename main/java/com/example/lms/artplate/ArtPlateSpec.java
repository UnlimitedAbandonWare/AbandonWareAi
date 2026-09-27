package com.example.lms.artplate;

import java.util.List;

public record ArtPlateSpec(
        String id,
        String intent,
        int webTopK,
        int vecTopK,
        boolean allowMemory,
        boolean kgOn,
        int webBudgetMs,
        int vecBudgetMs,
        List<String> domainAllow,
        double noveltyFloor,
        double authorityFloor,
        boolean includeHistory,
        boolean includeDraft,
        boolean includePrevAnswer,
        List<String> modelCandidates,
        boolean crossEncoderOn,
        int minEvidence,
        int minDistinctSources,
        double wAuthority,
        double wNovelty,
        double wFd,
        double wMatch) {
}
