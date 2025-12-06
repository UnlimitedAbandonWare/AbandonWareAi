// src/main/java/com/example/lms/repository/AssignmentRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Assignment;
import com.example.lms.domain.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;




@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    List<Assignment> findByCourse(Course course);
}