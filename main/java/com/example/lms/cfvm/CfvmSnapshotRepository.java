package com.example.lms.cfvm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CfvmSnapshotRepository extends JpaRepository<CfvmSnapshot, Long> {
    Optional<CfvmSnapshot> findTopByOrderByCreatedAtDesc();
}
