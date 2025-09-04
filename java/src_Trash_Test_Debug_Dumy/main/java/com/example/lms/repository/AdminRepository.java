// src/main/java/com/example/lms/repository/AdminRepository.java

package com.example.lms.repository;

import com.example.lms.domain.Administrator;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AdminRepository extends JpaRepository<Administrator, Long> {

    // username을 기준으로 관리자 정보를 찾는 메서드
    Optional<Administrator> findByUsername(String username);
}