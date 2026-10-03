package com.example.lms.repository;

import com.example.lms.domain.AttachmentSource;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface AttachmentSourceRepository extends JpaRepository<AttachmentSource,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AttachmentSource a where a.id = :id")
    Optional<AttachmentSource> findForUpdate(@Param("id") String id);
    List<AttachmentSource> findTop256BySessionIdAndTombstoneFalseOrderByRetainedAtAsc(String sessionId);
    List<AttachmentSource> findTop256ByTombstoneFalseAndExpiresAtLessThanEqual(long now);
}
