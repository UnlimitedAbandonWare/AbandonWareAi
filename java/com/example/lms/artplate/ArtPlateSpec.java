package com.example.lms.artplate;

import java.util.List;



/**
 * A "Plate" describes a retrieval + prompting + routing policy bundle.
 * Minimal, immutable spec to enable MoE gating + runtime tuning.
 */
public record ArtPlateSpec(
    String id,                 // e.g., "AP1_AUTH_WEB"
    String intent,             // "chat","rag","critic-repair","summary"/* ... */
    // Retrieval knobs
    int webTopK, int vecTopK, boolean allowMemory, boolean kgOn,
    int webBudgetMs, int vecBudgetMs,
    // Filters
    java.util.List<String> domainAllow, double noveltyFloor, double authorityFloor,
    // Prompting
    boolean includeHistory, boolean includeDraft, boolean includePrevAnswer,
    // Model candidates
    java.util.List<String> modelCandidates,
    // Rerank & guard
    boolean crossEncoderOn, int minEvidence, int minDistinctSources,
    // MoE weight scaling for gate signals (authority/novelty/Fd/match)
    double wAuthority, double wNovelty, double wFd, double wMatch
) {}