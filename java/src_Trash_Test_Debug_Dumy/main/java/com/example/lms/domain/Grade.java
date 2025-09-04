package com.example.lms.domain;

import com.example.lms.domain.Course;
import com.example.lms.domain.Student;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "grades")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Grade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 학생 정보 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    /** 강좌 정보 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /** 성적 */
    private double score;

    /** 석차 */
    private int rank;
}
