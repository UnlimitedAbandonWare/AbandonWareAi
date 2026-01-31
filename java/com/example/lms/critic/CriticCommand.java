package com.example.lms.critic;


/** Parameterized feedback for Planner after a failed run. */
public record CriticCommand(
    double minAuthority, double mmr, int webTopK, int vecTopK,
    boolean officialOnly, boolean forceDisambiguation
) {}