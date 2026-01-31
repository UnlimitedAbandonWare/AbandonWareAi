// src/main/java/com/example/lms/repository/AttendanceRepository.java
package com.example.lms.repository;

import com.example.lms.domain.Attendance;
import com.example.lms.domain.Course;
import com.example.lms.domain.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;




@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    // 강의 ID로 조회
    List<Attendance> findByCourseId(Long courseId);

    // 강의 엔티티로 조회
    List<Attendance> findByCourse(Course course);

    // 학생 엔티티로 조회
    List<Attendance> findByStudent(Student student);

    // 기간 필터 조회
    List<Attendance> findByCourseAndDateBetween(Course course, LocalDate start, LocalDate end);
}