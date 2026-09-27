package com.example.lms.domain.path;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;




/**
 * Entity representing a reinforced navigation path. A reinforced path is
 * created when the path-conformity score exceeds a configured threshold.
 * Persisting these paths allows the system to form lightweight neural
 * representations of frequently observed trajectories.
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