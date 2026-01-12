// src/main/java/com/example/lms/repository/CorrectedSampleRepository.java
package com.example.lms.repository;

import com.example.lms.entity.CorrectedSample;
import org.springframework.data.jpa.repository.*;
import java.util.List;



public interface CorrectedSampleRepository
        extends JpaRepository<CorrectedSample, Long> {

    /** dirty = true 인 것만 */
    @Query("SELECT c FROM CorrectedSample c WHERE c.dirty = true")
    List<CorrectedSample> findAllUnlearned();
}