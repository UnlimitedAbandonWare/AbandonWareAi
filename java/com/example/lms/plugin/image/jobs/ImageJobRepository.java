package com.example.lms.plugin.image.jobs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;




/**
 * Spring Data repository for {@link ImageJob} entities.  Provides
 * convenience methods for retrieving jobs by session and status.  The
 * built-in pagination and sorting support allows efficient polling for
 * pending jobs in FIFO order.
 */
@Repository
public interface ImageJobRepository extends JpaRepository<ImageJob, String> {

    /**
     * Retrieve up to 20 jobs for the given session ID ordered by creation
     * time descending.  Used to reconstruct the job history when a client
     * reconnects.
     *
     * @param sessionId the session identifier
     * @return a list of up to 20 recent jobs for the session
     */
    List<ImageJob> findTop20BySessionIdOrderByCreatedAtDesc(String sessionId);

    /**
     * Find the oldest job in the given status ordered by creation time.
     *
     * @param status the desired job status
     * @return the first job matching the status or {@code null} if none exist
     */
    ImageJob findFirstByStatusOrderByCreatedAtAsc(ImageJob.Status status);
}