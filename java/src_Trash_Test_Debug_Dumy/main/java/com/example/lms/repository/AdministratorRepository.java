// src/main/java/com/example/lms/repository/AdministratorRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Administrator;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AdministratorRepository extends JpaRepository<Administrator, Long> {
    Optional<Administrator> findByUsername(String username);
    boolean existsByUsername(String username); // ← 추가

}
