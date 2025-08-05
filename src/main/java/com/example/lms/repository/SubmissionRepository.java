// src/main/java/com/example/lms/repository/SubmissionRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Submission;
import com.example.lms.domain.Assignment;
import com.example.lms.domain.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Submission 엔티티 CRUD 및 조회용 JPA 리포지토리.
 * Spring Data JPA가 메서드명을 분석하여 자동 구현합니다.
 */
@Repository
public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    /** 해당 과제의 모든 제출 조회 */
    List<Submission> findByAssignment(Assignment assignment);

    /** 해당 학생의 모든 제출 조회 */
    List<Submission> findByStudent(Student student);

    /** 과제 + 학생 단일 제출 조회 (객체 버전) */
    Optional<Submission> findByAssignmentAndStudent(Assignment assignment,
                                                    Student student);

    /** 과제ID + 학생ID 단일 제출 조회 */
    Optional<Submission> findByAssignmentIdAndStudentId(Long assignmentId,
                                                        Long studentId);

    /** 존재 여부만 확인 */
    boolean existsByAssignmentIdAndStudentId(Long assignmentId,
                                             Long studentId);
}
