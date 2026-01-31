package com.example.lms.repository;

import com.example.lms.domain.Grade;
import com.example.lms.domain.Course;
import com.example.lms.domain.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;




@Repository
public interface GradeRepository extends JpaRepository<Grade, Long> {

    /** 특정 강좌의 모든 성적 조회 */
    List<Grade> findByCourse(Course course);

    /** 특정 학생의 모든 성적 조회 */
    List<Grade> findByStudent(Student student);

    /** 해당 강좌 ID 기준으로 점수 내림차순 정렬 조회 */
    List<Grade> findByCourseIdOrderByScoreDesc(Long courseId);

    /** 강좌 + 학생 단일 성적 조회 */
    Optional<Grade> findByCourseAndStudent(Course course, Student student);

    /** 강좌ID + 학생ID 로 조회 */
    List<Grade> findByCourseIdAndStudentId(Long courseId, Long studentId);
}