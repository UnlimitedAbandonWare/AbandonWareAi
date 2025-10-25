package com.example.lms.service.verification;


/**
 * Snapshot of collected evidence used to decide whether generation is allowed.
 * Implementations may enrich this object with detailed provenance or
 * contradiction flags.  Here we keep a simple boolean indicator.
 */
public class EvidenceSnapshot {
    private final boolean contradictory;

    /**
     * Create a new snapshot with the given contradictory flag.
     *
     * @param contradictory whether the evidence contains contradictions
     */
    public EvidenceSnapshot(boolean contradictory) {
        this.contradictory = contradictory;
    }

    /**
     * Indicates whether this evidence set contains internal contradictions.
     *
     * @return true when contradictory evidence was detected
     */
    public boolean isContradictory() {
        return contradictory;
    }
}