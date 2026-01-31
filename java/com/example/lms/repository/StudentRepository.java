
// src/main/java/com/example/lms/repository/StudentRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;



/**
 * 학생 엔티티 조회 및 저장소
 */
public interface StudentRepository extends JpaRepository<Student, Long> {

    /** 이메일로 학생 조회 */
    Optional<Student> findByEmail(String email);

    /** 카카오 ID로 학생 조회 */
    Optional<Student> findByKakaoId(String kakaoId);

    /** 이메일 중복 체크 */
    boolean existsByEmail(String email);
}