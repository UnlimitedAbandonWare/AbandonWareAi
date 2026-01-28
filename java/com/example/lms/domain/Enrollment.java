// 파일 경로:
// C:\Users\\dw-019\eclipse-workspace\\demo-1\\src\main\java\com\example\lms\\domain\Enrollment.java

package com.example.lms.domain;

import com.example.lms.domain.Course;
import com.example.lms.domain.Student;
import jakarta.persistence.*;
import java.time.LocalDateTime;



/**
 * 수강 신청(Entity) - Enrollment 테이블과 매핑
 */
@Entity
@Table(name = "ENROLLMENTS",
        uniqueConstraints = @UniqueConstraint(columnNames = {"course_id", "student_id"}))
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "enrolled_at", nullable = false)
    private LocalDateTime enrolledAt;

    /**
     * 다대일: 여러 Enrollment가 하나의 Course에 속함
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /**
     * 다대일: 여러 Enrollment가 하나의 Student에 속함
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    public Enrollment() {
        this.enrolledAt = LocalDateTime.now();
    }

    public Enrollment(Course course, Student student) {
        this.course = course;
        this.student = student;
        this.enrolledAt = LocalDateTime.now();
    }

    // --- getters / setters ---

    public Long getId() {
        return id;
    }

    public LocalDateTime getEnrolledAt() {
        return enrolledAt;
    }

    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
    }

    public Student getStudent() {
        return student;
    }

    public void setStudent(Student student) {
        this.student = student;
    }
}