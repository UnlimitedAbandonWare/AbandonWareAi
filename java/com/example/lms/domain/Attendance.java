// src/main/java/com/example/lms/domain/Attendance.java
package com.example.lms.domain;

import com.example.lms.domain.Course;
import com.example.lms.domain.Status;
import com.example.lms.domain.Student;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "attendances")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id")
    private Course course;

    private LocalDate date;

    @Enumerated(EnumType.STRING)
    private Status status;

    /** id 전용 생성자 (서비스에서 new Attendance(id) 용) */
    public Attendance(Long id) {
        this.id = id;
    }
}
