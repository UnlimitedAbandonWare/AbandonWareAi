// 파일 경로:
// C:\Users\\dw-019\eclipse-workspace\\demo-1\\src\main\java\com\example\lms\\service\EnrollmentService.java

package com.example.lms.service;

import com.example.lms.service.CourseService;
import com.example.lms.service.StudentService;
import com.example.lms.domain.Course;
import com.example.lms.domain.Student;
import com.example.lms.domain.Enrollment;
import com.example.lms.repository.EnrollmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;



/**
 * Enrollment 관련 비즈니스 로직 처리
 */
@Service
@Transactional
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final CourseService courseService;
    private final StudentService studentService;

    public EnrollmentService(EnrollmentRepository enrollmentRepository,
                             CourseService courseService,
                             StudentService studentService) {
        this.enrollmentRepository = enrollmentRepository;
        this.courseService = courseService;
        this.studentService = studentService;
    }

    /**
     * 수강 신청 (학생이 강의를 신청)
     */
    public Enrollment enroll(Long courseId, Long studentId) {
        Course course = courseService.findById(courseId);
        Student student = studentService.findById(studentId);

        // 중복 신청 방지
        enrollmentRepository.findByCourseAndStudent(course, student)
                .ifPresent(e -> { throw new IllegalStateException("이미 신청된 강의입니다."); });

        Enrollment e = new Enrollment();
        e.setCourse(course);
        e.setStudent(student);
        return enrollmentRepository.save(e);
    }

    /** 특정 강의의 수강생 목록 조회 */
    @Transactional(readOnly = true)
    public List<Enrollment> getEnrollmentsByCourse(Long courseId) {
        Course course = courseService.findById(courseId);
        return enrollmentRepository.findByCourse(course);
    }

    /** 특정 학생이 신청한 수강 목록 조회 */
    @Transactional(readOnly = true)
    public List<Enrollment> getEnrollmentsByStudent(Long studentId) {
        Student student = studentService.findById(studentId);
        return enrollmentRepository.findByStudent(student);
    }

    /** 수강 취소 */
    public void cancelEnrollment(Long enrollmentId) {
        enrollmentRepository.deleteById(enrollmentId);
    }
}