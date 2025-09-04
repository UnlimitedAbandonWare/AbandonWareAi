// src/main/java/com/example/lms/domain/Course.java
package com.example.lms.domain;

import com.example.lms.domain.Attendance;
import com.example.lms.domain.Category;
import com.example.lms.domain.Enrollment;
import com.example.lms.domain.Professor;
import com.example.lms.domain.*;
import com.example.lms.domain.Grade;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "courses")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)   // ← public no-args 생성자
@AllArgsConstructor                               // 전체 필드 생성자
@Builder                                          // 빌더 지원
public class Course {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String description;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professor_id", nullable = false)
    private Professor professor;

    @Enumerated(EnumType.STRING)
    private Category category;

    private String syllabusUrl;
    private String attachmentUrl;

    @Builder.Default
    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Attendance> attendances = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Grade> grades = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Enrollment> enrollments = new ArrayList<>();

    /** 편의 생성자: ID 전용 */
    public Course(Long id) {
        this.id = id;
    }

    /** 편의 생성자: 타이틀·설명·교수 */
    public Course(String title, String description, Professor professor) {
        this.title       = title;
        this.description = description;
        this.professor   = professor;
    }

    public void addEnrollment(Enrollment e) {
        this.enrollments.add(e);
        e.setCourse(this);
    }

    public void removeEnrollment(Enrollment e) {
        this.enrollments.remove(e);
        e.setCourse(null);
    }
}
