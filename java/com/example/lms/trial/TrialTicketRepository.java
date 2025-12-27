package com.example.lms.trial;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;




/**
 * Repository for {@link TrialTicket} entities.  Provides lookup by trial
 * identifier and supports optimistic concurrency via the version field.
 */
@Repository
public interface TrialTicketRepository extends JpaRepository<TrialTicket, Long> {
    Optional<TrialTicket> findByTrialId(String trialId);
}