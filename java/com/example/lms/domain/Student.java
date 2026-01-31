// src/main/java/com/example/lms/domain/Student.java
package com.example.lms.domain;

import com.example.lms.domain.Enrollment;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;



@Entity
@Table(name = "students", uniqueConstraints = {
        @UniqueConstraint(columnNames = "email", name = "uk_student_email"),
        @UniqueConstraint(columnNames = "kakao_id", name = "uk_student_kakao_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor // public no-arg constructor for form binding
@AllArgsConstructor
public class Student {

    // 편의 생성자: ID만 설정
    public Student(Long id) {
        this.id = id;
    }

    // 편의 생성자: 이름과 이메일 설정
    public Student(String name, String email) {
        this.name = name;
        this.email = email;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Builder.Default
    @Column(name = "role", nullable = false, length = 20)
    private String role = "ROLE_STUDENT";

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "kakao_id", length = 40, unique = true)
    private String kakaoId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Enrollment> enrollments = new ArrayList<>();

    // 연관관계 편의 메서드
    public void addEnrollment(Enrollment enrollment) {
        this.enrollments.add(enrollment);
        enrollment.setStudent(this);
    }
}