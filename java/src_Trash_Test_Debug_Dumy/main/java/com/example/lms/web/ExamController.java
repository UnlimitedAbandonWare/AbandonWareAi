// src/main/java/com/example/lms/web/ExamController.java
package com.example.lms.web;

import com.example.lms.domain.Exam;
import com.example.lms.domain.Question;
import com.example.lms.service.ExamService;
import com.example.lms.service.QuestionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/exams")
public class ExamController {

    private final ExamService     examService;
    private final QuestionService questionService;

    public ExamController(ExamService examService,
                          QuestionService questionService) {
        this.examService     = examService;
        this.questionService = questionService;
    }

    /** (1) 과목별 시험 목록 */
    @GetMapping("/course/{courseId}")
    public String list(@PathVariable Long courseId, Model model) {
        model.addAttribute("exams", examService.findByCourse(courseId));
        model.addAttribute("courseId", courseId);
        return "exams/list";
    }

    /** (2) 시험 상세(읽기 전용) */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Exam exam = examService.findById(id);
        List<Question> qs = questionService.findByExam(id);
        model.addAttribute("exam", exam);
        model.addAttribute("questions", qs);
        return "exams/detail";
    }

    /** (3) 시험 응시 폼 */
    @GetMapping("/{id}/form")
    public String form(@PathVariable Long id, Model model) {
        Exam exam = examService.findById(id);
        List<Question> qs = questionService.findByExam(id);
        model.addAttribute("exam", exam);
        model.addAttribute("questions", qs);
        return "exams/form";
    }

    /**
     * (4) 시험 제출 처리
     * - MCQ 객관식(List<Long> mcqAnswers)
     * - ESSAY 서술형(List<String> essayAnswers)
     */
    @PreAuthorize("hasRole('USER')")
    @PostMapping("/{id}/submit")
    public String submit(@PathVariable Long id,
                         @RequestParam(required = false) List<Long> mcqAnswers,
                         @RequestParam(required = false) List<String> essayAnswers,
                         Authentication auth) {
        Long userId = Long.valueOf(auth.getName());
        if (mcqAnswers != null) {
            examService.evaluateMCQ(id, userId, mcqAnswers);
        }
        if (essayAnswers != null) {
            examService.evaluateEssay(id, userId, essayAnswers);
        }
        // 제출 후 과목 상세로 리다이렉트
        Long courseId = examService.findById(id).getCourse().getId();
        return "redirect:/courses/" + courseId;
    }
}
