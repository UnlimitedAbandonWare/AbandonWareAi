package com.example.lms.location.repo;

import com.example.lms.location.domain.LastLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;




/**
 * Spring Data repository for the most recent location entries.  Uniqueness
 * constraints on the {@code userId} field allow safe upserts when the
 * user's location is updated.
 */
@Repository
public interface LastLocationRepository extends JpaRepository<LastLocation, Long> {
    Optional<LastLocation> findByUserId(String userId);
}