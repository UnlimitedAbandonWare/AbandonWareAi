package com.example.lms.repository;

import com.example.lms.domain.path.ReinforcedPath;
import org.springframework.data.repository.Repository;
import java.util.Optional;




/**
 * Minimal repository for storing reinforced navigation paths. The actual
 * implementation is provided by Spring Data JPA in production, while tests
 * may supply an in-memory variant.
 */
public interface ReinforcedPathRepository extends Repository<ReinforcedPath, Long> {
    ReinforcedPath save(ReinforcedPath path);
    Optional<ReinforcedPath> findByPath(String path);
}