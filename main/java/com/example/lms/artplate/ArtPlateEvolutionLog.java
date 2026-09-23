package com.example.lms.artplate;

import com.example.lms.trace.SafeRedactor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "art_plate_evolution_log", indexes = {
        @Index(name = "idx_art_plate_evolution_created", columnList = "created_at"),
        @Index(name = "idx_art_plate_evolution_candidate", columnList = "candidate_id")
})
public class ArtPlateEvolutionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false, length = 96)
    private String candidateId;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "rollout_percent", nullable = false)
    private int rolloutPercent;

    @Column(name = "promote", nullable = false)
    private boolean promote;

    @Column(name = "reason", nullable = false, length = 96)
    private String reason;

    @Column(name = "samples", nullable = false)
    private int samples;

    @Column(name = "authority", nullable = false)
    private double authority;

    @Column(name = "novelty", nullable = false)
    private double novelty;

    @Column(name = "fusion_diversity", nullable = false)
    private double fusionDiversity;

    @Column(name = "match_score", nullable = false)
    private double matchScore;

    @Column(name = "latency_penalty", nullable = false)
    private double latencyPenalty;

    @Column(name = "error_penalty", nullable = false)
    private double errorPenalty;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ArtPlateEvolutionLog() {
    }

    static ArtPlateEvolutionLog from(
            ArtPlateEvolver.RolloutDecision decision,
            ArtPlateEvolver.ScoreCard card) {
        ArtPlateEvolutionLog log = new ArtPlateEvolutionLog();
        ArtPlateSpec candidate = decision == null ? null : decision.candidate();
        ArtPlateEvolver.ScoreCard safeCard = card == null ? ArtPlateEvolver.ScoreCard.neutral() : card;
        log.candidateId = SafeRedactor.traceLabelOrFallback(candidate == null ? null : candidate.id(), "unknown");
        log.score = clamp01(decision == null ? safeCard.composite() : decision.score());
        log.rolloutPercent = clampInt(decision == null ? 0 : decision.rolloutPercent(), 0, 100);
        log.promote = decision != null && decision.promote();
        log.reason = SafeRedactor.traceLabelOrFallback(decision == null ? "missing_decision" : decision.reason(), "unknown");
        log.samples = Math.max(0, safeCard.samples());
        log.authority = clamp01(safeCard.authority());
        log.novelty = clamp01(safeCard.novelty());
        log.fusionDiversity = clamp01(safeCard.fusionDiversity());
        log.matchScore = clamp01(safeCard.match());
        log.latencyPenalty = clamp01(safeCard.latencyPenalty());
        log.errorPenalty = clamp01(safeCard.errorPenalty());
        return log;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public double getScore() {
        return score;
    }

    public int getRolloutPercent() {
        return rolloutPercent;
    }

    public boolean isPromote() {
        return promote;
    }

    public String getReason() {
        return reason;
    }

    public int getSamples() {
        return samples;
    }

    public double getAuthority() {
        return authority;
    }

    public double getNovelty() {
        return novelty;
    }

    public double getFusionDiversity() {
        return fusionDiversity;
    }

    public double getMatchScore() {
        return matchScore;
    }

    public double getLatencyPenalty() {
        return latencyPenalty;
    }

    public double getErrorPenalty() {
        return errorPenalty;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
