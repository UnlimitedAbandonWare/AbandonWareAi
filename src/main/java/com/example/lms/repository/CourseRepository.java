// src/main/java/com/example/lms/repository/CourseRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Course;
import com.example.lms.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findByProfessorId(Long professorId);

    // ↓ 여기에 추가
    List<Course> findByCategory(Category category);
}
