// 파일 경로:
// C:\Users\dw-019\eclipse-workspace\demo-1\src\main\java\com\example\lms\repository\EnrollmentRepository.java

package com.example.lms.repository;

import com.example.lms.domain.Enrollment;
import com.example.lms.domain.Course;
import com.example.lms.domain.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Enrollment 엔티티에 대한 CRUD 및 커스텀 조회
 */
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    List<Enrollment> findByCourse(Course course);
    List<Enrollment> findByStudent(Student student);
    Optional<Enrollment> findByCourseAndStudent(Course course, Student student);
}
