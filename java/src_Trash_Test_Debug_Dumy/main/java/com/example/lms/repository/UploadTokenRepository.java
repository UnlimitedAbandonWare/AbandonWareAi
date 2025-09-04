// src/main/java/com/example/lms/repository/UploadTokenRepository.java
package com.example.lms.repository;

import com.example.lms.domain.UploadToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UploadTokenRepository extends JpaRepository<UploadToken, Long> {
    Optional<UploadToken> findByToken(String token);
}
