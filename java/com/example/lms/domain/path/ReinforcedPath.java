package com.example.lms.domain.path;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Entity recording a reinforced navigation path and its score. Stored for
 * future lookups in the big-data repository.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class ReinforcedPath {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalised representation of the discovered path. */
    @Column(nullable = false, unique = true)
    private String path;

    /** Path-conformity score that triggered reinforcement. */
    private double score;

    @Column(nullable = false)
    private Instant reinforcedAt = Instant.now();
}
