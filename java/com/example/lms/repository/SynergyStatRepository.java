// src/main/java/com/example/lms/repository/SynergyStatRepository.java
package com.example.lms.repository;

import com.example.lms.domain.scoring.SynergyStat;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;



public interface SynergyStatRepository extends JpaRepository<SynergyStat, Long> {
    Optional<SynergyStat> findByDomainAndSubjectIgnoreCaseAndPartnerIgnoreCase(String domain, String subject, String partner);
}