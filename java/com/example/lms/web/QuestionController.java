// src/main/java/com/example/lms/web/QuestionController.java
package com.example.lms.web;

import com.example.lms.domain.Question;
import com.example.lms.domain.QuestionType;
import com.example.lms.service.QuestionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.List;




@Controller
@RequestMapping("/exams/{examId}/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    /** (1) 문제 등록 폼 (관리자 전용) */
    @GetMapping("/new")
    @PreAuthorize("hasRole('ADMIN')")
    public String newForm(@PathVariable Long examId, Model model) {
        // 빈 Question 객체도 빌더로!
        model.addAttribute("question", Question.builder().build());
        model.addAttribute("examId", examId);
        model.addAttribute("types", QuestionType.values());
        return "questions/form";
    }

    /** (2) 객관식 문제 추가 (레거시 API) */
    @PostMapping("/add")
    @PreAuthorize("hasRole('ADMIN')")
    public String addMCQ(@PathVariable Long examId,
                         @RequestParam String content,
                         @RequestParam List<String> choices,
                         @RequestParam String answerKey) {
        questionService.addMCQ(examId, content, choices, answerKey);
        return "redirect:/exams/" + examId + "/form";
    }

    /** (3) 문제 저장 (MCQ/ESSAY 모두 지원) */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public String create(@PathVariable Long examId,
                         @ModelAttribute Question question,
                         @RequestParam(required = false) String[] choiceText,
                         @RequestParam(required = false) Boolean[] choiceCorrect) {
        // MCQ 인 경우
        if (question.getType() == QuestionType.MCQ) {
            questionService.addQuestion(examId, question, choiceText, choiceCorrect);
        } else {
            // ESSAY 문제 저장 (choice 필드 무시)
            questionService.addQuestion(examId, question, null, null);
        }
        return "redirect:/exams/" + examId + "/form";
    }
}