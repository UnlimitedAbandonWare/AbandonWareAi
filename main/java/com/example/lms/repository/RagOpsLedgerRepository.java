package com.example.lms.repository;

import com.example.lms.entity.RagOpsLedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RagOpsLedgerRepository extends JpaRepository<RagOpsLedgerEntry, Long> {

    List<RagOpsLedgerEntry> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(LocalDateTime since, Pageable pageable);

    @Query("""
            select e from RagOpsLedgerEntry e
            where (:entryType is null or e.entryType = :entryType)
              and (:decision is null or e.decision = :decision)
            order by e.createdAt desc
            """)
    List<RagOpsLedgerEntry> findRecent(@Param("entryType") String entryType,
                                       @Param("decision") String decision,
                                       Pageable pageable);

    List<RagOpsLedgerEntry> findTop20ByDecisionOrderByCreatedAtDesc(String decision);

    @Query(value = """
            SELECT COALESCE(hotspot, 'unknown') AS hotspot, COUNT(*) AS cnt
            FROM rag_ops_ledger
            GROUP BY COALESCE(hotspot, 'unknown')
            ORDER BY cnt DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM (
                SELECT COALESCE(hotspot, 'unknown') AS hotspot
                FROM rag_ops_ledger
                GROUP BY COALESCE(hotspot, 'unknown')
            ) hotspot_counts
            """,
            nativeQuery = true)
    Page<HotspotCountRow> countHotspotDistribution(Pageable pageable);

    interface HotspotCountRow {
        String getHotspot();

        Long getCnt();
    }
}
