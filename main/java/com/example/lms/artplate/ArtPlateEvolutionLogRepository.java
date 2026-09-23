package com.example.lms.artplate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ArtPlateEvolutionLogRepository extends JpaRepository<ArtPlateEvolutionLog, Long> {
    Optional<ArtPlateEvolutionLog> findTopByOrderByCreatedAtDesc();
}
