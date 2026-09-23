package com.example.lms.cfvm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "cfvm_snapshot", indexes = {
        @Index(name = "idx_cfvm_snap_created", columnList = "created_at")
})
public class CfvmSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "weights_json", nullable = false, length = 1024)
    private String weightsJson;

    @Column(name = "boltzmann_temp", nullable = false)
    private double boltzmannTemp;

    @Column(name = "buffer_size", nullable = false)
    private int bufferSize;

    @Column(name = "dominant_slot", nullable = false)
    private int dominantSlot;

    @Column(name = "session_hash", length = 128)
    private String sessionHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getWeightsJson() {
        return weightsJson;
    }

    public void setWeightsJson(String weightsJson) {
        this.weightsJson = weightsJson;
    }

    public double getBoltzmannTemp() {
        return boltzmannTemp;
    }

    public void setBoltzmannTemp(double boltzmannTemp) {
        this.boltzmannTemp = boltzmannTemp;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public int getDominantSlot() {
        return dominantSlot;
    }

    public void setDominantSlot(int dominantSlot) {
        this.dominantSlot = dominantSlot;
    }

    public String getSessionHash() {
        return sessionHash;
    }

    public void setSessionHash(String sessionHash) {
        this.sessionHash = sessionHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
