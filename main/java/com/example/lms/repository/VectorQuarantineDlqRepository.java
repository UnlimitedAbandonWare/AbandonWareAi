package com.example.lms.repository;

import com.example.lms.entity.VectorQuarantineDlq;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VectorQuarantineDlqRepository extends JpaRepository<VectorQuarantineDlq, Long> {

    Optional<VectorQuarantineDlq> findByDedupeKey(String dedupeKey);

    long countByStatus(VectorQuarantineDlq.Status status);

    /**
     * Conservative, portable claim building blocks.
     *
     * <p>Instead of relying on UPDATE ... ORDER BY ... LIMIT (dialect/version-sensitive in MySQL/MariaDB),
     * we lock candidate rows via SELECT ... FOR UPDATE (PESSIMISTIC_WRITE) and then UPDATE them.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select v from VectorQuarantineDlq v
            where v.status = :status
              and (v.nextAttemptAt is null or v.nextAttemptAt <= :now)
            order by v.id asc
            """)
    List<VectorQuarantineDlq> findPendingDueForUpdate(@Param("status") VectorQuarantineDlq.Status status,
                                                      @Param("now") LocalDateTime now,
                                                      Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select v from VectorQuarantineDlq v
            where v.status = :status
              and (v.lockedAt is null or v.lockedAt < :expireBefore)
            order by v.id asc
            """)
    List<VectorQuarantineDlq> findStaleInflightForUpdate(@Param("status") VectorQuarantineDlq.Status status,
                                                         @Param("expireBefore") LocalDateTime expireBefore,
                                                         Pageable pageable);

    @Query("""
            select count(v) from VectorQuarantineDlq v
            where v.status = com.example.lms.entity.VectorQuarantineDlq.Status.INFLIGHT
              and v.lockedAt < :expireBefore
            """)
    long countInflightExpired(@Param("expireBefore") LocalDateTime expireBefore);

    /**
     * Admin list projection (keeps payload/meta LOB out of list responses).
     */
    interface DlqRecordSummary {
        Long getId();

        VectorQuarantineDlq.Status getStatus();

        Integer getAttemptCount();

        LocalDateTime getNextAttemptAt();

        LocalDateTime getLastAttemptAt();

        String getLastError();

        String getOriginalSid();

        String getOriginalSidBase();

        String getQuarantineReason();

        String getQuarantineVectorId();

        String getOriginalVectorId();

        String getLockedBy();

        LocalDateTime getLockedAt();

        LocalDateTime getCreatedAt();

        LocalDateTime getUpdatedAt();
    }

    Page<DlqRecordSummary> findAllByOrderByIdDesc(Pageable pageable);

    Page<DlqRecordSummary> findByStatusOrderByIdDesc(VectorQuarantineDlq.Status status, Pageable pageable);

    Page<DlqRecordSummary> findByOriginalSidBaseOrderByIdDesc(String originalSidBase, Pageable pageable);

    Page<DlqRecordSummary> findByStatusAndOriginalSidBaseOrderByIdDesc(VectorQuarantineDlq.Status status,
                                                                       String originalSidBase,
                                                                       Pageable pageable);

    /**
     * Top reasons view (grouped by a stable “reason key”).
     * - For FAILED: usually exception class (before ':')
     * - For BLOCKED: usually guard key (poison_guard/scope_guard/...) before ':'
     */
    interface ReasonCountView {
        String getReasonKey();

        Long getCnt();

        String getSample();
    }

    @Query("""
            select
              case
                when v.lastError is null then ''
                when locate(':', v.lastError) > 0 then substring(v.lastError, 1, locate(':', v.lastError) - 1)
                else v.lastError
              end as reasonKey,
              count(v) as cnt,
              max(v.lastError) as sample
            from VectorQuarantineDlq v
            where v.status = :status
            group by
              case
                when v.lastError is null then ''
                when locate(':', v.lastError) > 0 then substring(v.lastError, 1, locate(':', v.lastError) - 1)
                else v.lastError
              end
            order by cnt desc
            """)
    List<ReasonCountView> topReasonCounts(@Param("status") VectorQuarantineDlq.Status status, Pageable pageable);
}
