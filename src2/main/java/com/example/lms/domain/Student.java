// src/main/java/com/example/lms/domain/Student.java
package com.example.lms.domain;

import com.example.lms.domain.Enrollment;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "students",
        uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 20)
    private String role = "ROLE_STUDENT";

    @Column(nullable = false, length = 50)
    private String name;

    @CreationTimestamp
    private LocalDateTime createdAt;

    // ─── 여기부터 추가 ───────────────────────────
    /**
     * 카카오톡 연동용 userKey
     */
    @Column(length = 40, unique = true)
    private String kakaoId;
    // ─────────────────────────────────────────────

    @OneToMany(mappedBy = "student",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<Enrollment> enrollments = new ArrayList<>();

    public Student() {}

    public Student(Long id) {
        this.id = id;
    }

    public Student(String email, String password, String name) {
        this.email = email;
        this.password = password;
        this.name = name;
    }

    public Student(String name, String email) {
        this(email, "", name);
    }

    // ───────── Getters / Setters ─────────

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }
    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }
    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }
    public void setRole(String role) {
        this.role = role;
    }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<Enrollment> getEnrollments() {
        return enrollments;
    }
    public void setEnrollments(List<Enrollment> enrollments) {
        this.enrollments = enrollments;
    }

    // ─── kakaoId 필드 접근자 ─────────────────────
    public String getKakaoId() {
        return kakaoId;
    }
    public void setKakaoId(String kakaoId) {
        this.kakaoId = kakaoId;
    }
    // ─────────────────────────────────────────────
}
