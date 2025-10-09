// src/main/java/com/example/lms/service/ExamService.java
package com.example.lms.service;

import com.example.lms.domain.Choice;
import com.example.lms.domain.Course;
import com.example.lms.domain.Exam;
import com.example.lms.domain.Grade;
import com.example.lms.domain.Question;
import com.example.lms.domain.QuestionType;
import com.example.lms.domain.Student;
import com.example.lms.repository.ExamRepository;
import com.example.lms.repository.GradeRepository;
import com.example.lms.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ExamService {

    private final ExamRepository     examRepo;
    private final QuestionRepository questionRepo;
    private final GradeRepository    gradeRepo;

    /* ───────────────────────────
       조회 메서드
       ─────────────────────────── */
    @Transactional(readOnly = true)
    public List<Exam> findByCourse(Long courseId) {
        return examRepo.findByCourseId(courseId);
    }

    @Transactional(readOnly = true)
    public Exam findById(Long id) {
        return examRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Question> findQuestions(Long examId) {
        return questionRepo.findByExamId(examId);
    }

    /* ───────────────────────────
       1. 객관식(MCQ) 자동 채점
       ─────────────────────────── */
    public void evaluateMCQ(Long examId, Long studentId, List<Long> mcqAnswers) {
        Exam   exam   = findById(examId);
        double score  = 0;

        List<Question> mcqs = questionRepo.findByExamId(examId).stream()
                .filter(q -> q.getType() == QuestionType.MCQ)
                .collect(Collectors.toList());

        for (Question q : mcqs) {
            Set<Long> correct = q.getChoices().stream()
                    .filter(Choice::isCorrect)
                    .map(Choice::getId)
                    .collect(Collectors.toSet());

            for (Long ansId : mcqAnswers) {
                if (correct.contains(ansId)) score += 1;
            }
        }
        saveExamGrade(exam, studentId, score);
    }

    /* ───────────────────────────
       2. 서술형(Essay) 자동 채점 (shim)
       ─────────────────────────── */
    public void evaluateEssay(Long examId, Long studentId, List<String> essayAnswers) {
        double additional = (essayAnswers == null || essayAnswers.isEmpty()) ? 0 : 5;
        Exam     exam     = findById(examId);
        Course   course   = exam.getCourse();
        Student  student  = new Student(studentId);

        Grade grade = findOrCreateGrade(course, student);
        grade.setScore(grade.getScore() + additional);
        gradeRepo.save(grade);
    }

    /* ───────────────────────────
       3. 종합 채점(MCQ + Essay)
       ─────────────────────────── */
    public void evaluateExam(Long examId,
                             Long studentId,
                             Map<Long, String> answers) {

        Exam   exam  = findById(examId);
        double total = 0;

        for (Question q : questionRepo.findByExamId(examId)) {
            String ans = answers.get(q.getId());

            if (q.getType() == QuestionType.MCQ) {
                /* 🎯 필드명 변경에 맞춰 getAnswerKey() 사용 */
                Set<String> correct = Set.of(q.getAnswerKey().split(","));   // ← 수정된 부분
                Set<String> select  = (ans == null || ans.isBlank())
                        ? Collections.emptySet()
                        : Set.of(ans.split(","));

                if (select.equals(correct)) total += 1;

            } else {
                // ESSAY 채점 로직을 여기에 확장
            }
        }
        saveExamGrade(exam, studentId, total);
    }

    /* ───────────────────────────
       4. Grade 보조 메서드
       ─────────────────────────── */
    private Grade findOrCreateGrade(Course course, Student student) {
        return gradeRepo.findByCourseAndStudent(course, student)
                .orElseGet(() -> {
                    Grade g = new Grade();
                    g.setCourse(course);
                    g.setStudent(student);
                    g.setScore(0);
                    return g;
                });
    }

    private void saveExamGrade(Exam exam, Long studentId, double score) {
        Course  course  = exam.getCourse();
        Student student = new Student(studentId);

        Grade grade = findOrCreateGrade(course, student);
        grade.setScore(score);
        grade.setCourse(course);
        gradeRepo.save(grade);
    }
}
