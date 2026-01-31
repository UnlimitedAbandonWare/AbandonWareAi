package com.example.lms.repository;

import com.example.lms.entity.VectorShadowMergeDlq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VectorShadowMergeDlqRepository extends JpaRepository<VectorShadowMergeDlq, Long> {

    Optional<VectorShadowMergeDlq> findByDedupeKey(String dedupeKey);

    long countByStatus(VectorShadowMergeDlq.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM VectorShadowMergeDlq q " +
            "WHERE q.status = com.example.lms.entity.VectorShadowMergeDlq.Status.PENDING " +
            "AND (q.nextAttemptAt IS NULL OR q.nextAttemptAt <= :now) " +
            "ORDER BY q.id ASC")
    List<VectorShadowMergeDlq> lockDue(@Param("now") LocalDateTime now,
                                      org.springframework.data.domain.Pageable pageable);
}
