// src/main/java/com/example/lms/service/AttendanceService.java
package com.example.lms.service;

import com.example.lms.domain.Attendance;
import com.example.lms.domain.Grade;
import com.example.lms.domain.Status;
import com.example.lms.repository.AttendanceRepository;
import com.example.lms.repository.GradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;




@Service
@Transactional
public class AttendanceService {
    private final AttendanceRepository attendanceRepo;
    private final GradeRepository gradeRepo;

    public AttendanceService(AttendanceRepository attendanceRepo,
                             GradeRepository gradeRepo) {
        this.attendanceRepo = attendanceRepo;
        this.gradeRepo = gradeRepo;
    }

    public Attendance record(Long studentId, Long courseId, LocalDate date, Status status) {
        Attendance a = new Attendance();
        a.setStudent(new com.example.lms.domain.Student(studentId));
        a.setCourse(new com.example.lms.domain.Course(courseId));
        a.setDate(date);
        a.setStatus(status);
        return attendanceRepo.save(a);
    }

    @Transactional(readOnly = true)
    public List<Attendance> getByCourse(Long courseId) {
        return attendanceRepo.findByCourse(new com.example.lms.domain.Course(courseId));
    }

    public void calculateGrades(Long courseId) {
        // 출결 점수 매핑: PRESENT=1, LATE=0.5, ABSENT=0
        List<Attendance> list = attendanceRepo.findByCourse(new com.example.lms.domain.Course(courseId));
        // 학생별 집계
        list.stream()
                .map(Attendance::getStudent)
                .distinct()
                .forEach(student -> {
                    double score = list.stream()
                            .filter(a -> a.getStudent().equals(student))
                            .mapToDouble(a -> {
                                if (a.getStatus() == Status.PRESENT) return 1;
                                if (a.getStatus() == Status.LATE) return 0.5;
                                return 0;
                            }).sum();
                    Grade g = gradeRepo.findByCourse(new com.example.lms.domain.Course(courseId)).stream()
                            .filter(gr -> gr.getStudent().equals(student))
                            .findFirst()
                            .orElse(new Grade(null, student, new com.example.lms.domain.Course(courseId), 0, 0));
                    g.setScore(score);
                    gradeRepo.save(g);
                });
        // 석차 계산
        List<Grade> grades = gradeRepo.findByCourse(new com.example.lms.domain.Course(courseId));
        grades.sort((g1,g2) -> Double.compare(g2.getScore(), g1.getScore()));
        for (int i = 0; i < grades.size(); i++) {
            grades.get(i).setRank(i+1);
            gradeRepo.save(grades.get(i));
        }
    }
}