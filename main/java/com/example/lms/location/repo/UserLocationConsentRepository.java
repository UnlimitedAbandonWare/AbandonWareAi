package com.example.lms.location.repo;

import com.example.lms.location.domain.UserLocationConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;




/**
 * Spring Data repository for {@link UserLocationConsent} records.  Provides
 * common CRUD operations as well as convenience finders based on the user
 * identifier.
 */
@Repository
public interface UserLocationConsentRepository extends JpaRepository<UserLocationConsent, Long> {
    Optional<UserLocationConsent> findByUserId(String userId);
}