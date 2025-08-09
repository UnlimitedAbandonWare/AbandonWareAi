// src/main/java/com/example/lms/repository/StudentRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {
    // 로그인 시 이메일로 조회하기 위해
    Optional<Student> findByEmail(String email);
    /**
     * 카카오 userKey로 학생 조회
     */
    Optional<Student> findByKakaoId(String kakaoId);
}
