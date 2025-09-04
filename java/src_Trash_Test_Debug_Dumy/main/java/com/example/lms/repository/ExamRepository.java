// src/main/java/com/example/lms/repository/ExamRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Exam;
import com.example.lms.domain.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExamRepository extends JpaRepository<Exam, Long> {
    List<Exam> findByCourse(Course course);
    List<Exam> findByCourseId(Long courseId);
}
