// src/main/java/com/example/lms/domain/Assignment.java
package com.example.lms.domain;

import com.example.lms.domain.Course;
import com.example.lms.domain.Submission;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;



@Entity
@Table(name = "assignments")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA용 기본 생성자
@AllArgsConstructor
@Builder
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String description;

    /** LocalDate 로 통일 */
    private LocalDate dueDate;

    /** 강의(코스) 연관 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /** 이 과제에 대한 제출 목록 */
    @Builder.Default
    @OneToMany(mappedBy = "assignment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Submission> submissions = new ArrayList<>();

    /**
     * ID 전용 생성자: SubmissionQueryServiceImpl 등에서
     * new Assignment(asgId) 형태로 간단 조회할 때 사용
     */
    public Assignment(Long id) {
        this.id = id;
    }
}