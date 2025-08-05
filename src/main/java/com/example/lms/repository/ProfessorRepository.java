// src/main/java/com/example/lms/repository/ProfessorRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Professor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ProfessorRepository extends JpaRepository<Professor, Long> {
    // 로그인 시 이메일로 조회하기 위해
    Optional<Professor> findByEmail(String email);
    Optional<Professor> findByUsername(String username);
 //   Optional<Professor> findByLoginId(String loginId);

}
