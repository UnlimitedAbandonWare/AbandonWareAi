package com.example.lms.repository;

import com.example.lms.entity.ApiKey;
import com.example.lms.entity.ApiKeyUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ApiKeyUsageRepository extends JpaRepository<ApiKeyUsage, Long> {
    Optional<ApiKeyUsage> findByApiKeyAndDate(ApiKey apiKey, LocalDate date);
}
