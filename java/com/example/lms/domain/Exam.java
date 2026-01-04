package com.example.lms.domain;

import com.example.lms.domain.Course;
import com.example.lms.domain.Question;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;



@Entity
@Table(name = "exams")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Exam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 시험이 속한 강의 */
    @ManyToOne(optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /** 시험 제목 */
    private String title;

    /** 시험 시작 시각 */
    private LocalDateTime startTime;

    /** 시험 종료 시각 */
    private LocalDateTime endTime;

    /** 시험 문제들 */
    @OneToMany(mappedBy = "exam", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Question> questions = new ArrayList<>();

    /**
     * id 전용 생성자: 연관 엔티티 조회 없이 id만 주입할 때 사용
     */
    public Exam(Long id) {
        this.id = id;
    }
}